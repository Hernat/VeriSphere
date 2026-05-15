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
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.verisphere.app.accessibility.VeriSphereAccessibilityService
import com.verisphere.app.bubble.BubbleOverlayService
import com.verisphere.app.bubble.EXTRA_BUBBLE_ANCHOR_X_PX
import com.verisphere.app.bubble.EXTRA_SESSION_ID
import com.verisphere.app.storage.SessionRecord
import com.verisphere.app.ui.detail.AnchoredDetailPanel
import com.verisphere.app.ui.detail.DetailPanelContent
import com.verisphere.app.ui.detail.buildSourceLinkIntent
import com.verisphere.app.ui.history.HistoryScreen
import com.verisphere.app.ui.history.HistoryScreenIntent
import com.verisphere.app.ui.onboarding.AccessibilityExplanationScreen
import com.verisphere.app.ui.onboarding.PermissionExplanationScreen
import com.verisphere.app.ui.theme.VeriSphereTheme
import com.verisphere.app.util.tag
import kotlinx.coroutines.launch

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
 *   - Else → [com.verisphere.app.ui.history.HistoryScreen] (Story 4.1) AND
 *     start [BubbleOverlayService] from `onResume` (the bubble can now
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

    // Story 2.4 — Detail panel state. The bubble service routes a tap
    // here via Intent.ACTION_VIEW + EXTRA_SESSION_ID; onCreate /
    // onNewIntent parse it into pendingDetailSessionId; onResume's
    // coroutine resolves the id against HistoryRepository and flips
    // detailRecordToShow into the panel composable.
    private var detailRecordToShow: SessionRecord? by mutableStateOf(null)
    private var detailBubbleAnchorXPx: Int by mutableStateOf(0)
    private var pendingDetailSessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        overlayGranted = Settings.canDrawOverlays(this)
        accessibilityServiceEnabled = isAccessibilityServiceEnabled()
        resolvePendingDetailSession(intent)

        setContent {
            VeriSphereTheme {
                // Story 2.4 — Box overlay: the existing 3-state gating
                // UI is the BASE layer; the detail panel mounts ON TOP
                // when detailRecordToShow is non-null. The panel uses
                // M3 ModalBottomSheet's stock dismiss semantics (back,
                // swipe-down, scrim-tap) — no BackHandler override.
                //
                // P6 — the panel render is gated on the same permission
                // predicate used in tryOpenPendingDetailPanel. Without
                // this guard, a stale detailRecordToShow surviving a
                // permission revocation would mount the panel on top of
                // PermissionExplanationScreen / AccessibilityExplanationScreen,
                // hiding the recovery affordance.
                Box {
                    when {
                        !overlayGranted -> PermissionExplanationScreen(onExit = ::finish)
                        !accessibilityServiceEnabled -> AccessibilityExplanationScreen(
                            onActivateClick = ::launchAccessibilitySettings,
                            onExitClick = ::finish,
                        )
                        else -> HistoryScreen(
                            // Story 4.4 will replace this no-op with a
                            // read-only DetailPanelHost open for the
                            // tapped record id. Story 4.1's contract is
                            // the scaffold + ViewModel state surface only.
                            onItemClick = { /* Story 4.4 wires read-only panel open */ },
                            onBackClick = ::finish,
                        )
                    }
                    val record = detailRecordToShow
                    val panelGateOpen = (BuildConfig.DEBUG && bypassGatesForTest) ||
                        (overlayGranted && accessibilityServiceEnabled)
                    if (record != null && panelGateOpen) {
                        DetailPanelHost(
                            record = record,
                            bubbleAnchorXPx = detailBubbleAnchorXPx,
                            onDismiss = { detailRecordToShow = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Story 1.8.5: the bubble service routes here via
        // ACTION_REQUEST_ACCESSIBILITY when a long-press happens but
        // the accessibility service is off. Story 2.4: the same
        // service also routes here via ACTION_VIEW + EXTRA_SESSION_ID
        // for the tap-to-expand flow. Story 4.3: the same service
        // routes here via HistoryScreenIntent.ACTION_OPEN when the
        // user taps the idle bubble — ensure HistoryScreen is the
        // visible base layer. Update the cached intent (per the
        // documented onNewIntent contract) and parse the action.
        setIntent(intent)
        resolvePendingDetailSession(intent)

        // Story 4.3 — when the bubble routes here via ACTION_OPEN, the
        // gating `setContent { when {} else -> HistoryScreen(...) }`
        // block in onCreate already renders HistoryScreen when both
        // permission gates pass, so the only work needed is dismissing
        // any stale DetailPanelHost overlay that might still be mounted
        // from a prior Story 2.4 / 3.3 panel-open. Clearing
        // `detailRecordToShow` triggers automatic Compose recomposition
        // (mutableStateOf) that drops the DetailPanelHost. Also clear
        // `pendingDetailSessionId` so a subsequent `tryOpenPendingDetailPanel`
        // call (e.g. from `onResume` re-entering RESUMED) does not
        // re-open the dismissed panel from a stale prior intent.
        if (intent.action == HistoryScreenIntent.ACTION_OPEN) {
            detailRecordToShow = null
            pendingDetailSessionId = null
        }
    }

    /**
     * Story 2.4 — Parses an [Intent.ACTION_VIEW] carrying the panel
     * extras (set by [com.verisphere.app.bubble.buildDetailPanelIntent]).
     * Other actions (e.g. Story 1.8.5's `ACTION_REQUEST_ACCESSIBILITY`
     * or the launcher's `ACTION_MAIN`) fall through untouched.
     *
     * Also triggers [tryOpenPendingDetailPanel] so a new intent arriving
     * on an already-RESUMED activity opens the panel without waiting for
     * a (non-occurring) `onResume` cycle.
     */
    private fun resolvePendingDetailSession(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_VIEW) return
        // P5 — `hasExtra` returns true even when the stored value is
        // null, so guard on the actual extracted value being non-null
        // BEFORE assigning either state field. Otherwise a stale
        // detailBubbleAnchorXPx could outlive a null session id.
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        pendingDetailSessionId = sessionId
        detailBubbleAnchorXPx = intent.getIntExtra(EXTRA_BUBBLE_ANCHOR_X_PX, 0)
        tryOpenPendingDetailPanel()
    }

    /**
     * Story 2.4 — If there is a pending session id and the permission
     * gates allow it, resolve the [SessionRecord] from
     * [com.verisphere.app.storage.HistoryRepository.getById] and flip
     * [detailRecordToShow] into the panel composable. Safe to call from
     * both `onResume` (initial launch flow) and `resolvePendingDetailSession`
     * (subsequent `onNewIntent` on a running activity).
     */
    private fun tryOpenPendingDetailPanel() {
        val pending = pendingDetailSessionId ?: return
        // P2 — `bypassGatesForTest` is read ONLY in BuildConfig.DEBUG.
        // Release builds always require both gates (production safety
        // posture). @VisibleForTesting is a lint hint, not access
        // control; the BuildConfig.DEBUG guard makes the bypass path
        // structurally unreachable in release APKs.
        val testBypass = BuildConfig.DEBUG && bypassGatesForTest
        val gatesOpen = testBypass || (overlayGranted && accessibilityServiceEnabled)
        if (!gatesOpen) return
        // Clear BEFORE launch so a duplicate trigger (e.g. onNewIntent
        // followed by onResume in the same cycle) does not fire twice.
        pendingDetailSessionId = null
        val container = (application as VeriSphereApplication).container
        lifecycleScope.launch {
            val record = container.historyRepository.getById(pending)
            if (record != null) {
                detailRecordToShow = record
            } else {
                // FIFO eviction edge case (record dropped between
                // Verdict emission and tap). Silent log; panel does
                // not open.
                Log.w(TAG, "getById($pending) returned null — record evicted or never persisted")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have returned from any system Settings screen
        // (overlay permission OR accessibility list). Re-check both
        // gates so the UI flips between the three states.
        overlayGranted = Settings.canDrawOverlays(this)
        accessibilityServiceEnabled = isAccessibilityServiceEnabled()

        // Story 2.4 — Resolve any pending detail-panel session id. The
        // lookup hits HistoryRepository's in-memory cache (loaded by the
        // service-side append BEFORE the Verdict state was emitted —
        // Story 1.10 persist-before-publish ordering), so the suspend
        // call returns sub-millisecond.
        tryOpenPendingDetailPanel()

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

        /**
         * Story 2.4 — Test-only short-circuit for the overlay /
         * accessibility gates so `MainActivityDetailPanelTest` can
         * render `DetailPanelHost` without granting `SYSTEM_ALERT_WINDOW`
         * or activating the accessibility service in the AVD.
         *
         * **MUST remain `false` in production.** No production code path
         * flips it. The test source set sets it via `@Before` and resets
         * to `false` via `@After`.
         */
        @VisibleForTesting
        var bypassGatesForTest: Boolean = false

        private val TAG = tag("MainActivity")
    }
}

/**
 * Story 2.4 P10 — file-level log tag for `DetailPanelHost` failure
 * paths. Follows the `tag("X")` helper convention used elsewhere in
 * the project (replaces the original hard-coded `"VS.DetailPanelHost"`
 * literal flagged by review P10).
 */
private val TAG_DETAIL_HOST = tag("DetailPanelHost")

/**
 * Story 2.4 — Activity-side host for the detail panel. Renders the
 * stock M3 [AnchoredDetailPanel] populated by Story 2.3's
 * [DetailPanelContent]. Wraps the source-link `startActivity` call in
 * typed catches (`ActivityNotFoundException` + `SecurityException`)
 * to close Story 2.1's deferred-work line 23 (browser missing,
 * work-profile, locked-down device) and degrade gracefully on a
 * misbehaving Gemini URL scheme (defer-line-21 — UX-DR1 calm-over-loud).
 *
 * Inlined in `MainActivity.kt` per Story 2.4 Critical Dev Note #5: the
 * host is ~30 LoC + activity-only state; promoting to its own file
 * would force `internal` exposure of helpers and break the
 * `ui/detail/` package's "composables only, no hosts" convention.
 */
@Composable
private fun DetailPanelHost(
    record: SessionRecord,
    bubbleAnchorXPx: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx().toInt() }
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    AnchoredDetailPanel(
        isVisible = true,
        onDismiss = onDismiss,
        bubbleAnchorXPx = bubbleAnchorXPx,
        screenWidthPx = screenWidthPx,
        isLandscape = isLandscape,
    ) {
        DetailPanelContent(
            record = record,
            onSourceClick = { citation ->
                // Story 2.1 deferred-work line 23 close-out: wrap
                // startActivity in typed catches. Calm-over-loud
                // (UX-DR1) — no Toast / Snackbar; the panel stays
                // open and the user can try another source chip.
                //
                // P4 fix — narrow from `runCatching` (which swallows
                // every Throwable including OOM and
                // CancellationException) to two typed catches that
                // match the actual failure modes documented in
                // deferred-work line 23 / line 21. Any other
                // exception bubbles up — that's a real bug, not a
                // graceful no-op.
                try {
                    context.startActivity(buildSourceLinkIntent(citation.url))
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG_DETAIL_HOST, "Source link could not be opened: ${citation.url}", e)
                } catch (e: SecurityException) {
                    // intent:// or content:// scheme from a misbehaving
                    // Gemini response — defer-line-21 URL-scheme guard
                    // is the upstream owner; locally we degrade
                    // gracefully.
                    Log.w(TAG_DETAIL_HOST, "Source link blocked by SecurityException: ${citation.url}", e)
                }
            },
        )
    }
}

