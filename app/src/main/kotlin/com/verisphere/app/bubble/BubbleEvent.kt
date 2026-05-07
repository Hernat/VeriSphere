package com.verisphere.app.bubble

/**
 * User-driven events that mutate [BubbleState] via
 * [BubbleStateMachine.onEvent] (architecture: AR21, D4.3).
 *
 * Story 1.7 shipped [UserActivity] + [AutoFadeTimeout]; Story 1.8 adds
 * [LongPressCompleted] as a forward-compat signal (no state change in
 * 1.8 — the reducer's branch is a deliberate no-op). Story 1.10 introduces
 * the `Pressing` / `Capturing` / `Thinking` / `Verdict` states and replaces
 * the no-op with a real transition. Verdict / failure events also land in
 * Story 1.10 + Epic 3.
 */
sealed interface BubbleEvent {

    /**
     * Any user gesture (tap, drag delta, tap-near-miss in the 24 dp halo).
     * Resets the inactivity timer and clears [BubbleState.Idle.faded].
     */
    data object UserActivity : BubbleEvent

    /**
     * Fired by the inactivity timer 5 s after the last [UserActivity].
     * Sets [BubbleState.Idle.faded] to `true`. Idempotent — firing while
     * already faded is a no-op.
     */
    data object AutoFadeTimeout : BubbleEvent

    /**
     * Emitted by [com.verisphere.app.bubble.ui.BubbleOverlay] when a finger
     * has been held on the visible bubble for ≥ 1 s without exceeding the
     * 4 dp drag threshold (PRD FR3, UX-DR5, UX-DR14 `Idle × Long-press`).
     *
     * Story 1.8 + 1.8.5 map this to `current` (no state change) —
     * long-press is a *signal* the service consumes to start the
     * [com.verisphere.app.capture.CapturePipeline]. Story 1.10 will
     * introduce the `Pressing` / `Capturing` states and replace the
     * no-op branch with the real transition.
     */
    data object LongPressCompleted : BubbleEvent
}
