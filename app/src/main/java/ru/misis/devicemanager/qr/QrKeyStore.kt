package ru.misis.devicemanager.qr

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Private app storage, excluded from Android backup. Portable exports are always password encrypted. */
class QrKeyStore(context: Context) {
    private val dir = File(context.filesDir, "qr-keys").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("qr_keys", Context.MODE_PRIVATE)
    data class Entry(val id: String, val label: String, val pair: KeyPair)

    @Synchronized fun list(): List<Entry> = dir.listFiles().orEmpty().filter { it.extension == "json" }
        .map { parse(JSONObject(AtomicFile(it).openRead().bufferedReader().use { r -> r.readText() })) }
        .sortedBy { it.label }
    fun active(): Entry? = list().let { entries -> entries.find { it.id == prefs.getString("active", null) } ?: entries.firstOrNull() }
    fun select(id: String) { require(list().any { it.id == id }); prefs.edit().putString("active", id).apply() }
    fun find(id: String): Entry? = list().find { it.id == id }
    fun create(label: String): Entry {
        if (label.length > 120) throw QrException("Название ключа не должно превышать 120 символов")
        return save(Entry("", label.trim().ifBlank { "Ключ организации" }, DeviceQrCodec.generateKey()))
    }

    @Synchronized private fun save(entry: Entry): Entry {
        val valid = entry.copy(id = DeviceQrCodec.keyId(entry.pair.public))
        val file = AtomicFile(File(dir, "${valid.id}.json"))
        val stream = file.startWrite()
        try { stream.write(json(valid).toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        prefs.edit().putString("active", valid.id).apply()
        return valid
    }

    fun export(entry: Entry, password: CharArray): String {
        if (password.size < 12) throw QrException("Для файла ключа нужен пароль не короче 12 символов")
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val secret = derive(password, salt)
        val encrypted = try { Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, iv)); updateAAD(AAD)
        }.doFinal(json(entry).toString().toByteArray(Charsets.UTF_8)) } finally { secret.fill(0) }
        return JSONObject().put("type", "dm.key").put("v", 1).put("kdf", "PBKDF2-HMAC-SHA1")
            .put("iterations", ITERATIONS).put("salt", DeviceQrCodec.b64(salt)).put("iv", DeviceQrCodec.b64(iv))
            .put("ct", DeviceQrCodec.b64(encrypted)).toString()
    }

    fun import(raw: String, password: CharArray): Entry {
        try {
            DeviceQrCodec.checkSize(raw, 16_384)
            val obj = JSONObject(raw)
            require(obj.getString("type") == "dm.key" && obj.getInt("v") == 1)
            require(obj.getString("kdf") == "PBKDF2-HMAC-SHA1" && obj.getInt("iterations") == ITERATIONS)
            val salt = DeviceQrCodec.un64(obj.getString("salt")); val iv = DeviceQrCodec.un64(obj.getString("iv"))
            require(salt.size == 16 && iv.size == 12)
            val secret = derive(password, salt)
            val plain = try { Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, iv)); updateAAD(AAD)
            }.doFinal(DeviceQrCodec.un64(obj.getString("ct"))) } finally { secret.fill(0) }
            return save(parse(JSONObject(String(plain, Charsets.UTF_8))))
        } catch (error: Exception) { throw QrException("Не удалось открыть ключ: проверьте пароль и файл", error) }
    }

    private fun parse(obj: JSONObject): Entry {
        val factory = KeyFactory.getInstance("RSA")
        val publicKey = factory.generatePublic(X509EncodedKeySpec(DeviceQrCodec.un64(obj.getString("public")))) as RSAPublicKey
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(DeviceQrCodec.un64(obj.getString("private")))) as RSAPrivateKey
        require(publicKey.modulus.bitLength() == 2048 && publicKey.modulus == privateKey.modulus)
        val label = obj.getString("label"); require(label.length <= 120)
        val entry = Entry(DeviceQrCodec.keyId(publicKey), label, KeyPair(publicKey, privateKey))
        // A successful challenge also verifies the private CRT values, not only the advertised modulus.
        val signature = java.security.Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey); signature.update(AAD); val signed = signature.sign()
        signature.initVerify(publicKey); signature.update(AAD); require(signature.verify(signed))
        return entry
    }

    private fun json(entry: Entry) = JSONObject().put("label", entry.label)
        .put("public", DeviceQrCodec.b64(entry.pair.public.encoded)).put("private", DeviceQrCodec.b64(entry.pair.private.encoded))
    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    companion object {
        private const val ITERATIONS = 600_000
        private val AAD = "dm.key|1|PBKDF2-HMAC-SHA1|600000|A256GCM".toByteArray(Charsets.UTF_8)
    }
}
