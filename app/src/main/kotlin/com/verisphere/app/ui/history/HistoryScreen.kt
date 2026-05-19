package com.verisphere.app.ui.history

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.verisphere.app.R
import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.storage.SessionRecord
import com.verisphere.app.ui.banner.UpdateBanner
import com.verisphere.app.ui.theme.VSPalette
import com.verisphere.app.ui.theme.VSSpacing
import com.verisphere.app.ui.theme.VSTypography
import com.verisphere.app.ui.theme.VeriSphereTheme

/**
 * Story 6.3 — typed payload for the banner's reactive state.
 *
 * Carried as a nullable parameter to [HistoryScreen] / [HistoryContent]
 * — `null` when the banner predicate
 * (`updateAvailableVersion != null && downloadUrl != null &&
 * updateAvailableVersion != updateBannerDismissedForVersion`) evaluates
 * false, non-null when the banner should render.
 *
 * The two fields always agree on null-ness because they originate from
 * the same notify-seam call (Story 6.2 CDN #4 pairing invariant,
 * extended in Story 6.3 CDN #6 to include downloadUrl).
 */
data class UpdateBannerPayload(
    val version: String,
    val downloadUrl: String,
)

/**
 * Story 4.2 — Compose testTag for the empty-state placeholder. Hoisted
 * to a public constant (code-review patch P4 — 2026-05-17) so
 * androidTest can import the symbol instead of duplicating the magic
 * string. Mirrors the `TEST_TAG_BANNER` / `TEST_TAG_DISMISS` pattern in
 * [com.verisphere.app.ui.banner.UpdateBanner].
 */
internal const val TEST_TAG_HISTORY_EMPTY_PLACEHOLDER: String = "history_empty_placeholder"

/**
 * Stateful host for the history surface (Story 4.1).
 *
 * Architecture line 350 stateful/stateless split — this composable only
 * plumbs the [HistoryViewModel]'s [HistoryUiState] StateFlow into
 * [HistoryContent]; no business logic. Story 4.2 fills the empty-state
 * copy + glyph and replaces the `ContentList` placeholder with
 * [HistoryItemRow]; Story 4.3 wired the bubble idle-tap entry point via
 * [HistoryScreenIntent.ACTION_OPEN] (the bubble service builds an
 * `Intent(MainActivity).setAction(ACTION_OPEN)` and `MainActivity.onNewIntent`
 * dismisses any stale detail panel so this screen is the visible base
 * layer); Story 4.4 wires `onItemClick` to the read-only detail panel.
 *
 * **`collectAsState` vs `collectAsStateWithLifecycle`** — V1 ships
 * [collectAsState] to avoid adding `androidx.lifecycle:lifecycle-runtime-compose`
 * for one composable. MainActivity is a single-view host so the activity
 * is almost always RESUMED while the user is looking at this screen;
 * the trade-off (no STOPPED-pause for the upstream collect) is
 * negligible on V1 scale. Revisit in V2 a11y/perf pass.
 */
@Composable
fun HistoryScreen(
    onItemClick: (recordId: String) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory),
    // Story 6.3 — banner wiring (CDN #10). Defaults preserve backward
    // compatibility for the existing 10 @Preview catalogue + the
    // HistoryScreenInstrumentedTest. The MainActivity host (the only
    // production caller) passes non-null values when the visibility
    // predicate (`version != null && downloadUrl != null && version
    // != dismissed`) is true.
    updateBanner: UpdateBannerPayload? = null,
    onUpdateBannerDismiss: () -> Unit = {},
    onUpdateBannerDownload: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    HistoryContent(
        state = state,
        onItemClick = onItemClick,
        onBackClick = onBackClick,
        modifier = modifier,
        updateBanner = updateBanner,
        onUpdateBannerDismiss = onUpdateBannerDismiss,
        onUpdateBannerDownload = onUpdateBannerDownload,
    )
}

