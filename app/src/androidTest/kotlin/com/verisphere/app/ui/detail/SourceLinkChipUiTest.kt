package com.verisphere.app.ui.detail

import android.content.Intent
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.ui.theme.VeriSphereTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Story 2.1 — Compose UI coverage for [SourceLinkChip] click wiring +
 * accessibility announcement. Lambda-seam pattern (Critical Dev Note #5):
 * we assert the `onClick` callback fires on tap and that TalkBack would
 * read the merged content description. We deliberately do NOT verify the
 * Intent firing (would require `espresso-intents`, out of scope per AC
 * #11). Story 2.3's manual smoke covers the full chip → browser hop.
 *
 * Test method names use underscores per existing androidTest convention
 * ([`CaptureFlowSmokeTest`](../../capture/CaptureFlowSmokeTest.kt),
 * [`GeminiAssetsInstrumentedTest`](../../gemini/GeminiAssetsInstrumentedTest.kt)).
 */
class SourceLinkChipUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun chip_click_invokes_on_click_lambda() {
        var clickCount = 0
        val citation = SourceCitation(
            title = "Story headline",
            url = "https://www.bbc.com/news/world-12345",
            publisher = "BBC News",
            dateYearMonth = "2026-04",
        )

        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    SourceLinkChip(
                        citation = citation,
                        onClick = { clickCount++ },
                    )
                }
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Source: BBC News, 2026-04, opens in browser")
            .performClick()

        assertEquals(1, clickCount)
    }

    @Test
    fun chip_renders_label_with_dot_separator_when_date_is_present() {
        val citation = SourceCitation(
            title = "Headline",
            url = "https://www.lemonde.fr/article/42",
            publisher = "Le Monde",
            dateYearMonth = "2026-03",
        )

        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    SourceLinkChip(citation = citation, onClick = {})
                }
            }
        }

        // Label format: "lemonde.fr · 2026-03". The middle-dot is a
        // single Unicode codepoint U+00B7.
        composeTestRule
            .onAllNodes(hasText("lemonde.fr · 2026-03", substring = false))
            .assertCountEquals(1)
    }

    @Test
    fun chip_drops_date_separator_when_date_is_null() {
        val citation = SourceCitation(
            title = "Encyclopaedia article",
            url = "https://en.wikipedia.org/wiki/Whatever",
            publisher = "Wikipedia",
            dateYearMonth = null,
        )

        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    SourceLinkChip(citation = citation, onClick = {})
                }
            }
        }

        // Visible label is the bare domain (no separator).
        composeTestRule
            .onAllNodes(hasText("en.wikipedia.org", substring = false))
            .assertCountEquals(1)

        // And the separator-form must NOT be present anywhere on screen.
        composeTestRule
            .onAllNodes(hasText(" · ", substring = true))
            .assertCountEquals(0)
    }

    @Test
    fun chip_announces_publisher_and_browser_hint_for_a11y_with_no_date() {
        val citation = SourceCitation(
            title = "Article",
            url = "https://en.wikipedia.org/wiki/Whatever",
            publisher = "Wikipedia",
            dateYearMonth = null,
        )

        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    SourceLinkChip(citation = citation, onClick = {})
                }
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Source: Wikipedia, opens in browser")
            .assertExists()
    }

    // ----- buildSourceLinkIntent -----------------------------------------
    //
    // Code-review patch P1 — the Intent factory is impossible to cover
    // from the JVM unit-test runtime (Android stub returns null/0 for
    // Intent accessors under `unitTests.isReturnDefaultValues = true`),
    // so its three correctness invariants live as instrumented tests
    // here. The instrumented runtime exposes the real Android Intent
    // class, so action / dataString / flags accessors return proper
    // values.

    @Test
    fun buildSourceLinkIntent_constructs_an_action_view_intent_targeting_the_url() {
        val url = "https://www.bbc.com/news/world-12345"

        val intent = buildSourceLinkIntent(url)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.dataString)
    }

    @Test
    fun buildSourceLinkIntent_preserves_query_and_fragment_on_the_data_uri() {
        val url = "https://www.lemonde.fr/article/42?utm_source=verisphere#section-3"

        val intent = buildSourceLinkIntent(url)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.dataString)
    }

    @Test
    fun buildSourceLinkIntent_sets_flag_activity_new_task_for_non_activity_call_sites() {
        val url = "https://news.bbc.co.uk/article/42"

        val intent = buildSourceLinkIntent(url)

        // Bitwise AND so the assertion stays tolerant of additional flags
        // being added to the factory in future stories.
        assertTrue(
            "Intent must carry FLAG_ACTIVITY_NEW_TASK",
            (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) == Intent.FLAG_ACTIVITY_NEW_TASK,
        )
    }
}
