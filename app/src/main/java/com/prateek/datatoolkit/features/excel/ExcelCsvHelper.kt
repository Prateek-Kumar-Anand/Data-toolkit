package com.prateek.datatoolkit.features.excel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.dhatim.fastexcel.Color
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet
import org.dhatim.fastexcel.reader.ReadableWorkbook
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Excel/CSV: reads and writes both formats into a common `List<List<String>>`
 * table shape, so the same rows can flow through DataCleaner, QualityScorer,
 * or be re-exported as the other format.
 */
object ExcelCsvHelper {

    fun readXlsx(file: File, sheetIndex: Int = 0): List<List<String>> {
        // fastexcel-reader's ReadableWorkbook needs javax.xml.stream.XMLInputFactory to resolve
        // to the aalto-xml engine ToolkitApp.onCreate() forces it to (see the comment there). In
        // practice that resolution has kept failing on real devices - ART throws
        // NoClassDefFoundError partway through the aalto-xml/stax2-api class hierarchy even with
        // both pinned as explicit dependencies - so this no longer trusts that path alone. It's
        // tried first (it's the fuller-featured reader), but ANY failure - Exception or Error -
        // falls back to readXlsxViaPlatformXml(), which reads the xlsx zip directly with
        // android.util.Xml and never touches javax.xml.stream/aalto-xml/stax2-api at all.
        return try {
            readXlsxViaFastexcel(file, sheetIndex)
        } catch (primary: Throwable) {
            try {
                readXlsxViaPlatformXml(file, sheetIndex)
            } catch (fallback: Throwable) {
                // Both readers failed - report both, not just the primary. (An earlier version
                // of this only surfaced the primary reader's error here, which meant a bug in
                // the fallback itself was invisible - the message looked identical whether the
                // fallback ran and also failed, or never ran at all.)
                val primaryDetail = listOfNotNull(
                    primary.message,
                    primary.cause?.let { "caused by ${it.javaClass.simpleName}: ${it.message}" }
                ).joinToString(" - ").ifBlank { "unknown error" }
                val fallbackDetail = listOfNotNull(
                    fallback.message,
                    fallback.cause?.let { "caused by ${it.javaClass.simpleName}: ${it.message}" }
                ).joinToString(" - ").ifBlank { "unknown error" }
                throw java.io.IOException(
                    "Could not read this spreadsheet. Primary reader: ${primary.javaClass.simpleName}: " +
                        "$primaryDetail | Fallback reader: ${fallback.javaClass.simpleName}: $fallbackDetail",
                    primary
                )
            }
        }
    }

    private fun readXlsxViaFastexcel(file: File, sheetIndex: Int): List<List<String>> {
        FileInputStream(file).use { input ->
            ReadableWorkbook(input).use { wb ->
                // NOTE: wb.sheets is a java.util.stream.Stream<Sheet>, not a Kotlin collection.
                // Calling .toList() on it needs the kotlin.streams.toList() extension, and even
                // with that import, newer JDKs can resolve it to Java 16's native Stream.toList()
                // instead - a method that doesn't exist on Android's runtime (minSdk 24) and
                // throws NoSuchMethodError the moment a file is read. forEach() is unambiguous
                // and safe on every Android version this app targets.
                val sheetsList = mutableListOf<org.dhatim.fastexcel.reader.Sheet>()
                wb.sheets.forEach { sheetsList.add(it) }
                val sheet = sheetsList.getOrNull(sheetIndex) ?: return emptyList()
                val rows = mutableListOf<List<String>>()
                sheet.openStream().use { rowStream ->
                    rowStream.forEach { row ->
                        val cells = (0 until row.cellCount).map { i ->
                            row.getCell(i)?.text ?: ""
                        }
                        rows.add(cells)
                    }
                }
                return rows
            }
        }
    }

