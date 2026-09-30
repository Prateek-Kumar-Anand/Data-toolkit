package com.prateek.datatoolkit.features.email

/**
 * Email Extraction: pulls candidate email addresses out of any block of
 * text (pasted text, an OCR result, or the text/HTML from a scraped page),
 * validates them, and de-duplicates case-insensitively.
 */
object EmailExtractor {

    // Broad match first (catches "name at domain dot com"-style obfuscation is NOT handled -
    // this matches standard address syntax only, which is the common, reliable case).
    private val candidatePattern = Regex(
        "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
    )
    private val strictPattern = Regex(
        "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    )

    data class ExtractionResult(
        val emails: List<String>,      // valid, de-duplicated, sorted
        val rejected: List<String>,    // candidates that didn't pass validation
        val duplicatesRemoved: Int
    )

    private const val MAX_TOKEN_LENGTH = 320   // longer than any legal address (254) + punctuation
    private val whitespace = Regex("\\s+")

    fun extract(text: String): ExtractionResult {
        // Matching token by token keeps every regex run tiny - running the pattern over one huge
        // unbroken string is quadratic and could freeze the app on pasted junk.
        val candidates = ArrayList<String>()
        for (token in text.split(whitespace)) {
            if (token.length < 5 || token.length > MAX_TOKEN_LENGTH || '@' !in token) continue
            candidatePattern.findAll(token).forEach { candidates.add(it.value.trim('.', ',', '-', '_')) }
        }

        val valid = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        for (c in candidates) {
            if (isValid(c)) valid.add(c) else rejected.add(c)
        }

        val beforeDedupe = valid.size
        val deduped = valid.map { it.lowercase() }.distinct().sorted()

        return ExtractionResult(
            emails = deduped,
            rejected = rejected.distinct(),
            duplicatesRemoved = beforeDedupe - deduped.size
        )
    }

    /** Stricter than the regex alone: rejects a@b..com, .a@b.com, a@-b.com, over-long parts. */
    private fun isValid(email: String): Boolean {
        if (!strictPattern.matches(email) || email.length > 254) return false
        val local = email.substringBefore('@')
        val domain = email.substringAfter('@')
        if (local.length > 64 || local.startsWith('.') || local.endsWith('.') || local.contains("..")) return false
        if (domain.contains("..") || domain.startsWith('-') || domain.startsWith('.')) return false
        return domain.split('.').all { it.isNotEmpty() && it.length <= 63 && !it.startsWith('-') && !it.endsWith('-') }
    }

    fun toCsv(emails: List<String>): String =
        "email\n" + emails.joinToString("\n") { com.prateek.datatoolkit.core.security.CsvSafety.sanitizeCell(it) }
}
