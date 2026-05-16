package com.verisphere.app.ui.banner

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.verisphere.app.R
import com.verisphere.app.ui.theme.VeriSphereTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Story 6.2 — Compose UI coverage for [UpdateBanner].
 *
 * Stateless composable + lambda-seam pattern means no AppContainer /
 * SecureStorage / ViewModel scaffolding — pass recording lambdas + raw
 * `version` string and assert behaviour directly. Mirrors
 * `HistoryScreenInstrumentedTest` (Story 4.1) and
 * `BatteryOptimizationBottomSheetTest` (Story 5.3) patterns.
 *
 * Method names use underscore_snake_case per the existing androidTest
 * convention (HistoryScreenInstrumentedTest, BatteryOptimizationBottomSheetTest).
 */
class UpdateBannerUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun banner_renders_with_version_in_body_text() {
        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    UpdateBanner(
                        version = "1.2.3",
                        onDownloadClick = {},
                        onDismiss = {},
                    )
                }
            }
        }

        val expected = composeTestRule.activity.getString(R.string.update_banner_text, "1.2.3")
        composeTestRule.onNodeWithText(expected).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TEST_TAG_BANNER).assertIsDisplayed()
    }

    @Test
    fun banner_dismiss_icon_invokes_onDismiss_exactly_once() {
        var dismissCount = 0

        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    UpdateBanner(
                        version = "0.2.0",
                        onDownloadClick = {},
                        onDismiss = { dismissCount++ },
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(TEST_TAG_DISMISS).performClick()

        assertEquals(1, dismissCount)
    }

    @Test
    fun banner_download_button_invokes_onDownloadClick_exactly_once() {
        var downloadCount = 0

        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    UpdateBanner(
                        version = "0.2.0",
                        onDownloadClick = { downloadCount++ },
                        onDismiss = {},
                    )
                }
            }
        }

        val downloadLabel =
            composeTestRule.activity.getString(R.string.update_banner_download)
        composeTestRule.onNodeWithText(downloadLabel).performClick()

        assertEquals(1, downloadCount)
    }
}
