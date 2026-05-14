package com.verisphere.app.ui.history

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * Compose UI tests for [HistoryItemRow] (Story 4.2). The composable is
 * pure stateless — no `ViewModel`, no `AppContainer`. Tests instantiate
 * the row directly with hand-built [SessionRecord] fixtures and verify
 * (a) row click forwarding, (b) per-label verdict word rendering via
 * resource lookup (locale-safe), (c) headline preview presence,
 * (d) long-headline node-presence (visual ellipsis verification deferred
 * to `@Preview` per Story 2.3 deferred-work line 154 — programmatic
 * `onTextLayout`/`hasVisualOverflow` check is V2 hardening),
 * (e) merged-semantics a11y label substring match (locale-invariant),
 * (f) emoji slot visibility (defends against `Modifier.alpha(0f)`
 * regressions).
 *
 * Method naming uses underscores per the existing androidTest convention
 * (`SourceLinkChipUiTest`, `HistoryScreenInstrumentedTest`).
 */
class HistoryItemRowUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun sampleRecord(
        label: VerdictLabel,
        headline: String = "Sample headline",
        timestampMs: Long = 1_000_000L,
        id: String = "rec-${label.name.lowercase()}",
    ): SessionRecord =
        SessionRecord(
            id = id,
            timestampMs = timestampMs,
            verdictLabel = label,
            headline = headline,
            contextLines = emptyList(),
            sourceLinks = emptyList<SourceCitation>(),
            ocrText = "",
            regionalBiasNote = null,
            injectionDetected = false,
        )

    @Test
    fun history_item_row_invokes_on_click_with_record_id() {
        val captured = mutableListOf<String>()
        val record = sampleRecord(
            label = VerdictLabel.TRUE,
            headline = "Click target",
            id = "rec-xyz",
        )

        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    HistoryItemRow(
                        record = record,
                        onClick = { captured += record.id },
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Click target").performClick()

        assertEquals(listOf("rec-xyz"), captured)
    }

    @Test
    fun history_item_row_renders_verdict_word_for_each_label() {
        // Patch P6 (code review 2026-05-14) — single setContent renders
        // all 4 labels at once (one row per label inside a Column);
        // assertions iterate after composition completes. Avoids the
        // multi-setContent pattern (fragile across Compose versions per
        // BH#15 / EC#10). Strings loaded via
        // composeTestRule.activity.getString(...) so the assertion
        // follows the resource (Story 2.1 deferred-work line 174 i18n
        // pattern — avoids hardcoded English).
        val labels = VerdictLabel.entries
        val recordsByLabel = labels.associateWith { label ->
            sampleRecord(
                label = label,
                headline = "headline-for-${label.name}",
            )
        }

        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    Column {
                        labels.forEach { label ->
                            HistoryItemRow(
                                record = recordsByLabel.getValue(label),
                                onClick = {},
                            )
                        }
                    }
                }
            }
        }

        labels.forEach { label ->
            val expectedWordRes = when (label) {
                VerdictLabel.TRUE -> R.string.flash_verdict_true
                VerdictLabel.FALSE -> R.string.flash_verdict_false
                VerdictLabel.DOUBTFUL -> R.string.flash_verdict_doubtful
                VerdictLabel.NON_VERIFIABLE -> R.string.flash_verdict_non_verifiable
            }
            val expectedWord = composeTestRule.activity.getString(expectedWordRes)
            composeTestRule.onNodeWithText(expectedWord).assertIsDisplayed()
        }
    }

    @Test
    fun history_item_row_renders_headline_preview() {
        val record = sampleRecord(
            label = VerdictLabel.DOUBTFUL,
            headline = "Sample claim under verification",
        )

        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    HistoryItemRow(record = record, onClick = {})
                }
            }
        }

        composeTestRule
            .onNodeWithText("Sample claim under verification")
            .assertIsDisplayed()
    }

    /**
     * Long-headline node-presence assertion.
     *
     * **Patch P3 (code review 2026-05-14)** — renamed from
     * `..._composes_with_long_headline` for honesty: the test name
     * previously implied truncation verification, but the actual
     * assertion is semantics-tree node-presence (Compose's semantics
     * tree carries the full string regardless of `maxLines = 1` +
     * `Ellipsis`, so substring-matching the full string only proves
     * the node composed — NOT that ellipsis fired).
     *
     * AC #11.4 + Story 2.3 deferred-work line 154 precedent:
     * programmatic "visually truncated to 1 line" requires an
     * `onTextLayout` callback + `hasVisualOverflow` assertion. That
     * rewrite is intrusive and deferred to V2 hardening. For V1 we
     * assert the node composes without crashing and rely on the
     * long-headline `@Preview` for visual verification of ellipsis
     * (matches Story 2.3 testing budget).
     */
    @Test
    fun history_item_row_composes_with_full_string_in_semantics_when_headline_is_long() {
        val longHeadline = buildString {
            repeat(10) {
                append(
                    "This is a deliberately long Gemini headline crafted to verify " +
                        "the bodyMedium maxLines=1 ellipsis behaviour on the row. ",
                )
            }
        }.trim()
        val record = sampleRecord(
            label = VerdictLabel.NON_VERIFIABLE,
            headline = longHeadline,
        )

        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    HistoryItemRow(record = record, onClick = {})
                }
            }
        }

        // Substring match — at maxLines=1+Ellipsis the visible text in
        // the layout is truncated, but Compose's semantics tree still
        // carries the full string for accessibility purposes. The node
        // exists; ellipsis is preview-verified.
        composeTestRule
            .onNodeWithText(longHeadline, substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun history_item_row_announces_merged_a11y_label_with_verdict_announcement() {
        // Substring match for the verdict-announcement portion of the
        // merged TalkBack utterance. Full label looks like
        //   "Verdict: true. Sample headline. 0 minutes ago"
        // but the timestamp string depends on System.currentTimeMillis()
        // at test time, so we substring-match the locale-invariant
        // verdict-announcement prefix.
        val record = sampleRecord(
            label = VerdictLabel.TRUE,
            headline = "Sample headline",
        )

        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    HistoryItemRow(record = record, onClick = {})
                }
            }
        }

        val expectedSubstring = composeTestRule.activity.getString(
            R.string.bubble_verdict_true_content_description,
        )
        composeTestRule
            .onNodeWithContentDescription(expectedSubstring, substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun history_item_row_renders_emoji_for_true_label() {
        // One emoji test is sufficient (AC #11.6) — the 4-label loop
        // in #2 already validates each code-path; this defends against
        // an accidental Modifier.alpha(0f) regression on the emoji slot.
        val record = sampleRecord(label = VerdictLabel.TRUE)

        composeTestRule.setContent {
            VeriSphereTheme {
                Surface {
                    HistoryItemRow(record = record, onClick = {})
                }
            }
        }

        composeTestRule.onNodeWithText("✅").assertIsDisplayed()
    }
}
