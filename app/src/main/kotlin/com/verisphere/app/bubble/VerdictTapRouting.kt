package com.verisphere.app.bubble

/**
 * Story 2.4 — Pure routing helper for `BubbleState.Verdict × Tap`.
 *
 * Reads the bubble's current state and routes to [onLaunchPanel] with the
 * verdict record's id when (and only when) the state is [BubbleState.Verdict].
 * For every other state, the tap is a no-op in this story:
 *  - `Idle × Tap` → Story 4.3 (history-open wiring), not yet shipped;
 *  - `Pressing / Capturing / Thinking × Tap` → ignored per UX-DR14;
 *  - `FailureState.* × Tap` → Story 3.3 (Epic 3) introduces the variants.
 *
 * **Critical invariant** — this helper does NOT dispatch any [BubbleEvent].
 * The state machine is read synchronously via `state.value` at the call site
 * and the bubble remains in `Verdict(record, tooltipFaded)` across the panel
 * open / close cycle (story spec AC #4 + Critical Dev Note #1).
 *
 * **Lambda-seam pattern** (6th application — Stories 1.5, 1.7, 2.1, 2.2, 2.3):
 *  - production wires `onLaunchPanel = ::launchDetailPanelActivity` in
 *    `BubbleOverlayService.onBubbleTap`;
 *  - JVM tests pass a capturing lambda and assert the captured session id
 *    matches the seeded `Verdict.record.id` (or assert no capture occurred for
 *    non-Verdict states).
 *
 * @param state The current [BubbleState] snapshot (read via `stateFlow.value`
 *              at the call site; this helper takes the value, not the flow).
 * @param onLaunchPanel Invoked with the [com.verisphere.app.storage.SessionRecord.id]
 *                      when the state is [BubbleState.Verdict].
 */
internal fun handleVerdictBubbleTap(
    state: BubbleState,
    onLaunchPanel: (sessionId: String) -> Unit,
) {
    if (state is BubbleState.Verdict) {
        onLaunchPanel(state.record.id)
    }
}
