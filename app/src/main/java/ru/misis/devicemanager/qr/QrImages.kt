package ru.misis.devicemanager.qr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.InputStream

object QrImages {
    fun render(raw: String): Bitmap {
        DeviceQrCodec.checkSize(raw)
        val matrix = QRCodeWriter().encode(raw, BarcodeFormat.QR_CODE, 1024, 1024, mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt() }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }

    fun decode(stream: InputStream): String {
        val bytes = readLimited(stream, 24 * 1024 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw QrException("Не удалось открыть изображение")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw QrException("Не удалось открыть изображение")
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
            try { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text }
            catch (_: NotFoundException) { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text }
        } catch (_: ReaderException) { throw QrException("QR не найден. Выберите чёткое изображение всей метки.") }
        finally { bitmap.recycle() }
    }

    fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer); if (n == -1) break
            if (output.size() + n > limit) throw QrException("Файл слишком большой")
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
}
