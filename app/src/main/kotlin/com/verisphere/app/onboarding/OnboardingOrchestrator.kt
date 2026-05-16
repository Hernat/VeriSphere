package com.verisphere.app.onboarding

import android.os.Build

/**
 * Story 5.2 — first-launch detection + permission-orchestration logic
 * centralized in one testable class.
 *
 * Architecture rationale (line 517 — new top-level package requires a
 * one-line rationale): centralize first-launch + permission orchestration
 * logic + flag persistence keys; currently scattered between
 * MainActivity and architectural intent. Consolidating enables JVM
 * unit testing per Story 5.2 AC #7 (fake `SecureStorage` lambdas + fake
 * API level) without going through instrumented `MainActivity` tests.
 *
 * **Lambda-seam pattern** — mirrors [com.verisphere.app.storage.RateLimitRepositoryImpl]
 * (Story 1.5) and [com.verisphere.app.storage.HistoryRepositoryImpl] (Story 1.10).
 * The class takes `readBoolean` / `writeBoolean` function references at
 * construction; in production, [com.verisphere.app.MainActivity] wires
 * `secureStorage::readBoolean` and `secureStorage::writeBoolean` from the
 * `AppContainer`. JVM tests pass any pair of pure-Kotlin lambdas (e.g.
 * a `MutableMap<String, Boolean>` backing).
 *
 * **Two persisted flags** (both `SecureStorage.readBoolean` /
 * `writeBoolean`, default `false`):
 *
 *  - `tutorial_seen` — set on `OnboardingTutorialOverlay.onComplete`
 *    (Card 4 "Got it") OR `onSkip` (Cards 2-4 "Skip"). Single-show
 *    invariant per AC #9 / CDN #4 (UX spec section 6 "Once skipped or
 *    completed, never re-shown").
 *  - `notification_permission_asked` — set in the
 *    `ActivityResultContracts.RequestPermission` callback regardless
 *    of grant/deny outcome (CDN #6). Suppresses re-prompting per
 *    Android 13+ runtime-permission contract (architecture D2.6).
 *
 * **Code-review YAGNI decision (DN1, 2026-05-16)**: the originally-specified
 * `first_launch_completed` "master gate" flag was removed because nothing
 * in the cascade consumes it (CDN #3's claim that it distinguishes "fresh
 * install" from "steady state" was aspirational — the cascade decisions
 * are made entirely on `tutorialSeen` + the 3 permission booleans). The
 * `tryMarkFirstLaunchComplete` write was dead code. If a V2 story needs
 * a "have we ever fully onboarded" gate, it can be re-derived from
 * `tutorialSeen && all-permissions-granted` at call time, or
 * re-introduced with an actual consumer.
 *
 * **Service-start gate** ([canStartBubbleService]) — the ALL THREE
 * AND-condition required to start `BubbleOverlayService` (AC #4,
 * CDN #2, architecture D5.13 + D2.11):
 *
 *     canDrawOverlays
 *         AND (Build.VERSION.SDK_INT < 33 OR POST_NOTIFICATIONS granted)
 *         AND isAccessibilityServiceEnabled
 *
 * Exposed as a `companion object` function because it depends on no
 * stored state — pure boolean algebra parameterized on the runtime
 * gate values + API level.
 *
 * **API-level injection**: [canStartBubbleService] accepts `apiLevel: Int`
 * as a parameter with `Build.VERSION.SDK_INT` default so JVM unit tests
 * can simulate API 32 vs API 33+ without Robolectric. Production callers
 * omit the argument.
 *
 * Stateless within the class — no fields besides the two lambdas; all
 * persisted state lives behind the seam. Safe to call from any thread;
 * reads/writes are synchronous on the caller's thread per SecureStorage's
 * blocking API (D1.6). Production callers wrap first-access reads in
 * `lifecycleScope.launch { withContext(Dispatchers.IO) }` per CDN #8
 * to keep the 50–200 ms Keystore cold-init off the main thread.
 */
class OnboardingOrchestrator(
    private val readBoolean: (String, Boolean) -> Boolean,
    private val writeBoolean: (String, Boolean) -> Unit,
) {

    fun isTutorialSeen(): Boolean =
        readBoolean(KEY_TUTORIAL_SEEN, false)

    fun markTutorialSeen() {
        writeBoolean(KEY_TUTORIAL_SEEN, true)
    }

    fun isNotificationPermissionAsked(): Boolean =
        readBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false)

    fun markNotificationPermissionAsked() {
        writeBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true)
    }

    companion object {
        const val KEY_TUTORIAL_SEEN: String = "tutorial_seen"
        const val KEY_NOTIFICATION_PERMISSION_ASKED: String = "notification_permission_asked"

        /**
         * Pure-function form of the AC #4 / CDN #2 service-start gate.
         * Exposed as a companion-object function so JVM tests can call it
         * without instantiating the class.
         */
        fun canStartBubbleService(
            overlayGranted: Boolean,
            notificationGranted: Boolean,
            accessibilityEnabled: Boolean,
            apiLevel: Int = Build.VERSION.SDK_INT,
        ): Boolean = overlayGranted &&
            (apiLevel < API_LEVEL_RUNTIME_NOTIFICATIONS || notificationGranted) &&
            accessibilityEnabled

        const val API_LEVEL_RUNTIME_NOTIFICATIONS: Int = 33
    }
}
