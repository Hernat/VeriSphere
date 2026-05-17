package com.verisphere.app.ui.history

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.verisphere.app.R
import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.storage.SessionRecord
import com.verisphere.app.ui.banner.TEST_TAG_BANNER
import com.verisphere.app.ui.banner.TEST_TAG_DISMISS
import com.verisphere.app.ui.theme.VeriSphereTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Story 6.3 — Compose UI tests for the [HistoryContent] banner wiring.
 *
 * The stateless content composable takes `updateBanner: UpdateBannerPayload?`
 * directly — no AppContainer / SecureStorage / ViewModel scaffolding.
 * Mirrors [HistoryScreenInstrumentedTest] (Story 4.1) +
 * [com.verisphere.app.ui.banner.UpdateBannerUiTest] (Story 6.2)
 * patterns. The recording-lambda + payload-injection technique is the
 * same pattern Story 6.2 used for UpdateBanner itself, scaled one
 * level up to the HistoryContent host.
 *
 * Method names use underscore_snake_case per the existing androidTest
 * convention.
 */
class HistoryScreenUpdateBannerTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val samplePayload = UpdateBannerPayload(
        version = "1.2.3",
        downloadUrl = "https://drive.google.com/file/d/test/view",
    )

    @Test
    fun update_banner_shown_when_payload_non_null_and_state_is_empty() {
        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    HistoryContent(
                        state = HistoryUiState.Empty,
                        onItemClick = {},
                        onBackClick = {},
                        updateBanner = samplePayload,
                        onUpdateBannerDismiss = {},
                        onUpdateBannerDownload = {},
                    )
                }
            }
        }
        composeTestRule.onNodeWithTag(TEST_TAG_BANNER).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TEST_TAG_HISTORY_EMPTY_PLACEHOLDER).assertIsDisplayed()
    }

    @Test
    fun update_banner_shown_when_payload_non_null_and_state_is_content() {
        val records = listOf(
            SessionRecord(
                id = "rec-1",
                // Code-review patch P5 (2026-05-17) — fixed timestamp
                // eliminates date-boundary flake on slow CI / NTP-skewed
                // devices. `HistoryItemRow`'s relative-time formatter
                // produces a stable "57 years ago" string for the Unix
                // epoch + 1s value; the test asserts only the headline
                // text so the rendered timestamp is irrelevant.
                timestampMs = FIXED_TEST_TIMESTAMP_MS,
                verdictLabel = VerdictLabel.TRUE,
                headline = "Sample verdict headline",
                contextLines = emptyList(),
                sourceLinks = emptyList<SourceCitation>(),
                ocrText = "",
                regionalBiasNote = null,
                injectionDetected = false,
            ),
        )
        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    HistoryContent(
                        state = HistoryUiState.Content(records),
                        onItemClick = {},
                        onBackClick = {},
                        updateBanner = samplePayload,
                        onUpdateBannerDismiss = {},
                        onUpdateBannerDownload = {},
                    )
                }
            }
        }
        composeTestRule.onNodeWithTag(TEST_TAG_BANNER).assertIsDisplayed()
        composeTestRule.onNodeWithText("Sample verdict headline").assertIsDisplayed()
    }

    @Test
    fun update_banner_absent_when_payload_is_null() {
        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    HistoryContent(
                        state = HistoryUiState.Empty,
                        onItemClick = {},
                        onBackClick = {},
                        updateBanner = null,
                    )
                }
            }
        }
        composeTestRule.onNodeWithTag(TEST_TAG_BANNER).assertDoesNotExist()
    }

    @Test
    fun update_banner_dismiss_click_invokes_callback_exactly_once() {
        var dismissCount = 0
        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    HistoryContent(
                        state = HistoryUiState.Empty,
                        onItemClick = {},
                        onBackClick = {},
                        updateBanner = samplePayload,
                        onUpdateBannerDismiss = { dismissCount++ },
                        onUpdateBannerDownload = {},
                    )
                }
            }
        }
        composeTestRule.onNodeWithTag(TEST_TAG_DISMISS).performClick()
        assertEquals(1, dismissCount)
    }

    @Test
    fun update_banner_download_click_invokes_callback_exactly_once() {
        var downloadCount = 0
        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    HistoryContent(
                        state = HistoryUiState.Empty,
                        onItemClick = {},
                        onBackClick = {},
                        updateBanner = samplePayload,
                        onUpdateBannerDismiss = {},
                        onUpdateBannerDownload = { downloadCount++ },
                    )
                }
            }
        }
        val downloadLabel = composeTestRule.activity.getString(R.string.update_banner_download)
        composeTestRule.onNodeWithText(downloadLabel).performClick()
        assertEquals(1, downloadCount)
    }

    private companion object {
        /**
         * Code-review patch P5 (2026-05-17) — fixed test timestamp
         * (Unix epoch + 1s) replaces `System.currentTimeMillis() - 60_000L`
         * to eliminate date-boundary flake on slow CI / NTP-skewed
         * devices. The tests assert only headline text + testTag presence;
         * the absolute timestamp value is irrelevant to the assertions.
         */
        const val FIXED_TEST_TIMESTAMP_MS: Long = 1_000L
    }
}
