package com.verisphere.app.ui.detail

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.verisphere.app.R
import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.ui.theme.VeriSphereTheme
import java.net.URI

/**
 * Compact source citation chip — the UX-DR12 / UX spec line 703 surface
 * that lets the user (a) recognise an outlet at a glance via its domain
 * and (b) tap to read the full article in the system browser.
 *
 * **Anatomy** (UX-DR12, UX spec line 425, NFR13, PRD FR9 + FR14):
 *  - Material 3 [AssistChip] — leading "open in new" icon (18 dp, M3
 *    default; drawn via `R.drawable.ic_open_in_new` rather than
 *    `androidx.compose.material.icons.filled.OpenInNew` to avoid the
 *    1.5 MB `material-icons-extended` dependency — see Story 2.1
 *    Critical Dev Note #6 + this file's drawable XML), label
 *    `domain · YYYY-MM` in `labelMedium`
 *    (12 sp per [`Type.kt`](../theme/Type.kt) — UX-DR2 + UX spec line 411).
 *  - Stock M3 colour scheme: `surfaceVariant` background,
 *    `onSurfaceVariant` content. The verdict semantic palette
 *    (`vs_verdict_*`) is reserved for the bubble + flash tooltip ONLY
 *    (Story 2.1 anti-pattern #4).
 *  - Touch target ≥ 48 dp via [Modifier.minimumInteractiveComponentSize]
 *    so the chip honours NFR13 even when packed densely in the
 *    detail panel's source row (Story 2.3 layout).
 *
 * **Date handling** ([SourceCitation.dateYearMonth] is nullable per the
 * Gemini schema — evergreen Wikipedia / archived snapshots have no
 * parseable date): when `null`, the chip drops the ` · YYYY-MM` suffix
 * and renders the domain alone. No `(date unknown)` placeholder — chips
 * are dense; an extra word per chip degrades scannability. Story 2.3's
 * detail panel may render a fuller "(date unknown)" affordance elsewhere.
 *
 * **Click contract** — the composable takes an `onClick: () -> Unit`
 * lambda (Story 2.1 Critical Dev Note #5 — lambda-seam matches Stories
 * 1.5 / 1.7). The call site (Story 2.3 / 2.4) wires:
 * ```kotlin
 * val context = LocalContext.current
 * SourceLinkChip(
 *     citation = citation,
 *     onClick = { context.startActivity(buildSourceLinkIntent(citation.url)) },
 * )
 * ```
 * Keeping `Context` out of the composable body preserves preview
 * fidelity (no live-context dependency) and JVM-test friendliness for
 * the helpers.
 *
 * **Accessibility** (UX-DR18, NFR12):
 *  - Merged semantics — TalkBack reads
 *    `"Source: $publisher, $dateYearMonth, opens in browser"` (or the
 *    no-date variant when [SourceCitation.dateYearMonth] is null).
 *  - The publisher (e.g. "BBC News") is announced even though the
 *    visible label shows the domain — voice users benefit from the
 *    editorial outlet name; sighted users can scan the domain faster.
 *  - The leading icon's `contentDescription = null` because the merged
 *    chip semantics carry the announcement (M3 contract for decorative
 *    leading icons inside a labelled chip).
 */
@Composable
fun SourceLinkChip(
    citation: SourceCitation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val domain = extractDisplayDomain(citation.url)
    val label = if (citation.dateYearMonth != null) {
        "$domain · ${citation.dateYearMonth}"
    } else {
        domain
    }

    val announcement = if (citation.dateYearMonth != null) {
        stringResource(
            R.string.source_chip_a11y_with_date,
            citation.publisher,
            citation.dateYearMonth,
        )
    } else {
        stringResource(R.string.source_chip_a11y_no_date, citation.publisher)
    }

    AssistChip(
        onClick = onClick,
        label = {
            // Code-review patch P3 — clamp to one line and ellipsise so a
            // long domain (e.g. `extremely-long-investigative-newsroom.example.org`)
            // does not blow up Story 2.3's source-row layout.
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_open_in_new),
                contentDescription = null,
            )
        },
        modifier = modifier
            .minimumInteractiveComponentSize()
            .semantics(mergeDescendants = true) {
                contentDescription = announcement
            },
    )
}

