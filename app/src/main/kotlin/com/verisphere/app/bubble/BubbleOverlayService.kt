package com.verisphere.app.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.verisphere.app.AppContainer
import com.verisphere.app.MainActivity
import com.verisphere.app.R
import com.verisphere.app.VeriSphereApplication
import com.verisphere.app.accessibility.VeriSphereAccessibilityService
import com.verisphere.app.bubble.ui.BubbleOverlay
import com.verisphere.app.bubble.ui.FailureFlashTooltip
import com.verisphere.app.bubble.ui.FlashTooltip
import com.verisphere.app.bubble.ui.PointerDirection
import com.verisphere.app.capture.CapturePipeline
import com.verisphere.app.gemini.VerificationOutcome
import com.verisphere.app.storage.SessionRecord
import com.verisphere.app.ui.theme.VeriSphereTheme
import com.verisphere.app.util.isReduceMotionEnabled
import com.verisphere.app.util.tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Foreground service hosting the persistent bubble overlay (architecture:
 * AR19, AR20, AR21, D4.10, D5.2; PRD: FR1, FR2, NFR15).
 *
 * Why: the bubble must survive app-switch / rotation / dim and remain
 * visible across every other app. A foreground `Service` with a silent
 * `IMPORTANCE_MIN` notification is the only Android primitive that
 * provides this guarantee without battery-optimisation kills.
 *
 * The service hosts a [ComposeView] mounted via [WindowManager] using
 * `TYPE_APPLICATION_OVERLAY`. To make Compose work inside a non-Activity
 * host, the service implements the lifecycle trio:
 *   - [LifecycleOwner] via a manual [LifecycleRegistry]
 *   - [SavedStateRegistryOwner] via a [SavedStateRegistryController]
 *   - [ViewModelStoreOwner] via a private [ViewModelStore]
 *
 * Story 1.7 fills the previously-empty `setContent {}` with [BubbleOverlay]
 * and owns the drag + edge-snap arithmetic — composables in `bubble/ui/`
 * are stateless consumers per the architecture's State-boundary section.
 * The service's [bubbleStateMachine] holds the `Idle(faded)` state and
 * runs the 5 s inactivity timer. Position is persisted via
 * [container.secureStorage] under [KEY_BUBBLE_X] / [KEY_BUBBLE_Y].
 *
 * Story 1.8.5 (Sprint Change 2026-05-07) routes captures through
 * [VeriSphereAccessibilityService] (architecture D5.13, AR27) instead
 * of `MEDIA_PROJECTION` (D5.3 deprecated, AR22 deprecated). The bubble
 * service no longer holds any capture token — it just triggers the
 * pipeline on long-press, and the pipeline reads the active accessibility
 * service via `VeriSphereAccessibilityService.instance` (a `@Volatile`
 * static reference set in `onServiceConnected`). The foreground-service
 * type stays at `specialUse` for the entire service lifetime — no
 * runtime promote/demote dance any more (D5.2 amended).
 *
 * On long-press the service either (a) starts the capture pipeline
 * directly if [VeriSphereAccessibilityService.instance] is non-null, or
 * (b) routes the user back to [MainActivity] (which renders
 * [com.verisphere.app.ui.onboarding.AccessibilityExplanationScreen]) so
 * they can activate the accessibility service in Settings.
 *
 * Threading: `onCreate`, `onStartCommand`, `onDestroy` run on the main
 * thread. Lifecycle event dispatch must therefore happen on the main
 * thread — [serviceScope] uses `Dispatchers.Main.immediate` for that.
 *
 * NEVER add Service-owned collaborators to `AppContainer` — see the
 * comment at the bottom of `AppContainer.kt`.
 */
// ─── Story 2.4 — Tap-to-expand Intent contract (file-level for JVM testability) ──────

/**
 * Story 2.4 — Intent extra carrying the [SessionRecord.id] of the verdict the user
 * tapped to expand. Resolved activity-side via
 * [com.verisphere.app.storage.HistoryRepository.getById] (in-memory cache hit, sub-ms
 * after Story 1.10's persist-before-publish ordering).
 */
internal const val EXTRA_SESSION_ID: String = "com.verisphere.app.extra.SESSION_ID"

/**
 * Story 2.4 — Intent extra carrying the bubble's visible-centre X coordinate (raw
 * window pixels) at tap time. Consumed by [com.verisphere.app.ui.detail.AnchoredDetailPanel]'s
 * `computeEmergenceEdge` so the panel slides in from the side nearest the bubble
 * when `BuildConfig.USE_STANDARD_BOTTOM_SHEET = false`. **V1 ships the flag as
 * `true`** so this extra is functionally inert until the architect flips it — but
 * plumbed regardless so the flip is a one-line change.
 */
internal const val EXTRA_BUBBLE_ANCHOR_X_PX: String = "com.verisphere.app.extra.BUBBLE_ANCHOR_X_PX"

/**
 * Story 2.4 — Pure factory for the panel-launch [Intent]. File-level (NOT
 * inside [BubbleOverlayService]) so the androidTest / JVM-test surface can call
 * it without instantiating the Service.
 *
 * **Flags rationale** — same pattern as Story 1.8.5's
 * `launchAccessibilityExplanationActivity`:
 *  - `FLAG_ACTIVITY_NEW_TASK` is required because the caller is a Service (no
 *    Activity stack to launch into).
 *  - `FLAG_ACTIVITY_SINGLE_TOP` combines with the manifest's
 *    `launchMode="singleTop"` to route into `MainActivity.onNewIntent` when the
 *    activity is already in the task (the typical case post-onboarding).
 *
 * **Action rationale** — `Intent.ACTION_VIEW` is idiomatic for "navigate to a
 * viewable resource" (the resource here is the verdict identified by
 * [sessionId]). UX-DR14's `Verdict × Tap = Expand detail panel`.
 *
 * @param context Source context for the explicit [MainActivity] component.
 * @param sessionId The [SessionRecord.id] UUID string of the tapped verdict.
 * @param bubbleAnchorXPx The bubble's visible-centre X coordinate at tap time,
 *                       in raw window pixels.
 */
internal fun buildDetailPanelIntent(
    context: Context,
    sessionId: String,
    bubbleAnchorXPx: Int,
): Intent = Intent(context, MainActivity::class.java)
    .setAction(Intent.ACTION_VIEW)
    .putExtra(EXTRA_SESSION_ID, sessionId)
    .putExtra(EXTRA_BUBBLE_ANCHOR_X_PX, bubbleAnchorXPx)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