/**
 * Stateless history-surface content. Three exhaustive arms over
 * [HistoryUiState]; previewable and androidTest-friendly without any
 * `ViewModel` scaffolding.
 *
 * Story 4.2 fills the empty-state placeholder (centred glyph + verbatim
 * UX-DR17 copy) and routes each `Content` record through
 * [HistoryItemRow]. The `onBackClick` parameter is still wired through
 * the public signature for Story 4.3's `TopAppBar` back-arrow.
 *
 * @param onBackClick Wired to a system-back equivalent for Story 4.3's
 *   `TopAppBar` navigation icon. Story 4.2 does not render the
 *   `TopAppBar` so the parameter is currently unused inside this
 *   composable, but the signature is committed to keep Story 4.3's
 *   diff minimal.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun HistoryContent(
    state: HistoryUiState,
    onItemClick: (String) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    updateBanner: UpdateBannerPayload? = null,
    onUpdateBannerDismiss: () -> Unit = {},
    onUpdateBannerDownload: () -> Unit = {},
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        // Story 6.3 AC #2 — banner renders ABOVE the state body, visible
        // across all three HistoryUiState arms (UX spec L609 + Flow 4:
        // banner appears whenever the user opens history). Predicate
        // evaluation lives in the MainActivity host per CDN #2 —
        // Compose just renders what it's told.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (updateBanner != null) {
                UpdateBanner(
                    version = updateBanner.version,
                    onDownloadClick = onUpdateBannerDownload,
                    onDismiss = onUpdateBannerDismiss,
                )
            }
            // Code-review patch P2 (2026-05-17) — `weight(1f)` so the
            // body Box claims REMAINING vertical space after the banner
            // measures, not the full Scaffold padding. Defends against
            // fontScale 2x + long version strings + small screens
            // where a banner could otherwise starve the placeholder /
            // ContentList of vertical space (BH15 + EC16 merged).
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (state) {
                    HistoryUiState.Loading -> LoadingPlaceholder()
                    HistoryUiState.Empty -> EmptyPlaceholder()
                    is HistoryUiState.Content -> ContentList(
                        records = state.records,
                        onItemClick = onItemClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(VSSpacing.space32)
                .testTag("history_loading_indicator"),
        )
    }
}

@Composable
private fun EmptyPlaceholder(modifier: Modifier = Modifier) {
    // Empty-state copy + glyph per UX-DR17 + UX spec line 764.
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(TEST_TAG_HISTORY_EMPTY_PLACEHOLDER),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyBubbleGlyph()
        Spacer(modifier = Modifier.height(VSSpacing.space16))
        Text(
            text = stringResource(R.string.history_empty_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(VSSpacing.space4))
        Text(
            text = stringResource(R.string.history_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Story 4.2 — empty-state visual anchor mirroring the production
 * `BubbleOverlay`'s idle look (Compose-first per Story 1.7;
 * `R.drawable.bubble_idle.xml` in architecture line 692 is aspirational
 * and intentionally not created — see spec Critical Dev Note #3).
 * 56 dp circle, `surfaceVariant` fill, centred "G" glyph in
 * `headlineSmall`.
 */
@Composable
private fun EmptyBubbleGlyph(modifier: Modifier = Modifier) {
    val a11yLabel = stringResource(R.string.history_empty_glyph_content_description)
    // Patch P7 (code review 2026-05-14) — mergeDescendants = true so the
    // decorative "G" Text inside is folded into the parent Surface's
    // contentDescription, preventing TalkBack from double-announcing
    // ("VeriSphere bubble" + literal "G").
    Surface(
        modifier = modifier
            .size(HistoryItemRowDefaults.BubbleGlyphSize)
            .semantics(mergeDescendants = true) { contentDescription = a11yLabel },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "G",
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

@Composable
private fun ContentList(
    records: List<SessionRecord>,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = VSSpacing.space16,
            end = VSSpacing.space16,
            top = VSSpacing.space8,
            bottom = VSSpacing.space24,
        ),
        verticalArrangement = Arrangement.spacedBy(VSSpacing.space8),
    ) {
        // Epic 8 Story 8.1 — editorial header (EB Garamond title +
        // Figtree tracked subtitle). Lives inside LazyColumn so it
        // scrolls with the list (Wispr editorial header pattern).
        item(key = "history-header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = VSSpacing.space8,
                        end = VSSpacing.space8,
                        top = VSSpacing.space24,
                        bottom = VSSpacing.space16,
                    ),
                verticalArrangement = Arrangement.spacedBy(VSSpacing.space8),
            ) {
                Text(
                    text = stringResource(R.string.history_screen_title),
                    style = VSTypography.headlineSerif,
                    color = VSPalette.ink,
                )
                // Code-review F7 (Group B) — plurals.xml drives the
                // grammatical agreement of `dernière(s) vérification(s)`
                // with `records.size`. F19 — the misleading "· 24 h"
                // suffix was dropped (no rolling-window filter exists).
                Text(
                    text = androidx.compose.ui.res.pluralStringResource(
                        id = R.plurals.history_screen_subtitle,
                        count = records.size,
                        records.size,
                    ),
                    style = VSTypography.labelTrackedSans.copy(),
                    color = VSPalette.inkSoft,
                )
            }
        }
        // Story 4.1 Critical Dev Note #3 invariant — the `key = { it.id }`
        // MUST stay on the items() call (not inside HistoryItemRow) so
        // Compose tracks row identity across reorderings.
        items(records, key = { it.id }) { record ->
            HistoryItemRow(
                record = record,
                onClick = { onItemClick(record.id) },
            )
        }
    }
}

// ─── @Preview catalogue (Task 6 — 10 previews) ────────────────────────

