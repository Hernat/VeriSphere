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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.verisphere.app.R
import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.serp.AgreementVerdict
import com.verisphere.app.serp.SerpReference
import com.verisphere.app.serp.sanitizeSerpMarkdown
import com.verisphere.app.storage.SessionRecord
import com.verisphere.app.ui.theme.VSPalette
import com.verisphere.app.ui.theme.VSSpacing
import com.verisphere.app.ui.theme.VSTypography
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
    // Epic 9 Story 9.2 — restructured layout per plan :
    //   1. VerdictRow         — verdict word + cleaned headline (top)
    //   2. AgreementBadgeRow  — visible only when SerpAPI disagrees
    //   3. SupplementaryInfo  — Gemini contextLines + SerpAPI markdown
    //                           + regionalBiasNote (former RegionalBiasRow
    //                           folded in)
    //   4. SourcesSection     — Gemini sourceLinks + SerpAPI serpReferences
    //                           fused (dedup by URL)
    //   5. AnalyzedTextSection — extractedClaim + raw OCR, collapsed by
    //                           default (moved to bottom)
    //   6. FooterRow          — privacy + "Built with Gemini"
    VerdictRow(label = record.verdictLabel, headline = record.headline)
    AgreementBadgeRow(agreement = record.agreement)
    SupplementaryInfoSection(
        contextLines = record.contextLines,
        serpMarkdown = record.serpMarkdown,
        regionalBiasNote = record.regionalBiasNote,
    )
    SourcesSection(
        geminiSources = record.sourceLinks,
        serpReferences = record.serpReferences,
        onSourceClick = onSourceClick,
    )
    AnalyzedTextSection(
        extractedClaim = record.extractedClaim,
        rawOcrText = record.ocrText,
    )
    FooterRow()
}

// ----- Section 1: Verdict row -----------------------------------------

@Composable
private fun VerdictRow(label: VerdictLabel, headline: String) {
    val verdictWord = stringResource(verdictWordResFor(label))
    val verdictAnnouncement = stringResource(verdictContentDescriptionFor(label))
    // Epic 8 — defensive strip of any "C'EST VRAI :" / "C'EST FAUX :" /
    // "DOUTEUX :" / "NON VÉRIFIABLE :" prefix Gemini may have written
    // (the verdict word is shown above; the prefix is redundant +
    // sometimes contradictory). Idempotent + safe on legacy records.
    val cleanedHeadline = com.verisphere.app.gemini.stripVerdictPrefix(headline, label)
    // P3 — when `headline` is blank, join the announcement without the trailing
    // ". " separator so TalkBack does not read "Verdict: true. " with a
    // dangling period, and skip the (empty) bodyMedium Text below so it does
    // not consume a line of layout space.
    val rowAnnouncement = if (cleanedHeadline.isBlank()) {
        verdictAnnouncement
    } else {
        "$verdictAnnouncement. $cleanedHeadline"
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
        if (cleanedHeadline.isNotBlank()) {
            Text(
                text = cleanedHeadline,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ----- Section 1b: Agreement badge (Epic 9 Story 9.2) -----------------

/**
 * Epic 9 Story 9.2 — agreement badge surfaced when [AgreementScorer]
 * compared Gemini's verdict against SerpAPI's Google synthesis :
 *
 *  - [AgreementVerdict.Disagree] → warm-gold "Sources contradictoires"
 *    badge (code-review F8 — semantic separation from the DOUBTFUL
 *    verdict pastel ; `accent_pulse` is the documented warning token).
 *  - [AgreementVerdict.Agree]    → sage "Sources concordantes" badge
 *    (code-review F24 — symmetric positive signal so the user can
 *    distinguish "Gemini + Google agree" from "no SerpAPI signal").
 *  - [AgreementVerdict.Inconclusive] → silence (legacy pre-Epic-9
 *    records + low-signal cases ; no badge avoids visual noise).
 */
@Composable
private fun AgreementBadgeRow(agreement: AgreementVerdict) {
    when (agreement) {
        AgreementVerdict.Disagree -> AgreementBadgeChrome(
            a11yRes = R.string.detail_agreement_disagree_a11y,
            background = VSPalette.accentPulse,
            foreground = VSPalette.onAccentPulse,
            glyph = "△",
            messageRes = R.string.detail_agreement_disagree,
        )
        AgreementVerdict.Agree -> AgreementBadgeChrome(
            a11yRes = R.string.detail_agreement_agree_a11y,
            background = VSPalette.accentSage,
            foreground = VSPalette.onAccentSage,
            glyph = "✓",
            messageRes = R.string.detail_agreement_agree,
        )
        AgreementVerdict.Inconclusive -> Unit
    }
}

@Composable
private fun AgreementBadgeChrome(
    @androidx.annotation.StringRes a11yRes: Int,
    background: androidx.compose.ui.graphics.Color,
    foreground: androidx.compose.ui.graphics.Color,
    glyph: String,
    @androidx.annotation.StringRes messageRes: Int,
) {
    val a11yLabel = stringResource(a11yRes)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_AGREEMENT_BADGE)
            .semantics(mergeDescendants = true) { contentDescription = a11yLabel },
        color = background,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VSSpacing.space12),
            modifier = Modifier.padding(
                horizontal = VSSpacing.space16,
                vertical = VSSpacing.space12,
            ),
        ) {
            Text(text = glyph, style = VSTypography.titleSerif, color = foreground)
            Text(text = stringResource(messageRes), style = VSTypography.bodySans, color = foreground)
        }
    }
}

