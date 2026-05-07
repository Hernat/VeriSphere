package com.verisphere.app.capture

import android.util.Log
import com.verisphere.app.accessibility.VeriSphereAccessibilityService
import com.verisphere.app.gemini.VerificationOutcome
import com.verisphere.app.gemini.VerificationOutcome.Failure
import com.verisphere.app.storage.RateLimitRepository
import com.verisphere.app.util.tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 * to Gemini, nowhere else") is satisfied by construction.
 *
 * **Lambda-provider seams** ([hasToken], [frameExtractor], [verify],
 * [clock]) — let the production service close over
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
     * to [Failure.CaptureFailed] (the user can retry). The Gemini client's
     * own `callTimeout = 20 s` is identical to this budget; in practice
     * the inner OkHttp timeout fires first, but the outer `withTimeout`
     * is the architectural backstop (D4.5).
     */
    @Suppress("TooGenericExceptionCaught") // architecture line 487 — pipeline is the single error funnel
    suspend fun runCapture(): VerificationOutcome = withContext(Dispatchers.IO) {
        try {
            withTimeout(CAPTURE_TIMEOUT.inWholeMilliseconds) {
                runCaptureInner()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "capture timed out after ${CAPTURE_TIMEOUT.inWholeSeconds}s", e)
            Failure.CaptureFailed
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
        return verify(frame)
    }

    companion object {
        /**
         * The full pipeline budget: covers frame capture + the Gemini
         * call. PRD NFR1 targets P95 < 2 s end-to-end; the 20 s ceiling
         * is the backstop matching the OkHttp `callTimeout` (D3.8).
         */
        val CAPTURE_TIMEOUT = 20.seconds

        private val TAG = tag("CapturePipeline")
    }
}