private fun sampleRecords(count: Int): List<SessionRecord> {
    val labels = VerdictLabel.entries
    return List(count) { idx ->
        SessionRecord(
            id = "preview-$idx",
            timestampMs = (count - idx).toLong() * 1_000L,
            verdictLabel = labels[idx % labels.size],
            headline = "Preview headline #${idx + 1} — sample claim under verification",
            contextLines = emptyList(),
            sourceLinks = emptyList<SourceCitation>(),
            ocrText = "",
            regionalBiasNote = null,
            injectionDetected = false,
        )
    }
}

@Preview(showBackground = true, name = "Loading • Light")
@Composable
private fun HistoryContentLoadingLightPreview() {
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

@Preview(showBackground = true, name = "Loading • Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HistoryContentLoadingDarkPreview() {
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

@Preview(showBackground = true, name = "Empty • Light")
@Composable
private fun HistoryContentEmptyLightPreview() {
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

@Preview(showBackground = true, name = "Empty • Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HistoryContentEmptyDarkPreview() {
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

@Preview(showBackground = true, name = "Content 1 record • Light")
@Composable
private fun HistoryContentContent1RecordLightPreview() {
    VeriSphereTheme {
        Surface {
            HistoryContent(
                state = HistoryUiState.Content(sampleRecords(1)),
                onItemClick = {},
                onBackClick = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Content 1 record • Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HistoryContentContent1RecordDarkPreview() {
    VeriSphereTheme {
        Surface {
            HistoryContent(
                state = HistoryUiState.Content(sampleRecords(1)),
                onItemClick = {},
                onBackClick = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Content 5 records • Light")
@Composable
private fun HistoryContentContent5RecordsLightPreview() {
    VeriSphereTheme {
        Surface {
            HistoryContent(
                state = HistoryUiState.Content(sampleRecords(5)),
                onItemClick = {},
                onBackClick = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Content 5 records • Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HistoryContentContent5RecordsDarkPreview() {
    VeriSphereTheme {
        Surface {
            HistoryContent(
                state = HistoryUiState.Content(sampleRecords(5)),
                onItemClick = {},
                onBackClick = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Content 50 records • Light", heightDp = 1200)
@Composable
private fun HistoryContentContent50RecordsLightPreview() {
    VeriSphereTheme {
        Surface {
            HistoryContent(
                state = HistoryUiState.Content(sampleRecords(50)),
                onItemClick = {},
                onBackClick = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Content 50 records • Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, heightDp = 1200)
@Composable
private fun HistoryContentContent50RecordsDarkPreview() {
    VeriSphereTheme {
        Surface {
            HistoryContent(
                state = HistoryUiState.Content(sampleRecords(50)),
                onItemClick = {},
                onBackClick = {},
            )
        }
    }
}

// ─── Story 6.3 — banner-above-history @Preview catalogue ─────────────

private val PREVIEW_BANNER_PAYLOAD = UpdateBannerPayload(
    version = "0.2.0",
    downloadUrl = "https://drive.google.com/file/d/preview",
)

@Preview(showBackground = true, name = "Empty + banner • Light")
@Composable
private fun HistoryContentEmptyWithBannerLightPreview() {
    VeriSphereTheme {
        Surface {
            HistoryContent(
                state = HistoryUiState.Empty,
                onItemClick = {},
                onBackClick = {},
                updateBanner = PREVIEW_BANNER_PAYLOAD,
            )
        }
    }
}

@Preview(showBackground = true, name = "Content 5 records + banner • Light")
@Composable
private fun HistoryContentContent5RecordsWithBannerLightPreview() {
    VeriSphereTheme {
        Surface {
            HistoryContent(
                state = HistoryUiState.Content(sampleRecords(5)),
                onItemClick = {},
                onBackClick = {},
                updateBanner = PREVIEW_BANNER_PAYLOAD,
            )
        }
    }
}

/**
 * Story 4.3 — Intent action namespace for the history surface.
 *
 * `HistoryScreen` is a top-level `@Composable fun`, not a class — Kotlin
 * does not allow `companion object` on top-level functions. The sibling
 * `object` is the idiomatic Kotlin/Android pattern for grouping stable
 * Intent contracts (mirrors Android's `Intent.ACTION_*` constants on a
 * sibling type-namespace) and preserves the epics-spec phrasing
 * (`HistoryScreen.ACTION_OPEN`) closely enough that no spec amendment is
 * required.
 *
 * Mirrors the Story 1.8.5 `MainActivity.ACTION_REQUEST_ACCESSIBILITY`
 * format (`com.verisphere.app.action.<NAME>` prefix, SCREAMING_SNAKE_CASE
 * suffix) so the action-string family stays consistent.
 *
 * **Public visibility intentional** — the action string IS the public
 * Intent contract. The bubble service's `buildOpenHistoryIntent`
 * (different package) and `MainActivity.onNewIntent` (different package)
 * both read it; marking `internal` would compile but mislead readers
 * about the contract's stability.
 */
object HistoryScreenIntent {
    const val ACTION_OPEN: String = "com.verisphere.app.action.OPEN_HISTORY"
}
