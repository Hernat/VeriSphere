package com.verisphere.app.ui.onboarding

import android.content.res.Configuration
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.verisphere.app.R
import com.verisphere.app.ui.theme.VSPalette
import com.verisphere.app.ui.theme.VSSpacing
import com.verisphere.app.ui.theme.VSTypography
import com.verisphere.app.ui.theme.VeriSphereTheme
import com.verisphere.app.util.isReduceMotionEnabled

/**
 * First-launch tutorial (Story 5.1, UX-DR11 amended Sprint Change 2026-05-07,
 * FR19). Four sequential full-screen cards :
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
 * **Wispr-Flow refonte 2026-05-19** — converted from a centred `Card` on a
 * dark scrim (with a `BlendMode.Clear` bubble cut-out) to the full-screen
 * editorial pattern shared with [AccessibilityExplanationScreen]:
 *   - 72 dp sage rounded-square icon at the top centre,
 *   - editorial serif title (EB Garamond 28 sp),
 *   - Figtree 17 sp body, centred, max 340 dp wide,
 *   - bottom-anchored full-width sage CTA (52 dp height, 14 dp corners),
 *   - "Passer" `TextButton` below the CTA on cards 2-4 (Card 1 unchanged —
 *     no skip affordance until the Accessibility service is on).
 * The scrim + cut-out + [bubbleAnchorOffset] parameter were removed
 * outright (the previous design relied on punching a transparent hole in
 * the scrim to highlight the live bubble ; the full-screen canvas has no
 * scrim to punch through). The animated demo glyph still teaches the
 * gesture — it is now positioned inside the sage frame and animates the
 * inner glyph only, so the frame stays static.
 *
 * **Stateless composable, callbacks only** — mirror of the Story 1.8.5
 * [AccessibilityExplanationScreen] pattern (P7 — "stateless callbacks for
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
 * **Skip rule** — the "Passer" `TextButton` is rendered ONLY on cards 2-4.
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
 * **Reduce-motion** — when [com.verisphere.app.util.isReduceMotionEnabled]
 * returns `true`, the demo composables short-circuit their
 * `infiniteRepeatable` animations and render the bubble glyph statically.
 * Cached once at composable entry per Story 3.4 / `SuctionAnimation` precedent
 * (no observer for mid-session toggles).
 *
 * **All copy via [R.string]** — see `strings_tutorial.xml` (UX-DR17).
 *
 * @param accessibilityServiceEnabled Compose-observable from the host. Card 1
 *   auto-advances to Card 2 the moment this flips to `true`.
 * @param onActivateAccessibilityClick Invoked when the user taps the
 *   "Activer" CTA on card 1. The host should
 *   `startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))` and
 *   handle `ActivityNotFoundException` with a toast (see
 *   [com.verisphere.app.MainActivity.launchAccessibilitySettings]).
 * @param onComplete Invoked when the user taps "OK" on card 4. The host
 *   should persist `tutorial_seen = true` via `SecureStorage.writeBoolean`
 *   and drop the overlay (Story 5.2).
 * @param onSkip Invoked when the user taps "Passer" on cards 2-4. Same
 *   host contract as [onComplete] — persist `tutorial_seen = true` and
 *   drop the overlay.
 * @param initialCardIndex Seed for the initial card index. Production
 *   callers (Story 5.2) leave the default `0` — the `rememberSaveable`
 *   saved value wins on restoration so the seed only matters on first
 *   composition. Previews use it to render Cards 2-4 directly via the
 *   production composable.
 */
