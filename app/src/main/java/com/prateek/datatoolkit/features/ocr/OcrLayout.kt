package com.prateek.datatoolkit.features.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Rebuilds a page's reading layout from OCR word positions so tables keep their rows and
 * columns. Plain recognizer text flattens a table column-by-column or line-by-line and loses
 * which value belongs to which row/column.
 *
 * Output: normal lines stay as lines; every row of a detected table becomes one line whose
 * cells are separated by a TAB character (empty cells stay empty, so columns always line up).
 * The exporters (XLSX, DOCX, PDF) turn tab-separated runs back into real columns.
 *
 * If no table is found, [toLayoutText] returns [fallback] (the recognizer's own text) untouched.
 */
object OcrLayout {

    private class Cell(var text: String, var left: Int, var right: Int)

    private class Row(first: OcrWord) {
        val words = mutableListOf(first)
        private var sumCy = (first.top + first.bottom) / 2f
        private var sumH = (first.bottom - first.top).toFloat()
        val cy get() = sumCy / words.size
        val height get() = sumH / words.size
        val top get() = words.minOf { it.top }
        val bottom get() = words.maxOf { it.bottom }
        fun add(w: OcrWord) {
            words.add(w)
            sumCy += (w.top + w.bottom) / 2f
            sumH += (w.bottom - w.top).toFloat()
        }
    }

    fun toLayoutText(lines: List<OcrLine>, fallback: String): String {
        val words = lines.flatMap { l ->
            if (l.words.isNotEmpty()) l.words else listOf(OcrWord(l.text, l.left, l.top, l.right, l.bottom))
        }.filter { it.text.isNotBlank() && it.bottom > it.top }
        if (words.size < 6) return fallback

        val medianH = words.map { it.bottom - it.top }.sorted().let { it[it.size / 2] }.coerceAtLeast(1)
        val rows = buildRows(words)
        val gap = (medianH * 0.8f).toInt()
        val cellRows = rows.map { cellsOf(it, gap) }
        val n = rows.size

        val blocks = findBlocks(cellRows, rows, medianH)
        val rendered = mutableMapOf<Int, List<List<String>>>() // block start -> table rows
        val blockEnd = mutableMapOf<Int, Int>()
        for (b in blocks) {
            val table = buildTable(b, cellRows, rows, medianH) ?: continue
            rendered[b.first] = table
            blockEnd[b.first] = b.last
        }
        if (rendered.isEmpty()) return fallback

        val out = StringBuilder()
        var i = 0
        while (i < n) {
            val table = rendered[i]
            if (table != null) {
                // A blank line before/after keeps neighbouring tables from fusing into one.
                if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append('\n')
                table.forEach { out.append(it.joinToString("\t")).append('\n') }
                out.append('\n')
                i = blockEnd.getValue(i) + 1
            } else {
                out.append(cellRows[i].joinToString(" ") { it.text }).append('\n')
                i++
            }
        }
        return out.toString().trimEnd('\n')
    }

    /**
     * Pulls just the tables out of [toLayoutText] output: every tab-separated line becomes a
     * row, an empty row separates one table from the next, and rows are padded to equal width.
     */
    fun tableRows(layoutText: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var inTable = false
        for (line in layoutText.lines()) {
            if (line.contains('\t')) {
                rows.add(line.split('\t'))
                inTable = true
            } else if (inTable) {
                rows.add(emptyList())
                inTable = false
            }
        }
        while (rows.isNotEmpty() && rows.last().isEmpty()) rows.removeAt(rows.lastIndex)
        val cols = rows.maxOfOrNull { it.size } ?: return emptyList()
        return rows.map { r -> r + List(cols - r.size) { "" } }
    }

    private fun buildRows(words: List<OcrWord>): List<Row> {
        val rows = mutableListOf<Row>()
        for (w in words.sortedBy { it.top + it.bottom }) {
            val cy = (w.top + w.bottom) / 2f
            val last = rows.lastOrNull()
            if (last != null && abs(cy - last.cy) <= 0.5f * min((w.bottom - w.top).toFloat(), last.height)) last.add(w)
            else rows.add(Row(w))
        }
        return rows
    }

    private fun cellsOf(row: Row, gap: Int): List<Cell> {
        val out = mutableListOf<Cell>()
        for (w in row.words.sortedBy { it.left }) {
            val c = out.lastOrNull()
            if (c != null && w.left - c.right <= gap) {
                c.text += " " + w.text
                c.right = max(c.right, w.right)
            } else out.add(Cell(w.text, w.left, w.right))
        }
        return out
    }

    /**
     * Splits the page into separate tables. Rows are added to the current table only while
     * they fit its column structure and sit close to the previous row; a new column layout,
     * or a clear vertical gap, starts the next table. One single-cell row directly under a
     * table row (a wrapped line) stays inside the table.
     */
    private fun findBlocks(cellRows: List<List<Cell>>, rows: List<Row>, medianH: Int): List<IntRange> {
        val blocks = mutableListOf<IntRange>()
        var start = -1
        var lastMulti = -1
        var last = -1
        var groupCells = mutableListOf<Cell>()
        var multiCount = 0
        val tol = (0.4f * medianH).toInt()

        fun close() {
            if (start >= 0 && multiCount >= 2) blocks.add(start..lastMulti)
            start = -1; lastMulti = -1; last = -1; multiCount = 0
            groupCells = mutableListOf()
        }

        for (r in cellRows.indices) {
            val cells = cellRows[r]
            if (cells.size >= 2) {
                if (start >= 0) {
                    val gap = rows[r].top - rows[last].bottom
                    if (gap > 2.2f * medianH || !compatible(cells, bandsOf(groupCells), tol)) close()
                }
                if (start < 0) start = r
                groupCells.addAll(cells)
                multiCount++
                lastMulti = r
                last = r
            } else if (start >= 0) {
                val gap = rows[r].top - rows[last].bottom
                val bands = bandsOf(groupCells)
                val fits = cells.isNotEmpty() && bands.any { overlaps(cells[0], it, tol) }
                if (gap <= 0.8f * medianH && fits) last = r else close()
            }
        }
        close()
        return blocks
    }

    private fun overlaps(c: Cell, b: IntArray, tol: Int) = c.left <= b[1] + tol && c.right >= b[0] - tol

    /** Column bands = merged horizontal extents of the cells (very wide spanning cells ignored). */
    private fun bandsOf(cells: List<Cell>): List<IntArray> {
        if (cells.isEmpty()) return emptyList()
        val width = (cells.maxOf { it.right } - cells.minOf { it.left }).coerceAtLeast(1)
        val usable = cells.filter { it.right - it.left <= 0.5f * width }.ifEmpty { cells }
        val bands = mutableListOf<IntArray>()
        for (c in usable.sortedBy { it.left }) {
            val last = bands.lastOrNull()
            if (last != null && c.left <= last[1]) last[1] = max(last[1], c.right) else bands.add(intArrayOf(c.left, c.right))
        }
        return bands
    }

    /** True if a row's cells line up with the table's columns (no spanning, no two cells in one column). */
    private fun compatible(cells: List<Cell>, bands: List<IntArray>, tol: Int): Boolean {
        if (bands.isEmpty()) return true
        val width = (bands.last()[1] - bands.first()[0]).coerceAtLeast(1)
        val hit = IntArray(bands.size)
        var shared = false
        for (c in cells) {
            if (bands.size >= 2 && c.right - c.left > 0.5f * width) continue
            val idx = bands.indices.filter { overlaps(c, bands[it], tol) }
            if (idx.size >= 2) return false
            if (idx.size == 1) {
                if (++hit[idx[0]] > 1) return false
                shared = true
            }
        }
        return shared
    }

    private fun buildTable(block: IntRange, cellRows: List<List<Cell>>, rows: List<Row>, medianH: Int): List<List<String>>? {
        val multiCells = block.filter { cellRows[it].size >= 2 }.flatMap { cellRows[it] }
        // Prose in two newspaper-style columns has long "cells"; real table cells are short.
        val avgWords = multiCells.map { it.text.split(' ').size }.average()
        if (avgWords > 6) return null

        val bands = bandsOf(multiCells)
        if (bands.size !in 2..15) return null

        fun bandOf(c: Cell): Int {
            var best = 0
            var bestOverlap = Int.MIN_VALUE
            for ((idx, b) in bands.withIndex()) {
                val overlap = min(b[1], c.right) - max(b[0], c.left)
                if (overlap > bestOverlap) { bestOverlap = overlap; best = idx }
            }
            if (bestOverlap <= 0) {
                val cx = (c.left + c.right) / 2
                best = bands.indices.minByOrNull { abs((bands[it][0] + bands[it][1]) / 2 - cx) } ?: 0
            }
            return best
        }

        val table = mutableListOf<MutableList<String>>()
        var prevRowIndex = -1
        for (r in block) {
            val cells = cellRows[r]
            val filled = MutableList(bands.size) { "" }
            for (c in cells) {
                val b = bandOf(c)
                filled[b] = if (filled[b].isEmpty()) c.text else filled[b] + " " + c.text
            }
            val isContinuation = cells.size == 1 && table.isNotEmpty() && prevRowIndex >= 0 &&
                rows[r].top - rows[prevRowIndex].bottom <= 0.8f * medianH
            if (isContinuation) {
                val b = bandOf(cells[0])
                val prev = table.last()
                prev[b] = if (prev[b].isEmpty()) cells[0].text else prev[b] + " " + cells[0].text
            } else table.add(filled)
            prevRowIndex = r
        }
        return table
    }
}
