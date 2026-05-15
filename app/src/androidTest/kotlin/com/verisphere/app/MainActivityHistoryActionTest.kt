package com.verisphere.app

import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verisphere.app.bubble.EXTRA_BUBBLE_ANCHOR_X_PX
import com.verisphere.app.bubble.EXTRA_SESSION_ID
import com.verisphere.app.bubble.buildOpenHistoryIntent
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
 * Story 4.3 — Compose UI coverage for [MainActivity]'s
 * [com.verisphere.app.ui.history.HistoryScreenIntent.ACTION_OPEN] arm
 * in [MainActivity.onNewIntent]. Exercises the end-to-end Intent →
 * `detailRecordToShow = null` path inside the live `MainActivity` host.
 *
 * **Mirrors [MainActivityDetailPanelTest]'s gate-bypass + fixture
 * seeding pattern verbatim** (Story 2.4 precedent). The empty-history
 * test for AC #12.3 lives in a separate class
 * [MainActivityHistoryActionEmptyTest] because that test must skip the
 * class-level `@Before` seeding.
 *
 * **Test scope**:
 *  - #12.1 — panel-then-history sequence: send a panel-open intent,
 *    confirm panel mounts, then send the history-open intent and
 *    confirm the panel is dismissed (only the LazyColumn row remains).
 *    The single load-bearing test for AC #2 ("`MainActivity.onNewIntent`
 *    reads the action and ensures the `HistoryScreen` is shown").
 *  - #12.2 — history-already-visible no-op: send the history-open
 *    intent with no panel open; assert the LazyColumn row stays
 *    rendered (no double-render, no crash).
 *
 * Method names use underscore_snake_case per the existing androidTest
 * convention (matches `MainActivityDetailPanelTest`).
 */
@RunWith(AndroidJUnit4::class)
class MainActivityHistoryActionTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        // Re-assert in case a prior @AfterClass (or external test
        // ordering glitch) reset it before this @Before fires.
        MainActivity.bypassGatesForTest = true
        // Seed the production HistoryRepository (shared singleton via
        // AppContainer). Each method uses a uniquely-keyed id so order
        // independence is preserved within the suite.
        val container = appContainer()
        runBlocking {
            container.historyRepository.append(fixtureRecord(id = PRIMARY_ID))
        }
    }

    @After
    fun tearDown() {
        // Clear persisted history so subsequent test classes start with
        // a fresh on-disk state. Mirrors `MainActivityDetailPanelTest`'s
        // P8 pattern.
        val container = appContainer()
        container.secureStorage.clear(HistoryRepositoryImpl.KEY_HISTORY)
    }

    @Test
    fun idle_tap_intent_opens_history_screen_after_panel_was_open() {
        // Step 1: send panel-open Intent (Story 2.4 path) — panel mounts
        // on top of HistoryScreen. The headline appears in BOTH the
        // panel (Story 2.3 header) and the history-list row (Story 4.2)
        // → 2 matches expected.
        sendPanelIntent(PRIMARY_ID)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("What was read").assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText(fixtureHeadlineFor(PRIMARY_ID))
            .assertCountEquals(EXPECTED_MATCHES_PANEL_PLUS_HISTORY)

        // Step 2: send history-open Intent (Story 4.3 path) — onNewIntent
        // sets detailRecordToShow = null which triggers Compose
        // recomposition that drops the DetailPanelHost overlay.
        sendHistoryOpenIntent()
        composeTestRule.waitForIdle()

        // Step 3: panel is dismissed — only the LazyColumn row renders
        // the headline. The "What was read" panel section heading is
        // gone. AC #2 satisfied.
        composeTestRule.onNodeWithText("What was read").assertDoesNotExist()
        composeTestRule
            .onAllNodesWithText(fixtureHeadlineFor(PRIMARY_ID))
            .assertCountEquals(EXPECTED_MATCHES_HISTORY_ONLY)
    }

    @Test
    fun idle_tap_intent_with_history_already_visible_is_a_noop() {
        // No panel ever opened — only HistoryScreen visible from the
        // initial activity launch. The seeded record's headline appears
        // exactly once (history list).
        composeTestRule.waitForIdle()
        composeTestRule
            .onAllNodesWithText(fixtureHeadlineFor(PRIMARY_ID))
            .assertCountEquals(EXPECTED_MATCHES_HISTORY_ONLY)

        // Send the history-open Intent.
        sendHistoryOpenIntent()
        composeTestRule.waitForIdle()

        // History row is still rendered (no double-render, no crash).
        composeTestRule
            .onAllNodesWithText(fixtureHeadlineFor(PRIMARY_ID))
            .assertCountEquals(EXPECTED_MATCHES_HISTORY_ONLY)
        // The panel section heading must not exist (no panel was ever
        // mounted).
        composeTestRule.onNodeWithText("What was read").assertDoesNotExist()
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

    private fun sendHistoryOpenIntent() {
        composeTestRule.activity.runOnUiThread {
            val context = ApplicationProvider.getApplicationContext<VeriSphereApplication>()
            composeTestRule.activity.startActivity(buildOpenHistoryIntent(context))
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
        ocrText = "Sample OCR text for the history-action fixture",
        regionalBiasNote = null,
    )

    companion object {
        private const val PRIMARY_ID = "history-action-test-id"

        /**
         * Story 4.3 — record-specific headline so `onNodeWithText` does
         * not multi-match against the `HistoryScreen` LazyColumn rows.
         * `EXPECTED_MATCHES_PANEL_PLUS_HISTORY` = 2 because the panel
         * renders the record's headline AND the history row renders
         * the same headline behind the panel. `EXPECTED_MATCHES_HISTORY_ONLY` = 1
         * once the panel is dismissed.
         */
        private fun fixtureHeadlineFor(id: String): String =
            "Story 4.3 fixture headline ($id)"

        private const val EXPECTED_MATCHES_PANEL_PLUS_HISTORY = 2
        private const val EXPECTED_MATCHES_HISTORY_ONLY = 1

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
