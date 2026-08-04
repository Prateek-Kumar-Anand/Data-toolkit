package com.prateek.datatoolkit.features.datacleaning

/** How to resolve a numeric date like "05-06-2024" where both components are <=12 and the
 * order is genuinely ambiguous. AUTO behaves like DAY_FIRST (the common non-US convention) -
 * whenever one component is unambiguously >12, the real day/month order is used regardless
 * of this hint. */
enum class DateOrderHint { AUTO, DAY_FIRST, MONTH_FIRST }

/** Parses a broad range of common date spellings and standardizes them to YYYY-MM-DD.
 * Returns null - never a best-guess mangled value - when a date can't be confidently parsed,
 * so callers can leave the original untouched rather than risk corrupting it. */
object DateNormalizer {

    private val MONTH_NAMES = mapOf(
        "jan" to 1, "january" to 1, "feb" to 2, "february" to 2, "mar" to 3, "march" to 3,
        "apr" to 4, "april" to 4, "may" to 5, "jun" to 6, "june" to 6, "jul" to 7, "july" to 7,
        "aug" to 8, "august" to 8, "sep" to 9, "sept" to 9, "september" to 9,
        "oct" to 10, "october" to 10, "nov" to 11, "november" to 11, "dec" to 12, "december" to 12
    )

    private val ISO_REGEX = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$")
    private val NUMERIC_REGEX = Regex("^(\\d{1,4})[/.\\-](\\d{1,2})[/.\\-](\\d{1,4})$")
    private val DAY_MONTHNAME_YEAR_REGEX = Regex("^(\\d{1,2})[\\s-]+([A-Za-z]{3,9})[\\s,-]+(\\d{2,4})$")
    private val MONTHNAME_DAY_YEAR_REGEX = Regex("^([A-Za-z]{3,9})[\\s,]+(\\d{1,2}),?\\s+(\\d{2,4})$")

    fun normalize(raw: String, hint: DateOrderHint = DateOrderHint.AUTO): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null

        ISO_REGEX.matchEntire(value)?.let { m ->
            val (y, mo, d) = m.destructured
            return buildIfValid(y.toInt(), mo.toInt(), d.toInt())
        }

        DAY_MONTHNAME_YEAR_REGEX.matchEntire(value)?.let { m ->
            val (d, monthName, y) = m.destructured
            val mo = MONTH_NAMES[monthName.lowercase()] ?: return@let
            return buildIfValid(normalizeYear(y), mo, d.toInt())
        }

        MONTHNAME_DAY_YEAR_REGEX.matchEntire(value)?.let { m ->
            val (monthName, d, y) = m.destructured
            val mo = MONTH_NAMES[monthName.lowercase()] ?: return@let
            return buildIfValid(normalizeYear(y), mo, d.toInt())
        }

        NUMERIC_REGEX.matchEntire(value)?.let { m ->
            val (a, b, c) = m.destructured
            val an = a.toInt(); val bn = b.toInt()
            if (a.length == 4) return buildIfValid(an, bn, c.toInt()) // YYYY-M-D
            val year = normalizeYear(c)
            return when {
                an > 12 && bn <= 12 -> buildIfValid(year, bn, an) // only "a" can be the day
                bn > 12 && an <= 12 -> buildIfValid(year, an, bn) // only "b" can be the day
                hint == DateOrderHint.MONTH_FIRST -> buildIfValid(year, an, bn)
                else -> buildIfValid(year, bn, an) // AUTO / DAY_FIRST
            }
        }

        return null
    }

    private fun normalizeYear(y: String): Int {
        val n = y.toInt()
        if (y.length > 2) return n
        return if (n <= 30) 2000 + n else 1900 + n
    }

    private fun buildIfValid(year: Int, month: Int, day: Int): String? {
        if (year < 1000 || year > 9999) return null
        if (month !in 1..12) return null
        if (day !in 1..daysInMonth(year, month)) return null
        return "%04d-%02d-%02d".format(year, month, day)
    }

    private fun daysInMonth(year: Int, month: Int): Int {
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        return when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (leap) 29 else 28
            else -> 31
        }
    }
}
