package com.verisphere.app.onboarding

import com.verisphere.app.onboarding.OnboardingOrchestrator.Companion.KEY_BATTERY_OPTIMIZATION_PROMPTED
import com.verisphere.app.onboarding.OnboardingOrchestrator.Companion.KEY_NOTIFICATION_PERMISSION_ASKED
import com.verisphere.app.onboarding.OnboardingOrchestrator.Companion.KEY_TUTORIAL_SEEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [OnboardingOrchestrator] (Story 5.2 AC #7).
 *
 * Architecture-line-426 naming: backtick-quoted English-sentence method
 * names. JVM tests (`src/test/`) so the DEX-< 040 SimpleName constraint
 * does NOT apply — spaces fine.
 *
 * Storage is a [FakeBooleanStore] — a `MutableMap<String, Boolean>` whose
 * `read` / `write` member references are passed to the orchestrator in
 * lieu of a real `SecureStorage`. Pattern mirrors Story 1.5
 * `RateLimitRepositoryTest.FakeStore`.
 *
 * API-level simulation: tests pass explicit `apiLevel` arguments to
 * `canStartBubbleService` so the `Build.VERSION.SDK_INT` default is
 * never evaluated in JVM-test context.
 *
 * **Code-review YAGNI cleanup (DN1, 2026-05-16)**: 5 tests dedicated to
 * `tryMarkFirstLaunchComplete` + `isFirstLaunchComplete` were removed
 * alongside the production methods. The flag was written but never read;
 * removing both eliminates dead code and dead tests.
 */
class OnboardingOrchestratorTest {

    private class FakeBooleanStore {
        private val map = mutableMapOf<String, Boolean>()
        fun read(key: String, default: Boolean): Boolean = map[key] ?: default
        fun write(key: String, value: Boolean) {
            map[key] = value
        }

        fun get(key: String): Boolean? = map[key]
    }

    private fun newOrchestrator(store: FakeBooleanStore) = OnboardingOrchestrator(
        readBoolean = store::read,
        writeBoolean = store::write,
    )

    @Test
    fun `isTutorialSeen returns false when storage flag is absent`() {
        val store = FakeBooleanStore()
        val orch = newOrchestrator(store)
        assertFalse(orch.isTutorialSeen())
    }

    @Test
    fun `isTutorialSeen returns true when storage flag is true`() {
        val store = FakeBooleanStore().apply { write(KEY_TUTORIAL_SEEN, true) }
        val orch = newOrchestrator(store)
        assertTrue(orch.isTutorialSeen())
    }

    @Test
    fun `markTutorialSeen writes the flag to storage`() {
        val store = FakeBooleanStore()
        val orch = newOrchestrator(store)
        orch.markTutorialSeen()
        assertEquals(true, store.get(KEY_TUTORIAL_SEEN))
    }

    @Test
    fun `markNotificationPermissionAsked writes the flag regardless of grant outcome`() {
        // The flag captures that the OS dialog was SHOWN, not what the user
        // chose. Callers invoke this from the launcher callback BEFORE
        // checking the granted boolean (CDN #6 — suppresses re-prompting).
        val store = FakeBooleanStore()
        val orch = newOrchestrator(store)
        orch.markNotificationPermissionAsked()
        assertEquals(true, store.get(KEY_NOTIFICATION_PERMISSION_ASKED))
        assertFalse(orch.isTutorialSeen())
    }

    @Test
    fun `isNotificationPermissionAsked returns false when storage flag is absent`() {
        val store = FakeBooleanStore()
        val orch = newOrchestrator(store)
        assertFalse(orch.isNotificationPermissionAsked())
    }

    @Test
    fun `canStartBubbleService returns true when all three conditions pass on API 33`() {
        assertTrue(
            OnboardingOrchestrator.canStartBubbleService(
                overlayGranted = true,
                notificationGranted = true,
                accessibilityEnabled = true,
                apiLevel = 33,
            ),
        )
    }

    @Test
    fun `canStartBubbleService returns false when overlay denied`() {
        assertFalse(
            OnboardingOrchestrator.canStartBubbleService(
                overlayGranted = false,
                notificationGranted = true,
                accessibilityEnabled = true,
                apiLevel = 33,
            ),
        )
    }

    @Test
    fun `canStartBubbleService returns false when notification denied on API 33`() {
        assertFalse(
            OnboardingOrchestrator.canStartBubbleService(
                overlayGranted = true,
                notificationGranted = false,
                accessibilityEnabled = true,
                apiLevel = 33,
            ),
        )
    }

    @Test
    fun `canStartBubbleService returns true when notification denied on API 32`() {
        // Below API 33, POST_NOTIFICATIONS is install-time-granted so the
        // notification gate is effectively bypassed (CDN #2 second clause).
        assertTrue(
            OnboardingOrchestrator.canStartBubbleService(
                overlayGranted = true,
                notificationGranted = false,
                accessibilityEnabled = true,
                apiLevel = 32,
            ),
        )
    }

    @Test
    fun `canStartBubbleService returns false when accessibility disabled`() {
        assertFalse(
            OnboardingOrchestrator.canStartBubbleService(
                overlayGranted = true,
                notificationGranted = true,
                accessibilityEnabled = false,
                apiLevel = 33,
            ),
        )
    }

    // ─── Story 5.3 — battery-optimisation flag (AR26, D5.8) ─────────

    @Test
    fun `isBatteryOptimizationPrompted returns false when storage flag is absent`() {
        val store = FakeBooleanStore()
        val orch = newOrchestrator(store)
        assertFalse(orch.isBatteryOptimizationPrompted())
    }

    @Test
    fun `isBatteryOptimizationPrompted returns true when storage flag is true`() {
        val store = FakeBooleanStore().apply { write(KEY_BATTERY_OPTIMIZATION_PROMPTED, true) }
        val orch = newOrchestrator(store)
        assertTrue(orch.isBatteryOptimizationPrompted())
    }

    @Test
    fun `markBatteryOptimizationPrompted writes the flag to storage`() {
        val store = FakeBooleanStore()
        val orch = newOrchestrator(store)
        orch.markBatteryOptimizationPrompted()
        assertEquals(true, store.get(KEY_BATTERY_OPTIMIZATION_PROMPTED))
    }

    @Test
    fun `markBatteryOptimizationPrompted does not touch tutorial_seen or notification_permission_asked`() {
        // Cross-key isolation check — mirrors L77-79 pattern for
        // `markNotificationPermissionAsked`. Three Story 5.x flags
        // share the same SecureStorage prefs file (D1.4 single-file
        // posture) so accidental cross-write would be a real bug.
        val store = FakeBooleanStore()
        val orch = newOrchestrator(store)
        orch.markBatteryOptimizationPrompted()
        assertEquals(true, store.get(KEY_BATTERY_OPTIMIZATION_PROMPTED))
        assertFalse(orch.isTutorialSeen())
        assertFalse(orch.isNotificationPermissionAsked())
    }
}
