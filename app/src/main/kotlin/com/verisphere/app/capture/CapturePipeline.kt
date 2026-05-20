package com.verisphere.app.capture

import android.util.Log
import com.verisphere.app.accessibility.VeriSphereAccessibilityService
import com.verisphere.app.gemini.ReverdictOutcome
import com.verisphere.app.gemini.VerificationOutcome
import com.verisphere.app.gemini.VerificationOutcome.Failure
import com.verisphere.app.serp.AgreementScorer
import com.verisphere.app.serp.SerpOutcome
import com.verisphere.app.storage.SessionRecord
import com.verisphere.app.storage.RateLimitRepository
import com.verisphere.app.util.tag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Verdict-path orchestration (architecture: AR23, D4.5; PRD: NFR17).
 *
 * **Single suspending function** — the entire long-press → frame →
 * Gemini → return sequence runs inside [runCapture], wrapped in
 * `withTimeout(20.seconds)`. The 20 s budget covers BOTH frame
 * extraction AND the Gemini call.
 *
 * **Single error funnel** (architecture line 487) — every exception
 * thrown inside the pipeline (rate-limit IO error, frame extraction
 * failure, timeout) is caught here and mapped to a [Failure] variant.
 * **No exception escapes to the caller.** [verify] follows the same
 * contract — [com.verisphere.app.gemini.GeminiClient.verify] never
 * throws — so the pipeline's `catch (e: Exception)` is the defensive
 * backstop only (programmer errors, not wire-format issues).
 *
 * **In-memory frame lifecycle** (architecture validation Gap #2). The
 * captured frame `ByteArray` lives only inside this function's local
 * variable — never written to disk, never passed to a global
 * collaborator, never retained beyond the function's stack frame. On
 * `Verdict` it is GC-eligible the moment [runCapture] returns; on any
 * `Failure` path it is dropped before unwinding. PRD FR6 ("image only
 * to Gemini, nowhere else") is satisfied by construction. **Closes
 * architecture validation Gap #2 (AR23 — frame-lifecycle KDoc).**
 *
 * **Accessibility-revoked seam** (architecture validation Gap #1,
 * amended post-Sprint-Change-2026-05-07). The original architecture's
 * "OS-dialog seam" referred to the `MEDIA_PROJECTION` consent dialog
 * between [com.verisphere.app.bubble.BubbleEvent.LongPressCompleted]
 * and [com.verisphere.app.bubble.BubbleState.Capturing]; D5.3 was
 * deprecated in favour of D5.13 ([com.verisphere.app.accessibility.VeriSphereAccessibilityService.takeScreenshot]),
 * and the OS-permission seam moved to Accessibility activation in
 * Settings. The functional equivalent now has three branches:
 *
 *  1. [com.verisphere.app.bubble.BubbleOverlayService.onLongPress]
 *     pre-checks `VeriSphereAccessibilityService.instance != null` and
 *     routes the user to [com.verisphere.app.ui.onboarding.AccessibilityExplanationScreen]
 *     when revoked — this never invokes the pipeline.
 *  2. The pipeline's [hasToken] returns `false` → immediate
 *     [Failure.PermissionDenied] (defensive — same outcome the service
 *     pre-check would have produced).
 *  3. TOCTOU: the user disables the service between [hasToken] and
 *     [frameExtractor] →
 *     [com.verisphere.app.accessibility.VeriSphereAccessibilityService.ServiceUnboundException]
 *     → [Failure.PermissionDenied] (Story 1.8.5 patch P4).
 *
 * The OS Settings screen is an OS surface, not an app state — there is
 * intentionally NO distinct [com.verisphere.app.bubble.BubbleState] for
 * "waiting for Accessibility grant". [Failure.PermissionDenied] is the
 * typed outcome; [com.verisphere.app.bubble.BubbleStateMachine.reduce]
 * maps it to [com.verisphere.app.bubble.BubbleState.Idle] (Story 1.10
 * minimal mapping). Story 3.3 will add the proper `FailureState.*` UX
 * variants. **Closes architecture validation Gap #1 (AR15 — OS-
 * permission seam KDoc, amended for Sprint Change 2026-05-07).**
 *
 * **Lambda-provider seams** ([hasToken], [frameExtractor], [verify])
 * — let the production service close over
 * [com.verisphere.app.accessibility.VeriSphereAccessibilityService]
 * (Story 1.8.5) and [com.verisphere.app.gemini.GeminiClient] (Story
 * 1.9) while keeping the pipeline's collaborators JVM-testable. The
 * [verify] lambda routes to `GeminiClient.verify`; pipeline owns only
 * the rate-limit gate, capture, and timeout — Gemini network specifics
 * belong to `GeminiClient` (architecture line 752 boundary discipline).
 */
class CapturePipeline(
    private val rateLimitRepository: RateLimitRepository,
    private val hasToken: () -> Boolean,
    private val frameExtractor: suspend () -> ByteArray,
    private val verify: suspend (ByteArray) -> VerificationOutcome,
    // Code-review patch P15 — `clock` parameter removed. Story 1.8 used
    // it to inject `FIXED_NOW` into the synthesized verdict's timestamp;
    // Story 1.9 deleted `synthesizeVerdict` so `clock` became dead code.
    // The `verify` lambda now produces the canonical timestamp inside
    // `GeminiClient.toSessionRecord` (`System.currentTimeMillis()`).

    // Epic 9 Story 9.1 — SerpAPI cross-source enrichment seams. All
    // 4 lambdas default to no-op / disabled so existing tests + the
    // pipeline contract pre-Epic-9 keep working unchanged when SerpAPI
    // is wired off (BuildConfig.SERP_API_KEY empty) or in JVM tests
    // that do not exercise SerpAPI behaviour.
    private val serpSearch: suspend (query: String) -> SerpOutcome =
        { _ -> SerpOutcome.Failure.NotConfigured },
    private val shouldSkipSerp: () -> Boolean = { false },
    private val onSerpQuotaExceeded: () -> Unit = {},
    private val onSerpSuccess: () -> Unit = {},
    /**
     * Second Gemini call — re-evaluates the verdict from the SerpAPI
     * Google synthesis (the most up-to-date evidence). Defaults to
     * [ReverdictOutcome.Failure] so existing tests + the legacy
     * Gemini-only path keep working unchanged. Production wires this to
     * [com.verisphere.app.gemini.GeminiClient.reverdict].
     */
    private val reverdict: suspend (claim: String, serpMarkdown: String) -> ReverdictOutcome =
        { _, _ -> ReverdictOutcome.Failure },
) {

    /**
     * Run the full capture pipeline and return a [VerificationOutcome].
     *
     * Order of operations (each step is the SOLE source of the
     * corresponding [Failure] variant):
     *   1. Token presence check via [hasToken] → [Failure.PermissionDenied]
     *      when the accessibility service is not bound. MUST run BEFORE
     *      the rate-limit gate (Code review patch P5): a missing token
     *      cannot produce a capture, so consuming a quota slot here
     *      would burn 1/30 daily captures with zero work done.
     *   2. Rate-limit gate via [RateLimitRepository.consume] →
     *      [Failure.DailyLimitReached] when exhausted.
     *   3. Frame extraction via [frameExtractor] → [Failure.CaptureFailed]
     *      on most exceptions; [Failure.PermissionDenied] specifically
     *      on [VeriSphereAccessibilityService.ServiceUnboundException]
     *      (Story 1.8.5 patch P4 — TOCTOU between [hasToken] and
     *      [frameExtractor]).
     *   4. Verdict via [verify] (Story 1.9) — routes to the Gemini
     *      client; never throws (architecture line 487 + GeminiClient
     *      single error funnel). Whatever outcome [verify] produces is
     *      returned unchanged.
     *
     * The pipeline runs on [Dispatchers.IO] (architecture D4.4 — repositories
     * use IO internally so call sites don't have to). The 20 s budget is
     * enforced via [withTimeout]; `TimeoutCancellationException` is mapped
     * to [Failure.Timeout] (Story 3.1 — was `Failure.CaptureFailed` before
     * the cosmetic mapping fix). The Gemini client's own `callTimeout = 20 s`
     * is identical to this budget; in practice the inner OkHttp timeout fires
     * first, but the outer `withTimeout` is the architectural backstop (D4.5).
     */
    @Suppress("TooGenericExceptionCaught") // architecture line 487 — pipeline is the single error funnel
    suspend fun runCapture(): VerificationOutcome = withContext(Dispatchers.IO) {
        try {
            withTimeout(CAPTURE_TIMEOUT.inWholeMilliseconds) {
                runCaptureInner()
            }
        } catch (e: TimeoutCancellationException) {
            // Story 3.1 — cosmetic mapping fix flagged by Story 2.4 smoke
            // (deferred-work 2026-05-11). The pipeline's `withTimeout`
            // budget being exceeded is semantically a Failure.Timeout, not
            // Failure.CaptureFailed; the latter is reserved for frame-
            // extraction or programmer errors. Story 3.3 will render the
            // distinction in the FlashTooltip (`⏱️ TIMEOUT · Try again`
            // vs the silent-return-to-idle bucket).
            //
            // ORDER INVARIANT: this clause MUST precede catch(CancellationException)
            // below — TimeoutCancellationException is a subclass of
            // CancellationException. Reversing the order silently collapses
            // Timeout to CaptureFailed.
            Log.w(TAG, "capture timed out after ${CAPTURE_TIMEOUT.inWholeSeconds}s", e)
            Failure.Timeout
        } catch (e: CancellationException) {
            // Story 3.1 — preserve structured concurrency. A non-timeout
            // CancellationException arises from parent-scope cancellation
            // (e.g. service teardown during runCaptureAndDispatch); it
            // must propagate so the caller can abandon dispatch instead
            // of driving the BubbleStateMachine to Idle via a fake
            // Failure.CaptureFailed. Same pattern as GeminiClient.verify
            // (code-review patch P4, Story 1.9).
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "capture failed", e)
            Failure.CaptureFailed
        }
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun runCaptureInner(): VerificationOutcome {
        // Code review patch P5 (Story 1.8): token check FIRST, then
        // rate-limit. A user whose accessibility service was disabled
        // between long-press and pipeline run would otherwise burn one
        // of 30 daily captures with no actual capture performed.
        if (!hasToken()) {
            Log.d(TAG, "no accessibility service bound — PermissionDenied")
            return Failure.PermissionDenied
        }
        if (!rateLimitRepository.consume()) {
            Log.d(TAG, "rate-limit gate rejected — DailyLimitReached")
            return Failure.DailyLimitReached
        }
        val frame = try {
            frameExtractor()
        } catch (e: VeriSphereAccessibilityService.ServiceUnboundException) {
            // Code-review patch P4 (Story 1.8.5): the service was bound
            // when hasToken() returned true, but unbound between then
            // and the frameExtractor call (TOCTOU). Map to
            // PermissionDenied — semantically a permission problem, not
            // a capture failure. The bubble's onLongPress handler will
            // route the user to AccessibilityExplanationScreen.
            Log.w(TAG, "accessibility service unbound mid-capture — PermissionDenied", e)
            return Failure.PermissionDenied
        } catch (e: CancellationException) {
            // Story 3.1 — preserve structured concurrency. Without this
            // explicit re-throw, the broader `catch (e: Exception)`
            // below would swallow parent-scope cancellations (and
            // TimeoutCancellationException) and produce a fake
            // Failure.CaptureFailed. CancellationException must
            // propagate through every catch level, not just the
            // outermost. Mirrors the GeminiClient.verify pattern
            // (Story 1.9 code-review patch P4).
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "frame extraction failed", e)
            return Failure.CaptureFailed
        }
        if (frame.isEmpty()) {
            Log.w(TAG, "frame extraction returned empty bytes — CaptureFailed")
            return Failure.CaptureFailed
        }

        // Story 1.9 — replaced the simulated 200 ms delay + synthetic
        // verdict with the real Gemini call. The verify lambda routes
        // to [com.verisphere.app.gemini.GeminiClient.verify] which
        // returns a typed VerificationOutcome (never throws).
        val geminiOutcome = verify(frame)

        // Epic 9 Story 9.1 — chain SerpAPI cross-source enrichment after
        // Gemini success. Failures fall through unchanged: the user gets
        // the Gemini-only verdict, SerpAPI's silent absence is the right
        // UX per Epic 9 plan ("graceful degradation").
        return if (geminiOutcome is VerificationOutcome.Verdict) {
            enrichWithSerp(geminiOutcome)
        } else {
            geminiOutcome
        }
    }

    /**
     * Epic 9 Story 9.1 — sequential SerpAPI enrichment.
     *
     * Quota gate first ([shouldSkipSerp]) — if a recent quota signal is
     * still inside its cooldown window, skip SerpAPI entirely and return
     * the Gemini verdict unchanged. Otherwise call [serpSearch] with the
     * Gemini-derived `extractedClaim` (already stripped of social-media
     * chrome by the system prompt), then map the [SerpOutcome] :
     *   - Success → enrich record (references + markdown + agreement)
     *               + clear quota gate via [onSerpSuccess]
     *   - Failure.Quota → trip the gate via [onSerpQuotaExceeded] +
     *                     return Gemini verdict unchanged
     *   - other Failure → log + return Gemini verdict unchanged
     *
     * **Injection skip** (code-review DN2) — if Gemini flagged the OCR
     * as injection-tainted, skip SERP entirely. The detail panel for
     * `FailureState.PossibleInjection` should foreground the security
     * warning, not distract with SERP references.
     *
     * **SERP timeout containment** (code-review P2) — the SerpAPI call
     * is wrapped in [withTimeoutOrNull] so SERP-internal stalls map to
     * a typed failure that returns the Gemini verdict unchanged, rather
     * than propagating a [TimeoutCancellationException] up to the outer
     * pipeline budget and discarding Gemini's successful work. The
     * outer [CAPTURE_TIMEOUT] still bounds the whole pipeline as a
     * structural backstop; the per-SERP budget [SERP_BUDGET] is the
     * per-call ceiling.
     *
     * **Bounds on persisted SERP data** (code-review F15) — references
     * are capped at [MAX_PERSISTED_REFS] and markdown truncated at
     * [MAX_PERSISTED_MARKDOWN_CHARS] before the SessionRecord copy.
     * Encrypted SharedPrefs is the persistence backend; an unbounded
     * `List<SerpReference>` per record × 50 history records can balloon
     * faster than the SharedPrefs sweet-spot.
     */
    @Suppress("ReturnCount")
    private suspend fun enrichWithSerp(
        gemini: VerificationOutcome.Verdict,
    ): VerificationOutcome {
        if (gemini.record.injectionDetected) {
            // Code-review DN2 — PossibleInjection UX foregrounds the
            // security warning; SERP refs would distract.
            Log.d(TAG, "injectionDetected=true — skip SerpAPI, Gemini-only verdict")
            return gemini
        }
        if (shouldSkipSerp()) {
            Log.d(TAG, "SerpQuotaGate active — skip SerpAPI, Gemini-only verdict")
            return gemini
        }

        val query = gemini.record.extractedClaim
        val outcome: SerpOutcome = try {
            withTimeoutOrNull(SERP_BUDGET) { serpSearch(query) }
                ?: SerpOutcome.Failure.Timeout
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // SerpApiClient.search is documented as never-throws (single
            // error funnel mirroring GeminiClient). This catch is the
            // defensive backstop ; treat as silent failure.
            Log.w(TAG, "SerpAPI threw unexpectedly", e)
            return gemini
        }

        return when (outcome) {
            is SerpOutcome.Success -> {
                onSerpSuccess()
                val cappedRefs = outcome.references.take(MAX_PERSISTED_REFS)
                val cappedMarkdown = outcome.markdown.take(MAX_PERSISTED_MARKDOWN_CHARS)
                val baseRecord = gemini.record.copy(
                    serpReferences = cappedRefs,
                    serpMarkdown = cappedMarkdown,
                )
                Log.i(
                    TAG,
                    "SerpAPI OK (${outcome.engineUsed}) refs=${cappedRefs.size}",
                )
                VerificationOutcome.Verdict(applyReverdict(baseRecord, cappedMarkdown))
            }
            SerpOutcome.Failure.Quota -> {
                onSerpQuotaExceeded()
                Log.w(TAG, "SerpAPI quota exceeded — Gemini-only verdict, cooldown armed")
                gemini
            }
            is SerpOutcome.Failure -> {
                Log.w(TAG, "SerpAPI failed ($outcome) — Gemini-only verdict")
                gemini
            }
        }
    }

    /**
     * Reverdict step — calls Gemini #2 with the Google synthesis as the
     * authoritative ground-truth, then merges the re-issued verdict
     * fields back over [baseRecord] (which still carries OCR text,
     * extractedClaim, Gemini Search Grounding citations, SerpAPI
     * references + markdown, injectionDetected). Agreement is recomputed
     * AFTER the merge so the "Sources contradictoires" badge reflects
     * the FINAL verdict vs the SerpAPI markdown, not the obsolete
     * Gemini-1 verdict.
     *
     * **Skip conditions** (return the unchanged record + an agreement
     * score against the Gemini-1 verdict) :
     *  - [SessionRecord.injectionDetected] true — injection-tainted
     *    captures foreground the security warning; a re-vote on
     *    poisoned input could amplify the attack.
     *  - blank [SessionRecord.extractedClaim] or blank [serpMarkdown] —
     *    no signal to re-vote on (the reverdict prompt requires both
     *    inputs to be substantive).
     *
     * Failure of the reverdict call (timeout, network, parse, missing
     * API key) collapses to a Gemini-1-verdict-with-SerpAPI-context
     * record — same graceful degradation as the SerpAPI-failure branch.
     */
    private suspend fun applyReverdict(
        baseRecord: SessionRecord,
        serpMarkdown: String,
    ): SessionRecord {
        if (baseRecord.injectionDetected ||
            baseRecord.extractedClaim.isBlank() ||
            serpMarkdown.isBlank()
        ) {
            Log.d(TAG, "reverdict skipped (injection/blank inputs) — Gemini-1 verdict kept")
            return baseRecord.copy(
                agreement = AgreementScorer.score(baseRecord.verdictLabel, serpMarkdown),
            )
        }

        val outcome: ReverdictOutcome = try {
            withTimeoutOrNull(REVERDICT_BUDGET) {
                reverdict(baseRecord.extractedClaim, serpMarkdown)
            } ?: ReverdictOutcome.Failure
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "reverdict threw unexpectedly — keeping Gemini-1 verdict", e)
            ReverdictOutcome.Failure
        }

        return when (outcome) {
            is ReverdictOutcome.Success -> {
                Log.i(
                    TAG,
                    "reverdict OK ${baseRecord.verdictLabel}→${outcome.verdictLabel}",
                )
                baseRecord.copy(
                    verdictLabel = outcome.verdictLabel,
                    headline = outcome.headline,
                    contextLines = outcome.contextLines,
                    regionalBiasNote = outcome.regionalBiasNote,
                    agreement = AgreementScorer.score(outcome.verdictLabel, serpMarkdown),
                )
            }
            ReverdictOutcome.Failure -> {
                Log.w(TAG, "reverdict failed — Gemini-1 verdict kept")
                baseRecord.copy(
                    agreement = AgreementScorer.score(baseRecord.verdictLabel, serpMarkdown),
                )
            }
        }
    }

    companion object {
        /**
         * The full pipeline budget: covers frame capture + the Gemini
         * call + the SerpAPI enrichment. PRD NFR1 targets P95 ≤ ~30 s
         * end-to-end with Search Grounding + Vision active (amended in
         * Sprint Change 2026-05-18 / Story 7.4 MS1); the 80 s ceiling
         * (code-review P2 — was 60 s) is the worst-case backstop
         * matching Gemini's OkHttp `callTimeout = 60 s` (D3.8) PLUS the
         * SerpAPI [SERP_BUDGET] of 15 s plus a 5 s structural slack so
         * a successful-but-late Gemini verdict is not discarded by a
         * SERP timeout.
         */
        val CAPTURE_TIMEOUT: Duration = 100.seconds

        /**
         * Per-SerpAPI-call wall-clock ceiling enforced by [withTimeoutOrNull]
         * inside [enrichWithSerp] (code-review P2 — was unenforced; the
         * `SerpApiClient.CALL_TIMEOUT_SECONDS = 15` constant existed but
         * was never wired to OkHttp). Distinct from the SerpApiClient's
         * own OkHttp `callTimeout` ; both apply belt-and-braces.
         */
        val SERP_BUDGET: Duration = 15.seconds

        /**
         * Per-reverdict-call wall-clock ceiling. Text-only Gemini call
         * (no image, no Search Grounding) is much faster than [verify] —
         * 15 s is generous. On expiry, the pipeline falls back to the
         * Gemini-1 verdict + SerpAPI enrichment (graceful degradation).
         */
        val REVERDICT_BUDGET: Duration = 15.seconds

        /**
         * Persistence guards (code-review F15) — keep encrypted
         * SharedPrefs payload bounded across 50 history records.
         * Numbers chosen so a worst-case record stays ≤ ~4 KB.
         */
        private const val MAX_PERSISTED_REFS: Int = 6
        private const val MAX_PERSISTED_MARKDOWN_CHARS: Int = 2_000

        private val TAG = tag("CapturePipeline")
    }
}
