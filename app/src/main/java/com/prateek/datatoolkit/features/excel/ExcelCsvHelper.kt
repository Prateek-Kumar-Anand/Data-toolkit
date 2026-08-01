package com.prateek.datatoolkit.features.excel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.reader.ReadableWorkbook
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Excel/CSV: reads and writes both formats into a common `List<List<String>>`
 * table shape, so the same rows can flow through DataCleaner, QualityScorer,
 * or be re-exported as the other format.
 */
object ExcelCsvHelper {

    fun readXlsx(file: File, sheetIndex: Int = 0): List<List<String>> {
        // Everything below is wrapped because fastexcel-reader's ReadableWorkbook constructor
        // calls javax.xml.stream.XMLInputFactory.newInstance() to get a StAX parser for each
        // sheet's XML. ToolkitApp.onCreate() already forces that lookup to resolve to the
        // aalto-xml engine bundled with this app (see the comment there for the full story),
        // which is what actually fixes the "opening any .xlsx crashes the whole app" bug. This
        // try/catch is the second, independent layer: if a factory still can't be resolved on
        // some device, XMLInputFactory throws a javax.xml.stream.FactoryConfigurationError -
        // a java.lang.Error, not an Exception - which would otherwise slip past every
        // "catch (e: Exception)" in ExcelCsvActivity/DataCleaningActivity/WorkflowEngine and
        // crash the whole process instead of showing a normal, friendly error message.
        try {
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
        } catch (e: Exception) {
            throw e
        } catch (e: Error) {
            throw java.io.IOException(
                "Could not read this spreadsheet (${e.javaClass.simpleName}: ${e.message ?: "unknown XML/parser error"})", e
            )
        }
    }

    fun writeXlsx(rows: List<List<String>>, output: File, sheetName: String = "Sheet1") {
        FileOutputStream(output).use { out ->
            val wb = Workbook(out, "DataToolkit", "1.0")
            val ws = wb.newWorksheet(sheetName)
            for (r in rows.indices) {
                val row = rows[r]
                for (c in row.indices) {
                    ws.value(r, c, row[c])
                }
            }
            // Workbook.finish() already calls close()/finish() on every worksheet it owns.
            // Calling ws.finish() here too writes the "xl/worksheets/sheetN.xml" zip entry
            // twice, which throws a duplicate-entry error and corrupts the .xlsx on export.
            wb.finish()
        }
    }

    /**
     * Like [writeXlsx], but for tables that have a column of raw image URLs (e.g. the scraper's
     * "Image" column - see ItemExtractor.IMAGE_COLUMN_INDEX): each URL is downloaded (up to 6 at
     * once, so a page with dozens of items doesn't wait on dozens of sequential network round
     * trips) and embedded as an actual thumbnail picture in that cell, instead of the URL just
     * sitting there as text. The URL itself is still written as the cell's value too (so CSV
     * round-trips and DataCleaner/QualityScorer keep working unchanged) - the picture is simply
     * drawn on top. A broken/unreachable image link never fails the whole export: it silently
     * falls back to plain URL text for that one row.
     */
    suspend fun writeXlsxWithImages(rows: List<List<String>>, output: File, sheetName: String = "Sheet1", imageColumn: Int) {
        val imagesByRow = withContext(Dispatchers.IO) {
            val semaphore = Semaphore(6)
            rows.indices.drop(1) // row 0 is the header - never has an image to fetch
                .mapNotNull { r -> rows[r].getOrNull(imageColumn)?.takeIf { it.isNotBlank() }?.let { r to it } }
                .map { (r, url) -> async { semaphore.withPermit { downloadImageBytes(url) }?.let { r to it } } }
                .awaitAll()
                .filterNotNull()
                .toMap()
        }

        FileOutputStream(output).use { out ->
            val wb = Workbook(out, "DataToolkit", "1.0")
            val ws = wb.newWorksheet(sheetName)
            ws.width(imageColumn, 16.0)
            for (r in rows.indices) {
                val row = rows[r]
                if (r > 0) ws.rowHeight(r, 60.0) // room for a thumbnail below the header row
                for (c in row.indices) {
                    ws.value(r, c, row[c])
                }
                imagesByRow[r]?.let { bytes ->
                    try {
                        ws.addImage(r, imageColumn, bytes, 90, 70)
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
