package com.prateek.datatoolkit.features.datacleaning

import org.json.JSONArray
import org.json.JSONObject

/** Case-normalization modes, usable both globally and as a per-column override. */
enum class CaseMode { NONE, LOWER, UPPER, TITLE }

/** The formats Data Cleaning can read from and export to. */
enum class DataFormat { CSV, XLSX, JSON, TXT }

/** Data types Data Cleaning can auto-detect a column as, and validate its cells against. */
enum class ColumnType { TEXT, NUMBER, EMAIL, PHONE, URL, DATE }

/**
 * Per-column overrides. Any null field falls back to the matching global [CleaningOptions]
 * value, so a column only needs to specify what makes it different from the rest of the table.
 */
data class ColumnRule(
    val skip: Boolean = false, // leave this column completely untouched by every cleaning step
    val standardizeCase: CaseMode? = null,
    val fillMissingWith: String? = null,
    val expectedType: ColumnType? = null // explicit type for validation; null = auto-detect
)

/** A user-defined search/replace rule, scoped to one column or every column. */
data class ReplaceRule(
    val find: String,
    val replaceWith: String,
    val isRegex: Boolean = false,
    val ignoreCase: Boolean = false,
    val column: String? = null // null = every column
)

/** A cell whose value didn't match its column's expected/detected type. */
data class InvalidCell(val rowIndex: Int, val column: String, val value: String, val expectedType: ColumnType)

/** Options the user can toggle in the Data Cleaning screen. */
data class CleaningOptions(
    val trimWhitespace: Boolean = true,
    val collapseInnerSpaces: Boolean = true,
    val removeDuplicateRows: Boolean = true,
    val standardizeCase: CaseMode = CaseMode.NONE,
    val removeEmptyRows: Boolean = true,
    val removeUnwantedChars: Boolean = false,
    val fillMissingWith: String? = null, // null = leave blank, otherwise fill value e.g. "N/A"

    // Smarter duplicate detection: empty = compare the whole row (old behavior); otherwise
    // only these column(s) decide whether two rows are "the same". fuzzyDedupe trims + lowercases
    // whatever is being compared, so "Asha " and "asha" count as the same key.
    val dedupeKeyColumns: List<String> = emptyList(),
    val fuzzyDedupe: Boolean = false,

    // Per-column overrides, keyed by header name.
    val columnRules: Map<String, ColumnRule> = emptyMap(),

    // User-defined find/replace cleanup, applied in order before trim/case/unwanted-chars.
    val replaceRules: List<ReplaceRule> = emptyList(),

    // Type detection & validation: an explicit ColumnRule.expectedType overrides auto-detection.
    // The winning type (explicit or detected) is used to flag cells that don't match it - this
    // never changes the data, it only reports mismatches in the CleaningReport.
    val flagInvalidCells: Boolean = true
)

data class CleaningReport(
    val rowsIn: Int,
    val rowsOut: Int,
    val duplicatesRemoved: Int,
    val emptyRowsRemoved: Int,
    val cellsFilled: Int,
    val unwantedCharsRemoved: Int = 0,
    val replacementsMade: Int = 0,
    val detectedTypes: Map<String, ColumnType> = emptyMap(),
    val invalidCells: List<InvalidCell> = emptyList()
)

/** Lightweight, dependency-free detection/validation for common column data types. */
object TypeDetector {
    private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val URL_REGEX = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
    private val DATE_REGEX = Regex("^\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}$")
    private val NUMBER_REGEX = Regex("^-?\\d+([.,]\\d+)?$")
    private val PHONE_REGEX = Regex("^\\+?[\\d\\s().-]{7,15}$")

    // Order matters: more specific patterns (email/url/date) are checked before the very
    // permissive phone/number patterns, so e.g. a date never gets misread as a "number".
    private val CHECKS = listOf(
        ColumnType.EMAIL to EMAIL_REGEX,
        ColumnType.URL to URL_REGEX,
        ColumnType.DATE to DATE_REGEX,
        ColumnType.NUMBER to NUMBER_REGEX,
        ColumnType.PHONE to PHONE_REGEX
    )

    /** Samples up to 50 non-blank values and picks the type at least 80% of them match. */
    fun detect(values: List<String>): ColumnType {
        val sample = values.map { it.trim() }.filter { it.isNotEmpty() }.take(50)
        if (sample.isEmpty()) return ColumnType.TEXT
        val best = CHECKS
            .map { (type, regex) -> type to sample.count { regex.matches(it) }.toDouble() / sample.size }
            .filter { it.second >= 0.8 }
            .maxByOrNull { it.second }
        return best?.first ?: ColumnType.TEXT
    }

