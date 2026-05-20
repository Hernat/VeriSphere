package com.verisphere.app.bubble

import com.verisphere.app.gemini.VerificationOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Story 10.1 — JVM unit tests for the new
 * [BubbleState.FailureState.NoApiKey] variant + the
 * [BubbleStateMachine.mapFailureToState] route from
 * [VerificationOutcome.Failure.NotConfigured]. Mirrors the
 * [BubbleStateMachineNotFoundTest] virtual-clock pattern
 * (`StandardTestDispatcher` + `advanceTimeBy`).
 *
 * Unlike `NotFound` (which has a dedicated
 * [BubbleEvent.HistoryRecordNotFound] event from a cross-process
 * MainActivity null-branch trigger), `NoApiKey` arrives through the
 * normal pipeline path : `GeminiClient.verify` short-circuits on a
 * blank `apiKeyProvider()` ; `CapturePipeline` dispatches the existing
 * [BubbleEvent.VerificationOutcomeReceived] with the new
 * [VerificationOutcome.Failure.NotConfigured] outcome ; the reducer's
 * `mapFailureToState` returns [BubbleState.FailureState.NoApiKey].
 *
 * Five assertions per Story 10.1 T4.7 :
 *  1. `Capturing → VerificationOutcomeReceived(NotConfigured) → FailureState.NoApiKey(tooltipFaded=false)`
 *  2. `Thinking → VerificationOutcomeReceived(NotConfigured) → FailureState.NoApiKey(tooltipFaded=false)`
 *  3. `Idle × VerificationOutcomeReceived → no-op` (stale signal cannot
 *     rewrite a non-transient state)
 *  4. `FailureState.NoApiKey → AutoFadeTimeout → tooltipFaded=true`
 *      (timer auto-flip after [BubbleStateMachine.TOOLTIP_FADE_MS])
 *  5. `FailureState.NoApiKey → BackToIdle → Idle(faded=false)` exit
 *
 * Plus the constructor-default `tooltipFaded = false` invariant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BubbleStateMachineNoApiKeyTest {

    private fun newScope(testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler): TestScope =
        TestScope(StandardTestDispatcher(testScheduler))

    // ----- (1) Capturing → NotConfigured → FailureState.NoApiKey -----

    @Test
    fun `Capturing transitions to FailureState NoApiKey on NotConfigured outcome`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.Capturing,
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Failure.NotConfigured))

        assertEquals(
            BubbleState.FailureState.NoApiKey(tooltipFaded = false),
            sm.state.value,
        )
    }

    @Test
    fun `Thinking transitions to FailureState NoApiKey on NotConfigured outcome`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.Thinking,
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Failure.NotConfigured))

        assertEquals(
            BubbleState.FailureState.NoApiKey(tooltipFaded = false),
            sm.state.value,
        )
    }

    // ----- (2) Stale-signal no-op from non-transient states ----------

    @Test
    fun `Idle × VerificationOutcomeReceived NotConfigured is reducer no-op`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.Idle(faded = false),
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Failure.NotConfigured))

        // Idle is not Capturing / Thinking — the reducer drops the
        // stale outcome silently per the existing branch contract.
        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    // ----- (3) AutoFadeTimeout auto-flip after timer -----------------

    @Test
    fun `FailureState NoApiKey tooltipFaded false auto-flips to true after TOOLTIP_FADE_MS`() =
        runTest {
            val sm = BubbleStateMachine(
                initial = BubbleState.Capturing,
                coroutineScope = newScope(testScheduler),
            )

            sm.onEvent(BubbleEvent.VerificationOutcomeReceived(VerificationOutcome.Failure.NotConfigured))
            assertEquals(
                BubbleState.FailureState.NoApiKey(tooltipFaded = false),
                sm.state.value,
            )

            // Just before the timer — still not faded.
            advanceTimeBy(BubbleStateMachine.TOOLTIP_FADE_MS - 1)
            assertEquals(
                BubbleState.FailureState.NoApiKey(tooltipFaded = false),
                sm.state.value,
            )

            // Tick over — auto-fade fires.
            advanceTimeBy(2)
            assertEquals(
                BubbleState.FailureState.NoApiKey(tooltipFaded = true),
                sm.state.value,
            )
        }

    @Test
    fun `AutoFadeTimeout on NoApiKey tooltipFaded true is reducer no-op idempotency`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.FailureState.NoApiKey(tooltipFaded = true),
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.AutoFadeTimeout)

        // idempotent — true.copy(tooltipFaded = true) is a no-op.
        assertEquals(
            BubbleState.FailureState.NoApiKey(tooltipFaded = true),
            sm.state.value,
        )
    }

    // ----- (4) BackToIdle exit ---------------------------------------

    @Test
    fun `BackToIdle exits FailureState NoApiKey to Idle faded false`() = runTest {
        val sm = BubbleStateMachine(
            initial = BubbleState.FailureState.NoApiKey(tooltipFaded = false),
            coroutineScope = newScope(testScheduler),
        )

        sm.onEvent(BubbleEvent.BackToIdle)

        assertEquals(BubbleState.Idle(faded = false), sm.state.value)
    }

    // ----- (5) Constructor default invariant -------------------------

    @Test
    fun `FailureState NoApiKey default constructor sets tooltipFaded false`() {
        assertEquals(false, BubbleState.FailureState.NoApiKey().tooltipFaded)
    }
}