// ----- Section 2: Supplementary info (Epic 9 Story 9.2) --------------

/**
 * Epic 9 Story 9.2 — fuses three former / new info streams under one
 * "Information supplémentaire" heading :
 *  - Gemini's `contextLines` bullets (was rendered as separate bullets
 *    in V1 ; if absent, this row is skipped),
 *  - SerpAPI's `reconstructed_markdown` paragraph under a "Synthèse
 *    Google" sub-title (only when non-blank),
 *  - the regional-bias italic note (formerly its own RegionalBiasRow ;
 *    folded in here so the section count drops by one).
 *
 * When all three are empty, the section renders nothing — no orphan
 * heading.
 */
@Composable
private fun SupplementaryInfoSection(
    contextLines: List<String>,
    serpMarkdown: String,
    regionalBiasNote: String?,
) {
    val hasContextLines = contextLines.any { it.isNotBlank() }
    // Epic 9 hotfix 2026-05-19 — sanitize the raw SerpAPI markdown
    // (tables, backslash-escapes, References block, citation markers) into
    // clean plain text. remember() keyed on the raw payload so the
    // (cheap) regex pipeline isn't re-run on every recomposition.
    val cleanedSerpMarkdown = remember(serpMarkdown) { sanitizeSerpMarkdown(serpMarkdown) }
    val hasSerp = cleanedSerpMarkdown.isNotBlank()
    val hasBias = !regionalBiasNote.isNullOrBlank()
    if (!hasContextLines && !hasSerp && !hasBias) return

    Column(
        verticalArrangement = Arrangement.spacedBy(VSSpacing.space12),
        modifier = Modifier.testTag(TAG_SUPPLEMENTARY_INFO),
    ) {
        Text(
            text = stringResource(R.string.detail_section_supplementary_info),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (hasContextLines) {
            Column(verticalArrangement = Arrangement.spacedBy(VSSpacing.space4)) {
                contextLines.filter { it.isNotBlank() }.forEach { bullet ->
                    Text(
                        text = "• $bullet",
                        style = VSTypography.bodySans,
                        color = VSPalette.inkMuted,
                    )
                }
            }
        }
        if (hasSerp) {
            Column(verticalArrangement = Arrangement.spacedBy(VSSpacing.space4)) {
                // Hernat 2026-05-19 — bumped "Synthèse Google" weight to
                // Bold + size to 13 sp + colour to `ink` so the subtitle
                // reads as a deliberate sub-heading, not a faded caption.
                // Keeps the `labelTrackedSans` 0.14 em tracking that
                // signals "editorial label" for visual continuity with
                // the surrounding section headers.
                Text(
                    text = stringResource(R.string.detail_section_serp_synthesis),
                    style = VSTypography.labelTrackedSans.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    ),
                    color = VSPalette.ink,
                )
                Text(
                    text = cleanedSerpMarkdown,
                    style = VSTypography.bodySans,
                    color = VSPalette.inkMuted,
                )
            }
        }
        if (hasBias) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(VSSpacing.space8),
                modifier = Modifier.testTag(TAG_BIAS_ROW),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_globe),
                    contentDescription = null,
                    tint = VSPalette.inkSoft,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = regionalBiasNote.orEmpty(),
                    style = VSTypography.bodySans,
                    color = VSPalette.inkMuted,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
    }
}

