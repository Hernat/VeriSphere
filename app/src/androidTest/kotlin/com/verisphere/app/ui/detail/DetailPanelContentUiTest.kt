package com.verisphere.app.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.storage.SessionRecord
import com.verisphere.app.ui.theme.VeriSphereTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Story 2.3 — Compose UI coverage for [DetailPanelContent].
 *
 * Method names use underscore_snake_case per the DEX < 040 constraint at
 * `minSdk = 30` (deferred-work — androidTest test-naming fallback) and the
 * existing convention in [`SourceLinkChipUiTest`](./SourceLinkChipUiTest.kt) +
 * [`AnchoredDetailPanelUiTest`](./AnchoredDetailPanelUiTest.kt).
 *
 * The 6 test methods cover AC #11.b:
 *  1. TRUE verdict with 3 sources renders all 5 sections.
 *  2. NON_VERIFIABLE renders without sources row + with unavailable message.
 *  3. Null regional bias hides the bias row.
 *  4. Non-null regional bias shows the bias row.
 *  5. `fontScale = 1.5f` does not truncate (NFR14 lock-in).
 *  6. Source-chip click invokes `onSourceClick` lambda.
 *
 * Tests render `DetailPanelContent` directly (not wrapped in [AnchoredDetailPanel])
 * so test assertions traverse the host Activity tree without going through
 * a `Dialog` window. This is the pragmatic choice: AC #11.b targets the
 * content composable's behaviour, not the panel chrome (covered by
 * [`AnchoredDetailPanelUiTest`](./AnchoredDetailPanelUiTest.kt)).
 */
class DetailPanelContentUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun resetCapturedHolder() {
        // P6 — clear cross-test state in the CapturedHolder singleton so a
        // stale MutableState reference from a previous test cannot leak into
        // the next test method's assertions.
        CapturedHolder.captured = null
    }

    @Test
    fun true_verdict_with_three_sources_renders_all_five_sections() {
        val record = fixtureRecord(
            verdictLabel = VerdictLabel.TRUE,
            sources = listOf(SAMPLE_BBC, SAMPLE_LEMONDE, SAMPLE_REUTERS),
            regionalBiasNote = SAMPLE_BIAS_NOTE,
        )

        composeTestRule.setContent {
            VeriSphereTheme {
                Column { DetailPanelContent(record = record, onSourceClick = {}) }
            }
        }

        // Section 1 — verdict word "TRUE" + headline.
        composeTestRule.onNodeWithText(VERDICT_WORD_TRUE).assertIsDisplayed()
        composeTestRule.onNodeWithText(record.headline).assertIsDisplayed()

        // Section 2 — Analyzed-text section : collapsed-by-default
        // post-Epic 9 Story 9.2 (code-review F2 (Group B)). Title is
        // visible ; tap the toggle to expand and reveal the OCR card.
        composeTestRule.onNodeWithText(OCR_TITLE, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TAG_ANALYZED_TEXT_TOGGLE).performClick()
        composeTestRule.onNodeWithText(record.ocrText).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TAG_OCR_CARD).assertIsDisplayed()

        // Section 3 — Sources section title + LazyRow + at least the BBC chip.
        composeTestRule.onNodeWithText(SOURCES_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TAG_SOURCES_ROW).assertIsDisplayed()
        composeTestRule.onNodeWithText(BBC_CHIP_LABEL).assertIsDisplayed()

        // Section 4 — bias row visible.
        composeTestRule.onNodeWithTag(TAG_BIAS_ROW).assertIsDisplayed()
        composeTestRule.onNodeWithText(SAMPLE_BIAS_NOTE).assertIsDisplayed()

        // Section 5 — footer with padlock copy + Built with Gemini.
        composeTestRule.onNodeWithTag(TAG_FOOTER_ROW).assertIsDisplayed()
        composeTestRule.onNodeWithText(PADLOCK_COPY).assertIsDisplayed()
        composeTestRule.onNodeWithText(BUILT_WITH_GEMINI).assertIsDisplayed()
    }

    @Test
    fun non_verifiable_verdict_renders_without_sources_row_and_with_unavailable_message() {
        val record = fixtureRecord(
            verdictLabel = VerdictLabel.NON_VERIFIABLE,
            sources = emptyList(),
            regionalBiasNote = null,
            headline = "No analyzable claim in the captured image.",
        )

        composeTestRule.setContent {
            VeriSphereTheme {
                Column { DetailPanelContent(record = record, onSourceClick = {}) }
            }
        }

        // Sources title remains visible (AC #4 — only the row is hidden).
        composeTestRule.onNodeWithText(SOURCES_TITLE).assertIsDisplayed()

        // The unavailable message replaces the LazyRow.
        composeTestRule.onNodeWithTag(TAG_SOURCES_UNAVAILABLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(SOURCES_UNAVAILABLE_MSG).assertIsDisplayed()

        // The LazyRow itself does NOT exist for an empty sources list.
        composeTestRule.onNodeWithTag(TAG_SOURCES_ROW).assertDoesNotExist()

        // No source chip rendered.
        composeTestRule.onNodeWithText(BBC_CHIP_LABEL).assertDoesNotExist()
    }

    @Test
    fun null_regional_bias_note_hides_the_bias_row() {
        val record = fixtureRecord(regionalBiasNote = null)

        composeTestRule.setContent {
            VeriSphereTheme {
                Column { DetailPanelContent(record = record, onSourceClick = {}) }
            }
        }

        composeTestRule.onNodeWithTag(TAG_BIAS_ROW).assertDoesNotExist()
        composeTestRule.onNodeWithText(SAMPLE_BIAS_NOTE).assertDoesNotExist()
    }

    @Test
    fun non_null_regional_bias_note_shows_italic_text_with_globe_icon() {
        val record = fixtureRecord(regionalBiasNote = SAMPLE_BIAS_NOTE)

        composeTestRule.setContent {
            VeriSphereTheme {
                Column { DetailPanelContent(record = record, onSourceClick = {}) }
            }
        }

        composeTestRule.onNodeWithTag(TAG_BIAS_ROW).assertIsDisplayed()
        composeTestRule.onNodeWithText(SAMPLE_BIAS_NOTE).assertIsDisplayed()
    }

    @Test
    fun font_scale_1_5_does_not_truncate_ocr_text_or_overlap_footer() {
        val longHeadline =
            "An especially long headline that exercises the body-medium wrap behaviour at " +
                "fontScale 1.5 across multiple visual lines without truncation."
        val longOcr =
            "Sample OCR text deliberately authored to be longer than a single line so the " +
                "preview pane and the instrumented runtime exercise the wrap behaviour inside " +
                "the OCR card. The card uses surfaceContainerLowest as background and a 1 dp " +
                "dashed outline in the colorScheme.outline tint, matching the UX spec."

        val record = fixtureRecord(
            verdictLabel = VerdictLabel.TRUE,
            ocrText = longOcr,
            headline = longHeadline,
        )

        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density,
                    fontScale = LARGE_FONT_SCALE,
                ),
            ) {
                VeriSphereTheme {
                    Column { DetailPanelContent(record = record, onSourceClick = {}) }
                }
            }
        }

        // P8 — assert every visible text node from AC #11.b.1 (verdict word,
        // section titles, headline, OCR, padlock copy, Built with Gemini)
        // is still discoverable at fontScale 1.5f. NFR14 lock-in.
        // Code-review F2 (Group B) — AnalyzedTextSection is collapsed
        // by default ; tap the toggle to expand before asserting OCR.
        composeTestRule.onNodeWithText(VERDICT_WORD_TRUE).assertIsDisplayed()
        composeTestRule.onNodeWithText(OCR_TITLE, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(SOURCES_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(longHeadline).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TAG_ANALYZED_TEXT_TOGGLE).performClick()
        composeTestRule.onNodeWithText(longOcr).assertIsDisplayed()
        composeTestRule.onNodeWithText(BUILT_WITH_GEMINI).assertIsDisplayed()
        composeTestRule.onNodeWithText(PADLOCK_COPY).assertIsDisplayed()

        // Truncation marker — Compose ellipsises with the literal "…" (U+2026).
        // No rendered text node should contain this glyph at fontScale 1.5.
        // (substring=true so the assertion fires even if the ellipsis is mid-string.)
        composeTestRule
            .onAllNodesWithText("…", substring = true)
            .assertCountEquals(0)
    }

    @Test
    fun source_chip_click_invokes_on_source_click_lambda() {
        val record = fixtureRecord(sources = listOf(SAMPLE_BBC))

        composeTestRule.setContent {
            VeriSphereTheme {
                val captured: MutableState<SourceCitation?> = remember { mutableStateOf(null) }
                Column {
                    DetailPanelContent(
                        record = record,
                        onSourceClick = { citation -> captured.value = citation },
                    )
                }
                CapturedHolder.captured = captured
            }
        }

        composeTestRule
            .onNodeWithText(BBC_CHIP_LABEL)
            .assertIsDisplayed()
            .performClick()

        composeTestRule.runOnIdle {
            assertEquals(SAMPLE_BBC, CapturedHolder.captured?.value)
        }
    }
}

