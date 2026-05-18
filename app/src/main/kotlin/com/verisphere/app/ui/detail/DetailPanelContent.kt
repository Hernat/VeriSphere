package com.verisphere.app.ui.detail

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.verisphere.app.R
import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.storage.SessionRecord
import com.verisphere.app.ui.theme.VeriSphereTheme

/**
 * Story 2.3 — content composable that fills [AnchoredDetailPanel]'s slot.
 * Renders, top-to-bottom: (1) verdict row (emoji + word) and headline,
 * (2) "What was read" OCR card, (3) "Sources" row of [SourceLinkChip]
 * (or an unavailable message when the verdict carries no sources),
 * (4) the conditional regional-bias row, and (5) the privacy + Built-with-Gemini
 * footer.
 *
 * **Sizing contract** — declared as a `ColumnScope.()` extension so the 5
 * sections drop directly as siblings of the receiver column. [AnchoredDetailPanel]'s
 * slot mounts a `Column(modifier = Modifier.padding(16.dp), verticalArrangement =
 * Arrangement.spacedBy(16.dp))` (Story 2.2 AC #6); this composable adds NO outer
 * wrapper `Column`, NO outer `Modifier.padding`, NO outer `Arrangement.spacedBy`.
 *
 * **Lambda-seam** ([Story 2.1 / 2.2 precedent]) — no [android.content.Context]
 * parameter. [onSourceClick] is invoked with the tapped citation; the call
 * site (Story 2.4) wires `LocalContext.current.startActivity(...)` inside
 * a `runCatching` to handle [android.content.ActivityNotFoundException].
 *
 * **Accessibility** (UX-DR18, NFR12, NFR14):
 *  - Verdict row uses merged semantics (TalkBack reads "Verdict: true. {headline}").
 *  - Section titles ("What was read", "Sources") carry `Modifier.semantics { heading() }`
 *    for TalkBack heading-navigation.
 *  - Footer renders with merged semantics so TalkBack speaks the privacy +
 *    attribution line as a single utterance instead of pronouncing each
 *    emoji and the em-dash separately.
 *  - All section copy lives in `strings_verdict.xml` (UX-DR17 verbatim).
 *  - The footer renders in [FlowRow] so 12-sp `labelMedium` copy wraps
 *    cleanly at `fontScale = 1.5f` without overlap (NFR14 lock-in).
 *
 * **Closes:** UX-DR8 content layer; UX-DR17 (panel copy); FR7 (OCR rendered);
 * FR14 (every panel component present); FR26 (NON-VERIFIABLE explanation).
 *
 * @param record Verification session record produced by `GeminiClient.verify`
 *               and persisted by `HistoryRepository.append` (Story 1.10).
 * @param onSourceClick Invoked when the user taps a [SourceLinkChip].
 *                      Story 2.4 binds this to the source-link Intent launch.
 */
@Composable
fun ColumnScope.DetailPanelContent(
    record: SessionRecord,
    onSourceClick: (SourceCitation) -> Unit,
) {
    VerdictRow(label = record.verdictLabel, headline = record.headline)
    OcrSection(ocrText = record.ocrText)
    SourcesSection(sources = record.sourceLinks, onSourceClick = onSourceClick)
    RegionalBiasRow(note = record.regionalBiasNote)
    FooterRow()
}

// ----- Section 1: Verdict row -----------------------------------------

