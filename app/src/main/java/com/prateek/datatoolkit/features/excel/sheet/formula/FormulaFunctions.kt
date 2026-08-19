package com.prateek.datatoolkit.features.excel.sheet.formula

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The non-control-flow function library. IF/IFERROR/AND/OR live in [FormulaEvaluator] instead
 * of here, since they need lazy/short-circuit access to *unevaluated* argument nodes -
 * `=IF(A1<>0, 1/A1, 0)` must never evaluate `1/A1` when A1 is 0, and a library function that
 * only ever receives already-evaluated arguments has no way to skip that. Every function here
 * receives fully-evaluated arguments, with any range argument already flattened to one
 * [FormulaValue] per cell in that range.
 */
object FormulaFunctions {

    fun call(name: String, args: List<FormulaValue>): FormulaValue {
        // COUNT/COUNTA are deliberately tolerant of errors in their range (that's their whole
        // job - characterizing a range regardless of what's in it - and COUNTA specifically
        // counts an error cell as "present"), so they're handled before the generic
        // error-short-circuit below applies to everything else.
        when (name) {
            "COUNT" -> return FormulaValue.NumberValue(args.count { it is FormulaValue.NumberValue }.toDouble())
            "COUNTA" -> return FormulaValue.NumberValue(args.count { it !is FormulaValue.EmptyValue }.toDouble())
        }
        for (a in args) if (a.isError) return a

        return when (name) {
            "SUM" -> FormulaValue.NumberValue(numbersOnly(args).sum())
            "AVERAGE" -> {
                val nums = numbersOnly(args)
                if (nums.isEmpty()) FormulaValue.ErrorValue("#DIV/0!") else FormulaValue.NumberValue(nums.average())
            }
            "MIN" -> numbersOnly(args).let { FormulaValue.NumberValue(if (it.isEmpty()) 0.0 else it.min()) }
            "MAX" -> numbersOnly(args).let { FormulaValue.NumberValue(if (it.isEmpty()) 0.0 else it.max()) }
            "ROUND" -> roundFn(args)
            "ABS" -> unaryMath(args) { abs(it) }
            "INT" -> unaryMath(args) { Math.floor(it) }
            "SQRT" -> sqrtFn(args)
            "POWER" -> powerFn(args)
            "MOD" -> modFn(args)
            "NOT" -> notFn(args)
            "LEN" -> FormulaValue.NumberValue(textArg(args, 0).length.toDouble())
            "UPPER" -> FormulaValue.TextValue(textArg(args, 0).uppercase())
            "LOWER" -> FormulaValue.TextValue(textArg(args, 0).lowercase())
            "TRIM" -> FormulaValue.TextValue(textArg(args, 0).trim().replace(Regex(" +"), " "))
            "CONCATENATE", "CONCAT" -> FormulaValue.TextValue(args.joinToString("") { it.asText() })
            // No custom format-string support (Excel's TEXT takes a format code as its 2nd
            // argument, e.g. "0.00") - passes the value through as its plain display text.
            "TEXT" -> FormulaValue.TextValue(args.getOrNull(0)?.asText() ?: "")
            else -> FormulaValue.ErrorValue("#NAME?")
        }
    }

    private fun numbersOnly(args: List<FormulaValue>): List<Double> =
        args.filterIsInstance<FormulaValue.NumberValue>().map { it.value }

    private fun numberArg(args: List<FormulaValue>, index: Int): FormulaValue =
        (args.getOrNull(index) ?: return FormulaValue.ErrorValue("#VALUE!")).asNumber()

    private fun textArg(args: List<FormulaValue>, index: Int): String =
        (args.getOrNull(index) ?: FormulaValue.EmptyValue).asText()

    private fun unaryMath(args: List<FormulaValue>, f: (Double) -> Double): FormulaValue {
        val n = numberArg(args, 0)
        return if (n !is FormulaValue.NumberValue) n else FormulaValue.NumberValue(f(n.value))
    }

    private fun roundFn(args: List<FormulaValue>): FormulaValue {
        val n = numberArg(args, 0)
        if (n !is FormulaValue.NumberValue) return n
        val decimals = if (args.size > 1) numberArg(args, 1) else FormulaValue.NumberValue(0.0)
        if (decimals !is FormulaValue.NumberValue) return decimals
        val factor = 10.0.pow(decimals.value.toInt())
        return FormulaValue.NumberValue(Math.round(n.value * factor) / factor)
    }

    private fun sqrtFn(args: List<FormulaValue>): FormulaValue {
        val n = numberArg(args, 0)
        if (n !is FormulaValue.NumberValue) return n
        return if (n.value < 0) FormulaValue.ErrorValue("#NUM!") else FormulaValue.NumberValue(sqrt(n.value))
    }

    private fun powerFn(args: List<FormulaValue>): FormulaValue {
        val base = numberArg(args, 0); if (base !is FormulaValue.NumberValue) return base
        val exp = numberArg(args, 1); if (exp !is FormulaValue.NumberValue) return exp
        return FormulaValue.NumberValue(base.value.pow(exp.value))
    }

    private fun modFn(args: List<FormulaValue>): FormulaValue {
        val a = numberArg(args, 0); if (a !is FormulaValue.NumberValue) return a
        val b = numberArg(args, 1); if (b !is FormulaValue.NumberValue) return b
        if (b.value == 0.0) return FormulaValue.ErrorValue("#DIV/0!")
        val r = a.value.rem(b.value)
        // Excel's MOD follows the sign of the divisor (like Python's %), not the dividend
        // (which is what Kotlin's rem does) - this adjusts the two cases where they'd disagree.
        val adjusted = if (r != 0.0 && (r < 0) != (b.value < 0)) r + b.value else r
        return FormulaValue.NumberValue(adjusted)
    }

    private fun notFn(args: List<FormulaValue>): FormulaValue {
        val b = (args.getOrNull(0) ?: FormulaValue.EmptyValue).asBoolean()
        return if (b !is FormulaValue.BoolValue) b else FormulaValue.BoolValue(!b.value)
    }
}
