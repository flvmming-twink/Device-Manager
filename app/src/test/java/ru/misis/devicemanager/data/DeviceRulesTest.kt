package ru.misis.devicemanager.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class DeviceRulesTest {
    private val base = Device(id = 1, name = "Компьютер кафедры", inventoryNumber = "МИС-001",
        organization = "МИСИС", building = "Учебный", room = "305", category = "Компьютер")

    @Test fun requiredFieldsAndWriteOffReasonAreValidated() {
        assertEquals(setOf(DeviceField.NAME, DeviceField.INVENTORY, DeviceField.ORGANIZATION, DeviceField.ROOM),
            Device().invalidFields())
        assertTrue(base.invalidFields().isEmpty())
        assertEquals(setOf(DeviceField.WRITE_OFF_REASON), base.copy(status = DeviceStatus.WRITE_OFF).invalidFields())
        assertTrue(base.copy(status = DeviceStatus.WRITE_OFF, writeOffReason = "Не подлежит ремонту").invalidFields().isEmpty())
    }

    @Test fun activeStateRemovesOldWriteOffReasonAndTrimsFields() {
        val value = base.copy(name = "  Монитор  ", writeOffReason = "Сломан").normalized()
        assertEquals("Монитор", value.name)
        assertEquals("", value.writeOffReason)
    }

    @Test fun inventoryKeysHandleCyrillicCaseAndUnicodeWhitespace() {
        assertEquals(normalizedKey("мисис  НФ"), normalizedKey(" МИСИС\u00a0НФ "))
        assertEquals(normalizedKey("МИС-001"), normalizedKey("мис-001"))
    }

    @Test fun multiTermSearchMatchesAcrossDeviceFieldsAndSerial() {
        val item = base.copy(serialNumber = "SN-555", responsible = "Иванов")
        assertEquals(listOf(item), DeviceFilter(search = "мисис 305 sn-555").apply(listOf(item)))
        assertEquals(listOf(item), DeviceFilter(search = "ИВАНОВ").apply(listOf(item)))
        assertTrue(DeviceFilter(search = "306").apply(listOf(item)).isEmpty())
    }

    @Test fun identicalRoomsInDifferentBuildingsAndOrganizationsStayDistinct() {
        val otherBuilding = base.copy(id = 2, building = "Лабораторный")
        val otherOrganization = base.copy(id = 3, organization = "Предприятие")
        val values = listOf(base, otherBuilding, otherOrganization)
        assertEquals(listOf(base), DeviceFilter(organization = "мисис", building = "учебный", room = "305").apply(values))
        assertEquals(3, DeviceFilter(room = "305").apply(values).size)
    }

    @Test fun statusAndTrashAreIndependent() {
        val marked = base.copy(id = 2, status = DeviceStatus.WRITE_OFF, writeOffReason = "Износ")
        val deleted = base.copy(id = 3, deletedAt = 1)
        assertEquals(listOf(marked), DeviceFilter(status = DeviceStatus.WRITE_OFF).apply(listOf(base, marked, deleted)))
        assertEquals(listOf(deleted), DeviceFilter(trash = true).apply(listOf(base, marked, deleted)))
    }

    @Test fun sortIsStableAndUpdatedIsDescending() {
        assertEquals(listOf(2L, 1L), DeviceFilter().apply(listOf(base.copy(updatedAt = 10),
            base.copy(id = 2, updatedAt = 20))).map { it.id })
        assertEquals(listOf(1L, 2L), DeviceFilter(sort = DeviceSort.NAME).apply(listOf(base.copy(id = 2), base)).map { it.id })
    }

    @Test fun csvPreservesCyrillicEscapesQuotesAndBlocksFormulas() {
        val output = ByteArrayOutputStream()
        DeviceCsv.write(listOf(base.copy(name = "Проектор \"А\"; учебный", serialNumber = "=HYPERLINK(1)",
            responsible = " +SUM(1)", notes = "Строка 1\nСтрока 2")), output)
        val text = output.toString("UTF-8")
        assertTrue(text.startsWith("\uFEFF"))
        assertTrue(text.contains("\"Проектор \"\"А\"\"; учебный\""))
        assertTrue(text.contains("\"'=HYPERLINK(1)\""))
        assertTrue(text.contains("\"' +SUM(1)\""))
        assertTrue(text.contains("\"Строка 1\nСтрока 2\""))
        assertTrue(text.contains("\"Действующее\""))
    }
}
