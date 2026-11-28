package ru.misis.devicemanager.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CloudSyncState(
    val configured: Boolean = false,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val workspaceCode: String? = null,
    val error: String? = null,
)

/** Online workspace sync; SQLite stays the app-facing cache and Firestore is the shared source. */
class CloudSyncManager(context: Context, private val repository: DeviceRepository) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushMutex = Mutex()
    private val listenerMutex = Mutex()
    private var listener: ListenerRegistration? = null
    private var listenerCode: String? = null
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { reconnect() }
        override fun onLost(network: Network) {
            updateState { copy(connected = false) }
        }
    }
    private val mutableState = MutableLiveData(CloudSyncState(configured = isConfigured(), workspaceCode = savedCode()))
    val state: LiveData<CloudSyncState> = mutableState

    private val localObserver: () -> Unit = { scope.launch { savedCode()?.let { code -> flushPending(code) } } }

    init {
        repository.addLocalMutationObserver(localObserver)
        connectivity.registerDefaultNetworkCallback(networkCallback)
    }

    fun isConfigured(): Boolean = FirebaseApp.getApps(appContext).isNotEmpty()

    fun startIfConnected() {
        reconnect()
    }

    private fun reconnect() {
        val code = savedCode() ?: return
        if (!isConfigured()) {
            updateState { copy(configured = false, workspaceCode = code) }
            return
        }
        scope.launch {
            listenerMutex.withLock {
                try {
                    signIn()
                    ensureListener(code)
                    flushPending(code)
                } catch (error: Exception) {
                    setError(error)
                }
            }
        }
    }

    suspend fun createWorkspace(): String {
        requireConfigured()
        updateState { copy(busy = true, error = null, configured = true) }
        try {
            val user = signIn()
            val code = generateCode()
            val workspace = firestore().collection("workspaces").document(code)
            workspace.set(mapOf("ownerUid" to user.uid, "schemaVersion" to 1, "createdAt" to System.currentTimeMillis())).awaitResult()
            workspace.collection("members").document(user.uid).set(
                mapOf("role" to "owner", "joinedAt" to System.currentTimeMillis()),
            ).awaitResult()
            saveCode(code)
            withContext(Dispatchers.IO) { repository.prepareCloudSync() }
            startListener(code)
            val synced = flushPending(code)
            updateState { copy(busy = false, configured = true, connected = synced || connected, workspaceCode = code,
                error = if (synced) null else error) }
            return formatCode(code)
        } catch (error: Exception) {
            updateState { copy(busy = false, configured = true, error = friendly(error)) }
            throw error
        }
    }

    suspend fun joinWorkspace(rawCode: String): String {
        requireConfigured()
        val code = normalizeCode(rawCode)
        require(code.length == CODE_LENGTH && code.all { it in CODE_ALPHABET }) { "Проверьте код общей базы и введите его без ошибок." }
        updateState { copy(busy = true, error = null, configured = true) }
        try {
            val user = signIn()
            val workspace = firestore().collection("workspaces").document(code)
            workspace.collection("members").document(user.uid).set(
                mapOf("role" to "member", "joinedAt" to System.currentTimeMillis()),
            ).awaitResult()
            val cloudDevices = workspace.collection("devices").get().awaitResult()
            withContext(Dispatchers.IO) {
                cloudDevices.documents.forEach(::applySnapshot)
                repository.prepareCloudSync()
            }
            saveCode(code)
            startListener(code)
            val synced = flushPending(code)
            updateState { copy(busy = false, configured = true, connected = synced || connected, workspaceCode = code,
                error = if (synced) null else error) }
            return formatCode(code)
        } catch (error: Exception) {
            updateState { copy(busy = false, configured = true, error = friendly(error)) }
            throw error
        }
    }

    fun shareCode(): String? = savedCode()?.let(::formatCode)

    private suspend fun startListener(code: String) = listenerMutex.withLock {
        signIn()
        ensureListener(code)
    }

    private fun ensureListener(code: String) {
        if (listenerCode == code && listener != null) return
        listener?.remove()
        listener = workspaceDevices(code).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) {
                setError(error)
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            updateState { copy(configured = true, connected = !snapshot.metadata.isFromCache, workspaceCode = code, error = null) }
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        snapshot.documentChanges.filter { it.type.name != "REMOVED" }.forEach { applySnapshot(it.document) }
                        snapshot.documentChanges.filter { it.type.name == "REMOVED" }.forEach {
                            repository.removeCloudDevice(it.document.id)
                        }
                    }
                    flushPending(code)
                }.onFailure(::setError)
            }
        }
        listenerCode = code
    }

    private suspend fun flushPending(code: String): Boolean = flushMutex.withLock {
        if (!isConfigured() || savedCode() != code) return@withLock false
        val reference = workspaceDevices(code)
        try {
            val changes = withContext(Dispatchers.IO) { repository.pendingCloudChanges() }
            changes.chunked(MAX_BATCH_WRITES).forEach { chunk ->
                val batch = firestore().batch()
                chunk.forEach { change ->
                    val document = reference.document(change.cloudId)
                    when (change.operation) {
                        "DELETE" -> batch.delete(document)
                        "UPSERT" -> {
                            val device = checkNotNull(change.device) { "Local device for cloud update is missing" }
                            batch.set(document, device.toCloudFields(change.previousCloudId))
                        }
                    }
                }
                batch.commit().awaitResult()
                withContext(Dispatchers.IO) { chunk.forEach { repository.acknowledgeCloudChange(it.cloudId, it.operation, it.changedAt) } }
            }
            updateState { copy(configured = true, error = null) }
            true
        } catch (error: Exception) {
            setError(error)
            false
        }
    }

    private fun applySnapshot(snapshot: DocumentSnapshot) {
        val status = runCatching { DeviceStatus.valueOf(snapshot.getString("status") ?: "") }
            .getOrElse { throw IllegalStateException("В общей базе найдено устройство с неподдерживаемым статусом.", it) }
        val deletedAt = snapshot.getLong("deletedAt")
        val device = Device(
            name = snapshot.getString("name").orEmpty(),
            inventoryNumber = snapshot.getString("inventoryNumber").orEmpty(),
            serialNumber = snapshot.getString("serialNumber").orEmpty(),
            category = snapshot.getString("category").orEmpty(),
            organization = snapshot.getString("organization").orEmpty(),
            building = snapshot.getString("building").orEmpty(),
            room = snapshot.getString("room").orEmpty(),
            responsible = snapshot.getString("responsible").orEmpty(),
            notes = snapshot.getString("notes").orEmpty(),
            status = status,
            writeOffReason = snapshot.getString("writeOffReason").orEmpty(),
            createdAt = snapshot.getLong("createdAt") ?: 0,
            updatedAt = snapshot.getLong("updatedAt") ?: 0,
            deletedAt = deletedAt,
        )
        repository.applyCloudDevice(snapshot.id, snapshot.getString("previousCloudId"), device)
    }

    private fun Device.toCloudFields(previousCloudId: String?): Map<String, Any?> = mapOf(
        "name" to name, "inventoryNumber" to inventoryNumber, "serialNumber" to serialNumber,
        "category" to category, "organization" to organization, "building" to building,
        "room" to room, "responsible" to responsible, "notes" to notes, "status" to status.name,
        "writeOffReason" to writeOffReason, "createdAt" to createdAt, "updatedAt" to updatedAt,
        "deletedAt" to deletedAt, "previousCloudId" to previousCloudId,
    )

    private suspend fun signIn() = FirebaseAuth.getInstance().currentUser
        ?: FirebaseAuth.getInstance().signInAnonymously().awaitResult().user
        ?: error("Не удалось создать анонимную учётную запись Firebase.")

    private fun firestore() = FirebaseFirestore.getInstance()
    private fun workspaceDevices(code: String) = firestore().collection("workspaces").document(code).collection("devices")

    private fun saveCode(code: String) {
        preferences.edit().putString(KEY_WORKSPACE_CODE, code).apply()
        updateState { copy(workspaceCode = code) }
    }

    private fun savedCode(): String? = preferences.getString(KEY_WORKSPACE_CODE, null)

    private fun requireConfigured() {
        check(isConfigured()) { "Firebase ещё не подключён. Добавьте app/google-services.json по инструкции проекта." }
    }

    private fun setError(error: Throwable) {
        updateState { copy(busy = false, error = friendly(error)) }
    }

    private fun friendly(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            "PERMISSION_DENIED" in message || "PERMISSION_DENIED" in (error.cause?.message.orEmpty()) ->
                "Firebase отклонил доступ. Проверьте, что включены Anonymous Authentication и опубликованы правила Firestore из firebase/firestore.rules."
            "NOT_FOUND" in message || "NOT_FOUND" in (error.cause?.message.orEmpty()) -> "Общая база с таким кодом не найдена. Проверьте код и подключение к сети."
            message.isNotBlank() -> message
            else -> "Не удалось синхронизировать реестр. Проверьте подключение к интернету и настройки Firebase."
        }
    }

    private inline fun updateState(transform: CloudSyncState.() -> CloudSyncState) {
        mutableState.postValue((mutableState.value ?: CloudSyncState()).transform())
    }

    private fun normalizeCode(code: String) = code.trim().uppercase().filterNot { it == '-' || it.isWhitespace() }

    private fun generateCode(): String {
        val bytes = ByteArray(CODE_LENGTH * 5 / 8)
        SecureRandom().nextBytes(bytes)
        var buffer = 0
        var bits = 0
        val encoded = StringBuilder(CODE_LENGTH)
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                encoded.append(CODE_ALPHABET[(buffer shr bits) and 31])
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return encoded.toString()
    }

    private fun formatCode(code: String) = code.chunked(4).joinToString("-")

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
        addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    }

    companion object {
        private const val PREFERENCES = "shared_workspace"
        private const val KEY_WORKSPACE_CODE = "workspace_code"
        private const val CODE_LENGTH = 16
        private const val MAX_BATCH_WRITES = 100
        private const val CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    }
}
