package ru.misis.devicemanager.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.misis.devicemanager.DeviceManagerApplication
import ru.misis.devicemanager.R
import ru.misis.devicemanager.data.AuditItem
import ru.misis.devicemanager.data.Device
import ru.misis.devicemanager.data.RoomAudit
import ru.misis.devicemanager.data.normalizedKey
import ru.misis.devicemanager.qr.DeviceQrCodec
import ru.misis.devicemanager.qr.QrKeyStore
import java.text.DateFormat
import java.util.Date

class InventoryAuditActivity : DeviceActivity() {
    private val repository by lazy { (application as DeviceManagerApplication).repository }
    private lateinit var devices: List<Device>
    private var audit: RoomAudit? = null

    private val scanResult = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::readScannedCode)
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchScanner() else message(getString(R.string.audit_camera_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inventory_audit)
        findViewById<MaterialToolbar>(R.id.auditToolbar).setNavigationOnClickListener { finish() }
        findViewById<View>(R.id.auditStart).setOnClickListener { startAudit() }
        findViewById<View>(R.id.auditScan).setOnClickListener { requestCamera() }
        findViewById<View>(R.id.auditFinish).setOnClickListener {
            if (audit?.completedAt != null) finish() else confirmFinish()
        }
        val organizationInput = findViewById<MaterialAutoCompleteTextView>(R.id.auditOrganization)
        val buildingInput = findViewById<MaterialAutoCompleteTextView>(R.id.auditBuilding)
        val roomInput = findViewById<MaterialAutoCompleteTextView>(R.id.auditRoom)
        var previousOrganization = organizationInput.text.toString()
        var previousBuilding = buildingInput.text.toString()
        organizationInput.doAfterTextChanged {
            val current = organizationInput.text.toString()
            if (current != previousOrganization) {
                previousOrganization = current
                buildingInput.setText("", false)
                roomInput.setText("", false)
                previousBuilding = ""
            }
            updateLocationSuggestions()
        }
        buildingInput.doAfterTextChanged {
            val current = buildingInput.text.toString()
            if (current != previousBuilding) {
                previousBuilding = current
                roomInput.setText("", false)
            }
            updateLocationSuggestions()
        }
        findViewById<TextInputLayout>(R.id.auditRoomLayout).error = null
        load(savedInstanceState?.getLong(STATE_AUDIT_ID, 0L) ?: 0L)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_AUDIT_ID, audit?.id ?: 0L)
        super.onSaveInstanceState(outState)
    }

    private fun load(savedAuditId: Long) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { Triple(repository.all(), repository.recentRoomAudits(), if (savedAuditId == 0L) null else repository.roomAudit(savedAuditId)) }
            devices = result.first
            bindSuggestions()
            renderRecent(result.second)
            result.third?.let(::renderAudit)
        }
    }

    private fun bindSuggestions() {
        val organization = findViewById<MaterialAutoCompleteTextView>(R.id.auditOrganization)
        val building = findViewById<MaterialAutoCompleteTextView>(R.id.auditBuilding)
        val room = findViewById<MaterialAutoCompleteTextView>(R.id.auditRoom)
        organization.setAdapter(adapter(devices.map { it.organization }))
        building.setAdapter(adapter(devices.map { it.building }))
        room.setAdapter(adapter(devices.map { it.room }))
        updateLocationSuggestions()
    }

    private fun updateLocationSuggestions() {
        if (!this::devices.isInitialized) return
        val org = findViewById<MaterialAutoCompleteTextView>(R.id.auditOrganization).text.toString()
        val building = findViewById<MaterialAutoCompleteTextView>(R.id.auditBuilding).text.toString()
        val inOrganization = devices.filter { org.isBlank() || normalizedKey(it.organization) == normalizedKey(org) }
        findViewById<MaterialAutoCompleteTextView>(R.id.auditBuilding).setAdapter(adapter(inOrganization.map { it.building }))
        val inBuilding = inOrganization.filter { building.isBlank() || normalizedKey(it.building) == normalizedKey(building) }
        findViewById<MaterialAutoCompleteTextView>(R.id.auditRoom).setAdapter(adapter(inBuilding.map { it.room }))
    }

    private fun adapter(values: List<String>) = android.widget.ArrayAdapter(
        this, android.R.layout.simple_dropdown_item_1line,
        values.filter(String::isNotBlank).distinctBy(::normalizedKey).sortedBy(::normalizedKey),
    )

    private fun startAudit() {
        val organizationField = findViewById<TextInputLayout>(R.id.auditOrganizationLayout)
        val roomField = findViewById<TextInputLayout>(R.id.auditRoomLayout)
        val organization = findViewById<MaterialAutoCompleteTextView>(R.id.auditOrganization).text.toString().trim()
        val building = findViewById<MaterialAutoCompleteTextView>(R.id.auditBuilding).text.toString().trim()
        val room = findViewById<MaterialAutoCompleteTextView>(R.id.auditRoom).text.toString().trim()
        organizationField.error = null
        roomField.error = null
        if (organization.isBlank()) organizationField.error = getString(R.string.required_error)
        if (room.isBlank()) roomField.error = getString(R.string.required_error)
        if (organization.isBlank() || room.isBlank()) return
        findViewById<View>(R.id.auditStart).isEnabled = false
        lifecycleScope.launch {
            try {
                val started = withContext(Dispatchers.IO) { repository.startRoomAudit(organization, building, room) }
                renderAudit(started)
            } catch (_: Exception) {
                message(getString(R.string.save_error))
            } finally { findViewById<View>(R.id.auditStart).isEnabled = true }
        }
    }

    private fun renderRecent(audits: List<RoomAudit>) {
        val list = findViewById<LinearLayout>(R.id.auditRecentList)
        list.removeAllViews()
        if (audits.isEmpty()) {
            list.addView(TextView(this).apply {
                setText(R.string.audit_recent_empty)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@InventoryAuditActivity, R.color.device_secondary))
                setPadding(0, dp(8), 0, dp(14))
            })
            return
        }
        audits.forEachIndexed { index, item ->
            if (index > 0) list.addView(divider())
            list.addView(TextView(this).apply {
                val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.startedAt))
                text = "${locationName(item.organization, item.building, item.room)}\n$date\n" +
                    getString(R.string.audit_summary, item.expectedCount, item.foundCount, item.missingCount, item.extraCount)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@InventoryAuditActivity, R.color.text_primary))
                setPadding(dp(2), dp(12), dp(2), dp(12))
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { renderAudit(item) }
            })
        }
    }

    private fun renderAudit(value: RoomAudit) {
        audit = value
        findViewById<View>(R.id.auditLocationSetup).isVisible = false
        findViewById<View>(R.id.auditRun).isVisible = true
        val complete = value.completedAt != null
        findViewById<TextView>(R.id.auditLocationTitle).text = locationName(value.organization, value.building, value.room)
        findViewById<TextView>(R.id.auditSummary).text = getString(
            R.string.audit_summary, value.expectedCount, value.foundCount, value.missingCount, value.extraCount)
        findViewById<View>(R.id.auditMissingNote).isVisible = complete && value.missingCount > 0
        findViewById<View>(R.id.auditScan).isVisible = !complete
        findViewById<View>(R.id.auditFinish).isVisible = true
        findViewById<TextView>(R.id.auditFinish).setText(if (complete) R.string.audit_back_to_register else R.string.audit_finish)
        val rows = findViewById<LinearLayout>(R.id.auditItems)
        rows.removeAllViews()
        if (value.items.isEmpty()) {
            rows.addView(TextView(this).apply {
                setText(R.string.audit_empty_room)
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@InventoryAuditActivity, R.color.device_secondary))
                setPadding(0, dp(14), 0, dp(14))
            })
        } else value.items.forEachIndexed { index, item ->
            if (index > 0) rows.addView(divider())
            rows.addView(auditRow(item))
        }
    }

    private fun auditRow(item: AuditItem): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(12), dp(2), dp(12))
        val title = TextView(this@InventoryAuditActivity).apply {
            text = "${item.name} · № ${item.inventoryNumber}"
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@InventoryAuditActivity, R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val condition = when {
            !item.expected -> getString(R.string.audit_extra_item)
            item.foundAt != null -> getString(R.string.audit_expected_found)
            else -> getString(R.string.audit_expected_missing)
        }
        val status = statusLabel(item.status)
        val currentRoom = if (!item.expected) " · ${locationName(item.organization, item.building, item.room)}" else ""
        val stateText = "$condition · $status$currentRoom"
        val state = TextView(this@InventoryAuditActivity).apply {
            text = stateText
            textSize = 14f
            val color = when {
                !item.expected -> R.color.device_repair
                item.foundAt == null -> R.color.device_broken
                else -> R.color.device_active
            }
            setTextColor(ContextCompat.getColor(this@InventoryAuditActivity, color))
            setPadding(0, dp(4), 0, 0)
        }
        addView(title)
        addView(state)
    }

    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchScanner()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun launchScanner() {
        scanResult.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.audit_scan))
            .setBeepEnabled(false).setOrientationLocked(true).setCaptureActivity(QrPortraitActivity::class.java))
    }

    private fun readScannedCode(raw: String) {
        val sessionId = audit?.takeIf { it.completedAt == null }?.id ?: return
        lifecycleScope.launch {
            try {
                val (device, result) = withContext(Dispatchers.IO) {
                    val keys = QrKeyStore(applicationContext)
                    val decoded = DeviceQrCodec.decode(raw) { keyId -> keys.find(keyId)?.pair?.private }
                    decoded to repository.recordAuditScan(sessionId, decoded)
                }
                val updated = withContext(Dispatchers.IO) { repository.roomAudit(sessionId) }
                if (updated != null) renderAudit(updated)
                val notice = when {
                    result.alreadyFound -> getString(R.string.audit_scan_repeat)
                    result.expected -> getString(R.string.audit_scan_expected, device.name, device.inventoryNumber)
                    else -> getString(R.string.audit_scan_extra, device.name, device.inventoryNumber)
                }
                message(notice)
            } catch (error: Exception) {
                message(error.message?.takeIf(String::isNotBlank) ?: getString(R.string.audit_scan_error))
            }
        }
    }

    private fun confirmFinish() {
        val current = audit ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.audit_finish)
            .setMessage(getString(R.string.audit_finish_summary, current.expectedCount, current.foundCount, current.missingCount, current.extraCount) +
                if (current.missingCount > 0) "\n\n${getString(R.string.audit_missing_warning)}" else "")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.audit_finish) { _, _ -> completeAudit(current.id) }
            .show()
    }

    private fun completeAudit(id: Long) {
        lifecycleScope.launch {
            val completed = withContext(Dispatchers.IO) { repository.finishRoomAudit(id) }
            if (completed != null) {
                renderAudit(completed)
                message(getString(R.string.audit_complete, completed.expectedCount, completed.foundCount, completed.missingCount, completed.extraCount))
            }
        }
    }

    private fun locationName(organization: String, building: String, room: String) =
        listOf(organization, building, getString(R.string.audit_room_label, room)).filter(String::isNotBlank).joinToString(" · ")

    private fun divider() = View(this).apply {
        setBackgroundColor(ContextCompat.getColor(this@InventoryAuditActivity, R.color.device_divider))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun message(value: String) {
        Snackbar.make(findViewById(R.id.auditToolbar), value, Snackbar.LENGTH_LONG).apply {
            view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.maxLines = 5
        }.show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { private const val STATE_AUDIT_ID = "audit_id" }
}
