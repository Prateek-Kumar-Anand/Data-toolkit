package com.prateek.datatoolkit.features.excel.sheet.formula

/** Thrown for any formula text [FormulaLexer]/[FormulaParser] can't make sense of - caught at
 *  the top of [SheetRecalculator]'s per-cell evaluation and turned into a #NAME? in the cell,
 *  the same way Excel shows an error rather than crashing on a malformed formula. */
class FormulaSyntaxException(message: String) : Exception(message)

sealed class Token {
    data class Number(val value: Double) : Token()
    data class Str(val value: String) : Token()
    /** Covers everything letters-first: a cell ref (A1), a function name (SUM), a boolean
     *  literal (TRUE/FALSE), or an unrecognized name - [FormulaParser] is what tells these
     *  apart, based on what follows. */
    data class Ident(val text: String) : Token()
    object Comma : Token()
    object LParen : Token()
    object RParen : Token()
    object Colon : Token()
    object Plus : Token()
    object Minus : Token()
    object Star : Token()
    object Slash : Token()
    object Caret : Token()
    object Percent : Token()
    object Ampersand : Token()
    object Eq : Token()
    object Neq : Token()
    object Lt : Token()
    object Lte : Token()
    object Gt : Token()
    object Gte : Token()
    object End : Token()
}

/** Turns a formula's expression text (the part after the leading '=', already stripped by the
 *  caller) into a flat token stream for [FormulaParser]. Hand-rolled rather than a regex pass:
 *  a regex can't cleanly handle "" as an escaped quote inside a string literal, and doing this
 *  char-by-char keeps error messages pointing at a specific spot in the formula. */
class FormulaLexer(private val text: String) {
    private var pos = 0

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (true) {
            skipWhitespace()
            if (pos >= text.length) { tokens.add(Token.End); break }
            val c = text[pos]
            tokens.add(
                when {
                    c.isDigit() || (c == '.' && pos + 1 < text.length && text[pos + 1].isDigit()) -> readNumber()
                    c == '"' -> readString()
                    c.isLetter() || c == '_' || c == '$' -> readIdent()
                    c == '(' -> { pos++; Token.LParen }
                    c == ')' -> { pos++; Token.RParen }
                    c == ',' -> { pos++; Token.Comma }
                    c == ':' -> { pos++; Token.Colon }
                    c == '+' -> { pos++; Token.Plus }
                    c == '-' -> { pos++; Token.Minus }
                    c == '*' -> { pos++; Token.Star }
                    c == '/' -> { pos++; Token.Slash }
                    c == '^' -> { pos++; Token.Caret }
                    c == '%' -> { pos++; Token.Percent }
                    c == '&' -> { pos++; Token.Ampersand }
                    c == '=' -> { pos++; Token.Eq }
                    c == '<' -> readLessThanFamily()
                    c == '>' -> readGreaterThanFamily()
                    else -> throw FormulaSyntaxException("Unexpected character '$c' at position $pos")
                }
            )
        }
        return tokens
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun readLessThanFamily(): Token {
        pos++
        return when {
            pos < text.length && text[pos] == '>' -> { pos++; Token.Neq }
            pos < text.length && text[pos] == '=' -> { pos++; Token.Lte }
            else -> Token.Lt
        }
    }

    private fun readGreaterThanFamily(): Token {
        pos++
        return if (pos < text.length && text[pos] == '=') { pos++; Token.Gte } else Token.Gt
    }

    private fun readNumber(): Token.Number {
        val start = pos
        while (pos < text.length && text[pos].isDigit()) pos++
        if (pos < text.length && text[pos] == '.') {
            pos++
            while (pos < text.length && text[pos].isDigit()) pos++
        }
        // Scientific notation (1.5E10) - rare in hand-typed formulas but cheap to support, and
        // backs out cleanly (leaving the 'E' for the next token) if it turns out not to be one.
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            val save = pos
            pos++
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
            if (pos < text.length && text[pos].isDigit()) {
                while (pos < text.length && text[pos].isDigit()) pos++
            } else {
                pos = save
            }
        }
        return Token.Number(text.substring(start, pos).toDouble())
    }

    private fun readString(): Token.Str {
        pos++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) throw FormulaSyntaxException("Unterminated string literal")
            val c = text[pos]
            if (c == '"') {
                if (pos + 1 < text.length && text[pos + 1] == '"') {
                    sb.append('"') // "" inside a string is a literal quote, Excel's own escaping rule
                    pos += 2
                } else {
                    pos++
                    break
                }
            } else {
                sb.append(c)
                pos++
            }
        }
        return Token.Str(sb.toString())
    }

    private fun readIdent(): Token.Ident {
        val start = pos
        if (text[pos] == '$') pos++ // leading column anchor, e.g. $A1 - kept in the captured
                                     // text so CellRef.parse (which also accepts '$') still matches it
        while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_' || text[pos] == '$')) pos++
        return Token.Ident(text.substring(start, pos))
    }
}
