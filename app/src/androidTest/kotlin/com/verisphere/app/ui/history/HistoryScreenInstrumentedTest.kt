package com.verisphere.app.ui.history

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.verisphere.app.R
import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.storage.SessionRecord
import com.verisphere.app.ui.theme.VeriSphereTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI tests for [HistoryContent] (Story 4.1). The stateless
 * content composable takes a [HistoryUiState] directly — no
 * `HistoryViewModel`, no `AppContainer`, no `Application`. Architecture
 * line 350 stateful/stateless split is what makes this trivial: the
 * androidTest source set never needs to scaffold a ViewModel.
 *
 * Method names use underscores per the existing androidTest convention
 * (`SourceLinkChipUiTest`, `CaptureFlowSmokeTest`,
 * `GeminiAssetsInstrumentedTest`).
 *
 * **Patch P4 (code review 2026-05-14)** — test fixtures use
 * `now - offset` style timestamps so `DateUtils.getRelativeTimeSpanString`
 * renders a sensible "2 m / 1 h / 3 d" trailing slot. Previously the
 * fixtures used absolute literals like `timestampMs = 1_000L` (Unix
 * epoch + 1 s) which the formatter rendered as ~57 years ago in 2026.
 */
class HistoryScreenInstrumentedTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun sampleRecord(id: String, headline: String, timestampMs: Long): SessionRecord =
        SessionRecord(
            id = id,
            timestampMs = timestampMs,
            verdictLabel = VerdictLabel.TRUE,
            headline = headline,
            contextLines = emptyList(),
            sourceLinks = emptyList<SourceCitation>(),
            ocrText = "",
            regionalBiasNote = null,
            injectionDetected = false,
        )

    private fun nowMinus(deltaMs: Long): Long = System.currentTimeMillis() - deltaMs

    @Test
    fun history_content_renders_empty_placeholder_when_state_is_empty() {
        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    HistoryContent(
                        state = HistoryUiState.Empty,
                        onItemClick = {},
                        onBackClick = {},
                    )
                }
            }
        }

        composeTestRule
            .onNodeWithTag("history_empty_placeholder")
            .assertIsDisplayed()

        // Story 4.2 — empty-state title + subtitle copy assertion. Strings
        // loaded via composeTestRule.activity.getString(...) for i18n safety
        // (Story 2.1 deferred-work line 174 pattern; matches Story 7.5's
        // future V1-language=French pass without test rewriting).
        composeTestRule
            .onNodeWithText(
                composeTestRule.activity.getString(R.string.history_empty_title),
            )
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                composeTestRule.activity.getString(R.string.history_empty_subtitle),
            )
            .assertIsDisplayed()

        // Patch P10 (code review 2026-05-14) — assert the
        // EmptyBubbleGlyph's contentDescription is wired through the
        // semantics tree so a future regression that drops it (or the
        // mergeDescendants block from P7) gets caught.
        composeTestRule
            .onNodeWithContentDescription(
                composeTestRule.activity.getString(R.string.history_empty_glyph_content_description),
            )
            .assertIsDisplayed()
    }

    @Test
    fun history_content_renders_loading_indicator_when_state_is_loading() {
        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    HistoryContent(
                        state = HistoryUiState.Loading,
                        onItemClick = {},
                        onBackClick = {},
                    )
                }
            }
        }

        composeTestRule
            .onNodeWithTag("history_loading_indicator")
            .assertIsDisplayed()
    }

    @Test
    fun history_content_renders_lazy_column_with_n_rows_when_state_is_content() {
        // Patch P4 — realistic relative timestamps (5 min / 2 min before
        // now). Order does not matter for this test because HistoryContent
        // does not sort; the ViewModel does. But we keep timestamps
        // distinct so the headlines (and resulting rows) are
        // unambiguous.
        val recA = sampleRecord(id = "a", headline = "Headline A", timestampMs = nowMinus(5L * 60_000L))
        val recB = sampleRecord(id = "b", headline = "Headline B", timestampMs = nowMinus(2L * 60_000L))

        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    HistoryContent(
                        state = HistoryUiState.Content(listOf(recA, recB)),
                        onItemClick = {},
                        onBackClick = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Headline A").assertIsDisplayed()
        composeTestRule.onNodeWithText("Headline B").assertIsDisplayed()
        composeTestRule
            .onAllNodes(hasText("Headline A").or(hasText("Headline B")))
            .assertCountEquals(2)
    }

    @Test
    fun history_content_forwards_row_click_to_on_item_click_with_record_id() {
        // Patch P4 — realistic relative timestamp (1 min before now).
        val rec = sampleRecord(
            id = "rec-xyz",
            headline = "Click target",
            timestampMs = nowMinus(60_000L),
        )
        val captured = mutableListOf<String>()

        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    HistoryContent(
                        state = HistoryUiState.Content(listOf(rec)),
                        onItemClick = { captured += it },
                        onBackClick = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Click target").performClick()

        assertEquals(listOf("rec-xyz"), captured)
    }
}