// Epic 9 Story 9.2 — OcrSection removed. AnalyzedTextSection (defined
// further below, near the footer) is the new collapsible entry point
// for raw OCR + extracted claim per Epic 9 plan ("texte analysé caché
// en bas de la liste"). OcrCard + RawOcrCollapsible helpers are reused
// from AnalyzedTextSection unchanged.

@Composable
private fun RawOcrCollapsible(rawOcrText: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.testTag(TAG_RAW_OCR_TOGGLE),
        ) {
            Text(
                text = stringResource(R.string.detail_raw_ocr_title) +
                    " — " +
                    stringResource(
                        if (expanded) R.string.detail_raw_ocr_hide else R.string.detail_raw_ocr_show,
                    ),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (expanded) {
            OcrCard(text = rawOcrText)
        }
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

/**
 * Epic 9 Story 9.2 — sources section fuses Gemini's Search-Grounded
 * citations with SerpAPI's references into a single deduplicated list.
 * Dedup keyed on URL (lowercased, trailing slash stripped) so the same
 * outlet doesn't appear twice when both layers surface it.
 *
 * Gemini sources keep precedence (first occurrence wins) so the
 * `dateYearMonth` they carry is preserved when an outlet appears in
 * both lists.
 */
@Composable
private fun SourcesSection(
    geminiSources: List<SourceCitation>,
    serpReferences: List<SerpReference>,
    onSourceClick: (SourceCitation) -> Unit,
) {
    val fused = remember(geminiSources, serpReferences) {
        fuseSources(geminiSources, serpReferences)
    }
    Column(verticalArrangement = Arrangement.spacedBy(VSSpacing.space8)) {
        Text(
            text = stringResource(R.string.detail_section_sources_combined),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (fused.isEmpty()) {
            Text(
                text = stringResource(R.string.detail_sources_unavailable),
                style = VSTypography.bodySans,
                color = VSPalette.inkMuted,
                modifier = Modifier.testTag(TAG_SOURCES_UNAVAILABLE),
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(VSSpacing.space8),
                modifier = Modifier.testTag(TAG_SOURCES_ROW),
            ) {
                itemsIndexed(
                    items = fused,
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

/**
 * Pure dedup helper — fuses SerpAPI references + Gemini sources for the
 * combined Sources row.
 *
 * **SerpAPI precedence** (Epic 9 hotfix 2026-05-19 ; code-review F9
 * Group B clarified the KDoc-vs-impl contradiction) : SerpAPI
 * references are appended FIRST because Gemini Search Grounding
 * citations are `vertexaisearch.cloud.google.com/grounding-api-
 * redirect/...` URLs that expire and 404 in production. SerpAPI
 * returns real outlet URLs that resolve reliably. When the same
 * outlet appears in both lists, the SerpAPI entry wins (it was added
 * first ; the Gemini entry's `dateYearMonth` is dropped — accepted
 * trade-off for reliable click-through).
 *
 * **vertexaisearch filter** : Gemini citations on the
 * `vertexaisearch.cloud.google.com` host are dropped entirely. They
 * are Gemini-internal redirects — clicking them is a 404. Direct URLs
 * (rare) are kept and appended after the SerpAPI references.
 *
 * **URL canonicalisation** (code-review F10 Group B) : the dedup key
 * is the canonical form of the URL — same scheme treatment, `www.`
 * prefix stripped, fragment dropped, lower-cased host, tracking
 * query params (`utm_*`, `fbclid`, `gclid`) removed. Without this,
 * Gemini + SerpAPI surface the same article with cosmetic URL
 * differences and the user sees a duplicate chip.
 *
 * **Snippet preservation** (code-review F11 Group B) : when the
 * surviving source comes from SerpAPI, the [SerpReference.snippet] is
 * carried into the [SourceCitation.snippet] field so downstream UI
 * (detail-list mode, not chip mode) can surface the per-outlet 1-2
 * sentence summary that Gemini Search Grounding never emits.
 *
 * `internal` so JVM tests can exercise the dedup logic without booting
 * the Compose runtime.
 */
internal fun fuseSources(
    geminiSources: List<SourceCitation>,
    serpReferences: List<SerpReference>,
): List<SourceCitation> {
    val seen = mutableSetOf<String>()
    val out = mutableListOf<SourceCitation>()

    serpReferences.forEach { ref ->
        val key = canonicaliseUrl(ref.url)
        if (key.isNotEmpty() && !isBrokenGeminiRedirect(ref.url) && seen.add(key)) {
            out += SourceCitation(
                title = ref.title,
                url = ref.url,
                publisher = ref.publisher,
                dateYearMonth = null,
                snippet = ref.snippet.takeIf { it.isNotBlank() },
            )
        }
    }
    geminiSources.forEach { src ->
        val key = canonicaliseUrl(src.url)
        if (key.isNotEmpty() && !isBrokenGeminiRedirect(src.url) && seen.add(key)) {
            out += src
        }
    }
    return out
}

private fun isBrokenGeminiRedirect(url: String): Boolean =
    url.contains("vertexaisearch.cloud.google.com", ignoreCase = true)

/**
 * Code-review F10 (Group B) — canonical URL form used as the dedup
 * key in [fuseSources]. Robust against the cosmetic differences
 * Gemini Search Grounding and SerpAPI introduce for the same outlet :
 *  - scheme stripped (`http://` vs `https://` → same key)
 *  - `www.` host prefix stripped
 *  - fragment (`#section`) dropped
 *  - tracking query params (`utm_*`, `fbclid`, `gclid`, `ref`) dropped
 *  - trailing slash dropped
 *  - lower-cased throughout
 *
 * NOT public — internal to the dedup logic ; if a UI surface ever
 * needs to render a "clean URL" for display, build a separate helper.
 */
internal fun canonicaliseUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val noScheme = trimmed
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("//")
    val noFragment = noScheme.substringBefore('#')
    val (path, queryRaw) = noFragment.substringBefore('?').let {
        it to noFragment.substringAfter('?', missingDelimiterValue = "")
    }
    val cleanedQuery = if (queryRaw.isEmpty()) {
        ""
    } else {
        queryRaw.split('&')
            .filter { param ->
                val name = param.substringBefore('=').lowercase()
                !TRACKING_PARAMS.any { name == it || name.startsWith("utm_") }
            }
            .sorted()
            .joinToString("&")
    }
    val host = path.substringBefore('/').removePrefix("www.").lowercase()
    val rest = path.substringAfter('/', missingDelimiterValue = "")
        .trimEnd('/')
        .lowercase()
    return buildString {
        append(host)
        if (rest.isNotEmpty()) append('/').append(rest)
        if (cleanedQuery.isNotEmpty()) append('?').append(cleanedQuery)
    }
}

private val TRACKING_PARAMS = listOf("fbclid", "gclid", "ref", "ref_src")

// ----- Section 4 (formerly): RegionalBiasRow removed in Epic 9 Story 9.2.
// The bias note is now folded into SupplementaryInfoSection above (single
// "Information supplémentaire" heading covers contextLines + serpMarkdown
// + bias). RegionalBiasRow composable deleted ; TAG_BIAS_ROW kept and
// rewired to the new italic-globe row inside SupplementaryInfoSection so
// existing instrumented tests that lookup the tag still resolve a node.

// ----- Section 5: Analyzed text (Epic 9 Story 9.2 — moved to bottom) --

/**
 * Epic 9 Story 9.2 — the "Texte analysé" section moved from top-3 to
 * bottom (just above the footer) AND collapsed by default. Rationale per
 * Epic 9 plan : the typical user reads the verdict + supplementary
 * info + sources first ; the raw / extracted OCR is a power-user
 * inspection surface (anti-injection self-revealing posture per FR7+FR8)
 * — it doesn't need to push the verdict off-screen.
 *
 * The user taps the section header to expand it ; expanded state shows
 * the existing two-tier card (extracted claim + collapsible raw OCR).
 */
@Composable
private fun AnalyzedTextSection(extractedClaim: String, rawOcrText: String) {
    val hasExtracted = extractedClaim.isNotBlank()
    val hasRaw = rawOcrText.isNotBlank()
    if (!hasExtracted && !hasRaw) return

    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(VSSpacing.space8)) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.testTag(TAG_ANALYZED_TEXT_TOGGLE),
        ) {
            Text(
                text = stringResource(R.string.detail_section_analyzed_text) +
                    " — " +
                    stringResource(
                        if (expanded) R.string.detail_analyzed_text_hide
                        else R.string.detail_analyzed_text_show,
                    ),
                style = VSTypography.labelTrackedSans,
            )
        }
        if (expanded) {
            OcrCard(text = if (hasExtracted) extractedClaim else rawOcrText)
            // Inner raw-OCR collapsible preserved for power users (extracted
            // present + raw differs).
            if (hasExtracted && hasRaw && rawOcrText.trim() != extractedClaim.trim()) {
                RawOcrCollapsible(rawOcrText = rawOcrText)
            }
        }
    }
}

// ----- Section 6: Footer ----------------------------------------------

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

/**
 * Epic 9 hotfix 2026-05-19 — replaced the colourful system emoji set
 * with monochrome editorial glyphs to match the Wispr Flow theme.
 * Renders in [VSTypography.titleSerif] (EB Garamond / Noto Serif) +
 * [VSPalette.ink] for the warm-brown ink colour, alongside the
 * verdict word in [VerdictRow]. Mirrors the [HistoryItemRow]
 * monoGlyphForLabel helper — keeping the two surfaces visually
 * consistent.
 */
private fun verdictEmojiFor(label: VerdictLabel): String = when (label) {
    VerdictLabel.TRUE -> "✓"
    VerdictLabel.FALSE -> "✗"
    VerdictLabel.DOUBTFUL -> "?"
    VerdictLabel.NON_VERIFIABLE -> "∅"
}

// ----- Constants ------------------------------------------------------

/** Test tags for instrumented tests (see DetailPanelContentUiTest). */
internal const val TAG_VERDICT_ROW = "vs_detail_verdict_row"
internal const val TAG_OCR_CARD = "vs_detail_ocr_card"
internal const val TAG_RAW_OCR_TOGGLE = "vs_detail_raw_ocr_toggle"
internal const val TAG_SOURCES_ROW = "vs_detail_sources_row"
internal const val TAG_SOURCES_UNAVAILABLE = "vs_detail_sources_unavailable"
internal const val TAG_BIAS_ROW = "vs_detail_bias_row"
internal const val TAG_FOOTER_ROW = "vs_detail_footer_row"
// Epic 9 Story 9.2 — new section test tags.
internal const val TAG_AGREEMENT_BADGE = "vs_detail_agreement_badge"
internal const val TAG_SUPPLEMENTARY_INFO = "vs_detail_supplementary_info"
internal const val TAG_ANALYZED_TEXT_TOGGLE = "vs_detail_analyzed_text_toggle"

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
