package com.verisphere.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.verisphere.app.accessibility.VeriSphereAccessibilityService
import com.verisphere.app.bubble.BubbleOverlayService
import com.verisphere.app.ui.onboarding.AccessibilityExplanationScreen
import com.verisphere.app.ui.onboarding.PermissionExplanationScreen
import com.verisphere.app.ui.theme.VeriSphereTheme
import com.verisphere.app.util.tag

/**
 * Single-Activity host for VeriSphere.
 *
 * Story 1.6 wires the `SYSTEM_ALERT_WINDOW` overlay-permission gate.
 * Story 1.8.5 (Sprint Change 2026-05-07) replaced the Story 1.8
 * `MediaProjectionLauncher` Intent dance with an
 * accessibility-permission-aware UI:
 *
 *   - If `SYSTEM_ALERT_WINDOW` denied → [PermissionExplanationScreen].
 *   - Else if `VeriSphereAccessibilityService` not enabled →
 *     [AccessibilityExplanationScreen] (deep-links to Settings).
 *   - Else → [BootstrapPlaceholder] AND start
 *     [BubbleOverlayService] from `onResume` (the bubble can now
 *     capture).
 *
 * The accessibility-enabled check uses BOTH
 * `AccessibilityManager.getEnabledAccessibilityServiceList` (primary)
 * AND `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` (fallback) per
 * code-review patch P6 — the AccessibilityManager API can lag the
 * Settings toggle by ~1 s on Samsung One UI / Xiaomi MIUI, causing
 * the explanation screen to flicker after the user enables.
 *
 * The `startForegroundService` call is wrapped in try/catch per
 * code-review patch P5 — Android 12+ throws
 * `ForegroundServiceStartNotAllowedException` if the activity is
 * resumed under doze with no recent user interaction (rare but
 * non-zero).
 *
 * Re-checks happen in `onResume` so the UI flips after the user
 * returns from any system Settings screen. Story 5.2 will replace
 * the placeholder bootstrap + the explanation screens with the full
 * onboarding orchestration (Stories 5.1's tutorial + permission
 * sequencing + first-launch detection).
 *
 * The bubble service may route the user back here via
 * [ACTION_REQUEST_ACCESSIBILITY] when a long-press happens but the
 * accessibility service is off — `onNewIntent` brings the activity
 * to the front and `onResume` re-checks the gate.
 */
class MainActivity : ComponentActivity() {

    // Compose-observable so onResume mutations flip the UI without
    // rebuilding the content tree.
    private var overlayGranted: Boolean by mutableStateOf(false)
    private var accessibilityServiceEnabled: Boolean by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        overlayGranted = Settings.canDrawOverlays(this)
        accessibilityServiceEnabled = isAccessibilityServiceEnabled()

