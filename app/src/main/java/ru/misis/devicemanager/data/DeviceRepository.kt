package ru.misis.devicemanager.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

sealed class SaveResult {
    data class Saved(val device: Device) : SaveResult()
    data class Invalid(val fields: Set<DeviceField>) : SaveResult()
    object DuplicateInventory : SaveResult()
    object Missing : SaveResult()
}

data class BatchImportResult(val added: Int, val duplicates: Int)
data class AuditScanResult(val expected: Boolean, val alreadyFound: Boolean, val knownDeviceId: Long?)

/** SQLite is the single source of truth for devices, audit sessions and local history. */
class DeviceRepository(context: Context, name: String = "device_manager.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, DATABASE_VERSION) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        createDevicesTable(db)
        createDeviceIndex(db)
        createHistoryTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE devices RENAME TO devices_v1")
            createDevicesTable(db)
            db.execSQL("""
                INSERT INTO devices (_id, name, inventory_number, inventory_key, serial_number, category,
                    organization, organization_key, building, room, responsible, notes, status, write_off_reason,
                    created_at, updated_at, deleted_at)
                SELECT _id, name, inventory_number, inventory_key, serial_number, category,
                    organization, organization_key, building, room, responsible, notes, status, write_off_reason,
                    created_at, updated_at, deleted_at FROM devices_v1
            """.trimIndent())
            db.execSQL("DROP TABLE devices_v1")
            createDeviceIndex(db)
            createHistoryTables(db)
            db.execSQL("""
                INSERT INTO device_history (device_id, inventory_number, device_name, event_type,
                    field_name, old_value, new_value, occurred_at)
                SELECT _id, inventory_number, name, 'BASELINE', 'device', '',
                    'История изменений начата в версии 1.1.2; прежние перемещения не записаны', updated_at
                FROM devices
            """.trimIndent())
            return
        }
        error("Unsupported inventory database migration: $oldVersion -> $newVersion")
    }

    private fun createDevicesTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE devices (
              _id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL, inventory_number TEXT NOT NULL, inventory_key TEXT NOT NULL,
              serial_number TEXT NOT NULL, category TEXT NOT NULL,
              organization TEXT NOT NULL, organization_key TEXT NOT NULL,
              building TEXT NOT NULL, room TEXT NOT NULL, responsible TEXT NOT NULL, notes TEXT NOT NULL,
              status TEXT NOT NULL CHECK(status IN ('ACTIVE', 'NEEDS_REPAIR', 'BROKEN', 'WRITE_OFF')),
              write_off_reason TEXT NOT NULL, created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL, deleted_at INTEGER,
              UNIQUE(organization_key, inventory_key)
            )
        """.trimIndent())
    }

    private fun createDeviceIndex(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX devices_status_location ON devices(deleted_at, status, organization_key, building, room)")
    }

    private fun createHistoryTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE device_history (
              _id INTEGER PRIMARY KEY AUTOINCREMENT,
              device_id INTEGER NOT NULL, inventory_number TEXT NOT NULL, device_name TEXT NOT NULL,
              event_type TEXT NOT NULL, field_name TEXT NOT NULL,
              old_value TEXT NOT NULL, new_value TEXT NOT NULL, occurred_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX device_history_device_time ON device_history(device_id, occurred_at DESC, _id DESC)")
        db.execSQL("""
            CREATE TABLE room_audits (
              _id INTEGER PRIMARY KEY AUTOINCREMENT,
              organization TEXT NOT NULL, organization_key TEXT NOT NULL,
              building TEXT NOT NULL, room TEXT NOT NULL, room_key TEXT NOT NULL,
              started_at INTEGER NOT NULL, completed_at INTEGER
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE room_audit_items (
              _id INTEGER PRIMARY KEY AUTOINCREMENT,
              audit_id INTEGER NOT NULL REFERENCES room_audits(_id) ON DELETE CASCADE,
              device_id INTEGER, organization TEXT NOT NULL, organization_key TEXT NOT NULL,
              building TEXT NOT NULL, room TEXT NOT NULL, name TEXT NOT NULL,
              inventory_number TEXT NOT NULL, inventory_key TEXT NOT NULL, status TEXT NOT NULL,
              expected INTEGER NOT NULL, found_at INTEGER,
              UNIQUE(audit_id, organization_key, inventory_key)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX room_audit_items_session ON room_audit_items(audit_id, expected, found_at)")
    }

    @Synchronized fun all(): List<Device> = readableDatabase.query(
        "devices", null, null, null, null, null, "updated_at DESC, _id ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.device()) } }

    @Synchronized fun find(id: Long): Device? = readableDatabase.query(
        "devices", null, "_id=?", arrayOf(id.toString()), null, null, null,
    ).use { if (it.moveToFirst()) it.device() else null }

    @Synchronized fun history(deviceId: Long): List<DeviceHistory> = readableDatabase.query(
        "device_history", null, "device_id=?", arrayOf(deviceId.toString()), null, null,
        "occurred_at DESC, _id DESC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.history()) } }

    @Synchronized fun save(raw: Device): SaveResult {
        val device = raw.normalized()
        val errors = device.invalidFields()
        if (errors.isNotEmpty()) return SaveResult.Invalid(errors)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previous = if (device.id == 0L) null else find(device.id)
            if (device.id != 0L && (previous == null || previous.deletedAt != null)) return SaveResult.Missing
            val now = System.currentTimeMillis()
            val saved = device.copy(createdAt = previous?.createdAt ?: now, updatedAt = now, deletedAt = null)
            val values = saved.values()
            val id = if (device.id == 0L) db.insertOrThrow("devices", null, values) else {
                db.update("devices", values, "_id=?", arrayOf(device.id.toString()))
                device.id
            }
            val result = saved.copy(id = id)
            if (previous == null) recordHistory(db, result, "CREATED", "device", "", fullLocation(result), now)
            else recordChanges(db, previous, result, now)
            db.setTransactionSuccessful()
            return SaveResult.Saved(result)
        } catch (_: SQLiteConstraintException) {
            return SaveResult.DuplicateInventory
        } finally { db.endTransaction() }
    }

    @Synchronized fun updateMany(
        ids: Collection<Long>, location: DeviceLocation?, responsible: String?, status: DeviceStatus?, writeOffReason: String?,
    ): Int {
        require(ids.isNotEmpty() && ids.size <= 500) { "Invalid bulk selection" }
        if (location != null) {
            require(location.organization.isNotBlank() && location.room.isNotBlank()) { "Organization and room are required" }
        }
        if (status == DeviceStatus.WRITE_OFF) require(!writeOffReason.isNullOrBlank()) { "Write-off reason is required" }
        val db = writableDatabase
        val now = System.currentTimeMillis()
        var changed = 0
        db.beginTransaction()
        try {
            ids.distinct().forEach { id ->
                val previous = find(id)?.takeIf { it.deletedAt == null } ?: return@forEach
                val next = previous.copy(
                    organization = location?.organization?.trim() ?: previous.organization,
                    building = location?.building?.trim() ?: previous.building,
                    room = location?.room?.trim() ?: previous.room,
                    responsible = responsible?.trim() ?: previous.responsible,
                    status = status ?: previous.status,
                    writeOffReason = when {
                        status == DeviceStatus.WRITE_OFF -> writeOffReason.orEmpty().trim()
                        status != null -> ""
                        else -> previous.writeOffReason
                    },
                    updatedAt = previous.updatedAt,
                ).normalized()
                if (next.invalidFields().isNotEmpty()) throw IllegalArgumentException("Invalid bulk update")
                if (next != previous) {
                    val saved = next.copy(updatedAt = now)
                    db.update("devices", saved.values(), "_id=?", arrayOf(id.toString()))
                    recordChanges(db, previous, saved, now)
                    changed++
                }
            }
            db.setTransactionSuccessful()
            return changed
        } finally { db.endTransaction() }
    }

    @Synchronized fun moveToTrash(id: Long): Boolean = changeDeletedAt(id, System.currentTimeMillis(), false)
    @Synchronized fun restore(id: Long): Boolean = changeDeletedAt(id, null, true)

    private fun changeDeletedAt(id: Long, timestamp: Long?, wasDeleted: Boolean): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val device = find(id) ?: return false
            if ((device.deletedAt != null) != wasDeleted) return false
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                if (timestamp == null) putNull("deleted_at") else put("deleted_at", timestamp)
                put("updated_at", now)
            }
            if (db.update("devices", values, "_id=?", arrayOf(id.toString())) != 1) return false
            val changed = device.copy(deletedAt = timestamp, updatedAt = now)
            recordHistory(db, changed, if (wasDeleted) "RESTORED" else "TRASHED", "deleted", device.deletedAt?.toString().orEmpty(), timestamp?.toString().orEmpty(), now)
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    @Synchronized fun deletePermanently(id: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val device = find(id)?.takeIf { it.deletedAt != null } ?: return false
            val now = System.currentTimeMillis()
            recordHistory(db, device, "PURGED", "deleted", "В корзине", "Удалено навсегда", now)
            val deleted = db.delete("devices", "_id=? AND deleted_at IS NOT NULL", arrayOf(id.toString())) == 1
            if (deleted) db.setTransactionSuccessful()
            return deleted
        } finally { db.endTransaction() }
    }

    /** Adds portable snapshots without replacing local records or restoring trashed ones. */
    @Synchronized fun importNew(rawDevices: List<Device>): BatchImportResult {
        require(rawDevices.isNotEmpty() && rawDevices.size <= 250) { "Invalid transfer batch size" }
        val devices = rawDevices.map { it.normalized() }
        require(devices.all { it.invalidFields().isEmpty() }) { "Invalid transfer record" }
        val db = writableDatabase
        val timestamp = System.currentTimeMillis()
        var added = 0
        var duplicates = 0
        db.beginTransaction()
        try {
            devices.forEach { device ->
                val imported = device.copy(id = 0, createdAt = timestamp, updatedAt = timestamp, deletedAt = null)
                val id = db.insertWithOnConflict("devices", null, imported.values(), SQLiteDatabase.CONFLICT_IGNORE)
                if (id == -1L) duplicates++ else {
                    added++
                    recordHistory(db, imported.copy(id = id), "IMPORTED", "device", "", "Получено из файла", timestamp)
                }
            }
            db.setTransactionSuccessful()
            return BatchImportResult(added, duplicates)
        } finally { db.endTransaction() }
    }

    @Synchronized fun startRoomAudit(organization: String, building: String, room: String): RoomAudit {
        val location = DeviceLocation(organization.trim(), building.trim(), room.trim())
        require(location.organization.isNotBlank() && location.room.isNotBlank()) { "Organization and room are required" }
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        val id: Long
        try {
            val values = ContentValues().apply {
                put("organization", location.organization); put("organization_key", normalizedKey(location.organization))
                put("building", location.building); put("room", location.room); put("room_key", normalizedKey(location.room))
                put("started_at", now); putNull("completed_at")
            }
            id = db.insertOrThrow("room_audits", null, values)
            all().filter { device -> device.deletedAt == null &&
                normalizedKey(device.organization) == normalizedKey(location.organization) &&
                normalizedKey(device.building) == normalizedKey(location.building) &&
                normalizedKey(device.room) == normalizedKey(location.room)
            }.forEach { insertAuditItem(db, id, it, expected = true, foundAt = null) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return getRoomAudit(id) ?: error("Audit session was not saved")
    }

    @Synchronized fun roomAudit(id: Long): RoomAudit? = getRoomAudit(id)

    @Synchronized fun recentRoomAudits(limit: Int = 20): List<RoomAudit> {
        val ids = readableDatabase.query("room_audits", arrayOf("_id"), null, null, null, null,
            "started_at DESC, _id DESC", limit.coerceIn(1, 100).toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        return ids.mapNotNull(::getRoomAudit)
    }

    @Synchronized fun recordAuditScan(auditId: Long, scanned: Device): AuditScanResult {
        val device = scanned.normalized()
        require(device.invalidFields().isEmpty()) { "QR is missing required equipment fields" }
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val audit = getRoomAuditHeader(db, auditId) ?: throw IllegalArgumentException("Audit session not found")
            require(audit.completedAt == null) { "Audit session is already complete" }
            val existing = findByIdentity(device.organization, device.inventoryNumber)
            val match = db.query("room_audit_items", null,
                "audit_id=? AND organization_key=? AND inventory_key=?",
                arrayOf(auditId.toString(), normalizedKey(device.organization), normalizedKey(device.inventoryNumber)), null, null, null)
                .use { if (it.moveToFirst()) it.auditItem() else null }
            val expected = match?.expected == true
            val alreadyFound = match?.foundAt != null
            if (match == null) {
                insertAuditItem(db, auditId, device.copy(id = existing?.id ?: 0), expected = false, foundAt = now)
            } else if (!alreadyFound) {
                db.update("room_audit_items", ContentValues().apply { put("found_at", now) }, "_id=?", arrayOf(match.id.toString()))
            }
            if (!alreadyFound && existing != null) {
                val current = find(existing.id) ?: existing
                recordHistory(db, current, "AUDIT_FOUND", "location", fullLocation(current), audit.location, now)
            }
            db.setTransactionSuccessful()
            return AuditScanResult(expected, alreadyFound, existing?.id)
        } finally { db.endTransaction() }
    }

    @Synchronized fun finishRoomAudit(id: Long): RoomAudit? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE room_audits SET completed_at=COALESCE(completed_at, ?) WHERE _id=?",
                arrayOf(System.currentTimeMillis(), id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return getRoomAudit(id)
    }

    private data class AuditHeader(
        val id: Long, val organization: String, val building: String, val room: String,
        val startedAt: Long, val completedAt: Long?,
    ) { val location get() = listOf(organization, building, room).filter(String::isNotBlank).joinToString(" · ") }

    private fun getRoomAudit(id: Long): RoomAudit? {
        val db = readableDatabase
        val header = getRoomAuditHeader(db, id) ?: return null
        val items = db.query("room_audit_items", null, "audit_id=?", arrayOf(id.toString()), null, null,
            "expected DESC, found_at IS NULL DESC, name COLLATE NOCASE, inventory_number COLLATE NOCASE")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.auditItem()) } }
        return RoomAudit(header.id, header.organization, header.building, header.room, header.startedAt, header.completedAt, items)
    }

    private fun getRoomAuditHeader(db: SQLiteDatabase, id: Long): AuditHeader? = db.query(
        "room_audits", null, "_id=?", arrayOf(id.toString()), null, null, null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else AuditHeader(
            cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
            cursor.getString(cursor.getColumnIndexOrThrow("organization")),
            cursor.getString(cursor.getColumnIndexOrThrow("building")),
            cursor.getString(cursor.getColumnIndexOrThrow("room")),
            cursor.getLong(cursor.getColumnIndexOrThrow("started_at")),
            if (cursor.isNull(cursor.getColumnIndexOrThrow("completed_at"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("completed_at")),
        )
    }

    private fun insertAuditItem(db: SQLiteDatabase, auditId: Long, device: Device, expected: Boolean, foundAt: Long?) {
        val values = ContentValues().apply {
            put("audit_id", auditId); if (device.id == 0L) putNull("device_id") else put("device_id", device.id)
            put("organization", device.organization); put("organization_key", normalizedKey(device.organization))
            put("building", device.building); put("room", device.room); put("name", device.name)
            put("inventory_number", device.inventoryNumber); put("inventory_key", normalizedKey(device.inventoryNumber))
            put("status", device.status.name); put("expected", if (expected) 1 else 0)
            if (foundAt == null) putNull("found_at") else put("found_at", foundAt)
        }
        db.insertOrThrow("room_audit_items", null, values)
    }

    private fun Cursor.auditItem(): AuditItem {
        fun text(column: String) = getString(getColumnIndexOrThrow(column))
        val deviceIndex = getColumnIndexOrThrow("device_id")
        val foundIndex = getColumnIndexOrThrow("found_at")
        return AuditItem(
            id = getLong(getColumnIndexOrThrow("_id")),
            deviceId = if (isNull(deviceIndex)) null else getLong(deviceIndex),
            organization = text("organization"), building = text("building"), room = text("room"),
            name = text("name"), inventoryNumber = text("inventory_number"), status = DeviceStatus.valueOf(text("status")),
            expected = getInt(getColumnIndexOrThrow("expected")) == 1,
            foundAt = if (isNull(foundIndex)) null else getLong(foundIndex),
        )
    }

    private fun findByIdentity(organization: String, inventory: String): Device? = all().firstOrNull {
        normalizedKey(it.organization) == normalizedKey(organization) && normalizedKey(it.inventoryNumber) == normalizedKey(inventory)
    }

    private fun recordChanges(db: SQLiteDatabase, old: Device, new: Device, now: Long) {
        if (old.organization != new.organization || old.building != new.building || old.room != new.room) {
            recordHistory(db, new, "MOVED", "location", fullLocation(old), fullLocation(new), now)
        }
        if (old.status != new.status) recordHistory(db, new, "STATUS", "status", old.status.name, new.status.name, now)
        val changes = listOf(
            "name" to (old.name to new.name), "inventory" to (old.inventoryNumber to new.inventoryNumber),
            "serial" to (old.serialNumber to new.serialNumber), "category" to (old.category to new.category),
            "responsible" to (old.responsible to new.responsible), "reason" to (old.writeOffReason to new.writeOffReason),
            "notes" to (old.notes to new.notes),
        )
        changes.filter { it.second.first != it.second.second }.forEach { (field, values) ->
            recordHistory(db, new, "EDITED", field, values.first, values.second, now)
        }
    }

    private fun recordHistory(
        db: SQLiteDatabase, device: Device, event: String, field: String, old: String, new: String, now: Long,
    ) {
        db.insertOrThrow("device_history", null, ContentValues().apply {
            put("device_id", device.id); put("inventory_number", device.inventoryNumber); put("device_name", device.name)
            put("event_type", event); put("field_name", field); put("old_value", old); put("new_value", new)
            put("occurred_at", now)
        })
    }

    private fun fullLocation(device: Device) = listOf(device.organization, device.building, device.room)
        .filter(String::isNotBlank).joinToString(" · ")

    private fun Device.values() = ContentValues().apply {
        put("name", name); put("inventory_number", inventoryNumber); put("inventory_key", normalizedKey(inventoryNumber))
        put("serial_number", serialNumber); put("category", category)
        put("organization", organization); put("organization_key", normalizedKey(organization))
        put("building", building); put("room", room); put("responsible", responsible); put("notes", notes)
        put("status", status.name); put("write_off_reason", writeOffReason)
        put("created_at", createdAt); put("updated_at", updatedAt); if (deletedAt == null) putNull("deleted_at") else put("deleted_at", deletedAt)
    }

    private fun Cursor.device(): Device {
        fun text(column: String) = getString(getColumnIndexOrThrow(column))
        fun number(column: String) = getLong(getColumnIndexOrThrow(column))
        val deletedIndex = getColumnIndexOrThrow("deleted_at")
        return Device(
            id = number("_id"), name = text("name"), inventoryNumber = text("inventory_number"),
            serialNumber = text("serial_number"), category = text("category"), organization = text("organization"),
            building = text("building"), room = text("room"), responsible = text("responsible"), notes = text("notes"),
            status = DeviceStatus.valueOf(text("status")), writeOffReason = text("write_off_reason"),
            createdAt = number("created_at"), updatedAt = number("updated_at"),
            deletedAt = if (isNull(deletedIndex)) null else number("deleted_at"),
        )
    }

    private fun Cursor.history() = DeviceHistory(
        id = getLong(getColumnIndexOrThrow("_id")), deviceId = getLong(getColumnIndexOrThrow("device_id")),
        inventoryNumber = getString(getColumnIndexOrThrow("inventory_number")),
        deviceName = getString(getColumnIndexOrThrow("device_name")),
        eventType = getString(getColumnIndexOrThrow("event_type")), fieldName = getString(getColumnIndexOrThrow("field_name")),
        oldValue = getString(getColumnIndexOrThrow("old_value")), newValue = getString(getColumnIndexOrThrow("new_value")),
        occurredAt = getLong(getColumnIndexOrThrow("occurred_at")),
    )

    companion object { private const val DATABASE_VERSION = 2 }
}
