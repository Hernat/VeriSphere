package com.verisphere.app.ui.onboarding

import android.content.res.Configuration
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.verisphere.app.R
import com.verisphere.app.ui.theme.VSSpacing
import com.verisphere.app.ui.theme.VeriSphereTheme
import com.verisphere.app.util.isReduceMotionEnabled

/**
 * First-launch tutorial overlay (Story 5.1, UX-DR11 amended Sprint Change
 * 2026-05-07, FR19). Four sequential cards:
 *
 *  1. **Accessibility activation** — disclosure copy + deep-link CTA. Gated:
 *     the card auto-advances only after [accessibilityServiceEnabled] flips
 *     to `true` (which the host observes via
 *     [android.view.accessibility.AccessibilityManager] in `onResume`).
 *  2. **Long-press** — animated press-pulse demonstration.
 *  3. **Drag**       — horizontal-drift demonstration.
 *  4. **Tap**        — fade-pulse demonstration; final CTA invokes
 *                       [onComplete].
 *
 * **Stateless composable, callbacks only** — mirror of the Story 1.8.5
 * `AccessibilityExplanationScreen` pattern (P7 — "stateless callbacks for
 * testability"). The composable does NOT:
 *   - touch `SecureStorage` (host writes `tutorial_seen` on
 *     [onComplete] / [onSkip] — Story 5.2 territory),
 *   - construct `Intent` (host owns the
 *     `Settings.ACTION_ACCESSIBILITY_SETTINGS` launch in
 *     [onActivateAccessibilityClick]),
 *   - poll `AccessibilityManager` (host observes the service-enabled
 *     state and passes it down as [accessibilityServiceEnabled] —
 *     see CDN #3 in story file).
 *
 * **Skip rule** — the "Skip" `TextButton` is rendered ONLY on cards 2-4.
 * Card 1 has no skip affordance because the bubble cannot capture
 * without the Accessibility service active.
 *
 * **Card-1 auto-advance** — driven by `LaunchedEffect(accessibilityServiceEnabled)`.
 * The `currentCardIndex == 0` guard inside the effect is mandatory:
 * without it, a user disabling Accessibility from Settings while on
 * card 3 would be yanked back to card 2 on re-enable.
 *
 * **`rememberSaveable` index** — survives config-changes triggered by
 * the Settings round-trip on card 1 (rotation, font-scale tweak, dark-
 * mode toggle). Plain `remember` would reset to card 1 mid-tutorial.
 *
 * **Scrim + cut-out** — drawn via a single [Canvas] using
 * [BlendMode.Clear] for the bubble cut-out. `BlendMode.Clear` requires
 * an offscreen compositing layer
 * ([CompositingStrategy.Offscreen]) — without it, the cut-out punches
 * through to the underlying SurfaceView and reveals the OS launcher /
 * source app instead of the scrim's transparent region. Trap-doc per
 * CDN #9.
 *
 * **Reduce-motion** — when [com.verisphere.app.util.isReduceMotionEnabled]
 * returns `true`, the demo composables short-circuit their
 * `infiniteRepeatable` animations and render the bubble glyph statically.
 * Cached once at composable entry per Story 3.4 / `SuctionAnimation` precedent
 * (no observer for mid-session toggles).
 *
 * **All copy via [R.string]** — see [strings_tutorial.xml] (UX-DR17).
 *
 * @param accessibilityServiceEnabled Compose-observable from the host. Card 1
 *   auto-advances to Card 2 the moment this flips to `true`.
 * @param onActivateAccessibilityClick Invoked when the user taps the
 *   "Activer" CTA on card 1. The host should
 *   `startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))` and
 *   handle `ActivityNotFoundException` with a toast (see
 *   [com.verisphere.app.MainActivity.launchAccessibilitySettings]).
 * @param onComplete Invoked when the user taps "Got it" on card 4. The
 *   host should persist `tutorial_seen = true` via [SecureStorage.writeBoolean]
 *   and drop the overlay (Story 5.2).
 * @param onSkip Invoked when the user taps "Skip" on cards 2-4. Same host
 *   contract as [onComplete] — persist `tutorial_seen = true` and drop
 *   the overlay.
 * @param bubbleAnchorOffset Pixel offset of the bubble's last position
 *   (used for the focused cut-out). When `null` OR not finite, no cut-out
 *   is drawn — acceptable V1 fallback per CDN #10 if the host has no
 *   recorded bubble position. Review patch P3: `Offset.Unspecified` /
 *   NaN / Infinity are also skipped (a non-null but unspecified Offset
 *   would otherwise be passed to [drawCircle] and either crash or render
 *   nothing depending on the Skia backend).
 * @param initialCardIndex Seed for the initial card index. Review patch
 *   P7: previews use this to render Cards 2-4 directly via the production
 *   composable (instead of a preview-only stand-in that would drift over
 *   time). Production callers (Story 5.2) leave the default `0` — the
 *   `rememberSaveable` saved value wins on restoration so the seed only
 *   matters on first composition.
 */