@Composable
fun OnboardingTutorialOverlay(
    accessibilityServiceEnabled: Boolean,
    onActivateAccessibilityClick: () -> Unit,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
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
    // re-poll (Story 3.4 precedent : reduce-motion is read once per
    // composition ; even most system animations behave this way). Lifetime-
    // bound `remember {}` (no key) per CDN #8 — keying on `LocalContext`
    // would re-invoke the helper under any `CompositionLocalProvider`
    // Context override (rare in production, possible in tests).
    val context = LocalContext.current
    val reduceMotion = remember { isReduceMotionEnabled(context) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = VSPalette.canvas,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = VSSpacing.space32,
                    vertical = VSSpacing.space40,
                )
                .heightIn(min = TUTORIAL_SCREEN_MIN_CONTENT_DP.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TutorialCardContent(
                cardIndex = currentCardIndex,
                accessibilityServiceEnabled = accessibilityServiceEnabled,
                reduceMotion = reduceMotion,
            )

            Spacer(modifier = Modifier.weight(1f))

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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VSPalette.accentSageDeep,
                    contentColor = VSPalette.onAccentSageDeep,
                ),
            ) {
                Text(text = ctaText, style = VSTypography.headlineBodySans)
            }

            Spacer(modifier = Modifier.height(VSSpacing.space8))

            // "Passer" link below the CTA — cards 2-4 only. Card 1 must
            // surface the Accessibility-service deep-link before any
            // skip is offered (the bubble cannot capture without it).
            if (currentCardIndex > 0) {
                TextButton(onClick = onSkip) {
                    Text(
                        text = stringResource(R.string.tutorial_cta_skip),
                        style = VSTypography.bodySans,
                        color = VSPalette.inkMuted,
                    )
                }
            } else {
                // Reserve symmetric vertical space so the CTA doesn't
                // hop ~36 dp when card 1 auto-advances to card 2.
                Spacer(modifier = Modifier.height(TUTORIAL_SKIP_PLACEHOLDER_DP.dp))
            }
        }
    }
}

/**
 * Per-card content slot — sage frame + animated demo glyph, title,
 * body, and (card 1 only) the waiting hint. Per-card differentiation is
 * driven by [cardIndex] ; [reduceMotion] short-circuits the animated
 * demos on cards 2-4 (Story 3.4 / CDN #8).
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
            // Patches P1 + P2 from the legacy implementation kept verbatim :
            //   - `mergeDescendants = true` — the parent label REPLACES
            //     the child traversal so TalkBack announces "Card N of 4: …"
            //     once, not the parent label + every child Text re-announced
            //     separately (EC6 over-announce).
            //   - `liveRegion = LiveRegionMode.Polite` — when the parent's
            //     contentDescription changes (card auto-advance after the
            //     Settings round-trip on card 1), TalkBack announces the new
            //     card without requiring the user to manually re-traverse
            //     (EC5 silent auto-advance).
            .semantics(mergeDescendants = true) {
                contentDescription = a11yLabel
                liveRegion = LiveRegionMode.Polite
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(VSSpacing.space40))

        TutorialBubbleGlyph(cardIndex = cardIndex, reduceMotion = reduceMotion)

        Spacer(modifier = Modifier.height(VSSpacing.space32))

        Text(
            text = stringResource(titleRes),
            style = VSTypography.headlineSerif,
            color = VSPalette.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = TUTORIAL_TITLE_MAX_WIDTH_DP.dp),
        )

        Spacer(modifier = Modifier.height(VSSpacing.space20))

        Text(
            text = stringResource(bodyRes),
            style = VSTypography.bodyLargeSans,
            color = VSPalette.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = TUTORIAL_BODY_MAX_WIDTH_DP.dp),
        )

        // Card 1 waiting hint — visible only while the accessibility
        // service is still off. The auto-advance LaunchedEffect drops it
        // the moment the user returns from Settings with the toggle on.
        if (cardIndex == 0 && !accessibilityServiceEnabled) {
            Spacer(modifier = Modifier.height(VSSpacing.space16))
            Text(
                text = stringResource(R.string.tutorial_card_1_waiting_hint),
                style = VSTypography.labelTrackedSans,
                color = VSPalette.inkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = TUTORIAL_BODY_MAX_WIDTH_DP.dp),
            )
        }
    }
}

/**
 * 72 dp sage rounded-square frame containing the VeriSphere brand logo
 * ([R.drawable.logo_vs]). The frame stays static across all 4 cards
 * (visually unifies with [AccessibilityExplanationScreen]'s brand
 * mark) ; the inner logo is what animates :
 *   - Card 0 — static.
 *   - Card 1 — long-press pulse (scale 1.0 → 1.15 → 1.0, 1000 ms).
 *   - Card 2 — drag drift (translateX ±24 dp, 1000 ms).
 *   - Card 3 — tap fade (alpha 1.0 → 0.6 → 1.0, 600 ms).
 *
 * Animations are skipped under [reduceMotion] per Story 3.4 precedent.
 */
