package com.prateek.datatoolkit.core.security

/**
 * Neutralizes spreadsheet formula injection ("CSV injection") in exported CSV: a cell that
 * starts with = + - @ (or tab/CR) and isn't just a number/phone number is prefixed with an
 * apostrophe, so Excel/Sheets show it as text instead of executing it.
 */
object CsvSafety {
    private val TRIGGERS = charArrayOf('=', '+', '-', '@', '\t', '\r')
    private val NUMBER = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")
    private val PHONE = Regex("^\\+?[\\d\\s().-]{5,}$")

    fun sanitizeCell(value: String): String {
        if (value.length <= 1) return value
        if (value[0] !in TRIGGERS) return value
        val t = value.trim()
        if (NUMBER.matches(t) || PHONE.matches(t)) return value
        return "'$value"
    }

    /** True if [formula] (without the leading '=') uses a DDE/command-launching or remote-fetch pattern. */
    fun isDangerousFormula(formula: String): Boolean {
        val f = formula.trim()
        if (f.contains('|') && f.contains('!')) return true // DDE: cmd|' /c calc'!A0
        return Regex("(?i)\\b(WEBSERVICE|FILTERXML|RTD|CALL|REGISTER|EXEC)\\s*\\(").containsMatchIn(f)
    }
}