@Composable
fun OnboardingTutorialOverlay(
    accessibilityServiceEnabled: Boolean,
    onActivateAccessibilityClick: () -> Unit,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    bubbleAnchorOffset: Offset? = null,
    initialCardIndex: Int = 0,
) {
    var currentCardIndex: Int by rememberSaveable { mutableIntStateOf(initialCardIndex) }

    // Card-1 auto-advance. The `currentCardIndex == 0` guard prevents a
    // late accessibility-service re-enable (after the user disabled it
    // mid-tutorial from Settings) from yanking the user back to card 2.
    LaunchedEffect(accessibilityServiceEnabled) {
        if (currentCardIndex == 0 && accessibilityServiceEnabled) {
            currentCardIndex = 1
        }
    }

    // Cache reduce-motion once. Settings-change mid-tutorial does NOT
    // re-poll (Story 3.4 precedent: reduce-motion is read once per
    // composition; even most system animations behave this way).
    // Review patch P6: `remember {}` (no key) is the lifetime-bound
    // cache the CDN #8 contract documents. The prior `remember(context)`
    // keyed on Context identity, which would re-invoke
    // [isReduceMotionEnabled] under any `CompositionLocalProvider(LocalContext
    // provides …)` override (rare in production, possible in tests).
    val context = LocalContext.current
    val reduceMotion = remember { isReduceMotionEnabled(context) }

    val density = LocalDensity.current
    val cutoutRadiusPx = remember(density) { with(density) { CUTOUT_RADIUS_DP.dp.toPx() } }

    val scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim + cut-out. Single Canvas with BlendMode.Clear punches a
        // transparent hole at the bubble position. CompositingStrategy.Offscreen
        // is load-bearing per CDN #9.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            drawRect(color = scrimColor)
            // Review patch P3 — guard non-finite Offsets. A non-null but
            // `Offset.Unspecified` (`NaN, NaN`) or any NaN/Infinity
            // component reaches [drawCircle] as a degenerate centre and
            // either crashes on strict GPU drivers or no-ops on lenient
            // ones — both worse than just skipping the cut-out.
            bubbleAnchorOffset
                ?.takeIf { it.isSpecified && it.x.isFinite() && it.y.isFinite() }
                ?.let { offset ->
                    drawCircle(
                        color = Color.Transparent,
                        radius = cutoutRadiusPx,
                        center = offset,
                        blendMode = BlendMode.Clear,
                    )
                }
        }

        // Card container — centred, padded, fills width minus side padding.
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(VSSpacing.space24)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(VSSpacing.space24),
                verticalArrangement = Arrangement.spacedBy(VSSpacing.space16),
            ) {
                TutorialCardContent(
                    cardIndex = currentCardIndex,
                    accessibilityServiceEnabled = accessibilityServiceEnabled,
                    reduceMotion = reduceMotion,
                )

                // Bottom action row. Skip on the left (cards 2-4 only),
                // primary CTA on the right (every card).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (currentCardIndex > 0) {
                        TextButton(onClick = onSkip) {
                            Text(text = stringResource(R.string.tutorial_cta_skip))
                        }
                    } else {
                        // Empty spacer keeps the primary button right-aligned
                        // on card 1 (no Skip rendered).
                        Spacer(modifier = Modifier.size(VSSpacing.space4))
                    }

                    val ctaText = when (currentCardIndex) {
                        0 -> stringResource(R.string.tutorial_card_1_cta_activate)
                        in 1..2 -> stringResource(R.string.tutorial_cta_next)
                        else -> stringResource(R.string.tutorial_cta_got_it)
                    }
                    Button(
                        onClick = {
                            when (currentCardIndex) {
                                0 -> onActivateAccessibilityClick()
                                1, 2 -> currentCardIndex += 1
                                else -> onComplete()
                            }
                        },
                    ) {
                        Text(text = ctaText)
                    }
                }
            }
        }
    }
}

