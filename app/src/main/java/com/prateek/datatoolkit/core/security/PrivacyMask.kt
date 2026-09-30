package com.prateek.datatoolkit.core.security

/** Masks personal data before it's stored in the history database (previews only). */
object PrivacyMask {
    private val EMAIL = Regex("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})")
    private val LONG_DIGITS = Regex("\\b(?:\\d[ -]?){12,19}\\b")

    fun maskPreview(text: String, maxLength: Int = 300): String {
        var out = text.take(maxLength)
        out = EMAIL.replace(out) { "${it.groupValues[1]}***@${it.groupValues[2]}" }
        out = LONG_DIGITS.replace(out) { m -> "••••" + m.value.filter { it.isDigit() }.takeLast(4) }
        return out
    }
}
