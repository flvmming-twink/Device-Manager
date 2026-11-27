package ru.misis.devicemanager.qr

import android.util.Base64
import org.json.JSONObject
import ru.misis.devicemanager.data.Device
import ru.misis.devicemanager.data.DeviceStatus
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

class QrException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Versioned portable snapshots. No database IDs or deletion commands cross the QR boundary. */
object DeviceQrCodec {
    const val MAX_JSON_BYTES = 65_536
    const val MAX_QR_BYTES = 2_100
    private const val OPEN = "dm.device"
    private const val SEALED = "dm.device.enc"
    private const val ALGORITHM = "A256GCM+RSA-OAEP-256"
    private val random = SecureRandom()
    private val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

    fun generateKey(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
    fun keyId(key: PublicKey): String = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        .take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
    internal fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    internal fun un64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    fun deviceJson(device: Device) = JSONObject().apply {
        put("name", device.name); put("inventory", device.inventoryNumber); put("serial", device.serialNumber)
        put("category", device.category); put("organization", device.organization)
        put("building", device.building); put("room", device.room); put("responsible", device.responsible)
        put("status", device.status.name); put("reason", device.writeOffReason); put("notes", device.notes)
    }

    fun readDevice(json: JSONObject): Device {
        fun field(name: String, required: Boolean = false): String {
            val value = json.opt(name)
            if (value == null && !required) return ""
            val limit = if (name == "notes" || name == "reason") 2000 else 200
            if (value !is String || value.length > limit) throw QrException("Некорректное или слишком длинное поле: $name")
            return value
        }
        val status = try { DeviceStatus.valueOf(field("status", true)) }
        catch (_: IllegalArgumentException) { throw QrException("Неизвестный статус устройства") }
        val device = Device(name = field("name", true), inventoryNumber = field("inventory", true),
            serialNumber = field("serial"), category = field("category"), organization = field("organization", true),
            building = field("building"), room = field("room", true), responsible = field("responsible"),
            status = status, writeOffReason = field("reason"), notes = field("notes")).normalized()
        if (device.invalidFields().isNotEmpty()) throw QrException("В QR не заполнены обязательные поля устройства")
        return device
    }

    fun encodeOpen(device: Device): String {
        if (device.invalidFields().isNotEmpty()) throw QrException("Сначала заполните обязательные поля устройства")
        readDevice(deviceJson(device))
        return JSONObject().put("type", OPEN).put("v", 1).put("device", deviceJson(device.normalized())).toString()
            .also { checkSize(it, MAX_JSON_BYTES) }
    }

    fun encodeSecure(device: Device, publicKey: PublicKey): String {
        val plain = encodeOpen(device).toByteArray(Charsets.UTF_8)
        val key = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey()
        val iv = ByteArray(12).also(random::nextBytes)
        val kid = keyId(publicKey)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(aad(kid))
        }
        val wrapped = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(Cipher.ENCRYPT_MODE, publicKey, oaep)
        }.doFinal(key.encoded)
        return JSONObject().put("type", SEALED).put("v", 1).put("alg", ALGORITHM).put("kid", kid)
            .put("ek", b64(wrapped)).put("iv", b64(iv)).put("ct", b64(cipher.doFinal(plain))).toString()
            .also { checkSize(it, MAX_JSON_BYTES) }
    }

    fun decode(raw: String, privateKey: (String) -> PrivateKey? = { null }): Device {
        try {
            checkSize(raw, MAX_JSON_BYTES)
            val obj = JSONObject(raw)
            if (obj.getInt("v") != 1) throw QrException("Эта версия QR не поддерживается")
            return when (obj.getString("type")) {
                OPEN -> readDevice(obj.getJSONObject("device"))
                SEALED -> {
                    if (obj.getString("alg") != ALGORITHM) throw QrException("Неизвестный алгоритм QR")
                    val kid = obj.getString("kid")
                    if (!kid.matches(Regex("[a-f0-9]{32}"))) throw QrException("Некорректный идентификатор ключа")
                    val key = privateKey(kid) ?: throw QrException("Нет ключа $kid. Импортируйте ключ организации в разделе «Ключи».")
                    val iv = un64(obj.getString("iv"))
                    val wrapped = un64(obj.getString("ek"))
                    if (iv.size != 12 || wrapped.size != 256) throw QrException("Некорректный защищённый QR")
                    val aes = Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.DECRYPT_MODE, key, oaep) }.doFinal(wrapped)
                    if (aes.size != 32) throw QrException("Некорректный ключ QR")
                    val plaintext = try {
                        Cipher.getInstance("AES/GCM/NoPadding").apply {
                            init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv)); updateAAD(aad(kid))
                        }.doFinal(un64(obj.getString("ct")))
                    } finally { aes.fill(0) }
                    val content = JSONObject(String(plaintext, Charsets.UTF_8))
                    if (content.getString("type") != OPEN || content.getInt("v") != 1) throw QrException("Некорректное содержимое QR")
                    readDevice(content.getJSONObject("device"))
                }
                else -> throw QrException("Это не QR устройства Device Manager")
            }
        } catch (error: QrException) { throw error }
        catch (_: Exception) { throw QrException("QR повреждён, изменён или зашифрован другим ключом") }
    }

    fun isSecure(raw: String): Boolean = runCatching { JSONObject(raw).optString("type") == SEALED }.getOrDefault(false)
    fun checkSize(raw: String, max: Int = MAX_QR_BYTES) {
        if (raw.toByteArray(Charsets.UTF_8).size > max) throw QrException(
            if (max == MAX_QR_BYTES) "Карточка слишком велика для одного QR. Сократите заметки или сохраните JSON-файл. Данные не обрезаны."
            else "Файл слишком большой")
    }
    private fun aad(kid: String) = "$SEALED|1|$ALGORITHM|$kid".toByteArray(Charsets.UTF_8)
}
