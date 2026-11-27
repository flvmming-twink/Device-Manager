package ru.misis.devicemanager.data

import org.json.JSONArray
import org.json.JSONObject
import ru.misis.devicemanager.qr.DeviceQrCodec
import ru.misis.devicemanager.qr.QrException
import java.security.MessageDigest

class DeviceTransferException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Versioned, integrity-checked portable snapshots for Android's system share flow. */
object DeviceTransferCodec {
    const val MAX_RECORDS = 250
    const val MAX_PACKET_BYTES = 8 * 1024 * 1024
    private const val TYPE = "dm.devices"
    private const val VERSION = 1

    fun encode(devices: List<Device>): ByteArray {
        if (devices.isEmpty() || devices.size > MAX_RECORDS) throw DeviceTransferException("Можно передать от 1 до $MAX_RECORDS устройств")
        val normalized = devices.map { it.normalized() }
        if (normalized.any { it.invalidFields().isNotEmpty() }) throw DeviceTransferException("В списке есть карточка с незаполненными обязательными полями")
        val records = JSONArray().apply { normalized.forEach { put(DeviceQrCodec.deviceJson(it)) } }
        val canonical = records.toString().toByteArray(Charsets.UTF_8)
        val packet = JSONObject().put("type", TYPE).put("v", VERSION).put("count", normalized.size)
            .put("sha256", sha256(canonical)).put("records", records).toString().toByteArray(Charsets.UTF_8)
        if (packet.size > MAX_PACKET_BYTES) throw DeviceTransferException("Передача слишком велика. Разделите её на несколько отправок.")
        return packet
    }

    fun decode(packet: ByteArray): List<Device> {
        if (packet.isEmpty() || packet.size > MAX_PACKET_BYTES) throw DeviceTransferException("Размер передачи выходит за допустимые пределы")
        try {
            val envelope = JSONObject(String(packet, Charsets.UTF_8))
            if (envelope.getString("type") != TYPE || envelope.getInt("v") != VERSION) {
                throw DeviceTransferException("Формат передачи не поддерживается")
            }
            val records = envelope.getJSONArray("records")
            val count = records.length()
            if (count !in 1..MAX_RECORDS || envelope.getInt("count") != count) {
                throw DeviceTransferException("Некорректное количество карточек")
            }
            val devices = buildList {
                for (index in 0 until count) add(DeviceQrCodec.readDevice(records.getJSONObject(index)))
            }
            val canonical = JSONArray().apply { devices.forEach { put(DeviceQrCodec.deviceJson(it)) } }
                .toString().toByteArray(Charsets.UTF_8)
            val expected = envelope.getString("sha256").toByteArray(Charsets.US_ASCII)
            val actual = sha256(canonical).toByteArray(Charsets.US_ASCII)
            if (!MessageDigest.isEqual(expected, actual)) throw DeviceTransferException("Контрольная сумма передачи не совпадает")
            return devices
        } catch (error: DeviceTransferException) { throw error }
        catch (error: QrException) { throw DeviceTransferException(error.message ?: "Карточка повреждена", error) }
        catch (error: Exception) { throw DeviceTransferException("Данные передачи повреждены или не поддерживаются", error) }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
