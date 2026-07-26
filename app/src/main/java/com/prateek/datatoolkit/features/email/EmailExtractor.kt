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

    fun extract(text: String): ExtractionResult {
        val candidates = candidatePattern.findAll(text).map { it.value.trim('.', ',') }.toList()

        val valid = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        for (c in candidates) {
            if (strictPattern.matches(c)) valid.add(c) else rejected.add(c)
        }

        val beforeDedupe = valid.size
        val deduped = valid.map { it.lowercase() }.distinct().sorted()

        return ExtractionResult(
            emails = deduped,
            rejected = rejected.distinct(),
            duplicatesRemoved = beforeDedupe - deduped.size
        )
    }

    fun toCsv(emails: List<String>): String =
        "email\n" + emails.joinToString("\n")
}
