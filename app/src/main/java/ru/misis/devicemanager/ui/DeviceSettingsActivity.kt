package ru.misis.devicemanager.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import ru.misis.devicemanager.R

class DeviceSettingsActivity : DeviceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_settings)
        findViewById<ImageButton>(R.id.settingsBack).setOnClickListener { finish() }
        val trash = intent.getBooleanExtra(EXTRA_TRASH, false)
        val rows = findViewById<LinearLayout>(R.id.settingsRows)

        if (!trash) {
            section(rows, R.string.settings_data_group)
            row(rows, R.string.export_csv, R.string.settings_export_csv_subtitle, ACTION_EXPORT_CSV)
            row(rows, R.string.export_xlsx, R.string.settings_export_xlsx_subtitle, ACTION_EXPORT_XLSX)
            row(rows, R.string.transfer_menu, R.string.settings_transfer_subtitle, ACTION_TRANSFER)
        }
        section(rows, R.string.settings_registry_group)
        row(rows, if (trash) R.string.inventory_title else R.string.trash_title,
            R.string.settings_trash_subtitle, if (trash) ACTION_INVENTORY else ACTION_TRASH)
        if (!trash) {
            row(rows, R.string.audit_menu, R.string.settings_audit_subtitle, ACTION_AUDIT)
            row(rows, R.string.bulk_edit_menu, R.string.settings_bulk_subtitle, ACTION_BULK)
        }
        section(rows, R.string.settings_app_group)
        row(rows, R.string.settings_cloud_title, R.string.settings_cloud_subtitle, ACTION_CLOUD)
        row(rows, R.string.theme_title, R.string.settings_theme_subtitle, ACTION_THEME)
        row(rows, R.string.qr_hub, R.string.settings_qr_subtitle, ACTION_QR)
        row(rows, R.string.about_action, R.string.settings_about_subtitle, ACTION_ABOUT)
    }

    private fun section(container: LinearLayout, label: Int) {
        container.addView(TextView(this).apply {
            setText(label)
            setTextColor(ContextCompat.getColor(this@DeviceSettingsActivity, R.color.device_secondary))
            textSize = 12f
            letterSpacing = .08f
            setPadding(dp(4), dp(24), dp(4), dp(8))
        })
    }

    private fun row(container: LinearLayout, title: Int, subtitle: Int, action: String) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(4), dp(12), dp(8), dp(12))
            val value = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
                setBackgroundResource(value.resourceId)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (action == ACTION_CLOUD) {
                    startActivity(Intent(this@DeviceSettingsActivity, CloudSyncActivity::class.java))
                    return@setOnClickListener
                }
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ACTION, action))
                finish()
            }
        }
        item.addView(TextView(this).apply {
            setText(title)
            setTextColor(ContextCompat.getColor(this@DeviceSettingsActivity, R.color.text_primary))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        item.addView(TextView(this).apply {
            setText(subtitle)
            setTextColor(ContextCompat.getColor(this@DeviceSettingsActivity, R.color.device_secondary))
            textSize = 13f
            setPadding(0, dp(4), 0, 0)
        })
        container.addView(item, LinearLayout.LayoutParams(-1, -2))
        container.addView(View(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@DeviceSettingsActivity, R.color.card_stroke))
        }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ACTION = "settings_action"
        const val EXTRA_TRASH = "settings_trash"
        const val ACTION_TRASH = "trash"
        const val ACTION_INVENTORY = "inventory"
        const val ACTION_EXPORT_CSV = "export_csv"
        const val ACTION_EXPORT_XLSX = "export_xlsx"
        const val ACTION_TRANSFER = "transfer"
        const val ACTION_AUDIT = "audit"
        const val ACTION_BULK = "bulk"
        const val ACTION_THEME = "theme"
        const val ACTION_QR = "qr"
        const val ACTION_ABOUT = "about"
        const val ACTION_CLOUD = "cloud"
    }
}
