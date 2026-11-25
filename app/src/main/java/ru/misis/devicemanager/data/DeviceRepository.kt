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

/** SQLite is the single source of truth; contacts are never imported as equipment. */
class DeviceRepository(context: Context, name: String = "device_manager.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE devices (
              _id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL, inventory_number TEXT NOT NULL, inventory_key TEXT NOT NULL,
              serial_number TEXT NOT NULL, category TEXT NOT NULL,
              organization TEXT NOT NULL, organization_key TEXT NOT NULL,
              building TEXT NOT NULL, room TEXT NOT NULL, responsible TEXT NOT NULL, notes TEXT NOT NULL,
              status TEXT NOT NULL CHECK(status IN ('ACTIVE', 'WRITE_OFF')),
              write_off_reason TEXT NOT NULL, created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL, deleted_at INTEGER,
              UNIQUE(organization_key, inventory_key)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX devices_status_location ON devices(deleted_at, status, organization_key, building, room)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future versions must migrate explicitly; never silently drop inventory.
        error("Unsupported inventory database migration: $oldVersion -> $newVersion")
    }

    @Synchronized fun all(): List<Device> = readableDatabase.query(
        "devices", null, null, null, null, null, "updated_at DESC, _id ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.device()) } }

    @Synchronized fun find(id: Long): Device? = readableDatabase.query(
        "devices", null, "_id=?", arrayOf(id.toString()), null, null, null,
    ).use { if (it.moveToFirst()) it.device() else null }

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
            db.setTransactionSuccessful()
            return SaveResult.Saved(saved.copy(id = id))
        } catch (_: SQLiteConstraintException) {
            return SaveResult.DuplicateInventory
        } finally { db.endTransaction() }
    }

    @Synchronized fun moveToTrash(id: Long): Boolean = changeDeletedAt(id, System.currentTimeMillis(), false)
    @Synchronized fun restore(id: Long): Boolean = changeDeletedAt(id, null, true)

    private fun changeDeletedAt(id: Long, timestamp: Long?, wasDeleted: Boolean): Boolean {
        val values = ContentValues().apply {
            if (timestamp == null) putNull("deleted_at") else put("deleted_at", timestamp)
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.update("devices", values,
            "_id=? AND deleted_at IS ${if (wasDeleted) "NOT " else ""}NULL",
            arrayOf(id.toString())) == 1
    }

    @Synchronized fun deletePermanently(id: Long): Boolean = writableDatabase.delete(
        "devices", "_id=? AND deleted_at IS NOT NULL", arrayOf(id.toString()),
    ) == 1

    private fun Device.values() = ContentValues().apply {
        put("name", name); put("inventory_number", inventoryNumber); put("inventory_key", normalizedKey(inventoryNumber))
        put("serial_number", serialNumber); put("category", category)
        put("organization", organization); put("organization_key", normalizedKey(organization))
        put("building", building); put("room", room); put("responsible", responsible); put("notes", notes)
        put("status", status.name); put("write_off_reason", writeOffReason)
        put("created_at", createdAt); put("updated_at", updatedAt); putNull("deleted_at")
    }

    private fun Cursor.device(): Device {
        fun text(column: String) = getString(getColumnIndexOrThrow(column))
        fun number(column: String) = getLong(getColumnIndexOrThrow(column))
        return Device(
            id = number("_id"), name = text("name"), inventoryNumber = text("inventory_number"),
            serialNumber = text("serial_number"), category = text("category"), organization = text("organization"),
            building = text("building"), room = text("room"), responsible = text("responsible"), notes = text("notes"),
            status = DeviceStatus.valueOf(text("status")), writeOffReason = text("write_off_reason"),
            createdAt = number("created_at"), updatedAt = number("updated_at"),
            deletedAt = if (isNull(getColumnIndexOrThrow("deleted_at"))) null else number("deleted_at"),
        )
    }
}
