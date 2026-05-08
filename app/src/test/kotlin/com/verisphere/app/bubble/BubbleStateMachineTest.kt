package com.verisphere.app.bubble

import app.cash.turbine.test
import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.gemini.VerificationOutcome
import com.verisphere.app.storage.SessionRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BubbleStateMachine] (Story 1.7 + 1.10 ACs).
 *
 * Virtual time via `kotlinx.coroutines.test`: the state machine launches
 * its 5 s inactivity timer on the injected [TestScope]; tests advance
 * the virtual clock with [advanceTimeBy] / [runCurrent] instead of
 * sleeping. Stable, deterministic, fast.
 *
 * `runTest` is Kotlin's recommended coroutine test entry-point; the
 * `StandardTestDispatcher` does NOT auto-run pending coroutines (in
 * contrast to `UnconfinedTestDispatcher`), which is what we need to
 * verify the timers' "fires after N ms, never sooner" contracts.
 *
 * Story 1.10 — every transition in the AC #3 transition table is
 * tested below, plus the obsolete `LongPressCompleted is a no-op on
 * Idle in story 1_8` test from Story 1.7 has been deleted (the event
 * now produces a real transition).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BubbleStateMachineTest {

    private fun newScope(testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler): TestScope =
        TestScope(StandardTestDispatcher(testScheduler))

    private fun sampleRecord(label: VerdictLabel = VerdictLabel.TRUE, id: String = "rec-1"): SessionRecord =
        SessionRecord(
            id = id,
            timestampMs = 0L,
            verdictLabel = label,
            headline = "Sample headline for $label",
            contextLines = emptyList(),
            sourceLinks = emptyList<SourceCitation>(),
            ocrText = "",
            regionalBiasNote = null,
        )

    // ----- Story 1.7 contract (preserved) -----------------------------

    @Test
    fun `initial state is idle, not faded`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))

        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    @Test
    fun `UserActivity from faded state clears the fade flag`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.Idle(faded = true),
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.UserActivity)

        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    @Test
    fun `AutoFadeTimeout sets faded to true`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))

        sm.onEvent(BubbleEvent.AutoFadeTimeout)

        assertEquals(BubbleState.Idle(faded = true), sm.state.value)
    }

    @Test
    fun `fade timer fires after 5 seconds of inactivity`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))

        sm.state.test {
            assertEquals(BubbleState.Idle(faded = false), awaitItem())

            sm.onEvent(BubbleEvent.UserActivity)
            runCurrent()
            expectNoEvents()

            advanceTimeBy(BubbleStateMachine.FADE_DELAY_MS - 1)
            runCurrent()
            expectNoEvents()

            advanceTimeBy(1)
            advanceUntilIdle()
            assertEquals(BubbleState.Idle(faded = true), awaitItem())

            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `UserActivity cancels the previous fade timer`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))

        sm.onEvent(BubbleEvent.UserActivity)
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(BubbleState.Idle(faded = false), sm.state.value)

        sm.onEvent(BubbleEvent.UserActivity)
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(BubbleState.Idle(faded = false), sm.state.value)

        advanceTimeBy(1_000)
        advanceUntilIdle()
        assertEquals(BubbleState.Idle(faded = true), sm.state.value)
    }

    @Test
    fun `dispose cancels the active fade timer`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))

        sm.onEvent(BubbleEvent.UserActivity)
        runCurrent()

        sm.dispose()

        advanceTimeBy(10_000)
        advanceUntilIdle()

        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    @Test
    fun `AutoFadeTimeout while already faded is a no-op`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.Idle(faded = true),
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.AutoFadeTimeout)
        sm.onEvent(BubbleEvent.AutoFadeTimeout)

        assertEquals(BubbleState.Idle(faded = true), sm.state.value)
    }

    // ----- Story 1.10 — full happy-path transition table --------------

    @Test
    fun `LongPressStarted from Idle transitions to Pressing`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))

        sm.onEvent(BubbleEvent.LongPressStarted)
        runCurrent()

        assertEquals(BubbleState.Pressing, sm.state.value)
    }

    @Test
    fun `LongPressCompleted from Pressing transitions to Capturing`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))

        sm.onEvent(BubbleEvent.LongPressStarted)
        runCurrent()
        sm.onEvent(BubbleEvent.LongPressCompleted)
        runCurrent()

        assertEquals(BubbleState.Capturing, sm.state.value)
    }

    @Test
    fun `Capturing auto-transitions to Thinking after suction animation duration`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))

        sm.onEvent(BubbleEvent.LongPressStarted)
        runCurrent()
        sm.onEvent(BubbleEvent.LongPressCompleted)
        runCurrent()
        assertEquals(BubbleState.Capturing, sm.state.value)

        advanceTimeBy(BubbleStateMachine.SUCTION_ANIMATION_MS - 1)
        runCurrent()
        assertEquals(BubbleState.Capturing, sm.state.value)

        advanceTimeBy(1)
        advanceUntilIdle()
        assertEquals(BubbleState.Thinking, sm.state.value)
    }

    @Test
    fun `BackToIdle from Pressing transitions to Idle`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))

        sm.onEvent(BubbleEvent.LongPressStarted)
        runCurrent()
        assertEquals(BubbleState.Pressing, sm.state.value)

        sm.onEvent(BubbleEvent.BackToIdle)
        runCurrent()

        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    @Test
    fun `VerificationOutcomeReceived with Verdict transitions Thinking to Verdict and tooltipFaded is false`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))

        sm.onEvent(BubbleEvent.LongPressStarted)
        sm.onEvent(BubbleEvent.LongPressCompleted)
        advanceTimeBy(BubbleStateMachine.SUCTION_ANIMATION_MS)
        advanceUntilIdle()
        assertEquals(BubbleState.Thinking, sm.state.value)

        val record = sampleRecord(VerdictLabel.TRUE)
        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Verdict(record)))
        runCurrent()

        val state = sm.state.value
        assertTrue("expected Verdict, got $state", state is BubbleState.Verdict)
        state as BubbleState.Verdict
        assertEquals(record, state.record)
        assertEquals(false, state.tooltipFaded)
    }

    @Test
    fun `VerificationOutcomeReceived with Failure transitions Thinking to Idle in story 1_10`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))

        sm.onEvent(BubbleEvent.LongPressStarted)
        sm.onEvent(BubbleEvent.LongPressCompleted)
        advanceTimeBy(BubbleStateMachine.SUCTION_ANIMATION_MS)
        advanceUntilIdle()

        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Failure.Offline))
        runCurrent()

        // Story 3.3 will introduce BubbleState.FailureState.* variants;
        // Story 1.10 returns silently to Idle so the happy path can ship.
        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    @Test
    fun `Verdict with tooltipFaded false auto-transitions to tooltipFaded true after fade timer`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))
        val record = sampleRecord()

        sm.onEvent(BubbleEvent.LongPressStarted)
        sm.onEvent(BubbleEvent.LongPressCompleted)
        advanceTimeBy(BubbleStateMachine.SUCTION_ANIMATION_MS)
        advanceUntilIdle()
        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Verdict(record)))
        runCurrent()

        // Tooltip text NOT yet faded.
        assertEquals(BubbleState.Verdict(record, tooltipFaded = false), sm.state.value)

        advanceTimeBy(BubbleStateMachine.TOOLTIP_FADE_MS - 1)
        runCurrent()
        assertEquals(BubbleState.Verdict(record, tooltipFaded = false), sm.state.value)

        advanceTimeBy(1)
        advanceUntilIdle()
        assertEquals(BubbleState.Verdict(record, tooltipFaded = true), sm.state.value)
    }

    @Test
    fun `Verdict tooltipFaded true survives subsequent AutoFadeTimeout events`() = runTest {
        val record = sampleRecord()
        val sm = BubbleStateMachine(
            initial = BubbleState.Verdict(record, tooltipFaded = true),
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.AutoFadeTimeout)
        sm.onEvent(BubbleEvent.AutoFadeTimeout)
        runCurrent()

        assertEquals(BubbleState.Verdict(record, tooltipFaded = true), sm.state.value)
    }

    @Test
    fun `BackToIdle from Verdict returns to Idle and clears the verdict record from in-memory state`() = runTest {
        val record = sampleRecord()
        val sm = BubbleStateMachine(
            initial = BubbleState.Verdict(record, tooltipFaded = false),
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.BackToIdle)
        runCurrent()

        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    @Test
    fun `LongPressStarted from Verdict transitions to Pressing for re-trigger`() = runTest {
        val record = sampleRecord()
        val sm = BubbleStateMachine(
            initial = BubbleState.Verdict(record, tooltipFaded = false),
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.LongPressStarted)
        runCurrent()

        // Per UX-DR14 `Verdict × Long-press = re-trigger`. The previous
        // record stays in history (the service-side persistence is
        // separate from the in-memory state); here we only check the
        // state transition.
        assertEquals(BubbleState.Pressing, sm.state.value)
    }

    @Test
    fun `AutoFadeTimeout when not Idle nor Verdict is a no-op`() = runTest {
        // Forward-compat guard from Story 1.7 — un-Ignored in Story 1.10
        // because the Pressing / Capturing / Thinking states now exist.
        // A stale fade timer fired during a transient state must not
        // flip it back to Idle(faded=true).
        for (state in listOf(BubbleState.Pressing, BubbleState.Capturing, BubbleState.Thinking)) {
            val sm = BubbleStateMachine(
                initial = state,
                coroutineScope = newScope(testScheduler),
            )

            sm.onEvent(BubbleEvent.AutoFadeTimeout)
            runCurrent()

            assertEquals("AutoFadeTimeout on $state should be a no-op", state, sm.state.value)
        }
    }

    @Test
    fun `BackToIdle from Verdict re-arms the adaptive-presence fade timer`() = runTest {
        // Code-review patch P1 regression — without re-arming, the bubble
        // would stay opaque indefinitely after Verdict dismissal.
        val record = sampleRecord()
        val sm = BubbleStateMachine(
            initial = BubbleState.Verdict(record, tooltipFaded = false),
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.BackToIdle)
        runCurrent()
        assertEquals(BubbleState.Idle(faded = false), sm.state.value)

        // Advance past the 5 s threshold — the new fadeJob should fire.
        advanceTimeBy(BubbleStateMachine.FADE_DELAY_MS)
        advanceUntilIdle()
        assertEquals(BubbleState.Idle(faded = true), sm.state.value)
    }

    @Test
    fun `VerificationOutcome Failure from Thinking re-arms the adaptive-presence fade timer`() = runTest {
        // Code-review patch P1 regression — Story 1.10 maps Failure.* to
        // Idle silently; the fade timer must still arm so the bubble
        // fades 5 s after the silent return.
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))

        sm.onEvent(BubbleEvent.LongPressStarted)
        sm.onEvent(BubbleEvent.LongPressCompleted)
        advanceTimeBy(BubbleStateMachine.SUCTION_ANIMATION_MS)
        advanceUntilIdle()
        assertEquals(BubbleState.Thinking, sm.state.value)

        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Failure.Offline))
        runCurrent()
        assertEquals(BubbleState.Idle(faded = false), sm.state.value)

        advanceTimeBy(BubbleStateMachine.FADE_DELAY_MS)
        advanceUntilIdle()
        assertEquals(BubbleState.Idle(faded = true), sm.state.value)
    }

    @Test
    fun `dispose cancels suction and tooltip-fade timers in addition to the fade timer`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))

        sm.onEvent(BubbleEvent.LongPressStarted)
        sm.onEvent(BubbleEvent.LongPressCompleted)
        runCurrent()
        // Capturing is now active with a 300 ms timer pending.

        sm.dispose()

        advanceTimeBy(10_000)
        advanceUntilIdle()

        // Without dispose, the timer would have promoted Capturing → Thinking
        // and then a tooltip-fade would have armed too. With dispose, the
        // state machine is frozen at Capturing (the last state before dispose).
        assertEquals(BubbleState.Capturing, sm.state.value)
    }
}
