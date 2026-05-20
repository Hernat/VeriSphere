package com.verisphere.app.serp

import android.util.Log
import com.verisphere.app.util.tag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Epic 9 Story 9.1 — single class allowed to talk to `serpapi.com`.
 * Boundary discipline (architecture line 752, AR15+AR16) mirrors
 * [com.verisphere.app.gemini.GeminiClient].
 *
 * **Hybrid engine strategy** : per Epic 9 plan, the client tries
 * `engine=google_ai_mode` first (richer payload : reconstructed_markdown +
 * references); if that response is empty (AI mode not available for the
 * query, common for non-English / niche claims), it falls back to
 * `engine=google` (organic_results). The fallback synthesises a 1-3
 * sentence markdown from the top-3 organic snippets so the downstream
 * [AgreementScorer] always has something to score against.
 *
 * **Fallback trigger** (code-review P5) — only [SerpOutcome.Failure.EmptyPayload]
 * (200 OK with refs=[] AND markdown="") triggers the `engine=google`
 * fallback. Genuine [SerpOutcome.Failure.MalformedResponse] (parse error
 * or non-quota error envelope) and every other Failure variant return
 * immediately to avoid burning a second SerpAPI credit on a request that
 * already failed for a non-recoverable reason.
 *
 * **Graceful degradation** : returns [SerpOutcome.Failure.NotConfigured]
 * when `BuildConfig.SERP_API_KEY` is empty (contributor without a SerpAPI
 * account) and [SerpOutcome.Failure.EmptyQuery] when the caller passes a
 * blank query (Gemini returned no `extractedClaim`).
 * [CapturePipeline] interprets every Failure variant as "use Gemini-only
 * verdict" — no user-visible error.
 *
 * **Timeout** : single per-call timeout of [CALL_TIMEOUT_SECONDS] s, enforced
 * via a derived OkHttp client in [com.verisphere.app.AppContainer.serpApiClient]
 * (code-review P2 — the shared 60 s budget was inherited by mistake, so
 * the client now receives a callTimeout-bounded clone). Covers AI mode +
 * fallback combined.
 *
 * **No retries** : SerpAPI failures are silently absorbed by the
 * pipeline; retrying here would only add latency without changing the
 * graceful-degradation outcome.
 *
 * **API key handling** (code-review P3) — SerpAPI requires the key as a
 * URL query parameter (no Authorization-header support per their REST
 * API). The key is appended LAST in the URL so partial-URL truncation in
 * exception logs leaks the least-recoverable suffix; combined with
 * R8-stripped logs and OkHttp's missing logging interceptor in release
 * builds, this matches the same threat surface as [GeminiClient]'s
 * `?key=` pattern. Documented in `SECURITY.md` rotation runbook.
 */
