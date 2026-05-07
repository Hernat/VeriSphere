package com.verisphere.app.capture

import android.util.Log
import com.verisphere.app.accessibility.VeriSphereAccessibilityService
import com.verisphere.app.gemini.VerificationOutcome
import com.verisphere.app.gemini.VerificationOutcome.Failure
import com.verisphere.app.storage.RateLimitRepository
import com.verisphere.app.storage.SessionRecord
import com.verisphere.app.util.tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Verdict-path orchestration (architecture: AR23, D4.5; PRD: NFR17).
 *
 * **Single suspending function** — the entire long-press → frame → (Gemini)
 * → persist sequence runs inside [runCapture], wrapped in
 * `withTimeout(20.seconds)`. The 20 s budget covers BOTH frame extraction
 * AND the future Gemini call (Story 1.9). Story 1.8 simulates the network
 * leg with a 200 ms `delay()` and synthesizes a placeholder
 * [VerificationOutcome.Verdict] — the real `geminiClient.verify(frame)`
 * call replaces the simulated leg in Story 1.9 without touching this
 * function's signature.
 *
 * **Single error funnel** (architecture line 486) — every exception thrown
 * inside the pipeline (rate-limit IO error, frame extraction failure,
 * timeout) is caught here and mapped to a [Failure] variant. **No
 * exception escapes to the caller.** Callers receive a typed
 * [VerificationOutcome] in every code path.
 *
 * **In-memory frame lifecycle** (architecture validation Gap #2). The
 * captured frame `ByteArray` lives only inside this function's local
 * variable — it is never written to disk, never passed to a global
 * collaborator, never retained beyond the function's stack frame. On
 * `Verdict` it is GC-eligible the moment [runCapture] returns; on any
 * `Failure` path it is dropped before unwinding. PRD FR6 ("image only
 * to Gemini, nowhere else") is satisfied by construction.
 *
 * **Lambda-provider seams** ([hasToken], [frameExtractor], [clock]) —
 * lets the production service close over [com.verisphere.app.accessibility.VeriSphereAccessibilityService]
 * (Story 1.8.5; previously `MediaProjectionTokenHolder` in Story 1.8)
 * while keeping the pipeline's collaborators JVM-testable. AC #11
 * explicitly mandates this pattern over an `interface FrameExtractor`
 * to keep the production class concrete.
 */
class CapturePipeline(
    private val rateLimitRepository: RateLimitRepository,
    private val hasToken: () -> Boolean,
    private val frameExtractor: suspend () -> ByteArray,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Run the full capture pipeline and return a [VerificationOutcome].
     *
     * Order of operations (each step is the SOLE source of the
     * corresponding [Failure] variant):
     *   1. Token presence check via [hasToken] → [Failure.PermissionDenied]
     *      when the OS dialog was cancelled or the projection was revoked.
     *      MUST run BEFORE the rate-limit gate (Code review patch P5):
     *      a missing token cannot produce a capture, so consuming a quota
     *      slot here would burn 1/30 daily captures with zero work done.
     *   2. Rate-limit gate via [RateLimitRepository.consume] →
     *      [Failure.DailyLimitReached] when exhausted (no capture, no
     *      slot consumed beyond the one we're about to use).
     *   3. Frame extraction via [frameExtractor] → [Failure.CaptureFailed]
     *      on any exception (display rotated, virtual-display refused,
     *      image acquire timeout) or empty frame.
     *   4. Simulated network call (200 ms `delay`) — Story 1.9 replaces
     *      with `geminiClient.verify(frame)`.
     *   5. [VerificationOutcome.Verdict] wrapping a placeholder
     *      [SessionRecord] (Story 1.10 introduces the FR15 final shape).
     *
     * The pipeline runs on [Dispatchers.IO] (architecture D4.4 — repositories
     * use IO internally so call sites don't have to). The 20 s budget is
     * enforced via [withTimeout]; `TimeoutCancellationException` is mapped
     * to [Failure.CaptureFailed] (the user can retry).
     */
    @Suppress("TooGenericExceptionCaught") // architecture line 486 — pipeline is the single error funnel
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

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runCaptureInner(): VerificationOutcome {
        // Code review patch P5: token check FIRST, then rate-limit. A
        // user whose projection was revoked between long-press and
        // pipeline run would otherwise burn one of 30 daily captures
        // with no actual capture performed.
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
            // Code-review patch P4: the service was bound when
            // hasToken() returned true, but unbound between then and
            // the frameExtractor call (TOCTOU). Map to PermissionDenied
            // — semantically a permission problem, not a capture
            // failure. The bubble's onLongPress handler will then route
            // the user to the AccessibilityExplanationScreen.
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

        // Story 1.8 placeholder — Story 1.9 replaces with the real
        // `geminiClient.verify(frame)` call. The simulated 200 ms delay
        // is a lower-bound stand-in for the future network round-trip
        // (NFR1 P95 < 2 s).
        delay(SIMULATED_NETWORK_MS)

        return synthesizeVerdict(frame.size)
    }

    private fun synthesizeVerdict(frameSizeBytes: Int): VerificationOutcome.Verdict {
        val now = clock()
        return VerificationOutcome.Verdict(
            SessionRecord(
                id = UUID.randomUUID().toString(),
                timestampMs = now,
                placeholderHeadline = "captured $frameSizeBytes bytes at $now",
            ),
        )
    }

    companion object {
        /**
         * The full pipeline budget: covers frame capture + the future
         * Gemini call (Story 1.9). PRD NFR1 targets P95 < 2 s end-to-end;
         * the 20 s ceiling is a backstop, not a target.
         */
        val CAPTURE_TIMEOUT = 20.seconds

        /**
         * Story 1.8 placeholder — the simulated network round-trip
         * duration. Story 1.9 removes this constant and the
         * corresponding `delay()` call when the real OkHttp call lands.
         */
        const val SIMULATED_NETWORK_MS: Long = 200L

        private val TAG = tag("CapturePipeline")
    }
}
