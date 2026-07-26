package com.prateek.datatoolkit.core.quality

/**
 * Quality Scoring: a single, reusable rubric (0-100) applied by every
 * feature to its own output, so results are comparable on the dashboard
 * regardless of which tool produced them.
 */
object QualityScorer {

    /** Generic scorer for any extracted/cleaned text (OCR output, scraped text, cleaned CSV cell text, etc). */
    fun scoreText(text: String, expectedMinLength: Int = 20): Int {
        if (text.isBlank()) return 0
        var score = 0.0

        // 1. Length / substance (0-30)
        val lengthScore = (text.trim().length.toDouble() / expectedMinLength).coerceAtMost(1.0) * 30
        score += lengthScore

        // 2. Printable-character ratio - penalizes OCR garbage / mojibake (0-30)
        val printable = text.count { it.isLetterOrDigit() || it.isWhitespace() || it in ".,;:!?()'\"-_/@%&" }
        val printableRatio = if (text.isNotEmpty()) printable.toDouble() / text.length else 0.0
        score += printableRatio * 30

        // 3. Word structure - average word length in a plausible range suggests real words, not noise (0-20)
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val avgWordLen = if (words.isNotEmpty()) words.map { it.length }.average() else 0.0
        val wordScore = if (avgWordLen in 2.0..12.0) 20.0 else 8.0
        score += if (words.isEmpty()) 0.0 else wordScore

        // 4. Whitespace sanity - not mostly blank lines / broken layout (0-20)
        val lines = text.lines()
        val blankLineRatio = if (lines.isNotEmpty()) lines.count { it.isBlank() }.toDouble() / lines.size else 0.0
        score += (1.0 - blankLineRatio).coerceIn(0.0, 1.0) * 20

        return score.coerceIn(0.0, 100.0).toInt()
    }

    /** Scorer for tabular data (CSV/Excel rows, scraped tables): completeness + consistency. */
    fun scoreTable(rows: List<List<String>>): Int {
        if (rows.isEmpty()) return 0
        val header = rows.first()
        val dataRows = rows.drop(1)
        if (dataRows.isEmpty()) return 40 // header only, nothing to grade

        val expectedCols = header.size
        var filledCells = 0
        var totalCells = 0
        var consistentRows = 0

        for (row in dataRows) {
            totalCells += expectedCols
            filledCells += row.count { it.isNotBlank() }
            if (row.size == expectedCols) consistentRows++
        }

        val completeness = if (totalCells > 0) filledCells.toDouble() / totalCells else 0.0
        val consistency = consistentRows.toDouble() / dataRows.size
        val duplicateRatio = 1.0 - (dataRows.map { it.joinToString("|") }.distinct().size.toDouble() / dataRows.size)

        val score = (completeness * 50) + (consistency * 35) + ((1.0 - duplicateRatio) * 15)
        return score.coerceIn(0.0, 100.0).toInt()
    }

    /** Scorer for a list of extracted emails: validity + duplicate rate. */
    fun scoreEmailList(emails: List<String>): Int {
        if (emails.isEmpty()) return 0
        val emailRegex = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        val validCount = emails.count { emailRegex.matches(it) }
        val validRatio = validCount.toDouble() / emails.size
        val distinctRatio = emails.distinct().size.toDouble() / emails.size
        return ((validRatio * 70) + (distinctRatio * 30)).coerceIn(0.0, 100.0).toInt()
    }

    fun label(score: Int): String = when {
        score >= 85 -> "Excellent"
        score >= 65 -> "Good"
        score >= 40 -> "Fair"
        else -> "Poor"
    }
}
