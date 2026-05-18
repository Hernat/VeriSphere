package com.verisphere.app.capture

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.verisphere.app.bubble.BubbleOverlayService
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Story 1.8.5 — instrumented smoke test for the bubble service +
 * AccessibilityService capture path.
 *
 * Drives the bubble window mount + the long-press-without-accessibility
 * routing path on a real device. The dialog-accept happy path
 * (long-press → AccessibilityService.takeScreenshot → Verdict) cannot
 * be automated by UiAutomator because the OS Accessibility settings
 * page requires user interaction to toggle the service ON; that one
 * tap remains a manual smoke step (Task 10.7 in the story file).
 *
 * **Approach:**
 *   1. Grant `SYSTEM_ALERT_WINDOW` via `appops set` (same trick as
 *      Story 1.6's `BubbleOverlayWindowTest`).
 *   2. The accessibility service stays DISABLED for these tests
 *      (we cannot enable it programmatically; the AVD's default is off).
 *   3. Start `BubbleOverlayService` directly via `Context.startForegroundService`.
 *   4. Verify the bubble window attaches (UiAutomator descContains).
 *   5. Verify a long-press routes the user back to MainActivity (the
 *      ACTION_REQUEST_ACCESSIBILITY path) — surfaced via the explanation
 *      screen's "Activer" button being findable.
 *   6. tearDown stops the service and revokes the appop.
 *
 * **Naming:** spaces in backtick names work for `src/test/` (JVM) but
 * NOT for `androidTest/` until `minSdk >= 30` (DEX < 040 limitation).
 * minSdk is 30 from Story 1.8.5 onward, but the existing convention
 * across the test suite still uses underscores — kept for consistency.
 */
@RunWith(AndroidJUnit4::class)
class CaptureFlowSmokeTest {

    private lateinit var device: UiDevice
    private lateinit var context: Context

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context = InstrumentationRegistry.getInstrumentation().targetContext
        runShell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        assertTrue(
            "SYSTEM_ALERT_WINDOW must be granted before the bubble can attach",
            Settings.canDrawOverlays(context),
        )
        // Ensure no stale service from a previous run.
        context.stopService(serviceIntent())
        SystemClock.sleep(STABILISATION_MS)
    }

    @After
    fun tearDown() {
        // Code review patch P12 (Story 1.8): revert the appop grant +
        // stop the service so subsequent test classes see a clean
        // device state. Best-effort: never let teardown failures fail
        // a passing test.
        runCatching { context.stopService(serviceIntent()) }
        runCatching { runShell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW ignore") }
    }

    @Test
    fun bubble_window_attaches_after_service_start() {
        runShell("logcat -c")
        context.startForegroundService(serviceIntent())
        SystemClock.sleep(SERVICE_BOOT_MS)

        // Wait for the bubble's compose view to mount. UiAutomator's
        // global By.descContains targets every accessible window — the
        // bubble's contentDescription "Bulle VeriSphere, au repos" is set
        // in BubbleOverlay.kt (Story 7.5 C6 French baseline; substring
        // match on "Bulle VeriSphere" covers every BubbleState variant).
        val bubbleVisible = device.wait(
            Until.hasObject(By.descContains("Bulle VeriSphere,")),
            BUBBLE_VISIBLE_TIMEOUT_MS,
        )
        assertTrue("Bubble overlay must be visible within $BUBBLE_VISIBLE_TIMEOUT_MS ms", bubbleVisible)

        context.stopService(serviceIntent())
        SystemClock.sleep(STABILISATION_MS)
    }

    @Test
    fun service_starts_cleanly_on_foreground_promotion() {
        // Verifies the post-Story-1.8.5 service start path:
        // - No more MediaProjection FGS-type promote/demote at startup.
        // - Service stays at foregroundServiceType="specialUse" for its
        //   entire lifetime.
        // - Notification posts; service is in foreground.
        runShell("logcat -c")
        context.startForegroundService(serviceIntent())
        SystemClock.sleep(SERVICE_BOOT_MS)

        val running = isServiceForeground()
        assertTrue("BubbleOverlayService must be in the foreground", running)

        // Confirm the bubble overlay attached (the service's onCreate
        // ran past the FGS-deadline guard and through attachOverlayView).
        val bubbleVisible = device.wait(
            Until.hasObject(By.descContains("Bulle VeriSphere,")),
            BUBBLE_VISIBLE_TIMEOUT_MS,
        )
        assertTrue("Bubble overlay must be visible after foreground promotion", bubbleVisible)

        context.stopService(serviceIntent())
        SystemClock.sleep(STABILISATION_MS)
    }

    @Test
    fun long_press_with_accessibility_disabled_routes_to_explanation_screen() {
        // Story 1.8.5 AC #12 / Task 9.4 — the test that was originally
        // skipped (code-review patch P10 from Story 1.8.5 review).
        //
        // Setup: SYSTEM_ALERT_WINDOW granted (via setUp's appops) but
        // VeriSphereAccessibilityService is NOT enabled (the AVD's
        // default state — the test runner has no privileges to toggle
        // the accessibility service). This is the user state when the
        // bubble service is alive but accessibility was disabled mid-
        // session OR the user dismissed the AccessibilityExplanationScreen.
        //
        // Expected: long-press on the bubble → BubbleOverlayService.onBubbleLongPress
        // sees `VeriSphereAccessibilityService.instance == null` →
        // launches MainActivity with ACTION_REQUEST_ACCESSIBILITY →
        // MainActivity.onResume re-checks the gate → `accessibilityServiceEnabled`
        // is false → setContent renders AccessibilityExplanationScreen
        // whose body has the "Activer" button.
        //
        // We assert the "Activer" button appears via UiAutomator's
        // By.text() lookup (the string is in French per UX-DR17:
        // R.string.accessibility_action_activate = "Activer").
        runShell("logcat -c")
        context.startForegroundService(serviceIntent())
        SystemClock.sleep(SERVICE_BOOT_MS)

        // Confirm the bubble is attached before we attempt the long-press.
        val bubbleVisible = device.wait(
            Until.hasObject(By.descContains("Bulle VeriSphere,")),
            BUBBLE_VISIBLE_TIMEOUT_MS,
        )
        assertTrue("Bubble must be visible before injecting long-press", bubbleVisible)

        // Locate the bubble window's bounds so we can target the long-press.
        val bubble = device.findObject(By.descContains("Bulle VeriSphere,"))
        assertNotNull("Bubble UI object lookup must succeed", bubble)
        val bounds = bubble.visibleBounds
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()

        // UiDevice.swipe(x1, y1, x2, y2, steps) with x1=x2,y1=y2 holds at
        // a single point. 100 steps × ~5 ms/step ≈ 500 ms — too short for
        // our 1 s long-press threshold. Use 250 steps ≈ 1250 ms.
        device.swipe(centerX, centerY, centerX, centerY, LONG_PRESS_STEPS)

        // Wait for MainActivity to come to the front rendering the
        // AccessibilityExplanationScreen — its "Activer" button is the
        // signal.
        val activerButtonShown = device.wait(
            Until.hasObject(By.text("Activer")),
            LONG_PRESS_REACTION_TIMEOUT_MS,
        )
        assertTrue(
            "After long-press without accessibility, AccessibilityExplanationScreen " +
                "must surface (looking for 'Activer' button)",
            activerButtonShown,
        )

        context.stopService(serviceIntent())
        SystemClock.sleep(STABILISATION_MS)
    }

    private fun serviceIntent(): Intent =
        Intent(context, BubbleOverlayService::class.java)

    private fun isServiceForeground(): Boolean {
        // Poll briefly — the FGS promotion is asynchronous.
        val deadline = SystemClock.elapsedRealtime() + SERVICE_BOOT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val output = runShell("dumpsys activity services com.verisphere.app")
            if (output.contains("isForeground=true")) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun runShell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd = automation.executeShellCommand(command)
        return BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(pfd))).use { it.readText() }
    }

    private companion object {
        private const val SERVICE_BOOT_MS: Long = 2_000L
        private const val STABILISATION_MS: Long = 500L
        private const val POLL_INTERVAL_MS: Long = 200L
        private const val BUBBLE_VISIBLE_TIMEOUT_MS: Long = 5_000L
        private const val LONG_PRESS_STEPS: Int = 250
        private const val LONG_PRESS_REACTION_TIMEOUT_MS: Long = 5_000L
    }
}
