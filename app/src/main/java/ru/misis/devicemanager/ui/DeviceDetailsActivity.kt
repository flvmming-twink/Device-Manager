package ru.misis.devicemanager.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.misis.devicemanager.DeviceManagerApplication
import ru.misis.devicemanager.R
import ru.misis.devicemanager.data.Device
import ru.misis.devicemanager.data.DeviceHistory
import ru.misis.devicemanager.data.DeviceStatus
import java.text.DateFormat
import java.util.Date

class DeviceDetailsActivity : DeviceActivity() {
    private val repository by lazy { (application as DeviceManagerApplication).repository }
    private var device: Device? = null
    private val editResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_detail)
        findViewById<MaterialToolbar>(R.id.deviceDetailToolbar).setNavigationOnClickListener { finish() }
        findViewById<View>(R.id.deviceDetailEdit).setOnClickListener {
            device?.let { editResult.launch(Intent(this, DeviceEditorActivity::class.java)
                .putExtra(DeviceEditorActivity.EXTRA_ID, it.id)) }
        }
        findViewById<View>(R.id.deviceDetailHistory).setOnClickListener {
            device?.let { startActivity(Intent(this, DeviceHistoryActivity::class.java)
                .putExtra(DeviceHistoryActivity.EXTRA_ID, it.id)) }
        }
        findViewById<View>(R.id.deviceDetailQr).setOnClickListener {
            device?.let { startActivity(Intent(this, QrActivity::class.java).putExtra(QrActivity.EXTRA_ID, it.id)) }
        }
        load()
    }

    private fun load() {
        findViewById<View>(R.id.deviceDetailProgress).isVisible = true
        lifecycleScope.launch {
            val record = withContext(Dispatchers.IO) { repository.find(intent.getLongExtra(EXTRA_ID, 0)) }
            findViewById<View>(R.id.deviceDetailProgress).isVisible = false
            if (record == null || record.deletedAt != null) {
                finish()
                return@launch
            }
            device = record
            bind(record)
        }
    }

    private fun bind(record: Device) {
        findViewById<TextView>(R.id.deviceDetailName).text = record.name
        findViewById<TextView>(R.id.deviceDetailInventory).text = getString(R.string.inventory_number_card, record.inventoryNumber)
        findViewById<TextView>(R.id.deviceDetailStatus).apply {
            text = statusLabel(record.status)
            setTextColor(statusColor(record.status))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(statusBackground(record.status))
            }
        }
        val fields = findViewById<LinearLayout>(R.id.deviceDetailFields)
        fields.removeAllViews()
        listOf(
            R.string.detail_serial to record.serialNumber,
            R.string.detail_category to record.category,
            R.string.detail_organization to record.organization,
            R.string.detail_building to record.building,
            R.string.detail_room to record.room,
            R.string.detail_responsible to record.responsible,
            R.string.detail_write_off_reason to record.writeOffReason,
            R.string.detail_notes to record.notes,
        ).filter { it.second.isNotBlank() }.forEach { (label, value) -> addField(fields, label, value) }
        addField(fields, R.string.detail_added, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.createdAt)))
        addField(fields, R.string.detail_updated, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.updatedAt)))
    }

    private fun addField(parent: LinearLayout, label: Int, value: String) {
        parent.addView(TextView(this).apply {
            setText(label)
            textSize = 14f
            setTextColor(getColorCompat(R.color.device_secondary))
            setPadding(0, dp(14), 0, dp(3))
        })
        parent.addView(TextView(this).apply {
            text = value
            textSize = 16f
            setTextColor(getColorCompat(R.color.text_primary))
            setTextIsSelectable(true)
            gravity = Gravity.START
        })
    }

    private fun getColorCompat(id: Int) = androidx.core.content.ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { const val EXTRA_ID = "detail_device_id" }
}

class DeviceHistoryActivity : DeviceActivity() {
    private val repository by lazy { (application as DeviceManagerApplication).repository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_history)
        findViewById<MaterialToolbar>(R.id.historyToolbar).setNavigationOnClickListener { finish() }
        load()
    }

    private fun load() {
        val id = intent.getLongExtra(EXTRA_ID, 0)
        lifecycleScope.launch {
            val (device, history) = withContext(Dispatchers.IO) { repository.find(id) to repository.history(id) }
            if (device == null) { finish(); return@launch }
            findViewById<TextView>(R.id.historyDeviceSummary).text = "${device.name}\n${getString(R.string.inventory_number_card, device.inventoryNumber)}"
            val list = findViewById<LinearLayout>(R.id.historyList)
            list.removeAllViews()
            if (history.isEmpty()) {
                list.addView(TextView(this@DeviceHistoryActivity).apply {
                    setText(R.string.history_empty)
                    textSize = 16f
                    setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceHistoryActivity, R.color.device_secondary))
                    setPadding(0, dp(18), 0, dp(18))
                })
            } else history.forEachIndexed { index, event ->
                if (index > 0) list.addView(View(this@DeviceHistoryActivity).apply {
                    setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@DeviceHistoryActivity, R.color.device_divider))
                    layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12) }
                })
                addEvent(list, event)
            }
        }
    }

    private fun addEvent(parent: LinearLayout, event: DeviceHistory) {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(event.occurredAt))
        parent.addView(TextView(this).apply {
            text = date
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceHistoryActivity, R.color.device_secondary))
            setPadding(0, dp(14), 0, dp(3))
        })
        parent.addView(TextView(this).apply {
            text = eventText(event)
            textSize = 16f
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceHistoryActivity, R.color.text_primary))
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(4))
        })
    }

    private fun eventText(event: DeviceHistory): String {
        val dash = getString(R.string.history_empty_value)
        return when (event.eventType) {
            "CREATED" -> getString(R.string.history_created, event.newValue.ifBlank { dash })
            "IMPORTED" -> getString(R.string.history_imported)
            "BASELINE" -> event.newValue.ifBlank { getString(R.string.history_migrated) }
            "MOVED" -> getString(R.string.history_moved, event.oldValue.ifBlank { dash }, event.newValue.ifBlank { dash })
            "STATUS" -> getString(R.string.history_status_changed,
                statusLabel(runCatching { DeviceStatus.valueOf(event.oldValue) }.getOrDefault(DeviceStatus.ACTIVE)),
                statusLabel(runCatching { DeviceStatus.valueOf(event.newValue) }.getOrDefault(DeviceStatus.ACTIVE)))
            "AUDIT_FOUND" -> getString(R.string.history_audit_found, event.newValue)
            "TRASHED" -> getString(R.string.history_trashed)
            "RESTORED" -> getString(R.string.history_restored)
            "PURGED" -> getString(R.string.history_purged)
            else -> getString(R.string.history_field_changed, fieldLabel(event.fieldName), event.oldValue.ifBlank { dash }, event.newValue.ifBlank { dash })
        }
    }

    private fun fieldLabel(field: String) = getString(when (field) {
        "name" -> R.string.detail_name
        "inventory" -> R.string.detail_inventory
        "serial" -> R.string.detail_serial
        "category" -> R.string.detail_category
        "responsible" -> R.string.detail_responsible
        "reason" -> R.string.detail_write_off_reason
        "notes" -> R.string.detail_notes
        else -> R.string.detail_device
    })

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { const val EXTRA_ID = "history_device_id" }
}