/**
 * Extracts the user-facing host portion of [url], stripping a leading
 * `www.` label. Falls back to the raw input when the URL is malformed
 * (bad scheme, spaces, empty string) so the chip always renders
 * SOMETHING the user can scan.
 *
 * Uses [java.net.URI] (RFC 3986 parser, JVM-built-in) rather than
 * [android.net.Uri] because the latter is a stub on the JVM unit-test
 * classpath under `unitTests.isReturnDefaultValues = true` — calling
 * `Uri.parse(...).getHost()` returns `null` in the test JVM, making
 * unit coverage impossible. See Story 2.1 Critical Dev Note #3.
 *
 * Hosts are lowercased before the `www.` prefix is stripped because
 * `java.net.URI.getHost()` preserves the original case from the input
 * (RFC 3986 §3.2.2 says hosts are case-insensitive but Java's URI does
 * NOT normalise them). Without the `lowercase()`, an input of
 * `WWW.BBC.COM` would render as `WWW.BBC.COM` on the chip.
 */
internal fun extractDisplayDomain(url: String): String =
    runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }
        .getOrNull()
        ?: url

/**
 * Builds the un-started [Intent] that opens the source citation's URL in
 * the user's default browser. Sets [Intent.FLAG_ACTIVITY_NEW_TASK]
 * unconditionally — required when starting from a non-Activity context
 * (Service / Application), harmless from an Activity context (Story 2.1
 * Critical Dev Note #4).
 *
 * Not directly tested on the JVM: under
 * `unitTests.isReturnDefaultValues = true`, `Intent.getAction()`,
 * `Intent.getDataString()`, and `Intent.getFlags()` all return null/0
 * on the Android JVM stub — same limitation that drove Story 1.9's
 * `base64Encoder` constructor seam in [`GeminiClient`](../../gemini/GeminiClient.kt).
 * Correctness is enforced by the instrumented tests in
 * [`SourceLinkChipUiTest`](../../../../androidTest/kotlin/com/verisphere/app/ui/detail/SourceLinkChipUiTest.kt)
 * (which exercise the real `Intent` accessors at runtime — they return
 * proper values in the instrumented JVM, unlike the unit-test stub).
 */
internal fun buildSourceLinkIntent(url: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

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

private val SAMPLE_WIKIPEDIA_NO_DATE = SourceCitation(
    title = "Encyclopaedia article placeholder",
    url = "https://en.wikipedia.org/wiki/Whatever",
    publisher = "Wikipedia",
    dateYearMonth = null,
)

private val SAMPLE_LONG_DOMAIN = SourceCitation(
    title = "Long-domain headline placeholder",
    url = "https://extremely-long-investigative-newsroom.example.org/article",
    publisher = "Extremely Long Investigative Newsroom",
    dateYearMonth = "2024-11",
)

@Preview(showBackground = true, name = "Chip - bbc.com - Light")
@Composable
private fun SourceLinkChipBbcLightPreview() {
    VeriSphereTheme {
        SourceLinkChip(
            citation = SAMPLE_BBC,
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(
    showBackground = true,
    name = "Chip - bbc.com - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SourceLinkChipBbcDarkPreview() {
    VeriSphereTheme {
        SourceLinkChip(
            citation = SAMPLE_BBC,
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "Chip - null date - Light")
@Composable
private fun SourceLinkChipNullDateLightPreview() {
    VeriSphereTheme {
        SourceLinkChip(
            citation = SAMPLE_WIKIPEDIA_NO_DATE,
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "Chip - long domain - Light")
@Composable
private fun SourceLinkChipLongDomainLightPreview() {
    VeriSphereTheme {
        SourceLinkChip(
            citation = SAMPLE_LONG_DOMAIN,
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "Row x3 - Light")
@Composable
private fun SourceLinkChipRowLightPreview() {
    VeriSphereTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SourceLinkChip(citation = SAMPLE_BBC, onClick = {})
            SourceLinkChip(citation = SAMPLE_LEMONDE, onClick = {})
            SourceLinkChip(citation = SAMPLE_REUTERS, onClick = {})
        }
    }
}

@Preview(
    showBackground = true,
    name = "Row x3 - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SourceLinkChipRowDarkPreview() {
    VeriSphereTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SourceLinkChip(citation = SAMPLE_BBC, onClick = {})
            SourceLinkChip(citation = SAMPLE_LEMONDE, onClick = {})
            SourceLinkChip(citation = SAMPLE_REUTERS, onClick = {})
        }
    }
}
