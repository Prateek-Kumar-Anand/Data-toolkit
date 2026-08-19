package com.prateek.datatoolkit.features.excel.sheet.formula

import com.prateek.datatoolkit.core.math.MathEngine
import kotlin.math.abs

/**
 * What a formula (or a piece of one, mid-evaluation) resolves to. Mirrors the small set of
 * types Excel formulas actually work with - a blank cell is its own case rather than folded
 * into "number 0" or "empty string", because which one it should act like depends on context
 * exactly the way it does in real Excel: `=A1+1` treats a blank A1 as 0, `=A1&"x"` treats it
 * as "", and `=COUNT(A1:A10)` doesn't count it at all.
 */
sealed class FormulaValue {
    data class NumberValue(val value: Double) : FormulaValue()
    data class TextValue(val value: String) : FormulaValue()
    data class BoolValue(val value: Boolean) : FormulaValue()
    object EmptyValue : FormulaValue()
    /** [code] is one of the real Excel error tokens (#DIV/0!, #VALUE!, #REF!, #NAME?, #NUM!,
     *  #CIRCULAR!) - shown verbatim in the cell exactly like Excel would show it. */
    data class ErrorValue(val code: String) : FormulaValue()

    val isError: Boolean get() = this is ErrorValue

    /** Numeric value per Excel's usual coercion: TRUE/FALSE -> 1/0, blank -> 0, text that looks
     *  like a number parses (reusing the same currency/comma-tolerant parser the rest of the
     *  app uses - see MathEngine), anything else is a #VALUE! error. */
    fun asNumber(): FormulaValue = when (this) {
        is NumberValue -> this
        is BoolValue -> NumberValue(if (value) 1.0 else 0.0)
        is EmptyValue -> NumberValue(0.0)
        is TextValue -> MathEngine.parseNumber(value)?.let { NumberValue(it) } ?: ErrorValue("#VALUE!")
        is ErrorValue -> this
    }

    /** Display/concatenation text per Excel's usual coercion: numbers formatted the same way
     *  a cell showing that number would be, TRUE/FALSE literally, blank -> "". */
    fun asText(): String = when (this) {
        is TextValue -> value
        is NumberValue -> formatResultNumber(value)
        is BoolValue -> if (value) "TRUE" else "FALSE"
        is EmptyValue -> ""
        is ErrorValue -> code
    }

    /** Truthiness per Excel's IF/AND/OR: numbers are true unless exactly 0, blank is false,
     *  text must be exactly "TRUE"/"FALSE" (case-insensitive) or it's a #VALUE! error - Excel
     *  does not treat an arbitrary non-empty string as truthy the way many languages do. */
    fun asBoolean(): FormulaValue = when (this) {
        is BoolValue -> this
        is NumberValue -> BoolValue(value != 0.0)
        is EmptyValue -> BoolValue(false)
        is TextValue -> when (value.trim().uppercase()) {
            "TRUE" -> BoolValue(true)
            "FALSE" -> BoolValue(false)
            else -> ErrorValue("#VALUE!")
        }
        is ErrorValue -> this
    }

    /** What this value looks like sitting in a cell - the string [com.prateek.datatoolkit.features.excel.sheet.ComputedResult.display]
     *  is built from. */
    fun toDisplayString(): String = when (this) {
        is NumberValue -> formatResultNumber(value)
        is TextValue -> value
        is BoolValue -> if (value) "TRUE" else "FALSE"
        is EmptyValue -> ""
        is ErrorValue -> code
    }
}

/** Excel shows a whole number as "4", not "4.0000000000", and trims trailing zeros on a
 *  fractional result ("4.5" not "4.5000000000") - this is the one shared place that rule
 *  lives, so every function/operator that produces a number displays it the same way. */
fun formatResultNumber(value: Double): String {
    if (value.isNaN()) return "#VALUE!"
    if (value.isInfinite()) return "#DIV/0!"
    if (abs(value) < 1e15 && value == Math.floor(value)) {
        return value.toLong().toString()
    }
    val fixed = "%.10f".format(value)
    return fixed.trimEnd('0').trimEnd('.')
}
