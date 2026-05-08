package com.verisphere.app.bubble.ui

import android.content.res.Configuration
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.verisphere.app.R
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.ui.theme.VeriSphereTheme
import kotlin.math.roundToInt

/**
 * Two-tier flash verdict tooltip rendered beside the bubble (UX-DR6,
 * UX spec lines 673–680; PRD FR11, FR13, FR26).
 *
 * **Anatomy** (UX-DR6 line 676):
 *  - [Surface] with `tonalElevation = 4.dp`, rounded corners, background
 *    coloured by [verdictBackgroundFor]/[verdictLabel] (UX Step 8 palette).
 *  - 8 dp pointer triangle drawn via [Modifier.drawBehind] on the side
 *    facing the bubble.
 *  - Two `Text` composables stacked vertically:
 *      • verdict word in `headlineMedium` 18 sp (UX-DR2);
 *      • headline in `bodyMedium` 14 sp on `onSurfaceVariant`.
 *
 * **Sizing** (UX-DR4 + UX spec line 423): max-width 75 % of screen
 * width; padding 12 dp horizontal / 8 dp vertical.
 *
 * **Auto-fade** (UX-DR6 line 679): the [textFaded] flag flips after the
 * 5–8 s timer fires in [com.verisphere.app.bubble.BubbleStateMachine].
 * The text alpha animates from 1.0 to 0.0 over [TEXT_FADE_MS] ms; the
 * Surface background stays fully opaque so the bubble retains its
 * semantic colour until the next user gesture.
 *
 * **Accessibility** (UX-DR6 line 680, UX-DR18):
 *  - `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` so
 *    TalkBack announces the verdict on appearance.
 *  - `contentDescription` resolves to "Verdict: {label}, {headline}".
 *  - Verdict word colour follows the UX Step 8 contrast rule — DOUBTFUL
 *    uses dark text on amber (`#F9AB00` text on white fails WCAG AA);
 *    every other verdict uses white text on its semantic background.
 */