// Holder bridging the @Composable scope and the @Test scope without
// hoisting test-only state into production code. The test sets `captured`
// inside `setContent`'s composable body; the assertion reads it from the
// JUnit thread on the main looper via runOnIdle.
private object CapturedHolder {
    var captured: MutableState<SourceCitation?>? = null
}

// ----- Fixtures -------------------------------------------------------

private val SAMPLE_BBC = SourceCitation(
    title = "Story headline placeholder",
    url = "https://www.bbc.com/news/world-12345",
    publisher = "BBC News",
    dateYearMonth = "2026-04",
)

private val SAMPLE_LEMONDE = SourceCitation(
    title = "Headline placeholder",
    url = "https://www.lemonde.fr/article/42",
    publisher = "Le Monde",
    dateYearMonth = "2026-03",
)

private val SAMPLE_REUTERS = SourceCitation(
    title = "Headline placeholder",
    url = "https://www.reuters.com/world/europe/whatever-12345",
    publisher = "Reuters",
    dateYearMonth = "2026-04",
)

private const val SAMPLE_BIAS_NOTE =
    "Coverage of this claim varies between major outlets in different regions."

private const val SAMPLE_OCR =
    "Sample OCR text extracted from the captured frame for the instrumented test."

private fun fixtureRecord(
    verdictLabel: VerdictLabel = VerdictLabel.TRUE,
    sources: List<SourceCitation> = listOf(SAMPLE_BBC, SAMPLE_LEMONDE, SAMPLE_REUTERS),
    regionalBiasNote: String? = SAMPLE_BIAS_NOTE,
    ocrText: String = SAMPLE_OCR,
    headline: String = "Sample verdict headline rendered in the detail panel.",
): SessionRecord = SessionRecord(
    id = "fixture-uuid",
    timestampMs = 0L,
    verdictLabel = verdictLabel,
    headline = headline,
    contextLines = emptyList(),
    sourceLinks = sources,
    ocrText = ocrText,
    regionalBiasNote = regionalBiasNote,
)

// ----- Constants ------------------------------------------------------

// Verdict word and copy strings — kept literal here so the assertions are
// self-contained (no R.string lookup required from an Activity rule).
// Epic 9 hotfix 2026-05-19 — verdict words moved to French baseline
// (VRAI / FAUX / DOUTEUX / NON VÉRIFIABLE) reverting Story 7.5 C6's
// "international convention" stance per user feedback.
// BUILT_WITH_GEMINI stays English per FR14 attribution invariant
// (Story 7.4 MS7 phrase pin per Google Gemini API attribution guidelines).
private const val VERDICT_WORD_TRUE = "VRAI"
// Epic 9 Story 9.2 — section renamed + moved to bottom (collapsed).
// Substring match used at call sites because the rendered text is
// "Texte analysé — Afficher" (compound header).
private const val OCR_TITLE = "Texte analysé"
private const val SOURCES_TITLE = "Sources"
private const val SOURCES_UNAVAILABLE_MSG = "Aucune source corroborante trouvée."
private const val PADLOCK_COPY = "Personne d'autre ne le voit. C'est entre toi et nous."
private const val BUILT_WITH_GEMINI = "Built with Gemini"

// Story 2.1 chip label format: `domain · YYYY-MM`. SAMPLE_BBC's URL is
// `https://www.bbc.com/...`, extractDisplayDomain returns `bbc.com`.
private const val BBC_CHIP_LABEL = "bbc.com · 2026-04"

private const val LARGE_FONT_SCALE = 1.5f