@Composable
private fun TutorialBubbleGlyph(cardIndex: Int, reduceMotion: Boolean) {
    Surface(
        modifier = Modifier.size(TUTORIAL_GLYPH_FRAME_DP.dp),
        shape = RoundedCornerShape(20.dp),
        color = VSPalette.accentSage.copy(alpha = 0.18f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Logo round-clip 2026-05-19 — the source PNG's white
            // square corners would otherwise poke past the sage frame's
            // 20 dp rounded corners. The inner logo is clipped to a
            // proportionally tighter rounded square (14 dp at 48 dp,
            // matches the 20 dp / 72 dp parent ratio) so the rounded
            // outline mirrors the container.
            Image(
                painter = painterResource(R.drawable.logo_vs),
                contentDescription = null,
                modifier = Modifier
                    .size(TUTORIAL_GLYPH_LOGO_DP.dp)
                    .clip(RoundedCornerShape(TUTORIAL_GLYPH_LOGO_CORNER_DP.dp))
                    .then(glyphAnimationModifier(cardIndex = cardIndex, reduceMotion = reduceMotion)),
            )
        }
    }
}

/**
 * Picks the per-card animation modifier for the inner glyph. Pure-helper
 * shape so the per-card animation choice is one switch statement in one
 * place, instead of three branching `if` blocks scattered through the
 * tree.
 */
@Composable
private fun glyphAnimationModifier(cardIndex: Int, reduceMotion: Boolean): Modifier {
    if (reduceMotion || cardIndex == 0) return Modifier
    return when (cardIndex) {
        1 -> longPressPulseModifier()
        2 -> dragDriftModifier()
        3 -> tapFadeModifier()
        else -> Modifier
    }
}

/** Card-2 long-press pulse : scale 1.0 → 1.15 → 1.0 over 1000 ms. */
@Composable
private fun longPressPulseModifier(): Modifier {
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
    return Modifier.graphicsLayer(scaleX = scale, scaleY = scale)
}

/** Card-3 drag drift : translateX ±24 dp over 1000 ms. */
@Composable
private fun dragDriftModifier(): Modifier {
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
    return Modifier.graphicsLayer(translationX = translateX)
}

/** Card-4 tap fade : alpha 1.0 → 0.6 → 1.0 over 600 ms. */
@Composable
private fun tapFadeModifier(): Modifier {
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
    return Modifier.graphicsLayer(alpha = alpha)
}

// ─── Tuning constants ──────────────────────────────────────────────────────

private const val TUTORIAL_SCREEN_MIN_CONTENT_DP: Int = 560
private const val TUTORIAL_GLYPH_FRAME_DP: Int = 72
// Logo size inside the 72 dp sage frame — leaves an ~12 dp ring of sage
// tint visible around the logo (66 / 72 ≈ same ratio as the adaptive-icon
// safe zone, keeping the visual language consistent with the launcher).
private const val TUTORIAL_GLYPH_LOGO_DP: Int = 48

// Corner radius of the round-clip applied to the inner logo. 14 / 48 ≈
// 20 / 72, i.e. proportional to the parent sage frame's RoundedCornerShape(20.dp)
// so the inner outline mirrors the container.
private const val TUTORIAL_GLYPH_LOGO_CORNER_DP: Int = 14
private const val TUTORIAL_TITLE_MAX_WIDTH_DP: Int = 320
private const val TUTORIAL_BODY_MAX_WIDTH_DP: Int = 340
private const val TUTORIAL_SKIP_PLACEHOLDER_DP: Int = 36

// Card-2 long-press pulse : 1.0 → 1.15 scale over 1000 ms, reverse, repeat.
// The cadence matches the Card-2 body copy "Maintiens la bulle 1 seconde".
private const val LONG_PRESS_PULSE_MAX: Float = 1.15f
private const val LONG_PRESS_PULSE_MS: Int = 1000

// Card-3 drag drift : ±24 dp translation X over 1000 ms, reverse, repeat.
// Stored as dp (converted to px via LocalDensity) so the visual drift is
// equivalent across densities — a raw-px literal would be ~10.6 dp on a
// 3x device, invisibly tiny.
private const val DRAG_DRIFT_RANGE_DP: Int = 24
private const val DRAG_DRIFT_MS: Int = 1000

// Card-4 tap fade : 1.0 → 0.6 alpha over 600 ms, reverse, repeat.
private const val TAP_FADE_MIN: Float = 0.6f
private const val TAP_FADE_MS: Int = 600

// ─── @Preview catalogue (4 cards × Light/Dark) ─────────────────────────────

@Preview(showBackground = true, name = "Card 1 Light")
@Composable
private fun OnboardingTutorialOverlayCard1LightPreview() {
    VeriSphereTheme {
        OnboardingTutorialOverlay(
            accessibilityServiceEnabled = false,
            onActivateAccessibilityClick = {},
            onComplete = {},
            onSkip = {},
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
            initialCardIndex = 3,
        )
    }
}
