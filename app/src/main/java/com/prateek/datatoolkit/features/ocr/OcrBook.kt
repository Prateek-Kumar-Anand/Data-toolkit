package com.prateek.datatoolkit.features.ocr

import kotlin.math.abs

/**
 * Book-page OCR: turns recognized lines into clean text that keeps the book's structure.
 *
 * - Headings are found by their size relative to body text (plus centering, ALL CAPS,
 *   "Chapter N" and "1.2 Title" patterns) and written as `# `, `## `, `### ` lines.
 * - Wrapped lines are joined into paragraphs (hyphenated line-end words are rejoined),
 *   paragraphs are separated by a blank line.
 * - Bare page numbers at the top/bottom of a page are dropped.
 * - Reading order is ML Kit's own block order, so two-column pages stay in order.
 */
object OcrBook {

    private class L(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val block: Int) {
        val h get() = (bottom - top).coerceAtLeast(1)
        val cx get() = (left + right) / 2
    }

    private class Item(val level: Int, var text: String)

    private val CHAPTER = Regex("^(chapter|part|book|section|unit|lesson|appendix|prologue|epilogue|preface|introduction|contents|foreword)\\b.*", RegexOption.IGNORE_CASE)
    private val NUMBERED = Regex("^(\\d+(\\.\\d+)*[.)]?|[IVXLC]+\\.)\\s+\\S.*")
    private val PAGE_NUMBER = Regex("^\\W*(\\d{1,4}|[ivxlcIVXLC]{1,6})\\W*$")

    fun toBookText(lines: List<OcrLine>, pageWidth: Int, pageHeight: Int, fallback: String): String {
        var ls = lines.filter { it.text.isNotBlank() }
            .map { L(it.text.trim(), it.left, it.top, it.right, it.bottom, it.block) }
        if (ls.isEmpty()) return fallback
        val pw = (if (pageWidth > 0) pageWidth else ls.maxOf { it.right }).coerceAtLeast(1)
        val ph = (if (pageHeight > 0) pageHeight else ls.maxOf { it.bottom }).coerceAtLeast(1)

        ls = ls.filterNot { PAGE_NUMBER.matches(it.text) && (it.bottom < ph * 0.09 || it.top > ph * 0.91) }
        if (ls.isEmpty()) return fallback

        val bodyH = weightedMedianHeight(ls)
        val linesPerBlock = ls.groupingBy { it.block }.eachCount()
        val blockLeft = ls.groupBy { it.block }.mapValues { e -> e.value.minOf { it.left } }
        val blockRight = ls.groupBy { it.block }.mapValues { e -> e.value.maxOf { it.right } }

        val items = mutableListOf<Item>()
        val para = StringBuilder()
        var prev: L? = null
        var prevWasHeading = false
        var dropCap = ""

        fun flush() {
            if (para.isNotBlank()) items.add(Item(0, para.toString().trim()))
            para.setLength(0)
        }

        for (l in ls) {
            // A huge 1-2 letter line is a decorative drop cap: glue it onto the next body line.
            if (l.text.length <= 2 && l.h > bodyH * 1.8f) { dropCap = l.text; continue }

            val level = headingLevel(l, bodyH, pw, linesPerBlock[l.block] == 1)
            if (level > 0) {
                flush()
                val last = items.lastOrNull()
                val p = prev
                if (prevWasHeading && last != null && last.level == level && p != null &&
                    p.block == l.block && l.top - p.bottom <= l.h
                ) last.text += " " + l.text
                else items.add(Item(level, l.text))
                prev = l
                prevWasHeading = true
                continue
            }

            val text = if (dropCap.isNotEmpty()) (dropCap + l.text).also { dropCap = "" } else l.text
            val p = prev
            val startNew = when {
                para.isEmpty() -> true
                p == null -> false
                l.block != p.block -> endsSentence(para) || !(text.first().isLowerCase())
                else -> {
                    val gap = l.top - p.bottom
                    val indent = l.left - (blockLeft[l.block] ?: l.left)
                    val prevShort = p.right < (blockRight[p.block] ?: p.right) - 3 * bodyH
                    gap > 0.9f * bodyH || indent > 1.2f * bodyH || (prevShort && endsSentence(para))
                }
            }
            if (startNew) flush()
            appendJoined(para, text)
            prev = l
            prevWasHeading = false
        }
        flush()

        if (items.isEmpty()) return fallback
        return items.joinToString("\n\n") { if (it.level > 0) "#".repeat(it.level) + " " + it.text else it.text }
    }

    /** Joins pages into one flow; a paragraph cut by a page break is stitched back together. */
    fun joinPages(pages: List<String>): String {
        val paras = mutableListOf<String>()
        for (p in pages.map { it.trim() }.filter { it.isNotEmpty() }) {
            val ps = p.split("\n\n")
            val last = paras.lastOrNull()
            val first = ps.first()
            if (last != null && !last.startsWith("#") && !first.startsWith("#") &&
                !endsSentence(last) && first.firstOrNull()?.isLowerCase() == true
            ) {
                val sb = StringBuilder(last)
                appendJoined(sb, first)
                paras[paras.lastIndex] = sb.toString()
                paras.addAll(ps.drop(1))
            } else paras.addAll(ps)
        }
        return paras.joinToString("\n\n")
    }

    private fun appendJoined(sb: StringBuilder, text: String) {
        if (sb.isEmpty()) { sb.append(text); return }
        val soft = sb.last() == '\u00AD'
        if ((sb.last() == '-' || soft) && text.firstOrNull()?.isLowerCase() == true && sb.length >= 2 && sb[sb.length - 2].isLetter()) {
            sb.setLength(sb.length - 1)
            sb.append(text)
        } else sb.append(' ').append(text)
    }

    private fun endsSentence(s: CharSequence): Boolean {
        val c = s.trimEnd().lastOrNull() ?: return true
        return c in ".?!:\"\u201D\u2019)\u2026"
    }

    private fun weightedMedianHeight(ls: List<L>): Int {
        val sorted = ls.sortedBy { it.h }
        val total = sorted.sumOf { it.text.length }
        var cum = 0
        for (l in sorted) {
            cum += l.text.length
            if (cum * 2 >= total) return l.h
        }
        return sorted.last().h
    }

    private fun headingLevel(l: L, bodyH: Int, pw: Int, alone: Boolean): Int {
        val words = l.text.split(Regex("\\s+")).size
        if (words > 14) return 0
        val ratio = l.h.toFloat() / bodyH
        val letters = l.text.filter { it.isLetter() }
        val allCaps = letters.length >= 3 && letters.all { it.isUpperCase() }
        val centered = abs(l.cx - pw / 2) < 0.07 * pw && (l.right - l.left) < 0.8 * pw
        val endsPunct = l.text.trimEnd().lastOrNull() in listOf('.', ',', ';')
        return when {
            ratio >= 1.5f -> 1
            ratio >= 1.25f -> 2
            CHAPTER.matches(l.text) && words <= 8 && (ratio >= 1.1f || centered || allCaps) -> if (ratio >= 1.1f || allCaps) 1 else 2
            ratio >= 1.12f && (centered || allCaps) && words <= 10 -> 3
            allCaps && words <= 8 && letters.length >= 4 && alone -> 3
            NUMBERED.matches(l.text) && words <= 10 && ratio >= 1.08f && !endsPunct -> 3
            else -> 0
        }
    }
}
