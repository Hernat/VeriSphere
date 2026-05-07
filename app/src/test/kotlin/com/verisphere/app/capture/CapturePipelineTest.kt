package com.verisphere.app.capture

import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.gemini.VerificationOutcome
import com.verisphere.app.gemini.VerificationOutcome.Failure
import com.verisphere.app.storage.RateLimitRepository
import com.verisphere.app.storage.SessionRecord
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [CapturePipeline] (Story 1.8 + Story 1.8.5 + Story 1.9).
 *
 * The pipeline is JVM-pure: its four collaborators
 * ([RateLimitRepository], the `hasToken` lambda, the `frameExtractor`
 * lambda, and the [verify] lambda added by Story 1.9) are all injected,
 * so no Robolectric / mockk / Android stubs are required. Story 1.8
 * originally closed over a `MediaProjectionTokenHolder`; Story 1.8.5
 * (Sprint Change 2026-05-07) closes over a `VeriSphereAccessibilityService`
 * static instance; Story 1.9 adds the `verify` lambda which closes over
 * `GeminiClient.verify` in production. Each lambda is exchangeable here
 * for a fake.
 *
 * Each test asserts a single end-to-end branch of the verdict path
 * (rate-limit, permission, capture-fail, happy, timeout, verify-routing).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapturePipelineTest {

    @Test
    fun `returns Failure DailyLimitReached when rate limiter rejects`() = runTest {
        // Code review patch P5: hasToken is checked BEFORE consume() so a
        // missing token does not burn a quota slot. This test exercises
        // the rate-limit-rejects path with a present token; we verify
        // (a) the outcome is DailyLimitReached, and (b) frameExtractor
        // and verify are NOT called once the rate-limit gate has rejected.
        val pipeline = CapturePipeline(
            rateLimitRepository = FakeRateLimitRepository(accept = false),
            hasToken = { true },
            frameExtractor = { error("frameExtractor must NOT be called when rate-limited") },
            verify = { error("verify must NOT be called when rate-limited") },
        )

        val outcome = pipeline.runCapture()

        assertEquals(Failure.DailyLimitReached, outcome)
    }

    @Test
    fun `does NOT consume rate-limit when token is missing`() = runTest {
        // Code review patch P5: a long-press whose accessibility service
        // is unbound before the pipeline runs should NOT burn a quota
        // slot. The TrackingRateLimitRepository tracks consume() calls.
        val rateLimit = TrackingRateLimitRepository(accept = true)
        val pipeline = CapturePipeline(
            rateLimitRepository = rateLimit,
            hasToken = { false },
            frameExtractor = { error("frameExtractor must NOT be called when token is missing") },
            verify = { error("verify must NOT be called when token is missing") },
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
            verify = { error("verify must NOT be called when token is missing") },
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
            verify = { error("verify must NOT be called when capture fails") },
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
            verify = { error("verify must NOT be called when frame is empty") },
        )

        val outcome = pipeline.runCapture()

        assertEquals(Failure.CaptureFailed, outcome)
    }

    @Test
    fun `returns Verdict from verify lambda on happy path`() = runTest {
        // Story 1.9: the pipeline's role post-capture is to route the
        // verify lambda's outcome unchanged. The fixedVerdict is the
        // exact reference returned by runCapture on the happy path.
        val fixedVerdict = VerificationOutcome.Verdict(sampleSessionRecord())
        val pipeline = CapturePipeline(
            rateLimitRepository = FakeRateLimitRepository(accept = true),
            hasToken = { true },
            frameExtractor = { ByteArray(SAMPLE_FRAME_SIZE) { it.toByte() } },
            verify = { fixedVerdict },
        )

        val outcome = pipeline.runCapture()

        // Code-review patch P22 — assertEquals (data-class equality) is
        // less brittle than assertSame (reference identity). A future
        // refactor that produces a structurally-equivalent Verdict still
        // satisfies the contract; only behaviour changes break the test.
        assertEquals("pipeline must route verify's outcome unchanged", fixedVerdict, outcome)
    }

    @Test
    fun `verify lambda receives the captured frame bytes`() = runTest {
        // Locks in the contract: whatever frameExtractor returns is what
        // verify sees. Story 1.9's GeminiClient.verify needs the JPEG
        // bytes; future stories must not introduce a transformation step
        // between extraction and verification without updating this test.
        val expectedFrame = ByteArray(SAMPLE_FRAME_SIZE) { (it * 7).toByte() }
        var seenBytes: ByteArray? = null
        val pipeline = CapturePipeline(
            rateLimitRepository = FakeRateLimitRepository(accept = true),
            hasToken = { true },
            frameExtractor = { expectedFrame },
            verify = { bytes ->
                seenBytes = bytes
                VerificationOutcome.Verdict(sampleSessionRecord())
            },
        )

        pipeline.runCapture()

        assertNotNull("verify must have been invoked", seenBytes)
        assertArrayEquals(
            "verify must receive the exact bytes from frameExtractor",
            expectedFrame,
            seenBytes,
        )
    }

    @Test
    fun `returns whatever verify returns when capture succeeds`() = runTest {
        // Locks in the routing contract: verify is the source of truth
        // post-capture. If verify produces a Failure (e.g. Offline,
        // Timeout, MalformedResponse), the pipeline returns that Failure
        // unchanged — it does NOT silently coerce to CaptureFailed.
        val pipeline = CapturePipeline(
            rateLimitRepository = FakeRateLimitRepository(accept = true),
            hasToken = { true },
            frameExtractor = { ByteArray(SAMPLE_FRAME_SIZE) { it.toByte() } },
            verify = { Failure.Offline },
        )

        val outcome = pipeline.runCapture()

        assertEquals(Failure.Offline, outcome)
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
            verify = { error("verify must NOT be called after pipeline timeout") },
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

        fun sampleSessionRecord(): SessionRecord = SessionRecord(
            id = "test-uuid-1",
            timestampMs = FIXED_NOW,
            verdictLabel = VerdictLabel.NON_VERIFIABLE,
            headline = "test headline",
            contextLines = emptyList(),
            sourceLinks = emptyList<SourceCitation>(),
            ocrText = "",
            regionalBiasNote = null,
        )
    }
}
