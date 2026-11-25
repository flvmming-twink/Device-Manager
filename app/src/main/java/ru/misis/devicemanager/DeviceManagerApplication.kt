package ru.misis.devicemanager

import android.app.Application
import ru.misis.devicemanager.core.ThemeManager
import ru.misis.devicemanager.data.DeviceRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

open class DeviceManagerApplication : Application() {
    val repository by lazy { DeviceRepository(this) }
    open val databaseDispatcher: CoroutineDispatcher = Dispatchers.IO
    override fun onCreate() {
        super.onCreate()
        ThemeManager.applySavedTheme(this)
    }
}
