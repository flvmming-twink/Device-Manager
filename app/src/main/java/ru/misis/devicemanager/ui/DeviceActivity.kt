package ru.misis.devicemanager.ui

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.misis.devicemanager.R
import ru.misis.devicemanager.data.DeviceStatus

abstract class DeviceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Old Android versions only support white navigation icons.
        if (Build.VERSION.SDK_INT >= 27) {
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (night) 0 else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else window.navigationBarColor = Color.rgb(15, 23, 42)
    }
}

internal fun DeviceActivity.statusLabel(status: DeviceStatus): String = getString(when (status) {
    DeviceStatus.ACTIVE -> R.string.status_active
    DeviceStatus.NEEDS_REPAIR -> R.string.status_needs_repair
    DeviceStatus.BROKEN -> R.string.status_broken
    DeviceStatus.WRITE_OFF -> R.string.status_write_off
})

internal fun DeviceActivity.statusColor(status: DeviceStatus): Int = ContextCompat.getColor(this, when (status) {
    DeviceStatus.ACTIVE -> R.color.device_active
    DeviceStatus.NEEDS_REPAIR -> R.color.device_repair
    DeviceStatus.BROKEN -> R.color.device_broken
    DeviceStatus.WRITE_OFF -> R.color.device_write_off
})

internal fun DeviceActivity.statusBackground(status: DeviceStatus): Int = ContextCompat.getColor(this, when (status) {
    DeviceStatus.ACTIVE -> R.color.device_active_bg
    DeviceStatus.NEEDS_REPAIR -> R.color.device_repair_bg
    DeviceStatus.BROKEN -> R.color.device_broken_bg
    DeviceStatus.WRITE_OFF -> R.color.device_write_off_bg
})
