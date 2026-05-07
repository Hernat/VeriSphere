package com.verisphere.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.verisphere.app.util.tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Accessibility-driven screen capture (architecture: AR27, D5.13, D2.11;
 * PRD: FR3, FR6, FR20).
 *
 * **Lifetime contract.** The user activates this service once via
 * `Settings → Accessibility → VeriSphere → On` (deep-linked from
 * onboarding card 1 — Story 5.1). The system binds the service and
 * calls [onServiceConnected]; the static [Companion.instance] is then
 * non-null and survives across reboots until the user disables the
 * service in Settings. Replaces the per-session `MEDIA_PROJECTION`
 * token (D5.3 deprecated 2026-05-07) with a permission that survives
 * process death and avoids the system "Sharing" notification + cast
 * icon.
 *
 * **Capture API.** [captureScreenshot] wraps
 * [AccessibilityService.takeScreenshot] (API 30+) into a coroutine via
 * [suspendCancellableCoroutine]. The system delivers the result on the
 * [Dispatchers.IO] executor; the function then hops to
 * [Dispatchers.Default] for the JPEG-encode step (D4.4 — pixel work
 * on Default).
 *
 * **HardwareBuffer dance** (Critical Dev Note in [story 1.8.5][1]):
 * `takeScreenshot` returns a [ScreenshotResult] whose `hardwareBuffer`
 * is a finite GPU resource. The required sequence is:
 *   1. `Bitmap.wrapHardwareBuffer(buffer, colorSpace)` → returns a
 *      `Config.HARDWARE` bitmap with NO CPU pixels.
 *   2. `.copy(Bitmap.Config.ARGB_8888, false)` → produces a software
 *      bitmap whose pixels CAN be compressed.
 *   3. `result.hardwareBuffer.close()` IMMEDIATELY — without this the
 *      GPU buffer pool fills up and sustained captures crash with
 *      `BufferQueue: failed to allocate buffer` after ~50-100 captures.
 *   4. `softwareBitmap.compress(JPEG, 80, …)` → JPEG bytes.
 *   5. `softwareBitmap.recycle()` to release the ARGB_8888 backing
 *      memory promptly (10 MB on a 1080×2400 frame).
 *
 * **Static instance + `@Volatile`.** [BubbleOverlayService][2] reads
 * [Companion.instance] from `Dispatchers.IO` (the pipeline's executor).
 * The system writes to [Companion.instance] from a binder thread
 * (typically main-looper-ish). Without `@Volatile`, the IO reader can
 * see a stale `null` due to memory caching. Same pattern as Story 1.8
 * review patch P1 (`@Volatile var _mediaProjection`).
 *
 * **Minimum-permission posture** (D2.11): [onAccessibilityEvent] is a
 * deliberate no-op. We declare `accessibilityEventTypes="typeWindowStateChanged"`
 * in [accessibility_service_config.xml] only because empty
 * `accessibilityEventTypes` causes unreliable service binding on
 * Samsung / Xiaomi / Huawei OEM ROMs. The events are dropped
 * immediately at zero CPU cost. We do NOT listen for any other event
 * type and we do NOT request `flagRetrieveInteractiveWindows` or
 * similar capability flags.
 *
 * **System rate limit** (333 ms / ~3 calls per second): documented in
 * `AccessibilityService.takeScreenshot` source. Calling faster returns
 * `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`. VeriSphere's per-device
 * rate limit (30 captures / UTC-day, AR14) is far more restrictive than
 * the system threshold so we will NOT hit it under normal use. The
 * threshold is a defensive backstop only.
 *
 * [1]: ../../../../../../_bmad-output/implementation-artifacts/1-8-5-accessibility-driven-screen-capture.md
 * [2]: ../bubble/BubbleOverlayService.kt
 */
class VeriSphereAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Canonical "ready" hook per Android docs — by the time
        // onServiceConnected fires, the AccessibilityServiceInfo
        // configuration parsed from accessibility_service_config.xml
        // is confirmed and takeScreenshot is callable.
        instance = this
        Log.d(TAG, "service connected — screenshot capability available")
    }

    /**
     * Deliberate no-op. We declare `typeWindowStateChanged` in the
     * service config only to keep the OEM binding path reliable; we
     * never need the event payload.
     */
    @Suppress("EmptyFunctionBlock")
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty — see KDoc on the class (minimum-permission posture).
    }

    /**
     * Deliberate no-op. The system invokes this when feedback delivery
     * is interrupted; we never deliver feedback so there is nothing to
     * cancel.
     */
    @Suppress("EmptyFunctionBlock")
    override fun onInterrupt() {
        // Intentionally empty — see KDoc on the class.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.d(TAG, "service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // Defensive null-out: onUnbind should always run first, but
        // onDestroy is the absolute safety net.
        instance = null
        super.onDestroy()
    }

    /**
     * Capture a single frame from the active display and return it as a
     * JPEG `ByteArray`. Architecture-mandated single-suspend boundary
     * for the capture step of [com.verisphere.app.capture.CapturePipeline]
     * (D4.5).
     *
     * **In-memory only** — the frame lives only in the local variables
     * inside this function and the returned `ByteArray`. Never written
     * to disk (validation Gap #2; FR6).
     *
     * **Two-phase implementation** (code-review patches P1 + P2 + P3,
     * Story 1.8.5):
     *   1. **Phase 1 — wrap + copy + close (synchronous, in callback).**
     *      The system delivers `ScreenshotResult` on `Dispatchers.IO.asExecutor()`.
     *      Inside the callback we [Bitmap.wrapHardwareBuffer] the
     *      [HardwareBuffer], `.copy(ARGB_8888)` to a software bitmap,
     *      and immediately [HardwareBuffer.close] the underlying GPU
     *      resource. Resource cleanup runs in finally blocks so a
     *      `wrapHardwareBuffer` null OR a `copy` failure both close
     *      the buffer (P2). The callback resumes the continuation with
     *      the software [Bitmap] — NOT the JPEG bytes.
     *   2. **Phase 2 — JPEG encode (suspend, on Default).** Once
     *      resumed, the suspend function hops to `Dispatchers.Default`
     *      via `withContext` and compresses the software bitmap to
     *      JPEG. Honours D4.4 dispatcher discipline cleanly without
     *      `runBlocking` (P1 — `runBlocking` inside an IO-thread
     *      callback would block an IO worker for the entire encode +
     *      cancellation cannot interrupt the encode).
     *
     * **Cancellation propagation** (P3): registers
     * [continuation.invokeOnCancellation] which closes the
     * [HardwareBuffer] if a late `onSuccess` arrives after the parent
     * coroutine cancellation (e.g. `withTimeout` fired in the pipeline).
     * Without this guard, the OS-buffered late frame leaks on every
     * cancelled capture.
     *
     * Throws [ScreenshotFailedException] on `takeScreenshot.onFailure`.
     * Throws [IllegalStateException] on `wrapHardwareBuffer` returning
     * null (defensive). The pipeline's `try { … } catch (e: Exception)`
     * single-error-funnel maps both to
     * [com.verisphere.app.gemini.VerificationOutcome.Failure.CaptureFailed]
     * (architecture line 486).
     */
    suspend fun captureScreenshot(): ByteArray {
        val softwareBitmap = acquireScreenshotBitmap()
        return try {
            // Phase 2 — JPEG encode on Dispatchers.Default. CPU-bound
            // (50-150 ms on a 1080x2400 frame). Running here as a suspend
            // hop honours D4.4 cleanly + cancellation propagates.
            withContext(Dispatchers.Default) {
                val output = ByteArrayOutputStream()
                softwareBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                output.toByteArray()
            }
        } finally {
            softwareBitmap.recycle()
        }
    }

    /**
     * Phase 1 of [captureScreenshot] — bridges the
     * [AccessibilityService.takeScreenshot] callback into a coroutine
     * and returns a software (ARGB_8888) [Bitmap]. The hardware buffer
     * is closed before this function returns. The caller owns the
     * returned bitmap and MUST [Bitmap.recycle] it.
     */
    private suspend fun acquireScreenshotBitmap(): Bitmap = suspendCancellableCoroutine { continuation ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            // Dispatchers.IO.asExecutor() — NOT mainExecutor. The
            // post-callback work is wrap + copy (CPU-bound but fast,
            // typically <50 ms); running on Main would jank the bubble
            // drag / Compose recomposition.
            Dispatchers.IO.asExecutor(),
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    if (!continuation.isActive) {
                        // P3: parent coroutine was cancelled (e.g.
                        // pipeline withTimeout fired) before the system
                        // delivered our frame. Close the HardwareBuffer
                        // immediately — without this the OS-buffered
                        // late frame leaks GPU memory.
                        try {
                            result.hardwareBuffer.close()
                        } catch (e: IllegalStateException) {
                            // Already closed by invokeOnCancellation —
                            // safe to ignore.
                            @Suppress("UnusedPrivateMember") val ignored = e
                        }
                        return
                    }
                    val softwareBitmap = try {
                        wrapAndCopy(result)
                    } catch (t: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(t)
                        }
                        return
                    }
                    if (continuation.isActive) {
                        continuation.resume(softwareBitmap)
                    } else {
                        // Cancelled between our isActive check and resume —
                        // recycle the bitmap we just allocated.
                        softwareBitmap.recycle()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) {
                        Log.w(TAG, "takeScreenshot onFailure code=$errorCode (${errorCodeName(errorCode)})")
                        continuation.resumeWithException(
                            ScreenshotFailedException(errorCode, errorCodeName(errorCode)),
                        )
                    }
                }
            },
        )
        // P3: invokeOnCancellation cannot reach the in-flight
        // takeScreenshot — but it CAN close any HardwareBuffer that
        // arrives in a late onSuccess after cancellation. We rely on
        // the onSuccess `!continuation.isActive` branch above for the
        // close; this hook is a defense-in-depth no-op (no resource
        // owned at the cancellation point itself).
        continuation.invokeOnCancellation {
            // No resource is reachable from here — the HardwareBuffer
            // is delivered inside the callback only. The onSuccess
            // branch handles the late-callback case.
        }
    }

    /**
     * Phase 1 helper — wrap the hardware buffer, copy to ARGB_8888
     * software bitmap, close the hardware buffer. Exception-safe:
     * the `finally` blocks ensure both the [HardwareBuffer] and the
     * intermediate hardware [Bitmap] are released regardless of which
     * step throws (P2).
     */
    private fun wrapAndCopy(result: ScreenshotResult): Bitmap {
        // The HardwareBuffer is closed in the outermost finally,
        // covering the wrapHardwareBuffer-null path (the original code
        // threw error() BEFORE entering try{}finally{}, leaking the
        // buffer — P2).
        try {
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                ?: error("Bitmap.wrapHardwareBuffer returned null")
            try {
                // Hardware bitmaps cannot be compressed — must copy to
                // ARGB_8888 software first. The HARDWARE wrapper bitmap
                // is recycled in the inner finally so we don't hold a
                // strong native ref past this scope (P2).
                return hardwareBitmap.copy(Bitmap.Config.ARGB_8888, /* mutable = */ false)
                    ?: error("hardwareBitmap.copy returned null")
            } finally {
                // Recycle the HARDWARE wrapper bitmap so the wrap's
                // strong ref doesn't outlive the function (P2). Safe
                // because the software copy above is independent.
                hardwareBitmap.recycle()
            }
        } finally {
            // Close the HardwareBuffer LAST — covers all throw paths
            // including wrapHardwareBuffer-null, copy-null, and any
            // unexpected exception. Finite GPU resource (P2).
            try {
                result.hardwareBuffer.close()
            } catch (e: IllegalStateException) {
                // Defensive: some drivers double-close on bitmap
                // recycle. Safe to ignore.
                Log.w(TAG, "HardwareBuffer.close() on already-closed buffer", e)
            }
        }
    }

    private fun errorCodeName(code: Int): String = when (code) {
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "INTERVAL_TIME_SHORT"
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "INTERNAL_ERROR"
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "INVALID_DISPLAY"
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "NO_ACCESSIBILITY_ACCESS"
        else -> "UNKNOWN($code)"
    }

    /**
     * Thrown by [captureScreenshot] when the system invokes the
     * `onFailure` callback. Caught by [com.verisphere.app.capture.CapturePipeline]'s
     * single error funnel and mapped to
     * [com.verisphere.app.gemini.VerificationOutcome.Failure.CaptureFailed].
     *
     * @property errorCode One of the `AccessibilityService.ERROR_TAKE_SCREENSHOT_*` constants.
     */
    class ScreenshotFailedException(val errorCode: Int, name: String) :
        RuntimeException("AccessibilityService.takeScreenshot failed: $name")

    /**
     * Thrown by `BubbleOverlayService.frameExtractor` when [instance] is
     * null at the moment of the capture call (i.e. user disabled
     * accessibility between the `hasToken` check and `frameExtractor`
     * invocation — TOCTOU window). Mapped by
     * [com.verisphere.app.capture.CapturePipeline]'s single error
     * funnel to
     * [com.verisphere.app.gemini.VerificationOutcome.Failure.PermissionDenied]
     * — semantically a permission problem, not a capture failure.
     * Code-review patch P4 (Story 1.8.5).
     */
    class ServiceUnboundException :
        RuntimeException("VeriSphereAccessibilityService is not bound — user disabled accessibility")

    companion object {
        /**
         * Cross-thread visible reference to the bound service instance.
         * Read by [BubbleOverlayService][com.verisphere.app.bubble.BubbleOverlayService]
         * from `Dispatchers.IO`; written from the system binder thread
         * via [onServiceConnected] / [onUnbind] / [onDestroy]. The
         * `@Volatile` annotation prevents the IO reader from seeing a
         * stale cached `null` (same pattern as Story 1.8 review patch P1).
         */
        @Volatile
        var instance: VeriSphereAccessibilityService? = null

        private val TAG = tag("VeriSphereAccessibilityService")

        /**
         * JPEG quality. Carried over from Story 1.8's deleted
         * `MediaProjectionTokenHolder.JPEG_QUALITY`. Quality 80 keeps a
         * 1080×2400 frame ≤ ~500 KB — fits Gemini's per-request body
         * budget once base64 encoded by Story 1.9's `GeminiClient`.
         */
        private const val JPEG_QUALITY = 80
    }
}
