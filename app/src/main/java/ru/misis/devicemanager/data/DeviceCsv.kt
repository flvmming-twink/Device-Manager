package ru.misis.devicemanager.data

import java.io.OutputStream
import java.io.OutputStreamWriter

object DeviceCsv {
    fun write(devices: List<Device>, stream: OutputStream) {
        OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
            writer.write("\uFEFF")
            writer.write(row(listOf("Название", "Инвентарный номер", "Серийный номер", "Тип", "Организация",
                "Корпус", "Кабинет / аудитория", "Ответственный", "Статус", "Причина списания", "Примечание")))
            devices.forEach { device ->
                writer.write(row(listOf(device.name, device.inventoryNumber, device.serialNumber,
                    device.category, device.organization, device.building, device.room, device.responsible,
                    when (device.status) {
                        DeviceStatus.ACTIVE -> "Действующее"
                        DeviceStatus.NEEDS_REPAIR -> "На ремонт"
                        DeviceStatus.BROKEN -> "Сломан"
                        DeviceStatus.WRITE_OFF -> "На списание"
                    },
                    device.writeOffReason, device.notes)))
            }
        }
    }

    private fun row(values: List<String>): String = values.joinToString(";") { value ->
        // Quoting alone does not prevent spreadsheet formula execution.
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') ||
            value.startsWith('\t') || value.startsWith('\r')) "'$value" else value
        "\"${safe.replace("\"", "\"\"")}\""
    } + "\r\n"
}
