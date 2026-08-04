package com.prateek.datatoolkit.features.datacleaning

/** Levenshtein-based string similarity, used to catch near-duplicate rows that differ by a
 * typo or a stray character rather than being byte-for-byte or normalized-equal. */
object StringSimilarity {

    /** Similarity in [0.0, 1.0], where 1.0 means identical. */
    fun ratio(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prevDiagonal = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prevDiagonal else 1 + minOf(prevDiagonal, dp[j], dp[j - 1])
                prevDiagonal = temp
            }
        }
        return dp[b.length]
    }
}