        setContent {
            VeriSphereTheme {
                when {
                    !overlayGranted -> PermissionExplanationScreen(onExit = ::finish)
                    !accessibilityServiceEnabled -> AccessibilityExplanationScreen(
                        onActivateClick = ::launchAccessibilitySettings,
                        onExitClick = ::finish,
                    )
                    else -> BootstrapPlaceholder()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Story 1.8.5: the bubble service routes here via
        // ACTION_REQUEST_ACCESSIBILITY when a long-press happens but
        // the accessibility service is off. Update the cached intent
        // (per the documented onNewIntent contract); onResume's gate
        // re-check renders the AccessibilityExplanationScreen.
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // The user may have returned from any system Settings screen
        // (overlay permission OR accessibility list). Re-check both
        // gates so the UI flips between the three states.
        overlayGranted = Settings.canDrawOverlays(this)
        accessibilityServiceEnabled = isAccessibilityServiceEnabled()

        // Once both gates pass, start the bubble service. Idempotent —
        // `startForegroundService` on an already-running service just
        // routes through `onStartCommand` with a null intent, which is
        // handled by Story 1.7's lifecycle-flicker guard. Wrapped in
        // try/catch per code-review patch P5: Android 12+ may throw
        // `ForegroundServiceStartNotAllowedException` if the activity
        // is resumed under doze / restricted bucket. We log and
        // continue — the next user interaction will re-trigger.
        if (overlayGranted && accessibilityServiceEnabled) {
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, BubbleOverlayService::class.java),
                )
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException is a
                // subtype of IllegalStateException. Catching the
                // parent type works across API levels without an
                // SDK_INT branch. The only consequence is that the
                // bubble doesn't start until the next onResume.
                Log.w(TAG, "startForegroundService denied (background-FGS restriction?)", e)
            }
        }
    }

    private fun launchAccessibilitySettings() {
        // ACTION_ACCESSIBILITY_SETTINGS opens the system Accessibility
        // list. The user must scroll to find VeriSphere and toggle it
        // ON. We cannot deep-link directly to our specific entry —
        // Android's API surface is intentionally limited so the user
        // is forced to acknowledge the permission weight (D2.11
        // minimum-permission posture).
        //
        // No FLAG_ACTIVITY_NEW_TASK: same rationale as
        // PermissionExplanationScreen (Story 1.6) — keep the Settings
        // screen in MainActivity's task so Back returns here and
        // triggers the re-check in onResume.
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            // Some stripped Android distributions don't expose the
            // accessibility settings. Fall back to a user-facing
            // instruction instead of crashing on the recovery path.
            Log.w(TAG, "ACTION_ACCESSIBILITY_SETTINGS not resolvable on this device", e)
            Toast.makeText(
                this,
                R.string.accessibility_settings_unavailable,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val ourPackage = packageName
        val ourClass = VeriSphereAccessibilityService::class.java.name

        // Primary check: AccessibilityManager API. Compares on
        // packageName + className via resolveInfo.serviceInfo —
        // unambiguous across OEMs (the AccessibilityServiceInfo.id
        // flatten format varies).
        val accessibilityManager = getSystemService(AccessibilityManager::class.java)
        if (accessibilityManager != null) {
            val byApi = accessibilityManager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                    serviceInfo.packageName == ourPackage && serviceInfo.name == ourClass
                }
            if (byApi) return true
        }

        // Fallback: parse `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
        // directly. Code-review patch P6: the AccessibilityManager
        // cache lags the Settings toggle by ~1 s on Samsung / Xiaomi
        // — the user enables, presses Back, and onResume reads stale
        // empty data. Settings.Secure is the source of truth and
        // updates synchronously with the toggle.
        val enabledRaw = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val ourComponentFlat = ComponentName(this, VeriSphereAccessibilityService::class.java).flattenToString()
        val ourComponentShort = ComponentName(this, VeriSphereAccessibilityService::class.java).flattenToShortString()
        return enabledRaw.split(":").any { it == ourComponentFlat || it == ourComponentShort }
    }

    companion object {
        // Story 1.8.5: the bubble service routes here when a long-press
        // happens but the accessibility service is off. The onResume
        // gate re-check + setContent state flip render the
        // AccessibilityExplanationScreen automatically — no special
        // handling needed in onNewIntent beyond the standard
        // setIntent(intent) update.
        const val ACTION_REQUEST_ACCESSIBILITY: String =
            "com.verisphere.app.action.REQUEST_ACCESSIBILITY"

        private val TAG = tag("MainActivity")
    }
}

/**
 * Placeholder body. Wraps content in a Material 3 `Scaffold` so the
 * status / navigation bar insets propagate correctly under
 * `enableEdgeToEdge()` — without this the placeholder text would
 * draw under the system bars on devices that respect inset reporting.
 */
@Composable
private fun BootstrapPlaceholder() {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = stringResource(R.string.app_name))
        }
    }
}

@Preview(showBackground = true, name = "Light")
@Preview(showBackground = true, name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BootstrapPlaceholderPreview() {
    VeriSphereTheme {
        BootstrapPlaceholder()
    }
}
