package com.verisphere.app.ui.history

import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verisphere.app.R
import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.gemini.stripVerdictPrefix
import com.verisphere.app.storage.SessionRecord
import com.verisphere.app.ui.theme.VSPalette
import com.verisphere.app.ui.theme.VSSpacing
import com.verisphere.app.ui.theme.VSTypography
import com.verisphere.app.ui.theme.VeriSphereTheme

/**
 * Story 4.2 — compact list row for one persisted [SessionRecord].
 *
 * Stateless per architecture line 350 (consumer of upstream state). Takes
 * the record by reference and calls [onClick] on tap. The host
 * `HistoryScreen.ContentList` lambda (Story 4.1) wires `onClick` to
 * `onItemClick(record.id)`; Story 4.4 wires the resulting record id to
 * open a read-only [com.verisphere.app.ui.detail.AnchoredDetailPanel].
 *
 * **Geometry** (UX-DR9, UX spec line 426 + line 700):
 *  - Min height `72.dp` via `heightIn(min = ...)` so user font-scale up to
 *    `≥ 1.5×` can grow the row without clipping the headline preview
 *    (NFR14 system font scaling).
 *  - Padding `16.dp` horizontal / `12.dp` vertical.
 *  - Click target spans the full row including padding because the
 *    `.clickable {}` modifier is applied BEFORE `.padding(...)` —
 *    Critical Dev Note #6 of the spec.
 *
 * **Three-slot horizontal layout** (left → right):
 *  - Leading 36 dp box with the verdict emoji in `headlineSmall`.
 *  - 12 dp gap.
 *  - Middle `Column.weight(1f)`: verdict word in `titleMedium` UPPERCASE
 *    `onSurface` (resource lookup via [verdictWordResFor]) +
 *    `bodyMedium` `onSurfaceVariant` headline preview with
 *    `maxLines = 1` + `TextOverflow.Ellipsis`.
 *  - 12 dp gap.
 *  - Trailing relative timestamp in `labelMedium` `onSurfaceVariant`
 *    via [relativeTimestamp] (locale-aware `DateUtils.getRelativeTimeSpanString`).
 *
 * **Accessibility** (UX-DR18, NFR12) — the row's children are merged via
 * `Modifier.semantics(mergeDescendants = true)` so TalkBack announces
 * one combined utterance (`"Verdict: true. <headline>. <timestamp>"`)
 * with `Role.Button` for "Double tap to activate" hint. Mirrors the
 * Story 2.3 [com.verisphere.app.ui.detail.DetailPanelContent] verdict-row
 * pattern.
 *
 * **DRY note** — [emojiForLabel] / [verdictWordResFor] /
 * [verdictContentDescriptionFor] are byte-identical to the helpers in
 * `DetailPanelContent` + `FlashTooltip`. Critical Dev Note #1 of the
 * spec documents the intentional duplication (file-local privacy is a
 * feature; extraction trigger is ≥ 4 duplicates which arrives in V2).
 */
