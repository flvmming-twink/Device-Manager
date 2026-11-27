package ru.misis.devicemanager.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.misis.devicemanager.DeviceManagerApplication
import ru.misis.devicemanager.R
import ru.misis.devicemanager.data.Device
import ru.misis.devicemanager.data.DeviceTransferCodec
import ru.misis.devicemanager.data.DeviceTransferException
import ru.misis.devicemanager.data.TransferReply
import ru.misis.devicemanager.data.normalizedKey
import java.util.UUID
import kotlin.concurrent.thread

/** Foreground-only Bluetooth Classic transfer. No Bluetooth work continues after this screen closes. */
class BluetoothTransferActivity : DeviceActivity() {
    companion object {
        const val EXTRA_MODE = "transfer_mode"
        const val EXTRA_DEVICE_IDS = "transfer_device_ids"
        const val MODE_SEND = "send"
        const val MODE_RECEIVE = "receive"
        private const val SERVICE_NAME = "Device Manager"
        private const val SERVICE_UUID = "6eae0a17-f3ce-4f72-9e92-3d0fb8c62011"
        private const val CONNECTION_TIMEOUT_MS = 180_000
        private const val MAX_VISIBLE_DEVICES = 30
    }

    private val repository by lazy { (application as DeviceManagerApplication).repository }
    private val main = Handler(Looper.getMainLooper())
    private var mode = MODE_RECEIVE
    private var transferDevices: List<Device> = emptyList()
    private var incomingDevices: List<Device>? = null
    private var incomingDuplicates = 0
    private var localServer: BluetoothServerSocket? = null
    @Volatile private var activeSocket: BluetoothSocket? = null
    @Volatile private var transferCompleted = false
    private var receiverRegistered = false
    private var discoveryReceiver: BroadcastReceiver? = null
    private val foundDevices = linkedMapOf<String, BluetoothDevice>()

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (missingPermissions().isEmpty()) ensureBluetoothEnabled() else showFailure(getString(R.string.transfer_permissions_denied))
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && bluetoothAdapter()?.isEnabled == true) startBluetoothOperation()
        else showFailure(getString(R.string.transfer_bluetooth_disabled))
    }
    private val makeDiscoverable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            renderState(getString(R.string.receive_wait_title), getString(R.string.receive_wait_help), loading = true)
            secondary(getString(R.string.transfer_cancel)) { finishTransfer(false) }
        } else {
            closeServer()
            showFailure(getString(R.string.transfer_error))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bluetooth_transfer)
        findViewById<MaterialToolbar>(R.id.transferToolbar).setNavigationOnClickListener { finishTransfer(false) }
        mode = intent.getStringExtra(EXTRA_MODE).takeIf { it == MODE_SEND || it == MODE_RECEIVE } ?: MODE_RECEIVE
        findViewById<MaterialButton>(R.id.transferPrimary).setOnClickListener { finishTransfer(transferCompleted) }
        findViewById<MaterialButton>(R.id.transferSecondary).setOnClickListener { finishTransfer(false) }

        if (mode == MODE_SEND) {
            val ids = intent.getLongArrayExtra(EXTRA_DEVICE_IDS)?.toSet().orEmpty()
            lifecycleScope.launch {
                transferDevices = withContext(Dispatchers.IO) { repository.all().filter { it.id in ids && it.deletedAt == null } }
                if (transferDevices.isEmpty()) {
                    showFailure(getString(R.string.export_empty))
                    return@launch
                }
                if (transferDevices.size > DeviceTransferCodec.MAX_RECORDS) {
                    showFailure(getString(R.string.transfer_too_many, DeviceTransferCodec.MAX_RECORDS))
                    return@launch
                }
                renderState(getString(R.string.send_find_title),
                    getString(R.string.send_selected_count, transferDevices.size) + "\n\n" + getString(R.string.send_find_help))
                requestBluetoothAccess()
            }
        } else {
            renderState(getString(R.string.receive_wait_title), getString(R.string.receive_wait_help))
            requestBluetoothAccess()
        }
    }

    private fun requestBluetoothAccess() {
        if (bluetoothAdapter() == null) {
            showFailure(getString(R.string.transfer_bluetooth_unavailable))
            return
        }
        val missing = missingPermissions()
        if (missing.isEmpty()) ensureBluetoothEnabled() else permissionRequest.launch(missing.toTypedArray())
    }

    private fun missingPermissions(): List<String> {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            buildList {
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
                add(if (mode == MODE_SEND) android.Manifest.permission.BLUETOOTH_SCAN else android.Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        } else if (mode == MODE_SEND) listOf(android.Manifest.permission.ACCESS_FINE_LOCATION) else emptyList()
        return required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun ensureBluetoothEnabled() {
        val adapter = bluetoothAdapter() ?: run {
            showFailure(getString(R.string.transfer_bluetooth_unavailable))
            return
        }
        if (adapter.isEnabled) startBluetoothOperation()
        else enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    @Suppress("DEPRECATION")
    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission")
    private fun startBluetoothOperation() {
        if (mode == MODE_SEND) startDiscovery() else startReceiver()
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val adapter = bluetoothAdapter() ?: return showFailure(getString(R.string.transfer_bluetooth_unavailable))
        stopDiscovery()
        renderState(getString(R.string.send_find_title),
            getString(R.string.send_selected_count, transferDevices.size) + "\n\n" + getString(R.string.send_scanning), loading = true)
        secondary(getString(R.string.transfer_cancel)) { finishTransfer(false) }
        foundDevices.clear()
        try {
            adapter.bondedDevices.forEach(::showBluetoothDevice)
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            discoveryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        BluetoothDevice.ACTION_FOUND -> intent.bluetoothDevice()?.let(::showBluetoothDevice)
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            if (foundDevices.isEmpty()) {
                                renderState(getString(R.string.send_find_title), getString(R.string.send_no_devices))
                                primary(getString(R.string.retry)) { startDiscovery() }
                                secondary(getString(R.string.transfer_cancel)) { finishTransfer(false) }
                            } else {
                                findViewById<ProgressBar>(R.id.transferProgress).isVisible = false
                                findViewById<TextView>(R.id.transferStateMessage).text = getString(R.string.send_found_count, foundDevices.size)
                            }
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(this, discoveryReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
            if (!adapter.startDiscovery() && foundDevices.isEmpty()) showFailure(getString(R.string.transfer_discovery_error))
        } catch (error: SecurityException) {
            showFailure(getString(R.string.transfer_permissions_denied))
        } catch (error: Exception) {
            showFailure(getString(R.string.transfer_discovery_error))
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    @SuppressLint("MissingPermission")
    private fun showBluetoothDevice(device: BluetoothDevice) {
        val address = runCatching { device.address }.getOrNull() ?: return
        if (foundDevices.containsKey(address) || foundDevices.size >= MAX_VISIBLE_DEVICES) return
        foundDevices[address] = device
        val name = runCatching { device.name }.getOrNull()?.takeIf(String::isNotBlank) ?: address
        main.post {
            val choices = findViewById<LinearLayout>(R.id.transferChoices)
            val button = MaterialButton(this).apply {
                text = name
                isAllCaps = false
                contentDescription = getString(R.string.select_device) + ": " + name
                setOnClickListener { connectTo(device, name) }
            }
            choices.addView(button, LinearLayout.LayoutParams(-1, -2))
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice, name: String) {
        stopDiscovery()
        renderState(getString(R.string.send_connecting, name),
            getString(R.string.send_selected_count, transferDevices.size) + "\n\n" + getString(R.string.send_find_help), loading = true)
        secondary(getString(R.string.transfer_cancel)) { closeConnection(); finishTransfer(false) }
        thread(name = "device-manager-bt-send") {
            try {
                val socket = device.createRfcommSocketToServiceRecord(UUID.fromString(SERVICE_UUID))
                activeSocket = socket
                socket.connect()
                DeviceTransferCodec.write(socket.outputStream, transferDevices)
                main.post {
                    renderState(getString(R.string.send_connecting, name), getString(R.string.send_waiting_accept), loading = true)
                    secondary(getString(R.string.transfer_cancel)) { closeConnection(); finishTransfer(false) }
                }
                val reply = DeviceTransferCodec.readReply(socket.inputStream)
                if (!reply.accepted) {
                    main.post {
                        renderState(getString(R.string.transfer_error_title), getString(R.string.receive_declined))
                        primary(getString(R.string.transfer_back_to_inventory)) { finishTransfer(false) }
                    }
                } else {
                    transferCompleted = true
                    main.post {
                        renderState(getString(R.string.transfer_complete_title), getString(R.string.send_done, reply.added, reply.duplicates))
                        returnToInventorySoon()
                    }
                }
            } catch (error: Exception) {
                android.util.Log.e("DeviceManager", "Bluetooth send failed", error)
                main.post { if (!isFinishing) showFailure(readableTransferError(error)) }
            } finally { closeConnection() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startReceiver() {
        val adapter = bluetoothAdapter() ?: return showFailure(getString(R.string.transfer_bluetooth_unavailable))
        try {
            localServer = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, UUID.fromString(SERVICE_UUID))
            renderState(getString(R.string.receive_wait_title), getString(R.string.receive_wait_help), loading = true)
            secondary(getString(R.string.transfer_cancel)) { finishTransfer(false) }
            val server = localServer
            thread(name = "device-manager-bt-receive") {
                try {
                    val socket = server?.accept(CONNECTION_TIMEOUT_MS) ?: return@thread
                    activeSocket = socket
                    closeServer()
                    val remoteName = runCatching { socket.remoteDevice.name }.getOrNull()?.takeIf(String::isNotBlank) ?: getString(R.string.app_name)
                    val received = DeviceTransferCodec.read(socket.inputStream)
                    val allKnown = repository.all().map(::identity).toMutableSet()
                    val unique = received.count { allKnown.add(identity(it)) }
                    val duplicates = received.size - unique
                    main.post { showIncoming(socket, remoteName, received, duplicates) }
                } catch (error: Exception) {
                    android.util.Log.e("DeviceManager", "Bluetooth receive failed", error)
                    main.post { if (!isFinishing) showFailure(readableTransferError(error)) }
                }
            }
            val discoverable = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180)
            makeDiscoverable.launch(discoverable)
        } catch (error: SecurityException) {
            closeServer()
            showFailure(getString(R.string.transfer_permissions_denied))
        } catch (error: Exception) {
            closeServer()
            showFailure(getString(R.string.transfer_error))
        }
    }

    private fun identity(device: Device) =
        normalizedKey(device.organization) + "\u0000" + normalizedKey(device.inventoryNumber)

    private fun showIncoming(socket: BluetoothSocket, sender: String, devices: List<Device>, duplicates: Int) {
        incomingDevices = devices
        incomingDuplicates = duplicates
        val preview = devices.take(8).joinToString("\n") { "• ${it.name.take(80)} · № ${it.inventoryNumber.take(40)}" }
        val tail = if (devices.size > 8) "\n… ещё ${devices.size - 8}" else ""
        val details = getString(R.string.receive_offer_summary, devices.size, duplicates) +
            "\n\n" + getString(R.string.receive_preview_header) + "\n" + preview + tail
        renderState(getString(R.string.receive_data), getString(R.string.receive_device_found, sender))
        findViewById<TextView>(R.id.transferDetails).text = details
        primary(if (devices.size == duplicates) getString(R.string.receive_no_new)
            else getString(R.string.receive_add_count, devices.size - duplicates)) { acceptIncoming(socket, devices) }
        secondary(getString(R.string.cancel)) { declineIncoming(socket) }
    }

    private fun acceptIncoming(socket: BluetoothSocket, devices: List<Device>) {
        renderState(getString(R.string.receive_data), getString(R.string.receive_wait_title), loading = true)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repository.importNew(devices) }
                runCatching { DeviceTransferCodec.writeReply(socket.outputStream, TransferReply(true, result.added, result.duplicates)) }
                transferCompleted = true
                renderState(getString(R.string.transfer_complete_title), getString(R.string.receive_done, result.added, result.duplicates))
                closeConnection()
                returnToInventorySoon()
            } catch (error: Exception) {
                android.util.Log.e("DeviceManager", "Bluetooth import failed", error)
                runCatching { DeviceTransferCodec.writeReply(socket.outputStream, TransferReply(false)) }
                showFailure(getString(R.string.transfer_error))
            }
        }
    }

    private fun declineIncoming(socket: BluetoothSocket) {
        runCatching { DeviceTransferCodec.writeReply(socket.outputStream, TransferReply(false)) }
        closeConnection()
        renderState(getString(R.string.transfer_error_title), getString(R.string.receive_declined))
        primary(getString(R.string.transfer_back_to_inventory)) { finishTransfer(false) }
    }

    private fun returnToInventorySoon() {
        main.postDelayed({ if (!isFinishing) finishTransfer(true) }, 1_300)
    }

    private fun renderState(title: String, message: String, loading: Boolean = false) {
        findViewById<TextView>(R.id.transferStateTitle).text = title
        findViewById<TextView>(R.id.transferStateMessage).text = message
        findViewById<ProgressBar>(R.id.transferProgress).isVisible = loading
        findViewById<TextView>(R.id.transferDetails).text = ""
        findViewById<LinearLayout>(R.id.transferChoices).removeAllViews()
        findViewById<MaterialButton>(R.id.transferPrimary).isVisible = false
        findViewById<MaterialButton>(R.id.transferSecondary).isVisible = false
    }

    private fun primary(label: String, action: () -> Unit) {
        findViewById<MaterialButton>(R.id.transferPrimary).apply {
            text = label
            isVisible = true
            setOnClickListener { action() }
        }
    }

    private fun secondary(label: String, action: () -> Unit) {
        findViewById<MaterialButton>(R.id.transferSecondary).apply {
            text = label
            isVisible = true
            setOnClickListener { action() }
        }
    }

    private fun showFailure(message: String) {
        if (isFinishing) return
        renderState(getString(R.string.transfer_error_title), message)
        primary(getString(R.string.transfer_back_to_inventory)) { finishTransfer(false) }
        secondary(getString(R.string.transfer_cancel)) { finishTransfer(false) }
    }

    private fun readableTransferError(error: Exception): String = when (error) {
        is SecurityException -> getString(R.string.transfer_permissions_denied)
        is DeviceTransferException -> error.message ?: getString(R.string.transfer_error)
        else -> getString(R.string.transfer_error)
    }

    private fun finishTransfer(success: Boolean) {
        if (success) setResult(Activity.RESULT_OK) else setResult(Activity.RESULT_CANCELED)
        finish()
    }

    @SuppressLint("MissingPermission")
    private fun stopDiscovery() {
        runCatching { bluetoothAdapter()?.cancelDiscovery() }
        if (receiverRegistered) {
            runCatching { unregisterReceiver(discoveryReceiver) }
            receiverRegistered = false
        }
    }

    private fun closeServer() {
        runCatching { localServer?.close() }
        localServer = null
    }

    private fun closeConnection() {
        runCatching { activeSocket?.close() }
        activeSocket = null
    }

    override fun onDestroy() {
        stopDiscovery()
        closeServer()
        closeConnection()
        super.onDestroy()
    }
}
