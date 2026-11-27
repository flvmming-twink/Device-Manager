package ru.misis.devicemanager.qr

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.misis.devicemanager.DeviceManagerApplication
import ru.misis.devicemanager.data.Device
import ru.misis.devicemanager.data.normalizedKey

enum class QrPage { HOME, EXPORT, PREVIEW, KEYS }
data class QrState(
    val page: QrPage = QrPage.HOME,
    val busy: Boolean = false,
    val device: Device? = null,
    val existing: Device? = null,
    val secure: Boolean = false,
    val raw: String? = null,
    val bitmap: Bitmap? = null,
    val qrIssue: String? = null,
    val keys: List<QrKeyStore.Entry> = emptyList(),
    val activeId: String? = null,
    val message: String? = null,
)

class QrViewModel(app: Application) : AndroidViewModel(app) {
    val state = MutableLiveData(QrState())
    private val repository = (app as DeviceManagerApplication).repository
    private val store = QrKeyStore(app)
    private val resolver = app.contentResolver
    private var initialized = false
    // Retained across Activity recreation while the system document picker is foregrounded.
    var pendingFile: ByteArray? = null
    var pendingFileName: String? = null

    fun initialize(id: Long) {
        if (initialized) return
        initialized = true
        if (id == 0L) keys(false) else runTask {
            val device = repository.find(id)?.takeIf { it.deletedAt == null } ?: throw QrException("Устройство недоступно")
            val entries = store.list()
            output(device, false).copy(keys = entries, activeId = store.active()?.id)
        }
    }

    fun generate(secure: Boolean) {
        val device = state.value?.device ?: return
        runTask { output(device, secure) }
    }

    private fun output(device: Device, secure: Boolean): QrState {
        val raw = if (secure) DeviceQrCodec.encodeSecure(device,
            store.active()?.pair?.public ?: throw QrException("Сначала создайте или импортируйте ключ организации в разделе «Ключи»"))
        else DeviceQrCodec.encodeOpen(device)
        var issue: String? = null
        val bitmap = try { QrImages.render(raw) } catch (e: QrException) { issue = e.message; null }
        return state.value!!.copy(page = QrPage.EXPORT, device = device, raw = raw, bitmap = bitmap, secure = secure,
            qrIssue = issue, existing = null)
    }

    fun read(raw: String) = runTask { preview(raw) }
    fun readFile(uri: Uri, image: Boolean) = runTask {
        val raw = resolver.openInputStream(uri)?.use {
            if (image) QrImages.decode(it) else String(QrImages.readLimited(it, DeviceQrCodec.MAX_JSON_BYTES), Charsets.UTF_8)
        } ?: throw QrException("Не удалось прочитать файл")
        preview(raw)
    }
    private fun preview(raw: String): QrState {
        val device = DeviceQrCodec.decode(raw) { id -> store.find(id)?.pair?.private }
        val existing = repository.all().firstOrNull {
            normalizedKey(it.organization) == normalizedKey(device.organization) &&
                normalizedKey(it.inventoryNumber) == normalizedKey(device.inventoryNumber)
        }
        return state.value!!.copy(page = QrPage.PREVIEW, device = device, existing = existing,
            secure = DeviceQrCodec.isSecure(raw), raw = raw, bitmap = null, qrIssue = null)
    }

    fun home() { if (!state.value!!.busy) state.value = state.value!!.copy(page = QrPage.HOME) }
    fun keys(show: Boolean = true) = runTask {
        state.value!!.copy(page = if (show) QrPage.KEYS else state.value!!.page, keys = store.list(), activeId = store.active()?.id)
    }
    fun createKey(label: String) = runTask {
        val entry = store.create(label)
        state.value!!.copy(page = QrPage.KEYS, keys = store.list(), activeId = entry.id, message = "Ключ создан. Сохраните файл ключа для переноса и восстановления доступа.")
    }
    fun selectKey(id: String) = runTask {
        store.select(id); state.value!!.copy(activeId = id, message = "Ключ выбран для новых QR")
    }
    fun importKey(uri: Uri, password: CharArray) = runTask {
        try {
            val raw = resolver.openInputStream(uri)?.use { String(QrImages.readLimited(it, 16_384), Charsets.UTF_8) }
                ?: throw QrException("Не удалось прочитать файл")
            val entry = store.import(raw, password)
            state.value!!.copy(page = QrPage.KEYS, keys = store.list(), activeId = entry.id, message = "Ключ импортирован")
        } finally { password.fill('\u0000') }
    }

    fun prepareKeyFile(password: CharArray) = runTask {
        try {
            val entry = store.active() ?: throw QrException("Сначала создайте или импортируйте ключ")
            pendingFile = store.export(entry, password).toByteArray(Charsets.UTF_8)
            pendingFileName = "DeviceManager-key-${entry.id.take(8)}.json"
            state.value!!.copy(message = "KEY_FILE_READY")
        } finally { password.fill('\u0000') }
    }
    fun prepareOutput(image: Boolean) = runTask {
        val current = state.value!!
        pendingFile = if (image) {
            val bitmap = current.bitmap ?: throw QrException(current.qrIssue ?: "QR ещё не готов")
            java.io.ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        } else (current.raw ?: throw QrException("Нет данных для сохранения")).toByteArray(Charsets.UTF_8)
        pendingFileName = "DeviceManager-${if (current.secure) "secure" else "open"}.${if (image) "png" else "json"}"
        current.copy(message = if (image) "PNG_FILE_READY" else "JSON_FILE_READY")
    }
    fun writeFile(uri: Uri?) {
        val bytes = pendingFile; pendingFile = null; pendingFileName = null
        if (uri == null) return
        runTask {
            if (bytes == null) throw QrException("Экран был закрыт системой. Повторите сохранение файла.")
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw QrException("Не удалось записать файл")
            state.value!!.copy(message = "Файл сохранён")
        }
    }
    fun consumeMessage() { state.value = state.value!!.copy(message = null) }

    private fun runTask(work: () -> QrState) {
        if (state.value!!.busy) return
        state.value = state.value!!.copy(busy = true, message = null)
        viewModelScope.launch {
            try { state.value = withContext(Dispatchers.IO) { work() }.copy(busy = false) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                state.value = state.value!!.copy(busy = false,
                    message = (e as? QrException)?.message ?: "Не удалось выполнить действие. Повторите попытку.")
            }
        }
    }
}
