package com.verisphere.app.ui.history

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 */
class HistoryScreenInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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
        val recA = sampleRecord(id = "a", headline = "Headline A", timestampMs = 1_000L)
        val recB = sampleRecord(id = "b", headline = "Headline B", timestampMs = 2_000L)

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
        val captured = mutableListOf<String>()
        val rec = sampleRecord(id = "rec-xyz", headline = "Click target", timestampMs = 5_000L)

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
