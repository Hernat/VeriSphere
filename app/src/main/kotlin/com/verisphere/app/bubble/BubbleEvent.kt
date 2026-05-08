package com.verisphere.app.bubble

import com.verisphere.app.gemini.VerificationOutcome

/**
 * User-driven events that mutate [BubbleState] via
 * [BubbleStateMachine.onEvent] (architecture: AR21, D4.3).
 *
 * Story 1.10 ships the full happy-path event set:
 *  - [UserActivity] / [AutoFadeTimeout] inherited from Story 1.7 — the
 *    fade-timer cycle still applies to [BubbleState.Idle]. [AutoFadeTimeout]
 *    is also reused to flip [BubbleState.Verdict.tooltipFaded] (Critical
 *    Dev Note #1 in the story file — single event, two reducer branches).
 *  - [LongPressStarted] — touch-down on the visible bubble. Replaces
 *    the no-op signal Story 1.7 / 1.8 used here.
 *  - [LongPressCompleted] — 1 s deadline reached, finger still down on
 *    the bubble. Story 1.10 changes the semantics from no-op to a real
 *    transition `Pressing → Capturing`.
 *  - [VerificationOutcomeReceived] — the [com.verisphere.app.capture.CapturePipeline]
 *    returned an outcome (verdict or failure). The reducer maps Verdict
 *    to [BubbleState.Verdict] and any [VerificationOutcome.Failure] back
 *    to [BubbleState.Idle] (Epic 3 / Story 3.3 will introduce the
 *    failure-state UX variants).
 *  - [BackToIdle] — explicit "user gesture in source app" or "press
 *    cancelled before 1 s" reset event. Drives the only path back to
 *    [BubbleState.Idle] from [BubbleState.Pressing] / [BubbleState.Verdict].
 */
sealed interface BubbleEvent {

    /**
     * Any user gesture (tap, drag delta, tap-near-miss in the 24 dp halo).
     * Resets the inactivity timer and clears [BubbleState.Idle.faded].
     */
    data object UserActivity : BubbleEvent

    /**
     * Story 1.7 + 1.10 — fired by the inactivity timer 5 s after the
     * last [UserActivity] AND by the tooltip-fade timer 5–8 s after a
     * [BubbleState.Verdict] was emitted. Reducer behaviour depends on
     * current state:
     *  - on [BubbleState.Idle] → sets `faded = true` (idempotent);
     *  - on [BubbleState.Verdict] → sets `tooltipFaded = true` (the
     *    bubble keeps its semantic colour);
     *  - on any other state → no-op (a stale timer cannot rewrite a
     *    transient state).
     */
    data object AutoFadeTimeout : BubbleEvent

    /**
     * Story 1.10 — touch-down on the visible 56 dp bubble (UX-DR5 +
     * UX-DR14 `Idle × Long-press` + `Verdict × Long-press` re-trigger).
     * Transitions [BubbleState.Idle] / [BubbleState.Verdict] →
     * [BubbleState.Pressing].
     *
     * Distinct from [UserActivity] (which fires on EVERY touch-down,
     * including drag and halo-tap). [LongPressStarted] fires only when
     * the touch landed inside the visible 56 dp circle — the
     * [com.verisphere.app.bubble.ui.BubbleOverlay] gesture handler tracks
     * `downOnBubble` and routes accordingly.
     */
    data object LongPressStarted : BubbleEvent

    /**
     * Story 1.10 — emitted by [com.verisphere.app.bubble.ui.BubbleOverlay]
     * when a finger has been held on the visible bubble for ≥ 1 s without
     * exceeding the 4 dp drag threshold (PRD FR3, UX-DR5, UX-DR14
     * `Idle × Long-press`). Transitions [BubbleState.Pressing] →
     * [BubbleState.Capturing]; the reducer launches an internal 300 ms
     * timer that auto-fires the [BubbleState.Capturing] → [BubbleState.Thinking]
     * transition (matches the Story 1.7 fade-timer pattern).
     *
     * Story 1.7 + 1.8 + 1.8.5 used this as a no-op signal — Story 1.10
     * upgrades the semantics to a real transition. The same event still
     * triggers the service's capture pipeline launch via
     * [com.verisphere.app.bubble.BubbleOverlayService.onBubbleLongPress].
     */
    data object LongPressCompleted : BubbleEvent

    /**
     * Story 1.10 — the [com.verisphere.app.capture.CapturePipeline] has
     * returned. Reducer maps:
     *  - [VerificationOutcome.Verdict] → [BubbleState.Verdict] (record
     *    must already have been persisted by the service via
     *    [com.verisphere.app.storage.HistoryRepository.append] — Critical
     *    Dev Note #6);
     *  - [VerificationOutcome.Failure] → [BubbleState.Idle] (failure-state
     *    UX is Story 3.3).
     */
    data class VerificationOutcomeReceived(val outcome: VerificationOutcome) : BubbleEvent

    /**
     * Story 1.10 — explicit "return to idle" trigger. Sources:
     *  - press cancelled before the 1 s long-press deadline (finger
     *    released early on the visible bubble); the gesture handler
     *    emits this from [com.verisphere.app.bubble.ui.BubbleOverlay]
     *    when in [BubbleState.Pressing];
     *  - tap-near-miss in the bubble's 24 dp halo while in
     *    [BubbleState.Verdict] — observable as a "next user gesture
     *    near the bubble" and routed by the service in
     *    [com.verisphere.app.bubble.BubbleOverlayService.onBubbleTapNearMiss].
     *
     * Note: source-app gestures OUTSIDE the bubble's halo window are
     * NOT observable (the overlay window uses
     * [android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL] so
     * those touches dispatch directly to the underlying app). Stories
     * post-1.10 that need a global "any user gesture dismisses the
     * verdict" semantic will require a separate signal source (e.g. an
     * `AppOpsManager` listener) — out of scope for V1.
     *
     * Reducer always returns [BubbleState.Idle] with `faded = false`;
     * the side-effect block in [BubbleStateMachine.handleTransitionSideEffects]
     * arms the 5 s adaptive-presence fade timer on this transition (code
     * review patch P1).
     */
    data object BackToIdle : BubbleEvent
}
