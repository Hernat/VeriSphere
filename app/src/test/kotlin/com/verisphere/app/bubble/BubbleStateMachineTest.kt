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
import org.junit.Assert.assertFalse
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

    private fun sampleRecord(
        label: VerdictLabel = VerdictLabel.TRUE,
        id: String = "rec-1",
        injectionDetected: Boolean = false,
    ): SessionRecord =
        SessionRecord(
            id = id,
            timestampMs = 0L,
            verdictLabel = label,
            headline = "Sample headline for $label",
            contextLines = emptyList(),
            sourceLinks = emptyList<SourceCitation>(),
            ocrText = "",
            regionalBiasNote = null,
            injectionDetected = injectionDetected,
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

    // Story 1.10 forward-compat test deleted in Story 3.3 — replaced by
    // the per-variant Failure → FailureState tests below.

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
    fun `silent-bucket Failure from Thinking re-arms the adaptive-presence fade timer`() = runTest {
        // Story 3.3 — silent-bucket Failure.* (PermissionDenied,
        // CaptureFailed, MalformedResponse, HttpError) still map to Idle;
        // Offline / Timeout / DailyLimit / QuotaExhausted now map to their
        // FailureState variants. Code-review patch P1 regression — the
        // fade timer must still arm so the bubble fades 5 s after the
        // silent return.
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))

        sm.onEvent(BubbleEvent.LongPressStarted)
        sm.onEvent(BubbleEvent.LongPressCompleted)
        advanceTimeBy(BubbleStateMachine.SUCTION_ANIMATION_MS)
        advanceUntilIdle()
        assertEquals(BubbleState.Thinking, sm.state.value)

        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Failure.PermissionDenied))
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

    // ----- Story 3.3 contract -----------------------------------------

    /**
     * Drives the SM through Idle → Pressing → Capturing → Thinking so
     * the [BubbleEvent.VerificationOutcomeReceived] dispatch lands in
     * a Thinking state (the canonical happy-path entry point for
     * outcome processing).
     */
    private fun TestScope.driveToThinking(sm: BubbleStateMachine) {
        sm.onEvent(BubbleEvent.LongPressStarted)
        sm.onEvent(BubbleEvent.LongPressCompleted)
        advanceTimeBy(BubbleStateMachine.SUCTION_ANIMATION_MS)
        advanceUntilIdle()
        assertEquals(BubbleState.Thinking, sm.state.value)
    }

    @Test
    fun `Failure Offline maps to FailureState Offline`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))
        driveToThinking(sm)

        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Failure.Offline))
        runCurrent()

        assertEquals(BubbleState.FailureState.Offline(tooltipFaded = false), sm.state.value)
    }

    @Test
    fun `Failure Timeout maps to FailureState Timeout`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))
        driveToThinking(sm)

        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Failure.Timeout))
        runCurrent()

        assertEquals(BubbleState.FailureState.Timeout(tooltipFaded = false), sm.state.value)
    }

    @Test
    fun `Failure DailyLimitReached maps to FailureState DailyLimit`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))
        driveToThinking(sm)

        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Failure.DailyLimitReached))
        runCurrent()

        assertEquals(BubbleState.FailureState.DailyLimit(tooltipFaded = false), sm.state.value)
    }

    @Test
    fun `Failure ApiQuotaExhausted maps to FailureState QuotaExhausted`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))
        driveToThinking(sm)

        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Failure.ApiQuotaExhausted))
        runCurrent()

        assertEquals(BubbleState.FailureState.QuotaExhausted(tooltipFaded = false), sm.state.value)
    }

    @Test
    fun `Verdict with injectionDetected true maps to FailureState PossibleInjection`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))
        driveToThinking(sm)

        val record = sampleRecord(label = VerdictLabel.DOUBTFUL, injectionDetected = true)
        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Verdict(record)))
        runCurrent()

        assertEquals(
            BubbleState.FailureState.PossibleInjection(record = record, tooltipFaded = false),
            sm.state.value,
        )
    }

    @Test
    fun `Verdict with injectionDetected false maps to Verdict`() = runTest {
        // Companion to the previous test — verifies the branching predicate
        // does NOT incorrectly redirect non-injection verdicts.
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))
        driveToThinking(sm)

        val record = sampleRecord(injectionDetected = false)
        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Verdict(record)))
        runCurrent()

        assertEquals(BubbleState.Verdict(record, tooltipFaded = false), sm.state.value)
    }

    @Test
    fun `Failure PermissionDenied maps to Idle faded false silent`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))
        driveToThinking(sm)

        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Failure.PermissionDenied))
        runCurrent()

        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    @Test
    fun `Failure CaptureFailed maps to Idle faded false silent`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))
        driveToThinking(sm)

        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Failure.CaptureFailed))
        runCurrent()

        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    @Test
    fun `Failure HttpError 403 maps to Idle faded false silent`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))
        driveToThinking(sm)

        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Failure.HttpError(403)))
        runCurrent()

        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    @Test
    fun `Failure HttpError 500 maps to Idle faded false silent`() = runTest {
        // 5xx after retry exhaustion (Story 3.2) still routes through the
        // silent bucket — the user-actionable end-states are Offline /
        // Timeout / DailyLimit / QuotaExhausted only.
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))
        driveToThinking(sm)

        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Failure.HttpError(500)))
        runCurrent()

        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    @Test
    fun `Failure MalformedResponse maps to Idle faded false silent`() = runTest {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))
        driveToThinking(sm)

        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Failure.MalformedResponse))
        runCurrent()

        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    /**
     * Verifies a FailureState variant's tooltipFaded flag auto-flips to
     * true after [BubbleStateMachine.TOOLTIP_FADE_MS] — mirrors the
     * existing Verdict tooltip-fade test (line ~253). The SM must be
     * driven through the full Idle → Thinking → FailureState transition
     * so [BubbleStateMachine.handleTransitionSideEffects] arms the
     * tooltip-fade timer (a constructor-initialized state would skip
     * the side-effect arming since there's no `previous → next`
     * transition to diff).
     *
     * @param outcome The [VerificationOutcome] to dispatch from Thinking;
     *                drives the canonical entry into the target
     *                FailureState variant.
     * @param expectedEntry The FailureState the reducer should produce
     *                      immediately after [outcome] is processed.
     * @param expectedFaded The same FailureState with `tooltipFaded = true`,
     *                      asserted after the timer fires.
     */
    private fun TestScope.assertTooltipFadeTimer(
        outcome: VerificationOutcome,
        expectedEntry: BubbleState,
        expectedFaded: BubbleState,
    ) {
        val sm = BubbleStateMachine(coroutineScope = newScope(testScheduler))
        driveToThinking(sm)
        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(outcome))
        runCurrent()
        assertEquals("Entry state mismatch", expectedEntry, sm.state.value)

        advanceTimeBy(BubbleStateMachine.TOOLTIP_FADE_MS - 1)
        runCurrent()
        assertEquals("Tooltip should not yet be faded", expectedEntry, sm.state.value)

        advanceTimeBy(1)
        advanceUntilIdle()
        assertEquals("Tooltip should be faded after TOOLTIP_FADE_MS", expectedFaded, sm.state.value)
    }

    @Test
    fun `FailureState Offline auto-flips tooltipFaded after TOOLTIP_FADE_MS`() = runTest {
        assertTooltipFadeTimer(
            outcome = VerificationOutcome.Failure.Offline,
            expectedEntry = BubbleState.FailureState.Offline(tooltipFaded = false),
            expectedFaded = BubbleState.FailureState.Offline(tooltipFaded = true),
        )
    }

    @Test
    fun `FailureState Timeout auto-flips tooltipFaded after TOOLTIP_FADE_MS`() = runTest {
        assertTooltipFadeTimer(
            outcome = VerificationOutcome.Failure.Timeout,
            expectedEntry = BubbleState.FailureState.Timeout(tooltipFaded = false),
            expectedFaded = BubbleState.FailureState.Timeout(tooltipFaded = true),
        )
    }

    @Test
    fun `FailureState DailyLimit auto-flips tooltipFaded after TOOLTIP_FADE_MS`() = runTest {
        assertTooltipFadeTimer(
            outcome = VerificationOutcome.Failure.DailyLimitReached,
            expectedEntry = BubbleState.FailureState.DailyLimit(tooltipFaded = false),
            expectedFaded = BubbleState.FailureState.DailyLimit(tooltipFaded = true),
        )
    }

    @Test
    fun `FailureState QuotaExhausted auto-flips tooltipFaded after TOOLTIP_FADE_MS`() = runTest {
        assertTooltipFadeTimer(
            outcome = VerificationOutcome.Failure.ApiQuotaExhausted,
            expectedEntry = BubbleState.FailureState.QuotaExhausted(tooltipFaded = false),
            expectedFaded = BubbleState.FailureState.QuotaExhausted(tooltipFaded = true),
        )
    }

    @Test
    fun `FailureState PossibleInjection auto-flips tooltipFaded after TOOLTIP_FADE_MS`() = runTest {
        val record = sampleRecord(label = VerdictLabel.DOUBTFUL, injectionDetected = true)
        assertTooltipFadeTimer(
            outcome = VerificationOutcome.Verdict(record),
            expectedEntry = BubbleState.FailureState.PossibleInjection(record = record, tooltipFaded = false),
            expectedFaded = BubbleState.FailureState.PossibleInjection(record = record, tooltipFaded = true),
        )
    }

    /**
     * Verifies BackToIdle from a FailureState returns to Idle AND re-arms
     * the 5 s adaptive-presence fade timer (code-review patch P1
     * regression coverage from Story 1.10 applied to FailureState).
     */
    private fun TestScope.assertBackToIdleRearmsFadeTimer(initialState: BubbleState) {
        val sm = BubbleStateMachine(
            initial = initialState,
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.BackToIdle)
        runCurrent()
        assertEquals(BubbleState.Idle(faded = false), sm.state.value)

        advanceTimeBy(BubbleStateMachine.FADE_DELAY_MS)
        advanceUntilIdle()
        assertEquals(BubbleState.Idle(faded = true), sm.state.value)
    }

    @Test
    fun `BackToIdle from FailureState Offline re-arms the adaptive-presence fade timer`() = runTest {
        assertBackToIdleRearmsFadeTimer(BubbleState.FailureState.Offline())
    }

    @Test
    fun `BackToIdle from FailureState Timeout re-arms the adaptive-presence fade timer`() = runTest {
        assertBackToIdleRearmsFadeTimer(BubbleState.FailureState.Timeout())
    }

    @Test
    fun `BackToIdle from FailureState DailyLimit re-arms the adaptive-presence fade timer`() = runTest {
        assertBackToIdleRearmsFadeTimer(BubbleState.FailureState.DailyLimit())
    }

    @Test
    fun `BackToIdle from FailureState QuotaExhausted re-arms the adaptive-presence fade timer`() = runTest {
        assertBackToIdleRearmsFadeTimer(BubbleState.FailureState.QuotaExhausted())
    }

    @Test
    fun `BackToIdle from FailureState PossibleInjection re-arms the adaptive-presence fade timer`() = runTest {
        val record = sampleRecord(label = VerdictLabel.DOUBTFUL, injectionDetected = true)
        assertBackToIdleRearmsFadeTimer(BubbleState.FailureState.PossibleInjection(record = record))
    }

    /**
     * UX-DR14 row "Failure × Long-press = Re-trigger capture" — every
     * FailureState transitions to Pressing on LongPressStarted, same as
     * Verdict.
     */
    private fun TestScope.assertLongPressFromFailureGoesToPressing(initialState: BubbleState) {
        val sm = BubbleStateMachine(
            initial = initialState,
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.LongPressStarted)
        runCurrent()

        assertEquals(BubbleState.Pressing, sm.state.value)
    }

    @Test
    fun `LongPressStarted from FailureState Offline transitions to Pressing`() = runTest {
        assertLongPressFromFailureGoesToPressing(BubbleState.FailureState.Offline())
    }

    @Test
    fun `LongPressStarted from FailureState Timeout transitions to Pressing`() = runTest {
        assertLongPressFromFailureGoesToPressing(BubbleState.FailureState.Timeout())
    }

    @Test
    fun `LongPressStarted from FailureState DailyLimit transitions to Pressing`() = runTest {
        assertLongPressFromFailureGoesToPressing(BubbleState.FailureState.DailyLimit())
    }

    @Test
    fun `LongPressStarted from FailureState QuotaExhausted transitions to Pressing`() = runTest {
        assertLongPressFromFailureGoesToPressing(BubbleState.FailureState.QuotaExhausted())
    }

    @Test
    fun `LongPressStarted from FailureState PossibleInjection transitions to Pressing`() = runTest {
        val record = sampleRecord(label = VerdictLabel.DOUBTFUL, injectionDetected = true)
        assertLongPressFromFailureGoesToPressing(BubbleState.FailureState.PossibleInjection(record = record))
    }

    // ===== Story 3.4 — Reduce-motion preference handling =================

    @Test
    fun `reduceMotionEnabled false plays the full suction timer`() = runTest {
        val sm = BubbleStateMachine(
            coroutineScope = newScope(testScheduler),
            reduceMotionEnabled = false,
        )
        sm.onEvent(BubbleEvent.LongPressStarted)
        sm.onEvent(BubbleEvent.LongPressCompleted)
        runCurrent()
        assertEquals(BubbleState.Capturing, sm.state.value)

        advanceTimeBy(BubbleStateMachine.SUCTION_ANIMATION_MS - 1)
        runCurrent()
        // Boundary check — the SM must still be Capturing 1 ms before
        // the timer fires (regression guard from Story 1.10).
        assertEquals(BubbleState.Capturing, sm.state.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(BubbleState.Thinking, sm.state.value)
    }

    @Test
    fun `reduceMotionEnabled true short-circuits Capturing to Thinking instantly`() = runTest {
        val sm = BubbleStateMachine(
            coroutineScope = newScope(testScheduler),
            reduceMotionEnabled = true,
        )
        sm.onEvent(BubbleEvent.LongPressStarted)
        sm.onEvent(BubbleEvent.LongPressCompleted)
        // No advanceTimeBy — the transition MUST fire inside one
        // event-loop tick (the suctionJob skips the 300 ms delay and
        // dispatches Thinking synchronously).
        runCurrent()
        assertEquals(BubbleState.Thinking, sm.state.value)
    }

    @Test
    fun `reduceMotionEnabled property reflects constructor parameter`() {
        val on = BubbleStateMachine(reduceMotionEnabled = true)
        val off = BubbleStateMachine(reduceMotionEnabled = false)
        try {
            assertTrue(on.reduceMotionEnabled)
            assertFalse(off.reduceMotionEnabled)
        } finally {
            on.dispose()
            off.dispose()
        }
    }

    @Test
    fun `reduceMotionEnabled true does not leak the suction job after dispose`() = runTest {
        val sm = BubbleStateMachine(
            coroutineScope = newScope(testScheduler),
            reduceMotionEnabled = true,
        )
        sm.onEvent(BubbleEvent.LongPressStarted)
        sm.onEvent(BubbleEvent.LongPressCompleted)
        runCurrent()
        assertEquals(BubbleState.Thinking, sm.state.value)

        // dispose() must not throw and must leave no pending
        // continuations that re-emit Thinking after the timer would
        // have fired (here the timer was synchronous so this is a
        // belt-and-suspenders check — the SM must not regress to a
        // version where a deferred Thinking emission overwrites a
        // subsequent state).
        sm.dispose()
        advanceTimeBy(BubbleStateMachine.SUCTION_ANIMATION_MS * 2)
        assertEquals(BubbleState.Thinking, sm.state.value)
    }
}
