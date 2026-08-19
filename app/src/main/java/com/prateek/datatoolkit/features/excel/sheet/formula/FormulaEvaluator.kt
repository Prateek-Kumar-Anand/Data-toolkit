package com.prateek.datatoolkit.features.excel.sheet.formula

import com.prateek.datatoolkit.features.excel.sheet.CellRef

/**
 * Walks a parsed formula's AST down to a single [FormulaValue], resolving cell/range
 * references through [lookup] (backed by [SheetRecalculator] during real use, which is what
 * makes sure a referenced formula cell is already up to date before this reads it) and
 * dispatching plain function calls to [FormulaFunctions].
 *
 * IF/IFERROR/AND/OR are handled directly here rather than in the function library because they
 * need *lazy* access to their unevaluated argument nodes: `=IF(A1<>0, 1/A1, 0)` must not
 * evaluate `1/A1` at all when A1 is 0, and `=IFERROR(1/A1, 0)` needs to catch the #DIV/0! from
 * evaluating its own first argument rather than receiving it pre-evaluated with no way back.
 */
class FormulaEvaluator(private val lookup: (CellRef) -> FormulaValue) {

    fun evaluate(node: FormulaNode): FormulaValue = when (node) {
        is FormulaNode.NumberLit -> FormulaValue.NumberValue(node.value)
        is FormulaNode.StringLit -> FormulaValue.TextValue(node.value)
        is FormulaNode.BoolLit -> FormulaValue.BoolValue(node.value)
        is FormulaNode.CellRefNode -> lookup(node.ref)
        // A bare range isn't a value on its own in Excel either (=A1:A5 alone in a cell is an
        // error) - it's only meaningful as a function argument, where evaluateCall flattens it
        // before this generic evaluate() ever sees the RangeNode.
        is FormulaNode.RangeNode -> FormulaValue.ErrorValue("#VALUE!")
        is FormulaNode.NameRef -> FormulaValue.ErrorValue("#NAME?")
        is FormulaNode.UnaryOp -> evaluateUnary(node)
        is FormulaNode.BinaryOp -> evaluateBinary(node)
        is FormulaNode.FunctionCall -> evaluateCall(node)
    }

    private fun evaluateUnary(node: FormulaNode.UnaryOp): FormulaValue {
        val operand = evaluate(node.operand)
        if (operand.isError) return operand
        val num = operand.asNumber()
        if (num !is FormulaValue.NumberValue) return num
        return when (node.op) {
            '-' -> FormulaValue.NumberValue(-num.value)
            '%' -> FormulaValue.NumberValue(num.value / 100.0)
            else -> FormulaValue.ErrorValue("#VALUE!")
        }
    }

    private fun evaluateBinary(node: FormulaNode.BinaryOp): FormulaValue {
        if (node.op == "&") {
            val l = evaluate(node.left); if (l.isError) return l
            val r = evaluate(node.right); if (r.isError) return r
            return FormulaValue.TextValue(l.asText() + r.asText())
        }
        if (node.op in COMPARISON_OPS) return evaluateComparison(node)

        val l = evaluate(node.left); if (l.isError) return l
        val r = evaluate(node.right); if (r.isError) return r
        val ln = l.asNumber(); if (ln !is FormulaValue.NumberValue) return ln
        val rn = r.asNumber(); if (rn !is FormulaValue.NumberValue) return rn
        return when (node.op) {
            "+" -> FormulaValue.NumberValue(ln.value + rn.value)
            "-" -> FormulaValue.NumberValue(ln.value - rn.value)
            "*" -> FormulaValue.NumberValue(ln.value * rn.value)
            "/" -> if (rn.value == 0.0) FormulaValue.ErrorValue("#DIV/0!") else FormulaValue.NumberValue(ln.value / rn.value)
            "^" -> FormulaValue.NumberValue(Math.pow(ln.value, rn.value))
            else -> FormulaValue.ErrorValue("#VALUE!")
        }
    }

    private fun evaluateComparison(node: FormulaNode.BinaryOp): FormulaValue {
        val l = evaluate(node.left); if (l.isError) return l
        val r = evaluate(node.right); if (r.isError) return r
        // Two numbers compare numerically; anything else - including a number-vs-text
        // comparison, which Excel defines as "any text is greater than any number" - compares
        // as text, which happens to give the right answer for that case too since it's a
        // lexical rather than semantic comparison at that point.
        val cmp = if (l is FormulaValue.NumberValue && r is FormulaValue.NumberValue) {
            l.value.compareTo(r.value)
        } else {
            l.asText().compareTo(r.asText(), ignoreCase = true)
        }
        val result = when (node.op) {
            "=" -> cmp == 0
            "<>" -> cmp != 0
            "<" -> cmp < 0
            "<=" -> cmp <= 0
            ">" -> cmp > 0
            ">=" -> cmp >= 0
            else -> false
        }
        return FormulaValue.BoolValue(result)
    }

    private fun evaluateCall(node: FormulaNode.FunctionCall): FormulaValue = when (node.name) {
        "IF" -> evaluateIf(node.args)
        "IFERROR" -> evaluateIfError(node.args)
        "AND" -> evaluateAndOr(node.args, isAnd = true)
        "OR" -> evaluateAndOr(node.args, isAnd = false)
        else -> {
            val flatArgs = mutableListOf<FormulaValue>()
            for (arg in node.args) {
                if (arg is FormulaNode.RangeNode) {
                    arg.range.cells().forEach { flatArgs.add(lookup(it)) }
                } else {
                    flatArgs.add(evaluate(arg))
                }
            }
            FormulaFunctions.call(node.name, flatArgs)
        }
    }

    private fun evaluateIf(args: List<FormulaNode>): FormulaValue {
        if (args.size !in 2..3) return FormulaValue.ErrorValue("#VALUE!")
        val cond = evaluate(args[0]); if (cond.isError) return cond
        val boolCond = cond.asBoolean(); if (boolCond !is FormulaValue.BoolValue) return boolCond
        return when {
            boolCond.value -> evaluate(args[1])
            args.size == 3 -> evaluate(args[2])
            else -> FormulaValue.BoolValue(false)
        }
    }

    private fun evaluateIfError(args: List<FormulaNode>): FormulaValue {
        if (args.size != 2) return FormulaValue.ErrorValue("#VALUE!")
        val primary = evaluate(args[0])
        return if (primary.isError) evaluate(args[1]) else primary
    }

    private fun evaluateAndOr(args: List<FormulaNode>, isAnd: Boolean): FormulaValue {
        if (args.isEmpty()) return FormulaValue.ErrorValue("#VALUE!")
        for (arg in args) {
            val values = if (arg is FormulaNode.RangeNode) arg.range.cells().map { lookup(it) } else listOf(evaluate(arg))
            for (v in values) {
                if (v.isError) return v
                if (v is FormulaValue.EmptyValue) continue // AND/OR skip blank cells in a range, same as Excel
                val b = v.asBoolean()
                if (b !is FormulaValue.BoolValue) return b
                if (isAnd && !b.value) return FormulaValue.BoolValue(false)
                if (!isAnd && b.value) return FormulaValue.BoolValue(true)
            }
        }
        return FormulaValue.BoolValue(isAnd)
    }

    companion object {
        private val COMPARISON_OPS = setOf("=", "<>", "<", "<=", ">", ">=")
    }
}
