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
import android.util.Log
import android.view.WindowManager
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
import com.verisphere.app.R
import com.verisphere.app.util.tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service hosting the persistent bubble overlay (architecture:
 * AR19, AR20, D4.10, D5.2; PRD: FR1, NFR15).
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
 * Story 1.6 ships the foundation only — an empty `ComposeView`
 * placeholder. Story 1.7 fills `setContent { }` with `BubbleOverlay`.
 * Story 1.8 adds the `MEDIA_PROJECTION` token holder and dynamic FGS
 * type switching (D5.2).
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
    private var overlayAttached: Boolean = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = _viewModelStore

    override fun onCreate() {
        super.onCreate()
        // Order rationale:
        //   1. performAttach()                  — register Recreator observer.
        //   2. performRestore(null)             — sets isRestored = true.
        //      (If ON_CREATE is dispatched before performRestore, the
        //       Recreator throws IllegalStateException; see savedstate.Recreator.)
        //   3. createNotificationChannel + startForegroundCompat — RUN FIRST
        //      so the FGS deadline contract is satisfied before any other
        //      step that could throw. Without this, an exception from
        //      handleLifecycleEvent / getSystemService / addView fires
        //      ForegroundServiceDidNotStartInTimeException 5 s later
        //      regardless of the original failure.
        //   4. handleLifecycleEvent(ON_CREATE)  — fires Recreator observer,
        //      which now safely consumes the restored state.
        //   5. WindowManager init + addView     — best-effort; failures
        //      here are caught (BadTokenException → degraded mode) or
        //      propagate cleanly without the framework's extra deadline crash.
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)

        createNotificationChannel()
        startForegroundCompat()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(WindowManager::class.java)
        attachOverlayView()

        Log.d(TAG, "Service created; overlay attached")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        // START_STICKY: Android auto-restarts the service after process
        // death (NFR15). The intent is null on restart — that is fine,
        // the bubble has no per-start parameters in V1.
        return START_STICKY
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
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
        composeView = ComposeView(this).apply {
            // ViewTree owners MUST be set before setContent {}, otherwise
            // Compose throws "ViewTreeLifecycleOwner not found" at first
            // composition.
            setViewTreeLifecycleOwner(this@BubbleOverlayService)
            setViewTreeSavedStateRegistryOwner(this@BubbleOverlayService)
            setViewTreeViewModelStoreOwner(this@BubbleOverlayService)
            setContent {
                // Story 1.7 fills this with `BubbleOverlay`. Story 1.6
                // ships the foundation only — empty placeholder.
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Flags rationale (D4.10):
            //   FLAG_NOT_FOCUSABLE      — touches outside the bubble
            //                             reach the underlying app.
            //   FLAG_LAYOUT_NO_LIMITS   — overlay can extend past screen
            //                             edges (Story 1.7 drag).
            //   FLAG_NOT_TOUCH_MODAL    — touches outside our window are
            //                             not intercepted by us.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )

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

    private companion object {
        private val TAG = tag("BubbleOverlayService")
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "vs_bubble_channel"
    }
}