    /** Dependency-free fallback for [readXlsx]. Walks the xlsx zip directly with
     *  android.util.Xml's pull parser: resolves the target sheet via workbook.xml + its rels
     *  (falling back to the sheetN.xml naming convention if that resolution comes up empty),
     *  resolves shared-string cells against sharedStrings.xml, and reads inline/numeric/boolean
     *  cells straight off the sheet XML. Covers the flat data tables this app actually produces
     *  and consumes; it isn't a full OOXML implementation (no rich number formatting - dates and
     *  currency come through as their raw stored value - and no live formula evaluation). */
    private fun readXlsxViaPlatformXml(file: File, sheetIndex: Int): List<List<String>> {
        ZipFile(file).use { zip ->
            val sharedStrings = readSharedStrings(zip)
            val entryName = resolveSheetEntry(zip, sheetIndex) ?: "xl/worksheets/sheet${sheetIndex + 1}.xml"
            val entry = zip.getEntry(entryName) ?: return emptyList()

            val rows = mutableListOf<List<String>>()
            zip.getInputStream(entry).use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, "UTF-8")
                var rowCells: MutableMap<Int, String>? = null
                var maxCol = -1
                var cellCol = -1
                var cellType: String? = null
                var text: StringBuilder? = null
                var capturing = false

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "row" -> { rowCells = mutableMapOf(); maxCol = -1 }
                            "c" -> {
                                cellCol = columnFromCellRef(parser.getAttributeValue(null, "r"))
                                cellType = parser.getAttributeValue(null, "t")
                            }
                            "v", "t" -> { capturing = true; text = StringBuilder() }
                        }
                        XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (capturing) text?.append(parser.text)
                        XmlPullParser.END_TAG -> when (parser.name) {
                            "v" -> {
                                capturing = false
                                if (cellCol >= 0) {
                                    val raw = text?.toString() ?: ""
                                    val value = when (cellType) {
                                        "s" -> raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: ""
                                        "b" -> if (raw == "1") "TRUE" else "FALSE"
                                        else -> raw
                                    }
                                    rowCells?.put(cellCol, value)
                                    if (cellCol > maxCol) maxCol = cellCol
                                }
                            }
                            "t" -> {
                                capturing = false
                                // Inline strings (t="inlineStr") carry their value in <is><t>
                                // right on the cell; shared-string <t> runs live inside
                                // sharedStrings.xml and are handled separately, not here.
                                if (cellType == "inlineStr" && cellCol >= 0) {
                                    rowCells?.put(cellCol, text?.toString() ?: "")
                                    if (cellCol > maxCol) maxCol = cellCol
                                }
                            }
                            "row" -> {
                                rowCells?.let { cells ->
                                    rows.add(MutableList(maxCol + 1) { i -> cells[i] ?: "" })
                                }
                                rowCells = null
                            }
                        }
                    }
                    event = parser.next()
                }
            }
            return rows
        }
    }

    private fun readSharedStrings(zip: ZipFile): List<String> {
        return try {
            val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
            val strings = mutableListOf<String>()
            zip.getInputStream(entry).use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, "UTF-8")
                var inItem = false
                var text: StringBuilder? = null
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> if (parser.name == "si") { inItem = true; text = StringBuilder() }
                        // Every <t> run inside one <si>...</si> (plain or split across rich-text
                        // <r> runs) gets appended in document order, so multi-run strings come out
                        // concatenated the same way Excel displays them. CDSECT is included since a
                        // pull parser reports CDATA-wrapped text separately from plain TEXT.
                        XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inItem) text?.append(parser.text)
                        XmlPullParser.END_TAG -> if (parser.name == "si") {
                            strings.add(text?.toString() ?: "")
                            inItem = false
                        }
                    }
                    event = parser.next()
                }
            }
            strings
        } catch (e: Exception) {
            // A malformed/unexpected sharedStrings.xml shouldn't take down the whole read - cells
            // that reference it just come back blank instead of the app failing to open the file.
            emptyList()
        }
    }

    private fun resolveSheetEntry(zip: ZipFile, sheetIndex: Int): String? = try {
        resolveSheetEntryOrThrow(zip, sheetIndex)
    } catch (e: Exception) {
        null
    }

    private fun resolveSheetEntryOrThrow(zip: ZipFile, sheetIndex: Int): String? {
        val wbEntry = zip.getEntry("xl/workbook.xml") ?: return null
        val relsEntry = zip.getEntry("xl/_rels/workbook.xml.rels") ?: return null

        val relIds = mutableListOf<String>()
        zip.getInputStream(wbEntry).use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                    // r:id - match by local name so this doesn't depend on the parser's
                    // namespace-prefix handling.
                    val rid = (0 until parser.attributeCount)
                        .firstOrNull { parser.getAttributeName(it).substringAfterLast(':') == "id" }
                        ?.let { parser.getAttributeValue(it) }
                    if (rid != null) relIds.add(rid)
                }
                event = parser.next()
            }
        }
        val targetId = relIds.getOrNull(sheetIndex) ?: return null

        var target: String? = null
        zip.getInputStream(relsEntry).use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "Relationship" &&
                    parser.getAttributeValue(null, "Id") == targetId
                ) {
                    target = parser.getAttributeValue(null, "Target")
                }
                event = parser.next()
            }
        }
        val t = target ?: return null
        return if (t.startsWith("/")) t.removePrefix("/") else "xl/${t.removePrefix("./")}"
    }

    /** "C7" -> 2 (0-based column index; ignores the row digits). */
    private fun columnFromCellRef(ref: String?): Int {
        if (ref.isNullOrEmpty()) return -1
        var col = 0
        for (ch in ref) {
            if (ch < 'A' || ch > 'Z') break
            col = col * 26 + (ch - 'A' + 1)
        }
        return col - 1
    }

    /** Writes a plain table - every column auto-widened to fit its content (capped so one
     *  huge cell doesn't blow out the whole sheet), and any column that regularly holds long
     *  text (a URL, a description) gets wrapped inside its cell instead of visually spilling
     *  into whatever's next to it. */
    fun writeXlsx(rows: List<List<String>>, output: File, sheetName: String = "Sheet1") {
        FileOutputStream(output).use { out ->
            val wb = Workbook(out, "DataToolkit", "1.0")
            val ws = wb.newWorksheet(sheetName)
            val wrappedColumns = autoFormatColumns(ws, rows)
            styleHeaderRow(ws, rows.firstOrNull()?.size ?: 0)
            for (r in rows.indices) {
                val row = rows[r]
                for (c in row.indices) {
                    ws.value(r, c, row[c])
                }
                if (r > 0 && row.indices.any { c -> wrappedColumns[c] == true }) {
                    ws.rowHeight(r, estimatedRowHeight(row, wrappedColumns))
                }
            }
            // Workbook.finish() already calls close()/finish() on every worksheet it owns.
            // Calling ws.finish() here too writes the "xl/worksheets/sheetN.xml" zip entry
            // twice, which throws a duplicate-entry error and corrupts the .xlsx on export.
            wb.finish()
        }
    }

    /** Bold, lightly-shaded header row with the top row frozen, so scrolling a long export
     *  never loses sight of what each column is. Shared by every xlsx writer below. */
    private fun styleHeaderRow(ws: Worksheet, columnCount: Int) {
        if (columnCount <= 0) return
        ws.range(0, 0, 0, columnCount - 1).style().bold().fillColor(Color.GRAY2).set()
        ws.freezePane(0, 1)
    }

    // --- Auto column width / wrap-text / row height, shared by every xlsx writer below --------

    private const val MIN_COL_WIDTH = 10.0
    private const val MAX_COL_WIDTH = 55.0
    private const val WRAP_COL_WIDTH = 45.0
    private const val WRAP_THRESHOLD_CHARS = 45

    /**
     * Widens each column to fit its longest value (padded a little, capped at [MAX_COL_WIDTH]
     * character-widths). A column whose content regularly runs past [WRAP_THRESHOLD_CHARS] -
     * a Link/URL column, a free-text description - is fixed at [WRAP_COL_WIDTH] and switched to
     * wrapped text instead: Excel's default behaviour is to let a long value visually spill
     * into the next cell whenever that cell happens to be blank, which is what produced the
     * "links overflow outside the cell" look. Wrapping keeps the text inside its own cell's
     * boundary and grows downward (via [estimatedRowHeight]) instead of sideways.
     *
     * Returns which columns ended up in wrap mode, so callers can size row heights accordingly.
     */
    private fun autoFormatColumns(ws: Worksheet, rows: List<List<String>>): Map<Int, Boolean> {
        if (rows.isEmpty()) return emptyMap()
        val columnCount = rows.maxOf { it.size }
        val wrapped = mutableMapOf<Int, Boolean>()
        for (c in 0 until columnCount) {
            val maxLen = rows.maxOf { row -> row.getOrNull(c)?.length ?: 0 }
            if (maxLen > WRAP_THRESHOLD_CHARS) {
                ws.width(c, WRAP_COL_WIDTH)
                if (rows.size > 1) {
                    ws.range(1, c, rows.size - 1, c).style().wrapText(true).set()
                }
                wrapped[c] = true
            } else {
                ws.width(c, (maxLen + 2).coerceIn(MIN_COL_WIDTH.toInt(), MAX_COL_WIDTH.toInt()).toDouble())
                wrapped[c] = false
            }
        }
        return wrapped
    }

    /** Rough estimate of how many wrapped lines a cell's text will need at [WRAP_COL_WIDTH]
     *  characters/line, so the row can be made tall enough that Excel doesn't clip it. */
    private fun estimatedWrapLines(text: String): Int {
        if (text.isBlank()) return 1
        val charsPerLine = WRAP_COL_WIDTH * 1.7
        return ceil(text.length / charsPerLine).toInt().coerceAtLeast(1)
    }

    private fun estimatedRowHeight(row: List<String>, wrappedColumns: Map<Int, Boolean>, minHeight: Double = 15.0): Double {
        val maxLines = row.indices.maxOfOrNull { c ->
            if (wrappedColumns[c] == true) estimatedWrapLines(row.getOrNull(c).orEmpty()) else 1
        } ?: 1
        return (maxLines * 15.0 + 4.0).coerceIn(minHeight, Worksheet.MAX_ROW_HEIGHT)
    }

    /**
     * Like [writeXlsx], but for tables that have a column of raw image URLs (e.g. the scraper's
     * "Image" column - see ItemExtractor.IMAGE_COLUMN_INDEX): each URL is downloaded (up to 6 at
     * once, so a page with dozens of items doesn't wait on dozens of sequential network round
     * trips), decoded, and re-encoded as a properly-proportioned thumbnail - see [buildThumbnail]
     * for why that step matters - then embedded as an actual picture in that cell instead of the
     * URL just sitting there as text. The URL itself is still written as the cell's value too
     * (so CSV round-trips and DataCleaner/QualityScorer keep working unchanged) - the picture is
     * simply drawn on top. A broken/unreachable/undecodable image link never fails the whole
     * export: it silently falls back to plain URL text for that one row.
     */
    suspend fun writeXlsxWithImages(rows: List<List<String>>, output: File, sheetName: String = "Sheet1", imageColumn: Int) {
        val thumbnailsByRow = withContext(Dispatchers.IO) {
            val semaphore = Semaphore(6)
            rows.indices.drop(1) // row 0 is the header - never has an image to fetch
                .mapNotNull { r -> rows[r].getOrNull(imageColumn)?.takeIf { it.isNotBlank() }?.let { r to it } }
                .map { (r, url) ->
                    async {
                        semaphore.withPermit { downloadImageBytes(url) }
                            ?.let { buildThumbnail(it) }
                            ?.let { r to it }
                    }
                }
                .awaitAll()
                .filterNotNull()
                .toMap()
        }

        FileOutputStream(output).use { out ->
            val wb = Workbook(out, "DataToolkit", "1.0")
            val ws = wb.newWorksheet(sheetName)
            val wrappedColumns = autoFormatColumns(ws, rows)
            styleHeaderRow(ws, rows.firstOrNull()?.size ?: 0)
            // Override the auto-computed width for the image column specifically - it needs to
            // fit the widest actual thumbnail, not the raw URL text sitting behind it.
            val widestThumbnailPx = thumbnailsByRow.values.maxOfOrNull { it.widthPx } ?: THUMBNAIL_MAX_PX
            ws.width(imageColumn, pxToColumnWidth(widestThumbnailPx))
            for (r in rows.indices) {
                val row = rows[r]
                for (c in row.indices) {
                    ws.value(r, c, row[c])
                }
                if (r > 0) {
                    val textHeight = if (row.indices.any { c -> wrappedColumns[c] == true }) {
                        estimatedRowHeight(row, wrappedColumns)
                    } else 0.0
                    val thumbnail = thumbnailsByRow[r]
                    val imageHeightPt = thumbnail?.let { it.heightPx * PX_TO_PT + IMAGE_ROW_PADDING_PT } ?: 0.0
                    val height = maxOf(textHeight, imageHeightPt, 15.0)
                    if (height > 15.0) ws.rowHeight(r, height.coerceAtMost(Worksheet.MAX_ROW_HEIGHT))
                }
                thumbnailsByRow[r]?.let { thumbnail ->
                    try {
                        ws.addImage(r, imageColumn, thumbnail.bytes, thumbnail.widthPx, thumbnail.heightPx)
                    } catch (_: Exception) {
                        // Unsupported/corrupt image bytes - the URL text written above stays as-is.
                    }
                }
            }
            wb.finish()
        }
    }

    private fun downloadImageBytes(url: String): ByteArray? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (DataToolkit Android App)")
        }
        if (connection.responseCode in 200..299) connection.inputStream.use { it.readBytes() } else null
    } catch (_: Exception) {
        null
    }

    // --- Image thumbnail sizing/re-encoding ----------------------------------------------------

    private const val THUMBNAIL_MAX_PX = 120
    private const val JPEG_QUALITY = 85

    /** 1px = 1/96in, 1pt = 1/72in at the 96dpi Excel/OOXML assumes for pixel-based sizing. */
    private const val PX_TO_PT = 0.75
    private const val IMAGE_ROW_PADDING_PT = 8.0

    private data class Thumbnail(val bytes: ByteArray, val widthPx: Int, val heightPx: Int)

    /**
     * Decodes the downloaded [bytes] and produces a properly-proportioned thumbnail: scaled so
     * its longest side is at most [THUMBNAIL_MAX_PX], preserving the source's actual aspect
     * ratio - never upscaled past the original size.
     *
     * The old fixed-90x70 embed ignored each image's real dimensions and stretched every photo
     * into that exact box regardless of whether it was actually square, portrait, or landscape;
     * squashing a real photo into the wrong aspect ratio is what made thumbnails look "small and
     * blurry" - the distortion, not just the size. This keeps proportions intact and re-encodes
     * at just the resolution actually needed for that display size (downsampling during decode,
     * not after, so a multi-megapixel source photo doesn't fully load into memory only to be
     * thrown away), which also keeps a page full of full-resolution product photos from bloating
     * the exported file with pixels nobody can see at cell size anyway.
     *
     * Returns null if the bytes can't be decoded as an image at all.
     */
    private fun buildThumbnail(bytes: ByteArray): Thumbnail? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val originalWidth = bounds.outWidth
            val originalHeight = bounds.outHeight
            if (originalWidth <= 0 || originalHeight <= 0) {
                null
            } else {
                val scale = minOf(1.0, THUMBNAIL_MAX_PX.toDouble() / maxOf(originalWidth, originalHeight))
                val targetWidth = (originalWidth * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (originalHeight * scale).roundToInt().coerceAtLeast(1)

                var sampleSize = 1
                while (originalWidth / (sampleSize * 2) >= targetWidth && originalHeight / (sampleSize * 2) >= targetHeight) {
                    sampleSize *= 2
                }
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                    ?: return null
                val resized = if (decoded.width == targetWidth && decoded.height == targetHeight) {
                    decoded
                } else {
                    Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true).also {
                        if (it !== decoded) decoded.recycle()
                    }
                }

                val out = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                resized.recycle()
                Thumbnail(out.toByteArray(), targetWidth, targetHeight)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Rough px -> Excel character-width conversion (~7px per character at the default font). */
    private fun pxToColumnWidth(px: Int): Double = (px / 7.0 + 2.0).coerceIn(MIN_COL_WIDTH, MAX_COL_WIDTH)

    fun readCsv(file: File): List<List<String>> =
        com.prateek.datatoolkit.features.datacleaning.DataCleaner.parseCsvText(file.readText())

    fun writeCsv(rows: List<List<String>>, output: File) {
        output.writeText(com.prateek.datatoolkit.features.datacleaning.DataCleaner.toCsvText(rows))
    }

    /** Converts an xlsx file straight to a CSV file (reads sheet 0, writes CSV). */
    fun xlsxToCsv(input: File, output: File) = writeCsv(readXlsx(input), output)

    /** Converts a CSV file straight to an xlsx file. */
    fun csvToXlsx(input: File, output: File) = writeXlsx(readCsv(input), output)
}
