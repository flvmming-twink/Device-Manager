package ru.misis.devicemanager.data

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a small, standards-compliant XLSX workbook without external dependencies. */
object DeviceXlsx {
    const val MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    fun write(devices: List<Device>, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            entry(zip, "[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>
            """.trimIndent())
            entry(zip, "_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>
            """.trimIndent())
            entry(zip, "xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="Оборудование" sheetId="1" r:id="rId1"/></sheets>
                </workbook>
            """.trimIndent())
            entry(zip, "xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                </Relationships>
            """.trimIndent())
            entry(zip, "xl/worksheets/sheet1.xml", worksheet(devices))
        }
    }

    private fun worksheet(devices: List<Device>): String {
        val headers = listOf("Название", "Инвентарный номер", "Серийный номер", "Тип", "Организация",
            "Корпус", "Кабинет / аудитория", "Ответственный", "Статус", "Причина списания", "Примечание")
        val rows = sequenceOf(headers) + devices.asSequence().map { device ->
            listOf(device.name, device.inventoryNumber, device.serialNumber, device.category,
                device.organization, device.building, device.room, device.responsible,
                when (device.status) {
                    DeviceStatus.ACTIVE -> "Действующее"
                    DeviceStatus.NEEDS_REPAIR -> "На ремонт"
                    DeviceStatus.BROKEN -> "Сломан"
                    DeviceStatus.WRITE_OFF -> "На списание"
                }, device.writeOffReason, device.notes)
        }
        val body = rows.mapIndexed { rowIndex, values ->
            val rowNumber = rowIndex + 1
            val cells = values.mapIndexed { columnIndex, value ->
                "<c r=\"${columnName(columnIndex + 1)}$rowNumber\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>"
            }.joinToString("")
            "<row r=\"$rowNumber\">$cells</row>"
        }.joinToString("")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$body</sheetData></worksheet>"""
    }

    private fun columnName(number: Int): String {
        var n = number
        val result = StringBuilder()
        while (n > 0) {
            n--
            result.insert(0, ('A'.code + n % 26).toChar())
            n /= 26
        }
        return result.toString()
    }

    private fun xml(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (char == '\t' || char == '\n' || char == '\r' || char >= ' ') append(char)
            }
        }
    }

    private fun entry(zip: ZipOutputStream, name: String, contents: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(contents.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
