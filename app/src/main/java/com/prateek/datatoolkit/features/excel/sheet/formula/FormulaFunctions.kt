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
            "COUNTBLANK" -> return FormulaValue.NumberValue(args.count { it is FormulaValue.EmptyValue || (it is FormulaValue.TextValue && it.value.isEmpty()) }.toDouble())
            "ISERROR" -> return FormulaValue.BoolValue(args.getOrNull(0)?.isError == true)
            "ISNUMBER" -> return FormulaValue.BoolValue(args.getOrNull(0) is FormulaValue.NumberValue)
            "ISTEXT" -> return FormulaValue.BoolValue(args.getOrNull(0) is FormulaValue.TextValue)
            "ISBLANK" -> return FormulaValue.BoolValue(args.getOrNull(0) is FormulaValue.EmptyValue)
            "PI" -> return FormulaValue.NumberValue(Math.PI)
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
            "TEXT" -> FormulaValue.TextValue(textFormat(args))
            "PRODUCT" -> numbersOnly(args).let { FormulaValue.NumberValue(if (it.isEmpty()) 0.0 else it.fold(1.0) { a, b -> a * b }) }
            "SUMSQ" -> FormulaValue.NumberValue(numbersOnly(args).sumOf { it * it })
            "MEDIAN" -> numbersOnly(args).sorted().let { n ->
                if (n.isEmpty()) FormulaValue.ErrorValue("#NUM!")
                else FormulaValue.NumberValue(if (n.size % 2 == 1) n[n.size / 2] else (n[n.size / 2 - 1] + n[n.size / 2]) / 2)
            }
            "MODE" -> numbersOnly(args).groupBy { it }.filter { it.value.size > 1 }.maxByOrNull { it.value.size }
                ?.let { FormulaValue.NumberValue(it.key) } ?: FormulaValue.ErrorValue("#N/A")
            "VAR", "VARP", "STDEV", "STDEVP" -> {
                val n = numbersOnly(args)
                val pop = name.endsWith("P")
                if (n.size < (if (pop) 1 else 2)) FormulaValue.ErrorValue("#DIV/0!") else {
                    val m = n.average()
                    val v = n.sumOf { (it - m) * (it - m) } / (if (pop) n.size else n.size - 1)
                    FormulaValue.NumberValue(if (name.startsWith("STDEV")) sqrt(v) else v)
                }
            }
            "LARGE", "SMALL" -> {
                val k = d(args, args.size - 1)?.toInt() ?: return FormulaValue.ErrorValue("#VALUE!")
                val n = numbersOnly(args.dropLast(1)).sorted().let { if (name == "LARGE") it.reversed() else it }
                if (k < 1 || k > n.size) FormulaValue.ErrorValue("#NUM!") else FormulaValue.NumberValue(n[k - 1])
            }
            "PERCENTILE" -> {
                val p = d(args, args.size - 1) ?: return FormulaValue.ErrorValue("#VALUE!")
                val n = numbersOnly(args.dropLast(1)).sorted()
                if (n.isEmpty() || p < 0 || p > 1) FormulaValue.ErrorValue("#NUM!") else {
                    val pos = p * (n.size - 1); val lo = Math.floor(pos).toInt(); val hi = Math.ceil(pos).toInt()
                    FormulaValue.NumberValue(n[lo] + (n[hi] - n[lo]) * (pos - lo))
                }
            }
            "CEILING", "FLOOR", "MROUND" -> {
                val x = d(args, 0) ?: return FormulaValue.ErrorValue("#VALUE!")
                val sig = if (args.size > 1) d(args, 1) ?: return FormulaValue.ErrorValue("#VALUE!") else 1.0
                if (sig == 0.0) FormulaValue.NumberValue(0.0) else FormulaValue.NumberValue(
                    when (name) { "CEILING" -> Math.ceil(x / sig) * sig; "FLOOR" -> Math.floor(x / sig) * sig; else -> Math.round(x / sig) * sig })
            }
            "ROUNDUP", "ROUNDDOWN" -> {
                val x = d(args, 0) ?: return FormulaValue.ErrorValue("#VALUE!")
                val f = 10.0.pow(if (args.size > 1) (d(args, 1) ?: 0.0).toInt() else 0)
                val sc = abs(x) * f
                val r = if (name == "ROUNDUP") Math.ceil(sc - 1e-9) else Math.floor(sc + 1e-9)
                FormulaValue.NumberValue(Math.signum(x) * r / f)
            }
            "TRUNC" -> {
                val x = d(args, 0) ?: return FormulaValue.ErrorValue("#VALUE!")
                val f = 10.0.pow(if (args.size > 1) (d(args, 1) ?: 0.0).toInt() else 0)
                FormulaValue.NumberValue(Math.signum(x) * Math.floor(abs(x) * f + 1e-9) / f)
            }
            "SIGN" -> unaryMath(args) { Math.signum(it) }
            "EXP" -> unaryMath(args) { Math.exp(it) }
            "SIN" -> unaryMath(args) { Math.sin(it) }
            "COS" -> unaryMath(args) { Math.cos(it) }
            "TAN" -> unaryMath(args) { Math.tan(it) }
            "ASIN" -> unaryMath(args) { Math.asin(it) }
            "ACOS" -> unaryMath(args) { Math.acos(it) }
            "ATAN" -> unaryMath(args) { Math.atan(it) }
            "DEGREES" -> unaryMath(args) { Math.toDegrees(it) }
            "RADIANS" -> unaryMath(args) { Math.toRadians(it) }
            "ATAN2" -> {
                val x = d(args, 0) ?: return FormulaValue.ErrorValue("#VALUE!")
                val y = d(args, 1) ?: return FormulaValue.ErrorValue("#VALUE!")
                FormulaValue.NumberValue(Math.atan2(y, x))
            }
            "LN" -> positiveMath(args) { Math.log(it) }
            "LOG10" -> positiveMath(args) { Math.log10(it) }
            "LOG" -> {
                val x = d(args, 0) ?: return FormulaValue.ErrorValue("#VALUE!")
                val b = if (args.size > 1) d(args, 1) ?: return FormulaValue.ErrorValue("#VALUE!") else 10.0
                if (x <= 0 || b <= 0 || b == 1.0) FormulaValue.ErrorValue("#NUM!") else FormulaValue.NumberValue(Math.log(x) / Math.log(b))
            }
            "FACT" -> {
                val x = d(args, 0)?.toInt() ?: return FormulaValue.ErrorValue("#VALUE!")
                if (x < 0 || x > 170) FormulaValue.ErrorValue("#NUM!") else FormulaValue.NumberValue((2..x).fold(1.0) { a, b -> a * b })
            }
            "GCD", "LCM" -> {
                val n = numbersOnly(args).map { Math.abs(it.toLong()) }
                if (n.isEmpty()) FormulaValue.ErrorValue("#VALUE!") else {
                    fun g(a: Long, b: Long): Long = if (b == 0L) a else g(b, a % b)
                    FormulaValue.NumberValue(n.reduce { a, b -> if (name == "GCD") g(a, b) else if (a == 0L || b == 0L) 0L else a / g(a, b) * b }.toDouble())
                }
            }
            "QUOTIENT" -> {
                val a = d(args, 0) ?: return FormulaValue.ErrorValue("#VALUE!")
                val b = d(args, 1) ?: return FormulaValue.ErrorValue("#VALUE!")
                if (b == 0.0) FormulaValue.ErrorValue("#DIV/0!") else FormulaValue.NumberValue(Math.floor(abs(a / b)) * Math.signum(a / b))
            }
            "ISEVEN" -> unaryBool(args) { Math.floor(abs(it)) % 2 == 0.0 }
            "ISODD" -> unaryBool(args) { Math.floor(abs(it)) % 2 == 1.0 }
            "XOR" -> FormulaValue.BoolValue(args.count { (it.asBoolean() as? FormulaValue.BoolValue)?.value == true } % 2 == 1)
            "LEFT" -> textArg(args, 0).let { t -> FormulaValue.TextValue(t.take((d(args, 1) ?: 1.0).toInt().coerceAtLeast(0))) }
            "RIGHT" -> textArg(args, 0).let { t -> FormulaValue.TextValue(t.takeLast((d(args, 1) ?: 1.0).toInt().coerceAtLeast(0))) }
            "MID" -> {
                val t = textArg(args, 0)
                val st = d(args, 1)?.toInt() ?: return FormulaValue.ErrorValue("#VALUE!")
                val len = d(args, 2)?.toInt() ?: return FormulaValue.ErrorValue("#VALUE!")
                if (st < 1 || len < 0) FormulaValue.ErrorValue("#VALUE!") else FormulaValue.TextValue(t.drop(st - 1).take(len))
            }
            "FIND", "SEARCH" -> {
                val needle = textArg(args, 0); val hay = textArg(args, 1)
                val st = ((d(args, 2) ?: 1.0).toInt() - 1).coerceAtLeast(0)
                val i = hay.indexOf(needle, st, ignoreCase = name == "SEARCH")
                if (i < 0) FormulaValue.ErrorValue("#VALUE!") else FormulaValue.NumberValue((i + 1).toDouble())
            }
            "SUBSTITUTE" -> {
                val t = textArg(args, 0); val old = textArg(args, 1); val rep = textArg(args, 2)
                val inst = d(args, 3)?.toInt()
                if (old.isEmpty()) FormulaValue.TextValue(t)
                else if (inst == null) FormulaValue.TextValue(t.replace(old, rep))
                else {
                    var idx = -1; var c = 0; var from = 0
                    while (true) { val i = t.indexOf(old, from); if (i < 0) break; c++; if (c == inst) { idx = i; break }; from = i + old.length }
                    FormulaValue.TextValue(if (idx < 0) t else t.substring(0, idx) + rep + t.substring(idx + old.length))
                }
            }
            "REPLACE" -> {
                val t = textArg(args, 0)
                val st = d(args, 1)?.toInt() ?: return FormulaValue.ErrorValue("#VALUE!")
                val len = d(args, 2)?.toInt() ?: return FormulaValue.ErrorValue("#VALUE!")
                if (st < 1 || len < 0) FormulaValue.ErrorValue("#VALUE!")
                else FormulaValue.TextValue(t.take(st - 1) + textArg(args, 3) + t.drop(st - 1 + len))
            }
            "REPT" -> FormulaValue.TextValue(textArg(args, 0).repeat((d(args, 1) ?: 0.0).toInt().coerceIn(0, 10000)))
            "PROPER" -> FormulaValue.TextValue(textArg(args, 0).lowercase().split(Regex("(?<=[^\\p{L}])|(?=[^\\p{L}])")).joinToString("") { it.replaceFirstChar { c -> c.uppercase() } })
            "EXACT" -> FormulaValue.BoolValue(textArg(args, 0) == textArg(args, 1))
            "VALUE" -> args.getOrNull(0)?.asNumber() ?: FormulaValue.ErrorValue("#VALUE!")
            "CLEAN" -> FormulaValue.TextValue(textArg(args, 0).filter { it.code >= 32 })
            "CHAR" -> d(args, 0)?.toInt()?.let { FormulaValue.TextValue(it.toChar().toString()) } ?: FormulaValue.ErrorValue("#VALUE!")
            "CODE" -> textArg(args, 0).firstOrNull()?.let { FormulaValue.NumberValue(it.code.toDouble()) } ?: FormulaValue.ErrorValue("#VALUE!")
            "TEXTJOIN" -> {
                val delim = textArg(args, 0)
                val skip = (args.getOrNull(1)?.asBoolean() as? FormulaValue.BoolValue)?.value ?: true
                FormulaValue.TextValue(args.drop(2).filter { !skip || it.asText().isNotEmpty() }.joinToString(delim) { it.asText() })
            }
            "TRUE" -> FormulaValue.BoolValue(true)
            "FALSE" -> FormulaValue.BoolValue(false)
            else -> FormulaValue.ErrorValue("#NAME?")
        }
    }

    private fun d(args: List<FormulaValue>, i: Int): Double? =
        (args.getOrNull(i)?.asNumber() as? FormulaValue.NumberValue)?.value

    private fun positiveMath(args: List<FormulaValue>, f: (Double) -> Double): FormulaValue {
        val n = numberArg(args, 0)
        if (n !is FormulaValue.NumberValue) return n
        return if (n.value <= 0) FormulaValue.ErrorValue("#NUM!") else FormulaValue.NumberValue(f(n.value))
    }

    private fun unaryBool(args: List<FormulaValue>, f: (Double) -> Boolean): FormulaValue {
        val n = numberArg(args, 0)
        return if (n !is FormulaValue.NumberValue) n else FormulaValue.BoolValue(f(n.value))
    }

    /** TEXT(value, "0.00" / "#,##0.00" / "0%" / "0.0%"); other codes fall back to plain text. */
    private fun textFormat(args: List<FormulaValue>): String {
        val v = args.getOrNull(0) ?: return ""
        val fmt = textArg(args, 1)
        val n = (v.asNumber() as? FormulaValue.NumberValue)?.value
        if (fmt.isEmpty() || n == null || !fmt.all { it in "0#,.%" }) return v.asText()
        val pct = fmt.endsWith("%")
        val dec = fmt.substringAfter('.', "").count { it == '0' || it == '#' }
        val x = if (pct) n * 100 else n
        val out = java.lang.String.format(java.util.Locale.US, if (fmt.contains(',')) "%,.${dec}f" else "%.${dec}f", x)
        return if (pct) "$out%" else out
    }

    private fun numbersOnly(args: List<FormulaValue>): List<Double> =
        args.filterIsInstance<FormulaValue.NumberValue>().map { it.value }

    private fun numberArg(args: List<FormulaValue>, index: Int): FormulaValue {
        val arg = args.getOrNull(index) ?: return FormulaValue.ErrorValue("#VALUE!")
        return arg.asNumber()
    }

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
