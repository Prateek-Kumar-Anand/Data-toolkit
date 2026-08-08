package com.prateek.datatoolkit.features.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * OCR (Scanned Documents -> Text): on-device recognizer via ML Kit, no
 * network call needed and no API key required. Works for photos taken with
 * the camera or images picked from the gallery.
 */
object OcrHelper {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Returns the recognized text plus a rough per-line confidence proxy (line count / block
     *  count), and [OcrResult.lines] - the same recognition broken into lines/words with their
     *  on-image positions, for callers (like [com.prateek.datatoolkit.features.invoice.ReceiptTableDetector])
     *  that need to reason about layout rather than just flattened text. A line/word with no
     *  bounding box (ML Kit occasionally omits one) is left out of [OcrResult.lines] since
     *  there's no position to reason about - it's still present in [OcrResult.text] as always. */
    suspend fun recognize(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { cont ->
        val processed = try {
            preprocessForOcr(bitmap)
        } catch (_: Exception) {
            bitmap // preprocessing is a best-effort accuracy boost, never a hard requirement
        }
        val image = InputImage.fromBitmap(processed, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val blockCount = visionText.textBlocks.size
                val lines = visionText.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line ->
                        line.boundingBox?.let { box ->
                            val words = line.elements.mapNotNull { element ->
                                element.boundingBox?.let { wordBox ->
                                    OcrWord(element.text, wordBox.left, wordBox.top, wordBox.right, wordBox.bottom)
                                }
                            }
                            OcrLine(line.text, words, box.left, box.top, box.right, box.bottom)
                        }
                    }
                }
                cont.resume(OcrResult(text = visionText.text, blockCount = blockCount, lines = lines))
            }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
            .addOnCompleteListener {
                // Only recycle the temporary preprocessed copy - the caller (OcrActivity /
                // InvoiceOcrActivity) still owns and displays the original [bitmap] in its
                // preview, unchanged.
                if (processed !== bitmap) processed.recycle()
            }
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

    // --- Preprocessing: a cheap accuracy boost before every recognize() call -------------------

    /** ML Kit's recognizer works best on a photo whose longest side sits in a "normal document
     *  photo" range - too small (a blurry low-res capture) starves it of stroke detail, too
     *  large mostly just costs time/memory without a further accuracy gain. */
    private const val MAX_DIMENSION_PX = 2200
    private const val MIN_DIMENSION_PX = 900

    /** How much to boost contrast around mid-gray - receipts are disproportionately printed on
     *  thermal paper, which fades fast, so a moderate boost separates faded print from the
     *  background without blowing out already-good scans. */
    private const val CONTRAST_FACTOR = 1.35f

    /**
     * Resizes into a good OCR range, then desaturates + boosts contrast: color noise/shadows
     * on a photographed receipt confuse text-edge detection more than they help, and a flat
     * grayscale, higher-contrast image gives the recognizer a cleaner signal - the same
     * preprocessing recommended for classic OCR engines, applied here before ML Kit's own
     * (unadjusted) internal grayscale conversion. Returns a brand-new [Bitmap]; the original
     * passed in is never mutated, so the caller's own preview/display of it is unaffected.
     */
    private fun preprocessForOcr(source: Bitmap): Bitmap {
        val resized = resizeForOcr(source)
        val enhanced = applyGrayscaleAndContrast(resized)
        if (resized !== source && resized !== enhanced) resized.recycle()
        return enhanced
    }

    private fun resizeForOcr(bitmap: Bitmap): Bitmap {
        val longSide = maxOf(bitmap.width, bitmap.height)
        val scale = when {
            longSide > MAX_DIMENSION_PX -> MAX_DIMENSION_PX.toFloat() / longSide
            longSide < MIN_DIMENSION_PX -> MIN_DIMENSION_PX.toFloat() / longSide
            else -> 1f
        }
        if (scale == 1f) return bitmap
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun applyGrayscaleAndContrast(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val grayscale = ColorMatrix().apply { setSaturation(0f) }
        val translate = (-0.5f * CONTRAST_FACTOR + 0.5f) * 255f
        val contrast = ColorMatrix(
            floatArrayOf(
                CONTRAST_FACTOR, 0f, 0f, 0f, translate,
                0f, CONTRAST_FACTOR, 0f, 0f, translate,
                0f, 0f, CONTRAST_FACTOR, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        grayscale.postConcat(contrast)
        paint.colorFilter = ColorMatrixColorFilter(grayscale)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }
}

data class OcrResult(val text: String, val blockCount: Int, val lines: List<OcrLine> = emptyList())

/** One recognized word/token and its bounding box, in the coordinate space of the (possibly
 *  preprocessed/resized) bitmap that was actually recognized. */
data class OcrWord(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** One recognized line - ML Kit's own line grouping - plus its constituent words, in reading
 *  order. This is the unit [com.prateek.datatoolkit.features.invoice.ReceiptTableDetector]
 *  reasons about: a receipt's item table is a run of these, and each word's horizontal
 *  position is what lets column boundaries be inferred instead of assumed. */
data class OcrLine(val text: String, val words: List<OcrWord>, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}