@Composable
private fun VerdictRow(label: VerdictLabel, headline: String) {
    val verdictWord = stringResource(verdictWordResFor(label))
    val verdictAnnouncement = stringResource(verdictContentDescriptionFor(label))
    // P3 — when `headline` is blank, join the announcement without the trailing
    // ". " separator so TalkBack does not read "Verdict: true. " with a
    // dangling period, and skip the (empty) bodyMedium Text below so it does
    // not consume a line of layout space.
    val rowAnnouncement = if (headline.isBlank()) {
        verdictAnnouncement
    } else {
        "$verdictAnnouncement. $headline"
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .testTag(TAG_VERDICT_ROW)
                .semantics(mergeDescendants = true) {
                    contentDescription = rowAnnouncement
                },
        ) {
            Text(
                text = verdictEmojiFor(label),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = verdictWord,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (headline.isNotBlank()) {
            Text(
                text = headline,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ----- Section 2: OCR card --------------------------------------------

@Composable
private fun OcrSection(ocrText: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.detail_ocr_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        OcrCard(text = ocrText)
    }
}

@Composable
private fun OcrCard(text: String) {
    val borderColor = MaterialTheme.colorScheme.outline
    val density = LocalDensity.current
    val strokePx = with(density) { 1.dp.toPx() }
    val cornerPx = with(density) { OCR_CARD_CORNER_DP.dp.toPx() }
    // P7 — key the dashEffect on density so a fold/unfold or density-changing
    // configuration change recomputes the effect and the dash intervals stay
    // consistent with the recomputed strokePx/cornerPx.
    val dashEffect = remember(density) { PathEffect.dashPathEffect(floatArrayOf(8f, 4f), 0f) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .testTag(TAG_OCR_CARD)
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = borderColor,
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                    style = Stroke(width = strokePx, pathEffect = dashEffect),
                )
            },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

// ----- Section 3: Sources row -----------------------------------------

@Composable
private fun SourcesSection(
    sources: List<SourceCitation>,
    onSourceClick: (SourceCitation) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.detail_sources_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (sources.isEmpty()) {
            Text(
                text = stringResource(R.string.detail_sources_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TAG_SOURCES_UNAVAILABLE),
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag(TAG_SOURCES_ROW),
            ) {
                // P2 — composite key on (index, url) defensively handles the
                // edge case where Gemini Search Grounding returns two citations
                // with the same URL or an empty-string URL; `LazyRow` would
                // otherwise throw IllegalArgumentException on duplicate keys.
                itemsIndexed(
                    items = sources,
                    key = { index, citation -> "$index-${citation.url}" },
                ) { _, citation ->
                    SourceLinkChip(
                        citation = citation,
                        onClick = { onSourceClick(citation) },
                    )
                }
            }
        }
    }
}

// ----- Section 4: Regional-bias row -----------------------------------

@Composable
private fun RegionalBiasRow(note: String?) {
    // P5 — treat an empty-string non-null `regionalBiasNote` the same as null
    // so a malformed Gemini response does not render an orphan globe icon
    // with no accompanying text.
    note?.takeIf { it.isNotBlank() }?.let { biasNote ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.testTag(TAG_BIAS_ROW),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_globe),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = biasNote,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

// ----- Section 5: Footer ----------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FooterRow() {
    val labelStyle = MaterialTheme.typography.labelMedium
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val padlockCopy = stringResource(R.string.detail_padlock_copy)
    val poweredBy = stringResource(R.string.detail_built_with_gemini)

    // P4 — merged-semantics announcement so TalkBack reads the privacy +
    // attribution as a single utterance instead of pronouncing each emoji
    // ("Locked. ... Em dash. ...") separately.
    val footerAnnouncement = "$padlockCopy $poweredBy"

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .testTag(TAG_FOOTER_ROW)
            .semantics(mergeDescendants = true) {
                contentDescription = footerAnnouncement
            },
    ) {
        // P4 — each emoji is paired with its body copy in an inner `Row` so
        // the two visual halves never wrap separately at fontScale = 1.5f
        // narrow widths. `FlowRow` may still wrap the two pairs to two lines,
        // but the padlock stays glued to its copy and the em-dash stays glued
        // to "Built with Gemini".
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "🔒", style = labelStyle)
            Text(text = padlockCopy, style = labelStyle, color = mutedColor)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "—", style = labelStyle, color = mutedColor)
            Text(text = poweredBy, style = labelStyle, color = mutedColor)
        }
    }
}

// ----- Helpers --------------------------------------------------------

@StringRes
private fun verdictWordResFor(label: VerdictLabel): Int = when (label) {
    VerdictLabel.TRUE -> R.string.flash_verdict_true
    VerdictLabel.FALSE -> R.string.flash_verdict_false
    VerdictLabel.DOUBTFUL -> R.string.flash_verdict_doubtful
    VerdictLabel.NON_VERIFIABLE -> R.string.flash_verdict_non_verifiable
}

