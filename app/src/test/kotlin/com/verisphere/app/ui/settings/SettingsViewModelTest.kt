package com.verisphere.app.ui.settings

import com.verisphere.app.onboarding.OnboardingOrchestrator
import com.verisphere.app.onboarding.OnboardingOrchestrator.Companion.KEY_USER_GEMINI_API_KEY
import com.verisphere.app.onboarding.OnboardingOrchestrator.Companion.KEY_USER_SERP_API_KEY
import com.verisphere.app.util.GeminiKeyValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Story 10.1 code-review P7 — JVM coverage for [SettingsViewModel].
 *
 * Closes the spec T5.7 acceptance gap (the largest net-new code
 * surface in the story was previously untested at the ViewModel
 * layer). Covers :
 *  1. `init` seeds drafts from the orchestrator
 *  2. `onGeminiKeyChange` / `onSerpKeyChange` updates drafts + drops
 *     prior validation
 *  3. `toggleGeminiVisibility` / `toggleSerpVisibility` flip the mask
 *  4. `onSave` validation matrix : Empty aborts, Valid persists both
 *     keys via the orchestrator
 *  5. `onSave` emits `SettingsEvent.KeysSaved` on success
 *  6. `onSave` resets `saveInFlight = false` in the `finally` block
 *     even when the underlying write throws (P2 IO-exception leak fix)
 *  7. Unicode invisibles in drafts are stripped before persistence
 *     (P6 normalization)
 *
 * **Lambda-seam pattern** — uses a fake `MutableMap<String, String>`-
 * backed orchestrator (mirrors the `FakeStringStore` pattern from
 * `OnboardingOrchestratorTest`). No Android dependencies ; no
 * Robolectric ; pure JVM.
 *
 * **Coroutine pattern** — `StandardTestDispatcher` + `Dispatchers.Main`
 * binding via `Dispatchers.setMain` so the `viewModelScope.launch`
 * dispatches are scheduler-driven and `advanceUntilIdle` flushes them
 * deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeStringStore {
        val map = mutableMapOf<String, String>()
        fun read(key: String): String? = map[key]
        fun write(key: String, value: String) {
            map[key] = value
        }
    }

    private fun newOrchestrator(seed: Map<String, String> = emptyMap()): Pair<OnboardingOrchestrator, FakeStringStore> {
        val stringStore = FakeStringStore().apply { map.putAll(seed) }
        // Boolean lambdas unused by SettingsViewModel ; stub them safely.
        val orchestrator = OnboardingOrchestrator(
            readBoolean = { _, default -> default },
            writeBoolean = { _, _ -> },
            readString = stringStore::read,
            writeString = stringStore::write,
        )
        return orchestrator to stringStore
    }

    // ─── (1) init seeds drafts ──────────────────────────────────────

    @Test
    fun `init seeds Gemini and SerpAPI drafts from orchestrator`() = runTest(testDispatcher) {
        val (orchestrator, _) = newOrchestrator(
            seed = mapOf(
                KEY_USER_GEMINI_API_KEY to "AIzaSeededGeminiKey0123456789ABCDEFGHIJ",
                KEY_USER_SERP_API_KEY to "seeded-serp-token",
            ),
        )
        val vm = SettingsViewModel(orchestrator, ioDispatcher = testDispatcher)
        advanceUntilIdle()
        val state = vm.state.value
        assertFalse("isLoading must flip to false after seed", state.isLoading)
        assertEquals("AIzaSeededGeminiKey0123456789ABCDEFGHIJ", state.geminiKeyDraft)
        assertEquals("seeded-serp-token", state.serpKeyDraft)
    }

    @Test
    fun `init with empty storage leaves both drafts blank`() = runTest(testDispatcher) {
        val (orchestrator, _) = newOrchestrator()
        val vm = SettingsViewModel(orchestrator, ioDispatcher = testDispatcher)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals("", state.geminiKeyDraft)
        assertEquals("", state.serpKeyDraft)
        assertNull(state.geminiValidation)
        assertNull(state.serpValidation)
    }

    // ─── (2) draft change handlers drop prior validation ────────────

    @Test
    fun `onGeminiKeyChange updates draft and clears geminiValidation`() = runTest(testDispatcher) {
        val (orchestrator, _) = newOrchestrator()
        val vm = SettingsViewModel(orchestrator, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        // Force a failed validation state first.
        vm.onSave()
        advanceUntilIdle()
        assertEquals(GeminiKeyValidation.Empty, vm.state.value.geminiValidation)

        // Typing clears the prior outcome.
        vm.onGeminiKeyChange("AIzaPartialTypedKey")
        assertEquals("AIzaPartialTypedKey", vm.state.value.geminiKeyDraft)
        assertNull(vm.state.value.geminiValidation)
    }

    @Test
    fun `onSerpKeyChange updates draft and clears serpValidation`() = runTest(testDispatcher) {
        val (orchestrator, _) = newOrchestrator()
        val vm = SettingsViewModel(orchestrator, ioDispatcher = testDispatcher)
        advanceUntilIdle()
        vm.onSerpKeyChange("anything")
        assertEquals("anything", vm.state.value.serpKeyDraft)
        assertNull(vm.state.value.serpValidation)
    }

    // ─── (3) visibility toggles ─────────────────────────────────────

    @Test
    fun `toggleGeminiVisibility flips the geminiKeyVisible flag`() = runTest(testDispatcher) {
        val (orchestrator, _) = newOrchestrator()
        val vm = SettingsViewModel(orchestrator, ioDispatcher = testDispatcher)
        advanceUntilIdle()
        assertFalse(vm.state.value.geminiKeyVisible)
        vm.toggleGeminiVisibility()
        assertTrue(vm.state.value.geminiKeyVisible)
        vm.toggleGeminiVisibility()
        assertFalse(vm.state.value.geminiKeyVisible)
    }

    @Test
    fun `toggleSerpVisibility flips the serpKeyVisible flag`() = runTest(testDispatcher) {
        val (orchestrator, _) = newOrchestrator()
        val vm = SettingsViewModel(orchestrator, ioDispatcher = testDispatcher)
        advanceUntilIdle()
        assertFalse(vm.state.value.serpKeyVisible)
        vm.toggleSerpVisibility()
        assertTrue(vm.state.value.serpKeyVisible)
    }

    // ─── (4) onSave validation matrix ───────────────────────────────

    @Test
    fun `onSave with short non-blank Gemini draft persists (no format gate)`() = runTest(testDispatcher) {
        // 2026-05-30 — Gemini format validation dropped. Any non-blank
        // draft is Valid and persists ; a wrong key surfaces server-side.
        val (orchestrator, store) = newOrchestrator()
        val vm = SettingsViewModel(orchestrator, ioDispatcher = testDispatcher)
        advanceUntilIdle()
        vm.onGeminiKeyChange("short-key")
        vm.onSerpKeyChange("any-serp-token")
        vm.onSave()
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(GeminiKeyValidation.Valid, state.geminiValidation)
        assertFalse(state.saveInFlight)
        assertEquals("short-key", store.map[KEY_USER_GEMINI_API_KEY])
    }

    @Test
    fun `onSave with Empty Gemini draft aborts without writing`() = runTest(testDispatcher) {
        val (orchestrator, store) = newOrchestrator()
        val vm = SettingsViewModel(orchestrator, ioDispatcher = testDispatcher)
        advanceUntilIdle()
        // Drafts default to empty.
        vm.onSave()
        advanceUntilIdle()
        assertEquals(GeminiKeyValidation.Empty, vm.state.value.geminiValidation)
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun `onSave with Valid Gemini draft persists both keys`() = runTest(testDispatcher) {
        val (orchestrator, store) = newOrchestrator()
        val vm = SettingsViewModel(orchestrator, ioDispatcher = testDispatcher)
        advanceUntilIdle()
        val validGemini = "AIzaTestKey0123456789ABCDEFGHIJ12345678"
        assertEquals(39, validGemini.length)
        vm.onGeminiKeyChange(validGemini)
        vm.onSerpKeyChange("my-serp-token")
        vm.onSave()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(GeminiKeyValidation.Valid, state.geminiValidation)
        assertFalse(state.saveInFlight)
        assertEquals(validGemini, store.map[KEY_USER_GEMINI_API_KEY])
        assertEquals("my-serp-token", store.map[KEY_USER_SERP_API_KEY])
    }

    // ─── (5) success event emission ─────────────────────────────────

    @Test
    fun `onSave emits KeysSaved event on successful persistence`() = runTest(testDispatcher) {
        val (orchestrator, _) = newOrchestrator()
        val vm = SettingsViewModel(orchestrator, ioDispatcher = testDispatcher)
        advanceUntilIdle()
        vm.onGeminiKeyChange("AIzaTestKey0123456789ABCDEFGHIJ12345678")
        vm.onSave()
        advanceUntilIdle()
        // `replay = 1` means the first collector sees the most recent event.
        val event = vm.events.first()
        assertEquals(SettingsEvent.KeysSaved, event)
    }

    // ─── (6) saveInFlight try / finally guarantee ───────────────────

    @Test
    fun `onSave resets saveInFlight to false even when write throws`() = runTest(testDispatcher) {
        // Compose an orchestrator whose writeString throws to simulate
        // a Keystore exception mid-save.
        val orchestrator = OnboardingOrchestrator(
            readBoolean = { _, default -> default },
            writeBoolean = { _, _ -> },
            readString = { null },
            writeString = { _, _ -> error("simulated Keystore exception") },
        )
        val vm = SettingsViewModel(orchestrator, ioDispatcher = testDispatcher)
        advanceUntilIdle()
        vm.onGeminiKeyChange("AIzaTestKey0123456789ABCDEFGHIJ12345678")

        runCatching {
            vm.onSave()
            advanceUntilIdle()
        }
        // P2 — try / finally ensures the UI is not locked even when the
        // underlying write blew up.
        assertFalse(
            "saveInFlight must reset to false in the finally block",
            vm.state.value.saveInFlight,
        )
    }

    // ─── (7) P6 Unicode invisibles stripped before persistence ──────

    @Test
    fun `onSave normalizes Unicode invisibles in drafts before persisting`() = runTest(testDispatcher) {
        val (orchestrator, store) = newOrchestrator()
        val vm = SettingsViewModel(orchestrator, ioDispatcher = testDispatcher)
        advanceUntilIdle()
        // Gemini key with a trailing ZWSP — passes validation via
        // normalizeApiKey ; must be stripped at write time so the
        // stored value matches what the validator saw.
        val rawWithZwsp = "AIzaTestKey0123456789ABCDEFGHIJ12345678​"
        val normalized = "AIzaTestKey0123456789ABCDEFGHIJ12345678"
        vm.onGeminiKeyChange(rawWithZwsp)
        vm.onSave()
        advanceUntilIdle()
        assertEquals(
            "stored value must NOT carry the ZWSP — would otherwise leak to the URL parameter",
            normalized,
            store.map[KEY_USER_GEMINI_API_KEY],
        )
    }
}
