package com.verisphere.app.gemini

import com.verisphere.app.storage.SessionRecord

/**
 * Sealed-class outcome of a single verification attempt (architecture:
 * AR15, D3.6; PRD: NFR17).
 *
 * **Single error funnel** — every exception thrown inside the capture
 * pipeline AND the Gemini client (Story 1.9) is caught and mapped to one
 * of the [Failure] variants. **No exception escapes to the UI**
 * (architecture Step 5 — Error handling discipline, line 487).
 *
 * **Capture-side variants** (Story 1.8 + 1.8.5):
 *   - [Failure.DailyLimitReached] — rate-limit gate rejected the request
 *   - [Failure.PermissionDenied] — the user has not enabled the
 *     accessibility service, OR disabled it mid-capture (TOCTOU)
 *   - [Failure.CaptureFailed] — frame capture threw or timed out
 *
 * **Network-layer variants** (Story 1.9 — AC #6):
 *   - [Failure.Offline] — no network connectivity
 *   - [Failure.Timeout] — Gemini call exceeded the OkHttp timeout budget
 *   - [Failure.ApiQuotaExhausted] — Gemini returned 429 / quota error
 *   - [Failure.MalformedResponse] — JSON parse / schema mismatch
 *   - [Failure.HttpError] — generic HTTP failure (carries the status code)
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
         * has been exhausted. Story 3.3 maps this to
         * `BubbleState.FailureState.DailyLimit` rendering the
         * `⚪ DAILY LIMIT · Daily limit reached. Try again tomorrow.`
         * flash variant.
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
         * Story 3.3 routes this to the **silent bucket** in
         * `BubbleStateMachine.mapFailureToState` — `Idle(faded=false)`
         * with no flash (UX-DR15 — operator/dev failure category, not
         * user-actionable from a bubble flash; the user is routed to
         * the dedicated Accessibility explanation Activity instead).
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
         * was exceeded. Story 3.3 routes this to the **silent bucket**
         * in `BubbleStateMachine.mapFailureToState` — `Idle(faded=false)`
         * with no flash (UX-DR15 — dev/operator failure category, no
         * user-facing root-cause hint we could surface). The user-visible
         * timeout case is owned by [Timeout] (Gemini-call timeout).
         */
        data object CaptureFailed : Failure()

        /**
         * No network connectivity — DNS resolution failed
         * (`UnknownHostException`). Story 3.3 maps this to
         * `BubbleState.FailureState.Offline` rendering the
         * `📡 OFFLINE · Try again when you're online` flash on the
         * `vs_state_offline` palette. Verdict requires the Gemini
         * round-trip — there is no offline fallback in V1 (architecture
         * line 73 — Gemini API is the only mandatory external service).
         * Surfaced by [com.verisphere.app.gemini.GeminiClient.verify]
         * (Story 1.9 AC #11).
         */
        data object Offline : Failure()

        /**
         * The Gemini call exceeded OkHttp's `callTimeout` budget (D3.8 —
         * 20 s ceiling). Surfaced as `SocketTimeoutException` or
         * `InterruptedIOException`. Story 3.3 maps this to
         * `BubbleState.FailureState.Timeout` rendering the
         * `⏱️ TIMEOUT · Try again` flash on the `vs_state_offline`
         * palette (same colour token as [Offline] per UX spec line 678).
         * Story 3.2 absorbed one retry on transient 5xx / IOException
         * before this state surfaces.
         */
        data object Timeout : Failure()

        /**
         * Gemini returned HTTP 429 (rate-limited) OR a quota-exhausted
         * envelope. Distinct from [DailyLimitReached] because this
         * indicates the BUNDLED API key has hit its server-side quota
         * (NFR9 — known-extractable secret + AR14 mitigation), not the
         * per-device daily limit. Story 3.3 maps this to
         * `BubbleState.FailureState.QuotaExhausted` rendering the
         * `⚪ UNAVAILABLE · Service temporarily unavailable` flash on
         * the neutral grey palette. Operational response: rotate the
         * bundled key per `SECURITY.md` (D2.3).
         */
        data object ApiQuotaExhausted : Failure()

        /**
         * Gemini returned HTTP 200 but the response body could not be
         * parsed into a `GeminiVerdictResponse` — either the envelope
         * was missing fields (`candidates` / `content` / `parts`), the
         * inner JSON was not valid JSON, the inner JSON did not match
         * the schema, OR an unknown `verdictLabel` value was returned.
         * Story 3.3 routes this to the **silent bucket** in
         * `BubbleStateMachine.mapFailureToState` — `Idle(faded=false)`
         * with no flash (UX-DR15 — opaque to the user; the user has no
         * recourse beyond a re-trigger).
         */
        data object MalformedResponse : Failure()

        /**
         * Generic HTTP failure with the raw status code. `code = 0` is
         * a sentinel for failures that happened before any HTTP status
         * was received (DNS resolved but connection refused, TLS
         * handshake fail, OkHttp internal error). Story 3.2's retry
         * policy already absorbed transient 5xx / IOException; Story 3.3
         * routes any HttpError that surfaces here (4xx and post-retry
         * 5xx) to the **silent bucket** in
         * `BubbleStateMachine.mapFailureToState` — `Idle(faded=false)`
         * with no flash (UX-DR15 — opaque to the user). User-actionable
         * end-states are [Offline] / [Timeout] / [DailyLimitReached] /
         * [ApiQuotaExhausted] only.
         */
        data class HttpError(val code: Int) : Failure()

        /**
         * Story 10.1 — the user has not configured a Gemini API key in
         * the new Paramètres tab (or has explicitly cleared it). The
         * `GeminiClient.verify()` short-circuits to this variant
         * BEFORE any network call when `apiKeyProvider()` returns a
         * blank string. Mirrors [com.verisphere.app.serp.SerpOutcome.Failure.NotConfigured]
         * pattern. Story 10.1 maps this to `BubbleState.FailureState.NoApiKey`
         * rendering the `⚠️ CLÉ MANQUANTE · Configure ta clé Gemini
         * dans Paramètres.` flash on the warm-gold `accentPulse`
         * palette (same token as [Timeout] / [ApiQuotaExhausted] —
         * transient operator-error semantics, not hostile failure).
         */
        data object NotConfigured : Failure()
    }
}
