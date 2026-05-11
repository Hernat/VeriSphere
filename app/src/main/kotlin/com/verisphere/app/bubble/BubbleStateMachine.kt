package com.verisphere.app.bubble

import com.verisphere.app.gemini.VerificationOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single owner of the bubble's visual [BubbleState] (architecture:
 * AR21, D4.2, D4.3). POKO — no Android type dependencies, runs on the
 * JVM under unit tests.
 *
 * Reducer shape (architecture lines 540–548, "Pattern Examples"):
 *   - [onEvent] is the only mutation entry point.
 *   - State transitions go through `_state.update { current -> reduce(current, event) }`
 *     so the StateFlow contract holds (no `var`, no `_state.value =`).
 *   - [reduce] is a pure `(current, event) -> next` function — no side
 *     effects. Side effects (timer launches) live in [onEvent] alongside
 *     the update call.
 *
 * **Story 1.10 timers** — three independent coroutine [Job]s, each
 * cancelled before re-arming and disposed cleanly via [dispose]:
 *   1. [fadeJob] — Story 1.7 inactivity fade (5 s after [BubbleEvent.UserActivity]).
 *   2. [suctionJob] — Story 1.10 capture-animation timer (300 ms after
 *      [BubbleEvent.LongPressCompleted]). Auto-transitions [BubbleState.Capturing]
 *      → [BubbleState.Thinking] without an external event — matches the
 *      AC #3 transition table while keeping the public event surface
 *      minimal (Critical Dev Note #2).
 *   3. [tooltipFadeJob] — Story 1.10 verdict-tooltip fade timer (6 s
 *      after [BubbleState.Verdict] is entered, midpoint of UX-DR6's 5–8 s
 *      range). Fires [BubbleEvent.AutoFadeTimeout] which the reducer
 *      maps to `Verdict.copy(tooltipFaded = true)` — the bubble retains
 *      its semantic colour.
 *
 * Adaptive presence (UX-DR5): the inactivity timer lives here — not in
 * the composable — because [BubbleOverlay][com.verisphere.app.bubble.ui.BubbleOverlay]
 * is a stateless consumer per the architecture's State-boundary section
 * (lines 762–764). Each [BubbleEvent.UserActivity] cancels the previous
 * timer Job FIRST (per Story 1.7 race-close), then updates state, then
 * launches a new timer. The launch's body re-checks cancellation via
 * [ensureActive] AFTER `delay()` returns — closing the race where a
 * timer's continuation has already been scheduled and a fresh
 * [BubbleEvent.UserActivity] fires before the scheduled continuation runs.
 *
 * The [coroutineScope] parameter has two purposes: production wiring
 * (the service injects `serviceScope` so timers die with the service)
 * AND test seam (tests inject a `TestScope` whose virtual clock is
 * driven by `advanceTimeBy`). The service ALWAYS passes its own scope;
 * the `MainScope()` default is for tests / quick callers and is
 * documented as a leaky default in deferred-work.md (V2 will make the
 * parameter required).
 */
class BubbleStateMachine(
    initial: BubbleState = BubbleState.Idle(faded = false),
    private val coroutineScope: CoroutineScope = kotlinx.coroutines.MainScope(),
) {

    private val _state: MutableStateFlow<BubbleState> = MutableStateFlow(initial)

    val state: StateFlow<BubbleState> = _state.asStateFlow()

    private var fadeJob: Job? = null
    private var suctionJob: Job? = null
    private var tooltipFadeJob: Job? = null

    fun onEvent(event: BubbleEvent) {
        // Cancel BEFORE updating state (Story 1.7 race-close): prevents
        // a stale timer's already-scheduled continuation from overwriting
        // the fresh state produced by reduce().
        if (event is BubbleEvent.UserActivity) {
            fadeJob?.cancel()
        }

        val previous = _state.value
        _state.update { current -> reduce(current, event) }
        val next = _state.value

        // Side effects driven by transitions (NOT by events alone): we
        // diff `previous → next` so that idempotent events (e.g. a stale
        // AutoFadeTimeout) do not re-arm a timer.
        handleTransitionSideEffects(previous, next, event)
    }

    /**
     * Cancels every active timer Job. The service calls this between
     * `ON_PAUSE` and `ON_STOP` in `onDestroy` so timer Jobs are severed
     * before `serviceScope` itself is cancelled. Cancelling
     * `serviceScope` would cancel the timers too, but explicit dispose
     * keeps lifecycle ordering symmetric and survives a future migration
     * off `serviceScope`.
     */
    fun dispose() {
        fadeJob?.cancel()
        fadeJob = null
        suctionJob?.cancel()
        suctionJob = null
        tooltipFadeJob?.cancel()
        tooltipFadeJob = null
    }

    /**
     * Pure state transition function. Side-effect-free, exhaustive on
     * [BubbleEvent], usable from `_state.update { reduce(it, event) }`.
     *
     * Story 1.10 transition table — see the story file's AC #3 for the
     * full matrix. The `else -> current` branches are intentional:
     * stale timers and out-of-grammar events leave the state unchanged
     * rather than crash.
     */
    @Suppress("CyclomaticComplexMethod") // pure reducer — extracting helpers fragments the table across functions
    private fun reduce(current: BubbleState, event: BubbleEvent): BubbleState = when (event) {
        BubbleEvent.UserActivity -> when (current) {
            is BubbleState.Idle -> BubbleState.Idle(faded = false)
            // Idle clears the fade flag; non-Idle states ignore UserActivity
            // (they own their own dwell semantics — Pressing / Capturing /
            // Thinking are transient; Verdict's BackToIdle is a separate
            // explicit event).
            else -> current
        }

        BubbleEvent.AutoFadeTimeout -> when (current) {
            is BubbleState.Idle -> current.copy(faded = true)
            is BubbleState.Verdict -> current.copy(tooltipFaded = true)
            // Stale timer from a previous Idle / Verdict period: a
            // transient state (Pressing / Capturing / Thinking) MUST NOT
            // be flipped back to faded-Idle by a stale fade timer.
            else -> current
        }

        BubbleEvent.LongPressStarted -> when (current) {
            // UX-DR14: `Idle × Long-press` and `Verdict × Long-press`
            // both transition to Pressing (the latter is the "re-trigger
            // capture" path — previous record stays in history).
            is BubbleState.Idle, is BubbleState.Verdict -> BubbleState.Pressing
            // Already pressing or in mid-pipeline — ignore the duplicate
            // touch-down (the gesture handler shouldn't fire twice in
            // practice; this is a defensive no-op).
            else -> current
        }

        BubbleEvent.LongPressCompleted -> when (current) {
            BubbleState.Pressing -> BubbleState.Capturing
            // Long-press fired without a preceding Pressing: shouldn't
            // happen in the gesture grammar (the bubble overlay always
            // emits LongPressStarted on touch-down before the 1 s deadline)
            // but defend against synthetic instrumented gestures by
            // promoting Idle directly to Capturing rather than dropping.
            is BubbleState.Idle -> BubbleState.Capturing
            else -> current
        }

        // Story 3.1 — the Accessibility-revoked seam (architecture
        // validation Gap #1, amended post-Sprint-Change-2026-05-07)
        // surfaces here as Failure.PermissionDenied. The OS Settings
        // screen is an OS surface, not a BubbleState; the typed Failure
        // funnels through the same minimal-mapping branch below. See
        // [com.verisphere.app.capture.CapturePipeline] class KDoc
        // for the full three-branch seam documentation.
        is BubbleEvent.VerificationOutcomeReceived -> when (current) {
            BubbleState.Thinking, BubbleState.Capturing -> when (val outcome = event.outcome) {
                is VerificationOutcome.Verdict -> BubbleState.Verdict(record = outcome.record)
                // Story 1.10 minimal mapping — Story 3.3 (Epic 3) introduces
                // BubbleState.FailureState.* variants. Until then, every
                // Failure.* returns silently to Idle so the pipeline
                // integrity is preserved (no crash, no fake verdict, no
                // infinite spinner). Story 3.1 added the cosmetic
                // Failure.Timeout mapping in CapturePipeline; the
                // minimal mapping here is unchanged (Story 3.3 will
                // diverge per-variant).
                is VerificationOutcome.Failure -> BubbleState.Idle(faded = false)
            }
            // Outcome arriving in any other state is a bug in the caller;
            // drop silently rather than rewrite the state.
            else -> current
        }

        BubbleEvent.BackToIdle -> when (current) {
            BubbleState.Pressing, is BubbleState.Verdict -> BubbleState.Idle(faded = false)
            // BackToIdle from Idle / Capturing / Thinking is a no-op —
            // Capturing / Thinking should run to completion on their own
            // timers; Idle is already idle.
            else -> current
        }
    }

    /**
     * Launch / cancel the timer Jobs that the new state needs. Called
     * AFTER the reducer mutates `_state` so we have the canonical
     * `previous → next` pair.
     */
    private fun handleTransitionSideEffects(
        previous: BubbleState,
        next: BubbleState,
        event: BubbleEvent,
    ) {
        // Adaptive-presence fade timer (Story 1.7 + 1.10 review patch P1).
        // Arm whenever we settle into Idle(faded=false). Sources:
        //   - UserActivity from any state → fresh interaction.
        //   - BackToIdle from Pressing / Verdict → re-trigger / dismissal.
        //   - VerificationOutcomeReceived(Failure) from Thinking → silent
        //     failure return (Story 1.10 minimal mapping; Epic 3 wires the
        //     proper failure-state UX).
        // Without arming on the latter two paths, the bubble would stay at
        // alpha=1.0 indefinitely after a verdict dismissal or a failed
        // verification — adaptive presence (UX-DR5) silently broken.
        //
        // Code-review patch P12 — cancel any in-flight fadeJob on the
        // direct Idle → non-Idle transition (e.g. Idle × LongPressCompleted
        // → Capturing for synthetic gestures). Without this cancel, a
        // stale AutoFadeTimeout fires in a transient state; reducer
        // drops it but the coroutine slot leaks until process death.
        if (previous is BubbleState.Idle && next !is BubbleState.Idle) {
            fadeJob?.cancel()
            fadeJob = null
        }
        val enteredIdleNotFaded = next is BubbleState.Idle && !next.faded &&
            (previous !is BubbleState.Idle || previous.faded)
        if (event is BubbleEvent.UserActivity && next is BubbleState.Idle && !next.faded) {
            fadeJob?.cancel()
            fadeJob = coroutineScope.launch {
                delay(FADE_DELAY_MS)
                ensureActive()
                onEvent(BubbleEvent.AutoFadeTimeout)
            }
        } else if (enteredIdleNotFaded) {
            // Code-review patch P1 — arm on any non-UserActivity transition
            // that lands in Idle(faded=false): BackToIdle from Pressing or
            // Verdict, Failure outcome from Thinking, etc.
            fadeJob?.cancel()
            fadeJob = coroutineScope.launch {
                delay(FADE_DELAY_MS)
                ensureActive()
                onEvent(BubbleEvent.AutoFadeTimeout)
            }
        }

        // Capture-animation timer (Story 1.10).
        // Arm on entering Capturing (regardless of how — LongPressCompleted
        // is the canonical path, but a future direct-promotion path
        // would also benefit). Cancel any in-flight suction job before
        // re-arming so a re-entry (e.g. Verdict × Long-press → Pressing
        // → Capturing during a previous suction) supersedes cleanly.
        if (next == BubbleState.Capturing && previous != BubbleState.Capturing) {
            suctionJob?.cancel()
            suctionJob = coroutineScope.launch {
                delay(SUCTION_ANIMATION_MS)
                ensureActive()
                _state.update { current ->
                    if (current == BubbleState.Capturing) BubbleState.Thinking else current
                }
            }
        }

        // Verdict-tooltip fade timer (Story 1.10).
        // Arm on entering Verdict with tooltipFaded=false. Cancel any
        // in-flight tooltip-fade job before re-arming so back-to-back
        // verdicts (re-trigger flow: Verdict × Long-press → ... → new
        // Verdict) reset the timer cleanly.
        val enteredFreshVerdict = next is BubbleState.Verdict && !next.tooltipFaded &&
            (previous !is BubbleState.Verdict || previous.record.id != next.record.id)
        if (enteredFreshVerdict) {
            tooltipFadeJob?.cancel()
            tooltipFadeJob = coroutineScope.launch {
                delay(TOOLTIP_FADE_MS)
                ensureActive()
                onEvent(BubbleEvent.AutoFadeTimeout)
            }
        }

        // Cancel timers that no longer apply when leaving their owning state.
        if (previous == BubbleState.Capturing && next != BubbleState.Capturing) {
            // Already cancelled implicitly when we re-armed for a new
            // Capturing entry, but on transitions out of Capturing
            // (e.g. an early outcome arriving before the suction timer
            // fired), explicit cancel ensures the stale timer cannot
            // overwrite the new state.
            suctionJob?.cancel()
        }
        if (previous is BubbleState.Verdict && next !is BubbleState.Verdict) {
            tooltipFadeJob?.cancel()
        }
    }

    companion object {
        const val FADE_DELAY_MS: Long = 5_000L

        /** UX-DR7 — suction animation duration (~300 ms). */
        const val SUCTION_ANIMATION_MS: Long = 300L

        /** UX-DR6 — tooltip text fade after 5–8 s; midpoint of the range. */
        const val TOOLTIP_FADE_MS: Long = 6_000L
    }
}
