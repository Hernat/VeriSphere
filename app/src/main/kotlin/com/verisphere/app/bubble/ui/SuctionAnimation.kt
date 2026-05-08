package com.verisphere.app.bubble.ui

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.verisphere.app.bubble.BubbleStateMachine
import com.verisphere.app.ui.theme.VeriSphereTheme

/**
 * Capture-confirmation animation (UX-DR7, PRD FR4).
 *
 * Renders a shrinking ring that appears to be "pulled into" the bubble
 * over [SUCTION_ANIMATION_MS_DEFAULT] (~300 ms). Implemented via Compose
 * [Canvas] + [Animatable] — zero APK cost (no Lottie). Lottie (~80 KB)
 * is the documented fallback per architecture line 309 + UX-DR7 if the
 * Compose-only result is insufficient — defer to V2.
 *
 * The animation runs once on first composition (i.e. on entering
 * [com.verisphere.app.bubble.BubbleState.Capturing]); the
 * [com.verisphere.app.bubble.BubbleStateMachine] then auto-transitions
 * to [com.verisphere.app.bubble.BubbleState.Thinking] after
 * [SUCTION_ANIMATION_MS_DEFAULT], at which point this composable is
 * removed from the tree by [BubbleOverlay].
 *
 * **Decorative** — `contentDescription = ""` so TalkBack skips it
 * (UX spec line 696). Not a live region; the user already knows they
 * triggered the capture.
 *
 * **Reduce-motion** — Story 3.4 / UX-DR16 will branch the duration to 0
 * when `Settings.System.ANIMATOR_DURATION_SCALE` is 0. For Story 1.10
 * the animation is unconditional.
 */
@Composable
fun SuctionAnimation(
    modifier: Modifier = Modifier,
    durationMs: Int = SUCTION_ANIMATION_MS_DEFAULT,
    color: Color = Color.White,
) {
    // Driven by Animatable so the timeline uses the host MonotonicFrameClock
    // (the bubble overlay's ComposeView gets one because the service
    // dispatches ON_RESUME — see BubbleOverlayService.onStartCommand).
    var progress: Float by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(Unit) {
        Animatable(initialValue = 1f).animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = durationMs, easing = LinearEasing),
        ) {
            progress = value
        }
    }

    Canvas(
        modifier = modifier
            .size(SUCTION_DIAMETER_DP.dp)
            .alpha(progress.coerceIn(0f, MAX_ALPHA))   // alpha follows progress with a 0.7 cap
            .semantics { contentDescription = "" },    // decorative
    ) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.minDimension / 2f
        val currentRadius = maxRadius * progress
        drawCircle(
            color = color,
            radius = currentRadius,
            center = centre,
            style = Stroke(width = STROKE_WIDTH_DP.dp.toPx()),
        )
    }
}

/**
 * UX-DR7 — ~300 ms. Single source of truth lives in
 * [BubbleStateMachine.SUCTION_ANIMATION_MS]; this default mirrors it
 * (code-review patch P13 — without the link, the state machine could
 * transition `Capturing → Thinking` before/after the animation
 * completes, producing visible desync).
 */
val SUCTION_ANIMATION_MS_DEFAULT: Int = BubbleStateMachine.SUCTION_ANIMATION_MS.toInt()

// 56 dp bubble + 16 dp ring on each side = 88 dp animation surface.
private const val SUCTION_DIAMETER_DP = 88f
private const val STROKE_WIDTH_DP = 2f
private const val MAX_ALPHA = 0.7f

@Preview(showBackground = true, name = "Suction - Light")
@Composable
private fun SuctionAnimationLightPreview() {
    VeriSphereTheme {
        // Preview renders the initial frame (full radius) — manual
        // verification of the in-flight animation is via the device.
        SuctionAnimation()
    }
}

@Preview(
    showBackground = true,
    name = "Suction - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SuctionAnimationDarkPreview() {
    VeriSphereTheme {
        SuctionAnimation()
    }
}
