package com.prateek.datatoolkit.features.datacleaning

/** Normalizes phone numbers to a plain 10-digit string, stripping punctuation and a
 * recognized leading country code (+1 US/Canada, +91 India). Never guesses when the
 * digit count doesn't unambiguously resolve to 10 - returns null instead of corrupting it. */
object PhoneNormalizer {

    fun normalize(value: String): String? {
        val digits = value.filter { it.isDigit() }
        return when (digits.length) {
            10 -> digits
            11 -> if (digits.startsWith("1")) digits.substring(1) else null   // +1 US/Canada
            12 -> if (digits.startsWith("91")) digits.substring(2) else null  // +91 India
            13 -> if (digits.startsWith("091")) digits.substring(3) else null // 0-prefixed +91
            else -> null
        }
    }
}