/**
 * Shell for the per-card content slot — title, demo glyph, body text, and
 * (card 1 only) the waiting hint. Per-card differentiation is driven by
 * [cardIndex]; [reduceMotion] short-circuits the animated demos on cards
 * 2-4 (Story 3.4 / CDN #8).
 *
 * Private to keep the file's public surface to the single
 * [OnboardingTutorialOverlay] composable (architecture file-naming rule).
 */
@Composable
private fun TutorialCardContent(
    cardIndex: Int,
    accessibilityServiceEnabled: Boolean,
    reduceMotion: Boolean,
) {
    val (titleRes, bodyRes, a11yRes) = when (cardIndex) {
        0 -> Triple(
            R.string.tutorial_card_1_title,
            R.string.tutorial_card_1_body,
            R.string.tutorial_card_1_a11y,
        )
        1 -> Triple(
            R.string.tutorial_card_2_title,
            R.string.tutorial_card_2_body,
            R.string.tutorial_card_2_a11y,
        )
        2 -> Triple(
            R.string.tutorial_card_3_title,
            R.string.tutorial_card_3_body,
            R.string.tutorial_card_3_a11y,
        )
        else -> Triple(
            R.string.tutorial_card_4_title,
            R.string.tutorial_card_4_body,
            R.string.tutorial_card_4_a11y,
        )
    }

    val a11yLabel = stringResource(a11yRes)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Review patches P1 + P2:
            //   - `mergeDescendants = true` (P1) — the parent label
            //     REPLACES the child traversal so TalkBack announces
            //     "Card N of 4: …" once, not the parent label + every
            //     child Text re-announced separately (EC6 over-announce).
            //   - `liveRegion = LiveRegionMode.Polite` (P2) — when the
            //     parent's contentDescription changes (card auto-advance
            //     after the Settings round-trip on card 1), TalkBack
            //     announces the new card without requiring the user to
            //     manually re-traverse (EC5 silent auto-advance).
            .semantics(mergeDescendants = true) {
                contentDescription = a11yLabel
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(VSSpacing.space16),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(DEMO_SLOT_HEIGHT_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (cardIndex) {
                0 -> Card1AccessibilityDemo()
                1 -> Card2LongPressDemo(reduceMotion = reduceMotion)
                2 -> Card3DragDemo(reduceMotion = reduceMotion)
                else -> Card4TapDemo(reduceMotion = reduceMotion)
            }
        }

        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
        )

        // Card 1 waiting hint — visible only while the accessibility service
        // is still off. The auto-advance LaunchedEffect drops it the moment
        // the user returns from Settings with the toggle on.
        if (cardIndex == 0 && !accessibilityServiceEnabled) {
            Text(
                text = stringResource(R.string.tutorial_card_1_waiting_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Card 1 demo — static bubble glyph. No animation; the card content is
 * dominated by the disclosure copy, not by motion.
 *
 * Mirrors the [com.verisphere.app.ui.history.HistoryScreen] `EmptyBubbleGlyph`
 * shape (Story 4.2) — 56 dp circle, `surfaceVariant` fill, centred "G"
 * glyph in `headlineSmall`.
 */
@Composable
private fun Card1AccessibilityDemo() {
    BubbleGlyph()
}

/**
 * Card 2 demo — long-press pulse. Bubble scales 1.0 → 1.15 over ~800 ms,
 * reverses, repeats. Suggests the "hold" gesture without literally
 * showing a finger.
 */
@Composable
private fun Card2LongPressDemo(reduceMotion: Boolean) {
    if (reduceMotion) {
        BubbleGlyph()
        return
    }
    val infinite = rememberInfiniteTransition(label = "tutorial_card_2_pulse")
    val scale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = LONG_PRESS_PULSE_MAX,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = LONG_PRESS_PULSE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tutorial_card_2_scale",
    )
    BubbleGlyph(
        modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale),
    )
}

/**
 * Card 3 demo — horizontal drift. Bubble translates X across a small range,
 * reverses, repeats. Suggests the "drag" gesture.
 */
@Composable
private fun Card3DragDemo(reduceMotion: Boolean) {
    if (reduceMotion) {
        BubbleGlyph()
        return
    }
    // Review patch P9 — convert the dp constant to px via LocalDensity
    // so the drift looks the same across densities. `graphicsLayer.translationX`
    // takes raw pixels.
    val density = LocalDensity.current
    val driftPx = remember(density) { with(density) { DRAG_DRIFT_RANGE_DP.dp.toPx() } }
    val infinite = rememberInfiniteTransition(label = "tutorial_card_3_drift")
    val translateX by infinite.animateFloat(
        initialValue = -driftPx,
        targetValue = driftPx,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = DRAG_DRIFT_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tutorial_card_3_translateX",
    )
    BubbleGlyph(
        modifier = Modifier.graphicsLayer(translationX = translateX),
    )
}

/**
 * Card 4 demo — fade pulse. Bubble alpha 1.0 → 0.6, reverses, repeats.
 * Suggests the "tap" gesture (brief acknowledgement).
 */
@Composable
private fun Card4TapDemo(reduceMotion: Boolean) {
    if (reduceMotion) {
        BubbleGlyph()
        return
    }
    val infinite = rememberInfiniteTransition(label = "tutorial_card_4_tap")
    val alpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = TAP_FADE_MIN,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = TAP_FADE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tutorial_card_4_alpha",
    )
    BubbleGlyph(
        modifier = Modifier.graphicsLayer(alpha = alpha),
    )
}

/**
 * 56 dp circular bubble glyph reused across the four demo composables.
 * Mirrors [com.verisphere.app.ui.history.HistoryScreen] `EmptyBubbleGlyph`
 * shape (Story 4.2). Decorative — `contentDescription` is supplied at the
 * card-content level via the a11y label parameter.
 */
@Composable
private fun BubbleGlyph(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(BUBBLE_GLYPH_SIZE_DP.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = BUBBLE_GLYPH_LETTER,
                style = MaterialTheme.typography.headlineMedium,
            )
        }
    }
}

