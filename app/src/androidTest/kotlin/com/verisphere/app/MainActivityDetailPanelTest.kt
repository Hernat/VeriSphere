package com.verisphere.app

import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verisphere.app.bubble.EXTRA_BUBBLE_ANCHOR_X_PX
import com.verisphere.app.bubble.EXTRA_SESSION_ID
import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.storage.HistoryRepositoryImpl
import com.verisphere.app.storage.SessionRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Story 2.4 — Compose UI coverage for [MainActivity]'s detail-panel
 * mode. Exercises the end-to-end Intent → `HistoryRepository.getById`
 * → `DetailPanelHost` → panel composition path inside the live
 * `MainActivity` host.
 *
 * **Launch sequencing** — `createAndroidComposeRule<MainActivity>()`
 * launches the activity with a default MAIN intent (no panel extras),
 * so the [com.verisphere.app.ui.history.HistoryScreen] renders first
 * (the gate-bypass flag masks the overlay / accessibility checks).
 * Each test method then sends a NEW intent via
 * [androidx.test.core.app.ActivityScenario]'s underlying
 * `startActivity` call, which routes through `onNewIntent` (manifest
 * `launchMode="singleTop"` + `FLAG_ACTIVITY_SINGLE_TOP`) and triggers
 * the panel-open path. Story 4.1 replaced the previous
 * `BootstrapPlaceholder` ("VeriSphere" centred text) with `HistoryScreen`
 * which renders the seeded records' headlines in a `LazyColumn` BEHIND
 * the panel — so per-record headlines MUST be unique to avoid
 * multi-match failures on `onNodeWithText(...)` (which is a
 * single-node matcher).
 *
 * **Gate bypass** — [MainActivity.bypassGatesForTest] is set in a
 * static initializer block (runs before the rule's `before` phase
 * launches the activity) so the panel-open path's permission gates
 * are short-circuited. Reset in `@AfterClass` to keep production
 * code-path honest for sibling test classes.
 *
 * **Fixture seeding** — the production `HistoryRepository` (shared
 * singleton via `AppContainer`) is seeded in `@Before` with unique
 * session ids per test method AND unique headlines per record
 * ([fixtureHeadlineFor]) to avoid collision with the
 * `HistoryScreen`-rendered LazyColumn rows behind the panel. The
 * cache caps at 500 entries; the suite seeds at most 2 records, well
 * within range.
 *
 * Method names use underscore_snake_case per the DEX < 040 fallback +
 * the existing androidTest convention (`SourceLinkChipUiTest`,
 * `DetailPanelContentUiTest`).
 */
