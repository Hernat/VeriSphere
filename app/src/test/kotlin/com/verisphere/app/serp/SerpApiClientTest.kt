package com.verisphere.app.serp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Epic 9 Story 9.1 — unit tests for [SerpApiClient].
 *
 * Drives the client against [MockWebServer] across :
 *   - AI mode happy path → [SerpOutcome.Success] with [SerpEngine.GoogleAiMode]
 *   - AI mode empty payload → fallback to `engine=google` → Success
 *   - HTTP 429 → [SerpOutcome.Failure.Quota]
 *   - Error envelope with "quota" → [SerpOutcome.Failure.Quota]
 *   - Network errors → Offline / Timeout
 *   - Malformed JSON → MalformedResponse
 *   - Empty key → NotConfigured (no HTTP call made)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SerpApiClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ─── (a) Happy path — AI mode ───────────────────────────────────

    @Test
    fun `AI mode returns Success with references + markdown`() = runTest {
        server.enqueue(MockResponse().setResponseCode(HTTP_OK).setBody(AI_MODE_SUCCESS_BODY))
        val client = newClient()

        val outcome = client.search("Tour Eiffel 330 mètres")

        assertTrue("expected Success but got $outcome", outcome is SerpOutcome.Success)
        val success = outcome as SerpOutcome.Success
        assertEquals(SerpEngine.GoogleAiMode, success.engineUsed)
        assertEquals(2, success.references.size)
        assertEquals("Tour Eiffel — Wikipédia", success.references[0].title)
        assertEquals("https://fr.wikipedia.org/wiki/Tour_Eiffel", success.references[0].url)
        assertTrue("markdown not empty", success.markdown.isNotBlank())
    }

    // ─── (b) Fallback path ──────────────────────────────────────────

    @Test
    fun `AI mode empty triggers google fallback Success`() = runTest {
        // First call (AI mode) returns empty payload → triggers fallback
        server.enqueue(MockResponse().setResponseCode(HTTP_OK).setBody(AI_MODE_EMPTY_BODY))
        // Second call (engine=google) returns organic results
        server.enqueue(MockResponse().setResponseCode(HTTP_OK).setBody(GOOGLE_SUCCESS_BODY))
        val client = newClient()

        val outcome = client.search("niche query")

        assertTrue("expected Success but got $outcome", outcome is SerpOutcome.Success)
        val success = outcome as SerpOutcome.Success
        assertEquals(SerpEngine.Google, success.engineUsed)
        assertEquals(2, success.references.size)
        assertTrue(
            "markdown should concatenate organic snippets",
            success.markdown.contains("first snippet") || success.markdown.contains("second snippet"),
        )

        // Verify 2 requests were made (AI mode + google)
        assertEquals(2, server.requestCount)
    }

    // ─── (c) Quota — HTTP 429 ───────────────────────────────────────

    @Test
    fun `HTTP 429 returns Failure_Quota immediately (no fallback)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(HTTP_TOO_MANY_REQUESTS).setBody("{}"))
        val client = newClient()

        val outcome = client.search("any query")

        assertEquals(SerpOutcome.Failure.Quota, outcome)
        // Verify only 1 request — quota means stop, don't try fallback
        assertEquals(1, server.requestCount)
    }

    // ─── (d) Quota — error envelope ─────────────────────────────────

    @Test
    fun `error envelope mentioning quota returns Failure_Quota`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(HTTP_OK).setBody(
                """{"error":"You have reached your monthly search limit."}""",
            ),
        )
        val client = newClient()

        val outcome = client.search("any query")

        assertEquals(SerpOutcome.Failure.Quota, outcome)
    }

    @Test
    fun `error envelope without quota keyword returns Failure_MalformedResponse (no fallback)`() = runTest {
        // Code-review P5 — Genuine MalformedResponse no longer triggers
        // fallback. The fallback path is reserved for EmptyPayload only
        // (200 OK + refs=[] + markdown=""), so we don't burn a second
        // credit on a request that already errored.
        server.enqueue(
            MockResponse().setResponseCode(HTTP_OK).setBody(
                """{"error":"engine not supported for locale"}""",
            ),
        )
        val client = newClient()

        val outcome = client.search("test")

        assertEquals(SerpOutcome.Failure.MalformedResponse, outcome)
        assertEquals(
            "MalformedResponse must NOT trigger a fallback call (code-review P5)",
            1,
            server.requestCount,
        )
    }

    @Test
    fun `error envelope containing rate as substring of separate is NOT quota`() = runTest {
        // Code-review F11 — substring matching false-positived on
        // "separate"/"moderate"/"corruption rate". Word-boundary regex
        // requires the keyword to be a standalone token.
        server.enqueue(
            MockResponse().setResponseCode(HTTP_OK).setBody(
                """{"error":"please separate your queries"}""",
            ),
        )
        val client = newClient()

        val outcome = client.search("test")

        assertEquals(
            "substring 'rate' inside 'separate' must NOT trip quota detection",
            SerpOutcome.Failure.MalformedResponse,
            outcome,
        )
    }

    // ─── (e) Network errors ────────────────────────────────────────

    @Test
    fun `disconnect during response returns network or malformed Failure`() = runTest {
        // Code-review P5 — DISCONNECT_DURING_RESPONSE_BODY can produce
        // either an IOException at body-read time (→ Failure.Offline)
        // OR a partial-body string that fails JSON parsing
        // (→ Failure.MalformedResponse). Both are valid; both are
        // "Gemini-only verdict" at the pipeline. Under the prior
        // semantics MalformedResponse silently fell back through a
        // second call, masking which case actually fired.
        server.enqueue(
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val client = newClient()

        val outcome = client.search("test")

        assertTrue(
            "expected Offline / Timeout / MalformedResponse but got $outcome",
            outcome is SerpOutcome.Failure.Offline ||
                outcome is SerpOutcome.Failure.Timeout ||
                outcome is SerpOutcome.Failure.MalformedResponse,
        )
    }

    // ─── (f) Malformed response ─────────────────────────────────────

    @Test
    fun `non-JSON response returns MalformedResponse without fallback`() = runTest {
        // Code-review P5 + F19 — parse failures no longer trigger
        // fallback (was: triggered, conflating "AI mode unavailable"
        // with "actual parse failure"). Also asserts requestCount==1
        // explicitly so a future refactor that re-enables fallback on
        // MalformedResponse fails this test loudly.
        server.enqueue(MockResponse().setResponseCode(HTTP_OK).setBody("not valid json {{{"))
        val client = newClient()

        val outcome = client.search("test")

        assertEquals(SerpOutcome.Failure.MalformedResponse, outcome)
        assertEquals(
            "MalformedResponse must NOT trigger fallback — only EmptyPayload does",
            1,
            server.requestCount,
        )
    }

    // ─── (g) NotConfigured ──────────────────────────────────────────

    @Test
    fun `empty api key returns NotConfigured without making HTTP call`() = runTest {
        val client = SerpApiClient(
            httpClient = okHttpClient(),
            apiKey = "",
            endpointBase = server.url("/search").toString(),
        )

        val outcome = client.search("any query")

        assertEquals(SerpOutcome.Failure.NotConfigured, outcome)
        assertEquals("expected zero HTTP calls", 0, server.requestCount)
    }

    @Test
    fun `blank query returns EmptyQuery without making HTTP call`() = runTest {
        // Code-review P4 — distinguished from NotConfigured so telemetry
        // can tell "no claim extracted" apart from "no API key".
        val client = newClient()

        val outcome = client.search("   ")

        assertEquals(SerpOutcome.Failure.EmptyQuery, outcome)
        assertEquals(0, server.requestCount)
    }

    // ─── (h) HttpError mapping ──────────────────────────────────────

    @Test
    fun `HTTP 500 returns Failure_HttpError`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        val client = newClient()

        val outcome = client.search("test")

        assertTrue("expected HttpError but got $outcome", outcome is SerpOutcome.Failure.HttpError)
        assertEquals(500, (outcome as SerpOutcome.Failure.HttpError).code)
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private fun newClient(): SerpApiClient = SerpApiClient(
        httpClient = okHttpClient(),
        apiKey = TEST_API_KEY,
        endpointBase = server.url("/search").toString(),
    )

    private fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val CALL_TIMEOUT_SECONDS = 5L
        private const val TEST_API_KEY = "test-key-fixture"

        private const val AI_MODE_SUCCESS_BODY = """
        {
          "reconstructed_markdown": "La Tour Eiffel mesure 330 mètres avec son antenne. Construite en 1889.",
          "references": [
            {"title": "Tour Eiffel — Wikipédia", "link": "https://fr.wikipedia.org/wiki/Tour_Eiffel", "snippet": "...", "source": "Wikipédia", "index": 0},
            {"title": "Site officiel Tour Eiffel", "link": "https://www.toureiffel.paris", "snippet": "...", "source": "Tour Eiffel", "index": 1}
          ]
        }
        """

        private const val AI_MODE_EMPTY_BODY = """
        {
          "reconstructed_markdown": "",
          "references": []
        }
        """

        private const val GOOGLE_SUCCESS_BODY = """
        {
          "organic_results": [
            {"title": "Result 1", "link": "https://example.com/1", "snippet": "first snippet content", "source": "Example", "position": 1},
            {"title": "Result 2", "link": "https://example.com/2", "snippet": "second snippet content", "source": "Example", "position": 2}
          ]
        }
        """
    }
}
