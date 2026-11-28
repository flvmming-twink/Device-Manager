package ru.misis.devicemanager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.misis.devicemanager.DeviceManagerApplication
import ru.misis.devicemanager.R
import ru.misis.devicemanager.data.*

data class InventoryState(
    val devices: List<Device> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val notice: Int? = null,
)

class DeviceListViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = (app as DeviceManagerApplication).repository
    private val databaseDispatcher = (app as DeviceManagerApplication).databaseDispatcher
    val state = MutableLiveData(InventoryState(loading = true))
    var filter = DeviceFilter()
    var pendingExport: List<Device>? = null
    private var refreshJob: Job? = null
    var mutating = false
        private set

    private val repositoryObserver: () -> Unit = { refresh() }

    init { repository.addDataObserver(repositoryObserver) }

    override fun onCleared() {
        repository.removeDataObserver(repositoryObserver)
        super.onCleared()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            state.value = state.value!!.copy(loading = true, failed = false)
            try {
                val devices = withContext(databaseDispatcher) { repository.all() }
                state.value = state.value!!.copy(devices = devices, loading = false)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { state.value = state.value!!.copy(loading = false, failed = true) }
        }
    }

    fun mutate(id: Long, operation: String) {
        if (mutating) return
        mutating = true
        viewModelScope.launch {
            try {
                val changed = withContext(databaseDispatcher) {
                    when (operation) {
                        "restore" -> repository.restore(id)
                        "purge" -> repository.deletePermanently(id)
                        else -> repository.moveToTrash(id)
                    }
                }
                state.value = state.value!!.copy(notice = if (!changed) R.string.missing_device else when (operation) {
                    "restore" -> R.string.device_restored
                    "purge" -> R.string.device_purged
                    else -> R.string.device_deleted
                })
                refresh()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { state.value = state.value!!.copy(notice = R.string.save_error) }
            finally { mutating = false }
        }
    }

    fun consumeNotice() { state.value = state.value!!.copy(notice = null) }
}

data class EditorState(
    val device: Device? = null,
    val suggestions: List<Device> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val completed: Boolean = false,
    val error: Int? = null,
    val invalid: Set<DeviceField> = emptySet(),
)

class DeviceEditorViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = (app as DeviceManagerApplication).repository
    private val databaseDispatcher = (app as DeviceManagerApplication).databaseDispatcher
    val state = MutableLiveData(EditorState())
    private var initialized = false
    private var requestedId = 0L
    private var requestedStatus: DeviceStatus? = null
    private var imported: Device? = null

    fun initialize(id: Long, status: DeviceStatus?, incoming: Device? = null) {
        if (initialized) return
        initialized = true
        requestedId = id
        requestedStatus = status
        imported = incoming
        load()
    }

    fun load() {
        viewModelScope.launch {
            state.value = state.value!!.copy(loading = true, error = null)
            try {
                val (record, suggestions) = withContext(databaseDispatcher) {
                    val prefs = getApplication<Application>().getSharedPreferences("device_editor", 0)
                    (if (requestedId == 0L) Device(organization = prefs.getString("organization", "МИСИС").orEmpty())
                    else repository.find(requestedId)) to repository.all().filter { it.deletedAt == null }
                }
                val valid = record?.takeIf { it.deletedAt == null }
                state.value = EditorState(
                    device = valid?.let { original ->
                        val draft = imported?.copy(id = original.id, createdAt = original.createdAt,
                            updatedAt = original.updatedAt, deletedAt = null) ?: original
                        if (requestedStatus == null) draft else draft.copy(status = requestedStatus!!)
                    },
                    suggestions = suggestions, loading = false,
                    error = if (valid == null) R.string.missing_device else null,
                )
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                android.util.Log.e("DeviceManager", "Unable to load device", error)
                state.value = state.value!!.copy(loading = false, error = R.string.load_error)
            }
        }
    }

    fun save(device: Device) {
        if (state.value!!.saving || state.value!!.loading) return
        val invalid = device.invalidFields()
        if (invalid.isNotEmpty()) {
            state.value = state.value!!.copy(invalid = invalid, error = null)
            return
        }
        state.value = state.value!!.copy(saving = true, invalid = emptySet(), error = null)
        viewModelScope.launch {
            try {
                when (val result = withContext(databaseDispatcher) { repository.save(device) }) {
                    is SaveResult.Saved -> {
                        getApplication<Application>().getSharedPreferences("device_editor", 0).edit()
                            .putString("organization", result.device.organization).apply()
                        state.value = state.value!!.copy(saving = false, completed = true)
                    }
                    is SaveResult.Invalid -> state.value = state.value!!.copy(saving = false, invalid = result.fields)
                    SaveResult.DuplicateInventory -> state.value = state.value!!.copy(saving = false, error = R.string.duplicate_inventory)
                    SaveResult.Missing -> state.value = state.value!!.copy(saving = false, error = R.string.missing_device)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { state.value = state.value!!.copy(saving = false, error = R.string.save_error) }
        }
    }

    fun delete() {
        val id = state.value?.device?.id ?: return
        if (state.value!!.saving) return
        state.value = state.value!!.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val changed = withContext(databaseDispatcher) { repository.moveToTrash(id) }
                state.value = state.value!!.copy(saving = false, completed = changed,
                    error = if (changed) null else R.string.missing_device)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { state.value = state.value!!.copy(saving = false, error = R.string.save_error) }
        }
    }
}
