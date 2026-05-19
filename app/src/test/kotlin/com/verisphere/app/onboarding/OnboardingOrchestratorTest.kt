package com.verisphere.app.onboarding

import com.verisphere.app.onboarding.OnboardingOrchestrator.Companion.KEY_BATTERY_OPTIMIZATION_PROMPTED
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
 * **Notification gate removed 2026-05-19** — the `canStartBubbleService`
 * companion no longer takes a `notificationGranted` / `apiLevel` pair.
 * POST_NOTIFICATIONS is now optional (see [OnboardingOrchestrator]
 * KDoc). The 2 API-level-specific tests + the
 * `markNotificationPermissionAsked` storage tests were dropped along
 * with the production methods.
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
    fun `canStartBubbleService returns true when both gates pass`() {
        assertTrue(
            OnboardingOrchestrator.canStartBubbleService(
                overlayGranted = true,
                accessibilityEnabled = true,
            ),
        )
    }

    @Test
    fun `canStartBubbleService returns false when overlay denied`() {
        assertFalse(
            OnboardingOrchestrator.canStartBubbleService(
                overlayGranted = false,
                accessibilityEnabled = true,
            ),
        )
    }

    @Test
    fun `canStartBubbleService returns false when accessibility disabled`() {
        assertFalse(
            OnboardingOrchestrator.canStartBubbleService(
                overlayGranted = true,
                accessibilityEnabled = false,
            ),
        )
    }

    @Test
    fun `canStartBubbleService returns false when both gates denied`() {
        assertFalse(
            OnboardingOrchestrator.canStartBubbleService(
                overlayGranted = false,
                accessibilityEnabled = false,
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
    fun `markBatteryOptimizationPrompted does not touch tutorial_seen`() {
        // Cross-key isolation check — two Story-5.x flags share the
        // same SecureStorage prefs file (D1.4 single-file posture) so
        // accidental cross-write would be a real bug.
        val store = FakeBooleanStore()
        val orch = newOrchestrator(store)
        orch.markBatteryOptimizationPrompted()
        assertEquals(true, store.get(KEY_BATTERY_OPTIMIZATION_PROMPTED))
        assertFalse(orch.isTutorialSeen())
    }
}