@Composable
fun FlashTooltip(
    verdictLabel: VerdictLabel,
    headline: String,
    textFaded: Boolean,
    pointerDirection: PointerDirection,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val maxWidthDp = (configuration.screenWidthDp * MAX_WIDTH_FRACTION).roundToInt().dp

    val backgroundColor = colorResource(verdictBackgroundFor(verdictLabel))
    val verdictTextColor = verdictTextColorFor(verdictLabel)
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val textAlpha by animateFloatAsState(
        targetValue = if (textFaded) 0f else 1f,
        animationSpec = tween(durationMillis = TEXT_FADE_MS),
        label = "tooltipTextAlpha",
    )

    val verdictContentDescription = stringResource(verdictContentDescriptionFor(verdictLabel)) +
        ". " + headline

    Row(
        modifier = modifier
            .widthIn(max = maxWidthDp)
            .semantics(mergeDescendants = true) {
                contentDescription = verdictContentDescription
                if (textFaded) {
                    // Code-review patch P4 — drop liveRegion + mark
                    // invisible to TalkBack once the tooltip text has
                    // faded. Without this, the polite live-region
                    // ré-announces the verdict on every recomposition
                    // even though alpha = 0; the bubble's persistent
                    // contentDescription (BubbleOverlay) carries the
                    // remaining accessibility surface.
                    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                    invisibleToUser()
                } else {
                    liveRegion = LiveRegionMode.Polite
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pointerDirection == PointerDirection.LEFT) {
            PointerTriangle(direction = PointerDirection.LEFT, color = backgroundColor)
        }

        Surface(
            tonalElevation = TONAL_ELEVATION_DP.dp,
            shape = RoundedCornerShape(CORNER_RADIUS_DP.dp),
            color = backgroundColor,
        ) {
            Column(
                modifier = Modifier
                    .padding(
                        horizontal = HORIZONTAL_PADDING_DP.dp,
                        vertical = VERTICAL_PADDING_DP.dp,
                    )
                    .alpha(textAlpha),
            ) {
                Text(
                    text = stringResource(verdictWordResFor(verdictLabel)),
                    style = MaterialTheme.typography.headlineMedium,
                    color = verdictTextColor,
                )
                Text(
                    text = headline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurfaceVariant,
                )
            }
        }

        if (pointerDirection == PointerDirection.RIGHT) {
            PointerTriangle(direction = PointerDirection.RIGHT, color = backgroundColor)
        }
    }
}

/** Side of the flash tooltip that points toward the bubble. */
enum class PointerDirection { LEFT, RIGHT }

@Composable
private fun PointerTriangle(direction: PointerDirection, color: Color) {
    Box(
        modifier = Modifier
            .size(POINTER_SIZE_DP.dp)
            .drawBehind {
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    when (direction) {
                        // Pointer LEFT: tip on the left edge (pointing
                        // toward the bubble that sits on the left). Base
                        // on the right edge.
                        PointerDirection.LEFT -> {
                            moveTo(0f, h / 2f)
                            lineTo(w, 0f)
                            lineTo(w, h)
                            close()
                        }
                        PointerDirection.RIGHT -> {
                            moveTo(w, h / 2f)
                            lineTo(0f, 0f)
                            lineTo(0f, h)
                            close()
                        }
                    }
                }
                drawPath(path = path, color = color)
            },
    )
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

@ColorRes
internal fun verdictBackgroundFor(label: VerdictLabel): Int = when (label) {
    VerdictLabel.TRUE -> R.color.vs_verdict_true
    VerdictLabel.FALSE -> R.color.vs_verdict_false
    VerdictLabel.DOUBTFUL -> R.color.vs_verdict_doubtful
    VerdictLabel.NON_VERIFIABLE -> R.color.vs_verdict_non_verifiable
}

/**
 * UX Step 8 footnote — the amber `#F9AB00` background fails WCAG AA
 * against white text. DOUBTFUL therefore renders the verdict word in
 * dark text; every other verdict uses white on coloured background.
 *
 * The DOUBTFUL text colour is theme-independent (the amber background
 * is the same RGB in light and dark themes per UX Step 8), so we use
 * a hard-coded `#1F1F1F` rather than `MaterialTheme.colorScheme.onSurface`
 * which would invert under dark theme.
 */
@Composable
@ReadOnlyComposable
private fun verdictTextColorFor(label: VerdictLabel): Color = when (label) {
    VerdictLabel.DOUBTFUL -> Color(VS_DOUBTFUL_TEXT_COLOR_ARGB)
    else -> Color.White
}

private const val MAX_WIDTH_FRACTION = 0.75f
private const val TONAL_ELEVATION_DP = 4f
private const val CORNER_RADIUS_DP = 8f
private const val HORIZONTAL_PADDING_DP = 12f
private const val VERTICAL_PADDING_DP = 8f
private const val POINTER_SIZE_DP = 8f
private const val TEXT_FADE_MS = 300

private const val VS_DOUBTFUL_TEXT_COLOR_ARGB = 0xFF1F1F1F.toInt()

// ----- Previews -------------------------------------------------------

private const val SAMPLE_HEADLINE_TRUE =
    "Confirmed by 3 independent newspapers between 2024 and 2026."
private const val SAMPLE_HEADLINE_FALSE =
    "Contradicted by official statements and primary-source reporting."
private const val SAMPLE_HEADLINE_DOUBTFUL =
    "Mixed reporting; some sources support, others contradict."
private const val SAMPLE_HEADLINE_NV =
    "No corroborating sources surfaced for this claim."

@Preview(showBackground = true, name = "Flash - TRUE - Light")
@Composable
private fun FlashTooltipTrueLightPreview() {
    VeriSphereTheme {
        FlashTooltip(
            verdictLabel = VerdictLabel.TRUE,
            headline = SAMPLE_HEADLINE_TRUE,
            textFaded = false,
            pointerDirection = PointerDirection.LEFT,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Preview(
    showBackground = true,
    name = "Flash - TRUE - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun FlashTooltipTrueDarkPreview() {
    VeriSphereTheme {
        FlashTooltip(
            verdictLabel = VerdictLabel.TRUE,
            headline = SAMPLE_HEADLINE_TRUE,
            textFaded = false,
            pointerDirection = PointerDirection.LEFT,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "Flash - FALSE - Light")
@Composable
private fun FlashTooltipFalseLightPreview() {
    VeriSphereTheme {
        FlashTooltip(
            verdictLabel = VerdictLabel.FALSE,
            headline = SAMPLE_HEADLINE_FALSE,
            textFaded = false,
            pointerDirection = PointerDirection.RIGHT,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Preview(
    showBackground = true,
    name = "Flash - FALSE - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun FlashTooltipFalseDarkPreview() {
    VeriSphereTheme {
        FlashTooltip(
            verdictLabel = VerdictLabel.FALSE,
            headline = SAMPLE_HEADLINE_FALSE,
            textFaded = false,
            pointerDirection = PointerDirection.RIGHT,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "Flash - DOUBTFUL - Light")
@Composable
private fun FlashTooltipDoubtfulLightPreview() {
    VeriSphereTheme {
        FlashTooltip(
            verdictLabel = VerdictLabel.DOUBTFUL,
            headline = SAMPLE_HEADLINE_DOUBTFUL,
            textFaded = false,
            pointerDirection = PointerDirection.LEFT,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Preview(
    showBackground = true,
    name = "Flash - DOUBTFUL - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun FlashTooltipDoubtfulDarkPreview() {
    VeriSphereTheme {
        FlashTooltip(
            verdictLabel = VerdictLabel.DOUBTFUL,
            headline = SAMPLE_HEADLINE_DOUBTFUL,
            textFaded = false,
            pointerDirection = PointerDirection.LEFT,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "Flash - NON-VERIFIABLE - Light")
@Composable
private fun FlashTooltipNonVerifiableLightPreview() {
    VeriSphereTheme {
        FlashTooltip(
            verdictLabel = VerdictLabel.NON_VERIFIABLE,
            headline = SAMPLE_HEADLINE_NV,
            textFaded = false,
            pointerDirection = PointerDirection.RIGHT,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Preview(
    showBackground = true,
    name = "Flash - NON-VERIFIABLE - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun FlashTooltipNonVerifiableDarkPreview() {
    VeriSphereTheme {
        FlashTooltip(
            verdictLabel = VerdictLabel.NON_VERIFIABLE,
            headline = SAMPLE_HEADLINE_NV,
            textFaded = false,
            pointerDirection = PointerDirection.RIGHT,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "Flash - TRUE - text faded - Light")
@Composable
private fun FlashTooltipTrueFadedLightPreview() {
    VeriSphereTheme {
        FlashTooltip(
            verdictLabel = VerdictLabel.TRUE,
            headline = SAMPLE_HEADLINE_TRUE,
            textFaded = true,
            pointerDirection = PointerDirection.LEFT,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Preview(
    showBackground = true,
    name = "Flash - FALSE - text faded - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun FlashTooltipFalseFadedDarkPreview() {
    VeriSphereTheme {
        FlashTooltip(
            verdictLabel = VerdictLabel.FALSE,
            headline = SAMPLE_HEADLINE_FALSE,
            textFaded = true,
            pointerDirection = PointerDirection.RIGHT,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}
