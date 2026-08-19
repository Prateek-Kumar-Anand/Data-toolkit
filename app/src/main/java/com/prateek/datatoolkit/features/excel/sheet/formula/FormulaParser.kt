package com.prateek.datatoolkit.features.excel.sheet.formula

import com.prateek.datatoolkit.features.excel.sheet.CellRange
import com.prateek.datatoolkit.features.excel.sheet.CellRef

sealed class FormulaNode {
    data class NumberLit(val value: Double) : FormulaNode()
    data class StringLit(val value: String) : FormulaNode()
    data class BoolLit(val value: Boolean) : FormulaNode()
    data class CellRefNode(val ref: CellRef) : FormulaNode()
    data class RangeNode(val range: CellRange) : FormulaNode()
    /** An identifier that wasn't a function call, a boolean literal, or a valid cell address -
     *  a typo'd function name or a named range this app doesn't support. Evaluates to #NAME?. */
    data class NameRef(val name: String) : FormulaNode()
    /** [op] is '-' (negation) or '%' (postfix percent, divides by 100). */
    data class UnaryOp(val op: Char, val operand: FormulaNode) : FormulaNode()
    /** [op] is one of + - * / ^ & = <> < <= > >=. */
    data class BinaryOp(val op: String, val left: FormulaNode, val right: FormulaNode) : FormulaNode()
    data class FunctionCall(val name: String, val args: List<FormulaNode>) : FormulaNode()
}

/**
 * Standard recursive-descent parser over Excel's operator precedence, highest to lowest:
 * `%` (postfix) > `^` (right-assoc) > unary `-` > `*` `/` > `+` `-` > `&` > comparisons.
 *
 * One deliberate wrinkle: unary minus sits *below* `^` here (power binds first), which matches
 * how Excel actually evaluates `-2^2` (= -4, i.e. `-(2^2)`) rather than what its own precedence
 * table would suggest (`(-2)^2` = 4) - a well-known real discrepancy between Excel's
 * documentation and its actual behavior. This follows the actual behavior, since that's what
 * someone porting a formula out of real Excel will expect. `^`'s right-hand side still allows
 * a unary minus directly (`2^-1` = 0.5), which is handled by parsePower calling back into
 * parseUnary for its exponent rather than straight into parsePercent.
 */
class FormulaParser(private val tokens: List<Token>) {
    private var pos = 0
    private fun peek(): Token = tokens[pos]
    private fun advance(): Token = tokens[pos++]

    fun parse(): FormulaNode {
        val node = parseComparison()
        if (peek() !is Token.End) throw FormulaSyntaxException("Unexpected trailing input in formula")
        return node
    }

    private fun parseComparison(): FormulaNode {
        var left = parseConcat()
        while (true) {
            val op = when (peek()) {
                Token.Eq -> "="; Token.Neq -> "<>"; Token.Lt -> "<"; Token.Lte -> "<="
                Token.Gt -> ">"; Token.Gte -> ">="
                else -> null
            } ?: break
            advance()
            left = FormulaNode.BinaryOp(op, left, parseConcat())
        }
        return left
    }

    private fun parseConcat(): FormulaNode {
        var left = parseAdditive()
        while (peek() == Token.Ampersand) {
            advance()
            left = FormulaNode.BinaryOp("&", left, parseAdditive())
        }
        return left
    }

    private fun parseAdditive(): FormulaNode {
        var left = parseMultiplicative()
        while (true) {
            val op = when (peek()) { Token.Plus -> "+"; Token.Minus -> "-"; else -> null } ?: break
            advance()
            left = FormulaNode.BinaryOp(op, left, parseMultiplicative())
        }
        return left
    }

    private fun parseMultiplicative(): FormulaNode {
        var left = parseUnary()
        while (true) {
            val op = when (peek()) { Token.Star -> "*"; Token.Slash -> "/"; else -> null } ?: break
            advance()
            left = FormulaNode.BinaryOp(op, left, parseUnary())
        }
        return left
    }

    private fun parseUnary(): FormulaNode {
        if (peek() == Token.Minus) { advance(); return FormulaNode.UnaryOp('-', parseUnary()) }
        if (peek() == Token.Plus) { advance(); return parseUnary() } // unary + is a no-op
        return parsePercent()
    }

    private fun parsePercent(): FormulaNode {
        var node = parsePower()
        while (peek() == Token.Percent) { advance(); node = FormulaNode.UnaryOp('%', node) }
        return node
    }

    private fun parsePower(): FormulaNode {
        val base = parsePrimary()
        if (peek() == Token.Caret) {
            advance()
            return FormulaNode.BinaryOp("^", base, parseUnary()) // recurses to parseUnary, not parsePower, so "2^-1" parses
        }
        return base
    }

    private fun parsePrimary(): FormulaNode = when (val t = peek()) {
        is Token.Number -> { advance(); FormulaNode.NumberLit(t.value) }
        is Token.Str -> { advance(); FormulaNode.StringLit(t.value) }
        Token.LParen -> {
            advance()
            val inner = parseComparison()
            if (advance() != Token.RParen) throw FormulaSyntaxException("Expected ')'")
            inner
        }
        is Token.Ident -> parseIdentOrCallOrRange(t)
        else -> throw FormulaSyntaxException("Unexpected token in formula")
    }

    private fun parseIdentOrCallOrRange(t: Token.Ident): FormulaNode {
        advance()
        if (peek() == Token.LParen) return parseFunctionCall(t.text.uppercase())

        when (t.text.uppercase()) {
            "TRUE" -> return FormulaNode.BoolLit(true)
            "FALSE" -> return FormulaNode.BoolLit(false)
        }

        val ref = CellRef.parse(t.text) ?: return FormulaNode.NameRef(t.text)
        if (peek() == Token.Colon) {
            advance()
            val endToken = advance()
            if (endToken !is Token.Ident) throw FormulaSyntaxException("Expected a cell reference after ':'")
            val endRef = CellRef.parse(endToken.text) ?: throw FormulaSyntaxException("Invalid range end '${endToken.text}'")
            return FormulaNode.RangeNode(CellRange(ref, endRef))
        }
        return FormulaNode.CellRefNode(ref)
    }

    private fun parseFunctionCall(name: String): FormulaNode {
        advance() // consume '('
        val args = mutableListOf<FormulaNode>()
        if (peek() != Token.RParen) {
            args.add(parseComparison())
            while (peek() == Token.Comma) { advance(); args.add(parseComparison()) }
        }
        if (advance() != Token.RParen) throw FormulaSyntaxException("Expected ')' to close $name(")
        return FormulaNode.FunctionCall(name, args)
    }
}
