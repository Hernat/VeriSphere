package com.verisphere.app.gemini

import com.verisphere.app.storage.SessionRecord

/**
 * Sealed-class outcome of a single verification attempt (architecture:
 * AR15, D3.6; PRD: NFR17).
 *
 * **Single error funnel** — every exception thrown inside the capture
 * pipeline (and, from Story 1.9, the Gemini client) is caught by
 * [com.verisphere.app.capture.CapturePipeline] and mapped to one of
 * the [Failure] variants. **No exception escapes to the UI** (architecture
 * Step 5 — Error handling discipline, line 486).
 *
 * **Story 1.8** ships only the failure variants the capture pipeline can
 * produce on its own:
 *   - [Failure.DailyLimitReached] — rate-limit gate rejected the request
 *   - [Failure.PermissionDenied] — user cancelled the OS `MediaProjection`
 *     dialog OR the held projection was revoked mid-session
 *   - [Failure.CaptureFailed] — frame capture threw or timed out
 *
 * **Story 1.9** adds the network-layer variants:
 *   - `Failure.Offline` — no network connectivity
 *   - `Failure.Timeout` — Gemini call exceeded the OkHttp timeout budget
 *   - `Failure.ApiQuotaExhausted` — Gemini returned 429 / quota error
 *   - `Failure.MalformedResponse` — JSON parse failed
 *   - `Failure.HttpError(code)` — generic HTTP failure
 *
 * The verdict path produces [Verdict] wrapping a [SessionRecord]. Story
 * 1.10 wires the record into `HistoryRepository.append(record)`.
 */
sealed class VerificationOutcome {

    /**
     * Successful verification. The contained [record] is the canonical
     * representation persisted to history (Story 1.10).
     */
    data class Verdict(val record: SessionRecord) : VerificationOutcome()

    /**
     * Single failure category. Each variant maps 1-to-1 to a UX flash
     * variant in Story 1.10 / Epic 3. Adding a new failure variant
     * therefore implies adding (or reusing) a flash-tooltip rendering.
     */
    sealed class Failure : VerificationOutcome() {

        /**
         * The per-device daily rate limit (30 captures / UTC-day, AR14)
         * has been exhausted. Story 3.3 wires the `⚪ DAILY LIMIT` flash
         * variant; in Story 1.8 the bubble silently returns to idle.
         */
        data object DailyLimitReached : Failure()

        /**
         * The user has not enabled VeriSphere's `AccessibilityService`
         * (Story 1.8.5, D5.13) OR disabled it from Settings between the
         * `hasToken` check and the `frameExtractor` call (TOCTOU window).
         * The bubble routes the user to `AccessibilityExplanationScreen`
         * via [com.verisphere.app.MainActivity.ACTION_REQUEST_ACCESSIBILITY]
         * — re-prompting the user mid-gesture would be hostile UX (PRD §
         * 5 Capture-cancelled returns silently to idle).
         *
         * Originally (Story 1.8 deprecated): "user cancelled the OS
         * MediaProjection dialog OR the held projection was revoked from
         * the system shade." See Sprint Change 2026-05-07.
         */
        data object PermissionDenied : Failure()

        /**
         * Frame capture threw (display rotated mid-capture, virtual-display
         * creation refused, image acquire timeout) OR the 20 s pipeline
         * budget [com.verisphere.app.capture.CapturePipeline.CAPTURE_TIMEOUT]
         * was exceeded. Story 3.3 maps this to the `⚫ TIMEOUT` flash
         * variant.
         */
        data object CaptureFailed : Failure()

        // Story 1.9 will add: Offline, Timeout, ApiQuotaExhausted,
        // MalformedResponse, HttpError(code: Int).
    }
}