// ─── Tuning constants ──────────────────────────────────────────────────────

private const val SCRIM_ALPHA: Float = 0.72f
private const val CUTOUT_RADIUS_DP: Int = 48
private const val DEMO_SLOT_HEIGHT_DP: Int = 120
private const val BUBBLE_GLYPH_SIZE_DP: Int = 56
private const val BUBBLE_GLYPH_LETTER: String = "G"

// Card-2 long-press pulse: 1.0 → 1.15 scale over 1000 ms, reverse, repeat.
// Review patch P10: bumped 800 → 1000 ms so the visual pulse cadence
// matches the Card-2 body copy "Hold the bubble for 1 second".
private const val LONG_PRESS_PULSE_MAX: Float = 1.15f
private const val LONG_PRESS_PULSE_MS: Int = 1000

// Card-3 drag drift: ±24 dp translation X over 1000 ms, reverse, repeat.
// Review patch P9: stored as dp (not px) so the drift is visually
// equivalent across densities. Converted to px inside [Card3DragDemo]
// via `LocalDensity` — on a 3x device the prior 32f raw-px literal was
// invisibly tiny (~10.6 dp); 24 dp here is a deliberate visual hint
// across the density range.
private const val DRAG_DRIFT_RANGE_DP: Int = 24
private const val DRAG_DRIFT_MS: Int = 1000

// Card-4 tap fade: 1.0 → 0.6 alpha over 600 ms, reverse, repeat.
private const val TAP_FADE_MIN: Float = 0.6f
private const val TAP_FADE_MS: Int = 600

