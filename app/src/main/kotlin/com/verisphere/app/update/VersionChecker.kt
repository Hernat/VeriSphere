package com.verisphere.app.update

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.verisphere.app.BuildConfig
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Single class allowed to fetch `version-info.json` (architecture
 * line 752-753 — boundary discipline, AR18). Every other component
 * receives a [VersionInfo] value object OR reads the persisted
 * SecureStorage keys ([KEY_UPDATE_AVAILABLE_VERSION] /
 * [KEY_UPDATE_AVAILABLE_DOWNLOAD_URL]) owned by this file's companion.
 *
 * **Story 6.1 contract** (PRD FR22; architecture D3.9, D5.10):
 *  - [checkForUpdates] is called exactly once per app launch from
 *    [com.verisphere.app.VeriSphereApplication.onCreate] via
 *    `applicationScope.launch { ... }` (CDN #9 — applicationScope,
 *    NOT lifecycleScope and NOT GlobalScope).
 *  - The fetch hits the shared [AppContainer.httpClient] (CDN #8 —
 *    architecture L844 single-client invariant; inherits the
 *    `followRedirects(false)` defence from patch P16).
 *  - On success with `latestVersion > BuildConfig.VERSION_NAME`
 *    (integer SemVer comparison per [isNewerSemVer] / CDN #5),
 *    persists both keys via the [writeString] seam.
 *  - On success with `latestVersion <= BuildConfig.VERSION_NAME`,
 *    clears both keys via the [clearKey] seam (handles the "user
 *    just installed the new APK" path that epics 6.3 AC #4 relies on).
 *  - On any failure (network / non-200 / malformed JSON / missing
 *    field), returns `null` silently with NO state mutation (CDN #2
 *    + AC #7; UX spec L623).
 *
 * **Lambda-seam constructor** (CDN #1) mirrors
 * [com.verisphere.app.onboarding.OnboardingOrchestrator] (Story 5.2)
 * and [com.verisphere.app.storage.HistoryRepositoryImpl] (Story 1.10).
 * Production wiring in [com.verisphere.app.AppContainer] passes
 * `secureStorage::writeString` and `secureStorage::clear`; JVM tests
 * pass any pair of recording lambdas with NO Android-API surface.
 *
 * **Privacy posture** (CDN #12 + architecture L505): no log line ever
 * contains the request URL, the response body, or the exception
 * message. We log only the exception CLASS NAME (same pattern as
 * [com.verisphere.app.gemini.GeminiClient]).
 *
 * **Threading**:
 *  - Self-wraps `withContext(Dispatchers.IO)` so callers can dispatch
 *    from any context (a no-op hop when already on IO).
 *  - JSON parse is small enough (≤ 200 bytes typical body) that it
 *    stays inside the IO context — no `Dispatchers.Default` hop
 *    (versus [com.verisphere.app.gemini.GeminiClient] which hops
 *    because the body is ~150 KB).
 *
 * **No retry** (CDN #4) — Story 3.2's D3.8 retry policy is scoped to
 * the GeminiClient verdict path. The version-info fetch is
 * fire-and-forget on launch; a failed attempt simply means the
 * banner dot won't appear this session — next launch retries.
 */
class VersionChecker(
    private val httpClient: OkHttpClient,
    private val writeString: (String, String) -> Unit,
    private val clearKey: (String) -> Unit,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val versionInfoUrl: String = VERSION_INFO_URL,
    /**
     * Story 6.2 — invoked AFTER each `writeString` / `clearKey` of the
     * update-available state to mirror the persisted value into the
     * in-process [kotlinx.coroutines.flow.MutableStateFlow] held by
     * [com.verisphere.app.AppContainer]. Default no-op so existing
     * Story 6.1 JVM tests continue to compile unchanged.
     *
     * **Contract** (CDN #4): invoked ONLY on the three state-mutating
     * branches (happy-path write / https-rejection clear / equal-or-older
     * clear). NEVER invoked on failure paths (network / non-200 /
     * malformed JSON / missing field) — those return `null` with NO
     * state mutation, and surfacing `null` here would CLEAR a perfectly
     * valid in-memory state on transient connectivity loss.
     */
    private val notifyUpdateAvailableChanged: (latestVersion: String?) -> Unit = {},
) {

    // Code-review patch P2 — `coerceInputValues` intentionally absent.
    // `VersionInfo` currently has no field defaults (only `releasedAt`
    // gained a default in patch P8), so the flag would do nothing today
    // — but the moment a future contributor adds a default like
    // `latestVersion = ""`, `coerceInputValues` would silently coerce a
    // server `null` into the empty string and `isNewerSemVer("", current)`
    // would clear the keys, erasing a real update. Leaving the flag off
    // makes the schema's non-coercion contract explicit.
    private val json: Json = Json {
        ignoreUnknownKeys = true
    }

    /**
     * Fetch `version-info.json`, parse it, and update the persisted
     * `update_available_*` keys per AC #4 / #5. Returns the parsed
     * [VersionInfo] on success (regardless of whether it triggered a
     * write or a clear), or `null` on any failure (AC #7).
     *
     * Never throws except [CancellationException] which is rethrown
     * unchanged to preserve structured concurrency (CDN #3).
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    suspend fun checkForUpdates(): VersionInfo? = withContext(Dispatchers.IO) {
        Log.d(TAG, "checkForUpdates start")
        val response: Pair<Int, String> = try {
            executeFetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "fetch failed: ${e::class.simpleName ?: e::class.java.name}")
            return@withContext null
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed (unexpected): ${e::class.simpleName ?: e::class.java.name}")
            return@withContext null
        }
        val (code, body) = response
        if (code != HTTP_OK) {
            Log.w(TAG, "non-200 HTTP code=$code")
            return@withContext null
        }
        val parsed: VersionInfo = try {
            json.decodeFromString(VersionInfo.serializer(), body)
        } catch (e: SerializationException) {
            Log.w(TAG, "malformed JSON: ${e::class.simpleName}")
            return@withContext null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "malformed JSON (IAE): ${e::class.simpleName}")
            return@withContext null
        }
        if (isNewerSemVer(parsed.latestVersion, currentVersionName)) {
            // Code-review patch P3 — defend Story 6.2's banner CTA
            // (Intent.ACTION_VIEW) against a compromised/typo
            // `downloadUrl` of scheme `intent://`, `file:///`, `content://`
            // etc. The banner-tap target MUST be a https URL; anything
            // else is treated as "no usable update" per the CDN #2
            // silent-failure contract. Stored state is cleared so a
            // previous valid entry doesn't get re-used.
            if (!parsed.downloadUrl.startsWith("https://")) {
                Log.w(TAG, "rejected non-https downloadUrl")
                clearKey(KEY_UPDATE_AVAILABLE_VERSION)
                clearKey(KEY_UPDATE_AVAILABLE_DOWNLOAD_URL)
                notifyUpdateAvailableChanged(null)
                return@withContext null
            }
            writeString(KEY_UPDATE_AVAILABLE_VERSION, parsed.latestVersion)
            writeString(KEY_UPDATE_AVAILABLE_DOWNLOAD_URL, parsed.downloadUrl)
            notifyUpdateAvailableChanged(parsed.latestVersion)
            Log.i(TAG, "update available: ${parsed.latestVersion}")
        } else {
            clearKey(KEY_UPDATE_AVAILABLE_VERSION)
            clearKey(KEY_UPDATE_AVAILABLE_DOWNLOAD_URL)
            notifyUpdateAvailableChanged(null)
            Log.d(
                TAG,
                "no newer version (latest=${parsed.latestVersion}, current=$currentVersionName)",
            )
        }
        parsed
    }

    /**
     * Bridge OkHttp's blocking [Call.execute]/`enqueue` into a
     * cancellable coroutine. On parent-scope cancellation,
     * [Call.cancel] is invoked so the socket is released immediately
     * instead of waiting for `callTimeout`. Mirrors the
     * [com.verisphere.app.gemini.GeminiClient.executeCall] pattern.
     */
    private suspend fun executeFetch(): Pair<Int, String> {
        val request = Request.Builder()
            .url(versionInfoUrl.toHttpUrl())
            .get()
            .build()
        val call = httpClient.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        // Code-review patch P1 — body-size cap defends
                        // against a compromised CDN / misconfigured repo
                        // serving an unbounded body (multi-GB blob would
                        // OOM the app mid-`Application.onCreate`).
                        // `version-info.json` is ~125 bytes today; 10 KB
                        // is generous headroom. Two checks: (1) reject
                        // up-front if Content-Length advertises > MAX;
                        // (2) stream-read at most MAX+1 bytes and reject
                        // if we hit the +1 boundary (covers chunked /
                        // unknown-length responses).
                        val body = resp.body
                        val advertisedLength = body?.contentLength() ?: -1L
                        if (advertisedLength > MAX_BODY_BYTES) {
                            Log.w(TAG, "body too large: contentLength=$advertisedLength")
                            if (continuation.isActive) {
                                continuation.resume(resp.code to "")
                            }
                            return
                        }
                        val payload = try {
                            body?.byteStream()?.use { stream ->
                                val buf = ByteArray(MAX_BODY_BYTES.toInt() + 1)
                                var total = 0
                                while (total < buf.size) {
                                    val r = stream.read(buf, total, buf.size - total)
                                    if (r <= 0) break
                                    total += r
                                }
                                if (total.toLong() > MAX_BODY_BYTES) {
                                    Log.w(TAG, "body exceeded MAX_BODY_BYTES during read")
                                    ""
                                } else {
                                    String(buf, 0, total, Charsets.UTF_8)
                                }
                            }.orEmpty()
                        } catch (e: IOException) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(e)
                            }
                            return
                        }
                        if (continuation.isActive) {
                            continuation.resume(resp.code to payload)
                        }
                    }
                }
            })
        }
    }

    companion object {
        /**
         * Story 6.1 AC #2 — fetch URL. Configurable via constructor
         * `versionInfoUrl` parameter (testing seam — MockWebServer
         * passes `server.url("/version-info.json").toString()`).
         *
         * Repo path uses canonical `Hernat/VeriSphere` casing per
         * [RELEASING.md L87-88]: `raw.githubusercontent.com` is
         * case-insensitive but always write the canonical casing so
         * downstream case-sensitive contexts don't burn.
         */
        const val VERSION_INFO_URL: String =
            "https://raw.githubusercontent.com/Hernat/VeriSphere/main/version-info.json"

        /**
         * Story 6.1 AC #4 — SecureStorage key for the latest published
         * version string. Stories 6.2 + 6.3 MUST import this constant
         * (CDN #6) — do NOT duplicate the literal `"update_available_version"`.
         */
        const val KEY_UPDATE_AVAILABLE_VERSION: String = "update_available_version"

        /**
         * Story 6.1 AC #4 — SecureStorage key for the download URL
         * (Drive share link or GitHub Release asset URL). Story 6.2
         * reads this on banner CTA tap to build `Intent.ACTION_VIEW`.
         */
        const val KEY_UPDATE_AVAILABLE_DOWNLOAD_URL: String = "update_available_download_url"

        private const val HTTP_OK: Int = 200

        /**
         * Code-review patch P1 — maximum body bytes the fetch will
         * accept. `version-info.json` is ~125 bytes today; 10 KB is
         * generous headroom. Larger bodies are rejected up-front (via
         * Content-Length) or mid-stream (via byte-count check) to
         * defend against OOM from a compromised CDN or misconfigured
         * repo.
         */
        private const val MAX_BODY_BYTES: Long = 10_240L
        private val TAG: String = tag("VersionChecker")
    }
}

