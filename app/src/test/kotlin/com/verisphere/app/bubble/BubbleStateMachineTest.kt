package com.verisphere.app.bubble

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test

/**
 * Unit tests for [BubbleStateMachine] (Story 1.7, AC #15).
 *
 * Virtual time via `kotlinx.coroutines.test`: the state machine launches
 * its 5 s inactivity timer on the injected [TestScope]; tests advance
 * the virtual clock with [advanceTimeBy] / [runCurrent] instead of
 * sleeping. Stable, deterministic, fast.
 *
 * `runTest` is Kotlin's recommended coroutine test entry-point; the
 * `StandardTestDispatcher` does NOT auto-run pending coroutines (in
 * contrast to `UnconfinedTestDispatcher`), which is what we need to
 * verify the timer's "fires after 5 s, never sooner" contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BubbleStateMachineTest {

    @Test
    fun `initial state is idle, not faded`() = runTest {
        val sm = BubbleStateMachine(
            coroutineScope = TestScope(StandardTestDispatcher(testScheduler)),
        )

        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    @Test
    fun `UserActivity from faded state clears the fade flag`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.Idle(faded = true),
            coroutineScope = TestScope(StandardTestDispatcher(testScheduler)),
        )

        sm.onEvent(BubbleEvent.UserActivity)

        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    @Test
    fun `AutoFadeTimeout sets faded to true`() = runTest {
        val sm = BubbleStateMachine(
            coroutineScope = TestScope(StandardTestDispatcher(testScheduler)),
        )

        sm.onEvent(BubbleEvent.AutoFadeTimeout)

        assertEquals(BubbleState.Idle(faded = true), sm.state.value)
    }

    @Test
    fun `fade timer fires after 5 seconds of inactivity`() = runTest {
        val sm = BubbleStateMachine(
            coroutineScope = TestScope(StandardTestDispatcher(testScheduler)),
        )

        sm.state.test {
            assertEquals(BubbleState.Idle(faded = false), awaitItem())

            // UserActivity launches the 5 s timer. The state stays
            // Idle(faded=false) — no emission yet because the value is
            // unchanged.
            sm.onEvent(BubbleEvent.UserActivity)
            runCurrent()
            expectNoEvents()

            // Advance 4.999 s — still under threshold.
            advanceTimeBy(BubbleStateMachine.FADE_DELAY_MS - 1)
            runCurrent()
            expectNoEvents()

            // Cross the threshold.
            advanceTimeBy(1)
            advanceUntilIdle()
            assertEquals(BubbleState.Idle(faded = true), awaitItem())

            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `UserActivity cancels the previous fade timer`() = runTest {
        val sm = BubbleStateMachine(
            coroutineScope = TestScope(StandardTestDispatcher(testScheduler)),
        )

        // First gesture starts the timer.
        sm.onEvent(BubbleEvent.UserActivity)
        runCurrent()

        // 4 s elapse — timer would still be in flight.
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(BubbleState.Idle(faded = false), sm.state.value)

        // Second gesture cancels and re-arms the timer.
        sm.onEvent(BubbleEvent.UserActivity)
        runCurrent()

        // Another 4 s elapse — total 8 s since the FIRST gesture, but
        // only 4 s since the second. The timer has NOT fired.
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(BubbleState.Idle(faded = false), sm.state.value)

        // The remaining 1 s pushes the SECOND timer over the threshold.
        advanceTimeBy(1_000)
        advanceUntilIdle()
        assertEquals(BubbleState.Idle(faded = true), sm.state.value)
    }

    @Test
    fun `dispose cancels the active fade timer`() = runTest {
        val sm = BubbleStateMachine(
            coroutineScope = TestScope(StandardTestDispatcher(testScheduler)),
        )

        sm.onEvent(BubbleEvent.UserActivity)
        runCurrent()

        sm.dispose()

        // Advance well past the timer threshold — nothing should fire.
        advanceTimeBy(10_000)
        advanceUntilIdle()

        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    @Test
    fun `AutoFadeTimeout while already faded is a no-op`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.Idle(faded = true),
            coroutineScope = TestScope(StandardTestDispatcher(testScheduler)),
        )

        sm.onEvent(BubbleEvent.AutoFadeTimeout)
        sm.onEvent(BubbleEvent.AutoFadeTimeout)

        assertEquals(BubbleState.Idle(faded = true), sm.state.value)
    }

    /**
     * Forward-compat smoke for the reducer's
     * `(current as? BubbleState.Idle)?.copy(faded = true) ?: current`
     * guard. Story 1.7 only ships [BubbleState.Idle], so we cannot
     * exercise the non-Idle branch with the public API. Stays @Ignored
     * until Story 1.10 introduces `Pressing` / `Capturing` / `Thinking`
     * variants that allow constructing a non-Idle initial state — Story
     * 1.8 only adds a no-op forward-compat event, not new states.
     */
    @Test
    @Ignore("Forward-compat guard — non-Idle states arrive in Story 1.10.")
    fun `AutoFadeTimeout when not Idle is a no-op`() {
        // TODO Story 1.10: when BubbleState gains a Pressing variant, this
        // test should construct `BubbleStateMachine(initial = Pressing, ...)`,
        // fire AutoFadeTimeout, and assert state is still Pressing.
    }

    /**
     * Story 1.8 — `LongPressCompleted` is a forward-compat signal: the
     * service consumes it to start the [com.verisphere.app.capture.CapturePipeline],
     * but the bubble's visible state stays in Idle. The reducer's branch
     * (`current` — no transition) is the wire that future stories (1.10)
     * will replace with the real `Idle → Pressing → Capturing` transition.
     */
    @Test
    fun `LongPressCompleted is a no-op on Idle in story 1_8`() = runTest {
        val sm = BubbleStateMachine(
            coroutineScope = TestScope(StandardTestDispatcher(testScheduler)),
        )

        sm.onEvent(BubbleEvent.LongPressCompleted)
        runCurrent()

        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    /**
     * Story 1.8 — `LongPressCompleted` MUST NOT cancel or restart the
     * inactivity fade timer. A previous touch-down has already emitted
     * `UserActivity` (covering the timer reset); the long-press event
     * itself is purely a capture signal.
     */
    @Test
    fun `LongPressCompleted does not affect the running fade timer`() = runTest {
        val sm = BubbleStateMachine(
            coroutineScope = TestScope(StandardTestDispatcher(testScheduler)),
        )

        // Touch-down arms the 5 s timer.
        sm.onEvent(BubbleEvent.UserActivity)
        runCurrent()

        // 1 s into the hold the user has crossed the long-press threshold.
        advanceTimeBy(1_000)
        runCurrent()
        sm.onEvent(BubbleEvent.LongPressCompleted)
        runCurrent()

        // Another 4 s elapse — total 5 s since UserActivity. The timer
        // must still fire because LongPressCompleted did NOT touch it.
        advanceTimeBy(4_000)
        advanceUntilIdle()

        assertEquals(BubbleState.Idle(faded = true), sm.state.value)
    }
}
