package ru.misis.devicemanager.data

import org.json.JSONArray
import org.json.JSONObject
import ru.misis.devicemanager.qr.DeviceQrCodec
import ru.misis.devicemanager.qr.QrException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

class DeviceTransferException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class TransferReply(val accepted: Boolean, val added: Int = 0, val duplicates: Int = 0)

/** Length-framed, versioned snapshots for a single secure Bluetooth RFCOMM session. */
object DeviceTransferCodec {
    const val MAX_RECORDS = 250
    private const val TYPE = "dm.devices"
    private const val VERSION = 1
    private const val MAX_PACKET_BYTES = 8 * 1024 * 1024

    fun write(output: OutputStream, devices: List<Device>) {
        val packet = encode(devices)
        DataOutputStream(output).apply {
            writeInt(packet.size)
            write(packet)
            flush()
        }
    }

    fun read(input: InputStream): List<Device> {
        val stream = DataInputStream(input)
        val size = try { stream.readInt() }
        catch (error: Exception) { throw DeviceTransferException("Не удалось прочитать начало передачи", error) }
        if (size !in 1..MAX_PACKET_BYTES) throw DeviceTransferException("Размер передачи выходит за допустимые пределы")
        val bytes = ByteArray(size)
        try { stream.readFully(bytes) }
        catch (error: Exception) { throw DeviceTransferException("Передача прервалась до получения всех данных", error) }
        return decode(bytes)
    }

    fun writeReply(output: OutputStream, reply: TransferReply) {
        DataOutputStream(output).apply {
            writeUTF(JSONObject().put("accepted", reply.accepted).put("added", reply.added)
                .put("duplicates", reply.duplicates).toString())
            flush()
        }
    }

    fun readReply(input: InputStream): TransferReply = try {
        val json = JSONObject(DataInputStream(input).readUTF())
        val added = json.getInt("added")
        val duplicates = json.getInt("duplicates")
        if (added < 0 || duplicates < 0 || added + duplicates > MAX_RECORDS) throw DeviceTransferException("Некорректный ответ получателя")
        TransferReply(json.getBoolean("accepted"), added, duplicates)
    } catch (error: DeviceTransferException) { throw error }
    catch (error: Exception) { throw DeviceTransferException("Не удалось получить ответ принимающего устройства", error) }

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
