package ru.misis.devicemanager.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import ru.misis.devicemanager.DeviceManagerApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.misis.devicemanager.R
import ru.misis.devicemanager.core.ThemeManager
import ru.misis.devicemanager.data.*

class DeviceListActivity : DeviceActivity() {
    private lateinit var model: DeviceListViewModel
    private lateinit var adapter: DeviceAdapter
    private var filterJob: Job? = null
    private var transferSelectionActive = false
    private var bulkSelectionActive = false
    private val selectedForTransfer = linkedSetOf<Long>()
    private val deviceTransfer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            model.filter = DeviceFilter()
            resetControls()
            if (transferSelectionActive) cancelTransferSelection()
        }
        model.refresh()
    }
    private val backFromTrash = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (transferSelectionActive || bulkSelectionActive) cancelTransferSelection() else toggleTrash(false)
        }
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val records = model.pendingExport
        model.pendingExport = null
        if (uri != null) {
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        checkNotNull(records)
                        val stream = checkNotNull(contentResolver.openOutputStream(uri))
                        DeviceCsv.write(records, stream)
                    }
                }
                notice(if (result.isSuccess) R.string.export_success else R.string.export_error)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devices)
        model = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[DeviceListViewModel::class.java]
        transferSelectionActive = savedInstanceState?.getBoolean("transferSelection") ?: false
        bulkSelectionActive = savedInstanceState?.getBoolean("bulkSelection") ?: false
        selectedForTransfer.addAll(savedInstanceState?.getLongArray("selectedForTransfer")?.toList().orEmpty())
        savedInstanceState?.getBundle("filter")?.let { bundle ->
            model.filter = DeviceFilter(
                search = bundle.getString("search").orEmpty(),
                status = bundle.getString("status")?.let { DeviceStatus.valueOf(it) },
                organization = bundle.getString("organization").orEmpty(),
                building = bundle.getString("building").orEmpty(), room = bundle.getString("room").orEmpty(),
                category = bundle.getString("category").orEmpty(),
                sort = DeviceSort.valueOf(bundle.getString("sort", DeviceSort.UPDATED.name)),
                trash = bundle.getBoolean("trash"),
            )
        }
        adapter = DeviceAdapter(
            open = { if (it.deletedAt == null) openDetails(it) else showActions(it, findViewById(R.id.deviceMenu)) },
            menu = ::showActions,
            selectionChanged = ::updateTransferSelection,
        )
        findViewById<RecyclerView>(R.id.deviceList).apply {
            layoutManager = LinearLayoutManager(this@DeviceListActivity)
            adapter = this@DeviceListActivity.adapter
        }
        findViewById<EditText>(R.id.deviceSearch).apply {
            setText(model.filter.search)
            doAfterTextChanged { model.filter = model.filter.copy(search = it.toString()); render() }
        }
        findViewById<ChipGroup>(R.id.statusFilters).apply {
            check(when (model.filter.status) {
                DeviceStatus.ACTIVE -> R.id.statusActive
                DeviceStatus.NEEDS_REPAIR -> R.id.statusNeedsRepair
                DeviceStatus.BROKEN -> R.id.statusBroken
                DeviceStatus.WRITE_OFF -> R.id.statusWriteOff
                null -> R.id.statusAll
            })
            setOnCheckedStateChangeListener { _, ids ->
                model.filter = model.filter.copy(status = when (ids.firstOrNull()) {
                    R.id.statusActive -> DeviceStatus.ACTIVE
                    R.id.statusNeedsRepair -> DeviceStatus.NEEDS_REPAIR
                    R.id.statusBroken -> DeviceStatus.BROKEN
                    R.id.statusWriteOff -> DeviceStatus.WRITE_OFF
                    else -> null
                })
                render()
            }
        }
        findViewById<Spinner>(R.id.deviceSort).apply {
            setup(resources.getStringArray(R.array.device_sorts).toList(), model.filter.sort.ordinal)
            onItemSelectedListener = Selection { position ->
                model.filter = model.filter.copy(sort = DeviceSort.entries[position]); render()
            }
        }
        findViewById<View>(R.id.addDevice).setOnClickListener { edit(null) }
        findViewById<View>(R.id.deviceCancelTransfer).setOnClickListener { cancelTransferSelection() }
        findViewById<View>(R.id.transferContinue).setOnClickListener { continueSelection() }
        findViewById<View>(R.id.scanDevice).setOnClickListener {
            startActivity(Intent(this, QrActivity::class.java).putExtra(QrActivity.EXTRA_SCAN, true))
        }
        findViewById<View>(R.id.locationFilters).setOnClickListener { showFilters() }
        findViewById<View>(R.id.deviceMenu).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.deviceRetry).setOnClickListener {
            if (model.state.value!!.failed) model.refresh() else resetFilters()
        }
        onBackPressedDispatcher.addCallback(this, backFromTrash)
        model.state.observe(this) { state ->
            render()
            state.notice?.let { notice(it); model.consumeNotice() }
        }
    }

    override fun onResume() { super.onResume(); model.refresh() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("transferSelection", transferSelectionActive)
        outState.putBoolean("bulkSelection", bulkSelectionActive)
        outState.putLongArray("selectedForTransfer", selectedForTransfer.toLongArray())
        outState.putBundle("filter", Bundle().apply {
            val filter = model.filter
            putString("search", filter.search); putString("status", filter.status?.name)
            putString("organization", filter.organization); putString("building", filter.building)
            putString("room", filter.room); putString("category", filter.category)
            putString("sort", filter.sort.name); putBoolean("trash", filter.trash)
        })
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        if (!::adapter.isInitialized) return
        val state = model.state.value ?: return
        val filter = model.filter
        val selectionActive = transferSelectionActive || bulkSelectionActive
        backFromTrash.isEnabled = filter.trash || selectionActive
        findViewById<TextView>(R.id.deviceTitle).setText(when {
            bulkSelectionActive -> R.string.bulk_title
            transferSelectionActive -> R.string.select_devices_title
            filter.trash -> R.string.trash_title
            else -> R.string.section_device
        })
        findViewById<View>(R.id.deviceMenu).isVisible = !selectionActive
        findViewById<View>(R.id.deviceCancelTransfer).isVisible = selectionActive
        findViewById<View>(R.id.addDevice).isVisible = !filter.trash && !selectionActive
        findViewById<View>(R.id.scanDevice).isVisible = !filter.trash && !selectionActive
        adapter.setSelectionState(selectionActive, selectedForTransfer)
        findViewById<View>(R.id.transferContinue).apply {
            isVisible = selectionActive && selectedForTransfer.isNotEmpty()
            if (isVisible) (this as android.widget.Button).text = getString(
                if (bulkSelectionActive) R.string.bulk_continue_count else R.string.continue_transfer_count,
                selectedForTransfer.size)
        }
        findViewById<View>(R.id.deviceLoading).isVisible = state.loading
        val scope = state.devices.filter { (it.deletedAt != null) == filter.trash }
        findViewById<TextView>(R.id.deviceStats).text = when {
            bulkSelectionActive && selectedForTransfer.isEmpty() -> getString(R.string.bulk_select_help)
            transferSelectionActive && selectedForTransfer.isEmpty() -> getString(R.string.select_devices_help)
            selectionActive -> getString(R.string.selected_devices_count, selectedForTransfer.size)
            filter.trash -> getString(R.string.trash_stats, scope.size)
            else -> getString(R.string.inventory_stats, scope.size,
                scope.count { it.status == DeviceStatus.ACTIVE },
                scope.count { it.status == DeviceStatus.NEEDS_REPAIR },
                scope.count { it.status == DeviceStatus.BROKEN },
                scope.count { it.status == DeviceStatus.WRITE_OFF })
        }
        val filterCount = listOf(filter.organization, filter.building, filter.room, filter.category).count(String::isNotBlank)
        findViewById<TextView>(R.id.locationFilters).text = if (filterCount == 0) getString(R.string.filter_location)
            else getString(R.string.filters_applied, filterCount)
        filterJob?.cancel()
        filterJob = lifecycleScope.launch {
            val visible = withContext(Dispatchers.Default) { filter.apply(state.devices) }
            adapter.submitList(visible)
            val empty = visible.isEmpty()
            findViewById<View>(R.id.deviceList).isVisible = !state.failed
            findViewById<View>(R.id.deviceEmpty).isVisible = (empty || state.failed) && !state.loading
            findViewById<TextView>(R.id.deviceResultCount).text =
                if (state.failed) "" else getString(R.string.visible_count, visible.size, scope.size)
            val title = when {
                state.failed -> R.string.load_error
                scope.isNotEmpty() -> R.string.empty_results_title
                filter.trash -> R.string.empty_trash_title
                else -> R.string.empty_inventory_title
            }
            val body = when {
                state.failed -> R.string.empty_results_body
                scope.isNotEmpty() -> R.string.empty_results_body
                filter.trash -> R.string.empty_trash_body
                else -> R.string.empty_inventory_body
            }
            findViewById<TextView>(R.id.deviceEmptyTitle).setText(title)
            findViewById<TextView>(R.id.deviceEmptyBody).apply { setText(body); isVisible = !state.failed }
            findViewById<Button>(R.id.deviceRetry).apply {
                isVisible = state.failed || (empty && scope.isNotEmpty())
                setText(if (state.failed) R.string.retry else R.string.reset_filters)
            }
        }
    }

    private fun edit(device: Device?, status: DeviceStatus? = null) {
        startActivity(Intent(this, DeviceEditorActivity::class.java).apply {
            putExtra(DeviceEditorActivity.EXTRA_ID, device?.id ?: 0L)
            status?.let { putExtra(DeviceEditorActivity.EXTRA_STATUS, it.name) }
        })
    }

    private fun openDetails(device: Device) {
        startActivity(Intent(this, DeviceDetailsActivity::class.java)
            .putExtra(DeviceDetailsActivity.EXTRA_ID, device.id))
    }

    private fun showActions(device: Device, anchor: View) {
        PopupMenu(this, anchor).apply {
            if (device.deletedAt == null) {
                menu.add(0, 1, 0, R.string.edit_action)
                menu.add(0, 2, 1, if (device.status == DeviceStatus.WRITE_OFF) R.string.mark_active else R.string.mark_write_off)
                menu.add(0, 3, 2, R.string.delete_device)
                menu.add(0, 6, 3, R.string.qr_action)
            } else {
                menu.add(0, 4, 0, R.string.restore_device)
                menu.add(0, 5, 1, R.string.delete_forever)
            }
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> edit(device)
                    2 -> edit(device, if (device.status == DeviceStatus.WRITE_OFF) DeviceStatus.ACTIVE else DeviceStatus.WRITE_OFF)
                    3 -> confirmDelete(device, false)
                    4 -> model.mutate(device.id, "restore")
                    5 -> confirmDelete(device, true)
                    6 -> startActivity(Intent(this@DeviceListActivity, QrActivity::class.java).putExtra(QrActivity.EXTRA_ID, device.id))
                }
                true
            }
            show()
        }
    }

    private fun confirmDelete(device: Device, permanent: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle(if (permanent) R.string.delete_forever else R.string.delete_device)
            .setMessage(getString(if (permanent) R.string.delete_forever_confirmation else R.string.delete_confirmation,
                device.name, device.inventoryNumber))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (permanent) R.string.delete_forever else R.string.delete_action) { _, _ ->
                model.mutate(device.id, if (permanent) "purge" else "trash")
            }.show()
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, if (model.filter.trash) R.string.inventory_title else R.string.trash_title)
            if (!model.filter.trash) menu.add(0, 2, 1, R.string.export_csv)
            menu.add(0, 3, 2, R.string.theme_title)
            menu.add(0, 4, 3, R.string.about_action)
            menu.add(0, 5, 4, R.string.qr_hub)
            if (!model.filter.trash) menu.add(0, 6, 5, R.string.transfer_menu)
            if (!model.filter.trash) menu.add(0, 7, 6, R.string.audit_menu)
            if (!model.filter.trash) menu.add(0, 8, 7, R.string.bulk_edit_menu)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> toggleTrash(!model.filter.trash)
                    2 -> {
                        if (adapter.currentList.isEmpty()) notice(R.string.export_empty)
                        else {
                            model.pendingExport = adapter.currentList.toList()
                            export.launch("DeviceManager-inventory.csv")
                        }
                    }
                    3 -> MaterialAlertDialogBuilder(this@DeviceListActivity).setTitle(R.string.theme_title)
                        .setSingleChoiceItems(R.array.device_themes, ThemeManager.getThemeMode(this@DeviceListActivity).ordinal) { dialog, position ->
                            dialog.dismiss()
                            ThemeManager.setThemeMode(this@DeviceListActivity, ThemeManager.ThemeMode.entries[position])
                        }.setNegativeButton(R.string.cancel, null).show()
                    4 -> MaterialAlertDialogBuilder(this@DeviceListActivity).setTitle(R.string.about_title)
                        .setMessage(R.string.about_body).setPositiveButton(R.string.ok, null).show()
                    5 -> startActivity(Intent(this@DeviceListActivity, QrActivity::class.java))
                    6 -> showTransferOptions()
                    7 -> startActivity(Intent(this@DeviceListActivity, InventoryAuditActivity::class.java))
                    8 -> beginBulkSelection()
                }
                true
            }
            show()
        }
    }

    private fun showTransferOptions() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.transfer_title)
            .setItems(arrayOf(getString(R.string.send_data), getString(R.string.receive_data))) { _, choice ->
                if (choice == 0) beginSendSelection() else beginReceiveTransfer()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun beginSendSelection() {
        if (model.filter.trash) return
        if (adapter.currentList.none { it.deletedAt == null }) {
            notice(R.string.export_empty)
            return
        }
        selectedForTransfer.clear()
        transferSelectionActive = true
        bulkSelectionActive = false
        render()
    }

    private fun beginBulkSelection() {
        if (model.filter.trash) return
        if (adapter.currentList.none { it.deletedAt == null }) { notice(R.string.export_empty); return }
        selectedForTransfer.clear()
        transferSelectionActive = false
        bulkSelectionActive = true
        render()
    }

    private fun updateTransferSelection(device: Device, selected: Boolean) {
        if ((!transferSelectionActive && !bulkSelectionActive) || device.deletedAt != null) return
        val selectionLimit = if (transferSelectionActive) DeviceTransferCodec.MAX_RECORDS else 500
        if (selected && selectedForTransfer.size >= selectionLimit) {
            Snackbar.make(findViewById(R.id.deviceRoot), getString(
                if (transferSelectionActive) R.string.transfer_too_many else R.string.bulk_too_many, selectionLimit), Snackbar.LENGTH_LONG).show()
            return
        }
        if (selected) selectedForTransfer.add(device.id) else selectedForTransfer.remove(device.id)
        render()
    }

    private fun continueDeviceTransfer() {
        if (selectedForTransfer.isEmpty()) return
        val intent = Intent(this, DeviceTransferActivity::class.java)
            .putExtra(DeviceTransferActivity.EXTRA_MODE, DeviceTransferActivity.MODE_SEND)
            .putExtra(DeviceTransferActivity.EXTRA_DEVICE_IDS, selectedForTransfer.toLongArray())
        deviceTransfer.launch(intent)
    }

    private fun continueSelection() {
        if (selectedForTransfer.isEmpty()) return
        if (bulkSelectionActive) showBulkEditDialog() else continueDeviceTransfer()
    }

    private fun showBulkEditDialog() {
        val records = selectedForTransfer.mapNotNull { id -> model.state.value?.devices?.firstOrNull { it.id == id } }
        if (records.isEmpty()) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(12))
        }
        fun common(values: List<String>) = values.distinctBy(::normalizedKey).singleOrNull().orEmpty()
        fun input(label: Int, value: String): Pair<TextInputLayout, TextInputEditText> {
            val field = TextInputLayout(this, null, R.style.DeviceInput).apply {
                hint = getString(label)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            }
            val edit = TextInputEditText(field.context).apply {
                setText(value)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSingleLine(true)
                filters = arrayOf(android.text.InputFilter.LengthFilter(200))
            }
            field.addView(edit)
            content.addView(field)
            return field to edit
        }
        val locationToggle = MaterialCheckBox(this).apply {
            setText(R.string.bulk_change_location)
            minHeight = dp(48)
        }
        content.addView(locationToggle)
        val (organizationField, organization) = input(R.string.field_organization, common(records.map { it.organization }))
        val (buildingField, building) = input(R.string.field_building, common(records.map { it.building }))
        val (roomField, room) = input(R.string.field_room, common(records.map { it.room }))
        val locationFields = listOf(organizationField, buildingField, roomField)
        locationFields.forEach { it.isEnabled = false }
        locationToggle.setOnCheckedChangeListener { _, checked -> locationFields.forEach { it.isEnabled = checked } }

        val responsibleToggle = MaterialCheckBox(this).apply {
            setText(R.string.bulk_change_responsible)
            minHeight = dp(48)
        }
        content.addView(responsibleToggle)
        val (responsibleField, responsible) = input(R.string.field_responsible, common(records.map { it.responsible }))
        responsibleField.isEnabled = false
        responsibleToggle.setOnCheckedChangeListener { _, checked -> responsibleField.isEnabled = checked }

        val statusLabel = TextView(this).apply {
            setText(R.string.bulk_change_status)
            setTextColor(getColorCompat(R.color.device_secondary))
            setPadding(0, dp(18), 0, dp(4))
        }
        content.addView(statusLabel)
        val statuses: List<DeviceStatus?> = listOf(null) + DeviceStatus.entries
        val status = Spinner(this).apply {
            setup(listOf(getString(R.string.bulk_status_unchanged)) + DeviceStatus.entries.map(::statusLabel))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        content.addView(status)
        val (reasonField, reason) = input(R.string.bulk_write_off_reason, "")
        reasonField.isVisible = false
        status.onItemSelectedListener = Selection { position ->
            reasonField.isVisible = statuses.getOrNull(position) == DeviceStatus.WRITE_OFF
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bulk_title)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.apply_bulk_changes, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val targetStatus = statuses.getOrNull(status.selectedItemPosition)
                val targetLocation = if (locationToggle.isChecked) DeviceLocation(
                    organization.text?.toString().orEmpty(), building.text?.toString().orEmpty(), room.text?.toString().orEmpty(),
                ) else null
                organizationField.error = null
                roomField.error = null
                if (targetLocation != null && targetLocation.organization.isBlank()) {
                    organizationField.error = getString(R.string.required_error)
                    return@setOnClickListener
                }
                if (targetLocation != null && targetLocation.room.isBlank()) {
                    roomField.error = getString(R.string.required_error)
                    return@setOnClickListener
                }
                val targetResponsible = if (responsibleToggle.isChecked) responsible.text?.toString().orEmpty() else null
                if (targetLocation == null && targetStatus == null && targetResponsible == null) {
                    notice(R.string.bulk_choose_change)
                    return@setOnClickListener
                }
                val reasonText = reason.text?.toString().orEmpty()
                if (targetStatus == DeviceStatus.WRITE_OFF && reasonText.isBlank()) {
                    reasonField.error = getString(R.string.required_error)
                    return@setOnClickListener
                }
                val ids = selectedForTransfer.toList()
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                lifecycleScope.launch {
                    try {
                        val changed = withContext((application as DeviceManagerApplication).databaseDispatcher) {
                            (application as DeviceManagerApplication).repository.updateMany(ids, targetLocation, targetResponsible, targetStatus, reasonText)
                        }
                        cancelTransferSelection()
                        model.refresh()
                        val resultText = if (changed == 0) getString(R.string.bulk_no_changes)
                            else getString(R.string.bulk_updated, changed)
                        Snackbar.make(findViewById(R.id.deviceRoot), resultText, Snackbar.LENGTH_LONG).show()
                        dialog.dismiss()
                    } catch (_: Exception) {
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                        Snackbar.make(findViewById(R.id.deviceRoot), R.string.bulk_error, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun beginReceiveTransfer() {
        deviceTransfer.launch(Intent(this, DeviceTransferActivity::class.java)
            .putExtra(DeviceTransferActivity.EXTRA_MODE, DeviceTransferActivity.MODE_RECEIVE))
    }

    private fun cancelTransferSelection() {
        transferSelectionActive = false
        bulkSelectionActive = false
        selectedForTransfer.clear()
        render()
    }

    private fun toggleTrash(trash: Boolean) {
        model.filter = DeviceFilter(trash = trash)
        resetControls()
        render()
    }

    private fun resetFilters() {
        model.filter = DeviceFilter(trash = model.filter.trash)
        resetControls()
        render()
    }

    private fun resetControls() {
        findViewById<EditText>(R.id.deviceSearch).setText("")
        findViewById<ChipGroup>(R.id.statusFilters).check(R.id.statusAll)
        findViewById<Spinner>(R.id.deviceSort).setSelection(0)
    }

    private fun showFilters() {
        val devices = model.state.value!!.devices.filter { (it.deletedAt != null) == model.filter.trash }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        fun spinner(label: Int): Spinner {
            val control = Spinner(this).apply {
                minimumHeight = dp(48)
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
            layout.addView(TextView(this).apply {
                setText(label); setTextColor(getColorCompat(R.color.device_secondary))
                setPadding(0, dp(16), 0, dp(4)); labelFor = View.generateViewId().also { control.id = it }
            })
            layout.addView(control)
            return control
        }
        val organization = spinner(R.string.field_organization)
        val building = spinner(R.string.field_building)
        val room = spinner(R.string.field_room)
        val category = spinner(R.string.field_category)
        var organizationValue = model.filter.organization
        var buildingValue = model.filter.building
        var roomValue = model.filter.room
        var categoryValue = model.filter.category
        fun options(values: List<String>) = listOf("") + values.filter(String::isNotBlank)
            .distinctBy(::normalizedKey).sortedBy(::normalizedKey)
        fun setOptions(control: Spinner, values: List<String>, selected: String, change: (String) -> Unit) {
            control.onItemSelectedListener = null
            val index = values.indexOfFirst { normalizedKey(it) == normalizedKey(selected) }.coerceAtLeast(0)
            control.setup(values.map { it.ifEmpty { getString(R.string.filter_any) } }, index)
            control.onItemSelectedListener = Selection { change(values[it]) }
        }
        fun selectedDevices() = devices.filter { organizationValue.isBlank() || normalizedKey(it.organization) == normalizedKey(organizationValue) }
        fun updateRooms() {
            val values = options(selectedDevices().filter { buildingValue.isBlank() || normalizedKey(it.building) == normalizedKey(buildingValue) }.map { it.room })
            if (values.none { normalizedKey(it) == normalizedKey(roomValue) }) roomValue = ""
            setOptions(room, values, roomValue) { roomValue = it }
        }
        fun updateBuildings() {
            val values = options(selectedDevices().map { it.building })
            if (values.none { normalizedKey(it) == normalizedKey(buildingValue) }) buildingValue = ""
            setOptions(building, values, buildingValue) { value ->
                if (value != buildingValue) { buildingValue = value; roomValue = ""; updateRooms() }
            }
            updateRooms()
        }
        setOptions(organization, options(devices.map { it.organization }), organizationValue) { value ->
            if (value != organizationValue) { organizationValue = value; buildingValue = ""; roomValue = ""; updateBuildings() }
        }
        updateBuildings()
        setOptions(category, options(devices.map { it.category }), categoryValue) { categoryValue = it }
        MaterialAlertDialogBuilder(this).setTitle(R.string.filters_title)
            .setView(ScrollView(this).apply { addView(layout) })
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.reset_filters) { _, _ ->
                model.filter = model.filter.copy(organization = "", building = "", room = "", category = ""); render()
            }.setPositiveButton(R.string.apply_filters) { _, _ ->
                model.filter = model.filter.copy(organization = organizationValue, building = buildingValue,
                    room = roomValue, category = categoryValue); render()
            }.show()
    }

    private fun notice(message: Int) { Snackbar.make(findViewById(R.id.deviceRoot), message, Snackbar.LENGTH_LONG).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun getColorCompat(id: Int) = androidx.core.content.ContextCompat.getColor(this, id)
}

internal fun Spinner.setup(labels: List<String>, selected: Int = 0) {
    adapter = ArrayAdapter(context, R.layout.item_sort_spinner_selected, labels).apply {
        setDropDownViewResource(R.layout.item_sort_spinner_dropdown)
    }
    setSelection(selected, false)
}

internal class Selection(private val changed: (Int) -> Unit) : AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { changed(position) }
    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
}
