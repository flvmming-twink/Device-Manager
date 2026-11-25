package ru.misis.devicemanager.data

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeviceRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: DeviceRepository
    private val filename = "inventory-test.db"
    private val base = Device(name = "Ноутбук", inventoryNumber = "НФ-001", organization = "МИСИС",
        building = "Главный", room = "305", category = "Ноутбук", serialNumber = "SN-1", responsible = "Иванов")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(filename)
        repository = DeviceRepository(context, filename)
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(filename) }
    private fun saved(device: Device = base) = (repository.save(device) as SaveResult.Saved).device

    @Test fun emptyDatabaseDoesNotCreateDemoEquipmentOrImportContacts() {
        context.getSharedPreferences("contact_manager_prefs", 0).edit().putString("contacts", "[{}]").commit()
        assertTrue(repository.all().isEmpty())
    }

    @Test fun allFieldsSurviveDatabaseReopen() {
        val expected = saved(base.copy(notes = "Для практикума", status = DeviceStatus.WRITE_OFF, writeOffReason = "Повреждён"))
        assertTrue(expected.id > 0)
        assertTrue(expected.createdAt > 0)
        repository.close()
        repository = DeviceRepository(context, filename)
        assertEquals(expected, repository.find(expected.id))
    }

    @Test fun editingLocationStatusAndInventoryDoesNotDuplicateRecord() {
        val first = saved()
        val changed = saved(first.copy(room = "407", building = "Лабораторный", inventoryNumber = "НФ-002",
            status = DeviceStatus.WRITE_OFF, writeOffReason = "Износ"))
        assertEquals(first.id, changed.id)
        assertEquals(first.createdAt, changed.createdAt)
        assertEquals(1, repository.all().size)
        assertEquals("407", repository.find(first.id)!!.room)
        val active = saved(changed.copy(status = DeviceStatus.ACTIVE))
        assertEquals("", active.writeOffReason)
    }

    @Test fun duplicateKeysAreRejectedWithinAnOrganizationIncludingCyrillicCase() {
        val first = saved()
        assertEquals(SaveResult.DuplicateInventory, repository.save(base.copy(inventoryNumber = " нф-001 ", organization = "мисис")))
        assertTrue(repository.save(base.copy(organization = "Другая организация")) is SaveResult.Saved)
        assertEquals(first, repository.find(first.id))
    }

    @Test fun duplicateEditIsAtomicAndDoesNotDestroyPreviousRecord() {
        saved()
        val second = saved(base.copy(inventoryNumber = "НФ-002", room = "410"))
        assertEquals(SaveResult.DuplicateInventory, repository.save(second.copy(inventoryNumber = "НФ-001", room = "999")))
        assertEquals(second, repository.find(second.id))
    }

    @Test fun trashRestoresFullRecordAndReservedInventoryNumber() {
        val original = saved()
        assertTrue(repository.moveToTrash(original.id))
        assertTrue(DeviceFilter().apply(repository.all()).isEmpty())
        assertEquals(1, DeviceFilter(trash = true).apply(repository.all()).size)
        assertEquals(SaveResult.DuplicateInventory, repository.save(base))
        assertTrue(repository.restore(original.id))
        val restored = repository.find(original.id)!!
        assertNull(restored.deletedAt)
        assertEquals(original.inventoryNumber, restored.inventoryNumber)
        assertEquals(original.room, restored.room)
        assertEquals(original.createdAt, restored.createdAt)
    }

    @Test fun permanentDeletionIsOnlyAllowedFromTrashAndReleasesNumber() {
        val original = saved()
        assertFalse(repository.deletePermanently(original.id))
        assertTrue(repository.moveToTrash(original.id))
        assertTrue(repository.deletePermanently(original.id))
        assertNull(repository.find(original.id))
        assertTrue(repository.save(base) is SaveResult.Saved)
    }

    @Test fun staleEditorsCannotResurrectDeletedRecords() {
        val original = saved()
        repository.moveToTrash(original.id)
        assertEquals(SaveResult.Missing, repository.save(original.copy(name = "Старое окно")))
        repository.deletePermanently(original.id)
        assertEquals(SaveResult.Missing, repository.save(original))
    }

    @Test fun invalidWritesAreRejectedWithoutMutatingStorage() {
        assertTrue(repository.save(base.copy(room = "")) is SaveResult.Invalid)
        assertTrue(repository.save(base.copy(status = DeviceStatus.WRITE_OFF)) is SaveResult.Invalid)
        assertTrue(repository.all().isEmpty())
    }
}
