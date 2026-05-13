package com.verisphere.app.util

import android.content.Context
import android.provider.Settings

/**
 * Reads the system's reduce-motion preference (Story 3.4, UX-DR16, PRD NFR4).
 *
 * `ANIMATOR_DURATION_SCALE = 0` is the Android-canonical signal that the
 * user has disabled animations (Developer Options → "Animator duration
 * scale" → "Animation off" OR the system Accessibility "Reduce motion"
 * toggle on API 33+).
 *
 * **System vs Global gotcha** — UX-DR16 spec wording calls out only
 * `Settings.System.ANIMATOR_DURATION_SCALE`, but the user-facing toggle
 * actually writes to `Settings.Global.ANIMATOR_DURATION_SCALE` on
 * API 17+. The legacy `Settings.System` row is NOT updated when the user
 * changes the Developer Options toggle on modern Android (the two tables
 * live in different ContentProviders and are never auto-synced). Reading
 * System alone misses the user's actual preference on every Pixel
 * running API 30+. This helper therefore reads BOTH and returns `true`
 * if EITHER is `0f` — a defensible super-set of the UX-DR16 wording
 * that honours the user's intent on the devices VeriSphere actually
 * targets (`minSdk = 30`).
 *
 * Called once per session by `BubbleOverlayService.onCreate`; the value
 * is then cached in [com.verisphere.app.bubble.BubbleStateMachine.reduceMotionEnabled]
 * — never re-read on each frame, never observed for mid-session toggles
 * (consistent with how Android itself treats `ANIMATOR_DURATION_SCALE`
 * changes — most system animations also do not hot-reload).
 */
fun isReduceMotionEnabled(context: Context): Boolean {
    val resolver = context.contentResolver
    // Settings.System.ANIMATOR_DURATION_SCALE is marked @Deprecated by
    // the platform — the column has moved to Settings.Global on API 17+.
    // We deliberately read it anyway as a fallback for legacy / forked
    // ROMs that still honour the System row (UX-DR16 verbatim wording
    // calls out System; we read BOTH per the Settings docstring above).
    //
    // Each read is wrapped in `runCatching` so a SecurityException from
    // a restricted user context (backup-restore, kid profile, multi-user
    // TOCTOU) OR any other RuntimeException from the SettingsProvider
    // IPC does NOT propagate up into `BubbleOverlayService.onCreate`
    // and crash the service (which would trigger a START_STICKY restart
    // loop). On read failure we fall back to the default 1f animation
    // scale → reduce-motion off — the safer default for a service-start
    // path is to NOT change runtime visual behaviour just because a
    // settings read threw.
    @Suppress("DEPRECATION")
    val systemScale = runCatching {
        Settings.System.getFloat(
            resolver,
            Settings.System.ANIMATOR_DURATION_SCALE,
            DEFAULT_ANIMATION_SCALE,
        )
    }.getOrDefault(DEFAULT_ANIMATION_SCALE)
    val globalScale = runCatching {
        Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            DEFAULT_ANIMATION_SCALE,
        )
    }.getOrDefault(DEFAULT_ANIMATION_SCALE)
    // NaN guard — a misbehaving OEM that writes `NaN` to either column
    // would otherwise silently disable reduce-motion (NaN != 0f returns
    // true for the strict equality). For an accessibility preference,
    // the safer interpretation of NaN is "treat as reduce-motion on"
    // (NaN → animations off → opt-in to the calm experience).
    return systemScale.isReduceMotionScale() || globalScale.isReduceMotionScale()
}

private fun Float.isReduceMotionScale(): Boolean = this == 0f || this.isNaN()

private const val DEFAULT_ANIMATION_SCALE = 1f
