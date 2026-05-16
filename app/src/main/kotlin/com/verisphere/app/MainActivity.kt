package com.verisphere.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.verisphere.app.accessibility.VeriSphereAccessibilityService
import com.verisphere.app.bubble.BubbleOverlayService
import com.verisphere.app.bubble.EXTRA_BUBBLE_ANCHOR_X_PX
import com.verisphere.app.bubble.EXTRA_SESSION_ID
import com.verisphere.app.onboarding.OnboardingOrchestrator
import com.verisphere.app.storage.SecureStorage
import com.verisphere.app.storage.SessionRecord
import com.verisphere.app.ui.detail.AnchoredDetailPanel
import com.verisphere.app.ui.detail.DetailPanelContent
import com.verisphere.app.ui.detail.buildSourceLinkIntent
import com.verisphere.app.ui.history.HistoryScreen
import com.verisphere.app.ui.history.HistoryScreenIntent
import com.verisphere.app.ui.onboarding.AccessibilityExplanationScreen
import com.verisphere.app.ui.onboarding.BatteryOptimizationBottomSheet
import com.verisphere.app.ui.onboarding.OnboardingTutorialOverlay
import com.verisphere.app.ui.onboarding.PermissionExplanationScreen
import com.verisphere.app.ui.onboarding.PermissionVariant
import com.verisphere.app.ui.onboarding.isHostileOem
import com.verisphere.app.ui.theme.VeriSphereTheme
import com.verisphere.app.util.tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * Story 4.4 wired `HistoryScreen.onItemClick` to [openHistoryRecord]
 * which resolves the tapped row's `recordId` via
 * `HistoryRepository.getById` and mounts the same [DetailPanelHost]
 * used by the Verdict×Tap path — FR17 (read-only re-open) is
 * satisfied by construction (no "Verify again" button exists in
 * [com.verisphere.app.ui.detail.DetailPanelContent]) and FR18 is
 * satisfied because `getById` hits the in-memory cache, no network.
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

    // Story 5.2 — first-launch + permission orchestration state.
    // `notificationGranted` flips when ContextCompat re-checks
    // POST_NOTIFICATIONS on API 33+ (always true on API < 33 since the
    // permission is install-time-granted on older OS releases). The
    // tutorial / asked flags survive cold-start via SecureStorage and
    // are seeded asynchronously in onCreate (CDN #8 — first-access
    // Keystore stall lives on Dispatchers.IO).
    private var notificationGranted: Boolean by mutableStateOf(true)
    private var tutorialSeen: Boolean by mutableStateOf(false)
    private var notificationPermissionAsked: Boolean by mutableStateOf(false)

    // Story 5.3 — hostile-OEM battery-optimisation prompt single-show
    // flag (AR26, D5.8). Synchronously seeded in onCreate per CDN #6
    // (P1 sync-seed pattern). Flipped to true in
    // [onBatteryOptimizationDismiss] BEFORE the SecureStorage write
    // resolves so the Compose recomposition drops the bottom-sheet
    // immediately, with the IO write completing in NonCancellable +
    // Dispatchers.IO (CDN #5).
    private var batteryOptimizationPrompted: Boolean by mutableStateOf(false)

    // Story 2.4 — Detail panel state. The bubble service routes a tap
    // here via Intent.ACTION_VIEW + EXTRA_SESSION_ID; onCreate /
    // onNewIntent parse it into pendingDetailSessionId; onResume's
    // coroutine resolves the id against HistoryRepository and flips
    // detailRecordToShow into the panel composable.
    private var detailRecordToShow: SessionRecord? by mutableStateOf(null)
    private var detailBubbleAnchorXPx: Int by mutableStateOf(0)
    private var pendingDetailSessionId: String? = null

    // Story 5.2 — Story 5.1 D2 host-side debounce (Task 6.2). Settings-
    // launching CTAs can be double-tapped on devices that suppress the
    // task-stack dedupe. Guard with a monotonic-wall-clock timestamp;
    // a Compose Button is not debounced and the user's finger naturally
    // produces sub-100 ms second taps when impatient.
    private var lastSettingsLaunchAt: Long = 0L

    // Story 5.2 — Orchestrator instance, wired with SecureStorage's
    // boolean read/write seams (mirrors RateLimitRepositoryImpl /
    // HistoryRepositoryImpl construction patterns from AppContainer).
    // Late-init because the AppContainer is only safely accessible after
    // `attachBaseContext` (i.e., onCreate). Read-only after onCreate.
    private lateinit var secureStorage: SecureStorage
    private lateinit var orchestrator: OnboardingOrchestrator

    /**
     * Story 5.2 — POST_NOTIFICATIONS runtime-permission launcher (API
     * 33+). MUST be initialized at class scope per CDN #7: AndroidX
     * Activity Result API requires `registerForActivityResult` to be
     * called before the Activity reaches `STARTED` lifecycle state,
     * which happens between `onCreate` return and `onStart`. A
     * property initializer is the canonical safe site.
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // CDN #6 — write the "asked" flag regardless of the user's
        // grant/deny choice. The flag captures that the OS dialog was
        // shown, not what the user picked. Subsequent onResume re-checks
        // will pick up `notificationGranted` from the actual permission
        // state via ContextCompat.checkSelfPermission.
        //
        // Story 5.2 code-review P7 — the SecureStorage write is wrapped
        // in `NonCancellable + Dispatchers.IO` because the activity may
        // be destroyed between the launcher callback firing and the
        // write completing (lifecycleScope cancellation surface).
        // NonCancellable matches the symmetric P4 pattern used by
        // `onTutorialComplete` / `onTutorialSkip` for the critical
        // single-write flag persistence.
        notificationGranted = granted
        notificationPermissionAsked = true
        lifecycleScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                orchestrator.markNotificationPermissionAsked()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        secureStorage = (application as VeriSphereApplication).container.secureStorage
        orchestrator = OnboardingOrchestrator(
            readBoolean = secureStorage::readBoolean,
            writeBoolean = secureStorage::writeBoolean,
        )

        overlayGranted = Settings.canDrawOverlays(this)
        accessibilityServiceEnabled = isAccessibilityServiceEnabled()
        notificationGranted = checkNotificationPermission()
        resolvePendingDetailSession(intent)

        // Story 5.2 code-review P1 — seed the SecureStorage-backed flags
        // SYNCHRONOUSLY before `setContent`. The prior implementation
        // launched a `lifecycleScope.launch { withContext(Dispatchers.IO) }`
        // async seed, which produced a visible tutorial flash on every
        // steady-state cold start (returning user with `tutorial_seen=true`
        // saw the tutorial mounted for ~50-200 ms while the IO read
        // completed). Synchronous reads block onCreate for the one-time
        // Keystore first-access cost (architecture L34-37) which is
        // already budgeted by NFR3 (<1 s cold-start). Three small
        // `readBoolean` calls do not change the budget materially and
        // eliminate the race window entirely.
        tutorialSeen = orchestrator.isTutorialSeen()
        notificationPermissionAsked = orchestrator.isNotificationPermissionAsked()
        // Story 5.3 CDN #6 — synchronous seed, same rationale as the
        // two flags above (eliminates flash on cold start for returning
        // users on hostile OEMs who already dismissed the sheet).
        batteryOptimizationPrompted = orchestrator.isBatteryOptimizationPrompted()

        setContent {
            VeriSphereTheme {
                // Story 5.2 — four-state onboarding-aware cascade
                // (extends Story 1.6 + 1.8.5 three-state pattern). Order
                // is mandatory per CDN #9:
                //   1. Notification not granted (API 33+) — blocks first.
                //   2. Overlay not granted.
                //   3. Accessibility off AND tutorial-seen — post-onboarding
                //      revocation fallback (Story 1.8.5 screen reused
                //      per CDN #5).
                //   4. Tutorial not seen yet — first-launch tutorial
                //      (Story 5.1 composable, accessibility activated
                //      via card 1).
                //   5. else — steady-state HistoryScreen.
                //
                // P6 — the DetailPanelHost layer renders ON TOP of the
                // cascade body, gated on overlay+accessibility (Story
                // 2.4 panel-state cleanup). It will NOT mount during the
                // tutorial because the tutorial owns the full screen as
                // a `when {}` branch, not a sibling layer.
                // Story 5.2 — under the `bypassGatesForTest` androidTest
                // hook (BuildConfig.DEBUG only), force the three
                // permission gates true so the cascade reaches the
                // `!tutorialSeen` / steady-state branch. Mirrors the
                // pre-existing pattern used by `panelGateOpen` below for
                // Story 2.4 / 4.4 detail-panel tests. Production
                // behaviour is unchanged (the `testBypass` short-circuit
                // is structurally unreachable in release APKs because
                // `BuildConfig.DEBUG` constant-folds to `false`).
                val testBypass = BuildConfig.DEBUG && bypassGatesForTest
                val overlayOk = overlayGranted || testBypass
                val notificationOk = notificationGranted || testBypass
                val accessibilityOk = accessibilityServiceEnabled || testBypass
                Box {
                    when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationOk -> PermissionExplanationScreen(
                            variant = PermissionVariant.NOTIFICATION,
                            onPrimaryClick = ::requestNotificationPermission,
                            onExit = ::finish,
                        )
                        !overlayOk -> PermissionExplanationScreen(
                            variant = PermissionVariant.OVERLAY,
                            onPrimaryClick = ::launchOverlaySettings,
                            onExit = ::finish,
                        )
                        !accessibilityOk && tutorialSeen -> AccessibilityExplanationScreen(
                            onActivateClick = ::launchAccessibilitySettings,
                            onExitClick = ::finish,
                        )
                        !tutorialSeen -> OnboardingTutorialOverlay(
                            accessibilityServiceEnabled = accessibilityOk,
                            onActivateAccessibilityClick = ::launchAccessibilitySettings,
                            onComplete = ::onTutorialComplete,
                            onSkip = ::onTutorialSkip,
                            // CDN #11 — `null` cut-out for first launch:
                            // the bubble service has not started yet, so
                            // no real anchor exists. The composable
                            // renders scrim-only when `null`.
                            bubbleAnchorOffset = null,
                        )
                        else -> HistoryScreen(
                            // Story 4.4 — tapping a history row resolves
                            // the record via `HistoryRepository.getById`
                            // and mounts the same `DetailPanelHost` used
                            // by the Verdict×Tap path (Story 2.4),
                            // satisfying FR17 (read-only re-open) +
                            // FR18 (offline history; in-memory cache).
                            onItemClick = ::openHistoryRecord,
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
                    // Story 5.3 — hostile-OEM battery-optimisation
                    // bottom-sheet (AR26, D5.8). Mounted as a sibling
                    // layer per CDN #7 (NOT a new cascade branch — the
                    // sheet overlays HistoryScreen, it does not replace
                    // it). The 4-AND gate is consolidated in
                    // [shouldShowBatteryOptimizationPrompt] so the
                    // test-bypass-aware booleans (overlayOk /
                    // notificationOk / accessibilityOk) and the
                    // hostile-OEM detection compose correctly per
                    // CDN #10.
                    if (shouldShowBatteryOptimizationPrompt(
                            overlayOk = overlayOk,
                            notificationOk = notificationOk,
                            accessibilityOk = accessibilityOk,
                        )
                    ) {
                        BatteryOptimizationBottomSheet(
                            onOpenSettings = ::launchBatteryOptimizationSettings,
                            onDismiss = ::onBatteryOptimizationDismiss,
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

    /**
     * Story 4.4 — Activity-side handler for `HistoryItemRow.onClick`
     * (wired from [com.verisphere.app.ui.history.HistoryScreen]'s
     * `onItemClick` lambda parameter, passed in at the `setContent`
     * call site above).
     *
     * Resolves [recordId] against [com.verisphere.app.storage.HistoryRepository.getById]
     * on `lifecycleScope` (suspending on `Dispatchers.IO` inside the
     * repository), then flips [detailRecordToShow] into the same
     * [DetailPanelHost] composable already used by the Verdict×Tap flow
     * (Story 2.4). The panel renders identically to a live verdict —
     * FR17 (read-only re-open) is satisfied by construction because
     * [com.verisphere.app.ui.detail.DetailPanelContent] does not render
     * a "Verify again" / "Refresh" button.
     *
     * **`detailBubbleAnchorXPx = 0` rationale** — there is no live
     * bubble at panel-open time (the user tapped a list row, not a
     * bubble). On the V1 default
     * (`BuildConfig.USE_STANDARD_BOTTOM_SHEET = true`) the anchor is
     * IGNORED by [com.verisphere.app.ui.detail.AnchoredDetailPanel] (the
     * `takeAnchoredPath` branch is `false` whenever the flag is `true`).
     * On the experimental edge-anchored flag-flip, `0` resolves to
     * `LEFT` emergence — acceptable, the user did not tap a bubble so
     * there is no spatial expectation. Story 4.4 spec Critical Dev
     * Note #3.
     *
     * **Null fall-through** — `getById` returns `null` on FIFO eviction
     * (architecture D1.3, 500-entry cap) or process-restart edge where
     * the cache is mid-load. Silent `Log.w` + no panel mount, mirroring
     * the Story 2.4 [tryOpenPendingDetailPanel] precedent. The V1.x
     * "NOT FOUND flash" variant tracked in `deferred-work.md` C1 is out
     * of Story 4.4 scope.
     */
    private fun openHistoryRecord(recordId: String) {
        val container = (application as VeriSphereApplication).container
        lifecycleScope.launch {
            val record = container.historyRepository.getById(recordId)
            if (record != null) {
                detailBubbleAnchorXPx = 0
                detailRecordToShow = record
            } else {
                Log.w(TAG, "openHistoryRecord($recordId) returned null — record evicted")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have returned from any system Settings screen
        // (overlay permission OR accessibility list OR app-notification
        // settings). Re-check all three gates so the UI flips between
        // the four states.
        overlayGranted = Settings.canDrawOverlays(this)
        accessibilityServiceEnabled = isAccessibilityServiceEnabled()
        notificationGranted = checkNotificationPermission()
        // Story 5.3 — re-read the persisted flag so a write from the
        // previous resumed lifecycle (or a force-stop-and-relaunch
        // edge case) is reflected before recomposition evaluates the
        // sibling-layer gate. Sticky-OR: once the in-memory flag is
        // `true`, never revert to `false` from disk — defends the
        // post-dismissal race where the IO write hasn't yet committed
        // before a Settings round-trip's `onResume` re-read fires
        // (code-review P2 — would otherwise re-mount the sheet after a
        // user dismissed it).
        batteryOptimizationPrompted = batteryOptimizationPrompted ||
            orchestrator.isBatteryOptimizationPrompted()

        // Story 5.2 code-review P8 — reset the Settings-launch debounce
        // every time the user returns to MainActivity (from any Settings
        // round-trip). The prior implementation kept a single timestamp
        // across `launchOverlaySettings` and `launchAccessibilitySettings`
        // calls, which blocked legitimate sequential CTAs (e.g. user
        // grants overlay → returns → cascade mounts tutorial Card 1 →
        // taps "Activer" within 500 ms → silently dropped). Resetting
        // on onResume restores the user's "fresh interaction" semantic.
        lastSettingsLaunchAt = 0L

        // Story 2.4 — Resolve any pending detail-panel session id. The
        // lookup hits HistoryRepository's in-memory cache (loaded by the
        // service-side append BEFORE the Verdict state was emitted —
        // Story 1.10 persist-before-publish ordering), so the suspend
        // call returns sub-millisecond.
        tryOpenPendingDetailPanel()

        // Story 5.2 — three-condition service-start gate per AC #4 /
        // CDN #2: canDrawOverlays AND (SDK < 33 OR POST_NOTIFICATIONS)
        // AND isAccessibilityServiceEnabled. Architecture D5.13 + D2.11.
        // Idempotent — `startForegroundService` on an already-running
        // service just routes through `onStartCommand` with a null
        // intent (Story 1.7 lifecycle-flicker guard). Wrapped in
        // try/catch per Story 1.8.5 P5: Android 12+ may throw
        // `ForegroundServiceStartNotAllowedException` if the activity
        // is resumed under doze / restricted bucket.
        //
        // Story 5.2 code-review P6 — additionally gated on `tutorialSeen`
        // so the bubble service does NOT start during the tutorial.
        // Without this gate, the user's Card-1 "Activer" → Settings
        // round-trip → onResume re-checks accessibility-true → service
        // starts → real bubble appears visually OVER tutorial Cards 2-4
        // (which has `bubbleAnchorOffset = null` and renders no cut-out
        // around the now-present bubble).
        if (tutorialSeen &&
            OnboardingOrchestrator.canStartBubbleService(
                overlayGranted = overlayGranted,
                notificationGranted = notificationGranted,
                accessibilityEnabled = accessibilityServiceEnabled,
            )
        ) {
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

        // Story 5.2 code-review DN1 (P15) — `tryMarkFirstLaunchComplete`
        // was removed via YAGNI cleanup. The `first_launch_completed`
        // flag was written but never read; the cascade decisions are
        // made entirely on `tutorialSeen` + permission booleans. If a
        // V2 story needs a "have we ever fully onboarded" gate, it can
        // be re-derived from the existing flags at call time.

        // Story 5.2 — auto-request POST_NOTIFICATIONS on first launch
        // (API 33+ only). Suppresses re-prompting per CDN #6: the flag
        // captures that the dialog has been SHOWN, not the outcome. The
        // gate `!notificationPermissionAsked` short-circuits subsequent
        // onResume calls in the same install. The `bypassGatesForTest`
        // guard (BuildConfig.DEBUG only) prevents the OS dialog from
        // claiming focus during instrumented tests — without it, the
        // dialog pushes MainActivity to PAUSED and `createAndroidComposeRule`
        // never sees the RESUMED state required to query the Compose
        // tree.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationGranted &&
            !notificationPermissionAsked &&
            !(BuildConfig.DEBUG && bypassGatesForTest)
        ) {
            requestNotificationPermission()
        }
    }

    /**
     * Story 5.2 — Returns the current POST_NOTIFICATIONS permission
     * state. On API < 33 the permission is install-time-granted, so the
     * three-condition service-start gate trivially short-circuits this
     * branch via `apiLevel < 33` per CDN #2 second clause. We still
     * return `true` here to keep the Compose-state representation
     * consistent across API levels (so the `when {}` cascade does not
     * stall on a `false` reading that the gate would otherwise ignore).
     */
    private fun checkNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Story 5.2 — Trigger the runtime POST_NOTIFICATIONS dialog via the
     * field-initialized launcher (CDN #7). The launcher callback writes
     * the `notification_permission_asked` flag and updates the
     * `notificationGranted` Compose state on the user's response.
     *
     * Story 5.2 code-review P3 — After Android's automatic "Don't ask
     * again" kicks in (post-second-deny on API 33+), the launcher's
     * `launch()` no-ops silently with `granted=false` and the user is
     * soft-locked on `PermissionExplanationScreen.PermissionVariant.NOTIFICATION`.
     * Detect this state via `!shouldShowRequestPermissionRationale &&
     * notificationPermissionAsked` and deep-link to the system's
     * per-app notification settings instead (mirrors `launchOverlaySettings`
     * pattern). The same `settingsLaunchDebounceOpen()` guard prevents
     * rapid double-taps from spawning multiple Settings instances.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val twiceDeniedLockout = notificationPermissionAsked &&
            !ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        if (twiceDeniedLockout) {
            launchAppNotificationSettings()
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Story 5.2 code-review P3 — Settings deep-link for per-app
     * notification permission, used when the OS has put the user in
     * the twice-denied lockout state (CDN #6 + P3 fix). Mirrors the
     * `launchOverlaySettings` pattern with the same debounce guard.
     */
    private fun launchAppNotificationSettings() {
        if (!settingsLaunchDebounceOpen()) return
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "ACTION_APP_NOTIFICATION_SETTINGS not resolvable on this device", e)
            Toast.makeText(
                this,
                R.string.permission_settings_unavailable,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Story 5.2 — Settings deep-link for the overlay permission. The
     * Story 1.6 inline launch from `PermissionExplanationScreen` was
     * relocated here as part of the stateless-callbacks refactor
     * (CDN #1). 500 ms debounce guard mirrors [launchAccessibilitySettings]
     * for symmetry (Task 6.2 — closes Story 5.1 D2 partially: same
     * surface, same risk).
     */
    private fun launchOverlaySettings() {
        if (!settingsLaunchDebounceOpen()) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "ACTION_MANAGE_OVERLAY_PERMISSION not resolvable on this device", e)
            Toast.makeText(
                this,
                R.string.permission_settings_unavailable,
                Toast.LENGTH_LONG,
            ).show()
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
        //
        // Story 5.2 Task 6.2 — 500 ms debounce closes Story 5.1 D2
        // (Activer-CTA double-tap on stripped OEMs that don't dedupe
        // the Settings task-stack entry).
        if (!settingsLaunchDebounceOpen()) return
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

    /**
     * Story 5.2 Task 6.2 — host-side debounce gate closing Story 5.1 D2
     * (Activer CTA double-tap). A Compose `Button` does not natively
     * debounce; rapid double-taps on Settings-launching CTAs may
     * produce two `startActivity` calls before the task-stack dedupe
     * kicks in, especially on stripped OEM ROMs that intermittently
     * miss the dedupe. 500 ms monotonic-clock minimum interval blocks
     * the second tap without affecting any realistic user interaction.
     *
     * Story 5.2 code-review P2 — uses [SystemClock.uptimeMillis] (monotonic
     * since boot, immune to wall-clock changes) instead of
     * `System.currentTimeMillis`. The prior wall-clock implementation
     * could break on NTP sync / user time-zone change: backward jump →
     * `now - lastSettingsLaunchAt` becomes negative which is `< 500L`
     * → permanent debounce closure; forward jump → debounce bypassed.
     *
     * @return `true` if enough time has elapsed since the last launch
     *   and the caller should proceed; `false` if the call should be
     *   silently dropped.
     */
    private fun settingsLaunchDebounceOpen(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastSettingsLaunchAt < SETTINGS_LAUNCH_DEBOUNCE_MS) return false
        lastSettingsLaunchAt = now
        return true
    }

    /**
     * Story 5.2 — Tutorial completion (Card 4 "Got it"). Persists
     * `tutorial_seen = true` per AC #9 / CDN #4 (single-show invariant)
     * and flips the Compose state so the `when {}` cascade falls
     * through to `HistoryScreen` on the next recomposition. `onResume`
     * subsequently evaluates the three-condition service-start gate
     * which now also requires `tutorialSeen=true` per P6.
     *
     * Story 5.2 code-review P4 — the SecureStorage write is wrapped in
     * `withContext(NonCancellable + Dispatchers.IO)` because the
     * activity may be destroyed (low memory / force-stop / rotation +
     * config-change race) between `lifecycleScope.launch` and the
     * actual `prefs.edit().putBoolean(...).apply()` call. Without
     * `NonCancellable`, `lifecycleScope` cancellation could drop the
     * write → flag never persisted → next launch shows tutorial again
     * → AC #9 / CDN #4 single-show invariant violated. The write is
     * install-lifetime, not activity-lifetime — `NonCancellable` is
     * the canonical Kotlin coroutines pattern for critical writes.
     */
    private fun onTutorialComplete() {
        lifecycleScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                orchestrator.markTutorialSeen()
            }
            tutorialSeen = true
        }
    }

    /**
     * Story 5.2 — Tutorial skip (Cards 2-4 "Skip"). Same persistence
     * contract as [onTutorialComplete] — AC #9 / CDN #4 documents both
     * paths setting the same flag. Card 1 has no skip per Story 5.1
     * AC #3 (cannot reach this path from card 1). Same P4
     * `NonCancellable` wrap as [onTutorialComplete].
     */
    private fun onTutorialSkip() {
        lifecycleScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                orchestrator.markTutorialSeen()
            }
            tutorialSeen = true
        }
    }

    /**
     * Story 5.3 — combined 4-AND gate (AR26, D5.8) deciding whether the
     * [BatteryOptimizationBottomSheet] mounts as a sibling layer over
     * [com.verisphere.app.ui.history.HistoryScreen]. Pure boolean
     * algebra, no side effects.
     *
     * The four conditions (all must be true):
     *   1. `tutorialSeen` — onboarding is complete (CDN #7: sheet
     *      overlays HistoryScreen, the steady-state surface).
     *   2. `canStartBubbleService(...)` — the three permission gates
     *      have passed (`overlayOk && (apiLevel < 33 || notificationOk)
     *      && accessibilityOk`).
     *   3. [isHostileOem] returns `true` for the device's
     *      `Build.MANUFACTURER` (or the test bypass override per
     *      CDN #8).
     *   4. `!batteryOptimizationPrompted` — single-show invariant
     *      (AC #5): once dismissed, never re-shown.
     *
     * CDN #10 — the caller passes the test-bypass-AWARE booleans
     * (`overlayOk`, `notificationOk`, `accessibilityOk`) so that under
     * `bypassGatesForTest = true`, the three permission gates are
     * forced true. Otherwise the AVD's actual permission state would
     * keep the sheet from mounting under instrumented test.
     */
    private fun shouldShowBatteryOptimizationPrompt(
        overlayOk: Boolean,
        notificationOk: Boolean,
        accessibilityOk: Boolean,
    ): Boolean {
        // Capture the static-mutable companion field into a local val
        // before dereferencing — defends the TOCTOU race where a test
        // could null the field between the `!= null` check and the
        // dereference on a different instrumentation thread (code-review
        // P3). Also defends `Build.MANUFACTURER`'s Kotlin platform-type
        // nullability via Elvis — buggy kernels can return `null` even
        // though the Android docs say non-null (code-review P5).
        val testOverride = bypassManufacturerForTest
        val manufacturer = if (BuildConfig.DEBUG && testOverride != null) {
            testOverride
        } else {
            Build.MANUFACTURER ?: ""
        }
        return tutorialSeen &&
            OnboardingOrchestrator.canStartBubbleService(
                overlayGranted = overlayOk,
                notificationGranted = notificationOk,
                accessibilityEnabled = accessibilityOk,
            ) &&
            isHostileOem(manufacturer) &&
            !batteryOptimizationPrompted
    }

    /**
     * Story 5.3 — primary CTA handler for the battery-optimisation
     * bottom-sheet. Launches the system Settings list of
     * battery-optimisation-eligible apps (AR26, D5.8 — NOT the
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` direct-dialog
     * action which would require the forbidden
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission per CDN #3).
     *
     * **CDN #12 persist-BEFORE-Settings ordering**: this method invokes
     * [onBatteryOptimizationDismiss] BEFORE the `startActivity` call so
     * the single-show invariant survives a process kill that may happen
     * while the user is in Settings (low-memory OEM aggressive killers,
     * force-stop). AC #5 explicitly says "regardless of whether the
     * user actually disabled optimisation".
     *
     * Reuses [settingsLaunchDebounceOpen] for the rapid-double-tap
     * guard (Story 5.2 P2 / P8 — monotonic-clock + onResume reset).
     */
    private fun launchBatteryOptimizationSettings() {
        if (!settingsLaunchDebounceOpen()) return
        // CDN #12 — persist BEFORE startActivity so process kill in
        // Settings still preserves the single-show invariant.
        onBatteryOptimizationDismiss()
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(
                TAG,
                "ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS not resolvable on this device",
                e,
            )
            Toast.makeText(
                this,
                R.string.battery_settings_unavailable,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Story 5.3 — dismissal handler for the battery-optimisation
     * bottom-sheet. Persists `battery_optimization_prompted = true`
     * via [OnboardingOrchestrator.markBatteryOptimizationPrompted].
     *
     * **CDN #5 `NonCancellable + Dispatchers.IO` wrap**: mirrors Story
     * 5.2 P4 / P7 pattern ([onTutorialComplete] above). The write is
     * install-lifetime — if the activity is destroyed (low memory /
     * force-stop / rotation race) between this method firing and the
     * `prefs.edit().putBoolean(...).apply()` completion, the flag
     * would be lost → next launch shows the sheet again → AC #5
     * single-show invariant violated.
     *
     * Flip Compose state synchronously (before the coroutine
     * dispatches) so the next recomposition drops the sheet
     * immediately — the IO write completing later only updates the
     * on-disk state, the in-memory state is already correct.
     */
    private fun onBatteryOptimizationDismiss() {
        batteryOptimizationPrompted = true
        lifecycleScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                orchestrator.markBatteryOptimizationPrompted()
            }
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

        /**
         * Story 5.3 — Test-only override of `Build.MANUFACTURER` so
         * `MainActivityBatteryOptimizationTest` can simulate a hostile
         * OEM (e.g. `"samsung"`) on the AVD which actually returns
         * `"Google"`. Read inside
         * [shouldShowBatteryOptimizationPrompt] under a
         * `BuildConfig.DEBUG && bypassManufacturerForTest != null`
         * guard — release builds dead-code-eliminate the branch via
         * constant-folding (CDN #8).
         *
         * **MUST remain `null` in production.** No production code path
         * sets it. The test source set sets it via `@BeforeClass` and
         * resets to `null` via `@AfterClass`.
         */
        @VisibleForTesting
        var bypassManufacturerForTest: String? = null

        /**
         * Story 5.2 Task 6.2 — wall-clock minimum interval between two
         * consecutive Settings launches via [launchAccessibilitySettings]
         * or [launchOverlaySettings]. Closes Story 5.1 D2 (Activer CTA
         * double-tap surface on stripped OEM ROMs).
         */
        private const val SETTINGS_LAUNCH_DEBOUNCE_MS: Long = 500L

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

