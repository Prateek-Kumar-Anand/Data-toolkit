package com.prateek.datatoolkit.features.excel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import com.prateek.datatoolkit.core.xml.XmlSafety
import com.prateek.datatoolkit.features.excel.sheet.CellRef
import com.prateek.datatoolkit.features.excel.sheet.SheetCell
import com.prateek.datatoolkit.features.excel.sheet.SheetData
import com.prateek.datatoolkit.features.excel.sheet.SheetsWorkbook
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
        // Defense-in-depth: screen every embedded XML/rels part for a DOCTYPE declaration
        // before either reader below touches this file - see XmlSafety. Deliberately placed
        // outside the try/fallback below so a rejected file is never retried through the
        // other reader; a file that fails this check should stay rejected, full stop.
        XmlSafety.assertZipHasNoDoctype(file)

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

    private const val MAX_IMAGE_REDIRECTS = 5

    /**
     * Fetches [url]'s bytes for xlsx image embedding. Unlike a URL the user typed into the Web
     * Scraper themselves, these [url]s come from `<img>` tags on arbitrary third-party pages the
     * scraper visited - content an attacker-controlled site fully controls - so this must not
     * blindly go wherever that markup points. Only plain http(s) is fetched; redirects are
     * followed one hop at a time (capped at [MAX_IMAGE_REDIRECTS]) instead of automatically, with
     * every hop's resolved address re-validated, so a malicious page can't use a redirect to reach
     * somewhere the initial URL check would have blocked. [isPubliclyRoutable] rejects loopback/
     * link-local (which also covers the 169.254.169.254 cloud metadata address)/private-use/
     * multicast targets - the classic pattern of an embedded image URL pointed at the device's own
     * local network (a router's admin page, another app's localhost debug server, etc.) instead of
     * a real image.
     */
    private fun downloadImageBytes(url: String): ByteArray? = try {
        var currentUrl = url
        var connection: HttpURLConnection? = null
        try {
            var result: ByteArray? = null
            for (hop in 0..MAX_IMAGE_REDIRECTS) {
                val parsed = URL(currentUrl)
                if (!parsed.protocol.equals("http", ignoreCase = true) && !parsed.protocol.equals("https", ignoreCase = true)) break
                if (!isPubliclyRoutable(parsed.host)) break

                connection = (parsed.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Mozilla/5.0 (DataToolkit Android App)")
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) break
                    currentUrl = URL(parsed, location).toString() // also resolves a relative Location
                    continue
                }
                result = if (code in 200..299) connection.inputStream.use { it.readBytes() } else null
                break
            }
            result
        } finally {
            connection?.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /** True only if every address [host] resolves to is a normal public unicast address -
     *  false for loopback/link-local/private-use (RFC1918 + IPv6 unique-local)/multicast/
     *  wildcard addresses, and false if resolution fails outright. */
    private fun isPubliclyRoutable(host: String): Boolean = try {
        val addresses = java.net.InetAddress.getAllByName(host)
        addresses.isNotEmpty() && addresses.all { addr ->
            !addr.isLoopbackAddress &&
                !addr.isLinkLocalAddress &&
                !addr.isSiteLocalAddress &&
                !addr.isMulticastAddress &&
                !addr.isAnyLocalAddress &&
                !isIpv6UniqueLocal(addr)
        }
    } catch (_: Exception) {
        false
    }

    /** [java.net.InetAddress.isSiteLocalAddress] only recognizes the deprecated IPv6 site-local
     *  range (fec0::/10) - the modern private-use range is Unique Local (fc00::/7), which has no
     *  built-in java.net check. */
    private fun isIpv6UniqueLocal(addr: java.net.InetAddress): Boolean {
        if (addr !is java.net.Inet6Address) return false
        val firstByte = (addr.address.getOrNull(0)?.toInt() ?: return false) and 0xFF
        return (firstByte and 0xFE) == 0xFC
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

    // ============================================================================================
    // Full-workbook read/write for the grid editor (SpreadsheetActivity): every sheet, every
    // cell's real type/formula - not flattened to List<List<String>> like everything above this
    // point. Purely additive: nothing below touches readXlsx/writeXlsx/readCsv/writeCsv, which
    // every other module (Data Cleaning, Invoice, Web Scraping, PDF, OCR, Batch) keeps using
    // completely unchanged.
    // ============================================================================================

    /** Reads every sheet of [file] into a [SheetsWorkbook], each cell keeping its real type -
     *  in particular, a formula cell comes back holding its formula text ("=SUM(A1:A3)"), not
     *  just the value Excel last cached for it. Same dual-reader resilience as [readXlsx] and
     *  for the same reason (see the comment there): fastexcel-reader is tried first, but ANY
     *  failure falls back to the dependency-free platform-XML reader rather than propagating. */
    fun readWorkbook(file: File): SheetsWorkbook {
        XmlSafety.assertZipHasNoDoctype(file)
        return try {
            readWorkbookViaFastexcel(file)
        } catch (primary: Throwable) {
            try {
                readWorkbookViaPlatformXml(file)
            } catch (fallback: Throwable) {
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

    private fun readWorkbookViaFastexcel(file: File): SheetsWorkbook {
        FileInputStream(file).use { input ->
            ReadableWorkbook(input).use { wb ->
                // Same NOTE as readXlsxViaFastexcel above: wb.sheets is a java.util.stream.Stream,
                // walked with forEach (not .toList()) for the same minSdk-24 NoSuchMethodError reason.
                val fastSheets = mutableListOf<org.dhatim.fastexcel.reader.Sheet>()
                wb.sheets.forEach { fastSheets.add(it) }
                if (fastSheets.isEmpty()) throw java.io.IOException("Workbook has no sheets")
                val sheetDataList = fastSheets.map { readSheetViaFastexcel(it) }
                return SheetsWorkbook(sheetDataList.toMutableList())
            }
        }
    }

    private fun readSheetViaFastexcel(fastSheet: org.dhatim.fastexcel.reader.Sheet): SheetData {
        val sheetData = SheetData(fastSheet.name?.takeIf { it.isNotBlank() } ?: "Sheet")
        fastSheet.openStream().use { rowStream ->
            rowStream.forEach { row ->
                for (i in 0 until row.cellCount) {
                    val cell = row.getCell(i) ?: continue
                    // Each cell reports its own absolute address rather than this trusting the
                    // 0-until-cellCount loop index to line up with the real column - safe even
                    // if a streaming row implementation ever returns cells sparsely.
                    val ref = CellRef(cell.address.row, cell.address.column)
                    val formula = cell.formula
                    sheetData.cellAt(ref).input = if (!formula.isNullOrBlank()) "=$formula" else (cell.text ?: "")
                    sheetData.ensureRoomFor(ref)
                }
            }
        }
        return sheetData
    }

    /** Dependency-free fallback for [readWorkbook] - same reasoning and same technique as
     *  [readXlsxViaPlatformXml] (walks the zip directly with android.util.Xml, never touches
     *  javax.xml.stream/aalto-xml/stax2-api), extended two ways: every sheet is read instead of
     *  just one, and each cell's `<f>` formula element is captured alongside its `<v>` cached
     *  value, preferring the formula when present. */
    private fun readWorkbookViaPlatformXml(file: File): SheetsWorkbook {
        ZipFile(file).use { zip ->
            val sharedStrings = readSharedStrings(zip)
            val sheets = resolveAllSheets(zip)
            if (sheets.isEmpty()) throw java.io.IOException("Workbook has no sheets")
            val sheetDataList = sheets.map { (name, entryName) -> readSheetViaPlatformXml(zip, entryName, sharedStrings, name) }
            return SheetsWorkbook(sheetDataList.toMutableList())
        }
    }

    /** Every sheet's (name, zip-entry-path) pair, in workbook order - the multi-sheet
     *  generalization of [resolveSheetEntryOrThrow], which only ever resolved one index at a
     *  time. Falls back to the conventional "xl/worksheets/sheetN.xml" naming (same fallback
     *  [readXlsx] already relies on for a single sheet) if the rels part is missing/unreadable. */
    private fun resolveAllSheets(zip: ZipFile): List<Pair<String, String>> {
        val wbEntry = zip.getEntry("xl/workbook.xml") ?: return emptyList()

        data class SheetRef(val name: String, val relId: String?)
        val sheetRefs = mutableListOf<SheetRef>()
        zip.getInputStream(wbEntry).use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                    val name = parser.getAttributeValue(null, "name") ?: "Sheet${sheetRefs.size + 1}"
                    val rid = (0 until parser.attributeCount)
                        .firstOrNull { parser.getAttributeName(it).substringAfterLast(':') == "id" }
                        ?.let { parser.getAttributeValue(it) }
                    sheetRefs.add(SheetRef(name, rid))
                }
                event = parser.next()
            }
        }

        val relsEntry = zip.getEntry("xl/_rels/workbook.xml.rels")
            ?: return sheetRefs.mapIndexed { i, s -> s.name to "xl/worksheets/sheet${i + 1}.xml" }

        val relMap = mutableMapOf<String, String>()
        zip.getInputStream(relsEntry).use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                    val id = parser.getAttributeValue(null, "Id")
                    val target = parser.getAttributeValue(null, "Target")
                    if (id != null && target != null) relMap[id] = target
                }
                event = parser.next()
            }
        }
        return sheetRefs.mapIndexed { i, s ->
            val target = s.relId?.let { relMap[it] } ?: "worksheets/sheet${i + 1}.xml"
            val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/${target.removePrefix("./")}"
            s.name to path
        }
    }

    private fun readSheetViaPlatformXml(zip: ZipFile, entryName: String, sharedStrings: List<String>, sheetName: String): SheetData {
        val sheetData = SheetData(sheetName)
        val entry = zip.getEntry(entryName) ?: return sheetData
        zip.getInputStream(entry).use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, "UTF-8")
            var cellRow = -1
            var cellCol = -1
            var cellType: String? = null
            var formulaText: String? = null
            var valueCapturing = false
            var formulaCapturing = false
            var text: StringBuilder? = null

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "c" -> {
                            val r = parser.getAttributeValue(null, "r")
                            cellCol = columnFromCellRef(r)
                            cellRow = rowFromCellRef(r)
                            cellType = parser.getAttributeValue(null, "t")
                            formulaText = null
                        }
                        "f" -> { formulaCapturing = true; text = StringBuilder() }
                        "v", "t" -> { valueCapturing = true; text = StringBuilder() }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (valueCapturing || formulaCapturing) text?.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "f" -> { formulaCapturing = false; formulaText = text?.toString() }
                        "v" -> {
                            valueCapturing = false
                            if (cellRow >= 0 && cellCol >= 0) {
                                val raw = text?.toString() ?: ""
                                val value = when (cellType) {
                                    "s" -> raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: ""
                                    "b" -> if (raw == "1") "TRUE" else "FALSE"
                                    else -> raw
                                }
                                setPlatformXmlCell(sheetData, cellRow, cellCol, formulaText, value)
                            }
                        }
                        "t" -> {
                            valueCapturing = false
                            // Inline strings (t="inlineStr") carry their value in <is><t> right
                            // on the cell, with no <v> at all - see readXlsxViaPlatformXml's
                            // matching comment.
                            if (cellType == "inlineStr" && cellRow >= 0 && cellCol >= 0) {
                                setPlatformXmlCell(sheetData, cellRow, cellCol, formulaText, text?.toString() ?: "")
                            }
                        }
                        "c" -> {
                            // A formula cell with no <v> at all (freshly created, never
                            // recalculated - rare but real) never reached the <v> branch above,
                            // so it gets one last chance here rather than silently vanishing.
                            // Harmless to repeat for the common case where <v> already set it:
                            // setPlatformXmlCell always prefers the formula when present, so
                            // this just reassigns the same value.
                            if (formulaText != null && cellRow >= 0 && cellCol >= 0) {
                                setPlatformXmlCell(sheetData, cellRow, cellCol, formulaText, "")
                            }
                            cellRow = -1; cellCol = -1; cellType = null; formulaText = null
                        }
                    }
                }
                event = parser.next()
            }
        }
        return sheetData
    }

    private fun setPlatformXmlCell(sheetData: SheetData, row: Int, col: Int, formula: String?, cachedValue: String) {
        val ref = CellRef(row, col)
        sheetData.cellAt(ref).input = if (!formula.isNullOrBlank()) "=$formula" else cachedValue
        sheetData.ensureRoomFor(ref)
    }

    /** "C7" -> 6 (0-based row index; ignores the column letters). Pairs with the existing
     *  [columnFromCellRef] above, which does the same for the column half of a cell reference. */
    private fun rowFromCellRef(ref: String?): Int {
        if (ref.isNullOrEmpty()) return -1
        val digits = ref.dropWhile { it in 'A'..'Z' }
        return (digits.toIntOrNull() ?: return -1) - 1
    }

    private val STRICT_NUMBER_REGEX = Regex("^[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?$")

    /** Writes every sheet of [workbook], preserving live formulas (via Worksheet.formula(), so
     *  the saved file's formulas actually recalculate when reopened in real Excel/Sheets - not
     *  just the value this app last computed for them) and bold formatting. Only each sheet's
     *  *used* range is written (see SheetData.usedRange) - a sheet that was opened with a large
     *  scrollable grid but only a handful of real cells doesn't write out thousands of blank
     *  rows. This produces a fresh .xlsx built from the current cell contents, not a byte-level
     *  edited copy of whatever file was originally opened - anything about the original beyond
     *  cell values/formulas/bold (its theme, column widths, charts, any other formatting) isn't
     *  round-tripped, the same limitation noted on ExcelCsvActivity's save flow. */
    fun writeWorkbook(workbook: SheetsWorkbook, output: File) {
        FileOutputStream(output).use { out ->
            val wb = Workbook(out, "DataToolkit", "1.0")
            for (sheet in workbook.sheets) {
                val ws = wb.newWorksheet(sanitizeSheetName(sheet.name))
                val used = sheet.usedRange() ?: continue
                for (row in used.minRow..used.maxRow) {
                    for (col in used.minCol..used.maxCol) {
                        val cell = sheet.existingCellAt(CellRef(row, col)) ?: continue
                        if (cell.isBlank()) continue
                        writeCellValue(ws, row, col, cell)
                    }
                }
            }
            wb.finish()
        }
    }

    private fun writeCellValue(ws: Worksheet, row: Int, col: Int, cell: SheetCell) {
        val text = cell.input.trim()
        when {
            cell.isFormula -> ws.formula(row, col, cell.input.removePrefix("="))
            STRICT_NUMBER_REGEX.matches(text) -> ws.value(row, col, text.toDouble())
            // Anything else - plain text, TRUE/FALSE (this app doesn't rely on Excel's native
            // boolean cell type anywhere, so it's kept simple and just written as literal text),
            // and an apostrophe-escaped value (see SheetCell.isFormula) - displayText() rather
            // than the raw input so that escaping apostrophe never leaks into the saved file.
            else -> ws.value(row, col, cell.displayText())
        }
        if (cell.bold) ws.style(row, col).bold().set()
    }

    /** Excel sheet names can't exceed 31 characters or contain \ / ? * [ ] : , and can't be
     *  blank - this app doesn't yet let a person type an invalid one (there's no rename-sheet
     *  UI), but cleaning it up here is cheap insurance against ever writing a file real Excel
     *  would refuse to open. */
    private fun sanitizeSheetName(name: String): String =
        name.replace(Regex("[\\\\/?*\\[\\]:]"), "_").trim().ifBlank { "Sheet" }.take(31)

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
