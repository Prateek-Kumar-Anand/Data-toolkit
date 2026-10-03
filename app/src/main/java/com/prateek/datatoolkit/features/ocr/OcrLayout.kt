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

        val blocks = findBlocks(cellRows, rows)
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
                table.forEach { out.append(it.joinToString("\t")).append('\n') }
                i = blockEnd.getValue(i) + 1
            } else {
                out.append(cellRows[i].joinToString(" ") { it.text }).append('\n')
                i++
            }
        }
        return out.toString().trimEnd('\n')
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

    /** Runs of rows with 2+ cells (one single-cell row may sit inside a run: a wrapped line). */
    private fun findBlocks(cellRows: List<List<Cell>>, rows: List<Row>): List<IntRange> {
        val n = cellRows.size
        val multi = BooleanArray(n) { cellRows[it].size >= 2 }
        val blocks = mutableListOf<IntRange>()
        var i = 0
        while (i < n) {
            if (!multi[i]) { i++; continue }
            var end = i
            var j = i + 1
            while (j < n) {
                if (multi[j]) { end = j; j++ }
                else if (j + 1 < n && multi[j + 1]) j++
                else break
            }
            if ((i..end).count { multi[it] } >= 2) blocks.add(i..end)
            i = end + 1
        }
        return blocks
    }

    private fun buildTable(block: IntRange, cellRows: List<List<Cell>>, rows: List<Row>, medianH: Int): List<List<String>>? {
        val multiCells = block.filter { cellRows[it].size >= 2 }.flatMap { cellRows[it] }
        // Prose in two newspaper-style columns has long "cells"; real table cells are short.
        val avgWords = multiCells.map { it.text.split(' ').size }.average()
        if (avgWords > 6) return null

        val minL = multiCells.minOf { it.left }
        val maxR = multiCells.maxOf { it.right }
        val width = (maxR - minL).coerceAtLeast(1)
        val usable = multiCells.filter { it.right - it.left <= 0.5f * width }.ifEmpty { multiCells }

        // Column bands = merged horizontal extents of cells across all rows.
        val bands = mutableListOf<IntArray>()
        for (c in usable.sortedBy { it.left }) {
            val last = bands.lastOrNull()
            if (last != null && c.left <= last[1]) last[1] = max(last[1], c.right) else bands.add(intArrayOf(c.left, c.right))
        }
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
