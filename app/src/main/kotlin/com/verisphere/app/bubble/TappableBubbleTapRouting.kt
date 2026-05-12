package com.verisphere.app.bubble

/**
 * Story 2.4 (+ Story 3.3) — Pure routing helper for tappable
 * [BubbleState] variants.
 *
 * Reads the bubble's current state and routes to [onLaunchPanel] with the
 * record's id when (and only when) the state is a "tappable" variant:
 *  - [BubbleState.Verdict] — opens the verdict detail panel (Story 2.4).
 *  - [BubbleState.FailureState.PossibleInjection] — opens the detail
 *    panel showing the OCR text so the user can inspect the injection
 *    attempt (Story 3.3; PRD FR8 + NFR8 self-revealing posture).
 *
 * For every other state, the tap is a no-op in V1:
 *  - `Idle × Tap` → Story 4.3 (history-open wiring), not yet shipped;
 *  - `Pressing / Capturing / Thinking × Tap` → ignored per UX-DR14;
 *  - `FailureState.Offline / Timeout / DailyLimit / QuotaExhausted × Tap`
 *    → no-op (no SessionRecord to expand; the user re-triggers via
 *    long-press).
 *
 * **Critical invariant** — this helper does NOT dispatch any [BubbleEvent].
 * The state machine is read synchronously via `state.value` at the call site
 * and the bubble remains in the source state across the panel open / close
 * cycle (story spec AC #4 + Critical Dev Note #1).
 *
 * **Lambda-seam pattern** (7th application — Stories 1.5, 1.7, 2.1, 2.2,
 * 2.3, 2.4):
 *  - production wires `onLaunchPanel = ::launchDetailPanelActivity` in
 *    `BubbleOverlayService.onBubbleTap`;
 *  - JVM tests pass a capturing lambda and assert the captured session id
 *    matches the seeded `record.id` (or assert no capture occurred for
 *    non-tappable states).
 *
 * @param state The current [BubbleState] snapshot (read via `stateFlow.value`
 *              at the call site; this helper takes the value, not the flow).
 * @param onLaunchPanel Invoked with the
 *                      [com.verisphere.app.storage.SessionRecord.id] when
 *                      the state is a tappable variant.
 */
internal fun handleTappableBubbleTap(
    state: BubbleState,
    onLaunchPanel: (sessionId: String) -> Unit,
) {
    when (state) {
        is BubbleState.Verdict -> onLaunchPanel(state.record.id)
        is BubbleState.FailureState.PossibleInjection -> onLaunchPanel(state.record.id)
        else -> Unit
    }
}
