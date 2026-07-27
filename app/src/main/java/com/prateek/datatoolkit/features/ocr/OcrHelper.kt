package com.prateek.datatoolkit.features.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR (Scanned Documents -> Text): on-device recognizer via ML Kit, no
 * network call needed and no API key required. Works for photos taken with
 * the camera or images picked from the gallery.
 */
object OcrHelper {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Returns the recognized text plus a rough per-line confidence proxy (line count / block count). */
    suspend fun recognize(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val blockCount = visionText.textBlocks.size
                cont.resume(OcrResult(text = visionText.text, blockCount = blockCount))
            }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }

    /**
     * Runs [recognize] over several images in sequence (each treated as one "page"),
     * reporting real progress after every page completes - not a simulated/fake
     * percentage. A page that fails to recognize is skipped (empty result) rather
     * than aborting the whole batch, so one bad photo doesn't lose everything else.
     */
    suspend fun recognizeBatch(
        bitmaps: List<Bitmap>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<OcrResult> {
        val results = mutableListOf<OcrResult>()
        val total = bitmaps.size
        for ((index, bitmap) in bitmaps.withIndex()) {
            val result = try {
                recognize(bitmap)
            } catch (e: Exception) {
                OcrResult(text = "", blockCount = 0)
            }
            results.add(result)
            onProgress(index + 1, total)
        }
        return results
    }
}

data class OcrResult(val text: String, val blockCount: Int)
