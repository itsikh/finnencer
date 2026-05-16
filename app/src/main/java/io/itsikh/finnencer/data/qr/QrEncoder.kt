package io.itsikh.finnencer.data.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Encodes a string into a square QR code [Bitmap]. Uses ZXing core directly
 * (no UI dependency), keeps the encoder deterministic for the same input +
 * size.
 */
object QrEncoder {

    /**
     * @param content the payload to encode (raw string, typically JSON from
     *        [KeysBundle.buildPayload])
     * @param sizePx side length of the output bitmap in pixels
     * @param margin quiet-zone in modules (ZXing default is 4)
     * @param ecLevel error-correction level (L/M/Q/H). Default M gives ~15%
     *        recovery — enough for camera-to-camera transfer without bloating
     *        the QR size for short keys.
     */
    fun encode(
        content: String,
        sizePx: Int,
        margin: Int = 1,
        ecLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
    ): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to margin,
            EncodeHintType.ERROR_CORRECTION to ecLevel,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[i++] = if (matrix[x, y]) Color.WHITE else Color.TRANSPARENT
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