@StringRes
private fun verdictContentDescriptionFor(label: VerdictLabel): Int = when (label) {
    VerdictLabel.TRUE -> R.string.bubble_verdict_true_content_description
    VerdictLabel.FALSE -> R.string.bubble_verdict_false_content_description
    VerdictLabel.DOUBTFUL -> R.string.bubble_verdict_doubtful_content_description
    VerdictLabel.NON_VERIFIABLE -> R.string.bubble_verdict_non_verifiable_content_description
}

private fun verdictEmojiFor(label: VerdictLabel): String = when (label) {
    VerdictLabel.TRUE -> "✅"
    VerdictLabel.FALSE -> "❌"
    VerdictLabel.DOUBTFUL -> "⚠️"
    VerdictLabel.NON_VERIFIABLE -> "⚪"
}

// ----- Constants ------------------------------------------------------

/** Test tags for instrumented tests (see DetailPanelContentUiTest). */
internal const val TAG_VERDICT_ROW = "vs_detail_verdict_row"
internal const val TAG_OCR_CARD = "vs_detail_ocr_card"
internal const val TAG_SOURCES_ROW = "vs_detail_sources_row"
internal const val TAG_SOURCES_UNAVAILABLE = "vs_detail_sources_unavailable"
internal const val TAG_BIAS_ROW = "vs_detail_bias_row"
internal const val TAG_FOOTER_ROW = "vs_detail_footer_row"

/** OCR-card corner radius in dp. Mirrors `MaterialTheme.shapes.small`'s
 *  default 4 dp; pulled out as a constant so the hand-drawn dashed border
 *  in [OcrCard] aligns with the Surface's clipped shape. */
private const val OCR_CARD_CORNER_DP = 4

// ----- Previews -------------------------------------------------------

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

private const val PREVIEW_OCR_SHORT =
    "Sample OCR text extracted from the captured frame for the preview pane."

private const val PREVIEW_OCR_LONG =
    "Sample OCR text extracted from the captured frame for the preview pane. " +
        "This block is deliberately longer than a single line so the preview pane " +
        "exercises wrap behaviour inside the OCR card. The card uses surfaceContainerLowest " +
        "as background and a 1 dp dashed outline in the colorScheme.outline tint, matching " +
        "the UX spec § Component Strategy AnchoredDetailPanel content order."

private const val PREVIEW_BIAS_NOTE =
    "Coverage of this claim varies between major outlets in different regions."

