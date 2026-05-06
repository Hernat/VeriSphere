package com.verisphere.app.storage

/**
 * Per-device daily rate limit (architecture: AR14, D3.7; PRD: FR10, NFR9).
 *
 * Why: the bundled Gemini API key (NFR9) is treated as known-extractable.
 * Mitigation = a per-device daily cap that survives process death and
 * resets at UTC midnight.
 *
 * The contract is intentionally minimal — `consume()` is the only method.
 * Callers do not need to know the count, the limit, or the date; they
 * only need a yes/no decision. This single-decision-point posture is
 * what keeps the V2 migration to a different storage backend mechanical.
 *
 * Threading: implementations MUST be safe to call from concurrent
 * coroutines (the bubble pipeline runs on `Dispatchers.IO`).
 */
interface RateLimitRepository {
    /**
     * Attempt to consume one quota slot for today (UTC).
     *
     * @return `true` if a slot was consumed (caller may proceed with the
     *   capture / API call); `false` if today's limit is exhausted.
     */
    suspend fun consume(): Boolean

    companion object {
        /** Captures permitted per device per UTC day (D3.7). */
        const val MAX_DAILY_CAPTURES: Int = 30
    }
}
