package com.verisphere.app.bubble

import com.verisphere.app.storage.SessionRecord

/**
 * Sealed visual states of the bubble overlay (architecture: AR21, D4.3,
 * line 273).
 *
 * Story 1.10 shipped the happy-path machine: [Idle] → [Pressing] →
 * [Capturing] → [Thinking] → [Verdict] (and back to [Idle] on the next
 * user gesture in the source app). Story 3.3 introduces the
 * [FailureState] nested sealed sub-hierarchy that drives the
 * failure-flash UX variants (UX-DR15, UX-DR17, FR25, NFR17).
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
 *  - [Verdict] — successful verdict received (and `injectionDetected = false`).
 *    The bubble adopts the verdict's semantic colour (UX Step 8 palette)
 *    and the [com.verisphere.app.bubble.ui.FlashTooltip] renders beside
 *    it. After 5–8 s the [Verdict.tooltipFaded] flag flips via a second
 *    internal timer; the bubble retains its colour until the next
 *    [BubbleEvent.BackToIdle].
 *  - [FailureState.*] — Story 3.3 failure-flash variants + Story 7.5 C1
 *    extension. Six concrete variants: [FailureState.Offline] /
 *    [FailureState.Timeout] (share the `vs_state_offline` palette token),
 *    [FailureState.DailyLimit] / [FailureState.QuotaExhausted] /
 *    [FailureState.NotFound] (share the neutral `vs_verdict_non_verifiable`
 *    grey), [FailureState.PossibleInjection] (carries the persisted
 *    [SessionRecord] and uses the doubtful amber palette — only failure
 *    variant for which a bubble-tap opens the detail panel). Each variant
 *    carries its own `tooltipFaded` flag and shares the verdict-tooltip
 *    auto-fade behaviour (UX-DR6).
 *
 * **Silent-bucket failures** ([com.verisphere.app.gemini.VerificationOutcome.Failure.PermissionDenied],
 * [com.verisphere.app.gemini.VerificationOutcome.Failure.CaptureFailed],
 * [com.verisphere.app.gemini.VerificationOutcome.Failure.HttpError],
 * [com.verisphere.app.gemini.VerificationOutcome.Failure.MalformedResponse])
 * stay mapped to [Idle] `faded = false` (no flash) per UX-DR15 — they
 * are operator/dev categories, not user-actionable.
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

    /**
     * Story 3.3 — sealed sub-hierarchy of failure-flash variants
     * (architecture line 273; UX-DR15; UX spec lines 673–680; FR25; NFR17).
     *
     * Each variant carries a `tooltipFaded` flag that the reducer flips
     * via [BubbleEvent.AutoFadeTimeout] after [BubbleStateMachine.TOOLTIP_FADE_MS]
     * (same auto-fade behaviour as [Verdict.tooltipFaded]). The bubble's
     * semantic colour is retained on the bubble Surface (via
     * `bubble/ui/BubbleOverlay.bubbleBackgroundColorFor`) until the next
     * [BubbleEvent.BackToIdle].
     */
    sealed interface FailureState : BubbleState {

        /**
         * No network connectivity (`Failure.Offline`). Tooltip renders
         * `OFFLINE` + `Try again when you're online` on the
         * `vs_state_offline` palette (white-on-dark / dark-on-light per
         * theme). Tap on bubble is a no-op; the user re-triggers a
         * capture via the existing long-press grammar.
         */
        data class Offline(val tooltipFaded: Boolean = false) : FailureState

        /**
         * Gemini call exceeded the OkHttp `callTimeout` (`Failure.Timeout`).
         * Tooltip renders `TIMEOUT` + `Try again` on the
         * `vs_state_offline` palette (same colour as [Offline] per
         * UX spec line 678 — both share `bubble_offline`). Tap on bubble
         * is a no-op.
         */
        data class Timeout(val tooltipFaded: Boolean = false) : FailureState

        /**
         * Per-device daily rate limit exhausted (`Failure.DailyLimitReached`,
         * 30 captures / UTC-day per AR14). Tooltip renders `DAILY LIMIT` +
         * `Daily limit reached. Try again tomorrow.` on the neutral
         * `vs_verdict_non_verifiable` grey. Tap on bubble is a no-op.
         */
        data class DailyLimit(val tooltipFaded: Boolean = false) : FailureState

        /**
         * Gemini API quota exhausted (`Failure.ApiQuotaExhausted`, server-side
         * 429 / quota envelope — distinct from [DailyLimit]). Tooltip renders
         * `UNAVAILABLE` + `Service temporarily unavailable` on the neutral
         * grey palette (same as [DailyLimit] per UX spec line 678). Tap on
         * bubble is a no-op.
         */
        data class QuotaExhausted(val tooltipFaded: Boolean = false) : FailureState

        /**
         * Successful Gemini verdict whose model self-reported an
         * injection attempt (`Verdict(record where injectionDetected = true)`,
         * PRD FR8 + NFR8 self-revealing posture). Tooltip renders
         * `POSSIBLE INJECTION` + `See OCR text` on the doubtful amber
         * palette (`vs_verdict_doubtful`).
         *
         * **The wrapped [record] WAS persisted to history BEFORE this
         * state was emitted** (Story 1.10 Critical Dev Note #6 invariant;
         * Story 3.1's `GeminiClient.toSessionRecord` propagates
         * `injectionDetected` from `GeminiVerdictResponse`). Tap on the
         * bubble opens the detail panel via Story 2.4's
         * `buildDetailPanelIntent(sessionId = record.id, ...)` — the
         * only [FailureState] variant for which tap-to-expand is wired
         * (the other failures carry no `SessionRecord`).
         */
        data class PossibleInjection(
            val record: SessionRecord,
            val tooltipFaded: Boolean = false,
        ) : FailureState

        /**
         * Story 7.5 C1 — history-record-not-found surface for the FIFO
         * eviction / stale-tap-race edge case (deferred-work L247).
         *
         * Fired when [com.verisphere.app.MainActivity.tryOpenPendingDetailPanel]
         * OR [com.verisphere.app.MainActivity.openHistoryRecord] resolves
         * `historyRepository.getById(pending)` to `null` — the record was
         * dropped between Verdict emission and panel mount (FIFO eviction
         * at the 500-entry cap per architecture D1.3, OR a stale tap whose
         * Verdict-state record was overwritten by a faster fresh capture).
         *
         * Tooltip renders `INTROUVABLE` + `Ce verdict n'est plus dans
         * l'historique.` on the neutral `vs_verdict_non_verifiable` grey
         * (same palette as [DailyLimit] / [QuotaExhausted] per UX-DR15 —
         * "not found" is benign, not hostile). Tap on bubble is a **no-op**
         * (carries no [SessionRecord] — the record is, by definition, gone).
         *
         * **Cross-process dispatch** — unlike the other 5 [FailureState]
         * variants which are mapped from `VerificationOutcome.Failure` by
         * [com.verisphere.app.bubble.BubbleStateMachine.mapFailureToState],
         * [NotFound] arrives via a dedicated [com.verisphere.app.bubble.BubbleEvent.HistoryRecordNotFound]
         * event triggered by the Activity-side null branches through the
         * new `BubbleOverlayService.ACTION_NOTIFY_HISTORY_NOT_FOUND` Intent
         * action (architecture D4.2 lifecycle-owner contract preserved —
         * `BubbleStateMachine` stays Service-private).
         */
        data class NotFound(val tooltipFaded: Boolean = false) : FailureState

        /**
         * Story 10.1 — the user has not configured a Gemini API key in
         * the Paramètres tab (or has explicitly cleared it).
         * [com.verisphere.app.gemini.GeminiClient.verify] short-circuits
         * to [com.verisphere.app.gemini.VerificationOutcome.Failure.NotConfigured]
         * BEFORE any network call ; [com.verisphere.app.bubble.BubbleStateMachine.mapFailureToState]
         * maps that outcome to this variant.
         *
         * Renders the `⚠️ CLÉ MANQUANTE · Configure ta clé Gemini dans
         * Paramètres.` flash on the warm-gold `accentPulse` palette
         * (same token as [Timeout] / [QuotaExhausted] — transient
         * operator-error semantics, not hostile system failure). Carries
         * no [SessionRecord] (the verify never ran). Not tap-actionable
         * — mirrors the non-clickable convention shared with [NotFound]
         * + the other failures that are not [PossibleInjection].
         */
        data class NoApiKey(val tooltipFaded: Boolean = false) : FailureState
    }
}