private fun previewRecord(
    verdictLabel: VerdictLabel = VerdictLabel.TRUE,
    sources: List<SourceCitation> = listOf(SAMPLE_BBC, SAMPLE_LEMONDE, SAMPLE_REUTERS),
    regionalBiasNote: String? = PREVIEW_BIAS_NOTE,
    ocrText: String = PREVIEW_OCR_SHORT,
    headline: String = "Headline placeholder for the verdict in the detail panel.",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewHost(content: @Composable ColumnScope.() -> Unit) {
    AnchoredDetailPanelImpl(
        useStandardBottomSheet = true,
        edge = EmergenceEdge.BOTTOM,
        isVisible = true,
        onDismiss = {},
        content = content,
    )
}

@Preview(
    name = "TRUE, light",
    showSystemUi = true,
    device = "spec:width=400dp,height=800dp",
)
@Composable
private fun DetailPanelContentTrueLightPreview() {
    VeriSphereTheme(darkTheme = false) {
        PreviewHost {
            DetailPanelContent(
                record = previewRecord(verdictLabel = VerdictLabel.TRUE),
                onSourceClick = {},
            )
        }
    }
}

@Preview(
    name = "TRUE, dark",
    showSystemUi = true,
    device = "spec:width=400dp,height=800dp",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DetailPanelContentTrueDarkPreview() {
    VeriSphereTheme(darkTheme = true) {
        PreviewHost {
            DetailPanelContent(
                record = previewRecord(verdictLabel = VerdictLabel.TRUE),
                onSourceClick = {},
            )
        }
    }
}

@Preview(
    name = "FALSE, light",
    showSystemUi = true,
    device = "spec:width=400dp,height=800dp",
)
@Composable
private fun DetailPanelContentFalseLightPreview() {
    VeriSphereTheme(darkTheme = false) {
        PreviewHost {
            DetailPanelContent(
                record = previewRecord(
                    verdictLabel = VerdictLabel.FALSE,
                    sources = listOf(SAMPLE_BBC, SAMPLE_LEMONDE),
                ),
                onSourceClick = {},
            )
        }
    }
}

@Preview(
    name = "FALSE, dark",
    showSystemUi = true,
    device = "spec:width=400dp,height=800dp",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DetailPanelContentFalseDarkPreview() {
    VeriSphereTheme(darkTheme = true) {
        PreviewHost {
            DetailPanelContent(
                record = previewRecord(
                    verdictLabel = VerdictLabel.FALSE,
                    sources = listOf(SAMPLE_BBC, SAMPLE_LEMONDE),
                ),
                onSourceClick = {},
            )
        }
    }
}

@Preview(
    name = "DOUBTFUL, light",
    showSystemUi = true,
    device = "spec:width=400dp,height=800dp",
)
@Composable
private fun DetailPanelContentDoubtfulLightPreview() {
    VeriSphereTheme(darkTheme = false) {
        PreviewHost {
            DetailPanelContent(
                record = previewRecord(
                    verdictLabel = VerdictLabel.DOUBTFUL,
                    sources = listOf(SAMPLE_BBC),
                    regionalBiasNote = null,
                ),
                onSourceClick = {},
            )
        }
    }
}

@Preview(
    name = "DOUBTFUL, dark",
    showSystemUi = true,
    device = "spec:width=400dp,height=800dp",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DetailPanelContentDoubtfulDarkPreview() {
    VeriSphereTheme(darkTheme = true) {
        PreviewHost {
            DetailPanelContent(
                record = previewRecord(
                    verdictLabel = VerdictLabel.DOUBTFUL,
                    sources = listOf(SAMPLE_BBC),
                    regionalBiasNote = null,
                ),
                onSourceClick = {},
            )
        }
    }
}

@Preview(
    name = "NON_VERIFIABLE, light",
    showSystemUi = true,
    device = "spec:width=400dp,height=800dp",
)
@Composable
private fun DetailPanelContentNonVerifiableLightPreview() {
    VeriSphereTheme(darkTheme = false) {
        PreviewHost {
            DetailPanelContent(
                record = previewRecord(
                    verdictLabel = VerdictLabel.NON_VERIFIABLE,
                    sources = emptyList(),
                    regionalBiasNote = null,
                    headline = "No analyzable claim in the captured image.",
                ),
                onSourceClick = {},
            )
        }
    }
}

@Preview(
    name = "NON_VERIFIABLE, dark",
    showSystemUi = true,
    device = "spec:width=400dp,height=800dp",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DetailPanelContentNonVerifiableDarkPreview() {
    VeriSphereTheme(darkTheme = true) {
        PreviewHost {
            DetailPanelContent(
                record = previewRecord(
                    verdictLabel = VerdictLabel.NON_VERIFIABLE,
                    sources = emptyList(),
                    regionalBiasNote = null,
                    headline = "No analyzable claim in the captured image.",
                ),
                onSourceClick = {},
            )
        }
    }
}

@Preview(
    name = "Large fontScale 1.5",
    showSystemUi = true,
    device = "spec:width=400dp,height=800dp",
    fontScale = 1.5f,
)
@Composable
private fun DetailPanelContentLargeFontScalePreview() {
    VeriSphereTheme(darkTheme = false) {
        PreviewHost {
            DetailPanelContent(
                record = previewRecord(
                    verdictLabel = VerdictLabel.TRUE,
                    ocrText = PREVIEW_OCR_LONG,
                    headline = "An especially long headline that exercises the body-medium wrap behaviour at fontScale 1.5.",
                ),
                onSourceClick = {},
            )
        }
    }
}

@Preview(
    name = "Null bias note (TRUE)",
    showSystemUi = true,
    device = "spec:width=400dp,height=800dp",
)
@Composable
private fun DetailPanelContentNullBiasPreview() {
    VeriSphereTheme(darkTheme = false) {
        PreviewHost {
            DetailPanelContent(
                record = previewRecord(
                    verdictLabel = VerdictLabel.TRUE,
                    sources = listOf(SAMPLE_BBC, SAMPLE_LEMONDE),
                    regionalBiasNote = null,
                ),
                onSourceClick = {},
            )
        }
    }
}
