package ru.misis.devicemanager.data

import java.text.Normalizer
import java.util.Locale

enum class DeviceStatus { ACTIVE, WRITE_OFF }
enum class DeviceSort { UPDATED, NAME, INVENTORY, LOCATION }
enum class DeviceField { NAME, INVENTORY, ORGANIZATION, ROOM, WRITE_OFF_REASON }

data class Device(
    val id: Long = 0,
    val name: String = "",
    val inventoryNumber: String = "",
    val serialNumber: String = "",
    val category: String = "",
    val organization: String = "",
    val building: String = "",
    val room: String = "",
    val responsible: String = "",
    val notes: String = "",
    val status: DeviceStatus = DeviceStatus.ACTIVE,
    val writeOffReason: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
) {
    fun normalized() = copy(
        name = name.trim(), inventoryNumber = inventoryNumber.trim(),
        serialNumber = serialNumber.trim(), category = category.trim(),
        organization = organization.trim(), building = building.trim(), room = room.trim(),
        responsible = responsible.trim(), notes = notes.trim(),
        writeOffReason = if (status == DeviceStatus.WRITE_OFF) writeOffReason.trim() else "",
    )

    fun invalidFields(): Set<DeviceField> = buildSet {
        if (name.isBlank()) add(DeviceField.NAME)
        if (inventoryNumber.isBlank()) add(DeviceField.INVENTORY)
        if (organization.isBlank()) add(DeviceField.ORGANIZATION)
        if (room.isBlank()) add(DeviceField.ROOM)
        if (status == DeviceStatus.WRITE_OFF && writeOffReason.isBlank()) add(DeviceField.WRITE_OFF_REASON)
    }

    val location: String get() = listOf(building, room).filter(String::isNotBlank).joinToString(" / ")
}

/** The same Unicode-aware key is used by uniqueness checks, filters and search. */
fun normalizedKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .trim().replace(Regex("[\\s\\p{Z}]+"), " ").lowercase(Locale.ROOT)

data class DeviceFilter(
    val search: String = "",
    val status: DeviceStatus? = null,
    val organization: String = "",
    val building: String = "",
    val room: String = "",
    val category: String = "",
    val sort: DeviceSort = DeviceSort.UPDATED,
    val trash: Boolean = false,
) {
    fun apply(devices: List<Device>): List<Device> {
        val terms = normalizedKey(search).split(" ").filter(String::isNotEmpty)
        val matches = devices.filter { device ->
            (device.deletedAt != null) == trash &&
                (status == null || device.status == status) &&
                matchesField(organization, device.organization) &&
                matchesField(building, device.building) &&
                matchesField(room, device.room) &&
                matchesField(category, device.category) &&
                terms.all { term -> normalizedKey(listOf(
                    device.name, device.inventoryNumber, device.serialNumber, device.category,
                    device.organization, device.building, device.room, device.responsible,
                ).joinToString(" ")).contains(term) }
        }
        val comparator = when (sort) {
            DeviceSort.UPDATED -> compareByDescending<Device> { it.updatedAt }
            DeviceSort.NAME -> compareBy { normalizedKey(it.name) }
            DeviceSort.INVENTORY -> compareBy { normalizedKey(it.inventoryNumber) }
            DeviceSort.LOCATION -> compareBy<Device> { normalizedKey(it.organization) }
                .thenBy { normalizedKey(it.building) }.thenBy { normalizedKey(it.room) }
        }
        return matches.sortedWith(comparator.thenBy { it.id })
    }

    private fun matchesField(filter: String, value: String) =
        filter.isEmpty() || normalizedKey(filter) == normalizedKey(value)
}
