package com.verisphere.app.onboarding

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
 * The class takes 4 function references at construction (Boolean and
 * String read/write pairs). In production, [com.verisphere.app.MainActivity]
 * wires `secureStorage::readBoolean` / `writeBoolean` / `readString` /
 * `writeString` from the `AppContainer`. JVM tests pass any pure-Kotlin
 * lambdas (e.g. a pair of `MutableMap`-backed fake stores).
 *
 * **Persisted state** (all `SecureStorage`, defaults `false` / `null`):
 *
 *  - `tutorial_seen` (Boolean) — set on `OnboardingTutorialOverlay.onComplete`
 *    (Card 4 "Got it") OR `onSkip` (Cards 2-4 "Skip"). Single-show
 *    invariant per AC #9 / CDN #4 (UX spec section 6 "Once skipped or
 *    completed, never re-shown").
 *  - `battery_optimization_prompted` (Boolean — Story 5.3 AR26 / D5.8) —
 *    set when the [BatteryOptimizationBottomSheet] is dismissed via any
 *    path (scrim tap / swipe-down / Back / primary CTA). Single-show
 *    invariant per Story 5.3 AC #5 ("regardless of whether the user
 *    actually disabled optimisation"). Only relevant on hostile OEMs
 *    (Story 5.3 HOSTILE_OEMS const); on non-hostile devices the flag
 *    is never written.
 *  - `user_gemini_api_key` (String — Story 10.1, 2026-05-20) — the
 *    user-entered Gemini API key from the Paramètres tab. Required for
 *    every verify request (no longer embedded in `BuildConfig` after
 *    the Story 10.1 build.gradle.kts cleanup). [readUserGeminiApiKey]
 *    returns `null` until the user saves one ; GeminiClient
 *    short-circuits to `GeminiOutcome.Failure.NotConfigured` when the
 *    stored value is blank.
 *  - `user_serp_api_key` (String — Story 10.1, 2026-05-20) — the
 *    optional SerpAPI key. SerpAPI is graceful-degradation per Epic 9
 *    plan : the Gemini-only verdict path stays functional when this
 *    key is `null` or blank.
 *
 * The third Story-5.2 flag, `notification_permission_asked`, was
 * removed 2026-05-19 alongside the notification-gate cleanup (see the
 * "POST_NOTIFICATIONS removed from the gate" section below).
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
 * **Service-start gate** ([canStartBubbleService]) — the AND-condition
 * required to start `BubbleOverlayService`:
 *
 *     canDrawOverlays
 *         AND isAccessibilityServiceEnabled
 *
 * Exposed as a `companion object` function because it depends on no
 * stored state — pure boolean algebra parameterized on the runtime gate
 * values.
 *
 * **POST_NOTIFICATIONS removed from the gate** (2026-05-19 — per Hernat
 * post-Epic-9 product decision): the runtime notification permission is
 * no longer mandatory. The foreground-service notification is silently
 * suppressed when the OS hasn't granted POST_NOTIFICATIONS, but the
 * service itself continues to run and the bubble overlay still
 * functions. Users who want the persistent notification can grant it
 * via system Settings → Apps → VeriSphere → Notifications. The
 * `POST_NOTIFICATIONS` manifest declaration is retained so that an
 * eventual opt-in surface can request the permission without a manifest
 * bump.
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
    private val readString: (String) -> String?,
    private val writeString: (String, String) -> Unit,
) {

    fun isTutorialSeen(): Boolean =
        readBoolean(KEY_TUTORIAL_SEEN, false)

    fun markTutorialSeen() {
        writeBoolean(KEY_TUTORIAL_SEEN, true)
    }

    /**
     * Story 5.3 AR26 / D5.8 — has the battery-optimisation bottom-sheet
     * been shown and dismissed at least once? Single-show invariant per
     * Story 5.3 AC #5.
     */
    fun isBatteryOptimizationPrompted(): Boolean =
        readBoolean(KEY_BATTERY_OPTIMIZATION_PROMPTED, false)

    /**
     * Story 5.3 AR26 / D5.8 — persist the single-show invariant. Callers
     * MUST wrap this write in `withContext(NonCancellable + Dispatchers.IO)`
     * (Story 5.2 P4 / P7 / Story 5.3 CDN #5 pattern) because the activity
     * may be destroyed between the dismissal gesture and the
     * `prefs.edit().putBoolean(...).apply()` completion.
     */
    fun markBatteryOptimizationPrompted() {
        writeBoolean(KEY_BATTERY_OPTIMIZATION_PROMPTED, true)
    }

    /**
     * Story 10.1 — read the user-entered Gemini API key (or `null` when
     * the user has not yet saved one). Consumed via the
     * `apiKeyProvider` lambda wired in [com.verisphere.app.AppContainer]
     * so every [com.verisphere.app.gemini.GeminiClient.verify] call
     * reads the freshest value. Returns `null` (not an empty string) so
     * the caller can distinguish "never set" from "explicitly cleared".
     */
    fun readUserGeminiApiKey(): String? =
        readString(KEY_USER_GEMINI_API_KEY)

    /**
     * Story 10.1 — persist the user-entered Gemini API key. Callers
     * MUST wrap this write in `withContext(NonCancellable + Dispatchers.IO)`
     * (Story 5.2 P4 / P7 + Story 5.3 CDN #5 pattern) because the
     * activity may be destroyed mid-save when the user backgrounds the
     * app between tapping Save and `prefs.edit().apply()` completion.
     */
    fun writeUserGeminiApiKey(value: String) {
        writeString(KEY_USER_GEMINI_API_KEY, value)
    }

    /**
     * Story 10.1 — read the user-entered SerpAPI key. Empty / `null` is
     * the supported "graceful disable" state per Epic 9 plan ; SerpAPI
     * is never required for a Gemini-only verdict.
     */
    fun readUserSerpApiKey(): String? =
        readString(KEY_USER_SERP_API_KEY)

    /**
     * Story 10.1 — persist the user-entered SerpAPI key. Same write
     * discipline as [writeUserGeminiApiKey].
     */
    fun writeUserSerpApiKey(value: String) {
        writeString(KEY_USER_SERP_API_KEY, value)
    }

    companion object {
        const val KEY_TUTORIAL_SEEN: String = "tutorial_seen"
        const val KEY_BATTERY_OPTIMIZATION_PROMPTED: String = "battery_optimization_prompted"

        /** Story 10.1 — `SecureStorage` key for the user-entered Gemini API key. */
        const val KEY_USER_GEMINI_API_KEY: String = "user_gemini_api_key"

        /** Story 10.1 — `SecureStorage` key for the user-entered SerpAPI key (optional). */
        const val KEY_USER_SERP_API_KEY: String = "user_serp_api_key"

        /**
         * Pure-function form of the service-start gate. Exposed as a
         * companion-object function so JVM tests can call it without
         * instantiating the class.
         */
        fun canStartBubbleService(
            overlayGranted: Boolean,
            accessibilityEnabled: Boolean,
        ): Boolean = overlayGranted && accessibilityEnabled
    }
}
