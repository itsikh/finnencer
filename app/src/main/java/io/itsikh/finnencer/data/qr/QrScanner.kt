package io.itsikh.finnencer.data.qr

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor

/**
 * CameraX [ImageAnalysis.Analyzer] that hands each frame to ML Kit's barcode
 * scanner (QR format only) and emits the first decoded raw value via
 * [onResult]. Once a value is produced, further frames are ignored until the
 * consumer calls [reset].
 */
class QrAnalyzer(
    @Suppress("unused") private val executor: Executor,
    private val onResult: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    @Volatile private var consumed = false

    fun reset() { consumed = false }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        if (consumed) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val raw = barcodes.firstOrNull()?.rawValue
                if (!raw.isNullOrEmpty() && !consumed) {
                    consumed = true
                    onResult(raw)
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    fun close() = scanner.close()
}
