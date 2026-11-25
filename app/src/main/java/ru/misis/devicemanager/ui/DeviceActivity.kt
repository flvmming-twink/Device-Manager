package ru.misis.devicemanager.ui

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

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
