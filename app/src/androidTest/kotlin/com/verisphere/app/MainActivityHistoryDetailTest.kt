package com.verisphere.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.storage.HistoryRepositoryImpl
import com.verisphere.app.storage.SessionRecord
import kotlinx.coroutines.runBlocking
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
 * Story 4.4 — Compose UI coverage for the history-row × tap →
 * read-only [com.verisphere.app.ui.detail.AnchoredDetailPanel] flow.
 *
 * Exercises the end-to-end `HistoryItemRow.onClick` →
 * `MainActivity.openHistoryRecord(recordId)` →
 * `HistoryRepository.getById(recordId)` → `detailRecordToShow = record`
 * → `DetailPanelHost` mount inside the live `MainActivity` host.
 *
 * Mirrors [MainActivityHistoryActionTest]'s gate-bypass + fixture
 * seeding pattern verbatim (Story 4.3 precedent). The class lives in
 * the `androidTest/` source set per the architecture line 426 rule
 * (Compose UI tests using `createAndroidComposeRule` cannot run on the
 * JVM; method names use underscore_snake_case per the project convention).
 *
 * **Test scope** (AC #6):
 *  - Test #1 — `tap_on_history_row_opens_detail_panel_with_record_fields`:
 *    seeded record → headline appears once before tap (LazyColumn row),
 *    twice after tap (panel header + row beneath the scrim); panel's
 *    [com.verisphere.app.ui.detail.TAG_SOURCES_ROW] (`vs_detail_sources_row`)
 *    is the discriminating selector that proves [com.verisphere.app.ui.detail.DetailPanelContent]
 *    actually mounted (not just any text match).
 *  - Test #2 — `back_press_after_row_tap_closes_panel_and_returns_to_history`:
 *    open the panel via row tap, dispatch back via
 *    `onBackPressedDispatcher.onBackPressed()`, assert the panel is
 *    gone (source-row tag absent), the history row is still rendered
 *    (count == 1), and the activity is still alive
 *    (`isFinishing == false`).
 *  - Test #3 — `back_press_with_no_panel_open_finishes_activity`: from
 *    the initial activity launch (history list visible, no panel),
 *    dispatch back, assert the activity is finishing
 *    (`isFinishing == true`). Demonstrates that AC #5
 *    (`onBackClick = ::finish`) wiring inherited from Story 4.1
 *    survives Story 4.4's lambda change.
 *
 * **Caveat — in-memory cache**: the shared `HistoryRepositoryImpl`
 * singleton retains appended records in its in-memory cache for the
 * lifetime of the instrumentation process. The `@After`
 * `clear(KEY_HISTORY)` removes records from disk but the cache still
 * serves them on `observe()`. Same V1-acknowledged limitation as
 * [MainActivityHistoryActionEmptyTest] (resolved by Hernat as V2
 * unit-test posture review; `deferred-work.md` D1 of Story 4-3 review).
 * If a sibling test class seeds records first and this class runs
 * second in the same instrumentation process, Test #3's empty-history
 * assumption may be invalidated (legitimate flake, not a Story 4.4
 * regression).
 *
 * **No `runBlocking` around `secureStorage.clear`** — `SecureStorage.clear`
 * is synchronous (Story 4.3 P3 patch precedent — removed the redundant
 * `runBlocking` from [MainActivityHistoryActionEmptyTest.setUp]).
 */
@RunWith(AndroidJUnit4::class)
class MainActivityHistoryDetailTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        // Re-assert in case a prior @AfterClass (or external test
        // ordering glitch) reset it before this @Before fires.
        MainActivity.bypassGatesForTest = true
        // Seed the production HistoryRepository (shared singleton via
        // AppContainer). The seeded record id is unique to this class
        // so order independence is preserved within the suite.
        val container = appContainer()
        runBlocking {
            container.historyRepository.append(fixtureRecord())
        }
    }

    @After
    fun tearDown() {
        // Clear persisted history so subsequent test classes start with
        // a fresh on-disk state. Mirrors `MainActivityHistoryActionTest`'s
        // tearDown pattern; the in-memory cache leak (D1) is acknowledged
        // in the class KDoc above.
        val container = appContainer()
        container.secureStorage.clear(HistoryRepositoryImpl.KEY_HISTORY)
    }

    @Test
    fun tap_on_history_row_opens_detail_panel_with_record_fields() {
        composeTestRule.waitForIdle()

        // Pre-state: the seeded record's headline appears exactly once
        // (the LazyColumn row). The panel is not mounted, so the
        // discriminating source-row tag does not exist yet.
        composeTestRule
            .onAllNodesWithText(FIXTURE_HEADLINE)
            .assertCountEquals(EXPECTED_MATCHES_HISTORY_ONLY)
        composeTestRule.onNodeWithTag(TAG_SOURCES_ROW).assertDoesNotExist()

        // Tap the row. `Modifier.semantics(mergeDescendants = true)` on
        // HistoryItemRow merges children's Text properties up to the
        // clickable Role.Button parent, so onNodeWithText(headline)
        // resolves to the row's merged node — performClick fires the
        // Modifier.clickable onClick.
        composeTestRule.onNodeWithText(FIXTURE_HEADLINE).performClick()
        composeTestRule.waitForIdle()

        // Post-tap: the same headline now renders TWICE — once in the
        // panel's verdict-row header (DetailPanelContent VerdictRow) and
        // once in the underlying LazyColumn row beneath the scrim. The
        // source-row testTag is the discriminating selector that proves
        // DetailPanelContent actually mounted (D6 brittleness deferred
        // V2 — Story 4.3 review D6 precedent).
        composeTestRule
            .onAllNodesWithText(FIXTURE_HEADLINE)
            .assertCountEquals(EXPECTED_MATCHES_PANEL_PLUS_HISTORY)
        composeTestRule.onNodeWithTag(TAG_SOURCES_ROW).assertIsDisplayed()
        // The "What was read" section heading is rendered by the panel's
        // OcrSection; this also proves DetailPanelContent rendered.
        composeTestRule.onNodeWithText(PANEL_OCR_HEADING).assertIsDisplayed()

        // FR17 regression guard (review patch P1) — the read-only panel
        // must NOT render a "Verify again" / "Refresh" button. Currently
        // satisfied by construction; this assertion catches a future
        // regression where a contributor adds such an affordance to
        // DetailPanelContent.
        composeTestRule.onNodeWithText("Verify again").assertDoesNotExist()
        composeTestRule.onNodeWithText("Refresh").assertDoesNotExist()
    }

    @Test
    fun back_press_after_row_tap_closes_panel_and_returns_to_history() {
        composeTestRule.waitForIdle()

        // Open the panel (same path as Test #1).
        composeTestRule.onNodeWithText(FIXTURE_HEADLINE).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_SOURCES_ROW).assertIsDisplayed()

        // Dispatch back press through the framework chain. The
        // ModalBottomSheet's internal Dialog claims it and routes to
        // `onDismissRequest = { detailRecordToShow = null }`, which
        // triggers Compose recomposition that drops DetailPanelHost.
        composeTestRule.activity.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        // Panel is gone — source-row tag no longer exists in the merged
        // semantics tree.
        composeTestRule.onNodeWithTag(TAG_SOURCES_ROW).assertDoesNotExist()
        // History base layer survives — the LazyColumn row still renders
        // the headline (count back to 1).
        composeTestRule
            .onAllNodesWithText(FIXTURE_HEADLINE)
            .assertCountEquals(EXPECTED_MATCHES_HISTORY_ONLY)
        // Activity is still alive — the back press was consumed by the
        // panel's onDismissRequest, NOT by ComponentActivity's default
        // back-press handler.
        assertFalse(
            "MainActivity must remain alive after the panel-dismiss back press",
            composeTestRule.activity.isFinishing,
        )
    }

    @Test
    fun back_press_with_no_panel_open_finishes_activity() {
        composeTestRule.waitForIdle()

        // Pre-state: history visible, no panel mounted.
        composeTestRule
            .onAllNodesWithText(FIXTURE_HEADLINE)
            .assertCountEquals(EXPECTED_MATCHES_HISTORY_ONLY)
        composeTestRule.onNodeWithTag(TAG_SOURCES_ROW).assertDoesNotExist()

        // Dispatch back press. With no panel mounted, no ModalBottomSheet
        // Dialog is in the composition to claim the back press; it
        // falls through to ComponentActivity.onBackPressed() which
        // calls finish() (Story 4.1's `onBackClick = ::finish` shape is
        // wired into HistoryScreen but the screen does not render a
        // TopAppBar back arrow — the system back routes through
        // ComponentActivity's default dispatcher).
        composeTestRule.activity.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        // Activity is finishing — AC #5 satisfied. The bubble overlay
        // window (TYPE_APPLICATION_OVERLAY, independent of MainActivity's
        // lifecycle per Story 1.10 + Story 4.3 CDN #5) survives the
        // activity finish; that behaviour is covered by the smoke
        // Scenario C in Task 7 (visual ground-truth — JVM/instrumentation
        // cannot assert it without an overlay-window snapshot).
        assertTrue(
            "MainActivity must be finishing after back press with no panel open",
            composeTestRule.activity.isFinishing,
        )
    }

    // ─── Fixture helpers ─────────────────────────────────────────────

    private fun appContainer(): AppContainer {
        val app = ApplicationProvider.getApplicationContext<VeriSphereApplication>()
        return app.container
    }

    private fun fixtureRecord(): SessionRecord = SessionRecord(
        id = FIXTURE_ID,
        timestampMs = 0L,
        verdictLabel = VerdictLabel.TRUE,
        headline = FIXTURE_HEADLINE,
        contextLines = emptyList(),
        sourceLinks = listOf(
            SourceCitation(
                title = "Sample article",
                url = "https://www.bbc.com/news/sample",
                publisher = "BBC News",
                dateYearMonth = "2026-04",
            ),
        ),
        ocrText = "Sample OCR text for the Story 4.4 history-detail fixture",
        regionalBiasNote = null,
    )

    companion object {
        private const val FIXTURE_ID = "history-detail-test-id"

        /**
         * Story 4.4 — record-specific headline so `onAllNodesWithText`
         * count assertions are not polluted by stray UI text. The
         * `assertCountEquals(...)` brittleness is the same V1 limitation
         * as Story 4.3 D6 (hardcoded counts assume panel + row each
         * render the headline exactly once — deferred V2 test-stability
         * pass).
         */
        private const val FIXTURE_HEADLINE = "Story 4.4 fixture headline."

        /**
         * [com.verisphere.app.ui.detail.TAG_SOURCES_ROW] is `internal` to
         * the `ui/detail` package. Hardcoding the literal here mirrors
         * the value at [com.verisphere.app.ui.detail.DetailPanelContent]
         * `internal const val TAG_SOURCES_ROW = "vs_detail_sources_row"`.
         * Any rename there must be reflected here (lint-impossible to
         * enforce without exposing the constant; same trade-off as
         * Story 2.4 `MainActivityDetailPanelTest`).
         */
        private const val TAG_SOURCES_ROW = "vs_detail_sources_row"

        /**
         * `R.string.detail_ocr_title` resolved value. Hardcoded to keep
         * the test free of resource-context plumbing; the literal lives
         * verbatim in `strings_verdict.xml` (line 35).
         */
        private const val PANEL_OCR_HEADING = "Texte lu"

        private const val EXPECTED_MATCHES_HISTORY_ONLY = 1
        private const val EXPECTED_MATCHES_PANEL_PLUS_HISTORY = 2

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
