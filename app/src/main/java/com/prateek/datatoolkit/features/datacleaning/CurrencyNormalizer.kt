package com.prateek.datatoolkit.features.datacleaning

/** Strips currency symbols (₹, $, €, £), thousands separators, currency codes, and
 * accounting-style parentheses down to a plain numeric string. Returns null - rather than
 * a mangled guess - when what's left isn't actually a number. */
object CurrencyNormalizer {

    private val SYMBOLS_AND_CODES = Regex("[₹$€£,\\s]|INR|USD|EUR|GBP|Rs\\.?", RegexOption.IGNORE_CASE)
    private val NON_NUMERIC = Regex("[^0-9.\\-]")

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val negative = trimmed.startsWith("(") && trimmed.endsWith(")")
        var v = SYMBOLS_AND_CODES.replace(trimmed, "")
        v = v.replace("(", "").replace(")", "")
        v = NON_NUMERIC.replace(v, "")
        if (v.isEmpty() || v == "-" || v == ".") return null

        val num = v.toDoubleOrNull() ?: return null
        val signed = if (negative) -kotlin.math.abs(num) else num
        return if (!signed.isInfinite() && signed == Math.floor(signed)) {
            signed.toLong().toString()
        } else {
            signed.toString()
        }
    }
}
