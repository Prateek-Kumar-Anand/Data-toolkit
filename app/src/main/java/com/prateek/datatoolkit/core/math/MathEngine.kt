package com.prateek.datatoolkit.core.math

import kotlin.math.abs
import kotlin.math.max

/**
 * Basic Calculations: a small, shared arithmetic engine for the two places this app computes
 * or verifies a numeric relationship rather than just transforming/validating text -
 * Data Cleaning's calculated-column rule (e.g. "Amount" column = Quantity × Unit Price) and
 * the Invoice module's gap-filling (e.g. a missing Subtotal filled from the sum of line-item
 * amounts) and totals-checking (Subtotal + Tax vs Total). Both build on the same number
 * parsing and tolerance rules here instead of each maintaining their own.
 *
 * Same guiding rule as every other normalizer in this app: never guess. A value that can't be
 * read as a number, or a calculation that would require dividing by zero, returns null instead
 * of a coerced/mangled result.
 */
object MathEngine {

    enum class Operator(val symbol: String) { ADD("+"), SUBTRACT("\u2212"), MULTIPLY("\u00d7"), DIVIDE("\u00f7") }

    private val CURRENCY_CODE = Regex("(?i)\\b(INR|USD|EUR|GBP|Rs\\.?)\\s*")
    private val CURRENCY_SYMBOLS_AND_SPACE = Regex("[\u20b9$\u20ac\u00a3,\\s]")

    /**
     * Parses [raw] as a plain number - tolerating currency symbols (₹$€£), thousands-separator
     * commas, currency codes (INR/USD/EUR/GBP/Rs), and accounting-style negative parentheses,
     * since spreadsheet cells and invoice fields are both "money-ish text" far more often than
     * a bare number. Deliberately strict everywhere else: once those known extras are stripped,
     * whatever remains must parse as a clean number on its own, so "3 pcs" or any other stray
     * unit/letter attached to a number still fails rather than silently parsing as just the
     * numeric part - a caller can't tell "definitely 3" apart from "possibly 3, possibly 300,
     * who knows" if this quietly strips the part of the text that would have told them.
     */
    fun parseNumber(raw: String): Double? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val negative = trimmed.length > 1 && trimmed.startsWith("(") && trimmed.endsWith(")")
        val unwrapped = if (negative) trimmed.substring(1, trimmed.length - 1) else trimmed

        val stripped = CURRENCY_SYMBOLS_AND_SPACE.replace(CURRENCY_CODE.replace(unwrapped, ""), "")
        if (stripped.isEmpty()) return null

        val value = stripped.toDoubleOrNull() ?: return null
        return if (negative) -abs(value) else value
    }

    /** Applies [op] to (a, b). Null on divide-by-zero, rather than the Infinity/NaN that would
     *  otherwise silently poison anything computed from the result. */
    fun apply(a: Double, op: Operator, b: Double): Double? = when (op) {
        Operator.ADD -> a + b
        Operator.SUBTRACT -> a - b
        Operator.MULTIPLY -> a * b
        Operator.DIVIDE -> if (b == 0.0) null else a / b
    }

    /** Formats a computed value for display/export: rounded to [decimals] places as plain
     *  fixed-point text - never scientific notation, never a stray floating-point artifact
     *  like "12.000000000002". Returns "" for a NaN/Infinite result (e.g. a divide-by-zero
     *  that slipped through some other way) rather than printing a nonsense value. */
    fun formatNumber(value: Double, decimals: Int = 2): String {
        if (value.isNaN() || value.isInfinite()) return ""
        val factor = Math.pow(10.0, decimals.toDouble())
        val rounded = Math.round(value * factor) / factor
        return "%.${decimals}f".format(rounded)
    }

    // --- Solving a "whole = partA op partB" relationship when exactly one side is missing -----
    // Used by the Invoice module to fill a gap left by OCR (a line item's Amount, or the
    // invoice-level Subtotal/Tax/Total) from whichever of the other values WERE detected -
    // each of these fills exactly one missing number and never overwrites a value that was
    // actually read off the page (that's the caller's job to check before calling).

    /** Solves `knownFactor × missingFactor = product` for the missing factor - e.g. Unit Price
     *  from a known Quantity and Amount. Null (never a guess) if [knownFactor] is zero. */
    fun solveFactor(knownFactor: Double, product: Double): Double? =
        if (knownFactor == 0.0) null else product / knownFactor

    /** `factor1 × factor2` - e.g. an item's Amount from its Quantity and Unit Price. */
    fun solveProduct(factor1: Double, factor2: Double): Double = factor1 * factor2

    /** Solves `knownAddend + missingAddend = sum` for the missing addend - e.g. Tax from a
     *  known Subtotal and Total. */
    fun solveAddend(knownAddend: Double, sum: Double): Double = sum - knownAddend

    /** `addend1 + addend2` - e.g. Total from a known Subtotal and Tax. */
    fun solveSum(addend1: Double, addend2: Double): Double = addend1 + addend2

    /**
     * True if [a] and [b] agree within a tolerance appropriate for money: whichever is bigger,
     * 1% of it or 0.02 - the same rule of thumb [com.prateek.datatoolkit.features.invoice.InvoiceParser]
     * already used for its item-sum-vs-subtotal check, now shared so every totals check in the
     * app agrees on what counts as "close enough to be rounding" versus a genuine mismatch.
     */
    fun approximatelyEquals(a: Double, b: Double): Boolean {
        val tolerance = max(0.02, max(abs(a), abs(b)) * 0.01)
        return abs(a - b) <= tolerance
    }
}
