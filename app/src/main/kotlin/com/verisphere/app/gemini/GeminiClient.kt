package com.verisphere.app.gemini

import android.util.Log
import com.verisphere.app.BuildConfig
import com.verisphere.app.gemini.VerificationOutcome.Failure
import com.verisphere.app.storage.SessionRecord
import com.verisphere.app.util.tag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Single class allowed to construct requests to
 * `generativelanguage.googleapis.com` — boundary discipline (architecture
 * line 752, AR15 + AR16). All other components receive a typed
 * [VerificationOutcome] from [verify]; raw HTTP, raw JSON, and the
 * Gemini-side wire format never escape this class.
 *
 * **Story 1.9 contract** (PRD FR5–FR9, FR13, FR26, FR27; NFR1, NFR8, NFR17):
 *  - [verify] sends the captured JPEG to Gemini Vision + Search Grounding,
 *    grounded by the system prompt at `assets/system_prompt_v1.txt` (D2.4).
 *  - The response is constrained by the structured-output schema built
 *    by [GeminiRequest.buildResponseSchema] (D3.4).
 *  - The verdict is mapped to a [SessionRecord] with a fresh UUID v4 +
 *    current `System.currentTimeMillis()`.
 *
 * **No retries in Story 1.9.** `OkHttpClient.retryOnConnectionFailure(false)`
 * is configured by [com.verisphere.app.AppContainer]; Story 3.2 owns the
 * retry policy on 5xx + transient network errors (architecture D3.8).
 *
 * **Single error funnel** (architecture line 487). [verify] never throws —
 * every exception is mapped to a [Failure.*] variant per the table in
 * the story file (AC #11):
 *
 * | Trigger                                              | Outcome              |
 * |------------------------------------------------------|----------------------|
 * | `UnknownHostException`                               | `Failure.Offline`    |
 * | `SocketTimeoutException`, `InterruptedIOException`   | `Failure.Timeout`    |
 * | `ConnectException` (connect-timeout)                 | `Failure.Timeout`    |
 * | Other `IOException`                                  | `Failure.HttpError(0)` |
 * | HTTP 200 + valid envelope + valid verdict JSON       | `Verdict(record)`    |
 * | HTTP 200 + invalid envelope or malformed verdict JSON| `Failure.MalformedResponse` |
 * | HTTP 429                                             | `Failure.ApiQuotaExhausted` |
 * | HTTP 4xx / 5xx (other)                               | `Failure.HttpError(code)` |
 * | Any other `Exception` (defensive)                    | `Failure.HttpError(0)` |
 *
 * `CancellationException` is rethrown unchanged so structured concurrency
 * is preserved — parent-scope cancellation propagates instead of being
 * dressed up as a `Failure` (code-review patch P4 fix).
 *
 * **Privacy posture** (architecture line 505 — forbidden in any log):
 * OCR text, captured frame data, the API key, the full system prompt,
 * full session content. The internal logging therefore reports byte
 * counts and verdict-class names only — and the OkHttp `Throwable` is
 * NEVER passed to `Log.w` because its message often embeds the request
 * URL with `?key=$apiKey`. We log only the exception class + a sanitised
 * one-line summary (code-review patch P1 fix).
 *
 * **Threading** (architecture line 473):
 *  - Base64 encoding (CPU-bound) → `Dispatchers.Default`.
 *  - JSON build / parse (CPU-bound) → `Dispatchers.Default`.
 *  - OkHttp `Call.execute()` (blocking I/O) → `Dispatchers.IO`.
 */
class GeminiClient(
    private val httpClient: OkHttpClient,
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
    private val model: String = DEFAULT_MODEL,
    private val endpointBase: String = ENDPOINT_BASE,
    private val systemPromptProvider: () -> String,
    /**
     * Base64 encoder seam. Production wiring uses `android.util.Base64`
     * with the `NO_WRAP` flag (matches the architecture D3.3 spec — body
     * must be one line so the JSON stays valid). Tests inject
     * `java.util.Base64.getEncoder()::encodeToString` because Android's
     * stub `Base64` returns null under `unitTests.isReturnDefaultValues
     * = true`. Both encoders produce identical output for our use case
     * (`NO_WRAP` ↔ default `getEncoder()` — neither wraps lines).
     */
    private val base64Encoder: (ByteArray) -> String,
) {

    init {
        // Code-review patch P17 — model URL-segment validation. Defensive
        // against future operator typos like `model = "x/y"` that would
        // split the URL path silently. Today's constants are safe.
        require(!model.contains('/') && !model.contains('?') && !model.contains('#')) {
            "model must be a single URL path segment, was: '$model'"
        }
    }

    private val systemPrompt: String by lazy { systemPromptProvider() }
    private val responseSchema by lazy { GeminiRequest.buildResponseSchema() }

    /**
     * Tolerant JSON parser — `ignoreUnknownKeys` lets Gemini add fields
     * (e.g. `safetyRatings`, `usageMetadata`) without crashing the parse
     * (architecture validation Gap #2 alignment with `SecureStorage`'s
     * tolerance pattern). `coerceInputValues = true` is set so an
     * explicit JSON `null` for a non-nullable primitive (e.g. Gemini
     * sending `"injectionDetected": null`) coerces to the data class's
     * default rather than crashing — code-review patch P6 fix. `isLenient`
     * stays at the strict default — Gemini's output is strict JSON,
     * lenient parsing would mask server-side bugs.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * Send the captured frame to Gemini and return a typed
     * [VerificationOutcome]. Never throws — see the failure-mapping
     * table in the class KDoc.
     */
    suspend fun verify(frameJpeg: ByteArray): VerificationOutcome {
        Log.d(TAG, "verify start (frame ${frameJpeg.size} bytes)")

        val outcome = try {
            val base64 = withContext(Dispatchers.Default) {
                base64Encoder(frameJpeg)
            }
            val body = withContext(Dispatchers.Default) {
                GeminiRequest.build(
                    systemPrompt = systemPrompt,
                    base64Image = base64,
                    responseSchema = responseSchema,
                )
            }

            // Code-review patch P7 — use HttpUrl builder so the apiKey is
            // properly URL-encoded as a query parameter. Raw string
            // interpolation broke if a future key contained `&`, `=`, or
            // `#`. The endpointBase is parsed and the path segments +
            // query parameter are appended atomically.
            val url = endpointBase.toHttpUrl().newBuilder()
                .addPathSegment("$model:generateContent")
                .addQueryParameter("key", apiKey)
                .build()
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            // Code-review patch P10 — bridge OkHttp's blocking Call into
            // a cancellable coroutine. `withContext(IO)` alone does NOT
            // propagate Kotlin cancellation into `Call.cancel()`; the
            // socket would stay open until OkHttp's own callTimeout (20 s).
            // suspendCancellableCoroutine + invokeOnCancellation hooks
            // the coroutine cancellation back into Call.cancel().
            val (code, responseBody) = withContext(Dispatchers.IO) {
                executeCall(httpClient.newCall(request))
            }

            when (code) {
                HTTP_OK -> withContext(Dispatchers.Default) {
                    // Code-review patch P3 — JSON parse is CPU-bound
                    // (architecture line 473); hop to Default before
                    // calling parseVerdict.
                    parseVerdict(responseBody)
                }
                HTTP_TOO_MANY_REQUESTS -> Failure.ApiQuotaExhausted
                else -> Failure.HttpError(code)
            }
        } catch (e: CancellationException) {
            // Code-review patch P4 — preserve structured concurrency.
            // Kotlin's CancellationException is an Exception, so the
            // generic catch below would swallow it and produce a misleading
            // Failure. Rethrow so the parent scope sees the cancellation.
            throw e
        } catch (e: UnknownHostException) {
            logFailure("Offline", e)
            Failure.Offline
        } catch (e: SocketTimeoutException) {
            logFailure("Timeout (socket)", e)
            Failure.Timeout
        } catch (e: InterruptedIOException) {
            logFailure("Timeout (interrupted)", e)
            Failure.Timeout
        } catch (e: ConnectException) {
            // Code-review patch P5 — connect-timeout surfaces as
            // ConnectException (NOT SocketTimeoutException). The mapping
            // table promises connect timeout → Timeout; without this
            // explicit clause the IOException catch below routes it to
            // HttpError(0).
            logFailure("Timeout (connect)", e)
            Failure.Timeout
        } catch (e: IOException) {
            logFailure("HttpError(0) — network IO", e)
            Failure.HttpError(0)
        } catch (e: SerializationException) {
            logFailure("MalformedResponse — serialization", e)
            Failure.MalformedResponse
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Defensive backstop — architecture line 487: no exception
            // escapes the verification pipeline to the UI.
            logFailure("HttpError(0) — unexpected", e)
            Failure.HttpError(0)
        }

        logOutcome(outcome)
        return outcome
    }

    /**
     * Bridge OkHttp's blocking [Call.execute] into a cancellable
     * coroutine. On parent-scope cancellation, [Call.cancel] is invoked
     * so the underlying socket is released immediately instead of waiting
     * for OkHttp's `callTimeout` (code-review patch P10).
     */
    private suspend fun executeCall(call: Call): Pair<Int, String> =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val payload = if (resp.code == HTTP_OK) {
                            try {
                                resp.body?.string().orEmpty()
                            } catch (e: IOException) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(e)
                                }
                                return
                            }
                        } else {
                            ""
                        }
                        if (continuation.isActive) {
                            continuation.resume(resp.code to payload)
                        }
                    }
                }
            })
        }

    private fun parseVerdict(rawBody: String): VerificationOutcome {
        // Two-stage parse (story Critical Dev Note):
        //   1. Outer envelope: candidates[0].content.parts[0].text → String
        //   2. Inner JSON string: GeminiVerdictResponse
        // Either parse failure → MalformedResponse via the catch in verify().
        val envelope = json.decodeFromString(GenerateContentResponse.serializer(), rawBody)
        val verdictJsonText = envelope.candidates.firstOrNull()
            ?.content?.parts?.firstOrNull()?.text
        if (verdictJsonText.isNullOrBlank()) {
            // Code-review patch P18 — surface finishReason for debugging
            // when the parts array is empty (Gemini emits this with
            // finishReason="SAFETY" content-blocked candidates). The
            // value is local-only (architecture line 505 OK — finishReason
            // is one of a fixed set of enum values, not session content).
            // Story 3.3 may add a distinct flash variant for SAFETY.
            val finishReason = envelope.candidates.firstOrNull()?.finishReason
            Log.w(TAG, "verify failed: MalformedResponse — empty parts[0].text (finishReason=$finishReason)")
            return Failure.MalformedResponse
        }
        val verdict = json.decodeFromString(GeminiVerdictResponse.serializer(), verdictJsonText)
        return VerificationOutcome.Verdict(toSessionRecord(verdict))
    }

    private fun toSessionRecord(verdict: GeminiVerdictResponse): SessionRecord = SessionRecord(
        id = UUID.randomUUID().toString(),
        timestampMs = System.currentTimeMillis(),
        verdictLabel = verdict.verdictLabel,
        headline = verdict.headline,
        contextLines = verdict.contextLines,
        sourceLinks = verdict.sources,
        ocrText = verdict.ocrText,
        regionalBiasNote = verdict.regionalBiasNote,
    )

    private fun logOutcome(outcome: VerificationOutcome) {
        // Privacy posture: log the verdict label only on success (label is
        // 1 of 4 enum values — not session content). On failure, log the
        // failure variant's class name. NEVER the headline / sources /
        // OCR / prompt / API key / body. (architecture line 505)
        when (outcome) {
            is VerificationOutcome.Verdict ->
                Log.d(TAG, "verify success: ${outcome.record.verdictLabel}")
            is Failure ->
                Log.d(TAG, "verify outcome: ${outcome::class.simpleName}")
        }
    }

    /**
     * Code-review patch P1 — privacy-safe failure logging. The
     * `Throwable` is NOT passed to `Log.w` because OkHttp's
     * `IOException` chain (StreamReset, route exceptions, certain SSL
     * handshake failures) frequently embeds the request URL — and the
     * URL contains `?key=$apiKey`. Architecture line 505 forbids logging
     * the API key. We log only the exception class name; the structured
     * outcome (Failure variant) is the diagnostic surface.
     */
    private fun logFailure(label: String, e: Throwable) {
        Log.w(TAG, "verify failed: $label (${e::class.simpleName})")
    }

    /**
     * Outer Gemini-API envelope. Gemini wraps the model output JSON
     * inside `candidates[0].content.parts[0].text` even when
     * `responseMimeType: application/json` is set — the structured
     * output guarantee constrains the *content* of that text, not the
     * envelope (architecture D3.4 + story Critical Dev Note).
     *
     * `finishReason` (added in code-review patch P18) is null on the
     * happy path and surfaces SAFETY / MAX_TOKENS / RECITATION when
     * the candidate's parts are empty. We log it for diagnostic; Story
     * 3.3 may map specific finish reasons to distinct UX flash variants.
     */
    @Serializable
    private data class GenerateContentResponse(val candidates: List<Candidate>) {
        @Serializable
        data class Candidate(
            val content: Content = Content(),
            val finishReason: String? = null,
        )

        @Serializable
        data class Content(val parts: List<Part> = emptyList())

        @Serializable
        data class Part(val text: String = "")
    }

    companion object {
        private val TAG = tag("GeminiClient")

        /**
         * Production endpoint. `internal` per code-review patch P19 —
         * boundary-discipline KDoc says only `GeminiClient` should know
         * the endpoint host; consumers in other packages have no
         * legitimate need for this constant.
         */
        internal const val ENDPOINT_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

        /**
         * Default Gemini model. `internal` per code-review patch P19.
         * Architecture D3.3 — Gemini 3 Flash preview is the V1 primary;
         * the Pro fallback (gemini-3.1-pro-preview) is parameterised by
         * the constructor.
         */
        internal const val DEFAULT_MODEL = "gemini-3-flash-preview"

        private const val HTTP_OK = 200
        private const val HTTP_TOO_MANY_REQUESTS = 429

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
