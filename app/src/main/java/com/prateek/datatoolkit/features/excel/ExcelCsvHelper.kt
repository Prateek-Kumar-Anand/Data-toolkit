package com.prateek.datatoolkit.features.excel

import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.reader.ReadableWorkbook
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Excel/CSV: reads and writes both formats into a common `List<List<String>>`
 * table shape, so the same rows can flow through DataCleaner, QualityScorer,
 * or be re-exported as the other format.
 */
object ExcelCsvHelper {

    fun readXlsx(file: File, sheetIndex: Int = 0): List<List<String>> {
        FileInputStream(file).use { input ->
            ReadableWorkbook(input).use { wb ->
                val sheet = wb.sheets.toList().getOrNull(sheetIndex) ?: return emptyList()
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
