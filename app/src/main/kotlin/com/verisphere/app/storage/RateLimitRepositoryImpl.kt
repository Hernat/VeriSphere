package com.verisphere.app.storage

import com.verisphere.app.BuildConfig
import com.verisphere.app.storage.RateLimitRepository.Companion.MAX_DAILY_CAPTURES
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate

/**
 * Default implementation of [RateLimitRepository] backed by `SecureStorage` (AR14, D3.7).
 *
 * Algorithm (executed under [mutex] for the read-then-write atomicity demanded by
 * architecture line 316 — concurrent `consume()` callers must not race past the
 * limit by both reading `count = 29` and both writing `count = 30`):
 *
 * 1. If the debug bypass flag is set, return `true` without touching storage.
 * 2. Read the persisted UTC date and capture count.
 * 3. If the persisted date is not today, treat the count as zero (UTC-midnight
 *    rollover; see also the [Long.MIN_VALUE] sentinel below — the "no key
 *    persisted yet" branch and the "different day" branch collapse into one).
 * 4. If the count is at or above [MAX_DAILY_CAPTURES], return `false`.
 * 5. Increment, persist, stamp today's date if it changed, and return `true`.
 *
 * Storage is plumbed through two member-reference lambdas instead of a full
 * `SecureStorage` instance. This keeps the unit tests at
 * `app/src/test/.../storage/RateLimitRepositoryTest.kt` free of `Context`,
 * Keystore, and Robolectric (out-of-scope per Story 1.4 Task 5.1) — the test
 * passes in-memory closures backed by a `MutableMap`. Production wiring lives
 * at [com.verisphere.app.AppContainer.rateLimitRepository], where the lambdas
 * resolve to `secureStorage::readLong` and `secureStorage::writeLong`.
 *
 * Time is obtained via the constructor-injected [Clock]. Production uses
 * [Clock.systemUTC]; tests use [Clock.fixed] to validate first-launch and
 * UTC-midnight-rollover behaviours deterministically.
 *
 * The [KEY_DATE] absent-state sentinel is [Long.MIN_VALUE] — no real-world
 * `LocalDate.toEpochDay()` reaches that value, so the algorithm uses one
 * branch (`storedDate != today`) instead of two.
 *
 * @property readLong Capability: read a Long from persistent storage with a default.
 * @property writeLong Capability: persist a Long to storage by key.
 * @property clock Time source. Default is [Clock.systemUTC]; tests inject [Clock.fixed].
 */
class RateLimitRepositoryImpl(
    private val readLong: (String, Long) -> Long,
    private val writeLong: (String, Long) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) : RateLimitRepository {

    private val mutex = Mutex()

    override suspend fun consume(): Boolean = consumeInternal(
        skipRateLimit = BuildConfig.DEBUG && BuildConfig.SKIP_RATE_LIMIT,
    )

    /**
     * Test seam for the debug bypass branch. Production callers reach
     * [consume] which derives `skipRateLimit` from `BuildConfig`; tests call
     * this directly with the desired flag value to exercise the bypass path
     * without flipping a compile-time constant.
     */
    internal suspend fun consumeInternal(skipRateLimit: Boolean): Boolean {
        if (skipRateLimit) return true
        return mutex.withLock {
            val today = LocalDate.now(clock).toEpochDay()
            val storedDate = readLong(KEY_DATE, Long.MIN_VALUE)
            val currentCount = if (storedDate == today) readLong(KEY_COUNT, 0L) else 0L
            if (currentCount >= MAX_DAILY_CAPTURES.toLong()) {
                return@withLock false
            }
            writeLong(KEY_COUNT, currentCount + 1L)
            if (storedDate != today) {
                writeLong(KEY_DATE, today)
            }
            true
        }
    }

    private companion object {
        const val KEY_COUNT: String = "rate_limit_count_today"
        const val KEY_DATE: String = "rate_limit_date"
    }
}
