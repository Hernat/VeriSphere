package com.verisphere.app

import android.content.Context
import android.util.Base64
import androidx.annotation.VisibleForTesting
import com.verisphere.app.gemini.GeminiClient
import com.verisphere.app.onboarding.OnboardingOrchestrator
import com.verisphere.app.serp.SerpApiClient
import com.verisphere.app.serp.SerpQuotaGate
import com.verisphere.app.storage.HistoryRepository
import com.verisphere.app.storage.HistoryRepositoryImpl
import com.verisphere.app.storage.RateLimitRepository
import com.verisphere.app.storage.RateLimitRepositoryImpl
import com.verisphere.app.storage.SecureStorage
import com.verisphere.app.storage.SessionRecord
import com.verisphere.app.update.VersionChecker
import com.verisphere.app.update.isNewerSemVer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
     * Story 10.1 — shared [OnboardingOrchestrator] instance.
     *
     * Pre-Story-10.1, `MainActivity` constructed its own orchestrator
     * locally. The Settings tab introduced in Story 10.1 needs a second
     * consumer (the `SettingsViewModel` reads/writes the user API
     * keys), so the construction moves into the container to enforce a
     * single source of truth. The same instance also backs the
     * `apiKeyProvider` lambdas on [geminiClient] + [serpApiClient]
     * below so a Settings → Save → next bubble long-press picks up the
     * freshest stored key.
     */
    val onboardingOrchestrator: OnboardingOrchestrator by lazy {
        OnboardingOrchestrator(
            readBoolean = secureStorage::readBoolean,
            writeBoolean = secureStorage::writeBoolean,
            readString = secureStorage::readString,
            writeString = secureStorage::writeString,
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
            // Story 10.1 — fresh-read on every verify so the user's
            // Settings save takes effect on the next bubble long-press
            // without a process restart. Empty / null → orEmpty() ⇒
            // GeminiClient short-circuits to Failure.NotConfigured.
            //
            // P3 (2026-05-20 code-review) — wrap in runCatching to
            // defuse Keystore decrypt exceptions (GeneralSecurityException
            // / AEADBadTagException) that can surface after factory-reset-
            // restore, fingerprint-enrolment-change on some OEMs, or
            // Tink key corruption. Without this wrap, the exception
            // propagates up through the lambda and crashes the
            // BubbleOverlayService capture coroutine ; the user sees
            // the bubble freeze in Capturing/Thinking with no path back
            // to Idle. The empty-fallback routes to Failure.NotConfigured
            // → FailureState.NoApiKey flash, which prompts the user to
            // re-enter their key in Settings.
            apiKeyProvider = {
                runCatching { onboardingOrchestrator.readUserGeminiApiKey().orEmpty() }
                    .getOrDefault("")
            },
            systemPromptProvider = { systemPrompt },
            base64Encoder = { bytes -> Base64.encodeToString(bytes, Base64.NO_WRAP) },
        )
    }

    /**
     * Epic 9 Story 9.1 — single talker to `serpapi.com` for the
     * cross-source fact-check enrichment layer. Boundary discipline
     * (architecture line 752) mirrors [geminiClient].
     *
     * Derives a SerpAPI-specific OkHttp client from the shared one
     * with the documented [SerpApiClient.CALL_TIMEOUT_SECONDS] = 15 s
     * `callTimeout` (code-review P2 — was silently inheriting the
     * shared 60 s budget, breaking the documented per-call ceiling and
     * compounding pipeline-level latency). Reuses the underlying
     * dispatcher / connection pool / DNS via [OkHttpClient.newBuilder].
     *
     * Story 10.1 — API key is now provided by a fresh-read lambda
     * wired to [onboardingOrchestrator] (was `BuildConfig.SERP_API_KEY`
     * via the constructor default pre-Story-10.1). Empty / null key →
     * graceful `SerpOutcome.Failure.NotConfigured` per Epic 9 plan.
     */
    val serpApiClient: SerpApiClient by lazy {
        val serpHttpClient = httpClient.newBuilder()
            .callTimeout(SerpApiClient.CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        SerpApiClient(
            httpClient = serpHttpClient,
            // P3 (2026-05-20 code-review) — same Keystore-exception guard
            // as geminiClient ; empty-fallback degrades gracefully to
            // SerpOutcome.Failure.NotConfigured per Epic 9 plan.
            apiKeyProvider = {
                runCatching { onboardingOrchestrator.readUserSerpApiKey().orEmpty() }
                    .getOrDefault("")
            },
        )
    }

    /**
     * Epic 9 Story 9.1 — in-memory throttle that bypasses SerpAPI for
     * 15 minutes after a quota-exhausted signal. Singleton so the
     * cooldown persists across pipeline calls within the process.
     */
    val serpQuotaGate: SerpQuotaGate by lazy {
        SerpQuotaGate()
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

    /**
     * Story 6.1 — single talker to `raw.githubusercontent.com` for the
     * version-info JSON (architecture line 752-753 boundary discipline,
     * AR18, D3.9). Lambda-seam wiring (Story 6.1 CDN #1): production
     * passes `secureStorage::writeString` + `secureStorage::clear` so
     * the `SecureStorage` import never reaches the `update/` package.
     *
     * Inherits the shared [httpClient] (CDN #8 — single OkHttp client
     * per architecture L844). Reads [BuildConfig.VERSION_NAME] via the
     * default parameter at construction (CDN #7).
     */
    val versionChecker: VersionChecker by lazy {
        VersionChecker(
            httpClient = httpClient,
            writeString = secureStorage::writeString,
            clearKey = secureStorage::clear,
            notifyUpdateAvailableChanged = { version, downloadUrl ->
                // Story 6.3 — CDN #6 pairing invariant: both values
                // either non-null (happy path) or both null (clear
                // branches). Atomic dual-flow update.
                _updateAvailableVersion.value = version
                _updateAvailableDownloadUrl.value = downloadUrl
            },
            notifyDismissedVersionCleared = {
                // Story 6.3 — install-cleanup mirror (CDN #4). Fired
                // ONLY by VersionChecker's equal-or-older branch.
                _updateBannerDismissedForVersion.value = null
            },
        )
    }

    /**
     * Story 6.2 — backing `MutableStateFlow` mirroring the persisted
     * `update_available_version` SecureStorage key. Initial value is read
     * once at first access (lazy).
     *
     * **First-subscriber stall caveat** (code-review patch P6, 2026-05-16):
     * the lazy keeps the read OUT of `Application.onCreate` (preserving
     * NFR3 cold-start budget THERE), but the cost LANDS at the first
     * subscriber. Today that's `BubbleOverlayService.setContent` which
     * runs on the main thread — first composition can stall 50-200 ms
     * on the Keystore-backed `EncryptedSharedPreferences` first-touch
     * per [com.verisphere.app.storage.SecureStorage] L35. This is a
     * documented limitation (deferred-work D1, Story 6.2 review); a V1.x
     * fix would emit asynchronously via `flowOn(Dispatchers.IO)` and an
     * initial-value coroutine.
     *
     * Mutations originate from [VersionChecker] via the lambda seam
     * wired in [versionChecker] above.
     *
     * Single-writer discipline (CDN #11): only `VersionChecker` writes
     * to this flow. `private` so no consumer can do `.value = ...`
     * directly — the public surface is [updateAvailableVersion].
     */
    private val _updateAvailableVersion: MutableStateFlow<String?> by lazy {
        val persisted = secureStorage.readString(VersionChecker.KEY_UPDATE_AVAILABLE_VERSION)
        // Story 6.3 AC #9 (closes deferred-work D2 from Story 6.2 review):
        // gate the initial flow value through the same SemVer comparison
        // VersionChecker uses on a successful fetch. Defends the upgrade-
        // without-network path — user upgrades from 0.1.0 to 0.2.0 while
        // SecureStorage still holds `update_available_version = 0.1.0`;
        // without this gate the banner/dot would advertise "0.1.0 available"
        // until the first successful post-upgrade fetch overwrites the
        // key (or never, if the user stays offline). Reuses the internal
        // [isNewerSemVer] helper for parser parity — out-of-range /
        // malformed inputs return false → gated to null, matching
        // VersionChecker AC #5 contract.
        MutableStateFlow(gatePersistedVersion(persisted, BuildConfig.VERSION_NAME))
    }

    /**
     * Story 6.2 — read-only StateFlow exposing the in-process mirror of
     * the persisted `update_available_version`. Consumed by
     * [com.verisphere.app.bubble.BubbleOverlayService] (dot-on-bubble)
     * and by Story 6.3's `HistoryScreen` wiring (banner visibility
     * predicate). The boolean `hasUpdate` derives from `value != null`.
     *
     * **Why a StateFlow, not synchronous SecureStorage reads** (CDN #3):
     * the launch-time `VersionChecker.checkForUpdates()` runs
     * fire-and-forget on `applicationScope` (Story 6.1 Task 4). It can
     * complete AFTER the bubble service is up; subscribers MUST observe
     * the transition reactively. A `secureStorage.readString(...)` per
     * recomposition would also hit EncryptedSharedPreferences on every
     * Compose frame, violating architecture L468 "no parallel state
     * primitives".
     */
    val updateAvailableVersion: StateFlow<String?> by lazy {
        _updateAvailableVersion.asStateFlow()
    }

    /**
     * Story 6.3 — backing `MutableStateFlow` mirroring the persisted
     * `update_available_download_url` SecureStorage key. Initial value
     * is gated by [_updateAvailableVersion] — if the version flow's
     * initial value is null (either absent or stale per AC #9 defense),
     * this flow's initial value is also null. The two flows ALWAYS
     * agree on null-ness (CDN #6 pairing invariant — Story 6.2
     * notify-seam now passes both values atomically).
     *
     * Mutations originate from [VersionChecker] via the
     * `notifyUpdateAvailableChanged` seam wired in [versionChecker].
     *
     * **Why a separate flow** (not folded into a single
     * `UpdateAvailable(version, downloadUrl)` data class flow): the
     * dot-on-bubble (Story 6.2 surface) consumes ONLY the version —
     * folding would force the bubble service to wire through an
     * unused field and pay a recomposition cost on every download-url
     * change.
     */
    private val _updateAvailableDownloadUrl: MutableStateFlow<String?> by lazy {
        // Gate alongside _updateAvailableVersion: if the version flow's
        // initial value is null (stale per AC #9), don't expose the
        // persisted downloadUrl either. Accessing `_updateAvailableVersion`
        // triggers its lazy initializer first (Kotlin's `by lazy` is
        // thread-safe by default) so the gate is consistent.
        val initial = if (_updateAvailableVersion.value != null) {
            secureStorage.readString(VersionChecker.KEY_UPDATE_AVAILABLE_DOWNLOAD_URL)
        } else {
            null
        }
        MutableStateFlow(initial)
    }

    /**
     * Story 6.3 — read-only StateFlow exposing the in-process mirror of
     * the persisted `update_available_download_url`. Consumed by
     * MainActivity's [com.verisphere.app.ui.history.HistoryScreen]
     * wiring to build the banner's `UpdateBannerPayload` (the
     * Story 6.2 [com.verisphere.app.ui.banner.buildUpdateDownloadIntent]
     * factory needs the URL).
     */
    val updateAvailableDownloadUrl: StateFlow<String?> by lazy {
        _updateAvailableDownloadUrl.asStateFlow()
    }

    /**
     * Story 6.3 — backing `MutableStateFlow` mirroring the persisted
     * `update_banner_dismissed_for_version` SecureStorage key (epics
     * L909-910). Written by [dismissUpdateBanner] when the user taps
     * the banner's dismiss IconButton; cleared by [VersionChecker]'s
     * equal-or-older branch via the `notifyDismissedVersionCleared`
     * seam (epics L912 install-cleanup).
     *
     * **Why no stale-version gate here** (contrast with
     * [_updateAvailableVersion]): the dismissed-for-version key is
     * only ever used in the predicate
     * `updateAvailableVersion != dismissedForVersion`. If
     * `updateAvailableVersion` is gated to null, the predicate
     * trivially evaluates false regardless of `dismissedForVersion`'s
     * value. The dismissed flow can carry a stale value without
     * affecting any user-facing surface; cleanup eventually happens
     * on the next successful equal-or-older fetch.
     *
     * **Two writers** (CDN #11 evolution): unlike
     * [_updateAvailableVersion] which has a single writer
     * (VersionChecker), the dismissed flow has TWO writers —
     * [VersionChecker]'s install-cleanup seam (clears to null) AND
     * [dismissUpdateBanner] (writes the current version). Both writers
     * are inside the `AppContainer` class boundary; consumers see only
     * the read-only [updateBannerDismissedForVersion] surface. The two
     * writers do not conflict because they fire on mutually exclusive
     * triggers (cold-launch VersionChecker vs user dismiss-click).
     */
    private val _updateBannerDismissedForVersion: MutableStateFlow<String?> by lazy {
        MutableStateFlow(
            secureStorage.readString(VersionChecker.KEY_UPDATE_BANNER_DISMISSED_FOR_VERSION),
        )
    }

    /**
     * Story 6.3 — read-only StateFlow exposing the in-process mirror of
     * the persisted `update_banner_dismissed_for_version`. Consumed by
     * MainActivity's banner-visibility predicate (third leg of the
     * `version != null && downloadUrl != null && version != dismissed`
     * gate).
     */
    val updateBannerDismissedForVersion: StateFlow<String?> by lazy {
        _updateBannerDismissedForVersion.asStateFlow()
    }

    /**
     * Story 6.3 AC #3 — user-initiated dismissal of the update banner.
     *
     * Persists the current [updateAvailableVersion] value (snapshot at
     * call time per CDN #5) to `SecureStorage` under
     * [VersionChecker.KEY_UPDATE_BANNER_DISMISSED_FOR_VERSION] AND
     * updates the in-process [_updateBannerDismissedForVersion] flow
     * synchronously, so the banner-visibility predicate
     * (`updateAvailableVersion != updateBannerDismissedForVersion`)
     * evaluates false on the very next Compose recomposition (epics
     * L910 "the banner disappears immediately").
     *
     * **Threading** (CDN #7): the in-memory `MutableStateFlow.value`
     * write is synchronous so the UI flips on the next frame. The
     * SecureStorage write is dispatched fire-and-forget on
     * [applicationScope] with `NonCancellable + Dispatchers.IO` — the
     * write is install-lifetime (must survive activity destruction
     * mid-write) per the Story 5.2 P4 / Story 5.3 CDN #5 precedent.
     *
     * **Null-version no-op** (defensive): if [updateAvailableVersion]
     * is null at call time, this method is a no-op. The HistoryScreen
     * predicate already prevents the banner from rendering in that
     * case, so the callback can never fire — but defending in depth
     * protects against future refactors.
     */
    fun dismissUpdateBanner() {
        // CDN #5 — capture snapshot once; both writes (in-memory + IO)
        // use the SAME value to defend against torn-read races against
        // a concurrent VersionChecker mutation.
        val version = _updateAvailableVersion.value ?: return
        // Flip the in-memory state synchronously so the next Compose
        // recomposition hides the banner immediately.
        _updateBannerDismissedForVersion.value = version
        // Persist asynchronously — the write is install-lifetime, so
        // NonCancellable guards against activity-destruction races
        // (mirrors Story 5.2 P4 / Story 5.3 CDN #5 pattern).
        applicationScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                secureStorage.writeString(
                    VersionChecker.KEY_UPDATE_BANNER_DISMISSED_FOR_VERSION,
                    version,
                )
            }
        }
    }

    /**
     * Story 6.1 — process-lifetime coroutine scope for fire-and-forget
     * background work that must outlive any Activity (e.g. the
     * `VersionChecker.checkForUpdates` call from
     * [com.verisphere.app.VeriSphereApplication.onCreate]).
     *
     * `SupervisorJob` so a single failure does not cancel sibling
     * launches. `Dispatchers.IO` as the default — individual launches
     * can override. NEVER use `GlobalScope` directly: detekt's
     * architecture-mandated rule (`detekt.yml` ForbiddenMethodCall +
     * project policy at architecture L159) bans it.
     */
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    // BubbleStateMachine and CapturePipeline live inside BubbleOverlayService
    // (Stories 1.6, 1.8) — owned by the service, not by AppContainer.

    private companion object {
        // Per architecture D3.8 (Sprint Change 2026-05-18 / Story 7.4 MS1
        // formalised the 5/45/60 + thinkingBudget=0 posture). Verdict +
        // Search Grounding + Vision on gemini-2.5-flash routinely
        // exceeds the original 20 s ceiling (Story 2.4 smoke evidence,
        // deferred-work L306-310). The 5/15/20 posture is reserved as a
        // V2 stretch contingent on Gemini Flash latency improvements OR
        // migration to streamGenerateContent (deferred-work L309).
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val READ_TIMEOUT_SECONDS = 45L
        private const val CALL_TIMEOUT_SECONDS = 60L
        private const val SYSTEM_PROMPT_ASSET = "system_prompt_v1.txt"
    }
}

/**
 * Story 6.3 AC #9 — D2-defense gate. Returns [persisted] if and only
 * if it is a strictly-newer SemVer than [current]; returns null in all
 * other cases (absent / equal / older / malformed / out-of-range).
 *
 * Top-level `internal fun` (NOT in `AppContainer.companion`) per
 * code-review patch P1 (2026-05-17): keeps `private companion object`
 * intact while remaining JVM-test-callable from the same module. The
 * helper is pure — no `AppContainer` instance state is touched, so
 * the `AppContainer.companion` location was incidental anyway.
 *
 * Reuses [com.verisphere.app.update.isNewerSemVer] for parser parity
 * (out-of-range / malformed inputs return false → gated to null,
 * matching [VersionChecker] AC #5 contract).
 */
@VisibleForTesting
internal fun gatePersistedVersion(persisted: String?, current: String): String? {
    if (persisted == null) return null
    return if (isNewerSemVer(persisted, current)) persisted else null
}
