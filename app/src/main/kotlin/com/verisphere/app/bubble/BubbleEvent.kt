package com.verisphere.app.bubble

/**
 * User-driven events that mutate [BubbleState] via
 * [BubbleStateMachine.onEvent] (architecture: AR21, D4.3).
 *
 * Story 1.7 ships only [UserActivity] and [AutoFadeTimeout] — the
 * long-press / capture / verdict / failure events are deferred to
 * Stories 1.8–1.10 and Epic 3.
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
}