// ─── @Preview catalogue (8 entries: 4 cards × Light/Dark) ──────────────────

@Preview(showBackground = true, name = "Card 1 Light")
@Composable
private fun OnboardingTutorialOverlayCard1LightPreview() {
    VeriSphereTheme {
        OnboardingTutorialOverlay(
            accessibilityServiceEnabled = false,
            onActivateAccessibilityClick = {},
            onComplete = {},
            onSkip = {},
            bubbleAnchorOffset = Offset(900f, 400f),
        )
    }
}

@Preview(
    showBackground = true,
    name = "Card 1 Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun OnboardingTutorialOverlayCard1DarkPreview() {
    VeriSphereTheme {
        OnboardingTutorialOverlay(
            accessibilityServiceEnabled = false,
            onActivateAccessibilityClick = {},
            onComplete = {},
            onSkip = {},
            bubbleAnchorOffset = Offset(900f, 400f),
        )
    }
}

@Preview(showBackground = true, name = "Card 1 Large Font", fontScale = 1.5f)
@Composable
private fun OnboardingTutorialOverlayCard1LargeFontPreview() {
    VeriSphereTheme {
        OnboardingTutorialOverlay(
            accessibilityServiceEnabled = false,
            onActivateAccessibilityClick = {},
            onComplete = {},
            onSkip = {},
            bubbleAnchorOffset = Offset(900f, 400f),
        )
    }
}

@Preview(showBackground = true, name = "Card 2 Light")
@Composable
private fun OnboardingTutorialOverlayCard2LightPreview() {
    VeriSphereTheme {
        OnboardingTutorialOverlay(
            accessibilityServiceEnabled = true,
            onActivateAccessibilityClick = {},
            onComplete = {},
            onSkip = {},
            bubbleAnchorOffset = Offset(900f, 400f),
            initialCardIndex = 1,
        )
    }
}

@Preview(
    showBackground = true,
    name = "Card 2 Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun OnboardingTutorialOverlayCard2DarkPreview() {
    VeriSphereTheme {
        OnboardingTutorialOverlay(
            accessibilityServiceEnabled = true,
            onActivateAccessibilityClick = {},
            onComplete = {},
            onSkip = {},
            bubbleAnchorOffset = Offset(900f, 400f),
            initialCardIndex = 1,
        )
    }
}

@Preview(showBackground = true, name = "Card 3 Light")
@Composable
private fun OnboardingTutorialOverlayCard3LightPreview() {
    VeriSphereTheme {
        OnboardingTutorialOverlay(
            accessibilityServiceEnabled = true,
            onActivateAccessibilityClick = {},
            onComplete = {},
            onSkip = {},
            bubbleAnchorOffset = Offset(900f, 400f),
            initialCardIndex = 2,
        )
    }
}

@Preview(
    showBackground = true,
    name = "Card 3 Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun OnboardingTutorialOverlayCard3DarkPreview() {
    VeriSphereTheme {
        OnboardingTutorialOverlay(
            accessibilityServiceEnabled = true,
            onActivateAccessibilityClick = {},
            onComplete = {},
            onSkip = {},
            bubbleAnchorOffset = Offset(900f, 400f),
            initialCardIndex = 2,
        )
    }
}

@Preview(showBackground = true, name = "Card 4 Light")
@Composable
private fun OnboardingTutorialOverlayCard4LightPreview() {
    VeriSphereTheme {
        OnboardingTutorialOverlay(
            accessibilityServiceEnabled = true,
            onActivateAccessibilityClick = {},
            onComplete = {},
            onSkip = {},
            bubbleAnchorOffset = Offset(900f, 400f),
            initialCardIndex = 3,
        )
    }
}

@Preview(
    showBackground = true,
    name = "Card 4 Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun OnboardingTutorialOverlayCard4DarkPreview() {
    VeriSphereTheme {
        OnboardingTutorialOverlay(
            accessibilityServiceEnabled = true,
            onActivateAccessibilityClick = {},
            onComplete = {},
            onSkip = {},
            bubbleAnchorOffset = Offset(900f, 400f),
            initialCardIndex = 3,
        )
    }
}
