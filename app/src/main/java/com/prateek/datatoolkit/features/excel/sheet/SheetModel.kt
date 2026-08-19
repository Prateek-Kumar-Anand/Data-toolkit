package com.prateek.datatoolkit.features.excel.sheet

/**
 * The editable-grid data model for the Excel module: a richer shape than the
 * `List<List<String>>` the rest of the app (DataCleaner, other exports) uses for flat
 * tables, since a real spreadsheet needs to tell a formula apart from its result, keep
 * multiple sheets, and grow as the user types past its current edge. Nothing outside
 * features/excel depends on these types - every other module keeps using the plain
 * string-table shape exactly as before.
 */

/** Zero-based (row, col) address of one cell - "A1" is CellRef(0, 0). */
data class CellRef(val row: Int, val col: Int) {
    override fun toString(): String = "${columnLabel(col)}${row + 1}"

    companion object {
        /** 0 -> "A", 25 -> "Z", 26 -> "AA", ... - the same base-26 (no zero digit) scheme
         *  Excel itself uses for column letters. */
        fun columnLabel(col: Int): String {
            var n = col
            val sb = StringBuilder()
            while (true) {
                sb.append('A' + (n % 26))
                n = n / 26 - 1
                if (n < 0) break
            }
            return sb.reverse().toString()
        }

        /** "A" -> 0, "AA" -> 26, ... Returns null if [label] isn't a run of A-Z letters (any
         *  case - callers normalize with .uppercase() first if the source might be lowercase). */
        fun columnIndex(label: String): Int? {
            if (label.isEmpty() || !label.all { it in 'A'..'Z' }) return null
            var n = 0
            for (ch in label) n = n * 26 + (ch - 'A' + 1)
            return n - 1
        }

        /** Parses an Excel-style address like "B12" (optionally with $ anchors, e.g. "$B$12" -
         *  accepted but not distinguished from a relative ref; this app has no copy/fill-handle
         *  feature that would need the distinction) into a [CellRef]. Null if [text] isn't a
         *  valid cell address. */
        fun parse(text: String): CellRef? {
            val m = CELL_REF_REGEX.matchEntire(text) ?: return null
            val col = columnIndex(m.groupValues[1].uppercase()) ?: return null
            val row = m.groupValues[2].toIntOrNull() ?: return null
            if (row < 1) return null
            return CellRef(row - 1, col)
        }

        private val CELL_REF_REGEX = Regex("\\$?([A-Za-z]{1,3})\\$?(\\d{1,7})")
    }
}

/** An A1:B10-style range, inclusive on both ends, normalized so callers don't need to worry
 *  about which corner was typed first - "B10:A1" is unusual but valid Excel syntax and means
 *  the same range as "A1:B10". */
data class CellRange(val start: CellRef, val end: CellRef) {
    val minRow get() = minOf(start.row, end.row)
    val maxRow get() = maxOf(start.row, end.row)
    val minCol get() = minOf(start.col, end.col)
    val maxCol get() = maxOf(start.col, end.col)

    fun contains(ref: CellRef): Boolean = ref.row in minRow..maxRow && ref.col in minCol..maxCol

    /** Every address in this range, row-major - the order SUM/AVERAGE/etc. walk it in. */
    fun cells(): List<CellRef> = (minRow..maxRow).flatMap { r -> (minCol..maxCol).map { c -> CellRef(r, c) } }
}

/** One cell's content and (if it's a formula) its last-computed result. Mutable - this backs
 *  a live-edited grid, not a read-only snapshot. */
