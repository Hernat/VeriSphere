package com.verisphere.app.bubble

import com.verisphere.app.storage.SessionRecord

/**
 * Sealed visual states of the bubble overlay (architecture: AR21, D4.3).
 *
 * Story 1.10 ships the full happy-path machine: [Idle] → [Pressing] →
 * [Capturing] → [Thinking] → [Verdict] (and back to [Idle] on the next
 * user gesture in the source app). The failure-state variants
 * (`FailureState.Offline / Timeout / DailyLimit / QuotaExhausted /
 * PossibleInjection`) are deliberately deferred to Epic 3 (Story 3.3) per
 * architecture line 152 — Story 1.10 maps every `Failure.*` outcome
 * silently back to [Idle] so the happy path can ship cleanly without
 * blocking on Epic 3.
 *
 * **State semantics:**
 *  - [Idle.faded] — at-rest state with the 5 s adaptive-presence fade
 *    (Story 1.7). Reset to `false` on any [BubbleEvent.UserActivity].
 *  - [Pressing] — finger is held on the visible bubble; the 1 s long-press
 *    deadline has not yet fired. The bubble pulses (~400 ms scale-up).
 *  - [Capturing] — long-press completed; the suction animation plays for
 *    ~300 ms. State is timer-bounded — auto-transitions to [Thinking] via
 *    an internal coroutine in [BubbleStateMachine] (matches the Story 1.7
 *    fade-timer pattern).
 *  - [Thinking] — Gemini round-trip in flight. The thin ring loader
 *    rotates around the bubble at 60 fps until [BubbleEvent.VerificationOutcomeReceived].
 *  - [Verdict] — verdict received. The bubble adopts the verdict's
 *    semantic colour (UX Step 8 palette) and the [com.verisphere.app.bubble.ui.FlashTooltip]
 *    renders beside it. After 5–8 s the [tooltipFaded] flag flips via a
 *    second internal timer; the bubble retains its colour until the next
 *    [BubbleEvent.BackToIdle].
 */
sealed interface BubbleState {

    /**
     * Persistent at-rest state (Story 1.7).
     *
     * @param faded `true` once the 5-second inactivity timer has fired.
     *   Drives the alpha animation in [com.verisphere.app.bubble.ui.BubbleOverlay]
     *   between 1.0 (opaque) and 0.4 (faded). Resets on any user gesture.
     */
    data class Idle(val faded: Boolean) : BubbleState

    /**
     * Finger held on the visible bubble; the 1 s long-press deadline has
     * not yet fired (UX-DR5 pressing-pulse). The bubble pulses ~400 ms
     * scale 1.0 → 1.1 → 1.0 in [com.verisphere.app.bubble.ui.BubbleOverlay].
     *
     * Exits via:
     *  - [BubbleEvent.LongPressCompleted] → [Capturing] (1 s mark reached);
     *  - [BubbleEvent.BackToIdle] → [Idle] (press cancelled before 1 s).
     */
    data object Pressing : BubbleState

    /**
     * Long-press completed; the [com.verisphere.app.bubble.ui.SuctionAnimation]
     * plays for ~300 ms (UX-DR7). Timer-bounded — [BubbleStateMachine]
     * auto-transitions to [Thinking] via an internal coroutine after
     * [BubbleStateMachine.SUCTION_ANIMATION_MS]. Do NOT keep the state
     * machine in [Capturing] for longer than the suction animation; if a
     * Gemini call returns faster than 300 ms (rare under NFR1's 2 s P95
     * target) the [Thinking] state still appears for the residual ~100 ms
     * before [Verdict] takes over — which is the correct UX (the suction
     * animation must not be cut short).
     */
    data object Capturing : BubbleState

    /**
     * Gemini round-trip in flight (UX-DR5 thinking-ring). The
     * [com.verisphere.app.bubble.ui.ThinkingRing] composable rotates a
     * 90° arc around the bubble at 60 fps.
     */
    data object Thinking : BubbleState

    /**
     * Verdict received. The bubble adopts the verdict's semantic colour
     * from [com.verisphere.app.gemini.VerdictLabel] (UX Step 8 palette)
     * and the [com.verisphere.app.bubble.ui.FlashTooltip] renders beside
     * it.
     *
     * @param record the [SessionRecord] persisted to [com.verisphere.app.storage.HistoryRepository]
     *   BEFORE this state was emitted (architecture NFR16 + Story 1.10
     *   Critical Dev Note #6 — observers can never see a verdict that
     *   was not persisted).
     * @param tooltipFaded `false` initially; flips to `true` after the
     *   5–8 s tooltip-fade timer fires (UX-DR6 line 679). The bubble
     *   retains its semantic colour in either case — only the tooltip
     *   text fades. The bubble returns to [Idle] on the next
     *   [BubbleEvent.BackToIdle] (typically the user's next gesture in
     *   the source app, observed via [BubbleEvent.UserActivity]).
     */
    data class Verdict(
        val record: SessionRecord,
        val tooltipFaded: Boolean = false,
    ) : BubbleState
}
