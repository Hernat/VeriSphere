package com.verisphere.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.verisphere.app.onboarding.OnboardingOrchestrator
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Story 5.2 — Compose UI coverage for the first-launch + permission
 * orchestration cascade in [MainActivity] (AC #7).
 *
 * Exercises the 4-state `when {}` cascade behaviour under the
 * [MainActivity.bypassGatesForTest] hook (Story 4.4 precedent extended
 * to all three permission gates for Story 5.2). The hook forces
 * `overlayOk = notificationOk = accessibilityOk = true` so the cascade
 * reaches the `!tutorialSeen` (tutorial overlay) or the steady-state
 * (HistoryScreen) branch depending on the seeded `tutorial_seen` flag.
 *
 * **Card 1 auto-advance under bypass**: with `accessibilityServiceEnabled`
 * forced to `true`, the `LaunchedEffect(accessibilityServiceEnabled)`
 * inside [com.verisphere.app.ui.onboarding.OnboardingTutorialOverlay]
 * advances `currentCardIndex 0 → 1` on first composition. Tests therefore
 * land on Card 2 ("Long-press to fact-check"), not Card 1 (Activer).
 *
 * **Test scope** (Story 5.2 AC #7 + AC #9):
 *  - Test #1 — `tutorial_overlay_mounts_when_all_three_gates_pass_and_tutorial_not_seen`:
 *    bypass on, no flags pre-written → Card 2 title ("Long-press to
 *    fact-check") + "Skip" CTA visible (proves tutorial composable
 *    mounted; HistoryScreen renders neither).
 *  - Test #2 — `tutorial_skip_writes_tutorial_seen_flag_to_secure_storage`:
 *    same setup as #1, tap "Skip", assert the persisted flag flipped
 *    via `OnboardingOrchestrator.isTutorialSeen()` (AC #9 / CDN #4
 *    single-show invariant).
 *  - Test #3 — `tutorial_got_it_writes_tutorial_seen_flag_to_secure_storage`:
 *    same setup, advance Card 2 → 3 → 4 via "Next" CTAs, tap "Got it"
 *    on Card 4, assert flag persisted.
 *  - Test #4 — `steady_state_skips_tutorial_when_seen_flag_pre_set`:
 *    pre-write `tutorial_seen = true` via the orchestrator, launch
 *    activity, assert the tutorial DOES NOT mount (Card 2 title
 *    absent, "Skip" absent) — verifies single-show invariant from the
 *    cold-start path.
 *
 * `@After` clears all three onboarding flags so subsequent test classes
 * start with a fresh on-disk state (the in-memory `SecureStorage` cache
 * leak documented in [MainActivityHistoryDetailTest] D1 also applies
 * here, mitigated by class-scoped `bypassGatesForTest` toggle).
 */
@RunWith(AndroidJUnit4::class)
class MainActivityFirstLaunchTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        // Re-assert in case a prior @AfterClass (or external test
        // ordering glitch) reset it before this @Before fires.
        MainActivity.bypassGatesForTest = true
        // Story 5.2 — wipe onboarding flags so each test starts from
        // a defined cold state. Required for parallel test ordering
        // across this class + sibling classes.
        //
        // Story 5.2 code-review P9 — `@Before` runs AFTER the `@Rule`
        // launches the activity; the activity's `onCreate` evaluates
        // the cascade with whatever state was leftover from a prior
        // test class. `@BeforeClass setUpClass` now ALSO clears the
        // flags pre-activity-launch (defensive symmetry), and we
        // additionally `scenario.recreate()` here to force the cascade
        // to re-evaluate against the freshly-cleared state. This makes
        // Test #1 / #2 / #3 resilient to cross-class pollution.
        clearOnboardingFlags()
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
        // Seed `tutorial_seen = true` ONLY for Test #4 — set inside
        // that test, not the @Before.
    }

    @After
    fun tearDown() {
        clearOnboardingFlags()
    }

    @Test
    fun tutorial_overlay_mounts_when_all_three_gates_pass_and_tutorial_not_seen() {
        composeTestRule.waitForIdle()

        // Card 1 auto-advances to Card 2 because bypassGatesForTest
        // forces `accessibilityServiceEnabled = true`, which fires the
        // `LaunchedEffect(accessibilityServiceEnabled)` auto-advance.
        composeTestRule
            .onNodeWithText(card2Title())
            .assertIsDisplayed()
        // The "Skip" CTA is rendered on Cards 2-4 only (Story 5.1 AC #3
        // — Card 1 has no skip). Its presence proves the tutorial
        // overlay is mounted in the `when {}` cascade.
        composeTestRule
            .onNodeWithText(skipCta())
            .assertIsDisplayed()
    }

    @Test
    fun tutorial_skip_writes_tutorial_seen_flag_to_secure_storage() {
        composeTestRule.waitForIdle()

        // Pre-state: tutorial mounted (Card 2 after auto-advance), flag
        // not yet written.
        composeTestRule.onNodeWithText(skipCta()).assertIsDisplayed()
        assertFalse(
            "tutorial_seen must not be set before tapping Skip",
            orchestrator().isTutorialSeen(),
        )

        // Tap "Skip" on Card 2.
        composeTestRule.onNodeWithText(skipCta()).performClick()
        composeTestRule.waitForIdle()

        // Post-tap: orchestrator flag persisted to SecureStorage.
        assertTrue(
            "tutorial_seen must be set to true after tapping Skip (AC #9 / CDN #4)",
            orchestrator().isTutorialSeen(),
        )
    }

    @Test
    fun tutorial_got_it_writes_tutorial_seen_flag_to_secure_storage() {
        composeTestRule.waitForIdle()

        // Card 2 is shown (auto-advance from Card 1). Tap "Next" to
        // reach Card 3.
        composeTestRule.onNodeWithText(card2Title()).assertIsDisplayed()
        composeTestRule.onNodeWithText(nextCta()).performClick()
        composeTestRule.waitForIdle()

        // Card 3 — tap "Next" again.
        composeTestRule.onNodeWithText(card3Title()).assertIsDisplayed()
        composeTestRule.onNodeWithText(nextCta()).performClick()
        composeTestRule.waitForIdle()

        // Card 4 — final CTA is "Got it" which invokes onComplete.
        composeTestRule.onNodeWithText(card4Title()).assertIsDisplayed()
        composeTestRule.onNodeWithText(gotItCta()).performClick()
        composeTestRule.waitForIdle()

        // Post-tap: orchestrator flag persisted.
        assertTrue(
            "tutorial_seen must be set to true after tapping Got it on Card 4 (AC #9 / CDN #4)",
            orchestrator().isTutorialSeen(),
        )
    }

    @Test
    fun steady_state_skips_tutorial_when_seen_flag_pre_set() {
        // Pre-write `tutorial_seen = true` BEFORE the rule recomposes.
        // The activity's onCreate runs before this @Test method body,
        // but Story 5.2 code-review P1 made the SecureStorage seed
        // SYNCHRONOUS, so `scenario.recreate()` now reliably reads the
        // freshly-written flag.
        //
        // Story 5.2 code-review P10 — added a positive
        // `historyEmptyState` assertion so the test fails on a blank
        // composition or crash, not just on the absence of tutorial
        // tokens. Story 5.2 code-review P11 — `waitUntil` (with timeout)
        // replaces the prior `waitForIdle` + immediate-assert sequence
        // which raced the IO seed on slow CI emulators.
        orchestrator().markTutorialSeen()
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitUntil(timeoutMillis = WAIT_UNTIL_TIMEOUT_MS) {
            composeTestRule
                .onAllNodesWithText(historyEmptyHeading())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Positive assertion: HistoryScreen empty-state visible.
        composeTestRule.onNodeWithText(historyEmptyHeading()).assertIsDisplayed()
        // Negative assertions: tutorial composable did NOT mount.
        composeTestRule.onNodeWithText(card2Title()).assertDoesNotExist()
        composeTestRule.onNodeWithText(skipCta()).assertDoesNotExist()
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
            readString = storage::readString,
            writeString = storage::writeString,
        )
    }

    private fun clearOnboardingFlags() {
        val storage = appContainer().secureStorage
        // Story 5.2 code-review P15 (DN1) — `KEY_FIRST_LAUNCH_COMPLETED`
        // was removed via YAGNI cleanup ; `KEY_NOTIFICATION_PERMISSION_ASKED`
        // dropped 2026-05-19 alongside the notification-gate removal.
        // Only the tutorial flag persists in V1 onboarding.
        storage.clear(OnboardingOrchestrator.KEY_TUTORIAL_SEEN)
    }

    // Story 4.4 D7 pattern: resolve string literals from the actual
    // resources via the targetContext so a copy edit in
    // strings_tutorial.xml doesn't silently fail the assertion.
    private fun targetString(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun card2Title(): String = targetString(R.string.tutorial_card_2_title)
    private fun card3Title(): String = targetString(R.string.tutorial_card_3_title)
    private fun card4Title(): String = targetString(R.string.tutorial_card_4_title)
    private fun nextCta(): String = targetString(R.string.tutorial_cta_next)
    private fun gotItCta(): String = targetString(R.string.tutorial_cta_got_it)
    private fun skipCta(): String = targetString(R.string.tutorial_cta_skip)

    // Story 5.2 code-review P10 — HistoryScreen empty-state heading used
    // as a positive cascade-evaluation assertion in Test #4.
    private fun historyEmptyHeading(): String = targetString(R.string.history_empty_title)

    companion object {

        // Story 5.2 code-review P11 — timeout for `waitUntil` polling
        // the post-`recreate()` synchronous-seed cascade evaluation.
        // Comfortable headroom for slow CI emulators (typical resolve
        // time is < 1 s).
        private const val WAIT_UNTIL_TIMEOUT_MS: Long = 5_000L

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            MainActivity.bypassGatesForTest = true
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            MainActivity.bypassGatesForTest = false
        }
    }
}
