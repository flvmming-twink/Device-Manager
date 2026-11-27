package ru.misis.devicemanager.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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
import ru.misis.devicemanager.data.normalizedKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** Uses Android's document picker and Sharesheet so the OS manages device-to-device transport. */
class DeviceTransferActivity : DeviceActivity() {
    companion object {
        const val EXTRA_MODE = "transfer_mode"
        const val EXTRA_DEVICE_IDS = "transfer_device_ids"
        const val MODE_SEND = "send"
        const val MODE_RECEIVE = "receive"
        private const val CACHE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val repository by lazy { (application as DeviceManagerApplication).repository }
    private var mode = MODE_RECEIVE
    private var transferDevices: List<Device> = emptyList()

    private val openTransferFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) showReceivePicker()
        else readAndPreview(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_transfer)
        findViewById<MaterialToolbar>(R.id.transferToolbar).apply {
            title = getString(R.string.transfer_title)
            setNavigationOnClickListener { finishTransfer(false) }
        }
        mode = intent.getStringExtra(EXTRA_MODE).takeIf { it == MODE_SEND || it == MODE_RECEIVE } ?: MODE_RECEIVE
        if (mode == MODE_SEND) loadSelectedDevices() else showReceivePicker()
    }

    private fun loadSelectedDevices() {
        renderState(getString(R.string.send_data), getString(R.string.transfer_prepare_file), loading = true)
        val ids = intent.getLongArrayExtra(EXTRA_DEVICE_IDS)?.toSet().orEmpty()
        lifecycleScope.launch {
            try {
                transferDevices = withContext(Dispatchers.IO) {
                    repository.all().filter { it.id in ids && it.deletedAt == null }
                }
                if (transferDevices.isEmpty()) {
                    showFailure(getString(R.string.export_empty))
                    return@launch
                }
                if (transferDevices.size > DeviceTransferCodec.MAX_RECORDS) {
                    showFailure(getString(R.string.transfer_too_many, DeviceTransferCodec.MAX_RECORDS))
                    return@launch
                }
                renderState(getString(R.string.transfer_file_ready_title),
                    getString(R.string.transfer_file_ready_body, transferDevices.size))
                primary(getString(R.string.transfer_choose_method)) { shareFile() }
                secondary(getString(R.string.cancel)) { finishTransfer(false) }
            } catch (_: Exception) {
                showFailure(getString(R.string.transfer_error))
            }
        }
    }

    private fun shareFile() {
        if (transferDevices.isEmpty()) return
        findViewById<MaterialButton>(R.id.transferPrimary).isEnabled = false
        renderState(getString(R.string.send_data), getString(R.string.transfer_prepare_file), loading = true)
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val bytes = DeviceTransferCodec.encode(transferDevices)
                    val directory = File(cacheDir, "device-transfers")
                    if (!directory.exists() && !directory.mkdirs()) throw DeviceTransferException("Не удалось подготовить файл передачи")
                    directory.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > CACHE_RETENTION_MS }
                        ?.forEach { it.delete() }
                    File(directory, "DeviceManager-${System.currentTimeMillis()}.json").apply { writeBytes(bytes) }
                }
                val uri = FileProvider.getUriForFile(this@DeviceTransferActivity, "$packageName.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(contentResolver, file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, getString(R.string.transfer_choose_method)))
                finishTransfer(true)
            } catch (error: Exception) {
                showFailure(error.message?.takeIf(String::isNotBlank) ?: getString(R.string.transfer_error))
            }
        }
    }

    private fun showReceivePicker() {
        renderState(getString(R.string.receive_file_title), getString(R.string.receive_file_help))
        primary(getString(R.string.receive_choose_file)) {
            openTransferFile.launch(arrayOf("application/json"))
        }
        secondary(getString(R.string.cancel)) { finishTransfer(false) }
    }

    private fun readAndPreview(uri: Uri) {
        renderState(getString(R.string.receive_file_title), getString(R.string.transfer_reading_file), loading = true)
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use(::readBounded)
                        ?: throw DeviceTransferException("Не удалось открыть выбранный файл")
                }
                val devices = DeviceTransferCodec.decode(bytes)
                val known = withContext(Dispatchers.IO) { repository.all().map(::identity).toMutableSet() }
                val newCount = devices.count { known.add(identity(it)) }
                showIncoming(devices, devices.size - newCount)
            } catch (error: Exception) {
                showFailure(error.message?.takeIf(String::isNotBlank) ?: getString(R.string.transfer_error))
            }
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (output.size() + count > DeviceTransferCodec.MAX_PACKET_BYTES) {
                throw DeviceTransferException("Размер файла передачи превышает 8 MiB")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun identity(device: Device) =
        normalizedKey(device.organization) + "\u0000" + normalizedKey(device.inventoryNumber)

    private fun showIncoming(devices: List<Device>, duplicates: Int) {
        val preview = devices.take(8).joinToString("\n") { "• ${it.name.take(80)} · № ${it.inventoryNumber.take(40)}" }
        val tail = if (devices.size > 8) "\n… ещё ${devices.size - 8}" else ""
        val details = getString(R.string.receive_offer_summary, devices.size, duplicates) +
            "\n\n" + getString(R.string.receive_preview_header) + "\n" + preview + tail
        renderState(getString(R.string.receive_data), getString(R.string.receive_file_review))
        findViewById<TextView>(R.id.transferDetails).text = details
        primary(if (devices.size == duplicates) getString(R.string.receive_no_new)
            else getString(R.string.receive_add_count, devices.size - duplicates)) { acceptIncoming(devices) }
        secondary(getString(R.string.cancel)) { finishTransfer(false) }
    }

    private fun acceptIncoming(devices: List<Device>) {
        renderState(getString(R.string.receive_data), getString(R.string.receive_importing), loading = true)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repository.importNew(devices) }
                renderState(getString(R.string.transfer_complete_title), getString(R.string.receive_done, result.added, result.duplicates))
                primary(getString(R.string.transfer_back_to_inventory)) { finishTransfer(true) }
            } catch (error: Exception) {
                showFailure(error.message?.takeIf(String::isNotBlank) ?: getString(R.string.transfer_error))
            }
        }
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
            isEnabled = true
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
        primary(getString(R.string.retry)) { if (mode == MODE_SEND) loadSelectedDevices() else showReceivePicker() }
        secondary(getString(R.string.cancel)) { finishTransfer(false) }
    }

    private fun finishTransfer(success: Boolean) {
        setResult(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }
}
