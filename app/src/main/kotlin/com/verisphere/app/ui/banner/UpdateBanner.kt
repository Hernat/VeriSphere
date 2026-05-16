package com.verisphere.app.ui.banner

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.verisphere.app.R
import com.verisphere.app.ui.theme.VSSpacing
import com.verisphere.app.ui.theme.VeriSphereTheme

/**
 * Story 6.2 — discreet full-width banner shown at the top of the
 * history list when [com.verisphere.app.update.VersionChecker] has
 * persisted an `update_available_version` to `SecureStorage` (Story 6.1
 * AC #4). The visibility predicate + dismissal persistence land in
 * Story 6.3 — Story 6.2 ships the composable + Intent factory only.
 *
 * **Stateless** (CDN #1) — every interaction surfaces through
 * [onDownloadClick] and [onDismiss]; no internal state, no
 * `LaunchedEffect`, no animation. Architecture L508–510 — state
 * hoisted to the caller. Compose UI tests instantiate the composable
 * directly without ViewModel scaffolding (mirrors `HistoryItemRow` /
 * `SourceLinkChip` / `BatteryOptimizationBottomSheet` pattern).
 *
 * **Anatomy** (epics L890 + UX spec L701, L735–736 verbatim):
 *  - Full-width `Surface` (`surfaceVariant` background).
 *  - 16 dp internal padding.
 *  - Row: leading info icon → body text → primary filled Download
 *    button → trailing dismiss IconButton.
 *
 * @param version SemVer string from `version-info.json.latestVersion`
 *   (Story 6.1 contract). Rendered verbatim into the body text via
 *   [R.string.update_banner_text] — no v-prefix, no quoting.
 * @param onDownloadClick Invoked on primary "Download" Button click.
 *   Caller routes to
 *   `context.startActivity(buildUpdateDownloadIntent(downloadUrl))`.
 * @param onDismiss Invoked on trailing dismiss `IconButton` click.
 *   Caller persists the dismissal flag (Story 6.3) and hides the
 *   banner immediately (epics L910).
 */
@Composable
fun UpdateBanner(
    version: String,
    onDownloadClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TEST_TAG_BANNER),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(VSSpacing.space16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Decorative icon — contentDescription null so TalkBack does
            // not double-announce ("Info" + body text). The body Text
            // carries the user-facing semantics.
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(ICON_SIZE_DP),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(VSSpacing.space12))
            // P1 (review 2026-05-16) — color is INHERITED from
            // Surface(contentColor = onSurfaceVariant) above; no explicit
            // override needed. P5 — maxLines = 2 + Ellipsis prevents
            // unbounded vertical growth at fontScale > 1.5× combined
            // with long version strings, while still allowing a 2-line
            // wrap on narrow screens (320 dp foldable inner cover).
            Text(
                text = stringResource(R.string.update_banner_text, version),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(VSSpacing.space8))
            Button(onClick = onDownloadClick) {
                // P5 — maxLines = 1 so the "Download" label never wraps,
                // keeping the Button shape stable across locales/fontScale.
                Text(
                    text = stringResource(R.string.update_banner_download),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(VSSpacing.space4))
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(TEST_TAG_DISMISS),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(
                        R.string.update_banner_dismiss_content_description,
                    ),
                )
            }
        }
    }
}

/**
 * Story 6.2 — Intent factory for the banner's "Download" CTA.
 *
 * File-level (NOT inside [UpdateBanner]) so JVM tests can call it
 * without instantiating the composable (Story 2.4 `buildDetailPanelIntent`
 * precedent). The wrapped URL is GUARANTEED https by upstream:
 * `VersionChecker` patch P3 rejects non-https `downloadUrl` and clears
 * the keys, so any value reaching this factory has passed the gate
 * (CDN #8 — never re-validate here).
 *
 * `FLAG_ACTIVITY_NEW_TASK` is set so the call site can launch from any
 * `Context` (activity OR service), matching the
 * `launchAccessibilityExplanationActivity` (Story 1.8.5) and
 * `buildDetailPanelIntent` (Story 2.4) precedent.
 */
fun buildUpdateDownloadIntent(downloadUrl: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

internal const val TEST_TAG_BANNER: String = "update_banner"
internal const val TEST_TAG_DISMISS: String = "update_banner_dismiss"

private val ICON_SIZE_DP = 24.dp

// ─── @Preview catalogue (AC #11 — 4 entries) ───────────────────────────

@Preview(showBackground = true, name = "Update banner • Light")
@Composable
private fun UpdateBannerLightPreview() {
    VeriSphereTheme {
        Surface {
            UpdateBanner(
                version = "0.2.0",
                onDownloadClick = {},
                onDismiss = {},
            )
        }
    }
}

@Preview(
    showBackground = true,
    name = "Update banner • Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun UpdateBannerDarkPreview() {
    VeriSphereTheme {
        Surface {
            UpdateBanner(
                version = "0.2.0",
                onDownloadClick = {},
                onDismiss = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Update banner • long version • Light")
@Composable
private fun UpdateBannerLongVersionLightPreview() {
    VeriSphereTheme {
        Surface {
            UpdateBanner(
                version = "1.10.99",
                onDownloadClick = {},
                onDismiss = {},
            )
        }
    }
}

@Preview(
    showBackground = true,
    name = "Update banner • long version • Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun UpdateBannerLongVersionDarkPreview() {
    VeriSphereTheme {
        Surface {
            UpdateBanner(
                version = "1.10.99",
                onDownloadClick = {},
                onDismiss = {},
            )
        }
    }
}
