package com.verisphere.app.capture

import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.gemini.VerificationOutcome
import com.verisphere.app.gemini.VerificationOutcome.Failure
import com.verisphere.app.serp.AgreementVerdict
import com.verisphere.app.serp.SerpEngine
import com.verisphere.app.serp.SerpOutcome
import com.verisphere.app.serp.SerpReference
import com.verisphere.app.storage.RateLimitRepository
import com.verisphere.app.storage.SessionRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
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
    fun `runCapture maps TimeoutCancellationException to Failure Timeout`() = runTest(
        // CAPTURE_TIMEOUT is currently 60s (smoke-bumped per deferred-work
        // 2026-05-11; pre-bump was 20s). Because runCapture switches to
        // `Dispatchers.IO`, the inner `withTimeout` runs on REAL time —
        // runTest's virtual clock cannot fast-forward it. Bump runTest's
        // default 60s timeout to 90s so the real-time withTimeout fires
        // before runTest gives up. Wall-clock cost: ≈ CAPTURE_TIMEOUT
        // (60s today). Revert this `timeout` parameter when D3.8 is
        // amended and CAPTURE_TIMEOUT returns to a smaller value.
        timeout = REAL_TIME_TEST_TIMEOUT,
    ) {
        // Story 3.1 — cosmetic mapping fix: when withTimeout cancels the
        // inner block, the outcome is Failure.Timeout (not Failure.CaptureFailed
        // as Story 1.8 originally shipped). Locks the contract Story 3.3's
        // FailureState.Timeout UX variant depends on.
        val frameExtractorDelayMs =
            CapturePipeline.CAPTURE_TIMEOUT.inWholeMilliseconds * FRAME_DELAY_MULTIPLIER
        val pipeline = CapturePipeline(
            rateLimitRepository = FakeRateLimitRepository(accept = true),
            hasToken = { true },
            // The frame extractor suspends LONGER than CAPTURE_TIMEOUT
            // (currently 60s smoke-bumped per deferred-work 2026-05-11;
            // pre-bump was 20s). 2× the timeout guarantees the withTimeout
            // fires first without tripping virtual-clock arithmetic.
            frameExtractor = {
                delay(frameExtractorDelayMs)
                ByteArray(SAMPLE_FRAME_SIZE) { it.toByte() }
            },
            verify = { error("verify must NOT be called after pipeline timeout") },
        )

        // Drive the virtual clock past the pipeline's timeout budget.
        // runTest's StandardTestDispatcher means delays are virtual —
        // advanceTimeBy moves time without sleeping. UNDISPATCHED start
        // ensures the suspending body actually begins executing (and
        // hits the inner withTimeout block) BEFORE the time advance.
        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            pipeline.runCapture()
        }
        advanceTimeBy(CapturePipeline.CAPTURE_TIMEOUT.inWholeMilliseconds + ONE_SECOND_MS)
        val outcome = deferred.await()

        assertEquals(Failure.Timeout, outcome)
    }

    @Test
    fun `pipeline propagates verify Failure Offline unchanged`() = runTest {
        // Story 3.1 — lock the routing contract: whatever Failure the
        // verify lambda returns, the pipeline returns it unchanged. No
        // coercion to CaptureFailed.
        assertFailurePropagates(Failure.Offline)
    }

    @Test
    fun `pipeline propagates verify Failure Timeout unchanged`() = runTest {
        assertFailurePropagates(Failure.Timeout)
    }

    @Test
    fun `pipeline propagates verify Failure ApiQuotaExhausted unchanged`() = runTest {
        assertFailurePropagates(Failure.ApiQuotaExhausted)
    }

    @Test
    fun `pipeline propagates verify Failure MalformedResponse unchanged`() = runTest {
        assertFailurePropagates(Failure.MalformedResponse)
    }

    @Test
    fun `pipeline propagates verify Failure HttpError preserving status code`() = runTest {
        // The carried int must round-trip — verifies HttpError(code: Int)
        // is not collapsed to a sentinel.
        val pipeline = CapturePipeline(
            rateLimitRepository = FakeRateLimitRepository(accept = true),
            hasToken = { true },
            frameExtractor = { ByteArray(SAMPLE_FRAME_SIZE) { it.toByte() } },
            verify = { Failure.HttpError(HTTP_SERVICE_UNAVAILABLE) },
        )

        val outcome = pipeline.runCapture()

        assertTrue("expected HttpError but got $outcome", outcome is Failure.HttpError)
        assertEquals(HTTP_SERVICE_UNAVAILABLE, (outcome as Failure.HttpError).code)
    }

    @Test
    fun `pipeline propagates verify Failure DailyLimitReached unchanged`() = runTest {
        // Symmetric coverage — DailyLimitReached is normally produced
        // BEFORE verify runs, but if a future refactor surfaced it from
        // the network layer, the routing contract must still hold.
        assertFailurePropagates(Failure.DailyLimitReached)
    }

    @Test
    fun `pipeline propagates verify Failure PermissionDenied unchanged`() = runTest {
        assertFailurePropagates(Failure.PermissionDenied)
    }

    @Test
    fun `pipeline propagates verify Failure CaptureFailed unchanged`() = runTest {
        assertFailurePropagates(Failure.CaptureFailed)
    }

    @Test
    fun `runCapture re-throws non-timeout CancellationException`() = runTest {
        // Story 3.1 — preserve structured concurrency. A parent-scope
        // CancellationException (e.g. service teardown mid-capture) must
        // propagate so the caller can abandon dispatch; coercing it to
        // Failure.CaptureFailed would drive the BubbleStateMachine to
        // Idle via a fake outcome. Mirrors GeminiClient.verify's
        // CancellationException handling (Story 1.9 patch P4).
        //
        // We do NOT use @Test(expected = CancellationException::class) —
        // runTest participates in structured concurrency and re-routes
        // child CancellationExceptions through its own scope, so the
        // JUnit expected-exception mechanism never sees it. Manual try/
        // catch around an explicit child coroutine isolates the throw.
        val pipeline = CapturePipeline(
            rateLimitRepository = FakeRateLimitRepository(accept = true),
            hasToken = { true },
            frameExtractor = { throw CancellationException("parent scope cancelled") },
            verify = { error("verify must NOT be called when capture is cancelled") },
        )

        var caught: CancellationException? = null
        val child = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                pipeline.runCapture()
                fail("expected CancellationException to propagate")
            } catch (e: CancellationException) {
                caught = e
            }
        }
        child.await()

        assertNotNull("CancellationException must propagate from runCapture", caught)
        assertEquals("parent scope cancelled", caught?.message)
    }

    // ─── Epic 9 Story 9.1 — enrichWithSerp orchestration (code-review P7) ──

    @Test
    fun `enrichWithSerp skipped when shouldSkipSerp returns true`() = runTest {
        // Code-review P7 — quota cooldown active → no SerpAPI call,
        // Gemini verdict returned unchanged.
        var serpCalled = false
        val pipeline = pipelineForSerp(
            verifyOutcome = VerificationOutcome.Verdict(sampleSessionRecord()),
            shouldSkipSerp = { true },
            serpSearch = { _ ->
                serpCalled = true
                error("serpSearch must NOT be called when shouldSkipSerp returns true")
            },
        )

        val outcome = pipeline.runCapture()

        assertTrue("expected Verdict but got $outcome", outcome is VerificationOutcome.Verdict)
        assertFalse("serpSearch must not fire when gate is closed", serpCalled)
        // Record is the Gemini one — no SERP fields populated.
        assertTrue((outcome as VerificationOutcome.Verdict).record.serpReferences.isEmpty())
    }

    @Test
    fun `enrichWithSerp skipped when injectionDetected is true`() = runTest {
        // Code-review DN2 — PossibleInjection state foregrounds the
        // security warning ; SERP enrichment is suppressed.
        var serpCalled = false
        val injectedRecord = sampleSessionRecord().copy(injectionDetected = true)
        val pipeline = pipelineForSerp(
            verifyOutcome = VerificationOutcome.Verdict(injectedRecord),
            shouldSkipSerp = { false },
            serpSearch = { _ ->
                serpCalled = true
                error("serpSearch must NOT be called when injectionDetected=true")
            },
        )

        val outcome = pipeline.runCapture()

        assertTrue(outcome is VerificationOutcome.Verdict)
        assertFalse("serpSearch must not fire on injection-flagged verdicts", serpCalled)
        assertTrue((outcome as VerificationOutcome.Verdict).record.injectionDetected)
    }

    @Test
    fun `enrichWithSerp Success populates record SERP fields and clears quota gate`() = runTest {
        var quotaCleared = false
        val refs = listOf(SerpReference(title = "T", url = "https://example.com", snippet = "s"))
        val pipeline = pipelineForSerp(
            verifyOutcome = VerificationOutcome.Verdict(sampleSessionRecord()),
            serpSearch = { _ ->
                SerpOutcome.Success(
                    references = refs,
                    markdown = "L'affirmation est confirmée par les sources. Le fait est exact.",
                    engineUsed = SerpEngine.GoogleAiMode,
                )
            },
            onSerpSuccess = { quotaCleared = true },
        )

        val outcome = pipeline.runCapture()

        val verdict = outcome as VerificationOutcome.Verdict
        assertEquals(refs, verdict.record.serpReferences)
        assertTrue(verdict.record.serpMarkdown.isNotBlank())
        // VerdictLabel.NON_VERIFIABLE shortcuts AgreementScorer to Inconclusive
        // regardless of markdown (locked by AgreementScorerTest). For the
        // happy non-NON_VERIFIABLE path see the next test.
        assertTrue(quotaCleared)
    }

    @Test
    fun `enrichWithSerp Quota trips onSerpQuotaExceeded and returns Gemini verdict`() = runTest {
        var quotaMarked = false
        val pipeline = pipelineForSerp(
            verifyOutcome = VerificationOutcome.Verdict(sampleSessionRecord()),
            serpSearch = { _ -> SerpOutcome.Failure.Quota },
            onSerpQuotaExceeded = { quotaMarked = true },
        )

        val outcome = pipeline.runCapture()

        assertTrue(quotaMarked)
        assertTrue(outcome is VerificationOutcome.Verdict)
        assertTrue((outcome as VerificationOutcome.Verdict).record.serpReferences.isEmpty())
    }

    @Test
    fun `enrichWithSerp NotConfigured returns Gemini verdict silently`() = runTest {
        val pipeline = pipelineForSerp(
            verifyOutcome = VerificationOutcome.Verdict(sampleSessionRecord()),
            serpSearch = { _ -> SerpOutcome.Failure.NotConfigured },
        )

        val outcome = pipeline.runCapture()

        assertTrue(outcome is VerificationOutcome.Verdict)
        assertEquals(
            "SERP fields must be empty on NotConfigured",
            AgreementVerdict.Inconclusive,
            (outcome as VerificationOutcome.Verdict).record.agreement,
        )
    }

    @Test
    fun `enrichWithSerp EmptyQuery returns Gemini verdict silently`() = runTest {
        // Code-review P4 — empty extractedClaim is no longer aliased to
        // NotConfigured but still leads to the same UX outcome.
        val pipeline = pipelineForSerp(
            verifyOutcome = VerificationOutcome.Verdict(sampleSessionRecord()),
            serpSearch = { _ -> SerpOutcome.Failure.EmptyQuery },
        )

        val outcome = pipeline.runCapture()

        assertTrue(outcome is VerificationOutcome.Verdict)
    }

    @Test
    fun `enrichWithSerp swallows exceptions and returns Gemini verdict`() = runTest {
        val pipeline = pipelineForSerp(
            verifyOutcome = VerificationOutcome.Verdict(sampleSessionRecord()),
            serpSearch = { _ -> throw IOException("network blew up unexpectedly") },
        )

        val outcome = pipeline.runCapture()

        assertTrue("unexpected SerpAPI exception must not break the pipeline", outcome is VerificationOutcome.Verdict)
    }

    @Test
    fun `enrichWithSerp NOT called when Gemini returns a Failure`() = runTest {
        var serpCalled = false
        val pipeline = pipelineForSerp(
            verifyOutcome = Failure.Offline,
            serpSearch = { _ ->
                serpCalled = true
                error("serpSearch must NOT fire on a Gemini Failure")
            },
        )

        val outcome = pipeline.runCapture()

        assertEquals(Failure.Offline, outcome)
        assertFalse(serpCalled)
    }

    @Test
    fun `enrichWithSerp caps references and truncates markdown (F15)`() = runTest {
        // Code-review F15 — encrypted SharedPrefs payload bound.
        val twentyRefs = (1..20).map {
            SerpReference(title = "T$it", url = "https://example.com/$it", snippet = "s$it")
        }
        val hugeMarkdown = "x".repeat(10_000)
        val pipeline = pipelineForSerp(
            verifyOutcome = VerificationOutcome.Verdict(sampleSessionRecord()),
            serpSearch = { _ ->
                SerpOutcome.Success(
                    references = twentyRefs,
                    markdown = hugeMarkdown,
                    engineUsed = SerpEngine.GoogleAiMode,
                )
            },
        )

        val outcome = pipeline.runCapture()
        val record = (outcome as VerificationOutcome.Verdict).record

        assertEquals(
            "references must be capped at MAX_PERSISTED_REFS (6)",
            6,
            record.serpReferences.size,
        )
        assertTrue(
            "markdown must be truncated at MAX_PERSISTED_MARKDOWN_CHARS (2000)",
            record.serpMarkdown.length <= 2_000,
        )
    }

    // ─── Reverdict (Gemini #2 after SerpAPI) ──────────────────────────

    @Test
    fun `reverdict Success replaces verdict label and headline`() = runTest {
        // The user-reported scenario: Gemini #1 says FAUX, Google synthesis
        // contradicts it, Gemini #2 (reverdict) re-issues the verdict as
        // VRAI based on the SerpAPI markdown. The final record carries
        // the re-issued verdict + the original OCR/extractedClaim.
        val geminiOneRecord = sampleSessionRecord().copy(
            verdictLabel = VerdictLabel.FALSE,
            headline = "Le joueur n'est pas blessé.",
            extractedClaim = "Le joueur X est blessé pour la Coupe du Monde 2026.",
            ocrText = "raw ocr",
        )
        val pipeline = pipelineForSerp(
            verifyOutcome = VerificationOutcome.Verdict(geminiOneRecord),
            serpSearch = { _ ->
                SerpOutcome.Success(
                    references = listOf(
                        SerpReference(title = "T", url = "https://example.com/news", snippet = "s"),
                    ),
                    markdown = "Le joueur X est officiellement forfait, blessure confirmée.",
                    engineUsed = SerpEngine.GoogleAiMode,
                )
            },
            reverdict = { _, _ ->
                com.verisphere.app.gemini.ReverdictOutcome.Success(
                    verdictLabel = VerdictLabel.TRUE,
                    headline = "C'EST VRAI : la blessure est confirmée.",
                    contextLines = listOf("Source A confirme la fracture."),
                    regionalBiasNote = null,
                )
            },
        )

        val outcome = pipeline.runCapture()

        val verdict = outcome as VerificationOutcome.Verdict
        assertEquals(VerdictLabel.TRUE, verdict.record.verdictLabel)
        assertTrue(verdict.record.headline.contains("blessure est confirmée"))
        assertEquals("Le joueur X est blessé pour la Coupe du Monde 2026.", verdict.record.extractedClaim)
        assertEquals("raw ocr", verdict.record.ocrText)
    }

    @Test
    fun `reverdict Failure keeps Gemini-1 verdict and still enriches with SERP`() = runTest {
        val baseRecord = sampleSessionRecord().copy(
            verdictLabel = VerdictLabel.FALSE,
            extractedClaim = "claim",
        )
        val pipeline = pipelineForSerp(
            verifyOutcome = VerificationOutcome.Verdict(baseRecord),
            serpSearch = { _ ->
                SerpOutcome.Success(
                    references = listOf(SerpReference(title = "T", url = "https://example.com", snippet = "s")),
                    markdown = "Some markdown text.",
                    engineUsed = SerpEngine.GoogleAiMode,
                )
            },
            reverdict = { _, _ -> com.verisphere.app.gemini.ReverdictOutcome.Failure },
        )

        val outcome = pipeline.runCapture()

        val verdict = outcome as VerificationOutcome.Verdict
        // Falls back to Gemini-1 verdict.
        assertEquals(VerdictLabel.FALSE, verdict.record.verdictLabel)
        // SERP enrichment still applied.
        assertEquals(1, verdict.record.serpReferences.size)
        assertTrue(verdict.record.serpMarkdown.isNotBlank())
    }

    @Test
    fun `reverdict skipped when injectionDetected is true`() = runTest {
        // Injection-tainted captures must not feed the reverdict prompt
        // (skip-conditions in applyReverdict). SERP is also skipped per
        // DN2 ; nothing in the verdict record changes.
        var reverdictCalled = false
        val baseRecord = sampleSessionRecord().copy(
            verdictLabel = VerdictLabel.FALSE,
            extractedClaim = "claim",
            injectionDetected = true,
        )
        val pipeline = pipelineForSerp(
            verifyOutcome = VerificationOutcome.Verdict(baseRecord),
            serpSearch = { _ ->
                SerpOutcome.Success(
                    references = emptyList(),
                    markdown = "irrelevant",
                    engineUsed = SerpEngine.GoogleAiMode,
                )
            },
            reverdict = { _, _ ->
                reverdictCalled = true
                error("reverdict must NOT fire when injectionDetected=true")
            },
        )

        pipeline.runCapture()

        assertFalse("reverdict must be skipped when injectionDetected=true", reverdictCalled)
    }

    @Test
    fun `reverdict skipped when extractedClaim is blank`() = runTest {
        var reverdictCalled = false
        val baseRecord = sampleSessionRecord().copy(
            verdictLabel = VerdictLabel.FALSE,
            extractedClaim = "",
        )
        val pipeline = pipelineForSerp(
            verifyOutcome = VerificationOutcome.Verdict(baseRecord),
            serpSearch = { _ ->
                SerpOutcome.Success(
                    references = listOf(SerpReference(title = "T", url = "https://example.com", snippet = "s")),
                    markdown = "markdown",
                    engineUsed = SerpEngine.GoogleAiMode,
                )
            },
            reverdict = { _, _ ->
                reverdictCalled = true
                error("reverdict must NOT fire when extractedClaim is blank")
            },
        )

        pipeline.runCapture()

        assertFalse("reverdict must be skipped when extractedClaim is blank", reverdictCalled)
    }

    private fun pipelineForSerp(
        verifyOutcome: VerificationOutcome,
        shouldSkipSerp: () -> Boolean = { false },
        serpSearch: suspend (String) -> SerpOutcome = { _ -> SerpOutcome.Failure.NotConfigured },
        onSerpQuotaExceeded: () -> Unit = {},
        onSerpSuccess: () -> Unit = {},
        reverdict: suspend (String, String) -> com.verisphere.app.gemini.ReverdictOutcome =
            { _, _ -> com.verisphere.app.gemini.ReverdictOutcome.Failure },
    ): CapturePipeline = CapturePipeline(
        rateLimitRepository = FakeRateLimitRepository(accept = true),
        hasToken = { true },
        frameExtractor = { ByteArray(SAMPLE_FRAME_SIZE) { it.toByte() } },
        verify = { verifyOutcome },
        serpSearch = serpSearch,
        shouldSkipSerp = shouldSkipSerp,
        onSerpQuotaExceeded = onSerpQuotaExceeded,
        onSerpSuccess = onSerpSuccess,
        reverdict = reverdict,
    )

    /**
     * Helper for the symmetric Failure pass-through tests. Locks the
     * "verify returns X → pipeline returns same X" contract for any
     * Failure variant that does not carry state (i.e. excludes
     * Failure.HttpError, which has its own test for code round-trip).
     */
    private suspend fun assertFailurePropagates(expected: Failure) {
        val pipeline = CapturePipeline(
            rateLimitRepository = FakeRateLimitRepository(accept = true),
            hasToken = { true },
            frameExtractor = { ByteArray(SAMPLE_FRAME_SIZE) { it.toByte() } },
            verify = { expected },
        )

        val outcome = pipeline.runCapture()

        assertEquals(expected, outcome)
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

        /** One-second slack added to the runtime CAPTURE_TIMEOUT in the timeout test. */
        private const val ONE_SECOND_MS: Long = 1_000L

        /**
         * Frame-extractor delay multiplier in the timeout test — the
         * delay must outlast CAPTURE_TIMEOUT but stay small enough to
         * keep virtual-clock arithmetic well clear of `Long.MAX_VALUE`
         * (avoids `UncompletedCoroutinesError` from overflow at the
         * dispatcher level). 2× is generous; CAPTURE_TIMEOUT ≤ 1 day
         * is the practical bound on which this multiplier is safe.
         */
        private const val FRAME_DELAY_MULTIPLIER: Long = 2L

        /** HTTP 503 — used as the carried code in the HttpError round-trip test. */
        private const val HTTP_SERVICE_UNAVAILABLE: Int = 503

        /**
         * Real-time test timeout for the single test that exercises
         * `withTimeout` end-to-end (timeout test). Must exceed
         * CAPTURE_TIMEOUT (currently 100s after the reverdict bump) so
         * the real-time withTimeout fires before runTest gives up.
         */
        private val REAL_TIME_TEST_TIMEOUT = 130.seconds

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