class SerpApiClient(
    private val httpClient: OkHttpClient,
    /**
     * Story 10.1 — API key provided by a lambda invoked fresh on every
     * [search] call. Wired in [com.verisphere.app.AppContainer] to
     * [com.verisphere.app.onboarding.OnboardingOrchestrator.readUserSerpApiKey]
     * so the user-entered Settings value takes effect on the very next
     * request. A blank return value short-circuits to
     * [SerpOutcome.Failure.NotConfigured] (graceful disable —
     * SerpAPI is optional V1 enrichment per Epic 9 plan).
     */
    private val apiKeyProvider: () -> String,
    private val endpointBase: String = ENDPOINT_BASE,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * Run a fact-check search for [query]. Returns [SerpOutcome] without
     * throwing — every internal exception is mapped to a typed failure
     * variant.
     *
     * @param query the fact-check target — typically Gemini's
     *              `extractedClaim` (already stripped of social-media chrome).
     */
    suspend fun search(query: String): SerpOutcome {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            return SerpOutcome.Failure.NotConfigured
        }
        if (query.isBlank()) {
            // Code-review P4 — distinguished from NotConfigured so the
            // pipeline can log "no claim to fact-check" vs "no API key"
            // distinctly. No HTTP call made.
            return SerpOutcome.Failure.EmptyQuery
        }

        // Try AI mode first.
        val aiOutcome = runEngine(query, SerpEngine.GoogleAiMode, apiKey)
        if (aiOutcome is SerpOutcome.Success) {
            // Code-review P5 — accept any Success (refs OR markdown
            // non-empty) ; mapAiMode only returns Success when at least
            // one of the two is non-empty, so the previous
            // `references.isNotEmpty()` gate rejected legitimate markdown-
            // only Successes and burned a second credit on the fallback.
            return aiOutcome
        }
        // AI mode returned empty payload — try classic Google.
        // Code-review P5 — ONLY EmptyPayload triggers the fallback. Genuine
        // MalformedResponse / HttpError / network failures all return
        // immediately ; a second call would burn a credit without
        // improving the outcome.
        if (aiOutcome is SerpOutcome.Failure.EmptyPayload) {
            return runEngine(query, SerpEngine.Google, apiKey)
        }
        return aiOutcome
    }

    private suspend fun runEngine(query: String, engine: SerpEngine, apiKey: String): SerpOutcome {
        val url = endpointBase.toHttpUrl().newBuilder()
            .addQueryParameter("engine", engine.queryParam())
            .addQueryParameter("q", query)
            // Code-review F12 — locale hints for V1 French baseline
            // (Story 7.5). SerpAPI defaults to en-US which under-fires
            // AgreementScorer's FR keyword set on French claims.
            .addQueryParameter("hl", "fr")
            .addQueryParameter("gl", "fr")
            // Code-review P3 — api_key LAST so partial-URL truncation
            // in any exception/logger leaks the least-recoverable suffix.
            .addQueryParameter("api_key", apiKey)
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val (code, body) = try {
            executeCall(request)
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "search timeout (${engine.name})")
            return SerpOutcome.Failure.Timeout
        } catch (e: InterruptedIOException) {
            Log.w(TAG, "search interrupted (${engine.name}): ${e.javaClass.simpleName}")
            return SerpOutcome.Failure.Timeout
        } catch (e: UnknownHostException) {
            Log.w(TAG, "search offline (${engine.name})")
            return SerpOutcome.Failure.Offline
        } catch (e: ConnectException) {
            Log.w(TAG, "search connect refused (${engine.name})")
            return SerpOutcome.Failure.Offline
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "search IO error (${engine.name}): ${e.javaClass.simpleName}")
            return SerpOutcome.Failure.Offline
        }

        if (code == HTTP_TOO_MANY_REQUESTS) {
            Log.w(TAG, "search HTTP 429 (${engine.name}) — quota")
            return SerpOutcome.Failure.Quota
        }
        if (code !in HTTP_SUCCESS_RANGE) {
            Log.w(TAG, "search HTTP $code (${engine.name})")
            return SerpOutcome.Failure.HttpError(code)
        }

        return parseResponse(body, engine)
    }

    private fun parseResponse(rawBody: String, engine: SerpEngine): SerpOutcome {
        val parsed: SerpApiResponse = try {
            json.decodeFromString(SerpApiResponse.serializer(), rawBody)
        } catch (e: SerializationException) {
            Log.w(TAG, "parse failed (${engine.name}): ${e.javaClass.simpleName}")
            return SerpOutcome.Failure.MalformedResponse
        }

        // Embedded SerpAPI error envelope.
        parsed.error?.let { err ->
            // Code-review F11 — word-boundary detection so "separate",
            // "corrupt data rate", "moderate" don't false-positive on
            // the "rate" keyword. \b is ASCII-only here which is fine
            // since SerpAPI error strings are English-only.
            if (QUOTA_PATTERNS.any { it.containsMatchIn(err) }) {
                Log.w(TAG, "search error (${engine.name}) — quota: $err")
                return SerpOutcome.Failure.Quota
            }
            Log.w(TAG, "search error (${engine.name}): $err")
            return SerpOutcome.Failure.MalformedResponse
        }

        return when (engine) {
            SerpEngine.GoogleAiMode -> mapAiMode(parsed)
            SerpEngine.Google -> mapGoogle(parsed)
        }
    }

    private fun mapAiMode(parsed: SerpApiResponse): SerpOutcome {
        val refs = parsed.references.map { ref ->
            SerpReference(
                title = ref.title,
                url = ref.link,
                publisher = ref.source,
                snippet = ref.snippet,
            )
        }
        val markdown = parsed.reconstructedMarkdown.orEmpty().trim()
        if (refs.isEmpty() && markdown.isEmpty()) {
            // Code-review P5 — distinguished from MalformedResponse so the
            // caller can fall back ONLY when AI mode produced no coverage,
            // not on genuine parse failures.
            return SerpOutcome.Failure.EmptyPayload
        }
        return SerpOutcome.Success(
            references = refs,
            markdown = markdown,
            engineUsed = SerpEngine.GoogleAiMode,
        )
    }

    private fun mapGoogle(parsed: SerpApiResponse): SerpOutcome {
        val refs = parsed.organicResults.map { org ->
            SerpReference(
                title = org.title,
                url = org.link,
                publisher = org.source,
                snippet = org.snippet,
            )
        }
        if (refs.isEmpty()) {
            return SerpOutcome.Failure.EmptyPayload
        }
        // Synthesise a 1-3 sentence markdown from the top snippets so the
        // AgreementScorer has signal to compare against the Gemini verdict.
        // Code-review F16 — period+space separator so the snippets don't
        // run together into a single ungrammatical sentence at render time.
        val markdown = refs.asSequence()
            .map { it.snippet }
            .filter { it.isNotBlank() }
            .take(FALLBACK_MARKDOWN_SNIPPET_COUNT)
            .joinToString(separator = ". ")
        return SerpOutcome.Success(
            references = refs,
            markdown = markdown,
            engineUsed = SerpEngine.Google,
        )
    }

    /**
     * Execute the OkHttp call inside a cancellable continuation.
     * Returns (statusCode, bodyString). Cancellation propagates to the
     * call so we don't leak connections on coroutine cancellation.
     */
    private suspend fun executeCall(request: Request): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val call: Call = httpClient.newCall(request)
                continuation.invokeOnCancellation {
                    runCatching { call.cancel() }
                }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { resp ->
                            val payload = resp.body?.string().orEmpty()
                            continuation.resume(resp.code to payload)
                        }
                    }
                })
            }
        }

    private fun SerpEngine.queryParam(): String = when (this) {
        SerpEngine.GoogleAiMode -> "google_ai_mode"
        SerpEngine.Google -> "google"
    }

    companion object {
        private val TAG = tag("SerpApiClient")

        /** Public SerpAPI search endpoint. */
        internal const val ENDPOINT_BASE = "https://serpapi.com/search"

        /** Single per-call budget (covers AI mode + fallback combined). */
        const val CALL_TIMEOUT_SECONDS: Long = 15

        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val HTTP_SUCCESS_RANGE = 200..299

        /** Whole-word indicators inside SerpAPI's `error` field that
         *  signal quota exhaustion (code-review F11 — substring matching
         *  false-positived on "separate"/"moderate"). Pre-compiled once. */
        private val QUOTA_PATTERNS: List<Regex> = listOf("limit", "quota", "credit", "rate")
            .map { kw -> Regex("\\b${Regex.escape(kw)}\\b", RegexOption.IGNORE_CASE) }

        /** How many top organic snippets to concatenate into the synthetic
         *  fallback markdown when AI mode wasn't available. Higher = more
         *  signal for AgreementScorer; lower = less noise. */
        private const val FALLBACK_MARKDOWN_SNIPPET_COUNT = 3
    }
}
