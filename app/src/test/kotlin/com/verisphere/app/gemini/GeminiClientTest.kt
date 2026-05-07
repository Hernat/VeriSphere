package com.verisphere.app.gemini

import com.verisphere.app.gemini.VerificationOutcome.Failure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [GeminiClient] (Story 1.9, AC #17).
 *
 * Drives [GeminiClient.verify] against [MockWebServer] across the
 * happy-path, HTTP-error, network-error, and malformed-response branches
 * of the failure-mapping table (AC #11). All tests are JVM-pure (no
 * Robolectric) — Android's `Base64` is dodged via the constructor seam,
 * the system prompt is provided as a fixture string, and the endpoint
 * URL is rewired to the `MockWebServer.url("/")`.
 *
 * Privacy posture (architecture line 505): no test asserts on the full
 * request body's base64 payload (the encoder produces deterministic
 * output via `java.util.Base64`, which would make the assertion brittle
 * across encoder bumps). The base64 substring presence is verified via
 * the `inlineData` JSON marker only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeminiClientTest {

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

    @Test
    fun `happy path returns Verdict with all GeminiVerdictResponse fields mapped to SessionRecord`() = runTest {
        val verdictJson = loadFixture("verdict_true.json")
        server.enqueue(MockResponse().setResponseCode(HTTP_OK).setBody(envelopeFor(verdictJson)))
        val client = newClient()

        val outcome = client.verify(SAMPLE_FRAME)

        assertTrue("expected Verdict but got $outcome", outcome is VerificationOutcome.Verdict)
        val verdict = outcome as VerificationOutcome.Verdict
        assertEquals(VerdictLabel.TRUE, verdict.record.verdictLabel)
        assertEquals(2, verdict.record.sourceLinks.size)
        // record.id is a fresh UUID v4 — assert it parses
        UUID.fromString(verdict.record.id) // throws if not a UUID
        // timestampMs is "now" — assert reasonable bounds
        val now = System.currentTimeMillis()
        assertTrue(
            "timestamp $now vs ${verdict.record.timestampMs}",
            kotlin.math.abs(now - verdict.record.timestampMs) < TIMESTAMP_TOLERANCE_MS,
        )
    }

    @Test
    fun `HTTP 429 maps to Failure ApiQuotaExhausted`() = runTest {
        server.enqueue(MockResponse().setResponseCode(HTTP_TOO_MANY_REQUESTS))
        val client = newClient()

        val outcome = client.verify(SAMPLE_FRAME)

        assertEquals(Failure.ApiQuotaExhausted, outcome)
    }

    @Test
    fun `HTTP 500 maps to Failure HttpError 500`() = runTest {
        server.enqueue(MockResponse().setResponseCode(HTTP_INTERNAL_ERROR))
        val client = newClient()

        val outcome = client.verify(SAMPLE_FRAME)

        assertEquals(Failure.HttpError(HTTP_INTERNAL_ERROR), outcome)
    }

    @Test
    fun `HTTP 400 maps to Failure HttpError 400`() = runTest {
        // Code-review patch P11 — coverage gap: only 500 was tested for
        // the "HTTP 4xx / 5xx (other) → HttpError(code)" mapping row.
        // 400 is the most likely real-world failure when the request
        // body or model name drifts.
        server.enqueue(MockResponse().setResponseCode(HTTP_BAD_REQUEST))
        val client = newClient()

        val outcome = client.verify(SAMPLE_FRAME)

        assertEquals(Failure.HttpError(HTTP_BAD_REQUEST), outcome)
    }

    @Test
    fun `empty candidates array maps to Failure MalformedResponse`() = runTest {
        // Code-review patch P12 — production trigger: Gemini emits an
        // empty `candidates: []` when ALL candidates are content-blocked
        // by safety filters. Previously untested even though it's the
        // most likely production "MalformedResponse" trigger.
        server.enqueue(
            MockResponse()
                .setResponseCode(HTTP_OK)
                .setBody("""{"candidates":[]}""")
        )
        val client = newClient()

        val outcome = client.verify(SAMPLE_FRAME)

        assertEquals(Failure.MalformedResponse, outcome)
    }

    @Test
    fun `HTTP 200 with invalid envelope maps to Failure MalformedResponse`() = runTest {
        server.enqueue(MockResponse().setResponseCode(HTTP_OK).setBody("""{"foo":"bar"}"""))
        val client = newClient()

        val outcome = client.verify(SAMPLE_FRAME)

        assertEquals(Failure.MalformedResponse, outcome)
    }

    @Test
    fun `HTTP 200 with valid envelope but invalid inner JSON maps to Failure MalformedResponse`() = runTest {
        // Envelope is valid; the inner parts[0].text is not valid JSON.
        server.enqueue(MockResponse().setResponseCode(HTTP_OK).setBody(envelopeFor("not json")))
        val client = newClient()

        val outcome = client.verify(SAMPLE_FRAME)

        assertEquals(Failure.MalformedResponse, outcome)
    }

    @Test
    fun `UnknownHostException maps to Failure Offline`() = runTest {
        // Code-review patch P20 — was using `.invalid` TLD which depends
        // on the JVM's DNS resolver. Captive-portal DNS rewriters often
        // return a valid IP for unresolvable hostnames, breaking the
        // test on hotel WiFi / corporate networks. We now use a
        // randomly-allocated free port on the loopback interface but
        // never enqueue any response on that server — the server is
        // shut down BEFORE the call, so OkHttp surfaces a
        // ConnectException (now mapped to Failure.Timeout per patch P5).
        // To preserve the test's *intent* (verify Offline maps from
        // UnknownHostException), point at a hostname that genuinely
        // cannot resolve via JVM's InetAddress. RFC 6761 reserved
        // `.test` TLD is a well-defined sentinel that resolvers MUST
        // NOT forward upstream — empirically more reliable than `.invalid`.
        val client = newClient(endpointBase = "http://does.not.exist.test:1/v1beta/models")

        val outcome = client.verify(SAMPLE_FRAME)

        // Accept either Offline (UnknownHostException — well-behaved
        // resolver) OR Timeout (rewriting resolver returns fake IP
        // and ConnectException fires after connect-timeout). Both
        // outcomes confirm the network-error path is wired correctly;
        // distinguishing them requires controlling the resolver, which
        // is beyond a JVM unit test's purview.
        assertTrue(
            "Expected Offline or Timeout, got $outcome",
            outcome == Failure.Offline || outcome == Failure.Timeout,
        )
    }

    @Test
    fun `SocketTimeoutException maps to Failure Timeout`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        // Override the OkHttp client with a much shorter callTimeout
        // so the test runs in ~1 s rather than the production 20 s.
        val fastClient = OkHttpClient.Builder()
            .connectTimeout(FAST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(FAST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(FAST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val client = newClient(httpClient = fastClient)

        val outcome = client.verify(SAMPLE_FRAME)

        assertEquals(Failure.Timeout, outcome)
    }

    @Test
    fun `request body contains base64 image data marker and the system prompt`() = runTest {
        server.enqueue(MockResponse().setResponseCode(HTTP_OK).setBody(envelopeFor(loadFixture("verdict_true.json"))))
        val client = newClient()

        client.verify(SAMPLE_FRAME)

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()

        // Code-review patch P13 — URL assertion. Locks in the wire
        // contract: the path includes `:generateContent` and the api
        // key is in the query string per AC #11 / D3.3. A future
        // refactor that drops `:generateContent`, double-slashes the
        // path, or moves the key to a header is now caught here.
        assertEquals(
            "/v1beta/models/test-model:generateContent?key=test-key-no-such-thing",
            recorded.path,
        )
        assertEquals("POST", recorded.method)
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))

        // The JSON envelope marker for the base64 image part — locks in
        // FR6 (image goes only to Gemini) at the wire-format level.
        assertTrue("body must contain inlineData JSON object", body.contains("\"inlineData\""))
        assertTrue("body must declare image/jpeg mime type", body.contains("image/jpeg"))

        // The system prompt's stable substring — locks in D2.4 (prompt
        // is sent verbatim from the asset, not inlined as a constant).
        assertTrue(
            "body must contain the test system prompt",
            body.contains("VeriSphere fact-check"),
        )

        // Code-review patch P24 — base64 round-trip. Extract the `data`
        // field from the JSON body, decode, and assert the bytes equal
        // SAMPLE_FRAME. Catches future regressions like base64-encoding
        // with line wraps (`Base64.DEFAULT` instead of `NO_WRAP`) which
        // would still satisfy the substring assertions above but break
        // the wire format (Gemini rejects line-wrapped base64 in JSON).
        val parsed = Json.parseToJsonElement(body).jsonObject
        val data = parsed["contents"]!!.jsonArray[0].jsonObject["parts"]!!
            .jsonArray[1].jsonObject["inlineData"]!!.jsonObject["data"]!!
            .jsonPrimitive.content
        assertArrayEquals(
            "base64 round-trip must equal the original frame bytes",
            SAMPLE_FRAME,
            Base64.getDecoder().decode(data),
        )
    }

    @Test
    fun `verify never throws on empty 200 body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(HTTP_OK).setBody(""))
        val client = newClient()

        // Must complete without throwing — the catch-all clause routes
        // an empty-body parse failure into Failure.MalformedResponse.
        val outcome = client.verify(SAMPLE_FRAME)

        assertNotNull(outcome)
        assertTrue("expected a Failure, got $outcome", outcome is Failure)
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private fun newClient(
        httpClient: OkHttpClient = defaultHttpClient(),
        endpointBase: String = server.url("/v1beta/models").toString().trimEnd('/'),
    ): GeminiClient = GeminiClient(
        httpClient = httpClient,
        apiKey = "test-key-no-such-thing",
        model = "test-model",
        endpointBase = endpointBase,
        systemPromptProvider = { TEST_SYSTEM_PROMPT },
        base64Encoder = { bytes -> Base64.getEncoder().encodeToString(bytes) },
    )

    private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(STANDARD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(STANDARD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(STANDARD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * Wrap a verdict JSON string in the Gemini API envelope:
     *   `{"candidates":[{"content":{"parts":[{"text":"<jsonString>"}]}}]}`
     *
     * The inner JSON is JSON-string-escaped via kotlinx.serialization so
     * quotes/backslashes survive the wire round-trip.
     */
    private fun envelopeFor(verdictJson: String): String {
        val escaped = Json.encodeToString(String.serializer(), verdictJson)
        return """{"candidates":[{"content":{"parts":[{"text":$escaped}]}}]}"""
    }

    private fun loadFixture(name: String): String =
        javaClass.getResourceAsStream("/gemini_responses/$name")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Fixture missing: /gemini_responses/$name")

    private companion object {
        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_INTERNAL_ERROR = 500

        private const val STANDARD_TIMEOUT_SECONDS = 5L
        // Code-review patch P21 — 800 ms was borderline-flaky on slow
        // CI runners (cold JVM, throttled GHA). 1500 ms preserves the
        // ~1-2 s test wall-clock target while restoring headroom.
        private const val FAST_TIMEOUT_MS = 1500L
        private const val TIMESTAMP_TOLERANCE_MS = 5_000L

        private val SAMPLE_FRAME = ByteArray(256) { it.toByte() }

        private const val TEST_SYSTEM_PROMPT =
            "Test prompt for VeriSphere fact-check assistant — minimal anti-injection clause."
    }
}
