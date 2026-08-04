package com.prateek.datatoolkit.features.datacleaning

enum class BooleanFormat { YES_NO, TRUE_FALSE, ONE_ZERO }

/** Normalizes recognized boolean-ish tokens (yes/no, y/n, true/false, t/f, 1/0) into one
 * consistent output format. Returns null for anything not clearly one of these, so an
 * unrelated value is flagged instead of silently mangled. */
object BooleanNormalizer {

    private val TRUE_TOKENS = setOf("yes", "y", "true", "t", "1")
    private val FALSE_TOKENS = setOf("no", "n", "false", "f", "0")

    fun normalize(raw: String, format: BooleanFormat): String? {
        val v = raw.trim().lowercase()
        val isTrue = v in TRUE_TOKENS
        val isFalse = v in FALSE_TOKENS
        if (!isTrue && !isFalse) return null
        return when (format) {
            BooleanFormat.YES_NO -> if (isTrue) "Yes" else "No"
            BooleanFormat.TRUE_FALSE -> if (isTrue) "True" else "False"
            BooleanFormat.ONE_ZERO -> if (isTrue) "1" else "0"
        }
    }
}
