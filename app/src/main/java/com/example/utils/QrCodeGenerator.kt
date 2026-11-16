package com.example.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Generates QR codes identifying a patient, so a printed chart or protocol can be scanned
 * back to that patient's record (see [QrPatientLink] for the encode/decode format shared
 * with the scanner screen).
 */
object QrCodeGenerator {

    fun generate(content: String, sizePx: Int = 240): Bitmap? {
        return try {
            val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}

/** Shared encode/decode format for the "scan a patient's QR to open their chart" feature. */
object QrPatientLink {
    private const val SCHEME = "orthotips"
    private const val HOST = "patient"

    fun encode(patientId: String): String = "$SCHEME://$HOST/${android.net.Uri.encode(patientId)}"

    /** Returns the patient id if [raw] is a recognized OrthoTips patient QR, else null. */
    fun decode(raw: String): String? {
        return try {
            val uri = android.net.Uri.parse(raw)
            if (uri.scheme == SCHEME && uri.host == HOST) {
                uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
