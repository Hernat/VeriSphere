package com.verisphere.app.bubble

/**
 * Sealed visual states of the bubble overlay (architecture: AR21, D4.3).
 *
 * Story 1.7 ships only [Idle] — the rest of the architecture's nine-state
 * machine (Pressing / Capturing / Thinking / Verdict / FailureState.*) is
 * deliberately deferred to Stories 1.8–1.10 and Epic 3. Adding placeholders
 * before they are wired triggers Detekt's unused-private-member rule and
 * leaves dead branches in [BubbleStateMachine.reduce].
 */
sealed interface BubbleState {

    /**
     * Persistent at-rest state.
     *
     * @param faded `true` once the 5-second inactivity timer has fired.
     *   Drives the alpha animation in [com.verisphere.app.bubble.ui.BubbleOverlay]
     *   between 1.0 (opaque) and 0.4 (faded). Resets on any user gesture.
     */
    data class Idle(val faded: Boolean) : BubbleState
}
