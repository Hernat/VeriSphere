package com.verisphere.app.onboarding

import com.verisphere.app.onboarding.OnboardingOrchestrator.Companion.KEY_BATTERY_OPTIMIZATION_PROMPTED
import com.verisphere.app.onboarding.OnboardingOrchestrator.Companion.KEY_TUTORIAL_SEEN
import com.verisphere.app.onboarding.OnboardingOrchestrator.Companion.KEY_USER_GEMINI_API_KEY
import com.verisphere.app.onboarding.OnboardingOrchestrator.Companion.KEY_USER_SERP_API_KEY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * Story 10.1 — String-keyed sibling of [FakeBooleanStore] used to
     * back the new [OnboardingOrchestrator.readString] / [OnboardingOrchestrator.writeString]
     * lambda seam. Returns `null` for absent keys (matches the
     * SecureStorage contract surfaced by `readString`).
     */
    private class FakeStringStore {
        private val map = mutableMapOf<String, String>()
        fun read(key: String): String? = map[key]
        fun write(key: String, value: String) {
            map[key] = value
        }

        fun get(key: String): String? = map[key]
    }

    private fun newOrchestrator(
        boolStore: FakeBooleanStore = FakeBooleanStore(),
        stringStore: FakeStringStore = FakeStringStore(),
    ) = OnboardingOrchestrator(
        readBoolean = boolStore::read,
        writeBoolean = boolStore::write,
        readString = stringStore::read,
        writeString = stringStore::write,
    )

    private fun newOrchestrator(store: FakeBooleanStore) = newOrchestrator(boolStore = store)

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

    // ─── Story 10.1 — user-API-key flags ────────────────────────────

    @Test
    fun `readUserGeminiApiKey returns null when storage is empty`() {
        val orch = newOrchestrator()
        assertNull(orch.readUserGeminiApiKey())
    }

    @Test
    fun `writeUserGeminiApiKey then readUserGeminiApiKey round-trip`() {
        val stringStore = FakeStringStore()
        val orch = newOrchestrator(stringStore = stringStore)
        orch.writeUserGeminiApiKey("AIza-test-key-1234567890ABCDEFGHIJ")
        assertEquals("AIza-test-key-1234567890ABCDEFGHIJ", orch.readUserGeminiApiKey())
        assertEquals("AIza-test-key-1234567890ABCDEFGHIJ", stringStore.get(KEY_USER_GEMINI_API_KEY))
    }

    @Test
    fun `readUserSerpApiKey returns null when storage is empty`() {
        val orch = newOrchestrator()
        assertNull(orch.readUserSerpApiKey())
    }

    @Test
    fun `writeUserSerpApiKey then readUserSerpApiKey round-trip`() {
        val stringStore = FakeStringStore()
        val orch = newOrchestrator(stringStore = stringStore)
        orch.writeUserSerpApiKey("serp-test-token-xyz")
        assertEquals("serp-test-token-xyz", orch.readUserSerpApiKey())
        assertEquals("serp-test-token-xyz", stringStore.get(KEY_USER_SERP_API_KEY))
    }

    @Test
    fun `writeUserGeminiApiKey does not touch SerpAPI key`() {
        // Cross-key isolation — both keys share the SecureStorage prefs
        // file; a cross-write bug would silently leak Gemini bytes into
        // the SerpAPI URL parameter (or vice-versa) at request time.
        val stringStore = FakeStringStore()
        val orch = newOrchestrator(stringStore = stringStore)
        orch.writeUserGeminiApiKey("AIza-gemini-only")
        assertEquals("AIza-gemini-only", stringStore.get(KEY_USER_GEMINI_API_KEY))
        assertNull(stringStore.get(KEY_USER_SERP_API_KEY))
        assertNull(orch.readUserSerpApiKey())
    }

    @Test
    fun `writeUserSerpApiKey does not touch Gemini key`() {
        val stringStore = FakeStringStore()
        val orch = newOrchestrator(stringStore = stringStore)
        orch.writeUserSerpApiKey("serp-only")
        assertEquals("serp-only", stringStore.get(KEY_USER_SERP_API_KEY))
        assertNull(stringStore.get(KEY_USER_GEMINI_API_KEY))
        assertNull(orch.readUserGeminiApiKey())
    }

    @Test
    fun `writing API keys does not touch boolean flags`() {
        // The String + Boolean stores are independent — verify the
        // 4-lambda seam keeps them isolated even under cross-type writes.
        val boolStore = FakeBooleanStore()
        val stringStore = FakeStringStore()
        val orch = newOrchestrator(boolStore = boolStore, stringStore = stringStore)
        orch.writeUserGeminiApiKey("AIza-foo")
        orch.writeUserSerpApiKey("serp-bar")
        assertFalse(orch.isTutorialSeen())
        assertFalse(orch.isBatteryOptimizationPrompted())
    }
}
