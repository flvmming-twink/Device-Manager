package ru.misis.devicemanager.qr

import android.graphics.Bitmap
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.misis.devicemanager.data.Device
import ru.misis.devicemanager.data.DeviceStatus
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeviceQrTest {
    private val record = Device(name = "Проектор Epson", inventoryNumber = "МИСИС-001", organization = "МИСИС",
        room = "305", building = "А", serialNumber = "SN001", category = "Проектор", responsible = "Иванов И.И.",
        notes = "Проверить лампу\nПитание исправно", status = DeviceStatus.WRITE_OFF, writeOffReason = "Износ")
    private fun rejects(block: () -> Unit) { assertThrows(QrException::class.java, block) }

    @Test fun publicJsonPreservesAllFieldsButNeverLocalIdentity() {
        val raw = DeviceQrCodec.encodeOpen(record.copy(id = 42, deletedAt = 123, createdAt = 45, updatedAt = 67))
        assertEquals(record, DeviceQrCodec.decode(raw))
        assertFalse(raw.contains("deletedAt")); assertFalse(raw.contains("createdAt"))
    }
    @Test fun encryptedRoundTripUsesOnlyMatchingPrivateKey() {
        val pair = DeviceQrCodec.generateKey()
        val raw = DeviceQrCodec.encodeSecure(record, pair.public)
        assertFalse(raw.contains(record.name)); assertFalse(raw.contains(record.organization))
        assertEquals(record, DeviceQrCodec.decode(raw) { id -> if (id == DeviceQrCodec.keyId(pair.public)) pair.private else null })
    }
    @Test fun freshAesKeyAndNonceMakeRepeatedQrDifferent() {
        val pair = DeviceQrCodec.generateKey()
        val a = JSONObject(DeviceQrCodec.encodeSecure(record, pair.public))
        val b = JSONObject(DeviceQrCodec.encodeSecure(record, pair.public))
        assertNotEquals(a.getString("iv"), b.getString("iv"))
        assertNotEquals(a.getString("ek"), b.getString("ek"))
        assertNotEquals(a.getString("ct"), b.getString("ct"))
    }
    @Test fun missingAndWrongKeysFailClosed() {
        val pair = DeviceQrCodec.generateKey(); val wrong = DeviceQrCodec.generateKey()
        val raw = DeviceQrCodec.encodeSecure(record, pair.public)
        rejects { DeviceQrCodec.decode(raw) }; rejects { DeviceQrCodec.decode(raw) { wrong.private } }
    }
    @Test fun modifiedCiphertextAndAuthenticatedMetadataAreRejected() {
        val pair = DeviceQrCodec.generateKey()
        val original = DeviceQrCodec.encodeSecure(record, pair.public)
        val obj = JSONObject(original)
        val cipher = DeviceQrCodec.un64(obj.getString("ct")); cipher[0] = (cipher[0].toInt() xor 1).toByte()
        obj.put("ct", DeviceQrCodec.b64(cipher))
        rejects { DeviceQrCodec.decode(obj.toString()) { pair.private } }
        val metadata = JSONObject(original).put("kid", "0".repeat(32))
        rejects { DeviceQrCodec.decode(metadata.toString()) { pair.private } }
    }
    @Test fun unknownVersionsAlgorithmsTypesAndInvalidFieldsFail() {
        val raw = DeviceQrCodec.encodeOpen(record)
        rejects { DeviceQrCodec.decode(JSONObject(raw).put("v", 2).toString()) }
        rejects { DeviceQrCodec.decode(JSONObject(raw).put("type", "contact").toString()) }
        rejects { DeviceQrCodec.decode("not JSON") }
        val obj = JSONObject(raw); obj.getJSONObject("device").put("status", "INVALID")
        rejects { DeviceQrCodec.decode(obj.toString()) }
        val secure = JSONObject(DeviceQrCodec.encodeSecure(record, DeviceQrCodec.generateKey().public)).put("alg", "none")
        rejects { DeviceQrCodec.decode(secure.toString()) }
    }
    @Test fun oversizeContentNeverGetsSilentlyTruncated() {
        rejects { DeviceQrCodec.encodeOpen(record.copy(name = "x".repeat(201))) }
        val raw = DeviceQrCodec.encodeOpen(record.copy(notes = "ю".repeat(2000)))
        rejects { DeviceQrCodec.checkSize(raw) }
        assertEquals("ю".repeat(2000), DeviceQrCodec.decode(raw).notes)
    }
    @Test fun visualQrIsActuallyReadableBackAsJson() {
        val raw = DeviceQrCodec.encodeOpen(record)
        val image = QrImages.render(raw)
        val bytes = ByteArrayOutputStream().apply { image.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        assertEquals(record, DeviceQrCodec.decode(QrImages.decode(bytes.inputStream())))
    }
    @Test fun secureQrFitsAndVisualDecoderRecoversIt() {
        val pair = DeviceQrCodec.generateKey()
        val raw = DeviceQrCodec.encodeSecure(record, pair.public)
        val bytes = ByteArrayOutputStream().apply { QrImages.render(raw).compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        assertEquals(record, DeviceQrCodec.decode(QrImages.decode(bytes.inputStream())) { pair.private })
    }
    @Test fun inputFilesAreBoundedBeforeBitmapAllocation() {
        rejects { QrImages.readLimited(ByteArray(20).inputStream(), 19) }
        assertEquals(20, QrImages.readLimited(ByteArray(20).inputStream(), 20).size)
    }
    @Test fun organizationKeyExportImportPreservesQrAccessAndRejectsWrongPassword() {
        val store = QrKeyStore(RuntimeEnvironment.getApplication())
        val entry = store.create("МИСИС QA")
        val raw = DeviceQrCodec.encodeSecure(record, entry.pair.public)
        val bundle = store.export(entry, "Test-only-password-123".toCharArray())
        assertFalse(bundle.contains("private"))
        rejects { store.import(bundle, "wrong password".toCharArray()) }
        val imported = store.import(bundle, "Test-only-password-123".toCharArray())
        assertEquals(entry.id, imported.id)
        assertEquals(record, DeviceQrCodec.decode(raw) { imported.pair.private })
        rejects { store.export(entry, "short".toCharArray()) }
    }
}