    fun matches(type: ColumnType, value: String): Boolean = when (type) {
        ColumnType.TEXT -> true
        ColumnType.EMAIL -> EMAIL_REGEX.matches(value.trim())
        ColumnType.URL -> URL_REGEX.matches(value.trim())
        ColumnType.DATE -> DATE_REGEX.matches(value.trim())
        ColumnType.NUMBER -> NUMBER_REGEX.matches(value.trim())
        ColumnType.PHONE -> PHONE_REGEX.matches(value.trim())
    }
}

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
        if (rows.isEmpty()) return rows to CleaningReport(0, 0, 0, 0, 0)

        val header = rows.first()
        var body = rows.drop(1)
        val rowsIn = body.size
        var cellsFilled = 0
        var unwantedCharsRemoved = 0
        var replacementsMade = 0

        fun ruleFor(colIndex: Int): ColumnRule? = header.getOrNull(colIndex)?.let { options.columnRules[it] }

        // 1. User-defined find/replace, then trim / collapse / strip-unwanted / case - all per cell,
        //    each column falling back to the global option unless its ColumnRule overrides it.
        //    A column marked "skip" bypasses every step in this pass untouched.
        body = body.map { row ->
            row.mapIndexed { c, cell ->
                val rule = ruleFor(c)
                if (rule?.skip == true) return@mapIndexed cell

                var value = cell
                val colName = header.getOrNull(c)
                for (rr in options.replaceRules) {
                    if (rr.column != null && rr.column != colName) continue
                    val before = value
                    value = if (rr.isRegex) {
                        val opts = if (rr.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                        runCatching { value.replace(Regex(rr.find, opts), rr.replaceWith) }.getOrDefault(value)
                    } else {
                        value.replace(rr.find, rr.replaceWith, ignoreCase = rr.ignoreCase)
                    }
                    if (value != before) replacementsMade++
                }

                if (options.removeUnwantedChars) {
                    val before = value.length
                    value = value.filter { it.isLetterOrDigit() || it.isWhitespace() || it in ALLOWED_PUNCTUATION }
                    unwantedCharsRemoved += before - value.length
                }
                if (options.trimWhitespace) value = value.trim()
                if (options.collapseInnerSpaces) value = value.replace(Regex("\\s+"), " ")
                value = applyCase(value, rule?.standardizeCase ?: options.standardizeCase)
                value
            }
        }

        // 2. Fill missing / blank cells (column override wins over the global fill value)
        body = body.map { row ->
            row.mapIndexed { c, cell ->
                if (cell.isBlank()) {
                    val rule = ruleFor(c)
                    if (rule?.skip == true) return@mapIndexed cell
                    val fill = rule?.fillMissingWith ?: options.fillMissingWith
                    if (fill != null) { cellsFilled++; fill } else cell
                } else cell
            }
        }

        // 3. Remove fully empty rows
        var emptyRemoved = 0
        if (options.removeEmptyRows) {
            val before = body.size
            body = body.filter { row -> row.any { it.isNotBlank() } }
            emptyRemoved = before - body.size
        }

        // 4. De-duplicate rows - by a chosen key column set (or the whole row if none given),
        //    optionally fuzzy (trim + lowercase before comparing), keeping the first occurrence.
        var duplicatesRemoved = 0
        if (options.removeDuplicateRows) {
            val before = body.size
            val keyIndices = options.dedupeKeyColumns.mapNotNull { name -> header.indexOf(name).takeIf { it >= 0 } }
            fun keyOf(row: List<String>): List<String> {
                val raw = if (keyIndices.isNotEmpty()) keyIndices.map { row.getOrElse(it) { "" } } else row
                return if (options.fuzzyDedupe) raw.map { it.trim().lowercase() } else raw
            }
            val seen = HashSet<List<String>>()
            body = body.filter { row -> seen.add(keyOf(row)) }
            duplicatesRemoved = before - body.size
        }

        // 5. Type detection & validation - runs on the final cleaned data, per column: an explicit
        //    ColumnRule.expectedType wins, otherwise the type is auto-detected from that column's
        //    own values. This never changes any cell - it only reports mismatches.
        val detectedTypes = mutableMapOf<String, ColumnType>()
        val invalidCells = mutableListOf<InvalidCell>()
        if (options.flagInvalidCells) {
            for (c in header.indices) {
                val rule = ruleFor(c)
                if (rule?.skip == true) continue
                val colName = header[c]
                val values = body.map { it.getOrElse(c) { "" } }
                val type = rule?.expectedType ?: TypeDetector.detect(values)
                detectedTypes[colName] = type
                if (type == ColumnType.TEXT) continue
                body.forEachIndexed { r, row ->
                    val v = row.getOrElse(c) { "" }
                    if (v.isNotBlank() && !TypeDetector.matches(type, v)) {
                        invalidCells += InvalidCell(r, colName, v, type)
                    }
                }
            }
        }

        val result = listOf(header) + body
        return result to CleaningReport(
            rowsIn = rowsIn,
            rowsOut = body.size,
            duplicatesRemoved = duplicatesRemoved,
            emptyRowsRemoved = emptyRemoved,
            cellsFilled = cellsFilled,
            unwantedCharsRemoved = unwantedCharsRemoved,
            replacementsMade = replacementsMade,
            detectedTypes = detectedTypes,
            invalidCells = invalidCells
        )
    }

    private fun applyCase(value: String, mode: CaseMode): String = when (mode) {
        CaseMode.LOWER -> value.lowercase()
        CaseMode.UPPER -> value.uppercase()
        CaseMode.TITLE -> value.split(" ").joinToString(" ") { w ->
            if (w.isEmpty()) w else w[0].uppercase() + w.drop(1).lowercase()
        }
        CaseMode.NONE -> value
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