@Composable
fun HistoryItemRow(
    record: SessionRecord,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val verdictAnnouncement = stringResource(verdictContentDescriptionFor(record.verdictLabel))
    val timestamp = relativeTimestamp(record.timestampMs)
    // Code-review F14 (Group B) — strip the verdict prefix from the
    // headline read by TalkBack so the spoken UI matches the visual
    // surface (which already strips via the Text below). Legacy records
    // persisted before [GeminiClient.toSessionRecord] strip support
    // shipped would otherwise have TalkBack reading "Verdict : vrai.
    // C'EST VRAI : <claim>. <timestamp>" — verdict announced twice.
    val strippedHeadline = stripVerdictPrefix(record.headline, record.verdictLabel)
    val rowA11yLabel = "$verdictAnnouncement. $strippedHeadline. $timestamp"

    // Epic 8 Story 8.1 — wrap each row in a Wispr-style paper card:
    // paper background + hairline border + 16 dp soft corners. Click +
    // a11y semantics stay on the row content (canonical pattern from
    // Story 4.2 patch P5 — role inside .clickable, mergeDescendants on
    // the same node as contentDescription).
    // Code-review F28 (Group B) — Surface body indented one level deeper
    // so the Row block is visually nested inside the Surface scope
    // (matches the project's prevailing 4-space-per-block convention).
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = VSPalette.paper,
        contentColor = VSPalette.ink,
        shape = RoundedCornerShape(VSSpacing.space16),
        border = BorderStroke(1.dp, VSPalette.hairline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HistoryItemRowDefaults.MinHeight)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(
                    horizontal = VSSpacing.space16,
                    vertical = VSSpacing.space12,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = rowA11yLabel
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(HistoryItemRowDefaults.EmojiSlotSize),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = monoGlyphForLabel(record.verdictLabel),
                    style = VSTypography.titleSerif,
                    color = VSPalette.ink,
                )
            }
            Spacer(modifier = Modifier.width(VSSpacing.space12))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(VSSpacing.space4),
            ) {
                Text(
                    // Epic 8 — defensive strip of any "C'EST VRAI :" / "C'EST
                    // FAUX :" / "DOUTEUX :" / "NON VÉRIFIABLE :" prefix that
                    // Gemini may have written into the headline (verdictLabel
                    // is the source of truth, rendered as the leading glyph).
                    // F14 — reuse the same strippedHeadline computed above for
                    // a11y so visual + spoken stay in lockstep.
                    text = strippedHeadline,
                    style = VSTypography.headlineBodySans,
                    color = VSPalette.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = timestamp,
                    style = VSTypography.labelTrackedSans,
                    color = VSPalette.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ─── Defaults + helpers ───────────────────────────────────────────────

/**
 * Layout slot sizes for [HistoryItemRow] and the empty-state glyph.
 *
 * `internal` so [com.verisphere.app.ui.history.HistoryScreen]'s
 * `EmptyBubbleGlyph` (sibling file, same package) can read
 * [BubbleGlyphSize]. These are layout slot sizes, NOT generic spacing
 * tokens — Critical Dev Note #4 of the spec explains why they live
 * here instead of in [VSSpacing] (adding them to the scale would invite
 * drift if a future Story 5.x onboarding wants a different 56 dp).
 */
internal object HistoryItemRowDefaults {
    val MinHeight: Dp = 72.dp
    val EmojiSlotSize: Dp = 36.dp
    val BubbleGlyphSize: Dp = 56.dp
}

/**
 * Epic 8 Story 8.1 + Epic 9 hotfix — monochrome editorial glyphs
 * replacing the system emoji set in HistoryItemRow. Renders in
 * [VSTypography.titleSerif] (EB Garamond / Noto Serif) with
 * [VSPalette.ink] for the Wispr Flow aesthetic. The previous
 * colourful `emojiForLabel` helper (✅❌⚠️⚪) was removed once every
 * caller had migrated to this helper.
 */
private fun monoGlyphForLabel(label: VerdictLabel): String = when (label) {
    VerdictLabel.TRUE -> "✓"
    VerdictLabel.FALSE -> "✗"
    // Code-review F15 (Group B) — "?" was indistinguishable from regular
    // question-mark punctuation when the headline itself contained "?".
    // U+2047 (DOUBLE QUESTION MARK) reads unambiguously as a marker
    // glyph, matching the visual register of the 3 other status glyphs.
    VerdictLabel.DOUBTFUL -> "⁇"
    VerdictLabel.NON_VERIFIABLE -> "∅"
}

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
 * Locale-aware abbreviated relative timestamp for the trailing slot of
 * [HistoryItemRow].
 *
 * `minResolution = MINUTE_IN_MILLIS` — coarsest readable unit; very
 * recent verdicts read as "0 m" (acceptable per UX-DR9 — a history row
 * is a "past" record, not a real-time clock).
 *
 * `FORMAT_ABBREV_RELATIVE` — yields "2 m" / "3 h" / "5 d" / "2 mo" /
 * "1 y" instead of "2 minutes ago" / "3 hours ago". UX-DR9 cited "2 h
 * ago" / "3 d ago" descriptively; the abbreviated form fits
 * `labelMedium` (12 sp) without wrapping at standard widths.
 *
 * **Patch P1 (code review 2026-05-14)** — Locale-flip recomposition fix:
 * keys [remember] on `LocalConfiguration.current.locales[0]` so a system
 * locale change invalidates the cached string. Previously a bare
 * `LocalContext.current` was read but did not invalidate the
 * `remember(timestampMs)` cache, so French users would see stale
 * English formatting until the row was re-entered.
 *
 * **Patch P2 (code review 2026-05-14)** — Defensive future-timestamp
 * clamp: a clock-skew record (device clock moved backward) would
 * otherwise produce "in 5 m" strings UX never specced. Clamp to `now`
 * so future timestamps render as "0 m".
 *
 * `remember(timestampMs, locale)` keeps the string stable per record
 * across composition while upstream `Content(records)` re-emits cause
 * a fresh formatting pass naturally (no 1-minute ticker — Story 4.1 D5
 * + spec CDN#2 both forbid it).
 */
@Composable
private fun relativeTimestamp(timestampMs: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(timestampMs, locale) {
        val now = System.currentTimeMillis()
        val safeTime = if (timestampMs > now) now else timestampMs
        DateUtils.getRelativeTimeSpanString(
            safeTime,
            now,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }
}

// ─── @Preview catalogue (Task 7 — 12 previews) ────────────────────────

private fun previewRecord(
    label: VerdictLabel,
    headline: String = "Sample claim under verification",
    timestampMs: Long = PREVIEW_TIMESTAMP_BASE_MS,
    id: String = "preview-${label.name.lowercase()}",
): SessionRecord = SessionRecord(
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

// Patch P8 (code review 2026-05-14) — fixed epoch constant for
// deterministic preview rendering. Was previously a `get()` property
// reading System.currentTimeMillis() on every preview render, which
// would break golden-image / screenshot tests if added later.
// Value: 2026-01-15T12:00:00Z (a stable wall clock far enough in the
// past that `relativeTimestamp(now=System.currentTimeMillis())` yields
// a readable abbreviated form in any preview captured in 2026+).
private const val PREVIEW_TIMESTAMP_BASE_MS: Long = 1_768_564_800_000L

// 280-char realistic Gemini-style headline for the long-headline previews.
private val PREVIEW_LONG_HEADLINE: String =
    buildString {
        repeat(4) {
            append(
                "This claim about a high-profile political resignation cannot be " +
                    "verified by the cited sources available at the time of writing. ",
            )
        }
    }.trim().take(PREVIEW_LONG_HEADLINE_MAX_LENGTH)

private const val PREVIEW_LONG_HEADLINE_MAX_LENGTH: Int = 280

private const val PREVIEW_FONT_SCALE_LARGE: Float = 1.5f

@Preview(showBackground = true, name = "TRUE • Light")
@Composable
private fun HistoryItemRowTrueLightPreview() {
    VeriSphereTheme {
        Surface {
            HistoryItemRow(record = previewRecord(VerdictLabel.TRUE), onClick = {})
        }
    }
}

@Preview(showBackground = true, name = "TRUE • Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HistoryItemRowTrueDarkPreview() {
    VeriSphereTheme {
        Surface {
            HistoryItemRow(record = previewRecord(VerdictLabel.TRUE), onClick = {})
        }
    }
}

@Preview(showBackground = true, name = "FALSE • Light")
@Composable
private fun HistoryItemRowFalseLightPreview() {
    VeriSphereTheme {
        Surface {
            HistoryItemRow(record = previewRecord(VerdictLabel.FALSE), onClick = {})
        }
    }
}

@Preview(showBackground = true, name = "FALSE • Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HistoryItemRowFalseDarkPreview() {
    VeriSphereTheme {
        Surface {
            HistoryItemRow(record = previewRecord(VerdictLabel.FALSE), onClick = {})
        }
    }
}

@Preview(showBackground = true, name = "DOUBTFUL • Light")
@Composable
private fun HistoryItemRowDoubtfulLightPreview() {
    VeriSphereTheme {
        Surface {
            HistoryItemRow(record = previewRecord(VerdictLabel.DOUBTFUL), onClick = {})
        }
    }
}

@Preview(showBackground = true, name = "DOUBTFUL • Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HistoryItemRowDoubtfulDarkPreview() {
    VeriSphereTheme {
        Surface {
            HistoryItemRow(record = previewRecord(VerdictLabel.DOUBTFUL), onClick = {})
        }
    }
}

@Preview(showBackground = true, name = "NON-VERIFIABLE • Light")
@Composable
private fun HistoryItemRowNonVerifiableLightPreview() {
    VeriSphereTheme {
        Surface {
            HistoryItemRow(record = previewRecord(VerdictLabel.NON_VERIFIABLE), onClick = {})
        }
    }
}

@Preview(showBackground = true, name = "NON-VERIFIABLE • Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HistoryItemRowNonVerifiableDarkPreview() {
    VeriSphereTheme {
        Surface {
            HistoryItemRow(record = previewRecord(VerdictLabel.NON_VERIFIABLE), onClick = {})
        }
    }
}

@Preview(showBackground = true, name = "Long headline • Light")
@Composable
private fun HistoryItemRowLongHeadlineLightPreview() {
    VeriSphereTheme {
        Surface {
            HistoryItemRow(
                record = previewRecord(
                    label = VerdictLabel.DOUBTFUL,
                    headline = PREVIEW_LONG_HEADLINE,
                ),
                onClick = {},
            )
        }
    }
}

@Preview(
    showBackground = true,
    name = "Long headline • Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HistoryItemRowLongHeadlineDarkPreview() {
    VeriSphereTheme {
        Surface {
            HistoryItemRow(
                record = previewRecord(
                    label = VerdictLabel.DOUBTFUL,
                    headline = PREVIEW_LONG_HEADLINE,
                ),
                onClick = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Font scale 1.5× • Light")
@Composable
private fun HistoryItemRowFontScale15LightPreview() {
    VeriSphereTheme {
        Surface {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = LocalDensity.current.density,
                    fontScale = PREVIEW_FONT_SCALE_LARGE,
                ),
            ) {
                HistoryItemRow(record = previewRecord(VerdictLabel.TRUE), onClick = {})
            }
        }
    }
}

@Preview(
    showBackground = true,
    name = "Font scale 1.5× • Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HistoryItemRowFontScale15DarkPreview() {
    VeriSphereTheme {
        Surface {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = LocalDensity.current.density,
                    fontScale = PREVIEW_FONT_SCALE_LARGE,
                ),
            ) {
                HistoryItemRow(record = previewRecord(VerdictLabel.TRUE), onClick = {})
            }
        }
    }
}
