package com.prateek.datatoolkit.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow

/**
 * Auto-Retry Logic: wraps any suspend operation (a web request, a file read
 * that might hit a transient IO error, etc.) with exponential backoff.
 *
 * Used by WebScraping, EmailExtraction (when the source is a URL), and the
 * BatchWorker so a single flaky network call doesn't fail a whole batch.
 */
object RetryPolicy {

    data class Result<T>(val value: T?, val attempts: Int, val lastError: Throwable?)

    suspend fun <T> withRetry(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        maxDelayMs: Long = 8000,
        factor: Double = 2.0,
        shouldRetry: (Throwable) -> Boolean = { true },
        block: suspend (attempt: Int) -> T
    ): Result<T> {
        var attempt = 0
        var lastError: Throwable? = null
        var delayMs = initialDelayMs

        while (attempt < maxAttempts) {
            attempt++
            try {
                val value = block(attempt)
                return Result(value, attempt, null)
            } catch (t: Throwable) {
                // A cancelled coroutine (e.g. the screen that started this was navigated away
                // from) must propagate immediately, not be treated as a retryable failure -
                // catching it here like any other error would mean attempting another retry
                // (and delay()) after the caller has already stopped caring, and would surface
                // as a plain failed Result instead of the cancellation the caller's own
                // lifecycleScope is expecting to see.
                if (t is CancellationException) throw t
                lastError = t
                if (attempt >= maxAttempts || !shouldRetry(t)) {
                    return Result(null, attempt, t)
                }
                delay(delayMs)
                delayMs = min((delayMs * factor).toLong(), maxDelayMs)
            }
        }
        return Result(null, attempt, lastError)
    }

    /** Simple exponential backoff calculation, exposed for WorkManager's setBackoffCriteria. */
    fun backoffDelayMs(attempt: Int, baseMs: Long = 1000, capMs: Long = 30000): Long =
        min(capMs, (baseMs * 2.0.pow(attempt.toDouble())).toLong())
}
