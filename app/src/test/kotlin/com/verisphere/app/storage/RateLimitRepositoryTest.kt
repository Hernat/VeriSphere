package com.verisphere.app.storage

import com.verisphere.app.storage.RateLimitRepository.Companion.MAX_DAILY_CAPTURES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM unit tests for [RateLimitRepositoryImpl].
 *
 * Architecture-line-426 naming: backtick-quoted English-sentence method names.
 * These are JVM tests (`src/test/`, not `src/androidTest/`) so the DEX-< 040
 * SimpleName constraint that bit Story 1.4 does NOT apply — spaces are fine.
 *
 * Storage is a [FakeStore] — a `ConcurrentHashMap<String, Long>` whose
 * `readLong` / `writeLong` member references are passed to the impl in lieu
 * of a real `SecureStorage`. This keeps these tests free of `Context`,
 * `EncryptedSharedPreferences`, and Android Keystore — no Robolectric.
 * `ConcurrentHashMap` is required for the concurrency test which dispatches
 * coroutines on `Dispatchers.Default` (a real thread pool).
 */
class RateLimitRepositoryTest {

    private class FakeStore {
        private val map = ConcurrentHashMap<String, Long>()
        fun readLong(key: String, default: Long): Long = map[key] ?: default
        fun writeLong(key: String, value: Long) {
            map[key] = value
        }

        fun snapshot(): Map<String, Long> = map.toMap()
        fun get(key: String): Long? = map[key]
    }

    private fun fixedClock(iso: String): Clock =
        Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

    private fun newImpl(store: FakeStore, clock: Clock) = RateLimitRepositoryImpl(
        readLong = store::readLong,
        writeLong = store::writeLong,
        clock = clock,
    )

    @Test
    fun `first call of the day starts from zero and persists today's date`() = runBlocking {
        val store = FakeStore()
        val clock = fixedClock("2026-05-06T10:00:00Z")
        val impl = newImpl(store, clock)

        val accepted = impl.consume()

        assertTrue("first call of the day must be accepted", accepted)
        assertEquals(1L, store.get("rate_limit_count_today"))
        assertEquals(
            LocalDate.parse("2026-05-06").toEpochDay(),
            store.get("rate_limit_date"),
        )
    }

    @Test
    fun `30 successful consumes are followed by a 31st rejection`() = runBlocking {
        val store = FakeStore()
        val clock = fixedClock("2026-05-06T10:00:00Z")
        val impl = newImpl(store, clock)

        repeat(MAX_DAILY_CAPTURES) { i ->
            assertTrue("call ${i + 1} must be accepted", impl.consume())
        }

        assertFalse("the 31st call must be rejected", impl.consume())
        assertEquals(
            "the persisted count must clamp at the daily cap",
            MAX_DAILY_CAPTURES.toLong(),
            store.get("rate_limit_count_today"),
        )
    }

    @Test
    fun `consume after UTC midnight rollover resets the counter to one`() = runBlocking {
        val store = FakeStore()
        val yesterday = LocalDate.parse("2026-05-06").toEpochDay()
        store.writeLong("rate_limit_count_today", MAX_DAILY_CAPTURES.toLong())
        store.writeLong("rate_limit_date", yesterday)

        val clock = fixedClock("2026-05-07T00:00:00Z")
        val impl = newImpl(store, clock)

        val accepted = impl.consume()

        assertTrue("first call after rollover must be accepted", accepted)
        assertEquals(1L, store.get("rate_limit_count_today"))
        assertEquals(
            LocalDate.parse("2026-05-07").toEpochDay(),
            store.get("rate_limit_date"),
        )
    }

    @Test
    fun `debug bypass returns true and does not touch storage`() = runBlocking {
        val store = FakeStore()
        val clock = fixedClock("2026-05-06T10:00:00Z")
        val impl = newImpl(store, clock)

        val accepted = impl.consumeInternal(skipRateLimit = true)

        assertTrue("bypass must always return true", accepted)
        assertTrue(
            "bypass must not write any state, but store had ${store.snapshot()}",
            store.snapshot().isEmpty(),
        )
        assertNull(store.get("rate_limit_count_today"))
        assertNull(store.get("rate_limit_date"))
    }

    @Test
    fun `consume reads back the persisted counter on a fresh impl instance for the same UTC day`() = runBlocking {
        val store = FakeStore()
        val clock = fixedClock("2026-05-06T10:00:00Z")

        val implA = newImpl(store, clock)
        repeat(5) { assertTrue(implA.consume()) }
        assertEquals(5L, store.get("rate_limit_count_today"))

        val implB = newImpl(store, clock)
        val accepted = implB.consume()

        assertTrue("fresh impl must continue, not reset", accepted)
        assertEquals(
            "fresh impl must increment the persisted count to 6",
            6L,
            store.get("rate_limit_count_today"),
        )
    }

    @Test
    fun `consume with stale count but absent date resets the counter to one`() = runBlocking {
        val store = FakeStore()
        // Simulate a crash that wrote KEY_COUNT but not KEY_DATE (e.g. process killed between
        // the two writeLong calls during a day-rollover). KEY_DATE absent → Long.MIN_VALUE
        // default → storedDate != today → algorithm must reset count to 0 and accept.
        store.writeLong("rate_limit_count_today", 25L)

        val clock = fixedClock("2026-05-06T10:00:00Z")
        val impl = newImpl(store, clock)

        val accepted = impl.consume()

        assertTrue("call with stale count and absent date must be accepted", accepted)
        assertEquals(
            "counter must reset to 1, not increment from the stale value",
            1L,
            store.get("rate_limit_count_today"),
        )
        assertEquals(
            "today's date must be stamped after the reset",
            LocalDate.parse("2026-05-06").toEpochDay(),
            store.get("rate_limit_date"),
        )
    }

    @Test
    fun `concurrent consume calls cannot exceed the daily limit`() = runBlocking {
        val store = FakeStore()
        val clock = fixedClock("2026-05-06T10:00:00Z")
        val impl = newImpl(store, clock)

        val attempts = 50
        val results: List<Boolean> = coroutineScope {
            (1..attempts)
                .map { async(Dispatchers.Default) { impl.consume() } }
                .awaitAll()
        }

        val accepted = results.count { it }
        val rejected = results.count { !it }
        assertEquals(
            "exactly $MAX_DAILY_CAPTURES calls must be accepted under contention",
            MAX_DAILY_CAPTURES,
            accepted,
        )
        assertEquals(
            "the remaining ${attempts - MAX_DAILY_CAPTURES} calls must be rejected",
            attempts - MAX_DAILY_CAPTURES,
            rejected,
        )
        assertEquals(
            "the persisted count must equal the daily cap exactly",
            MAX_DAILY_CAPTURES.toLong(),
            store.get("rate_limit_count_today"),
        )
    }
}
