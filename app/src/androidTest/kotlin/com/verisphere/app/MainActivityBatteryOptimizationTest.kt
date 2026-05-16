package com.verisphere.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.verisphere.app.onboarding.OnboardingOrchestrator
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Story 5.3 — Compose UI coverage for the battery-optimisation
 * bottom-sheet on hostile OEMs (AR26, D5.8, AC #8).
 *
 * Exercises the sibling-layer mount + dismissal persistence under the
 * dual test-bypass hooks (Story 5.2 / 5.3 precedent):
 *
 *  - [MainActivity.bypassGatesForTest] = `true` forces the three
 *    permission gates true so the cascade reaches the steady-state
 *    HistoryScreen branch.
 *  - [MainActivity.bypassManufacturerForTest] = `"samsung"` simulates
 *    a hostile OEM on the AVD (which actually returns `"Google"`).
 *
 * **Method ordering** ([FixMethodOrder] `NAME_ASCENDING`): test #4 sets
 * a non-hostile manufacturer override, so the alphabetical-name ordering
 * (`a_`, `b_`, `c_`, `d_` prefixes) guarantees the hostile-OEM tests
 * run first against the class-level `samsung` setup.
 *
 * **Test scope** (Story 5.3 AC #8):
 *  - Test a — `a_battery_optimization_sheet_mounts_when_all_four_conditions_pass`:
 *    bypass on, tutorial_seen seeded, battery_optimization_prompted
 *    cleared → the bottom-sheet's primary CTA text is displayed.
 *  - Test b — `b_dismissing_sheet_via_back_persists_flag`: same setup,
 *    `Espresso.pressBack()` (routes through `ModalBottomSheet.onDismissRequest`)
 *    → assert flag persisted (`orchestrator.isBatteryOptimizationPrompted()`).
 *  - Test c — `c_sheet_does_not_remount_after_recreate_when_flag_set`:
 *    pre-seed the flag → recreate → assert sheet CTA absent + history
 *    empty-state heading visible (P10 positive cascade assertion).
 *  - Test d — `d_sheet_does_not_mount_for_non_hostile_manufacturer`:
 *    override manufacturer to `"google"` → assert sheet CTA absent +
 *    history empty-state heading visible.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MainActivityBatteryOptimizationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        // Re-assert in case prior test class mutated either hook.
        MainActivity.bypassGatesForTest = true
        // Default to hostile manufacturer for Tests a/b/c. Test d
        // overrides this after the @Before fires.
        MainActivity.bypassManufacturerForTest = "samsung"
        // Wipe all three onboarding flags so each test starts cold.
        clearOnboardingFlags()
        // Tutorial must be marked seen so the cascade falls through to
        // HistoryScreen and the sibling-layer gate evaluates true.
        orchestrator().markTutorialSeen()
        // Force the cascade to re-evaluate against the freshly-seeded
        // state (Story 5.2 P9 pattern).
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
    }

    @After
    fun tearDown() {
        clearOnboardingFlags()
    }

    @Test
    fun a_battery_optimization_sheet_mounts_when_all_four_conditions_pass() {
        // Story 5.2 P11 — `waitUntil` instead of bare `waitForIdle` to
        // tolerate slow CI emulators that may need >100 ms to compose
        // the ModalBottomSheet's internal Dialog.
        composeTestRule.waitUntil(timeoutMillis = WAIT_UNTIL_TIMEOUT_MS) {
            composeTestRule
                .onAllNodesWithText(openSettingsCta())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText(openSettingsCta()).assertIsDisplayed()
    }

    @Test
    fun b_dismissing_sheet_via_back_persists_flag() {
        // Pre-state: sheet mounted, flag not yet written.
        composeTestRule.waitUntil(timeoutMillis = WAIT_UNTIL_TIMEOUT_MS) {
            composeTestRule
                .onAllNodesWithText(openSettingsCta())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertFalse(
            "battery_optimization_prompted must not be set before dismissal",
            orchestrator().isBatteryOptimizationPrompted(),
        )

        // Espresso's back-press routes through the activity's
        // dispatcher → ModalBottomSheet's internal Dialog onBackPressed
        // → onDismissRequest → onBatteryOptimizationDismiss → flag
        // persisted.
        Espresso.pressBack()

        // Code-review P4 — wait on the ACTUAL persistence (SecureStorage
        // disk write completion), NOT just the UI text disappearance.
        // The prior `waitUntil { ...isEmpty() }` resolved the instant
        // the synchronous Compose state flip happened in
        // `onBatteryOptimizationDismiss`, but the `lifecycleScope.launch
        // { withContext(NonCancellable + Dispatchers.IO) { ... } }` IO
        // write was still pending → the subsequent assertion on
        // `orchestrator().isBatteryOptimizationPrompted()` raced against
        // the disk commit and could spuriously read `false` on slow
        // emulators. Polling the orchestrator directly makes the test
        // deterministic.
        composeTestRule.waitUntil(timeoutMillis = WAIT_UNTIL_TIMEOUT_MS) {
            orchestrator().isBatteryOptimizationPrompted()
        }
        composeTestRule.onNodeWithText(openSettingsCta()).assertDoesNotExist()

        // Belt-and-braces: the final assertion is now redundant but
        // preserved as documentation of the AC #5 invariant.
        assertTrue(
            "battery_optimization_prompted must be set after dismissal (AC #5)",
            orchestrator().isBatteryOptimizationPrompted(),
        )
    }

    @Test
    fun c_sheet_does_not_remount_after_recreate_when_flag_set() {
        // Pre-write the flag (mirrors @Before's tutorialSeen seeding
        // pattern) BEFORE the recreate so onCreate's synchronous P1
        // seed picks it up.
        orchestrator().markBatteryOptimizationPrompted()
        composeTestRule.activityRule.scenario.recreate()

        // Story 5.2 P11 — wait until the cascade resolves to
        // HistoryScreen's empty-state heading (positive assertion per
        // P10 — catches blank composition / activity crash).
        composeTestRule.waitUntil(timeoutMillis = WAIT_UNTIL_TIMEOUT_MS) {
            composeTestRule
                .onAllNodesWithText(historyEmptyHeading())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText(historyEmptyHeading()).assertIsDisplayed()
        // Negative: sheet did NOT re-mount.
        composeTestRule.onNodeWithText(openSettingsCta()).assertDoesNotExist()
    }

    @Test
    fun d_sheet_does_not_mount_for_non_hostile_manufacturer() {
        // Override @Before's `samsung` to a non-hostile manufacturer.
        // Also clear the persisted flag from prior test to avoid the
        // gate's `!batteryOptimizationPrompted` short-circuit hiding
        // the real "non-hostile" branch.
        MainActivity.bypassManufacturerForTest = "google"
        // No additional clear needed — @Before already invoked
        // `clearOnboardingFlags()` (code-review P1 — removed the
        // empty `orchestrator().run { /* comment */ }` block that
        // claimed to "re-clear" but was a no-op).
        composeTestRule.activityRule.scenario.recreate()

        composeTestRule.waitUntil(timeoutMillis = WAIT_UNTIL_TIMEOUT_MS) {
            composeTestRule
                .onAllNodesWithText(historyEmptyHeading())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText(historyEmptyHeading()).assertIsDisplayed()
        composeTestRule.onNodeWithText(openSettingsCta()).assertDoesNotExist()
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private fun appContainer(): AppContainer {
        val app = ApplicationProvider.getApplicationContext<VeriSphereApplication>()
        return app.container
    }

    private fun orchestrator(): OnboardingOrchestrator {
        val storage = appContainer().secureStorage
        return OnboardingOrchestrator(
            readBoolean = storage::readBoolean,
            writeBoolean = storage::writeBoolean,
        )
    }

    private fun clearOnboardingFlags() {
        val storage = appContainer().secureStorage
        storage.clear(OnboardingOrchestrator.KEY_TUTORIAL_SEEN)
        storage.clear(OnboardingOrchestrator.KEY_NOTIFICATION_PERMISSION_ASKED)
        storage.clear(OnboardingOrchestrator.KEY_BATTERY_OPTIMIZATION_PROMPTED)
    }

    private fun targetString(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun openSettingsCta(): String =
        targetString(R.string.battery_optimization_open_settings)

    private fun historyEmptyHeading(): String =
        targetString(R.string.history_empty_title)

    companion object {
        private const val WAIT_UNTIL_TIMEOUT_MS: Long = 5_000L

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            MainActivity.bypassGatesForTest = true
            MainActivity.bypassManufacturerForTest = "samsung"
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            MainActivity.bypassGatesForTest = false
            MainActivity.bypassManufacturerForTest = null
        }
    }
}
