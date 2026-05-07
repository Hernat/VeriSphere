package com.verisphere.app.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.verisphere.app.capture.CapturePipeline
import com.verisphere.app.gemini.VerificationOutcome
import com.verisphere.app.ui.theme.VeriSphereTheme
import com.verisphere.app.util.tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        bubbleStateMachine = BubbleStateMachine(coroutineScope = serviceScope)

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
        )

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(WindowManager::class.java)
        attachOverlayView()

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
                    BubbleOverlay(
                        state = state,
                        onUserActivity = ::onBubbleUserActivity,
                        onTap = ::onBubbleTap,
                        onTapNearMiss = ::onBubbleTapNearMiss,
                        onLongPress = ::onBubbleLongPress,
                        onDragDelta = ::onBubbleDragDelta,
                        onDragEnd = ::onBubbleDragEnd,
                    )
                }
            }
        }

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
     * Bubble tap callback — Story 1.7 leaves this a true no-op. Story 4.3
     * wires `Idle × Tap` to "open chronological history" per UX-DR14 — DO
     * NOT add navigation logic here in Story 1.7's scope. UserActivity is
     * already emitted on touch-down via [onBubbleUserActivity], so the
     * fade-timer reset is taken care of regardless of how the gesture ends.
     */
    @Suppress("EmptyFunctionBlock")
    private fun onBubbleTap() {
        // Intentionally empty — see KDoc.
    }

    /**
     * Halo tap-near-miss callback. UserActivity is already emitted on
     * touch-down by [onBubbleUserActivity]; this stays as a distinct
     * surface for future stories that may treat near-miss specifically
     * (e.g., a different haptic pattern).
     */
    @Suppress("EmptyFunctionBlock")
    private fun onBubbleTapNearMiss() {
        // Intentionally empty — see KDoc.
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
     * Run the capture pipeline. Story 1.8.5 simplification: no more
     * runtime FGS-type promote/demote dance — the service stays at
     * `specialUse` for its entire lifetime because the capture API
     * (AccessibilityService.takeScreenshot, D5.13) does not require
     * `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` like the deprecated
     * MediaProjection path did (D5.3 deprecated). Story 1.10 will
     * dispatch `BubbleEvent.VerificationOutcomeReceived(outcome)` so
     * the bubble visually reflects the result.
     */
    private suspend fun runCaptureAndDispatch() {
        val outcome = capturePipeline.runCapture()
        Log.d(TAG, "capture outcome: ${outcome.logLabel()}")
        // Story 1.10 will route the outcome into the state machine.
    }

    private fun VerificationOutcome.logLabel(): String = when (this) {
        is VerificationOutcome.Verdict -> "Verdict(id=${record.id})"
        is VerificationOutcome.Failure -> this::class.simpleName ?: "Failure"
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
    }
}
