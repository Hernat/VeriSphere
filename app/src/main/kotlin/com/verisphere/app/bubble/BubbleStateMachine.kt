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
 *
 * **Story 3.4 reduce-motion** — when [reduceMotionEnabled] is `true`,
 * the [BubbleEvent.LongPressCompleted] → [BubbleState.Capturing] →
 * [BubbleState.Thinking] transition skips the 300 ms suction timer
 * (`Capturing` is emitted, then on the **next dispatcher tick**
 * `Thinking` is emitted without any virtual-time delay — the `delay`
 * + `ensureActive` pair inside the launched coroutine is skipped). On a
 * `StandardTestDispatcher` `runCurrent()` drains both transitions in
 * one drain pass; on production `Dispatchers.Main` the gap is whatever
 * the dispatcher schedules between two consecutive tasks. The flag is
 * read once on service `onCreate` (see [com.verisphere.app.util.isReduceMotionEnabled])
 * and never mutates for the life of this state machine instance —
 * propagating a mid-session toggle requires restarting the service.
 * The flag is exposed as a public `val` so the bubble Composables can
 * read it via the same `bubbleStateMachine` reference the service
 * already publishes (no separate CompositionLocal, no AppContainer
 * field).
 */
class BubbleStateMachine(
    initial: BubbleState = BubbleState.Idle(faded = false),
    private val coroutineScope: CoroutineScope = kotlinx.coroutines.MainScope(),
    val reduceMotionEnabled: Boolean = false,
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
     * Story 3.3 — the [BubbleEvent.VerificationOutcomeReceived] branch
     * now diverges per `Failure.*` variant via [mapFailureToState] (vs
     * Story 1.10's blanket `Failure.* → Idle` minimal mapping). The
     * `AutoFadeTimeout`, `LongPressStarted` and `BackToIdle` branches
     * are also extended to cover every [BubbleState.FailureState.*]
     * variant uniformly with the existing [BubbleState.Verdict] path.
     *
     * The `else -> current` branches are intentional: stale timers and
     * out-of-grammar events leave the state unchanged rather than crash.
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
            // Story 3.3 + Story 7.5 C1 — six distinct `is` branches because
            // `data class.copy()` is per-class; sealed-interface smart cast
            // does not unify copy().
            is BubbleState.FailureState.Offline -> current.copy(tooltipFaded = true)
            is BubbleState.FailureState.Timeout -> current.copy(tooltipFaded = true)
            is BubbleState.FailureState.DailyLimit -> current.copy(tooltipFaded = true)
            is BubbleState.FailureState.QuotaExhausted -> current.copy(tooltipFaded = true)
            is BubbleState.FailureState.PossibleInjection -> current.copy(tooltipFaded = true)
            is BubbleState.FailureState.NotFound -> current.copy(tooltipFaded = true)
            // Stale timer from a previous Idle / Verdict / FailureState
            // period: a transient state (Pressing / Capturing / Thinking)
            // MUST NOT be flipped back to faded-Idle by a stale fade timer.
            else -> current
        }

        BubbleEvent.LongPressStarted -> when (current) {
            // UX-DR14: `Idle × Long-press` (initiate) and `Verdict × Long-press`
            // (re-trigger — previous record stays in history). Story 3.3
            // extends the re-trigger grammar to every FailureState (the
            // long-press is the user's recovery affordance for Offline /
            // Timeout / DailyLimit / QuotaExhausted / PossibleInjection).
            is BubbleState.Idle,
            is BubbleState.Verdict,
            is BubbleState.FailureState -> BubbleState.Pressing
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
        // funnels through [mapFailureToState] which routes silent-bucket
        // failures (PermissionDenied, CaptureFailed, HttpError,
        // MalformedResponse) to Idle and user-actionable failures
        // (Offline, Timeout, DailyLimitReached, ApiQuotaExhausted) to
        // their FailureState variant. See
        // [com.verisphere.app.capture.CapturePipeline] class KDoc for
        // the full three-branch seam documentation.
        is BubbleEvent.VerificationOutcomeReceived -> when (current) {
            BubbleState.Thinking, BubbleState.Capturing -> when (val outcome = event.outcome) {
                is VerificationOutcome.Verdict ->
                    // Story 3.3 — `injectionDetected = true` is still a
                    // successful verdict (record was persisted to history
                    // by BubbleOverlayService.runCaptureAndDispatch BEFORE
                    // this event fired — Story 1.10 Critical Dev Note #6
                    // invariant). The reducer redirects to
                    // FailureState.PossibleInjection so the user sees the
                    // amber warning flash and can tap to inspect OCR.
                    if (outcome.record.injectionDetected) {
                        BubbleState.FailureState.PossibleInjection(record = outcome.record)
                    } else {
                        BubbleState.Verdict(record = outcome.record)
                    }
                is VerificationOutcome.Failure -> mapFailureToState(outcome)
            }
            // Outcome arriving in any other state is a bug in the caller;
            // drop silently rather than rewrite the state.
            else -> current
        }

        BubbleEvent.BackToIdle -> when (current) {
            // Story 3.3 — halo-tap / source-app-gesture dismissal from a
            // FailureState routes to Idle the same way as from Verdict.
            // The 5 s adaptive-presence fade timer is re-armed by
            // [handleTransitionSideEffects] via the `enteredIdleNotFaded`
            // predicate (code-review patch P1 regression coverage from
            // Story 1.10 applies identically here). Story 7.5 C1 — the
            // smart-cast `is BubbleState.FailureState` catch-all transitively
            // covers the new NotFound variant — no enumeration needed.
            BubbleState.Pressing,
            is BubbleState.Verdict,
            is BubbleState.FailureState -> BubbleState.Idle(faded = false)
            // BackToIdle from Idle / Capturing / Thinking is a no-op —
            // Capturing / Thinking should run to completion on their own
            // timers; Idle is already idle.
            else -> current
        }

        // Story 7.5 C1 — cross-process trigger from MainActivity's
        // null-getById branches (FIFO eviction / stale-tap race). Only
        // Idle transitions to NotFound; every other state is a no-op
        // because a stale signal cannot rewrite a transient state.
        BubbleEvent.HistoryRecordNotFound -> when (current) {
            is BubbleState.Idle -> BubbleState.FailureState.NotFound()
            else -> current
        }
    }

    /**
     * Story 3.3 — maps a [VerificationOutcome.Failure] to its corresponding
     * [BubbleState.FailureState] variant OR to [BubbleState.Idle] for the
     * silent-bucket failures (UX-DR15 — operator/dev failure categories that
     * are not user-actionable). Pure function; called from [reduce] only.
     */
    private fun mapFailureToState(failure: VerificationOutcome.Failure): BubbleState = when (failure) {
        VerificationOutcome.Failure.Offline -> BubbleState.FailureState.Offline()
        VerificationOutcome.Failure.Timeout -> BubbleState.FailureState.Timeout()
        VerificationOutcome.Failure.DailyLimitReached -> BubbleState.FailureState.DailyLimit()
        VerificationOutcome.Failure.ApiQuotaExhausted -> BubbleState.FailureState.QuotaExhausted()
        // Silent buckets — UX-DR15. Returning Idle(faded=false) preserves
        // the Story 1.10 minimal-mapping contract for these branches AND
        // keeps the existing adaptive-presence fade re-arm side-effect
        // (handleTransitionSideEffects `enteredIdleNotFaded`).
        VerificationOutcome.Failure.PermissionDenied,
        VerificationOutcome.Failure.CaptureFailed,
        VerificationOutcome.Failure.MalformedResponse,
        is VerificationOutcome.Failure.HttpError -> BubbleState.Idle(faded = false)
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
        //
        // Story 3.4 — when [reduceMotionEnabled] is `true`, the 300 ms
        // delay (and its accompanying `ensureActive` cancellation check)
        // are skipped; the `_state.update` runs as soon as the launched
        // coroutine is scheduled (next dispatcher tick — NOT the same
        // tick as `onEvent`). The `if (current == BubbleState.Capturing)`
        // guard remains so a fast outcome that beat the dispatch (e.g. a
        // cached Gemini response or a debug stub returning instantly) is
        // not overwritten by a stale Thinking emission.
        if (next == BubbleState.Capturing && previous != BubbleState.Capturing) {
            suctionJob?.cancel()
            suctionJob = coroutineScope.launch {
                if (!reduceMotionEnabled) {
                    delay(SUCTION_ANIMATION_MS)
                    ensureActive()
                }
                _state.update { current ->
                    if (current == BubbleState.Capturing) BubbleState.Thinking else current
                }
            }
        }

        // Verdict / FailureState tooltip-fade timer (Story 1.10 + Story 3.3).
        // Arm on entering a fresh Verdict OR a fresh FailureState with
        // tooltipFaded=false. The per-record-id check on Verdict and
        // PossibleInjection prevents back-to-back states with the SAME
        // record id from re-arming (a tooltipFaded=false → true copy()
        // re-emission must NOT re-arm the timer). The other four
        // FailureState variants carry no record, so a simple
        // type-discriminator check is sufficient.
        val enteredFreshTooltip = when (next) {
            is BubbleState.Verdict ->
                !next.tooltipFaded &&
                    (previous !is BubbleState.Verdict || previous.record.id != next.record.id)
            is BubbleState.FailureState.Offline ->
                !next.tooltipFaded && previous !is BubbleState.FailureState.Offline
            is BubbleState.FailureState.Timeout ->
                !next.tooltipFaded && previous !is BubbleState.FailureState.Timeout
            is BubbleState.FailureState.DailyLimit ->
                !next.tooltipFaded && previous !is BubbleState.FailureState.DailyLimit
            is BubbleState.FailureState.QuotaExhausted ->
                !next.tooltipFaded && previous !is BubbleState.FailureState.QuotaExhausted
            is BubbleState.FailureState.PossibleInjection ->
                !next.tooltipFaded &&
                    (previous !is BubbleState.FailureState.PossibleInjection ||
                        previous.record.id != next.record.id)
            // Story 7.5 C1 — NotFound carries no record, so a simple
            // type-discriminator suffices (mirrors Offline / Timeout /
            // DailyLimit / QuotaExhausted pattern).
            is BubbleState.FailureState.NotFound ->
                !next.tooltipFaded && previous !is BubbleState.FailureState.NotFound
            else -> false
        }
        if (enteredFreshTooltip) {
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
        // Story 3.3 — extend the tooltip-fade cancel to also fire when
        // leaving any FailureState (e.g. BackToIdle from FailureState.Offline
        // must cancel the pending tooltip-fade timer for that variant).
        val leavingTooltipState = (previous is BubbleState.Verdict && next !is BubbleState.Verdict) ||
            (previous is BubbleState.FailureState && next !is BubbleState.FailureState)
        if (leavingTooltipState) {
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
