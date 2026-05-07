package com.verisphere.app.capture

import com.verisphere.app.gemini.VerificationOutcome
import com.verisphere.app.gemini.VerificationOutcome.Failure
import com.verisphere.app.storage.RateLimitRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [CapturePipeline] (Story 1.8 + Story 1.8.5, AC #11).
 *
 * The pipeline is JVM-pure: its three collaborators
 * ([RateLimitRepository], the `hasToken` lambda, the `frameExtractor`
 * lambda) are all injected, so no Robolectric / mockk / Android stubs
 * are required. Story 1.8 originally closed over a `MediaProjectionTokenHolder`;
 * Story 1.8.5 (Sprint Change 2026-05-07) closes over a
 * `VeriSphereAccessibilityService` static instance — both behind the
 * same lambda contract, so this test class is unchanged in logic.
 *
 * Each test asserts a single end-to-end branch of the verdict
 * path (rate-limit, permission, capture-fail, happy, timeout).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapturePipelineTest {

    @Test
    fun `returns Failure DailyLimitReached when rate limiter rejects`() = runTest {
        // Code review patch P5: hasToken is checked BEFORE consume() so a
        // missing token does not burn a quota slot. This test exercises
        // the rate-limit-rejects path with a present token; we verify
        // (a) the outcome is DailyLimitReached, and (b) frameExtractor
        // is NOT called once the rate-limit gate has rejected (no actual
        // capture work performed).
        val pipeline = CapturePipeline(
            rateLimitRepository = FakeRateLimitRepository(accept = false),
            hasToken = { true },
            frameExtractor = { error("frameExtractor must NOT be called when rate-limited") },
            clock = { FIXED_NOW },
        )

        val outcome = pipeline.runCapture()

        assertEquals(Failure.DailyLimitReached, outcome)
    }

    @Test
    fun `does NOT consume rate-limit when token is missing`() = runTest {
        // Code review patch P5: a long-press whose projection was revoked
        // before the pipeline runs should NOT burn a quota slot. The
        // FakeRateLimitRepository tracks whether consume() was invoked.
        val rateLimit = TrackingRateLimitRepository(accept = true)
        val pipeline = CapturePipeline(
            rateLimitRepository = rateLimit,
            hasToken = { false },
            frameExtractor = { error("frameExtractor must NOT be called when token is missing") },
            clock = { FIXED_NOW },
        )

        val outcome = pipeline.runCapture()

        assertEquals(Failure.PermissionDenied, outcome)
        assertEquals(
            "rate-limit MUST NOT be consumed when the token is missing",
            0,
            rateLimit.consumeCount,
        )
    }

    @Test
    fun `returns Failure PermissionDenied when token is null`() = runTest {
        val pipeline = CapturePipeline(
            rateLimitRepository = FakeRateLimitRepository(accept = true),
            hasToken = { false },
            frameExtractor = { error("frameExtractor must NOT be called when token is missing") },
            clock = { FIXED_NOW },
        )

        val outcome = pipeline.runCapture()

        assertEquals(Failure.PermissionDenied, outcome)
    }

    @Test
    fun `returns Failure CaptureFailed when frame capture throws`() = runTest {
        val pipeline = CapturePipeline(
            rateLimitRepository = FakeRateLimitRepository(accept = true),
            hasToken = { true },
            frameExtractor = { throw IOException("display rotated mid-capture") },
            clock = { FIXED_NOW },
        )

        val outcome = pipeline.runCapture()

        assertEquals(Failure.CaptureFailed, outcome)
    }

    @Test
    fun `returns Failure CaptureFailed when frame is empty`() = runTest {
        val pipeline = CapturePipeline(
            rateLimitRepository = FakeRateLimitRepository(accept = true),
            hasToken = { true },
            frameExtractor = { ByteArray(0) },
            clock = { FIXED_NOW },
        )

        val outcome = pipeline.runCapture()

        assertEquals(Failure.CaptureFailed, outcome)
    }

    @Test
    fun `returns Verdict with placeholder record on happy path`() = runTest {
        val pipeline = CapturePipeline(
            rateLimitRepository = FakeRateLimitRepository(accept = true),
            hasToken = { true },
            frameExtractor = { ByteArray(SAMPLE_FRAME_SIZE) { it.toByte() } },
            clock = { FIXED_NOW },
        )

        val outcome = pipeline.runCapture()

        assertTrue("expected Verdict but got $outcome", outcome is VerificationOutcome.Verdict)
        val verdict = outcome as VerificationOutcome.Verdict
        assertNotNull(verdict.record.id)
        assertFalse("record id must be non-empty", verdict.record.id.isEmpty())
        assertEquals(FIXED_NOW, verdict.record.timestampMs)
    }

    @Test
    fun `runCapture honours the 20-second timeout`() = runTest {
        val pipeline = CapturePipeline(
            rateLimitRepository = FakeRateLimitRepository(accept = true),
            hasToken = { true },
            // The frame extractor suspends WAY past the 20 s budget. The
            // pipeline's withTimeout MUST fire before it ever returns.
            frameExtractor = {
                delay(THIRTY_SECONDS_MS)
                ByteArray(SAMPLE_FRAME_SIZE) { it.toByte() }
            },
            clock = { FIXED_NOW },
        )

        // Drive the virtual clock past the pipeline's 20 s budget.
        // runTest's StandardTestDispatcher means delays are virtual —
        // advanceTimeBy moves time without sleeping. UNDISPATCHED start
        // ensures the suspending body actually begins executing (and
        // hits the inner withTimeout block) BEFORE the time advance.
        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            pipeline.runCapture()
        }
        advanceTimeBy(TWENTY_ONE_SECONDS_MS)
        val outcome = deferred.await()

        assertEquals(Failure.CaptureFailed, outcome)
    }

    private class FakeRateLimitRepository(private val accept: Boolean) : RateLimitRepository {
        override suspend fun consume(): Boolean = accept
    }

    private class TrackingRateLimitRepository(private val accept: Boolean) : RateLimitRepository {
        var consumeCount: Int = 0
            private set

        override suspend fun consume(): Boolean {
            consumeCount++
            return accept
        }
    }

    private companion object {
        private const val FIXED_NOW: Long = 1_700_000_000_000L
        private const val SAMPLE_FRAME_SIZE: Int = 256
        private const val TWENTY_ONE_SECONDS_MS: Long = 21_000L
        private const val THIRTY_SECONDS_MS: Long = 30_000L
    }
}