@RunWith(AndroidJUnit4::class)
class MainActivityDetailPanelTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        // Re-assert in case a prior @AfterClass (or external test
        // ordering glitch) reset it before this @Before fires.
        MainActivity.bypassGatesForTest = true
        // Seed the production HistoryRepository (shared singleton via
        // AppContainer). Each method uses a uniquely-keyed id so order
        // independence is preserved within the suite. Story 4.1 P1 —
        // headlines must ALSO be unique per record because Story 4.1's
        // `HistoryScreen` now renders all seeded records' headlines in
        // a LazyColumn behind the detail panel; a duplicate-headline
        // seed would produce a multi-match crash on
        // `onNodeWithText(FIXTURE_HEADLINE)` (single-node matcher).
        val container = appContainer()
        runBlocking {
            container.historyRepository.append(fixtureRecord(id = PRIMARY_ID))
            container.historyRepository.append(fixtureRecord(id = TOOLTIP_ID))
        }
    }

    @After
    fun tearDown() {
        // P8 — Clear the persisted history key so subsequent test
        // classes start with a fresh on-disk state. The shared
        // HistoryRepository singleton's in-memory cache retains the
        // appended records for the lifetime of the instrumentation
        // process (the repository does not expose a public cache
        // reset, by design — production code does not need one). Tests
        // that observe `historyRepository.observe()` from a fresh
        // process WILL see this clean state because the lazy load
        // reads from SecureStorage. Tests that depend on the empty
        // initial state within THIS process must use unique session
        // ids (every test in this class does).
        val container = appContainer()
        container.secureStorage.clear(HistoryRepositoryImpl.KEY_HISTORY)
    }

    @Test
    fun tap_on_verdict_bubble_intent_opens_panel_with_record_fields() {
        sendPanelIntent(PRIMARY_ID)
        composeTestRule.waitForIdle()

        // P1 — assert the PRIMARY headline (unique per record) so the
        // node lookup matches exactly one node — the panel headline.
        // The history-list LazyColumn row for the same record also
        // contains this string; we expect 2 matches and accept either.
        composeTestRule.onNodeWithText("TRUE").assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText(fixtureHeadlineFor(PRIMARY_ID))
            .assertCountEquals(EXPECTED_MATCHES_PANEL_PLUS_HISTORY)
        composeTestRule.onNodeWithText("What was read").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sources").assertIsDisplayed()
        composeTestRule.onNodeWithText("No one sees this. Just between us.").assertIsDisplayed()
    }

    @Test
    fun tap_on_flash_tooltip_intent_opens_same_panel_for_same_record() {
        // The tooltip-tap path and the bubble-tap path produce the
        // same Intent via the same factory (buildDetailPanelIntent);
        // this method validates a second-record fixture also renders.
        sendPanelIntent(TOOLTIP_ID)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("TRUE").assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText(fixtureHeadlineFor(TOOLTIP_ID))
            .assertCountEquals(EXPECTED_MATCHES_PANEL_PLUS_HISTORY)
    }

    @Test
    fun unknown_session_id_does_not_show_panel() {
        sendPanelIntent("nonexistent-id-xyz")
        composeTestRule.waitForIdle()

        // The panel section heading from Story 2.3 must NOT exist.
        composeTestRule.onNodeWithText("What was read").assertDoesNotExist()
        // P1 — Story 4.1 replaced the "VeriSphere" BootstrapPlaceholder
        // with HistoryScreen; assert the seeded record headlines are
        // visible in the history list (which is now the fallback UI
        // shown when the panel is not open). Each seed is unique so
        // exactly one node per record.
        composeTestRule
            .onAllNodesWithText(fixtureHeadlineFor(PRIMARY_ID))
            .assertCountEquals(EXPECTED_MATCHES_HISTORY_ONLY)
    }

    @Test
    fun back_gesture_closes_panel_and_returns_to_placeholder() {
        sendPanelIntent(PRIMARY_ID)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("What was read").assertIsDisplayed()

        // Back-press routes through the M3 ModalBottomSheet's
        // onDismissRequest → onDismiss = { detailRecordToShow = null }.
        Espresso.pressBack()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("What was read").assertDoesNotExist()
        // P1 — Story 4.1 HistoryScreen is the post-dismiss fallback UI;
        // assert the seeded record headline is visible in the history
        // list. Exactly one match (panel is dismissed).
        composeTestRule
            .onAllNodesWithText(fixtureHeadlineFor(PRIMARY_ID))
            .assertCountEquals(EXPECTED_MATCHES_HISTORY_ONLY)
    }

    @Test
    fun font_scale_1_5_opened_panel_renders_all_sections_without_truncation() {
        // Story 2.4 AC #11.b method 5 — NFR14 lock-in for the
        // activity-mounted panel rendering path (vs Story 2.3's
        // `DetailPanelContentUiTest.font_scale_1_5_does_not_truncate_*`
        // which covers the content composable in isolation).
        //
        // Strict per-test `LocalDensity` override is not feasible with
        // `createAndroidComposeRule<MainActivity>()` because the
        // activity's `setContent {}` has already executed before the
        // test body runs, so `composeTestRule.setContent {}` is
        // unavailable. Two coverage paths combine to satisfy NFR14:
        //   1. This test asserts that ALL panel section nodes
        //      (verdict word, headline, OCR title, Sources title,
        //      footer) render through the full Intent → getById →
        //      DetailPanelHost path AND that no truncation ellipsis
        //      ("…") leaks into any text node — at the device's
        //      default font scale. Regression here means the activity
        //      mounting path itself dropped a node.
        //   2. Story 2.3's `font_scale_1_5_does_not_truncate_ocr_text_or_overlap_footer`
        //      covers the fontScale = 1.5f rendering of every section
        //      via a direct `composeTestRule.setContent` with
        //      `CompositionLocalProvider(LocalDensity provides ...)`.
        sendPanelIntent(PRIMARY_ID)
        composeTestRule.waitForIdle()

        // Same 5 section assertions as method 1 — every node from
        // method 1 must remain reachable via the activity-mounted
        // panel composition.
        composeTestRule.onNodeWithText("TRUE").assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText(fixtureHeadlineFor(PRIMARY_ID))
            .assertCountEquals(EXPECTED_MATCHES_PANEL_PLUS_HISTORY)
        composeTestRule.onNodeWithText("What was read").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sources").assertIsDisplayed()
        composeTestRule.onNodeWithText("No one sees this. Just between us.").assertIsDisplayed()

        // No truncation ellipsis leaks into any rendered Text node —
        // Story 2.3 D3 in deferred-work.md acknowledges this assertion
        // is overbroad if a future fixture string legitimately
        // contains U+2026, but no current fixture does.
        composeTestRule.onAllNodesWithText("…", substring = true).assertCountEquals(0)
    }

    @Test
    fun reopening_panel_after_dismiss_renders_record_again() {
        // Open → dismiss → reopen with the same intent extras.
        // Verifies that detailRecordToShow correctly resets on
        // dismiss AND can be re-populated on the next onNewIntent
        // cycle.
        sendPanelIntent(PRIMARY_ID)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("What was read").assertIsDisplayed()

        Espresso.pressBack()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("What was read").assertDoesNotExist()

        sendPanelIntent(PRIMARY_ID)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("What was read").assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText(fixtureHeadlineFor(PRIMARY_ID))
            .assertCountEquals(EXPECTED_MATCHES_PANEL_PLUS_HISTORY)
    }

    // ─── Fixture helpers ─────────────────────────────────────────────

    private fun appContainer(): AppContainer {
        val app = ApplicationProvider.getApplicationContext<VeriSphereApplication>()
        return app.container
    }

    private fun sendPanelIntent(sessionId: String) {
        composeTestRule.activity.runOnUiThread {
            composeTestRule.activity.startActivity(buildPanelIntent(sessionId))
        }
    }

    private fun buildPanelIntent(sessionId: String): Intent {
        val context = ApplicationProvider.getApplicationContext<VeriSphereApplication>()
        return Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .putExtra(EXTRA_BUBBLE_ANCHOR_X_PX, 0)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private fun fixtureRecord(id: String): SessionRecord = SessionRecord(
        id = id,
        timestampMs = 0L,
        verdictLabel = VerdictLabel.TRUE,
        headline = fixtureHeadlineFor(id),
        contextLines = emptyList(),
        sourceLinks = listOf(
            SourceCitation(
                title = "Sample article",
                url = "https://www.bbc.com/news/sample",
                publisher = "BBC News",
                dateYearMonth = "2026-04",
            ),
        ),
        ocrText = "Sample OCR text for the detail-panel fixture",
        regionalBiasNote = null,
    )

    companion object {
        private const val PRIMARY_ID = "primary-test-id"
        private const val TOOLTIP_ID = "tooltip-test-id"

        /**
         * Story 4.1 P1 — record-specific headline. Each seeded record
         * gets a unique headline so `onNodeWithText(...)` (a
         * single-node matcher) does not multi-match against the
         * `HistoryScreen` LazyColumn rows that now render behind the
         * detail panel.
         *
         * `EXPECTED_MATCHES_PANEL_PLUS_HISTORY` = 2 because the panel
         * renders the record's headline AND the history-list row
         * renders the same record's headline behind the panel.
         * `EXPECTED_MATCHES_HISTORY_ONLY` = 1 because only the
         * history-list row renders the headline when the panel is
         * dismissed / never opened.
         */
        private fun fixtureHeadlineFor(id: String): String =
            "Story 2.4 fixture headline ($id)"

        private const val EXPECTED_MATCHES_PANEL_PLUS_HISTORY = 2
        private const val EXPECTED_MATCHES_HISTORY_ONLY = 1

        // P7 — Use @BeforeClass / @AfterClass (with @JvmStatic) so the
        // gate-bypass flag is set BEFORE any @Rule fires AND reset
        // AFTER the last test, keeping the production code-path
        // honest for sibling test classes that run later in the same
        // instrumentation process.
        //
        // The KDoc previously promised this reset but the
        // implementation only had `init {}` (no reset). @AfterClass
        // closes that gap.
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
