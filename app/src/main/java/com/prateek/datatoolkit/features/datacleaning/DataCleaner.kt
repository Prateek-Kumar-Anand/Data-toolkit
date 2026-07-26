package com.prateek.datatoolkit.features.datacleaning

/** Options the user can toggle in the Data Cleaning screen. */
data class CleaningOptions(
    val trimWhitespace: Boolean = true,
    val collapseInnerSpaces: Boolean = true,
    val removeDuplicateRows: Boolean = true,
    val standardizeCase: CaseMode = CaseMode.NONE,
    val removeEmptyRows: Boolean = true,
    val fillMissingWith: String? = null // null = leave blank, otherwise fill value e.g. "N/A"
)

enum class CaseMode { NONE, LOWER, UPPER, TITLE }

data class CleaningReport(
    val rowsIn: Int,
    val rowsOut: Int,
    val duplicatesRemoved: Int,
    val emptyRowsRemoved: Int,
    val cellsFilled: Int
)

/**
 * Data Cleaning & Processing: operates on a simple table (list of rows, each
 * a list of cell strings) so it works for both pasted CSV text and rows read
 * out of an .xlsx sheet by the Excel/CSV feature.
 */
object DataCleaner {

    fun clean(rows: List<List<String>>, options: CleaningOptions): Pair<List<List<String>>, CleaningReport> {
        if (rows.isEmpty()) return rows to CleaningReport(0, 0, 0, 0, 0)

        val header = rows.first()
        var body = rows.drop(1)
        val rowsIn = body.size
        var cellsFilled = 0

        // 1. Trim / collapse whitespace / case normalization per cell
        body = body.map { row ->
            row.map { cell ->
                var value = cell
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
            cellsFilled = cellsFilled
        )
    }

    /** Parses raw pasted text as CSV (comma-separated, quote-aware for simple cases). */
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
}
