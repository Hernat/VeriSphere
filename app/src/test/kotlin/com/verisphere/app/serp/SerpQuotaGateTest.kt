package com.verisphere.app.serp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Epic 9 Story 9.1 — quota cooldown gate.
 *
 * Tests use an injected clock to control time deterministically.
 * Production code reads `System.currentTimeMillis()` — but the gate's
 * constructor accepts a `() -> Long` lambda so tests can advance time
 * without sleeping.
 */
class SerpQuotaGateTest {

    private class FakeClock(initial: Long = 0L) {
        var nowMs: Long = initial
        val provider: () -> Long = { nowMs }
    }

    @Test
    fun `fresh gate does not skip`() {
        val gate = SerpQuotaGate(nowMillisProvider = FakeClock().provider)
        assertFalse(gate.shouldSkipSerp())
    }

    @Test
    fun `after markQuotaExceeded the gate skips for the cooldown window`() {
        val clock = FakeClock(initial = 1_000_000L)
        val gate = SerpQuotaGate(nowMillisProvider = clock.provider)

        gate.markQuotaExceeded()
        assertTrue("immediately after markQuotaExceeded", gate.shouldSkipSerp())

        // 1 second later — still inside the 15 min cooldown.
        clock.nowMs += 1_000L
        assertTrue("1s after", gate.shouldSkipSerp())

        // ~14 minutes 58 seconds total elapsed — still inside the 15 min window.
        clock.nowMs += (14 * 60L + 57L) * 1_000L
        assertTrue("14m58s after", gate.shouldSkipSerp())
    }

    @Test
    fun `gate releases after the cooldown window expires`() {
        val clock = FakeClock(initial = 1_000_000L)
        val gate = SerpQuotaGate(nowMillisProvider = clock.provider)

        gate.markQuotaExceeded()
        // Just past 15 minutes — cooldown expired.
        clock.nowMs += 15L * 60L * 1_000L + 1L
        assertFalse(gate.shouldSkipSerp())
    }

    @Test
    fun `resetQuotaGate clears the cooldown immediately`() {
        val clock = FakeClock(initial = 1_000_000L)
        val gate = SerpQuotaGate(nowMillisProvider = clock.provider)

        gate.markQuotaExceeded()
        assertTrue("post-mark", gate.shouldSkipSerp())

        gate.resetQuotaGate()
        assertFalse("post-reset", gate.shouldSkipSerp())
    }

    @Test
    fun `resetQuotaGate is idempotent on fresh gate`() {
        val gate = SerpQuotaGate(nowMillisProvider = FakeClock().provider)
        gate.resetQuotaGate()
        gate.resetQuotaGate()
        assertFalse(gate.shouldSkipSerp())
    }

    @Test
    fun `clock skew (lastHit in the future) self-heals`() {
        // Sets the last-hit timestamp first.
        val clock = FakeClock(initial = 1_000_000L)
        val gate = SerpQuotaGate(nowMillisProvider = clock.provider)
        gate.markQuotaExceeded() // recorded at 1_000_000

        // Now move clock BACKWARDS — simulates user changing system time.
        clock.nowMs = 500L
        // lastHit (1_000_000) > now (500) → defensive branch clears gate.
        assertFalse(gate.shouldSkipSerp())
    }
}