class BubbleOverlayService :
    Service(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val _viewModelStore = ViewModelStore()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var container: AppContainer
    private lateinit var bubbleStateMachine: BubbleStateMachine
    private lateinit var capturePipeline: CapturePipeline
    private var overlayAttached: Boolean = false
    private var snapJob: Job? = null
    private var captureJob: Job? = null

    // Story 2.4 smoke 2026-05-11 (+ Story 3.3) — snapshot of the most
    // recently entered tappable state's record. Required because the
    // gesture handler dispatches `LongPressStarted` on touch-down, which
    // transitions the state machine Verdict / FailureState.PossibleInjection
    // → Pressing BEFORE `onBubbleTap` fires on release. Without this
    // snapshot, `state.value` is already Pressing at tap time and
    // `handleTappableBubbleTap` misses the record.
    //
    // Lifecycle: set when state enters Verdict OR
    // FailureState.PossibleInjection (via state observer); cleared when
    // state enters Idle. Read by `onBubbleTap` when the current state is
    // Pressing-immediately-after-tappable.
    @Volatile
    private var lastTappableRecordForTap: SessionRecord? = null

    // Story 4.3 — pre-touch-down snapshot of the most recently entered
    // non-Pressing state. Used by `onBubbleTap` / `onBubblePressCancelled`
    // to decide between Idle × Tap (open history) and the existing
    // Verdict / PossibleInjection × Tap (open detail panel) /
    // FailureState.{Offline|Timeout|DailyLimit|QuotaExhausted} × Tap
    // (silent dismissal — no panel, no history).
    //
    // Set by the state observer when entering Idle / Verdict /
    // PossibleInjection / non-tappable FailureState / Capturing /
    // Thinking; INTENTIONALLY left untouched on entering Pressing (the
    // touch-down phase) so the snapshot survives the synchronous
    // LongPressStarted dispatch and is readable when the tap callback
    // fires on release.
    //
    // Mirrors the existing `lastTappableRecordForTap` snapshot lifecycle
    // (Story 2.4 smoke fix + Story 3.3 P1 patch): the tap callbacks
    // read both fields, then `BubbleEvent.BackToIdle` clears the
    // snapshot transitively via the observer's Idle arm.
    @Volatile
    private var lastNonPressingState: BubbleState? = null

    // Story 1.10 — separate WindowManager view for the FlashTooltip
    // (Critical Dev Note #3 in story file). Kept distinct from the
    // bubble's halo window so the bubble's 104 dp + FLAG_NOT_TOUCH_MODAL
    // semantics are preserved unchanged. Lifecycle:
    //   - created on transition into BubbleState.Verdict;
    //   - removed on transition out of BubbleState.Verdict.
    private var tooltipView: ComposeView? = null
    private var tooltipParams: WindowManager.LayoutParams? = null

    // Story 7.4 P3 (code-review patch) — cached main-looper Handler.
    // Was previously instantiated per-call inside updateTooltipWindowLayout
    // which allocated a fresh Handler on every drag delta (~60 fps); the
    // single shared instance saves allocations. FIFO ordering on the main
    // looper's MessageQueue is preserved regardless of Handler-instance
    // count, so this change is allocation-hygiene only, not a correctness fix.
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var tooltipObserverJob: Job? = null

    // Story 1.10 — published bubble position in screen pixels (top-left
    // of the halo window, i.e. `params.x`, `params.y`). Tooltip's Compose
    // tree reads this to position itself relative to the bubble; updated
    // whenever drag / snap / persist mutates `params`.
    private val bubblePositionFlow = MutableStateFlow(Point(0, 0))

    // Drag-rounding accumulators (Story 1.7 review fix #4): per-frame
    // dxPx / dyPx are floats; rounding each independently to Int truncates
    // sub-pixel motion (≈ 0.4 px/frame on slow drags rounds to 0 every
    // frame → bubble does not move). Carry the fractional remainder
    // forward across frames so net motion is preserved. Reset on drag
    // end so the next gesture starts clean.
    private var pendingDx: Float = 0f
    private var pendingDy: Float = 0f

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = _viewModelStore

    override fun onCreate() {
        super.onCreate()
        // Order rationale (Story 1.6 + 1.7 + review amendments):
        //   1. performAttach()                  — register Recreator observer.
        //   2. performRestore(null)             — sets isRestored = true.
        //      (If ON_CREATE is dispatched before performRestore, the
        //       Recreator throws IllegalStateException; see savedstate.Recreator.)
        //   3. createNotificationChannel + startForegroundCompat — RUN
        //      BEFORE any step that can throw so the FGS deadline contract
        //      is satisfied even if a later init step fails. Story 1.6 set
        //      this invariant; Story 1.7's review reinstated it after the
        //      initial draft moved container init in front of these calls
        //      (the `application as VeriSphereApplication` cast is a
        //      ClassCastException source under instrumentation tests).
        //   4. container + bubbleStateMachine   — Story 1.7. Captured here
        //      because attachOverlayView() reads container.secureStorage
        //      for the persisted bubble position. Safe to do AFTER
        //      startForegroundCompat: any throw here can no longer breach
        //      the 5 s FGS deadline.
        //   5. handleLifecycleEvent(ON_CREATE)  — fires Recreator observer,
        //      which now safely consumes the restored state.
        //   6. WindowManager init + attachOverlayView — best-effort;
        //      BadTokenException → degraded mode (composition disposed).
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)

        createNotificationChannel()
        startForegroundCompat()

        container = (application as VeriSphereApplication).container
        // Story 3.4 — read reduce-motion preference ONCE on service
        // creation (UX-DR16 "read once per session"). The flag is then
        // cached as a public val on the state machine; Composables
        // read it via the same bubbleStateMachine reference. A
        // mid-session toggle requires the user to stop + restart
        // VeriSphere, consistent with how Android itself treats
        // ANIMATOR_DURATION_SCALE changes.
        bubbleStateMachine = BubbleStateMachine(
            coroutineScope = serviceScope,
            reduceMotionEnabled = isReduceMotionEnabled(applicationContext),
        )

        // Story 1.8.5 (Sprint Change 2026-05-07): pipeline reads the
        // active VeriSphereAccessibilityService via its @Volatile static
        // instance. The lambda-provider design (Story 1.8 review patch
        // P5 retained) makes this swap mechanical — no MediaProjection
        // token holder needed any more.
        //
        // Code-review patch P4: throw a typed ServiceUnboundException
        // (instead of generic `error(...)` which produces an
        // IllegalStateException) so the pipeline's single error funnel
        // can specifically map it to Failure.PermissionDenied — covers
        // the TOCTOU window between `hasToken()` returning true and
        // `frameExtractor()` being invoked (user disables accessibility
        // mid-capture).
        capturePipeline = CapturePipeline(
            rateLimitRepository = container.rateLimitRepository,
            hasToken = { VeriSphereAccessibilityService.instance != null },
            frameExtractor = {
                val service = VeriSphereAccessibilityService.instance
                    ?: throw VeriSphereAccessibilityService.ServiceUnboundException()
                service.captureScreenshot()
            },
            // Story 1.9: real Gemini call replaces Story 1.8's simulated
            // 200 ms delay + synthetic verdict. GeminiClient.verify is
            // the single boundary to generativelanguage.googleapis.com
            // (architecture line 752) and never throws (architecture
            // line 487 — single error funnel).
            //
            // Code-review patch P9 — deferring lambda (NOT a method
            // reference). `container.geminiClient::verify` would force
            // the `geminiClient` lazy + `httpClient` lazy + asset read
            // (patch P14) on the main thread inside onCreate, eating into
            // the FGS deadline. The lambda below defers the lazy chain
            // until first capture — and the first invocation lands inside
            // CapturePipeline's `Dispatchers.IO` context, so the asset
            // read happens on IO (architecture line 473).
            verify = { frame -> container.geminiClient.verify(frame) },
        )

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(WindowManager::class.java)
        attachOverlayView()
        startTooltipObserver()

        Log.d(TAG, "Service created; overlay attached=$overlayAttached")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Compose LaunchedEffect only fires on RESUMED; advancing past
        // ON_START is required for future stories' bubble effects to run
        // (Story 1.6 review deferred item, addressed in Story 1.7 AC #11).
        //
        // Guard against multi-start flicker (Story 1.7 review fix #8):
        // a second onStartCommand call after we are already RESUMED would
        // dispatch ON_START on a RESUMED registry, which downgrades through
        // ON_PAUSE → STARTED then back up via ON_RESUME — any LifecycleObserver
        // (e.g. ComposeView's composition) sees a spurious PAUSE/RESUME pair
        // and tears down + rebuilds LaunchedEffects.
        if (lifecycleRegistry.currentState != Lifecycle.State.RESUMED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        // Story 1.8.5 (Sprint Change 2026-05-07): no more
        // ACTION_DELIVER_TOKEN branch — the MediaProjection Intent dance
        // was deleted. The service no longer needs to receive token
        // deliveries because the AccessibilityService owns its own
        // permission lifecycle (granted via Settings, persistent across
        // reboots).

        // START_STICKY: Android auto-restarts the service after process
        // death (NFR15). The intent is null on restart — that is fine,
        // the bubble has no per-start parameters in V1.
        return START_STICKY
    }

    override fun onDestroy() {
        // State-aware lifecycle teardown (Story 1.7 review fix #9):
        // if the framework destroys the service before onStartCommand
        // ever ran (memory pressure, startup error path), the registry
        // is at CREATED. Dispatching ON_PAUSE from CREATED is invalid;
        // LifecycleRegistry will throw / log. Each event is gated by
        // `currentState.isAtLeast(...)` re-read after each dispatch so
        // we walk the registry down through whatever portion of the
        // grammar applies.
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        // Dispose the state machine's timer Job before serviceScope is
        // cancelled, so the dispose path is independent of scope cancel.
        if (::bubbleStateMachine.isInitialized) {
            bubbleStateMachine.dispose()
        }
        // Story 1.10 — cancel the tooltip observer Job and detach the
        // tooltip view BEFORE serviceScope.cancel() so the
        // detachTooltipView() call sees a still-valid windowManager.
        tooltipObserverJob?.cancel()
        tooltipObserverJob = null
        detachTooltipView()
        // Story 1.8.5: no MediaProjection token to release — the
        // VeriSphereAccessibilityService owns its own lifecycle via
        // Settings (D5.13). The bubble service tear-down is now leaner.
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        detachOverlayView()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        _viewModelStore.clear()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed; overlay removed")
        super.onDestroy()
    }

    // V1 has no bound clients — the service is started, never bound.
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bubble_service_channel_name),
            // IMPORTANCE_MIN: silent, no peek, hidden in collapsed shade.
            // IMPORTANCE_LOW would still show in the shade with no sound;
            // we want maximally unobtrusive.
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            // setContentTitle: some OEM shades render a title-less FGS
            // notification as "(no title)" — set explicitly to the app
            // name to avoid that.
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.bubble_service_ready))
            .setSmallIcon(R.drawable.ic_notification_silent)
            // CATEGORY_SERVICE: the documented Android pattern for FGS
            // notifications. Lets the system DND / shade rules treat this
            // as an ongoing service indicator rather than a generic
            // notification.
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires the FGS type to be passed at runtime to
            // match the manifest declaration. specialUse only for now;
            // mediaProjection is added dynamically in Story 1.8.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun attachOverlayView() {
        val (screenWidthPx, screenHeightPx) = currentWindowSizePx()
        val density = resources.displayMetrics.density
        val bubbleSizePx = (BUBBLE_DIAMETER_DP * density).roundToInt()
        val haloSizePx = (BUBBLE_HALO_DIAMETER_DP * density).roundToInt()
        val insetPx = (EDGE_INSET_DP * density).roundToInt()

        // Default position: right edge, vertically centred. Computed
        // against the visible bubble (56 dp), not the halo window — the
        // halo extends beyond the visible bubble symmetrically so we
        // shift the LayoutParams x/y back by the halo offset to keep
        // the visible bubble flush against the inset.
        val haloOffsetPx = (haloSizePx - bubbleSizePx) / 2
        val defaultX = screenWidthPx - bubbleSizePx - insetPx - haloOffsetPx
        val defaultY = (screenHeightPx - bubbleSizePx) / 2 - haloOffsetPx

        val persistedX = container.secureStorage.readLong(KEY_BUBBLE_X, defaultX.toLong()).toInt()
        val persistedY = container.secureStorage.readLong(KEY_BUBBLE_Y, defaultY.toLong()).toInt()

        // Defensive clamp: a previously-persisted value could be off-screen
        // if the user rotated the device or the display configuration
        // changed between sessions. params.x/y are top-left of the halo
        // window; the visible bubble must stay within
        // [insetPx, screenWidth - bubbleSize - insetPx].
        val minX = insetPx - haloOffsetPx
        val maxX = screenWidthPx - bubbleSizePx - insetPx - haloOffsetPx
        val minY = insetPx - haloOffsetPx
        val maxY = screenHeightPx - bubbleSizePx - insetPx - haloOffsetPx

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Flags rationale (D4.10):
            //   FLAG_NOT_FOCUSABLE      — touches outside the bubble
            //                             reach the underlying app.
            //   FLAG_LAYOUT_NO_LIMITS   — overlay can extend past screen
            //                             edges (drag).
            //   FLAG_NOT_TOUCH_MODAL    — touches outside our window are
            //                             not intercepted by us.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // TOP|START gravity makes (x, y) measure from the top-left.
            // The default centred gravity offsets x from screen centre,
            // which breaks the edge-snap arithmetic.
            gravity = Gravity.TOP or Gravity.START
            x = persistedX.coerceIn(minX, maxX)
            y = persistedY.coerceIn(minY, maxY)
        }

        composeView = ComposeView(this).apply {
            // ViewTree owners MUST be set before setContent {}, otherwise
            // Compose throws "ViewTreeLifecycleOwner not found" at first
            // composition.
            setViewTreeLifecycleOwner(this@BubbleOverlayService)
            setViewTreeSavedStateRegistryOwner(this@BubbleOverlayService)
            setViewTreeViewModelStoreOwner(this@BubbleOverlayService)
            setContent {
                VeriSphereTheme {
                    val state by bubbleStateMachine.state.collectAsState()
                    // Story 6.2 — reactive update-available signal sourced
                    // from AppContainer.updateAvailableVersion (mirrors
                    // SecureStorage via VersionChecker's notify-seam).
                    // collectAsState recomposes the bubble Box when
                    // VersionChecker writes/clears the keys — i.e. when
                    // the launch fetch completes after the bubble service
                    // is already up.
                    val updateVersion by container.updateAvailableVersion.collectAsState()
                    BubbleOverlay(
                        state = state,
                        onUserActivity = ::onBubbleUserActivity,
                        onLongPressStart = ::onBubbleLongPressStart,
                        onPressCancelled = ::onBubblePressCancelled,
                        onTap = ::onBubbleTap,
                        onTapNearMiss = ::onBubbleTapNearMiss,
                        onLongPress = ::onBubbleLongPress,
                        onDragDelta = ::onBubbleDragDelta,
                        onDragEnd = ::onBubbleDragEnd,
                        // Story 3.4 — read the cached flag directly. The
                        // value never mutates for the life of this
                        // BubbleStateMachine instance (read once at
                        // onCreate above), so no collectAsState wrapper
                        // is needed; Compose treats the captured value
                        // as a constant for this composition.
                        reduceMotion = bubbleStateMachine.reduceMotionEnabled,
                        hasUpdateAvailable = updateVersion != null,
                    )
                }
            }
        }

        // Story 1.10 — publish the initial bubble position so the tooltip
        // window's Compose tree (created later on entering Verdict)
        // observes a valid coordinate from its first composition.
        publishBubblePosition()

        try {
            windowManager.addView(composeView, params)
            overlayAttached = true
        } catch (e: WindowManager.BadTokenException) {
            // SYSTEM_ALERT_WINDOW was revoked between MainActivity's
            // canDrawOverlays() check and the service reaching addView, OR
            // the appop grant did not propagate to WMS's permission cache
            // (a known race on Android 14+ when overlay grant is set via
            // `appops set` shell rather than the Settings UI). Either way
            // we cannot host the bubble.
            //
            // Design: log and stay alive in DEGRADED MODE — foreground
            // notification only, no overlay. Calling stopSelf here, even
            // deferred via Handler.post, races Android 14+'s foreground-
            // promotion bookkeeping (ForegroundServiceDidNotStartInTimeException
            // fires ~5 s later despite startForegroundCompat having run).
            // MainActivity's onResume re-check routes the user to
            // PermissionExplanationScreen on their next foreground; once
            // they re-grant the permission, MainActivity (Story 5.2)
            // restarts the service cleanly with a fresh window attempt.
            Log.w(TAG, "Cannot add overlay window — SYSTEM_ALERT_WINDOW denied; running degraded.", e)
            // Disposing the composition releases any LaunchedEffect /
            // DisposableEffect / animateFloatAsState observer that the
            // bubble's setContent {} attached. The replacement empty
            // ComposeView keeps detachOverlayView()'s isInitialized guard
            // happy without nullability gymnastics.
            //
            // ViewTree owners are re-applied (Story 1.7 review fix #13)
            // so a future story that re-attaches this field via setContent
            // does not crash with "ViewTreeLifecycleOwner not found" at
            // first composition. Today nothing else touches this view —
            // it's purely a placeholder — but the cost of re-applying is
            // three method calls and the safety is permanent.
            composeView.disposeComposition()
            composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@BubbleOverlayService)
                setViewTreeSavedStateRegistryOwner(this@BubbleOverlayService)
                setViewTreeViewModelStoreOwner(this@BubbleOverlayService)
            }
        }
    }

    private fun detachOverlayView() {
        // `overlayAttached` is the primary signal, but we also `isInitialized`-guard
        // both lateinit fields because onDestroy can run after a partial onCreate
        // (e.g. createNotificationChannel succeeded → startForeground succeeded →
        // any later step threw before `windowManager` or `composeView` got
        // assigned). We catch IllegalArgumentException because the system can
        // remove the overlay view independently if `SYSTEM_ALERT_WINDOW` is
        // revoked mid-session — `removeView` then throws "View not attached".
        if (overlayAttached && ::windowManager.isInitialized && ::composeView.isInitialized) {
            try {
                windowManager.removeView(composeView)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Overlay view was already removed by the system.", e)
            }
            overlayAttached = false
        }
    }

    /**
     * Fired on every touch-down (Story 1.7 review fix #5). The inactivity
     * timer is reset here so any gesture — short tap, long-press, drag,
     * near-miss tap — keeps the bubble opaque while the user is interacting
     * with it. Without this, the long-press release path (gesture handler's
     * `else -> Unit` branch) emits no callback and the fade timer keeps
     * counting down through a 1.5 s hold.
     */
    private fun onBubbleUserActivity() {
        bubbleStateMachine.onEvent(BubbleEvent.UserActivity)
    }

    /**
     * Story 1.10 — touch-down on the visible 56 dp bubble. Routes to
     * [BubbleEvent.LongPressStarted] which the reducer maps:
     *   - `Idle / Verdict → Pressing` (UX-DR14 `Idle × Long-press`,
     *     `Verdict × Long-press` re-trigger).
     *   - other states → no-op (defensive).
     */
    private fun onBubbleLongPressStart() {
        bubbleStateMachine.onEvent(BubbleEvent.LongPressStarted)
    }

    /**
     * Story 1.10 — finger released on the bubble before the 1 s
     * long-press deadline AND no drag occurred AND elapsed ≥ 200 ms.
     * Routes to [BubbleEvent.BackToIdle] only when the state machine
     * is currently in [BubbleState.Pressing] — guarding ensures we
     * don't accidentally cancel a Verdict from a halo tap.
     */
    private fun onBubblePressCancelled() {
        if (bubbleStateMachine.state.value != BubbleState.Pressing) return

        // Story 3.3 smoke 2026-05-12 — `onTap` and `onPressCancelled` are
        // two paths emitted by the gesture handler for the same human
        // gesture ("short press" — released before the 1 s long-press
        // deadline). The split is purely on elapsed time:
        //   - elapsed < 200 ms → `onTap`
        //   - 200 ms ≤ elapsed < 1000 ms → `onPressCancelled`
        // For a normal human tap on the visible bubble, elapsed often
        // exceeds 200 ms (finger-down dwell + recognition latency). So
        // the `tap-after-tappable` logic in `onBubbleTap` (Story 2.4
        // smoke fix, Story 3.3 patch DN1) ALSO needs to fire here —
        // otherwise PossibleInjection × Tap (and Verdict × Tap) silently
        // dismisses the bubble without opening the detail panel when the
        // user's finger lingers ≥ 200 ms. Snapshot non-null in
        // `Pressing` means the user was viewing a tappable state
        // (Verdict / PossibleInjection) before the touch-down — open the
        // panel for that record before dismissing.
        //
        // Story 4.3 — same Idle × Tap reasoning: a finger lingering
        // ≥ 200 ms on the idle bubble routes through onPressCancelled,
        // NOT onTap. Without `handleIdleBubbleTap` here, ~30–50 % of
        // human idle-taps would dismiss silently and never open
        // history. The helper is a no-op when sourceSnapshot is not
        // Idle (e.g. Offline / Timeout / DailyLimit / QuotaExhausted
        // sources stay silent).
        val tappableSnapshot = lastTappableRecordForTap
        val sourceSnapshot = lastNonPressingState
        if (tappableSnapshot != null) {
            bubbleStateMachine.onEvent(BubbleEvent.BackToIdle)
            launchDetailPanelActivity(tappableSnapshot.id)
        } else {
            bubbleStateMachine.onEvent(BubbleEvent.BackToIdle)
            handleIdleBubbleTap(
                sourceState = sourceSnapshot,
                onLaunchHistory = ::launchHistoryActivity,
            )
        }
    }

    /**
     * Bubble tap callback. Story 2.4 wires `Verdict × Tap` to "expand
     * detail panel" per UX-DR14 by launching [MainActivity] with the
     * panel-launch Intent ([buildDetailPanelIntent]); Story 4.3 wires
     * `Idle × Tap` to "open chronological history" via
     * [handleIdleBubbleTap] + [launchHistoryActivity].
     *
     * **State invariant** (story spec AC #4 + Critical Dev Note #1):
     * [BubbleEvent.BackToIdle] is the only event dispatched, and only to
     * undo the touch-down's synchronous `LongPressStarted → Pressing`
     * transition. After the dispatch the bubble visibly returns to
     * [BubbleState.Idle] (per [BubbleStateMachine] reducer); the
     * activity-side surface (Story 2.4 `AnchoredDetailPanel` or
     * Story 4.3 `HistoryScreen`) is the only post-tap UI artefact, and
     * does not call back into the service when dismissed.
     *
     * UserActivity is already emitted on touch-down via
     * [onBubbleUserActivity], so the fade-timer reset is taken care of
     * regardless of how the gesture ends.
     */
    private fun onBubbleTap() {
        val currentState = bubbleStateMachine.state.value
        val tappableSnapshot = lastTappableRecordForTap
        val sourceSnapshot = lastNonPressingState

        // Story 2.4 smoke 2026-05-11 (+ Story 3.3) — gesture handler
        // dispatches LongPressStarted on touch-down BEFORE onTap fires,
        // so when `onTap` runs the state has already transitioned
        // Verdict / FailureState.PossibleInjection → Pressing. To honour
        // AC #2 (tappable × Tap → panel), we consult the snapshot
        // maintained by the state observer. If we have a tappable
        // snapshot AND we're in Pressing (tap-after-tappable), the user
        // intent was clearly "open the panel". Dispatch BackToIdle to
        // undo the touch-down's Pressing transition (the bubble returns
        // to Idle as if no press happened) and launch the panel.
        if (currentState is BubbleState.Pressing && tappableSnapshot != null) {
            bubbleStateMachine.onEvent(BubbleEvent.BackToIdle)
            launchDetailPanelActivity(tappableSnapshot.id)
            return
        }

        // Code-review patch DN1 (Story 3.3) — touch-down on the bubble
        // dispatches LongPressStarted (Idle / Verdict / FailureState →
        // Pressing) BEFORE this tap callback fires. For a non-tappable
        // source (Idle / FailureState.Offline / Timeout / DailyLimit /
        // QuotaExhausted), `tappableSnapshot == null` so the branch
        // above is skipped and we land here in `Pressing`. Without an
        // explicit BackToIdle, the state machine stays in Pressing
        // indefinitely (LongPressStarted from Pressing is a reducer
        // no-op, so even a fresh touch-down cannot recover). Dispatching
        // BackToIdle restores the bubble to Idle — the user's tap
        // dismisses any failure flash early but recovers cleanly,
        // matching the spec's "tap is a no-op" intent on the visible
        // end-state.
        //
        // Story 4.3 — Idle × Tap → open history. The Idle source is
        // confirmed via the pre-touch-down snapshot
        // [lastNonPressingState]. Non-Idle non-tappable failure sources
        // (Offline / Timeout / DailyLimit / QuotaExhausted) fall
        // through to the BackToIdle no-op below — `handleIdleBubbleTap`
        // emits no callback when sourceState is not Idle, satisfying
        // AC #3 (FailureState × Tap → silent dismissal).
        if (currentState is BubbleState.Pressing) {
            bubbleStateMachine.onEvent(BubbleEvent.BackToIdle)
            handleIdleBubbleTap(
                sourceState = sourceSnapshot,
                onLaunchHistory = ::launchHistoryActivity,
            )
            return
        }

        // Standard path: if state is still a tappable variant (e.g.
        // theoretical race), route normally via the pure helper.
        handleTappableBubbleTap(
            state = currentState,
            onLaunchPanel = ::launchDetailPanelActivity,
        )
    }

    /**
     * Story 2.4 — Build and dispatch the panel-launch [Intent].
     *
     * Anchor X is computed from the bubble window's current top-left
     * (`params.x`) plus the halo offset and the half-bubble inset, so
     * the value is the bubble's VISIBLE-centre in raw window pixels.
     * If [params] is not yet initialised (defensive — the service
     * could in theory be torn down between `Verdict` emission and a
     * very fast tap), anchor X falls back to 0 and the activity-side
     * `computeEmergenceEdge` resolves to LEFT (harmless because V1
     * defaults `BuildConfig.USE_STANDARD_BOTTOM_SHEET = true` and the
     * panel emerges from BOTTOM regardless).
     *
     * `startActivity` is wrapped in a try / catch for
     * [ActivityNotFoundException] — extremely unlikely in practice
     * (the manifest declares `MainActivity` with `exported="true"` +
     * MAIN/LAUNCHER filter) but free defensive coverage.
     */
    private fun launchDetailPanelActivity(sessionId: String) {
        val anchorXPx = if (::params.isInitialized) {
            visibleBubbleCenterXPx()
        } else {
            0
        }
        val intent = buildDetailPanelIntent(this, sessionId, anchorXPx)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Cannot launch MainActivity for detail panel — degraded.", e)
        }
    }

    /**
     * Story 4.3 — Build and dispatch the history-open [Intent].
     *
     * `startActivity` is wrapped in a try / catch for
     * [ActivityNotFoundException] — extremely unlikely in practice
     * (the manifest declares `MainActivity` with `exported="true"` +
     * MAIN/LAUNCHER filter) but free defensive coverage matching the
     * Story 2.4 sibling [launchDetailPanelActivity].
     *
     * No bubble-anchor extra is computed — history-open does not need
     * one (the panel-anchor math in [visibleBubbleCenterXPx] is for the
     * detail panel only). The entire payload is the action constant.
     */
    private fun launchHistoryActivity() {
        val intent = buildOpenHistoryIntent(this)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Cannot launch MainActivity for history — degraded.", e)
        }
    }

    /**
     * Story 2.4 — Returns the bubble's VISIBLE-centre X coordinate in
     * raw window pixels. Extracted from the per-dp → px math that also
     * appears in [attachOverlayView] and [onBubbleDragEnd] so the
     * panel-anchor computation stays in lock-step with the snap
     * arithmetic.
     *
     * P9 — Clamped to `[0, screenWidthPx]` to honour
     * [com.verisphere.app.ui.detail.computeEmergenceEdge]'s caller
     * contract ("Clamping is the caller's responsibility"). `params.x`
     * is normally clamped at rest by the snap arithmetic, but a
     * tap-during-drag race could theoretically leave a transient
     * off-screen X; this guard makes the off-screen path impossible
     * at the API boundary rather than relying on upstream invariants.
     */
    private fun visibleBubbleCenterXPx(): Int {
        val density = resources.displayMetrics.density
        val bubbleSizePx = (BUBBLE_DIAMETER_DP * density).roundToInt()
        val haloSizePx = (BUBBLE_HALO_DIAMETER_DP * density).roundToInt()
        val haloOffsetPx = (haloSizePx - bubbleSizePx) / 2
        val screenWidthPx = currentWindowSizePx().first
        return (params.x + haloOffsetPx + bubbleSizePx / 2).coerceIn(0, screenWidthPx)
    }

    /**
     * Halo tap-near-miss callback. UserActivity is already emitted on
     * touch-down by [onBubbleUserActivity]. Story 1.10 + Story 3.3:
     * when the state is [BubbleState.Verdict] OR any
     * [BubbleState.FailureState], a halo tap is a "next user gesture"
     * signal that returns the bubble to Idle (UX-DR6 line 679) — the
     * tooltip text was already faded by the timer; the colour resets
     * here.
     */
    private fun onBubbleTapNearMiss() {
        val state = bubbleStateMachine.state.value
        if (state is BubbleState.Verdict || state is BubbleState.FailureState) {
            bubbleStateMachine.onEvent(BubbleEvent.BackToIdle)
        }
    }

    /**
     * Story 1.8 — long-press handler. Dispatches the forward-compat
     * [BubbleEvent.LongPressCompleted] (no state change in 1.8; Story
     * 1.10 will introduce the `Pressing` / `Capturing` transition) and
     * either starts the capture pipeline directly (when the
     * [VeriSphereAccessibilityService] is bound) or routes the user back
     * to [MainActivity] so they can activate the accessibility service
     * in Settings.
     *
     * Re-entrancy: if a previous capture job is still running we drop
     * the new long-press silently (Story 1.10 will introduce a
     * `Thinking` state to make this visible).
     */
    private fun onBubbleLongPress() {
        bubbleStateMachine.onEvent(BubbleEvent.LongPressCompleted)

        if (captureJob?.isActive == true) {
            Log.d(TAG, "long-press ignored — previous capture still running")
            return
        }

        if (VeriSphereAccessibilityService.instance == null) {
            Log.d(TAG, "long-press without accessibility service — routing to MainActivity")
            launchAccessibilityExplanationActivity()
        } else {
            launchCaptureJob()
        }
    }

    private fun launchAccessibilityExplanationActivity() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_REQUEST_ACCESSIBILITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }

    private fun launchCaptureJob() {
        captureJob = serviceScope.launch {
            runCaptureAndDispatch()
        }
    }

    /**
     * Run the capture pipeline. Story 1.8.5 simplification: the service
     * stays at FGS type `specialUse` for its entire lifetime because
     * the capture API (AccessibilityService.takeScreenshot, D5.13) does
     * not require `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` like the
     * deprecated MediaProjection path did (D5.3 deprecated).
     *
     * Story 1.10 — persist BEFORE dispatching the state event (Critical
     * Dev Note #6 in story file): observers of [bubbleStateMachine.state]
     * (the bubble overlay's Compose recomposition AND the future Story 4
     * history view via `historyRepository.observe()`) must never see a
     * [BubbleState.Verdict] holding a record that has not been
     * persisted. A process-kill in the gap would lose the verdict from
     * history while the user already saw it (FR15 / FR17 violation).
     */
    private suspend fun runCaptureAndDispatch() {
        val outcome = capturePipeline.runCapture()
        Log.d(TAG, "capture outcome: ${outcome.logLabel()}")
        if (outcome is VerificationOutcome.Verdict) {
            container.historyRepository.append(outcome.record)
        }
        bubbleStateMachine.onEvent(BubbleEvent.VerificationOutcomeReceived(outcome))
    }

    private fun VerificationOutcome.logLabel(): String = when (this) {
        is VerificationOutcome.Verdict -> "Verdict(id=${record.id})"
        is VerificationOutcome.Failure -> this::class.simpleName ?: "Failure"
    }

    /**
     * Story 1.10 — full-screen tooltip overlay laid out via a custom
     * Compose [androidx.compose.ui.layout.Layout] that places the
     * [FlashTooltip] adjacent to the bubble's current screen position.
     *
     * Lives at the top of the file (alongside the `setContent {}`-style
     * composables) because it directly references service-side state
     * (`bubblePositionFlow`, `BUBBLE_DIAMETER_DP`). Logic is small
     * enough to inline rather than promote to its own file.
     */
    @androidx.compose.runtime.Composable
    private fun TooltipOverlayContent(
        verdict: BubbleState.Verdict,
        bubblePosition: Point,
        density: Float,
        onTooltipClick: () -> Unit,
        onTooltipLayout: (sizePx: IntSize, computedXPx: Int, computedYPx: Int) -> Unit,
    ) {
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        // Story 7.4 P4 (code-review patch) — read screen dims from the
        // SAME source as the bubble-position math (currentWindowSizePx →
        // WindowManager.currentWindowMetrics.bounds, INCLUDES system
        // bars). Pre-patch used LocalConfiguration.screenWidthDp /
        // heightDp which EXCLUDES bars, causing tooltips to be
        // artificially pulled UP from bubbles in the bottom system-bar
        // region. LocalConfiguration is still read to trigger
        // recomposition on rotation; remember(configuration) invalidates
        // the cached pair in lockstep.
        val (screenWidthPx, screenHeightPx) = remember(configuration) { currentWindowSizePx() }
        val bubbleSizePx = (BUBBLE_DIAMETER_DP * density).roundToInt()
        val haloSizePx = (BUBBLE_HALO_DIAMETER_DP * density).roundToInt()
        val haloOffsetPx = (haloSizePx - bubbleSizePx) / 2
        val gapPx = (TOOLTIP_GAP_DP * density).roundToInt()

        val visibleBubbleX = bubblePosition.x + haloOffsetPx
        val visibleBubbleY = bubblePosition.y + haloOffsetPx
        val direction = if (visibleBubbleX + bubbleSizePx / 2 < screenWidthPx / 2) {
            PointerDirection.LEFT
        } else {
            PointerDirection.RIGHT
        }

        // Story 7.4 MS4 — WRAP_CONTENT-sized tooltip window. The
        // FlashTooltip's own `widthIn(max = 75% screen)` caps width per
        // UX-DR4. `onSizeChanged` reports the measured pixel size; the
        // LaunchedEffect recomputes window x/y on every (direction ||
        // bubblePosition || tooltipSize) change and forwards to the
        // service-side `updateTooltipWindowLayout` callback. Clamping
        // uses screen dimensions (the window is no longer the full
        // screen, so we can't use Compose `constraints`).
        var tooltipSize by remember { mutableStateOf(IntSize.Zero) }

        LaunchedEffect(direction, bubblePosition, tooltipSize) {
            if (tooltipSize == IntSize.Zero) return@LaunchedEffect
            val tooltipX = if (direction == PointerDirection.LEFT) {
                visibleBubbleX + bubbleSizePx + gapPx
            } else {
                visibleBubbleX - tooltipSize.width - gapPx
            }
            val tooltipY = visibleBubbleY + bubbleSizePx / 2 - tooltipSize.height / 2
            val maxX = (screenWidthPx - tooltipSize.width).coerceAtLeast(0)
            val maxY = (screenHeightPx - tooltipSize.height).coerceAtLeast(0)
            onTooltipLayout(tooltipSize, tooltipX.coerceIn(0, maxX), tooltipY.coerceIn(0, maxY))
        }

        FlashTooltip(
            verdictLabel = verdict.record.verdictLabel,
            headline = verdict.record.headline,
            textFaded = verdict.tooltipFaded,
            pointerDirection = direction,
            onClick = onTooltipClick,
            modifier = Modifier.onSizeChanged { intSize -> tooltipSize = intSize },
        )
    }

    /**
     * Story 3.3 — sibling Composable to [TooltipOverlayContent] for the
     * failure-flash path. Identical layout / placement math; renders
     * [com.verisphere.app.bubble.ui.FailureFlashTooltip] instead of
     * [com.verisphere.app.bubble.ui.FlashTooltip] and reads the
     * variant's `tooltipFaded` flag directly off the FailureState
     * sub-type (every FailureState data class exposes the field).
     */
    @androidx.compose.runtime.Composable
    private fun FailureTooltipOverlayContent(
        failure: BubbleState.FailureState,
        bubblePosition: Point,
        density: Float,
        onTooltipClick: () -> Unit,
        onTooltipLayout: (sizePx: IntSize, computedXPx: Int, computedYPx: Int) -> Unit,
    ) {
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        // Story 7.4 P4 (code-review patch) — see TooltipOverlayContent
        // for full rationale; same source-mismatch fix.
        val (screenWidthPx, screenHeightPx) = remember(configuration) { currentWindowSizePx() }
        val bubbleSizePx = (BUBBLE_DIAMETER_DP * density).roundToInt()
        val haloSizePx = (BUBBLE_HALO_DIAMETER_DP * density).roundToInt()
        val haloOffsetPx = (haloSizePx - bubbleSizePx) / 2
        val gapPx = (TOOLTIP_GAP_DP * density).roundToInt()

        val visibleBubbleX = bubblePosition.x + haloOffsetPx
        val visibleBubbleY = bubblePosition.y + haloOffsetPx
        val direction = if (visibleBubbleX + bubbleSizePx / 2 < screenWidthPx / 2) {
            PointerDirection.LEFT
        } else {
            PointerDirection.RIGHT
        }

        val textFaded = when (failure) {
            is BubbleState.FailureState.Offline -> failure.tooltipFaded
            is BubbleState.FailureState.Timeout -> failure.tooltipFaded
            is BubbleState.FailureState.DailyLimit -> failure.tooltipFaded
            is BubbleState.FailureState.QuotaExhausted -> failure.tooltipFaded
            is BubbleState.FailureState.PossibleInjection -> failure.tooltipFaded
        }

        // Story 7.4 MS4 — same WRAP_CONTENT-sized window pattern as
        // TooltipOverlayContent. See its doc for rationale.
        var tooltipSize by remember { mutableStateOf(IntSize.Zero) }

        LaunchedEffect(direction, bubblePosition, tooltipSize) {
            if (tooltipSize == IntSize.Zero) return@LaunchedEffect
            val tooltipX = if (direction == PointerDirection.LEFT) {
                visibleBubbleX + bubbleSizePx + gapPx
            } else {
                visibleBubbleX - tooltipSize.width - gapPx
            }
            val tooltipY = visibleBubbleY + bubbleSizePx / 2 - tooltipSize.height / 2
            val maxX = (screenWidthPx - tooltipSize.width).coerceAtLeast(0)
            val maxY = (screenHeightPx - tooltipSize.height).coerceAtLeast(0)
            onTooltipLayout(tooltipSize, tooltipX.coerceIn(0, maxX), tooltipY.coerceIn(0, maxY))
        }

        FailureFlashTooltip(
            failure = failure,
            textFaded = textFaded,
            pointerDirection = direction,
            onClick = onTooltipClick,
            modifier = Modifier.onSizeChanged { intSize -> tooltipSize = intSize },
        )
    }

    private fun onBubbleDragDelta(dxPx: Float, dyPx: Float) {
        // A new drag pre-empts any in-flight edge-snap from a previous
        // gesture so the bubble stays under the user's finger instead
        // of continuing the previous spring trajectory.
        snapJob?.cancel()
        snapJob = null

        if (!overlayAttached || !::params.isInitialized) return

        val (screenWidthPx, screenHeightPx) = currentWindowSizePx()
        val density = resources.displayMetrics.density
        val bubbleSizePx = (BUBBLE_DIAMETER_DP * density).roundToInt()
        val haloSizePx = (BUBBLE_HALO_DIAMETER_DP * density).roundToInt()
        val haloOffsetPx = (haloSizePx - bubbleSizePx) / 2

        // During drag we permit the bubble to touch the screen edge
        // (no inset clamping); the snap on ACTION_UP pulls it back to
        // the 16 dp inset.
        val minX = -haloOffsetPx
        val maxX = screenWidthPx - bubbleSizePx - haloOffsetPx
        val minY = -haloOffsetPx
        val maxY = screenHeightPx - bubbleSizePx - haloOffsetPx

        // Drag-rounding accumulator (Story 1.7 review fix #4): per-frame
        // dxPx/dyPx are floats; rounding each independently truncates
        // sub-pixel motion (≈ 0.4 px/frame on slow drags rounds to 0
        // every frame → bubble stays still). Add the float into a pending
        // accumulator, take the integer portion, keep the fractional
        // remainder for the next frame.
        pendingDx += dxPx
        pendingDy += dyPx
        val intDx = pendingDx.toInt()
        val intDy = pendingDy.toInt()
        pendingDx -= intDx
        pendingDy -= intDy

        params.x = (params.x + intDx).coerceIn(minX, maxX)
        params.y = (params.y + intDy).coerceIn(minY, maxY)
        safeUpdateViewLayout()
        // Story 1.10 — keep the tooltip overlay in lock-step with the
        // bubble during drag (UX-DR14 row "Verdict × Drag = Reposition").
        publishBubblePosition()
    }

    private fun onBubbleDragEnd() {
        // Reset drag-rounding accumulators so the next drag starts from
        // a clean fractional remainder (Story 1.7 review fix #4).
        pendingDx = 0f
        pendingDy = 0f

        if (!overlayAttached || !::params.isInitialized) return

        val (screenWidthPx, screenHeightPx) = currentWindowSizePx()
        val density = resources.displayMetrics.density
        val bubbleSizePx = (BUBBLE_DIAMETER_DP * density).roundToInt()
        val haloSizePx = (BUBBLE_HALO_DIAMETER_DP * density).roundToInt()
        val haloOffsetPx = (haloSizePx - bubbleSizePx) / 2
        val insetPx = (EDGE_INSET_DP * density).roundToInt()

        val visibleBubbleCentreX = params.x + haloSizePx / 2
        val targetX = if (visibleBubbleCentreX < screenWidthPx / 2) {
            insetPx - haloOffsetPx
        } else {
            screenWidthPx - bubbleSizePx - insetPx - haloOffsetPx
        }

        // Vertical clamp to keep the visible bubble within the inset
        // band — no vertical snap, only horizontal (UX-DR5).
        val minY = insetPx - haloOffsetPx
        val maxY = screenHeightPx - bubbleSizePx - insetPx - haloOffsetPx
        params.y = params.y.coerceIn(minY, maxY)

        snapJob = serviceScope.launch {
            // try/finally ensures the latest params position is persisted
            // even when a new drag pre-empts the snap mid-animation
            // (Story 1.7 review fix #7). Without this, rapid drag-snap
            // sequences lose the user's most recent position because
            // animateSnapToX throws CancellationException and persist
            // never runs.
            try {
                // AndroidUiDispatcher.Main provides a MonotonicFrameClock
                // driven by Choreographer — required by Compose's
                // Animatable.animateTo. serviceScope's Dispatchers.Main.immediate
                // does NOT carry a MonotonicFrameClock (Activity-side Compose
                // gets it for free via the Recomposer; a non-Activity Service
                // host must opt in explicitly). Without this withContext,
                // every drag-end crashes with "A MonotonicFrameClock is not
                // available in this CoroutineContext" — surfaced for the
                // first time during Story 1.8's manual smoke test on the AVD
                // (Story 1.7's connectedDebugAndroidTest gate had not been
                // run on real hardware).
                withContext(AndroidUiDispatcher.Main) {
                    animateSnapToX(targetX.toFloat())
                }
            } finally {
                persistPosition()
            }
        }
    }

    private suspend fun animateSnapToX(targetX: Float) {
        val animatable = Animatable(initialValue = params.x.toFloat())
        animatable.animateTo(
            targetValue = targetX,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ) {
            if (overlayAttached) {
                params.x = value.roundToInt()
                safeUpdateViewLayout()
                publishBubblePosition()    // Story 1.10 — sync tooltip during snap animation
            }
        }
    }

    private fun safeUpdateViewLayout() {
        if (!::windowManager.isInitialized || !::composeView.isInitialized) return
        try {
            windowManager.updateViewLayout(composeView, params)
        } catch (e: IllegalArgumentException) {
            // Overlay view was removed by the system mid-drag (e.g.
            // SYSTEM_ALERT_WINDOW revoked). Drop the update silently —
            // MainActivity's onResume re-check will route recovery.
            Log.w(TAG, "updateViewLayout: view not attached", e)
        }
    }

    private fun persistPosition() {
        if (!::container.isInitialized) return
        container.secureStorage.writeLong(KEY_BUBBLE_X, params.x.toLong())
        container.secureStorage.writeLong(KEY_BUBBLE_Y, params.y.toLong())
        publishBubblePosition()
    }

    /**
     * Story 1.10 — keeps [bubblePositionFlow] in lock-step with
     * [params].x / [params].y so the [tooltipView]'s Compose tree can
     * observe live bubble movement (drag during Verdict state slides
     * the tooltip alongside the bubble per UX-DR14 row "Verdict × Drag
     * = Reposition").
     */
    private fun publishBubblePosition() {
        if (!::params.isInitialized) return
        bubblePositionFlow.value = Point(params.x, params.y)
    }

    /**
     * Story 1.10 — observe state transitions to/from
     * [BubbleState.Verdict] and create/destroy the FlashTooltip
     * [WindowManager] view accordingly. Kept in [serviceScope] so the
     * coroutine dies with the service.
     *
     * The observer reads `state.collectAsState()` content INSIDE the
     * tooltip view's `setContent {}` block, so Compose handles all
     * intermediate updates (e.g. Verdict.tooltipFaded flipping after
     * the 5–8 s timer) without us re-creating the view.
     */
    private fun startTooltipObserver() {
        tooltipObserverJob?.cancel()
        tooltipObserverJob = serviceScope.launch {
            // Code-review patch P10 — guard the collect body. An
            // unexpected exception inside attach/detach (Compose init
            // failure, hostile OEM WindowManager throws) would
            // otherwise kill the observer coroutine; subsequent verdicts
            // would never re-show the tooltip. Catch + log keeps the
            // service alive in degraded mode (bubble visible, tooltip
            // missing) — better than a silently-dead observer.
            try {
                bubbleStateMachine.state.collect { state ->
                    // Story 2.4 smoke fix (+ Story 3.3): snapshot the
                    // record when we enter a tappable state (Verdict OR
                    // FailureState.PossibleInjection); clear on Idle.
                    // Read by `onBubbleTap` to launch the panel even
                    // when state has already transitioned to Pressing
                    // on touch-down (gesture handler dispatches
                    // LongPressStarted before tap-vs-long-press is
                    // resolved). Other FailureState variants do NOT set
                    // the snapshot — their bubble tap is a no-op
                    // (no SessionRecord to expand).
                    // Story 4.3 — also maintain `lastNonPressingState` in
                    // each non-Pressing arm. The Pressing arm preserves
                    // BOTH snapshots through the synchronous touch-down
                    // dispatch. Same race-window discipline as
                    // `lastTappableRecordForTap`.
                    when (state) {
                        is BubbleState.Verdict -> {
                            lastTappableRecordForTap = state.record
                            lastNonPressingState = state
                        }
                        is BubbleState.FailureState.PossibleInjection -> {
                            lastTappableRecordForTap = state.record
                            lastNonPressingState = state
                        }
                        // Code-review patch P1 (Story 3.3) — clear the
                        // snapshot when entering a non-tappable, non-
                        // transient state (Idle / Capturing / Thinking /
                        // non-PossibleInjection FailureState). This closes
                        // the `PossibleInjection(A) → re-trigger →
                        // Failure.Offline` stale-snapshot gap where a
                        // subsequent tap would have opened the panel for
                        // the obsolete record.
                        //
                        // P1 followup (smoke) — `BubbleState.Pressing` is
                        // INTENTIONALLY excluded from the clear path: it
                        // is the transient touch-down phase dispatched
                        // synchronously by the gesture handler BEFORE the
                        // tap-vs-long-press deadline resolves. The
                        // `onBubbleTap` snapshot-replay path (Story 2.4
                        // smoke fix) reads `lastTappableRecordForTap`
                        // during `Pressing` to honour
                        // `Verdict × Tap → panel` /
                        // `PossibleInjection × Tap → panel` (AC #4).
                        // Story 4.3 reads `lastNonPressingState` during
                        // `Pressing` for the symmetric `Idle × Tap →
                        // open history` route. Clearing either snapshot
                        // on Pressing would break BOTH routes.
                        is BubbleState.Idle -> {
                            lastTappableRecordForTap = null
                            lastNonPressingState = state
                        }
                        BubbleState.Capturing,
                        BubbleState.Thinking,
                        is BubbleState.FailureState -> {
                            lastTappableRecordForTap = null
                            lastNonPressingState = state
                        }
                        BubbleState.Pressing -> Unit
                    }
                    // Story 3.3 — tooltip attach/detach: mount on Verdict
                    // OR any FailureState; detach on any other state.
                    val isTooltipState = state is BubbleState.Verdict || state is BubbleState.FailureState
                    val tooltipMounted = tooltipView != null
                    if (isTooltipState && !tooltipMounted) {
                        attachTooltipView()
                    } else if (!isTooltipState && tooltipMounted) {
                        detachTooltipView()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Re-throw cancellation so structured concurrency works.
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.e(TAG, "tooltip observer crashed — degraded mode (no tooltip until next service start)", e)
            }
        }
    }

    /**
     * Story 7.4 MS4 — post-composition window-position update for the
     * permanent tooltip touch-routing fix. Called by [TooltipOverlayContent]
     * + [FailureTooltipOverlayContent] via the `onTooltipLayout` callback
     * whenever the tooltip measures (post-`onSizeChanged`) or the bubble
     * position changes (drag, edge-snap). The [Handler.post] envelope
     * defers to the next main-looper tick to avoid mid-layout
     * `updateViewLayout` races; the typed catches mirror the existing
     * degraded-mode pattern in [attachTooltipView].
     */
    private fun updateTooltipWindowLayout(sizePx: IntSize, computedXPx: Int, computedYPx: Int) {
        mainHandler.post {
            val view = tooltipView ?: return@post
            val params = tooltipParams ?: return@post
            if (!::windowManager.isInitialized) return@post
            params.width = sizePx.width
            params.height = sizePx.height
            params.x = computedXPx
            params.y = computedYPx
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Tooltip updateViewLayout failed — view detached.", e)
            } catch (e: WindowManager.BadTokenException) {
                Log.w(TAG, "Tooltip updateViewLayout failed — bad token.", e)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w(TAG, "Tooltip updateViewLayout failed — degraded mode.", e)
            }
        }
    }

    private fun attachTooltipView() {
        if (tooltipView != null) return
        if (!::windowManager.isInitialized) return

        // Make sure the published bubble position reflects the current
        // params before the Compose tree first reads it.
        publishBubblePosition()

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BubbleOverlayService)
            setViewTreeSavedStateRegistryOwner(this@BubbleOverlayService)
            setViewTreeViewModelStoreOwner(this@BubbleOverlayService)
            setContent {
                VeriSphereTheme {
                    val state by bubbleStateMachine.state.collectAsState()
                    val bubblePosition by bubblePositionFlow.collectAsState()
                    when (val s = state) {
                        is BubbleState.Verdict -> TooltipOverlayContent(
                            verdict = s,
                            bubblePosition = bubblePosition,
                            density = resources.displayMetrics.density,
                            onTooltipClick = { launchDetailPanelActivity(s.record.id) },
                            onTooltipLayout = ::updateTooltipWindowLayout,
                        )
                        is BubbleState.FailureState -> FailureTooltipOverlayContent(
                            failure = s,
                            bubblePosition = bubblePosition,
                            density = resources.displayMetrics.density,
                            onTooltipClick = {
                                if (s is BubbleState.FailureState.PossibleInjection) {
                                    launchDetailPanelActivity(s.record.id)
                                }
                                // Other FailureState variants — onClick is
                                // gated to no-op at the Composable level
                                // (FailureFlashTooltip's clickable Surface
                                // is wrapped only for PossibleInjection per
                                // Story 3.3 patch P3). Window-flag posture:
                                // FLAG_NOT_TOUCH_MODAL forwards taps OUTSIDE
                                // the WRAP_CONTENT window bounds to the
                                // source app, but taps INSIDE the bounds on
                                // a non-clickable Surface are silently
                                // consumed (regression vs pre-MS4 full-
                                // screen FLAG_NOT_TOUCHABLE passthrough).
                                // Tracked as deferred-work D5 from Story
                                // 7.4 code review — runtime fix (dynamic
                                // flag swap by state) deferred to Story
                                // 7.5 / V2.
                            },
                            onTooltipLayout = ::updateTooltipWindowLayout,
                        )
                        else -> Unit
                    }
                }
            }
        }

        // Story 7.4 MS4 — permanent option-(a) fix per Hernat decision
        // (deferred-work L221-228 + L314-318). The tooltip overlay
        // window is now WRAP_CONTENT-sized at the FlashTooltip's
        // measured bounding box; position via `params.x/y` is updated
        // post-composition via `Modifier.onSizeChanged` + `LaunchedEffect`
        // → `updateTooltipWindowLayout` → `windowManager.updateViewLayout`
        // (posted to the main looper to avoid mid-layout races). With
        // WRAP_CONTENT, FLAG_NOT_TOUCH_MODAL (Story 1.10 baseline
        // restored) properly forwards outside-bounds taps to the source
        // app — taps INSIDE the tooltip Surface reach FlashTooltip's
        // clickable Surface and trigger the detail panel. The initial
        // `x = 0, y = 0` is overwritten ~16 ms after `addView` when the
        // first `updateTooltipWindowLayout` call fires; the one-frame
        // visual lag is invisible under the existing 300 ms fade-in
        // (FlashTooltip TEXT_FADE_MS). Closes Story 2.4 AC #3 properly
        // (taps on tooltip → detail panel) + closes deferred-work DN1
        // L312-318.
        val tParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Story 7.4 P2 (code-review patch) — start off-screen so the
            // first frame (before updateTooltipWindowLayout fires
            // post-onSizeChanged, ~16 ms after addView) doesn't briefly
            // paint at (0, 0). animateFloatAsState's default initialValue
            // == first targetValue, so text appears at full opacity
            // instantly; the brief flash at top-left would otherwise be
            // visible. updateTooltipWindowLayout overwrites x/y at the
            // first onSizeChanged callback, snapping the tooltip into
            // place. FLAG_LAYOUT_NO_LIMITS permits the negative offset.
            x = INITIAL_OFF_SCREEN_OFFSET_PX
            y = INITIAL_OFF_SCREEN_OFFSET_PX
        }

        try {
            windowManager.addView(view, tParams)
            tooltipView = view
            tooltipParams = tParams
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Cannot add tooltip overlay window — overlay permission likely revoked.", e)
            view.disposeComposition()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Code-review patch P9 — catch beyond BadTokenException.
            // Hostile OEMs / racy permission revocations can throw
            // IllegalStateException ("view already attached"),
            // NullPointerException (window manager service unbound),
            // or RuntimeException. Match the bubble window's
            // degraded-mode pattern at attachOverlayView's catch block
            // (lines ~409-446) — log, dispose composition, leave the
            // service alive without the tooltip.
            Log.w(TAG, "Tooltip overlay attach failed — degraded mode.", e)
            view.disposeComposition()
        }
    }

    private fun detachTooltipView() {
        val view = tooltipView ?: return
        if (::windowManager.isInitialized) {
            try {
                windowManager.removeView(view)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Tooltip view was already removed by the system.", e)
            }
        }
        view.disposeComposition()
        tooltipView = null
        tooltipParams = null
    }

    /**
     * Returns the current display size in pixels.
     *
     * Uses [WindowManager.getCurrentWindowMetrics] on API 30+ (the
     * documented modern path) and falls back to
     * [android.view.Display.getRealMetrics] on API 26–29 — `minSdk = 26`
     * so we cannot drop the legacy branch.
     */
    @Suppress("DEPRECATION")
    private fun currentWindowSizePx(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private companion object {
        // Story 1.8.5 (Sprint Change 2026-05-07): the public Intent
        // action / extra constants from Story 1.8 (ACTION_REQUEST_TOKEN,
        // ACTION_DELIVER_TOKEN, EXTRA_RESULT_CODE, EXTRA_RESULT_DATA)
        // are deleted — the MediaProjection Intent dance is gone.
        // companion object reverted to private since no external caller
        // needs its constants.

        private val TAG = tag("BubbleOverlayService")
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "vs_bubble_channel"

        // SecureStorage namespace is shared across V1 features per D1.4 —
        // never reuse these literals for any other Long.
        private const val KEY_BUBBLE_X = "bubble_position_x"
        private const val KEY_BUBBLE_Y = "bubble_position_y"

        // Materialised in pixels at the service layer because
        // WindowManager works in raw pixels, not Compose dp. Kept aligned
        // with the composable's matching `BUBBLE_DIAMETER_DP` / halo /
        // VSSpacing.space16 — bumping any of them requires updating both
        // surfaces.
        private const val BUBBLE_DIAMETER_DP = 56f
        private const val BUBBLE_HALO_DIAMETER_DP = 104f
        private const val EDGE_INSET_DP = 16f

        // Story 1.10 — gap between the bubble's visible edge and the
        // FlashTooltip pointer. UX-DR4 8 dp spacing token.
        private const val TOOLTIP_GAP_DP = 8f

        // Story 7.4 P2 (code-review patch) — initial off-screen x/y for
        // the tooltip overlay window so the first frame (before
        // updateTooltipWindowLayout fires) doesn't briefly paint at
        // (0, 0). Any large negative offset works because the window
        // has FLAG_LAYOUT_NO_LIMITS; -100,000 px is well beyond any
        // realistic display dimension.
        private const val INITIAL_OFF_SCREEN_OFFSET_PX = -100_000
    }
}
