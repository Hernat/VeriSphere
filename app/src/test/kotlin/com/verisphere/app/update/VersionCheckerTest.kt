package com.verisphere.app.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [VersionChecker] (Story 6.1, AC #8).
 *
 * MockWebServer drives the failure-mapping table; the fake
 * `SecureStorage` lambda-seam ([writeCalls] / [clearCalls]) records
 * persistence operations so each branch's state-mutation contract
 * (AC #4 / #5 / #7) is verifiable without going through Android APIs.
 *
 * Boundary discipline (Story 6.1 CDN #1): this test file MUST NOT
 * import `android.content.SharedPreferences`, `EncryptedSharedPreferences`,
 * or `MasterKey` — `SecureStorage` is mocked via the constructor seam.
 *
 * Logging assertion (CDN #12): no test asserts on the exact log
 * message body — the privacy posture is that the URL / response body /
 * exception message are NEVER logged. Verified by inspection of the
 * production `Log.w(TAG, "fetch failed: ${e::class.simpleName}")`
 * pattern; not amenable to JVM unit-test assertion because `Log.w`
 * routes to `RuntimeException` under the JVM stub `android.jar`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VersionCheckerTest {

    private lateinit var server: MockWebServer
    private val writeCalls = mutableListOf<Pair<String, String>>()
    private val clearCalls = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        writeCalls.clear()
        clearCalls.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newChecker(
        currentVersion: String = "0.1.0",
        notifyUpdateAvailableChanged: (String?) -> Unit = {},
    ): VersionChecker {
        val client = OkHttpClient.Builder()
            .connectTimeout(SHORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(SHORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        return VersionChecker(
            httpClient = client,
            writeString = { k, v -> writeCalls.add(k to v) },
            clearKey = { k -> clearCalls.add(k) },
            currentVersionName = currentVersion,
            versionInfoUrl = server.url("/version-info.json").toString(),
            notifyUpdateAvailableChanged = notifyUpdateAvailableChanged,
        )
    }

    @Test
    fun `newer version writes both update_available_ keys to SecureStorage`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(HTTP_OK).setBody(
                """{"latestVersion":"1.0.0","downloadUrl":"https://drive.google.com/x","releasedAt":"2026-06-01"}""",
            ),
        )
        val checker = newChecker(currentVersion = "0.1.0")

        val result = checker.checkForUpdates()

        assertNotNull(result)
        assertEquals("1.0.0", result!!.latestVersion)
        assertEquals("https://drive.google.com/x", result.downloadUrl)
        assertEquals("2026-06-01", result.releasedAt)
        // Code-review patch P4 — strict pairing assertion catches accidental
        // duplicate writes (e.g. a future refactor that loops the write
        // call). Asserts both the exact list and ordering.
        assertEquals(
            listOf(
                "update_available_version" to "1.0.0",
                "update_available_download_url" to "https://drive.google.com/x",
            ),
            writeCalls,
        )
        assertEquals(emptyList<String>(), clearCalls)
    }

    @Test
    fun `equal version clears both update_available_ keys`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(HTTP_OK).setBody(
                """{"latestVersion":"0.1.0","downloadUrl":"https://x","releasedAt":"2026-05-16"}""",
            ),
        )
        val checker = newChecker(currentVersion = "0.1.0")

        val result = checker.checkForUpdates()

        assertNotNull(result)
        // Code-review patch P4 — strict pairing on the clear path too.
        assertEquals(emptyList<Pair<String, String>>(), writeCalls)
        assertEquals(
            listOf("update_available_version", "update_available_download_url"),
            clearCalls,
        )
    }

    @Test
    fun `older published version clears both update_available_ keys`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(HTTP_OK).setBody(
                """{"latestVersion":"0.0.5","downloadUrl":"https://x","releasedAt":"2026-04-01"}""",
            ),
        )
        val checker = newChecker(currentVersion = "0.1.0")

        val result = checker.checkForUpdates()

        assertNotNull(result)
        assertEquals(emptyList<Pair<String, String>>(), writeCalls)
        assertEquals(
            listOf("update_available_version", "update_available_download_url"),
            clearCalls,
        )
    }

    @Test
    fun `HTTP 404 returns null with no state mutation`() = runTest {
        server.enqueue(MockResponse().setResponseCode(HTTP_NOT_FOUND))
        val checker = newChecker()

        val result = checker.checkForUpdates()

        assertNull(result)
        assertEquals(0, writeCalls.size)
        assertEquals(0, clearCalls.size)
    }

    @Test
    fun `HTTP 500 returns null with no state mutation and no retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(HTTP_INTERNAL_ERROR))
        val checker = newChecker()

        val result = checker.checkForUpdates()

        assertNull(result)
        assertEquals(0, writeCalls.size)
        assertEquals(0, clearCalls.size)
        // CDN #4 — no retry for the version-info fetch. A second
        // request would advance the counter; lock the invariant.
        assertEquals("Story 6.1 CDN #4 — no retry", 1, server.requestCount)
    }

    @Test
    fun `malformed JSON returns null with no state mutation`() = runTest {
        server.enqueue(MockResponse().setResponseCode(HTTP_OK).setBody("{not valid"))
        val checker = newChecker()

        val result = checker.checkForUpdates()

        assertNull(result)
        assertEquals(0, writeCalls.size)
        assertEquals(0, clearCalls.size)
    }

    @Test
    fun `missing releasedAt field is tolerated via empty-string default`() = runTest {
        // Code-review patch P8 (D2 decision) — `releasedAt` carries a
        // default of "" so a publisher who forgets the field doesn't
        // silently break update notification for every user.
        server.enqueue(
            MockResponse().setResponseCode(HTTP_OK).setBody(
                """{"latestVersion":"1.0.0","downloadUrl":"https://drive.google.com/x"}""",
            ),
        )
        val checker = newChecker(currentVersion = "0.1.0")

        val result = checker.checkForUpdates()

        assertNotNull(result)
        assertEquals("1.0.0", result!!.latestVersion)
        assertEquals("https://drive.google.com/x", result.downloadUrl)
        assertEquals("", result.releasedAt)
        // The update channel still works — both keys are written.
        assertEquals(
            listOf(
                "update_available_version" to "1.0.0",
                "update_available_download_url" to "https://drive.google.com/x",
            ),
            writeCalls,
        )
    }

    @Test
    fun `missing latestVersion field returns null with no state mutation`() = runTest {
        // `latestVersion` has NO default — it is load-bearing for the
        // banner — so a missing field must fail loudly (silently to the
        // user, but with zero state mutation per AC #7).
        server.enqueue(
            MockResponse().setResponseCode(HTTP_OK).setBody(
                """{"downloadUrl":"https://x","releasedAt":"2026-05-16"}""",
            ),
        )
        val checker = newChecker()

        val result = checker.checkForUpdates()

        assertNull(result)
        assertEquals(0, writeCalls.size)
        assertEquals(0, clearCalls.size)
    }

    @Test
    fun `non-https downloadUrl is rejected and clears any stored state`() = runTest {
        // Code-review patch P3 — defends Story 6.2's banner CTA against
        // a compromised / typo `downloadUrl` of scheme intent://, file://,
        // content:// etc. Story 6.1 treats malformed-scheme as "no
        // update" per the CDN #2 silent-failure contract.
        server.enqueue(
            MockResponse().setResponseCode(HTTP_OK).setBody(
                """{"latestVersion":"1.0.0","downloadUrl":"intent://malicious","releasedAt":"2026-06-01"}""",
            ),
        )
        val checker = newChecker(currentVersion = "0.1.0")

        val result = checker.checkForUpdates()

        // null return — banner CTA never gets an unsafe URL to invoke.
        assertNull(result)
        // Zero writes (no stale unsafe URL persisted).
        assertEquals(emptyList<Pair<String, String>>(), writeCalls)
        // Any previously-stored state is cleared to be safe.
        assertEquals(
            listOf("update_available_version", "update_available_download_url"),
            clearCalls,
        )
    }

    @Test
    fun `network exception at start returns null with no state mutation`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val checker = newChecker()

        val result = checker.checkForUpdates()

        assertNull(result)
        assertEquals(0, writeCalls.size)
        assertEquals(0, clearCalls.size)
    }

    @Test
    fun `CancellationException is rethrown to preserve structured concurrency`() = runTest {
        // Code-review patch P5 — lock CDN #3 contract: a future refactor
        // that lumps CancellationException into the generic catch
        // (Exception) branch would silently break parent-scope
        // cancellation. The test cancels the wrapping coroutine while
        // the checker is in-flight; the only valid behaviour is that
        // the launched job reaches the `Cancelled` state (no leak, no
        // silent success).
        server.enqueue(
            MockResponse()
                .setResponseCode(HTTP_OK)
                .setBody("""{"latestVersion":"1.0.0","downloadUrl":"https://x","releasedAt":"2026-06-01"}""")
                .setBodyDelay(2L, TimeUnit.SECONDS),
        )
        val checker = newChecker()

        val scope = CoroutineScope(Dispatchers.IO + Job())
        val job = scope.launch { checker.checkForUpdates() }
        // Allow the call to start
        delay(50L)
        job.cancelAndJoin()

        assertTrue("job must be cancelled", job.isCancelled)
        // Zero state mutation — the cancellation prevented the write/clear branch.
        assertEquals(emptyList<Pair<String, String>>(), writeCalls)
        assertEquals(emptyList<String>(), clearCalls)
    }

    @Test
    fun `isNewerSemVer handles string-vs-integer ordering and malformed inputs`() {
        // string-compare bug ("0.9.0" > "0.10.0" as strings) MUST NOT trigger
        assertTrue("1.0.0 > 0.1.0", isNewerSemVer("1.0.0", "0.1.0"))
        assertTrue("0.10.0 > 0.9.0 (integer)", isNewerSemVer("0.10.0", "0.9.0"))
        assertFalse("equal", isNewerSemVer("1.0.0", "1.0.0"))
        assertFalse("older", isNewerSemVer("0.1.0", "1.0.0"))
        // malformed
        assertFalse("wrong arity", isNewerSemVer("1.0", "1.0.0"))
        assertFalse("pre-release tag", isNewerSemVer("1.0.0-rc", "1.0.0"))
        assertFalse("empty", isNewerSemVer("", "1.0.0"))
        assertFalse("non-numeric latest", isNewerSemVer("abc", "1.0.0"))
        assertFalse("non-numeric current", isNewerSemVer("1.0.0", "abc"))
        // PATCH-only differences
        assertTrue("PATCH bump", isNewerSemVer("0.1.1", "0.1.0"))
        // MINOR-only differences
        assertTrue("MINOR bump", isNewerSemVer("0.2.0", "0.1.99"))
    }

    @Test
    fun `isNewerSemVer rejects out-of-range parts per version_properties budget`() {
        // Code-review patch P7 (D1 decision) — cap to MAJOR=0..200,
        // MINOR=0..99, PATCH=0..99 matching `app/build.gradle.kts:38-40`.
        // Out-of-range values return false (caller treats as "no update")
        // — prevents a malicious / typo publish (e.g. "999.0.0" or
        // "100.0.0") from pinning the banner permanently.
        assertFalse("MAJOR=201 above cap", isNewerSemVer("201.0.0", "0.1.0"))
        assertFalse("MAJOR=999 well above cap", isNewerSemVer("999.0.0", "0.1.0"))
        assertFalse("MINOR=100 above cap", isNewerSemVer("0.100.0", "0.1.0"))
        assertFalse("PATCH=100 above cap", isNewerSemVer("0.0.100", "0.1.0"))
        // Negative parts (rejected by range check, not the parse — `"-1"` parses fine as Int).
        assertFalse("negative MAJOR", isNewerSemVer("-1.0.0", "0.1.0"))
        assertFalse("negative MINOR", isNewerSemVer("0.-1.0", "0.1.0"))
        // Boundary acceptance — exactly at the cap.
        assertTrue("MAJOR=200 at cap", isNewerSemVer("200.0.0", "0.1.0"))
        assertTrue("MINOR=99 at cap", isNewerSemVer("0.99.0", "0.1.0"))
        assertTrue("PATCH=99 at cap", isNewerSemVer("0.0.99", "0.0.0"))
        // Current-side cap also enforced — if BuildConfig.VERSION_NAME ever
        // exceeds the cap (would be a build-time error per gradle parse),
        // isNewerSemVer returns false defensively.
        assertFalse("current side out-of-range", isNewerSemVer("1.0.0", "201.0.0"))
    }

    // ─── Story 6.2 — notifyUpdateAvailableChanged seam tests ─────────────

    @Test
    fun `notifyUpdateAvailableChanged is invoked with null on strictly-older published version`() = runTest {
        // Code-review patch P2 (2026-05-16) — locks the strict-greater
        // branch of `isNewerSemVer` separately from the equal-version
        // path. A future off-by-one error (e.g. `>=` vs `>`) would slip
        // through the equal-version test but be caught here. Downgrade-
        // safety: debug build with VERSION_NAME above server should NOT
        // trigger an "update available" notification.
        val notifications = mutableListOf<String?>()
        server.enqueue(
            MockResponse().setResponseCode(HTTP_OK).setBody(
                """{"latestVersion":"0.0.5","downloadUrl":"https://x","releasedAt":"2026-04-01"}""",
            ),
        )
        val checker = newChecker(
            currentVersion = "0.1.0",
            notifyUpdateAvailableChanged = { notifications.add(it) },
        )

        val result = checker.checkForUpdates()

        assertNotNull(result)
        assertEquals("0.0.5", result!!.latestVersion)
        assertEquals(listOf<String?>(null), notifications)
    }

    @Test
    fun `notifyUpdateAvailableChanged is invoked with latestVersion on happy-path write`() = runTest {
        val notifications = mutableListOf<String?>()
        server.enqueue(
            MockResponse().setResponseCode(HTTP_OK).setBody(
                """{"latestVersion":"1.0.0","downloadUrl":"https://drive.google.com/x","releasedAt":"2026-06-01"}""",
            ),
        )
        val checker = newChecker(
            currentVersion = "0.1.0",
            notifyUpdateAvailableChanged = { notifications.add(it) },
        )

        checker.checkForUpdates()

        assertEquals(listOf("1.0.0"), notifications)
    }

    @Test
    fun `notifyUpdateAvailableChanged is invoked with null on equal-version clear`() = runTest {
        val notifications = mutableListOf<String?>()
        server.enqueue(
            MockResponse().setResponseCode(HTTP_OK).setBody(
                """{"latestVersion":"0.1.0","downloadUrl":"https://x","releasedAt":"2026-05-16"}""",
            ),
        )
        val checker = newChecker(
            currentVersion = "0.1.0",
            notifyUpdateAvailableChanged = { notifications.add(it) },
        )

        checker.checkForUpdates()

        assertEquals(listOf<String?>(null), notifications)
    }

    @Test
    fun `notifyUpdateAvailableChanged is invoked with null on non-https downloadUrl rejection`() = runTest {
        val notifications = mutableListOf<String?>()
        server.enqueue(
            MockResponse().setResponseCode(HTTP_OK).setBody(
                """{"latestVersion":"1.0.0","downloadUrl":"http://insecure.example/x","releasedAt":"2026-06-01"}""",
            ),
        )
        val checker = newChecker(
            currentVersion = "0.1.0",
            notifyUpdateAvailableChanged = { notifications.add(it) },
        )

        val result = checker.checkForUpdates()

        assertNull(result) // patch P3 — non-https returns null
        assertEquals(listOf<String?>(null), notifications)
    }

    @Test
    fun `notifyUpdateAvailableChanged is NOT invoked on transient network failure`() = runTest {
        // CDN #4 — failure paths must NEVER mutate the flow, otherwise a
        // user losing connection mid-launch would lose a perfectly valid
        // in-memory update-dot signal set on the previous successful launch.
        val notifications = mutableListOf<String?>()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val checker = newChecker(
            currentVersion = "0.1.0",
            notifyUpdateAvailableChanged = { notifications.add(it) },
        )

        val result = checker.checkForUpdates()

        assertNull(result)
        assertEquals(emptyList<String?>(), notifications)
    }

    @Test
    fun `notifyUpdateAvailableChanged is NOT invoked on HTTP 404`() = runTest {
        val notifications = mutableListOf<String?>()
        server.enqueue(MockResponse().setResponseCode(HTTP_NOT_FOUND))
        val checker = newChecker(
            notifyUpdateAvailableChanged = { notifications.add(it) },
        )

        val result = checker.checkForUpdates()

        assertNull(result)
        assertEquals(emptyList<String?>(), notifications)
    }

    @Test
    fun `notifyUpdateAvailableChanged is NOT invoked on malformed JSON`() = runTest {
        val notifications = mutableListOf<String?>()
        server.enqueue(MockResponse().setResponseCode(HTTP_OK).setBody("{not valid"))
        val checker = newChecker(
            notifyUpdateAvailableChanged = { notifications.add(it) },
        )

        val result = checker.checkForUpdates()

        assertNull(result)
        assertEquals(emptyList<String?>(), notifications)
    }

    @Test
    fun `companion constants are stable across releases`() {
        // Stories 6.2 + 6.3 import these constants (CDN #6). Any
        // rename here is a breaking change requiring those stories
        // to be updated in the same PR.
        assertEquals("update_available_version", VersionChecker.KEY_UPDATE_AVAILABLE_VERSION)
        assertEquals(
            "update_available_download_url",
            VersionChecker.KEY_UPDATE_AVAILABLE_DOWNLOAD_URL,
        )
        assertEquals(
            "https://raw.githubusercontent.com/Hernat/VeriSphere/main/version-info.json",
            VersionChecker.VERSION_INFO_URL,
        )
    }

    private companion object {
        const val HTTP_OK: Int = 200
        const val HTTP_NOT_FOUND: Int = 404
        const val HTTP_INTERNAL_ERROR: Int = 500
        const val SHORT_TIMEOUT_SECONDS: Long = 2L
        const val CALL_TIMEOUT_SECONDS: Long = 3L
    }
}