/**
 * Pure-function SemVer comparator (Story 6.1 AC #6 / CDN #5 — integer
 * comparison, NOT string comparison). Returns `true` iff
 * [latest] strictly greater than [current] when both are parsed as
 * `MAJOR.MINOR.PATCH` integer triples.
 *
 * Malformed inputs (wrong arity, non-numeric, empty) return `false` —
 * caller treats as "no update, clear state" per AC #5. This is a
 * defensive contract; callers should not rely on it for general
 * SemVer validation.
 *
 * `internal` visibility (NOT `private`) so `VersionCheckerTest` in
 * the same `:app` module can test the function directly without
 * routing through the public `checkForUpdates` API — matches the
 * Story 5.3 `isHostileOem` precedent (architecture L428).
 */
@VisibleForTesting
internal fun isNewerSemVer(latest: String, current: String): Boolean {
    val l = parseSemVer(latest) ?: return false
    val c = parseSemVer(current) ?: return false
    return when {
        l.first != c.first -> l.first > c.first
        l.second != c.second -> l.second > c.second
        else -> l.third > c.third
    }
}

/**
 * Code-review patch P7 (D1 decision) — cap parsed parts to the
 * `version.properties` budget enforced by `app/build.gradle.kts:38-40`
 * (MAJOR=0..200, MINOR=0..99, PATCH=0..99). A malicious or accidental
 * publish of `latestVersion = "999.0.0"` would otherwise compare
 * greater than any legal version and pin the banner permanently
 * across launches. Out-of-range values return `null`, which the
 * caller treats as "no update" per AC #5.
 */
// Code-review patch P7 — file-level caps mirroring the
// `version.properties` budget enforced at build time
// (`app/build.gradle.kts:38-40`).
private const val MAX_MAJOR: Int = 200
private const val MAX_MINOR: Int = 99
private const val MAX_PATCH: Int = 99

private fun parseSemVer(v: String): Triple<Int, Int, Int>? {
    val parts = v.split('.')
    if (parts.size != 3) return null
    return try {
        val major = parts[0].toInt()
        val minor = parts[1].toInt()
        val patch = parts[2].toInt()
        if (major !in 0..MAX_MAJOR || minor !in 0..MAX_MINOR || patch !in 0..MAX_PATCH) {
            return null
        }
        Triple(major, minor, patch)
    } catch (_: NumberFormatException) {
        null
    }
}
