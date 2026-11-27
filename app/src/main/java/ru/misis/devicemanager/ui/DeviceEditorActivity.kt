package ru.misis.devicemanager.ui

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import ru.misis.devicemanager.R
import ru.misis.devicemanager.data.*
import java.text.DateFormat
import java.util.Date
import ru.misis.devicemanager.qr.DeviceQrCodec

class DeviceEditorActivity : DeviceActivity() {
    private lateinit var model: DeviceEditorViewModel
    private var bound = false
    private var draftRestored = false
    private var baseline: Device? = null
    private var shownError: Int? = null
    private val fieldIds = listOf(R.id.editName, R.id.editInventory, R.id.editSerial, R.id.editCategory,
        R.id.editOrganization, R.id.editBuilding, R.id.editRoom, R.id.editResponsible, R.id.editReason, R.id.editNotes)
    private val required = mapOf(
        DeviceField.NAME to R.id.editNameLayout, DeviceField.INVENTORY to R.id.editInventoryLayout,
        DeviceField.ORGANIZATION to R.id.editOrganizationLayout, DeviceField.ROOM to R.id.editRoomLayout,
        DeviceField.WRITE_OFF_REASON to R.id.editReasonLayout,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_editor)
        model = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[DeviceEditorViewModel::class.java]
        findViewById<Spinner>(R.id.editStatus).apply {
            setup(listOf(getString(R.string.status_active), getString(R.string.status_write_off)))
            onItemSelectedListener = Selection { updateStatus() }
        }
        savedInstanceState?.getBundle("draft")?.let { draft ->
            fieldIds.forEach { findViewById<EditText>(it).setText(draft.getString(it.toString()).orEmpty()) }
            findViewById<Spinner>(R.id.editStatus).setSelection(draft.getInt("status"))
            draftRestored = true
        }
        fieldIds.forEach { id ->
            findViewById<EditText>(id).doAfterTextChanged {
                (findViewById<EditText>(id).parent.parent as? TextInputLayout)?.error = null
                shownError = null
            }
        }
        findViewById<View>(R.id.editorSave).setOnClickListener { model.save(draft()) }
        findViewById<View>(R.id.editorBack).setOnClickListener { requestBack() }
        findViewById<View>(R.id.editorQr).setOnClickListener {
            val saved = model.state.value?.device ?: return@setOnClickListener
            if (intent.hasExtra(EXTRA_IMPORT) || draft().normalized() != saved.normalized()) {
                Snackbar.make(findViewById(R.id.editorRoot), "Сначала сохраните изменения, затем откройте QR из реестра.", Snackbar.LENGTH_LONG).show()
            } else startActivity(android.content.Intent(this, QrActivity::class.java).putExtra(QrActivity.EXTRA_ID, saved.id))
        }
        findViewById<View>(R.id.editorDelete).setOnClickListener {
            val device = model.state.value!!.device ?: return@setOnClickListener
            MaterialAlertDialogBuilder(this).setTitle(R.string.delete_device)
                .setMessage(getString(R.string.delete_confirmation, device.name, device.inventoryNumber))
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.delete_action) { _, _ -> model.delete() }.show()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { requestBack() }
        })
        model.state.observe(this) { render(it) }
        val incoming = intent.getStringExtra(EXTRA_IMPORT)?.let { runCatching { DeviceQrCodec.decode(it) }.getOrNull() }
        model.initialize(intent.getLongExtra(EXTRA_ID, 0), intent.getStringExtra(EXTRA_STATUS)?.let { DeviceStatus.valueOf(it) }, incoming)
        updateStatus()
    }

    private fun render(state: EditorState) {
        if (state.completed) { finish(); return }
        findViewById<View>(R.id.editorLoading).isVisible = state.loading || state.saving
        findViewById<Button>(R.id.editorSave).apply {
            isEnabled = !state.loading && !state.saving && state.device != null
            setText(if (state.saving) R.string.saving_device else R.string.save_device)
        }
        findViewById<View>(R.id.editorDelete).apply {
            isVisible = (state.device?.id ?: 0) != 0L
            isEnabled = !state.saving
        }
        findViewById<View>(R.id.editorQr).apply {
            isVisible = (state.device?.id ?: 0L) != 0L
            isEnabled = !state.saving && !state.loading
        }
        fieldIds.forEach { findViewById<View>(it).isEnabled = !state.loading && !state.saving }
        findViewById<View>(R.id.editStatus).isEnabled = !state.loading && !state.saving
        if (state.device != null && !bound) {
            bound = true
            baseline = state.device
            findViewById<TextView>(R.id.editorTitle).setText(if (state.device.id == 0L) R.string.add_device else R.string.edit_device)
            if (!draftRestored) bind(state.device)
            suggestions(state.suggestions)
            findViewById<TextView>(R.id.editorUpdated).apply {
                isVisible = state.device.updatedAt > 0
                text = if (state.device.updatedAt > 0) getString(R.string.updated_at,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.device.updatedAt))) else ""
            }
        }
        required.forEach { (field, id) ->
            findViewById<TextInputLayout>(id).error = if (field in state.invalid) getString(R.string.required_error) else null
        }
        if (state.invalid.isNotEmpty()) focus(required.getValue(state.invalid.first()))
        if (state.error != null && shownError != state.error) {
            shownError = state.error
            when (state.error) {
                R.string.duplicate_inventory -> {
                    findViewById<TextInputLayout>(R.id.editInventoryLayout).error = getString(state.error)
                    focus(R.id.editInventoryLayout)
                }
                R.string.missing_device -> MaterialAlertDialogBuilder(this).setMessage(state.error)
                    .setPositiveButton(R.string.ok) { _, _ -> finish() }.setOnCancelListener { finish() }.show()
                R.string.load_error -> MaterialAlertDialogBuilder(this).setMessage(state.error)
                    .setPositiveButton(R.string.retry) { _, _ -> shownError = null; model.load() }
                    .setNegativeButton(R.string.back) { _, _ -> finish() }.show()
                else -> Snackbar.make(findViewById(R.id.editorRoot), state.error, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun bind(device: Device) {
        val values = listOf(device.name, device.inventoryNumber, device.serialNumber, device.category,
            device.organization, device.building, device.room, device.responsible, device.writeOffReason, device.notes)
        fieldIds.zip(values).forEach { (id, value) -> findViewById<EditText>(id).setText(value) }
        findViewById<Spinner>(R.id.editStatus).setSelection(device.status.ordinal)
        updateStatus()
    }

    private fun suggestions(devices: List<Device>) {
        fun set(id: Int, values: List<String>) {
            findViewById<AutoCompleteTextView>(id).setAdapter(ArrayAdapter(this,
                android.R.layout.simple_dropdown_item_1line, values.filter(String::isNotBlank)
                    .distinctBy(::normalizedKey).sortedBy(::normalizedKey)))
        }
        set(R.id.editCategory, resources.getStringArray(R.array.device_categories).toList() + devices.map { it.category })
        set(R.id.editOrganization, devices.map { it.organization })
        fun locations() {
            val organization = normalizedKey(text(R.id.editOrganization))
            val building = normalizedKey(text(R.id.editBuilding))
            val matching = devices.filter { normalizedKey(it.organization) == organization }
            set(R.id.editBuilding, matching.map { it.building })
            set(R.id.editRoom, matching.filter { normalizedKey(it.building) == building }.map { it.room })
        }
        locations()
        findViewById<EditText>(R.id.editOrganization).doAfterTextChanged { locations() }
        findViewById<EditText>(R.id.editBuilding).doAfterTextChanged { locations() }
    }

    private fun draft() = (model.state.value?.device ?: Device()).copy(
        name = text(R.id.editName), inventoryNumber = text(R.id.editInventory), serialNumber = text(R.id.editSerial),
        category = text(R.id.editCategory), organization = text(R.id.editOrganization), building = text(R.id.editBuilding),
        room = text(R.id.editRoom), responsible = text(R.id.editResponsible), notes = text(R.id.editNotes),
        writeOffReason = text(R.id.editReason),
        status = DeviceStatus.entries[findViewById<Spinner>(R.id.editStatus).selectedItemPosition.coerceAtLeast(0)],
    )

    private fun text(id: Int) = findViewById<EditText>(id).text.toString()
    private fun updateStatus() {
        val writeOff = findViewById<Spinner>(R.id.editStatus).selectedItemPosition == DeviceStatus.WRITE_OFF.ordinal
        findViewById<View>(R.id.editReasonLayout).isVisible = writeOff
        findViewById<View>(R.id.writeOffHelp).isVisible = writeOff
    }

    private fun focus(layout: Int) {
        val input = findViewById<TextInputLayout>(layout).editText ?: return
        input.requestFocus()
        input.post { input.requestRectangleOnScreen(android.graphics.Rect(0, 0, input.width, input.height), false) }
    }

    private fun requestBack() {
        if (model.state.value!!.saving) return
        if (bound && (intent.hasExtra(EXTRA_IMPORT) || draft().normalized() != baseline?.normalized())) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.unsaved_title).setMessage(R.string.unsaved_body)
                .setNegativeButton(R.string.stay, null).setPositiveButton(R.string.discard) { _, _ -> finish() }.show()
        } else finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (bound || draftRestored) outState.putBundle("draft", Bundle().apply {
            fieldIds.forEach { putString(it.toString(), text(it)) }
            putInt("status", findViewById<Spinner>(R.id.editStatus).selectedItemPosition)
        })
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_ID = "device_id"
        const val EXTRA_STATUS = "device_status"
        const val EXTRA_IMPORT = "device_import"
    }
}
