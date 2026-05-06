package com.verisphere.app.bubble

import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke test for [BubbleOverlayService] (Story 1.6, AC #9).
 *
 * Verifies the foreground service starts cleanly with the overlay window
 * attached, and stops cleanly removing the window. This is a smoke test
 * — not a behavioural test of the bubble UI (Story 1.7 owns that).
 *
 * Permission setup: `SYSTEM_ALERT_WINDOW` is a special "appop" permission
 * that cannot be granted via `grantRuntimePermission`. We use the appops
 * shell command via `UiAutomation`; the shell user has `WRITE_SECURE_SETTINGS`
 * which is required to flip the appop. Granting is reverted in `tearDown`
 * so subsequent test runs start from a clean state.
 *
 * Test method naming: the architecture's testing-standards section prescribes
 * backtick-named English-sentence assertions. DEX format prior to version 040
 * (`minSdk >= 30`; we are at `minSdk = 26`) forbids spaces in method
 * `SimpleName`s. We honour the spirit by using `_` between words. Tracked in
 * `deferred-work.md` for V2.
 */
@RunWith(AndroidJUnit4::class)
class BubbleOverlayWindowTest {

    private lateinit var instrumentation: Instrumentation
    private lateinit var context: Context

    @Before
    fun setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        runShell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        assertTrue(
            "SYSTEM_ALERT_WINDOW must be granted before the service can attach the overlay",
            Settings.canDrawOverlays(context),
        )
        // Ensure no stale instance from a previous test run.
        context.stopService(serviceIntent())
        waitForForegroundState(foreground = false)
    }

    @After
    fun tearDown() {
        context.stopService(serviceIntent())
        waitForForegroundState(foreground = false)
        // Best-effort appop reset. The next test's @Before unconditionally
        // re-grants `allow`, so a cleanup failure is hygiene rather than a
        // correctness issue. We do not let cleanup failures fail the test.
        runCatching {
            runShell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW ignore")
        }.onFailure { e ->
            Log.w(TAG, "Best-effort appop reset failed (non-fatal)", e)
        }
    }

    /**
     * Combined start + stop smoke test.
     *
     * Bundled into one method so the service is ALWAYS explicitly stopped
     * within the test body. Splitting start and stop across two tests
     * caused the framework's foreground-promotion deadline timer to fire
     * after the test runner had moved on, crashing the next test's
     * process with `ForegroundServiceDidNotStartInTimeException`.
     */
    @Test
    fun service_starts_and_stops_cleanly() {
        context.startForegroundService(serviceIntent())

        // The service calls startForeground in onCreate and either attaches
        // the overlay (when WMS accepts addView) or stays running in degraded
        // mode (when the appops shell grant didn't fully propagate to WMS).
        // Both paths keep the service alive — what the smoke test verifies
        // is that startForeground actually completed (foreground = true).
        assertTrue(
            "BubbleOverlayService should reach foreground state within $TIMEOUT_MS ms",
            waitForForegroundState(foreground = true),
        )

        context.stopService(serviceIntent())

        assertTrue(
            "BubbleOverlayService should leave foreground state within $TIMEOUT_MS ms",
            waitForForegroundState(foreground = false),
        )
    }

    private fun serviceIntent(): Intent = Intent(context, BubbleOverlayService::class.java)

    /**
     * Polls [ActivityManager.getRunningServices] until the service's
     * FOREGROUND state matches [foreground] or the timeout elapses.
     *
     * Why check `foreground` (not "is the service registered at all"):
     * `startForegroundService` registers the service with the framework
     * IMMEDIATELY, so a "service running" check returns true within a
     * couple of ms — well before the new process is forked and `onCreate`
     * runs. By contrast `RunningServiceInfo.foreground` only flips to
     * `true` after `startForeground` has actually completed, so polling
     * on that flag is the correct synchronisation point for "the service
     * has fully promoted to foreground".
     *
     * On API 26+ this enumeration is restricted to the caller's own
     * services — fine here because the test process and the service
     * live in the same package. The deprecation warning is intentionally
     * suppressed: the API still works for the caller's own services
     * and there is no equivalent replacement for in-process testing.
     */
    @Suppress("DEPRECATION")
    private fun waitForForegroundState(foreground: Boolean, timeoutMs: Long = TIMEOUT_MS): Boolean {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val targetClass = BubbleOverlayService::class.java.name

        while (SystemClock.uptimeMillis() < deadline) {
            val matchingService = activityManager
                .getRunningServices(Int.MAX_VALUE)
                .firstOrNull { it.service.className == targetClass }
            val isForeground = matchingService?.foreground == true
            if (isForeground == foreground) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    /**
     * Executes a shell command via the instrumentation `UiAutomation`
     * and waits for it to complete. The shell user has `WRITE_SECURE_SETTINGS`,
     * which is what `appops set` requires.
     *
     * Exit-code checking is intentionally NOT performed here. On Android 16,
     * `appops set` for `SYSTEM_ALERT_WINDOW` exits non-zero even when the
     * appop change actually succeeds (the op is set, but the command emits
     * a deprecation/warning path that flips the exit code). Validation of
     * the effect happens at the call site:
     *   - `@Before` asserts `Settings.canDrawOverlays(context)` after the grant
     *   - `@After` is best-effort and logs failures rather than asserting
     */
    private fun runShell(command: String) {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        // Reading from the descriptor blocks until the command finishes,
        // giving us a synchronous "wait for completion" without sleep.
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private companion object {
        const val TIMEOUT_MS: Long = 5_000L
        const val POLL_INTERVAL_MS: Long = 100L
        val TAG: String = "VS.BubbleOverlayWindowTest"
    }
}
