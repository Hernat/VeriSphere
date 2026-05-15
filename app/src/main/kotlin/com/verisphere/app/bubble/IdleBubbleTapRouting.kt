package com.verisphere.app.bubble

/**
 * Story 4.3 — Pure routing helper for `Idle × Tap → open history`
 * (UX-DR14 gesture grammar; epics line 758).
 *
 * Reads a **pre-touch-down snapshot** of the bubble's [BubbleState] and
 * routes to [onLaunchHistory] when (and only when) the snapshot is
 * [BubbleState.Idle] — faded or opaque per epics AC #2.
 *
 * For every other source state (`Verdict`, `FailureState.*`, `Capturing`,
 * `Thinking`, `null`), the tap is a no-op:
 *  - `Verdict × Tap` and `FailureState.PossibleInjection × Tap` are
 *    handled by [handleTappableBubbleTap] (sibling helper) which opens
 *    the detail panel.
 *  - `FailureState.{Offline|Timeout|DailyLimit|QuotaExhausted} × Tap`
 *    silently dismiss the failure flash (no panel, no history) — the
 *    bubble returns to Idle via the caller's `BubbleEvent.BackToIdle`
 *    dispatch.
 *  - `Capturing` / `Thinking` taps are gesture-handler-suppressed in
 *    practice (timer-bounded, user can't physically tap mid-suction
 *    ~300 ms or mid-thinking ~1–2 s); covered defensively for the
 *    snapshot-race edge case.
 *  - `null` covers the boot window before the state observer's first
 *    emission lands (the snapshot field starts `null`).
 *
 * **Why a snapshot, not the live state.** The bubble's gesture handler
 * dispatches `BubbleEvent.LongPressStarted` on touch-down (transitioning
 * Idle / Verdict / FailureState → Pressing) BEFORE the tap-vs-long-press
 * deadline resolves. Reading `bubbleStateMachine.state.value` on the tap
 * callback always sees `Pressing`. The service maintains
 * `lastNonPressingState` (set by the state observer when entering any
 * non-Pressing state) and passes it here as `sourceState`. Mirrors the
 * Story 2.4 + 3.3 `lastTappableRecordForTap` snapshot pattern.
 *
 * **Critical invariant** — this helper does NOT dispatch any [BubbleEvent].
 * The state machine is read synchronously via the snapshot at the call site
 * and the bubble remains in `Pressing` until the caller's
 * `BubbleEvent.BackToIdle` dispatch (mirrors [handleTappableBubbleTap]'s
 * Story 2.4 Critical Dev Note #1).
 *
 * **Lambda-seam pattern** (8th application — Stories 1.5, 1.7, 2.1, 2.2,
 * 2.3, 2.4, 3.3 + this Story 4.3):
 *  - production wires `onLaunchHistory = ::launchHistoryActivity` in
 *    `BubbleOverlayService.onBubbleTap` / `onBubblePressCancelled`;
 *  - JVM tests pass a capturing lambda and assert `captured == true`
 *    only for `BubbleState.Idle` source states.
 *
 * @param sourceState The bubble's pre-touch-down state snapshot (read
 *                    via `lastNonPressingState` at the call site; this
 *                    helper takes the value, not the field).
 * @param onLaunchHistory Invoked when [sourceState] is [BubbleState.Idle]
 *                        (faded or opaque) — production routes to
 *                        `MainActivity` with the
 *                        `HistoryScreenIntent.ACTION_OPEN` action.
 */
internal fun handleIdleBubbleTap(
    sourceState: BubbleState?,
    onLaunchHistory: () -> Unit,
) {
    if (sourceState is BubbleState.Idle) onLaunchHistory()
}
