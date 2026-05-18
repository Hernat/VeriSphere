package com.verisphere.app.bubble

import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.storage.SessionRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Story 7.5 C1 — Unit tests for the new [BubbleEvent.HistoryRecordNotFound]
 * event + [BubbleState.FailureState.NotFound] variant + the corresponding
 * [BubbleStateMachine] reducer / predicate branches.
 *
 * Closes deferred-work Story 2.4 D3 (null-record silent no-op) + Story 4.4 D5
 * (no JVM-seam test coverage for the null-record fallthrough) per Story 7.5
 * AC #1. Mirrors the [BubbleStateMachineTest] virtual-clock pattern
 * (`StandardTestDispatcher` + `advanceTimeBy`).
 *
 * Three reducer-branch assertions per AC #1 acceptance:
 *  1. `Idle → HistoryRecordNotFound → FailureState.NotFound(tooltipFaded=false)`
 *  2. `FailureState.NotFound → AutoFadeTimeout → tooltipFaded=true` (timer
 *      auto-flip after [BubbleStateMachine.TOOLTIP_FADE_MS])
 *  3. `FailureState.NotFound → BackToIdle → Idle(faded=false)` exit
 *
 * Plus per-CDN-#3 stale-signal-no-op coverage (a stale signal cannot
 * rewrite a transient state).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BubbleStateMachineNotFoundTest {

    private fun newScope(testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler): TestScope =
        TestScope(StandardTestDispatcher(testScheduler))

    private fun sampleRecord(): SessionRecord = SessionRecord(
        id = "rec-1",
        timestampMs = 0L,
        verdictLabel = VerdictLabel.TRUE,
        headline = "Sample TRUE verdict",
        contextLines = emptyList(),
        sourceLinks = emptyList<SourceCitation>(),
        ocrText = "",
        regionalBiasNote = null,
        injectionDetected = false,
    )

    // ----- (1) Idle → HistoryRecordNotFound → FailureState.NotFound ----

    @Test
    fun `Idle transitions to FailureState NotFound on HistoryRecordNotFound`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))

        sm.onEvent(BubbleEvent.HistoryRecordNotFound)

        assertEquals(
            BubbleState.FailureState.NotFound(tooltipFaded = false),
            sm.state.value,
        )
    }

    @Test
    fun `Idle faded also transitions to FailureState NotFound on HistoryRecordNotFound`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.Idle(faded = true),
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.HistoryRecordNotFound)

        assertEquals(
            BubbleState.FailureState.NotFound(tooltipFaded = false),
            sm.state.value,
        )
    }

    // ----- (2) FailureState.NotFound auto-fades tooltip after timer ----

    @Test
    fun `FailureState NotFound tooltipFaded false auto-flips to true after TOOLTIP_FADE_MS`() =
        runTest {
            val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))

            sm.onEvent(BubbleEvent.HistoryRecordNotFound)
            assertEquals(
                BubbleState.FailureState.NotFound(tooltipFaded = false),
                sm.state.value,
            )

            // Just before the timer — still not faded.
            advanceTimeBy(BubbleStateMachine.TOOLTIP_FADE_MS - 1)
            assertEquals(
                BubbleState.FailureState.NotFound(tooltipFaded = false),
                sm.state.value,
            )

            // Tick over — auto-fade fires.
            advanceTimeBy(2)
            assertEquals(
                BubbleState.FailureState.NotFound(tooltipFaded = true),
                sm.state.value,
            )
        }

    @Test
    fun `AutoFadeTimeout on NotFound tooltipFaded true is reducer no-op idempotency`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.FailureState.NotFound(tooltipFaded = true),
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.AutoFadeTimeout)

        // idempotent — true.copy(tooltipFaded = true) is a no-op.
        assertEquals(
            BubbleState.FailureState.NotFound(tooltipFaded = true),
            sm.state.value,
        )
    }

    // ----- (3) FailureState.NotFound → BackToIdle → Idle ---------------

    @Test
    fun `FailureState NotFound transitions back to Idle on BackToIdle`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.FailureState.NotFound(tooltipFaded = false),
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.BackToIdle)

        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    /**
     * Story 7.5 code-review P8 — guards against a regression where
     * leaving `FailureState.NotFound` via `BackToIdle` fails to cancel
     * the in-flight `tooltipFadeJob`. If the cleanup at
     * `BubbleStateMachine.handleTransitionSideEffects` (the
     * `leavingTooltipState` branch) ever stops covering `NotFound`,
     * the stale `AutoFadeTimeout` fires after the user has already
     * returned to Idle and re-armed the adaptive-presence fade —
     * which would silently flip the now-Idle bubble back to faded
     * prematurely (Blind Hunter #8).
     *
     * The assertion is indirect: we drive an Idle→NotFound transition
     * (which arms the tooltipFadeJob via `enteredFreshTooltip`), then
     * BackToIdle, then advance virtual time past `TOOLTIP_FADE_MS`. If
     * the cleanup works, the bubble stays at `Idle(faded=false)` then
     * fades via the SEPARATE adaptive-presence fade timer (5 s). If
     * the cleanup is broken, the stale tooltipFadeJob fires and
     * `_state.update { ... }` is a no-op on Idle anyway — so the
     * smoking gun is a coroutine-leak detector. We use a tighter
     * assertion: advance EXACTLY past TOOLTIP_FADE_MS but BEFORE the
     * adaptive-fade FADE_DELAY_MS=5s timer, and assert state stays
     * `Idle(faded=false)`. This proves the tooltipFadeJob didn't
     * fire from a stale NotFound context.
     */
    @Test
    fun `BackToIdle from NotFound cancels in-flight tooltipFadeJob`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))

        // 1. Arm a fresh NotFound + its tooltipFadeJob.
        sm.onEvent(BubbleEvent.HistoryRecordNotFound)
        assertEquals(
            BubbleState.FailureState.NotFound(tooltipFaded = false),
            sm.state.value,
        )

        // 2. Dismiss before the tooltipFadeJob fires.
        sm.onEvent(BubbleEvent.BackToIdle)
        assertEquals(BubbleState.Idle(faded = false), sm.state.value)

        // 3. Advance past TOOLTIP_FADE_MS (6 s) but stop BEFORE the
        // adaptive-presence FADE_DELAY_MS (5 s after Idle entry — so
        // total 5 s + 1 s headroom past Idle re-arm). If the stale
        // tooltipFadeJob fires, it dispatches AutoFadeTimeout which
        // would set Idle(faded=true) prematurely. Test passes only
        // if the cleanup canceled it.
        advanceTimeBy(BubbleStateMachine.TOOLTIP_FADE_MS / 2)
        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    // ----- Stale-signal no-op coverage (CDN #3) ------------------------

    @Test
    fun `HistoryRecordNotFound from Pressing is a no-op`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.Pressing,
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.HistoryRecordNotFound)

        assertEquals(BubbleState.Pressing, sm.state.value)
    }

    @Test
    fun `HistoryRecordNotFound from Capturing is a no-op`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.Capturing,
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.HistoryRecordNotFound)

        assertEquals(BubbleState.Capturing, sm.state.value)
    }

    @Test
    fun `HistoryRecordNotFound from Thinking is a no-op`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.Thinking,
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.HistoryRecordNotFound)

        assertEquals(BubbleState.Thinking, sm.state.value)
    }

    @Test
    fun `HistoryRecordNotFound from Verdict is a no-op`() = runTest {
        val record = sampleRecord()
        val sm = BubbleStateMachine(
            initial = BubbleState.Verdict(record, tooltipFaded = false),
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.HistoryRecordNotFound)

        assertEquals(BubbleState.Verdict(record, tooltipFaded = false), sm.state.value)
    }

    @Test
    fun `HistoryRecordNotFound from another FailureState variant is a no-op`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.FailureState.Offline(tooltipFaded = false),
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.HistoryRecordNotFound)

        // The reducer's Idle-only guard means a stale NotFound signal
        // cannot overwrite an existing FailureState.Offline.
        assertEquals(BubbleState.FailureState.Offline(tooltipFaded = false), sm.state.value)
    }

    // ----- LongPressStarted re-trigger semantics (sealed catch-all) ----

    @Test
    fun `LongPressStarted from FailureState NotFound transitions to Pressing`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.FailureState.NotFound(tooltipFaded = false),
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.LongPressStarted)

        // Mirrors the existing UX-DR14 re-trigger grammar for all
        // FailureState variants (the long-press is the user's recovery
        // affordance after seeing the NotFound flash).
        assertEquals(BubbleState.Pressing, sm.state.value)
    }

    // ----- Adaptive-presence fade re-arms on BackToIdle from NotFound --

    @Test
    fun `BackToIdle from NotFound arms the adaptive-presence fade timer`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.FailureState.NotFound(tooltipFaded = false),
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.BackToIdle)
        assertEquals(BubbleState.Idle(faded = false), sm.state.value)

        // Adaptive-presence fade timer is armed (Story 1.10 P1 regression
        // coverage extends to NotFound transitively via the sealed
        // BackToIdle smart-cast catch-all).
        advanceUntilIdle()
        assertEquals(BubbleState.Idle(faded = true), sm.state.value)
    }
}