class SheetCell(
    /** Exactly what the user typed, or what was read from the file: "42", "hello", or
     *  "=SUM(A1:A3)" (formulas always start with '='). This is what the formula bar shows
     *  while editing, and what round-trips back out on export. */
    var input: String = "",
    /** The one piece of formatting carried through both read and write - see
     *  ExcelCsvHelper.readWorkbook/writeWorkbook. Everything else about how a cell looks
     *  (numbers right-aligned, a formula's result vs. its text, etc.) is derived from
     *  [input]/[computed] rather than stored separately. */
    var bold: Boolean = false
) {
    /** Last computed result for a formula cell, kept in sync by [SheetRecalculator] - null for
     *  a non-formula cell (where [input] itself is what's displayed) or a formula that hasn't
     *  been evaluated yet. Never set directly outside the recalculator. */
    var computed: ComputedResult? = null

    /** A leading '=' means "formula" - *unless* [input] starts with an apostrophe, Excel's own
     *  convention for "treat literally, don't interpret this". That escape matters beyond just
     *  letting someone type a literal '=' as their first character: it's also how
     *  [ExcelCsvActivity]'s CSV import protects itself - CSV has no concept of formulas at all,
     *  so a data value that merely happens to start with '=' must never be silently evaluated
     *  as one just because it landed in this richer model (the well-known "CSV injection" class
     *  of issue is exactly a spreadsheet program doing that unintentionally). */
    val isFormula: Boolean get() = input.startsWith("=")

    /** What this cell should actually show in the grid right now (as opposed to what shows in
     *  the formula bar while it's the selected/edited cell, which is always [input] verbatim,
     *  apostrophe included - only the *display* strips it, matching Excel's own behavior). */
    fun displayText(): String = when {
        input.startsWith("'") -> input.removePrefix("'")
        isFormula -> computed?.display ?: ""
        else -> input
    }

    fun isBlank(): Boolean = input.isBlank()
}

data class ComputedResult(val display: String, val isNumeric: Boolean, val isError: Boolean)

/** One sheet: a sparse grid - most spreadsheets are mostly empty, so only cells that have ever
 *  held something are stored - plus the size it should currently present as scrollable, which
 *  is always at least [GROWTH_MARGIN] rows/cols past the furthest cell actually used so there's
 *  always room to type into a fresh cell at the edge. */
class SheetData(var name: String) {
    private val cells = HashMap<CellRef, SheetCell>()

    var rowCount: Int = MIN_ROWS
        private set
    var colCount: Int = MIN_COLS
        private set

    /** Gets (creating if needed) the cell at [ref]. Every read/write of a cell's content goes
     *  through this - callers never construct a [SheetCell] directly, so there's exactly one
     *  place a new cell enters the sparse map. */
    fun cellAt(ref: CellRef): SheetCell = cells.getOrPut(ref) { SheetCell() }

    /** Same lookup, but doesn't create an entry for a cell nothing has ever touched - use this
     *  for read-only scans (formula evaluation, export) where creating millions of empty-cell
     *  entries just by scanning past them would defeat the whole point of a sparse grid. */
    fun existingCellAt(ref: CellRef): SheetCell? = cells[ref]

    fun allCells(): Map<CellRef, SheetCell> = cells

    /** Grows the presented grid if [ref] is at/past the current edge, so the user always has
     *  at least [GROWTH_MARGIN] blank rows/columns beyond anything they've touched. Never
     *  shrinks - clearing a cell's content doesn't shrink the sheet back down, same as Excel. */
    fun ensureRoomFor(ref: CellRef) {
        if (ref.row + GROWTH_MARGIN > rowCount) rowCount = ref.row + GROWTH_MARGIN
        if (ref.col + GROWTH_MARGIN > colCount) colCount = ref.col + GROWTH_MARGIN
    }

    /** Tightest range that actually contains non-blank data - null if the sheet is empty. Used
     *  when exporting, so a workbook opened with a large presented grid but only a handful of
     *  real cells doesn't write out thousands of blank rows. */
    fun usedRange(): CellRange? {
        val used = cells.filterValues { !it.isBlank() }.keys
        if (used.isEmpty()) return null
        return CellRange(
            CellRef(used.minOf { it.row }, used.minOf { it.col }),
            CellRef(used.maxOf { it.row }, used.maxOf { it.col })
        )
    }

    companion object {
        const val MIN_ROWS = 50
        const val MIN_COLS = 20
        const val GROWTH_MARGIN = 10
    }
}

/** A full workbook: one or more sheets plus which one is currently showing. Backs both a
 *  freshly opened .xlsx (which may bring several sheets with it) and a fresh/CSV-backed
 *  session (which always starts with exactly one, matching a CSV's single flat table). */
class SheetsWorkbook(val sheets: MutableList<SheetData> = mutableListOf(SheetData("Sheet1"))) {
    var activeSheetIndex: Int = 0
        set(value) { field = value.coerceIn(0, sheets.lastIndex) }

    val activeSheet: SheetData get() = sheets[activeSheetIndex]

    fun addSheet(name: String = nextDefaultName()): SheetData {
        val sheet = SheetData(name)
        sheets.add(sheet)
        activeSheetIndex = sheets.lastIndex
        return sheet
    }

    private fun nextDefaultName(): String {
        var n = sheets.size + 1
        while (sheets.any { it.name == "Sheet$n" }) n++
        return "Sheet$n"
    }
}
