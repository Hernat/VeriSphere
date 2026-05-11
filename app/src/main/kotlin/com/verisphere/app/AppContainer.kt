package com.verisphere.app

import android.content.Context
import android.util.Base64
import com.verisphere.app.gemini.GeminiClient
import com.verisphere.app.storage.HistoryRepository
import com.verisphere.app.storage.HistoryRepositoryImpl
import com.verisphere.app.storage.RateLimitRepository
import com.verisphere.app.storage.RateLimitRepositoryImpl
import com.verisphere.app.storage.SecureStorage
import com.verisphere.app.storage.SessionRecord
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Single dependency-injection graph for the whole application.
 *
 * Architecture: D4.1 — manual constructor injection via a service-locator.
 * No Hilt, no Koin in V1. The trade-off is recorded in the architecture
 * document under "Decision Priority Analysis" — frameworks earn their
 * keep at scale, not at the size of a wrapper app with ~10 collaborators.
 *
 * Subsequent stories populate the lazy fields below. Story 1.1 ships the
 * empty container; the placeholders document where each collaborator
 * will land. When you add a new field, follow the rule:
 *
 *   - the field is `val`, lazy-initialised,
 *   - dependencies are passed by interface (not concrete impl),
 *   - the only `Context` dependency is `applicationContext`.
 *
 * Replacement / migration paths are mechanical because every consumer
 * receives an interface (HistoryRepository, RateLimitRepository, etc.)
 * — never the concrete `*Impl`.
 */
class AppContainer(private val applicationContext: Context) {

    val secureStorage: SecureStorage by lazy {
        SecureStorage(applicationContext)
    }

    val rateLimitRepository: RateLimitRepository by lazy {
        RateLimitRepositoryImpl(
            readLong = secureStorage::readLong,
            writeLong = secureStorage::writeLong,
        )
    }

    /**
     * Shared OkHttp client (architecture D3.1, D3.8 + line 844 — single
     * client used by [GeminiClient] (Story 1.9) and the future
     * `VersionChecker` (Story 6.1)).
     *
     * **Timeouts** per D3.8:
     *  - `connectTimeout = 5 s` — TCP / TLS handshake budget.
     *  - `readTimeout = 15 s` — between bytes, after handshake.
     *  - `callTimeout = 20 s` — total wall-clock; matches
     *    [com.verisphere.app.capture.CapturePipeline.CAPTURE_TIMEOUT].
     *
     * **No retries in Story 1.9** — `retryOnConnectionFailure(false)`
     * is set explicitly. Story 3.2 owns the retry policy.
     */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            // Code-review patch P16 — disable HTTP 3xx auto-follow.
            // Boundary discipline (architecture line 752): GeminiClient is
            // the single talker to `generativelanguage.googleapis.com` and
            // VersionChecker (Story 6.1) is the single talker to
            // `raw.githubusercontent.com`. A redirect could route the
            // request to a non-allowlisted host silently — either of those
            // talkers should fail loudly rather than chase a 302. Manual
            // redirect inspection per-talker if ever needed.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * Single talker to `generativelanguage.googleapis.com` (architecture
     * line 752 — boundary discipline). Story 1.9 wires it; consumed by
     * `BubbleOverlayService` via the `CapturePipeline.verify` lambda.
     *
     * The system prompt is loaded lazily from `assets/system_prompt_v1.txt`
     * (D2.4) the first time `verify` is called per process. The
     * `applicationContext` is captured by the lambda — confines `Context`
     * to `AppContainer` and keeps `GeminiClient` itself Android-API-free
     * for JVM unit testing (story Task 9.5).
     */
    val geminiClient: GeminiClient by lazy {
        // Code-review patch P14 — read the system prompt eagerly when the
        // lazy initializer fires (instead of deferring to the lambda
        // which would be invoked inside Dispatchers.Default in
        // GeminiClient.verify, polluting the CPU pool with file I/O).
        // Combined with patch P9 (deferring lambda in BubbleOverlayService),
        // the geminiClient lazy first triggers from inside the pipeline's
        // Dispatchers.IO context — so the asset read lands on IO.
        val systemPrompt = applicationContext.assets
            .open(SYSTEM_PROMPT_ASSET)
            .bufferedReader()
            .use { it.readText() }
        GeminiClient(
            httpClient = httpClient,
            systemPromptProvider = { systemPrompt },
            base64Encoder = { bytes -> Base64.encodeToString(bytes, Base64.NO_WRAP) },
        )
    }

    /**
     * Single talker over [SecureStorage] for the persisted session
     * history (architecture AR13, D1.1, D1.2, D1.3; PRD FR15, FR16,
     * FR17, NFR16). Story 1.10 wires the lazy field; the
     * [com.verisphere.app.bubble.BubbleOverlayService] triggers the
     * first load by calling [HistoryRepository.append] on the first
     * verdict outcome — keeping the Keystore-backed first-access
     * (~50–200 ms) off `Application.onCreate` (NFR3 cold-start budget).
     *
     * The lambda seam (`readHistory` / `writeHistory`) lets unit tests
     * inject in-memory fakes — the JSON round-trip itself is covered by
     * `SessionRecordSerializationTest` + `SecureStorageInstrumentedTest`.
     */
    val historyRepository: HistoryRepository by lazy {
        HistoryRepositoryImpl(
            readHistory = {
                secureStorage.readJson<List<SessionRecord>>(HistoryRepositoryImpl.KEY_HISTORY)
            },
            writeHistory = { list ->
                secureStorage.writeJson(HistoryRepositoryImpl.KEY_HISTORY, list)
            },
        )
    }

    // TODO Story 6.1: val versionChecker: VersionChecker by lazy {
    //     VersionChecker(httpClient)
    // }

    // BubbleStateMachine and CapturePipeline live inside BubbleOverlayService
    // (Stories 1.6, 1.8) — owned by the service, not by AppContainer.

    private companion object {
        // TEMP Story 2.4 smoke 2026-05-11: bumped 5/15/20 → 5/45/60.
        // Verdict + Search Grounding + Vision on Gemini 2.5 Flash
        // sometimes exceeds 20s wall-clock (verified on Pixel_9_Pro
        // AVD: 11s success for one image, 20s+ timeout for a 390KB
        // image). Architecture D3.8 was written before Grounding +
        // Vision were combined; revisit when spec author confirms
        // production timeout target. Revert before merge unless
        // D3.8 is amended.
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val READ_TIMEOUT_SECONDS = 45L
        private const val CALL_TIMEOUT_SECONDS = 60L
        private const val SYSTEM_PROMPT_ASSET = "system_prompt_v1.txt"
    }
}
