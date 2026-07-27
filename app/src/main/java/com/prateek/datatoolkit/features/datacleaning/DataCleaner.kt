package com.prateek.datatoolkit.features.datacleaning

import org.json.JSONArray
import org.json.JSONObject

/** Options the user can toggle in the Data Cleaning screen. */
data class CleaningOptions(
    val trimWhitespace: Boolean = true,
    val collapseInnerSpaces: Boolean = true,
    val removeDuplicateRows: Boolean = true,
    val standardizeCase: CaseMode = CaseMode.NONE,
    val removeEmptyRows: Boolean = true,
    val removeUnwantedChars: Boolean = false,
    val fillMissingWith: String? = null // null = leave blank, otherwise fill value e.g. "N/A"
)

enum class CaseMode { NONE, LOWER, UPPER, TITLE }

/** The formats Data Cleaning can read from and export to. */
enum class DataFormat { CSV, XLSX, JSON, TXT }

data class CleaningReport(
    val rowsIn: Int,
    val rowsOut: Int,
    val duplicatesRemoved: Int,
    val emptyRowsRemoved: Int,
    val cellsFilled: Int,
    val unwantedCharsRemoved: Int = 0
)

/**
 * Data Cleaning & Processing: operates on a simple table (list of rows, each
 * a list of cell strings) so the same pipeline works no matter which format
 * (CSV, Excel, JSON, or plain TXT) the data originally came from.
 */
object DataCleaner {

    // Characters kept when "remove unwanted characters" is on: letters, digits,
    // whitespace, and everyday punctuation. Everything else (control chars, stray
    // symbols, mojibake) is stripped. Matches the printable-character notion
    // QualityScorer already uses, so a "cleaned" cell also scores better.
    private const val ALLOWED_PUNCTUATION = ".,;:!?()'\"-_/@%&$#*+"

    fun clean(rows: List<List<String>>, options: CleaningOptions): Pair<List<List<String>>, CleaningReport> {
        if (rows.isEmpty()) return rows to CleaningReport(0, 0, 0, 0, 0, 0)

        val header = rows.first()
        var body = rows.drop(1)
        val rowsIn = body.size
        var cellsFilled = 0
        var unwantedCharsRemoved = 0

        // 1. Trim / collapse whitespace / strip unwanted characters / case normalization per cell
        body = body.map { row ->
            row.map { cell ->
                var value = cell
                if (options.removeUnwantedChars) {
                    val before = value.length
                    value = value.filter { it.isLetterOrDigit() || it.isWhitespace() || it in ALLOWED_PUNCTUATION }
                    unwantedCharsRemoved += before - value.length
                }
                if (options.trimWhitespace) value = value.trim()
                if (options.collapseInnerSpaces) value = value.replace(Regex("\\s+"), " ")
                value = when (options.standardizeCase) {
                    CaseMode.LOWER -> value.lowercase()
                    CaseMode.UPPER -> value.uppercase()
                    CaseMode.TITLE -> value.split(" ").joinToString(" ") { w ->
                        if (w.isEmpty()) w else w[0].uppercase() + w.drop(1).lowercase()
                    }
                    CaseMode.NONE -> value
                }
                value
            }
        }

        // 2. Fill missing / blank cells
        if (options.fillMissingWith != null) {
            body = body.map { row ->
                row.map { cell ->
                    if (cell.isBlank()) {
                        cellsFilled++
                        options.fillMissingWith
                    } else cell
                }
            }
        }

        // 3. Remove fully empty rows
        var emptyRemoved = 0
        if (options.removeEmptyRows) {
            val before = body.size
            body = body.filter { row -> row.any { it.isNotBlank() } }
            emptyRemoved = before - body.size
        }

        // 4. De-duplicate rows
        var duplicatesRemoved = 0
        if (options.removeDuplicateRows) {
            val before = body.size
            body = body.distinct()
            duplicatesRemoved = before - body.size
        }

        val result = listOf(header) + body
        return result to CleaningReport(
            rowsIn = rowsIn,
            rowsOut = body.size,
            duplicatesRemoved = duplicatesRemoved,
            emptyRowsRemoved = emptyRemoved,
            cellsFilled = cellsFilled,
            unwantedCharsRemoved = unwantedCharsRemoved
        )
    }

    // --- CSV ---------------------------------------------------------------------------------

    /** Parses raw pasted/loaded text as CSV (comma-separated, quote-aware for simple cases). */
    fun parseCsvText(text: String): List<List<String>> =
        text.lines().filter { it.isNotEmpty() }.map { line -> splitCsvLine(line) }

    fun toCsvText(rows: List<List<String>>): String =
        rows.joinToString("\n") { row -> row.joinToString(",") { escapeCsv(it) } }

    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    result.add(current.toString()); current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    private fun escapeCsv(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n"))
            "\"" + value.replace("\"", "\"\"") + "\""
        else value

    // --- JSON (array of flat objects) ---------------------------------------------------------

    /** Parses a JSON array of objects into a table - column headers are the union of all keys seen. */
    fun parseJsonText(text: String): List<List<String>> {
        val arr = JSONArray(text)
        if (arr.length() == 0) return emptyList()

        val objects = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val keys = linkedSetOf<String>()
        objects.forEach { obj -> obj.keys().forEach { keys.add(it) } }
        val header = keys.toList()

        val rows = objects.map { obj ->
            header.map { key -> if (obj.has(key) && !obj.isNull(key)) obj.get(key).toString() else "" }
        }
        return listOf(header) + rows
    }

    /** Serializes a table back into a JSON array of objects, keyed by the header row. */
    fun toJsonText(rows: List<List<String>>): String {
        if (rows.isEmpty()) return "[]"
        val header = rows.first()
        val arr = JSONArray()
        rows.drop(1).forEach { row ->
            val obj = JSONObject()
            header.forEachIndexed { i, key -> obj.put(key, row.getOrElse(i) { "" }) }
            arr.put(obj)
        }
        return arr.toString(2)
    }

    // --- TXT (plain lines, no delimiter assumed) ------------------------------------------------

    /** Treats each non-empty line as one row in a single "Text" column. */
    fun parseTxtText(text: String): List<List<String>> =
        listOf(listOf("Text")) + text.lines().filter { it.isNotEmpty() }.map { listOf(it) }

    /** Generic plain-text export: tab-separated so multi-column tables stay readable. */
    fun toTxtText(rows: List<List<String>>): String =
        rows.joinToString("\n") { row -> row.joinToString("\t") }

    // --- Format-aware helpers ------------------------------------------------------------------

    fun parse(text: String, format: DataFormat): List<List<String>> = when (format) {
        DataFormat.CSV -> parseCsvText(text)
        DataFormat.JSON -> parseJsonText(text)
        DataFormat.TXT -> parseTxtText(text)
        DataFormat.XLSX -> emptyList() // XLSX is binary - read via ExcelCsvHelper.readXlsx instead
    }

    fun serialize(rows: List<List<String>>, format: DataFormat): String = when (format) {
        DataFormat.CSV -> toCsvText(rows)
        DataFormat.JSON -> toJsonText(rows)
        DataFormat.TXT -> toTxtText(rows)
        DataFormat.XLSX -> toCsvText(rows) // XLSX is binary - write via ExcelCsvHelper.writeXlsx instead
    }

    /** Guesses a format from a file name's extension; defaults to CSV. */
    fun formatFromFileName(name: String): DataFormat = when (name.substringAfterLast('.', "").lowercase()) {
        "xlsx", "xls" -> DataFormat.XLSX
        "json" -> DataFormat.JSON
        "txt" -> DataFormat.TXT
        else -> DataFormat.CSV
    }
}
