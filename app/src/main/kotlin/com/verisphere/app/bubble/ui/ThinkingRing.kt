package com.verisphere.app.bubble.ui

import android.content.res.Configuration
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verisphere.app.ui.theme.VeriSphereTheme


/**
 * Thinking-state ring loader (UX-DR5 thinking-ring; UX spec line 667).
 *
 * Continuously rotates a [SWEEP_ANGLE_DEFAULT_DEG]° arc around the
 * 56 dp bubble at 60 fps while the
 * [com.verisphere.app.bubble.BubbleState.Thinking] state is composed.
 * Driven by [rememberInfiniteTransition] — Compose's idiomatic way to
 * tie an animation to recomposition without a manual coroutine.
 *
 * **Decorative** — `contentDescription = ""` (UX-DR18). The bubble's
 * own `contentDescription` ("VeriSphere bubble, verifying") carries the
 * Thinking semantic; this Canvas is purely visual.
 *
 * **Story 3.4 — Reduce-motion** — when [reduceMotion] is `true`, the
 * rotating arc is replaced by a single 12 dp filled circle whose alpha
 * cycles between [STATIC_DOT_ALPHA_MIN] and [STATIC_DOT_ALPHA_MAX] over
 * [STATIC_DOT_PERIOD_MS] (`RepeatMode.Reverse` so a full 0.6 → 1.0 → 0.6
 * cycle takes 1.6 s — smoother than `Restart` which would snap at the
 * end of every leg). `rememberInfiniteTransition` is still used so the
 * "still thinking" affordance lives as long as the Composable is
 * composed; the difference vs the rotating arc is purely visual.
 */
@Composable
fun ThinkingRing(
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    strokeWidth: Dp = STROKE_WIDTH_DP.dp,
    sweepAngleDeg: Float = SWEEP_ANGLE_DEFAULT_DEG,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (reduceMotion) {
        ThinkingRingStaticDot(modifier = modifier, color = color)
        return
    }

    // rememberInfiniteTransition is the canonical primitive for an
    // animation that runs as long as the composable is composed; it
    // automatically cancels when the composable leaves the tree (i.e.
    // when state transitions out of Thinking).
    val transition = rememberInfiniteTransition(label = "thinkingRing")
    val rotationDeg by transition.animateFloat(
        initialValue = 0f,
        targetValue = FULL_ROTATION_DEG,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ROTATION_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    Canvas(
        modifier = modifier
            .size(RING_DIAMETER_DP.dp)
            .semantics { contentDescription = "" },
    ) {
        val strokePx = strokeWidth.toPx()
        val diameter = size.minDimension - strokePx
        val topLeft = Offset(strokePx / 2f, strokePx / 2f)
        val arcSize = Size(diameter, diameter)
        drawArc(
            color = color,
            startAngle = rotationDeg,
            sweepAngle = sweepAngleDeg,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}

/**
 * Story 3.4 — UX-DR16 static-dot variant of the thinking-ring loader.
 * Renders a single filled circle whose alpha cycles between 0.6 and 1.0
 * over an 800 ms half-cycle (`RepeatMode.Reverse` → full 1.6 s
 * round-trip). 12 dp diameter is small enough to read as "still
 * thinking" without competing with the bubble's letter G; large enough
 * to be visible at 1.0 alpha on a 56 dp bubble.
 *
 * **Positioning (smoke-discovered regression SD1, 2026-05-13)** — the
 * dot is anchored at the **top-centre** of the 72 dp canvas (its centre
 * sits at canvas-local `y = STATIC_DOT_RADIUS_DP` so the top of the dot
 * touches the canvas's top edge). The original implementation centred
 * the dot at the canvas centre, which placed it pile-on the 56 dp
 * bubble's "G" letter — a 12 dp dot drawn opaque-at-peak-alpha over
 * the centred G made the bubble read as a malformed "C" or unknown
 * glyph. Top-centre positioning floats the dot in the 8 dp halo above
 * the 56 dp bubble (the bottom 4 dp of the dot overlaps the top of the
 * bubble Surface, well above the G's vertical extent — the G is rendered
 * at the bubble's vertical centre with a 24 sp font), so the G stays
 * legible while the dot's alpha pulse signals "still thinking".
 */
@Composable
private fun ThinkingRingStaticDot(modifier: Modifier, color: Color) {
    val transition = rememberInfiniteTransition(label = "thinkingDot")
    val alpha by transition.animateFloat(
        initialValue = STATIC_DOT_ALPHA_MIN,
        targetValue = STATIC_DOT_ALPHA_MAX,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = STATIC_DOT_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Canvas(
        modifier = modifier
            .size(RING_DIAMETER_DP.dp)
            .semantics { contentDescription = "" },
    ) {
        // Top-centre anchor — see Composable KDoc for the SD1 rationale.
        // Dot centre x = half the canvas width; dot centre y = its own
        // radius so the dot's top edge touches the canvas y=0 edge.
        val dotRadiusPx = STATIC_DOT_RADIUS_DP.dp.toPx()
        val centre = Offset(size.width / 2f, dotRadiusPx)
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = dotRadiusPx,
            center = centre,
        )
    }
}

private const val RING_DIAMETER_DP = 72f             // 56 dp bubble + 8 dp ring per side
private const val STROKE_WIDTH_DP = 2f
private const val SWEEP_ANGLE_DEFAULT_DEG = 90f
private const val FULL_ROTATION_DEG = 360f
private const val ROTATION_PERIOD_MS = 1_000          // 1 full rotation per second — matches UX motion language

// Story 3.4 — UX-DR16 static-dot variant. 12 dp diameter dot (visible
// over the bubble's letter G but not competing with it), alpha pulsing
// 0.6 ↔ 1.0 over 800 ms with linear easing and Reverse repeat mode
// (full visual cycle = 1.6 s round-trip). Verbatim per UX-DR16:
// "static dot with a slow alpha cycle (animateFloatAsState between 0.6
// and 1.0 over 800 ms)".
private const val STATIC_DOT_RADIUS_DP = 6f          // 12 dp diameter
private const val STATIC_DOT_ALPHA_MIN = 0.6f
private const val STATIC_DOT_ALPHA_MAX = 1.0f
private const val STATIC_DOT_PERIOD_MS = 800

@Preview(showBackground = true, name = "Thinking Ring - Light")
@Composable
private fun ThinkingRingLightPreview() {
    VeriSphereTheme {
        // Preview renders a static frame at the initial rotation (0°).
        // Manual verification of the rotation is via the device.
        ThinkingRing()
    }
}

@Preview(
    showBackground = true,
    name = "Thinking Ring - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ThinkingRingDarkPreview() {
    VeriSphereTheme {
        ThinkingRing()
    }
}

// Story 3.4 — UX-DR16 reduce-motion previews. Render a single 12 dp
// dot at the bubble centre instead of a rotating arc. Preview captures
// a static frame at the dot's initial alpha; the alpha cycle is
// observable on-device.

@Preview(
    showBackground = true,
    showSystemUi = true,
    name = "Thinking Ring - Reduce-motion - Light",
)
@Composable
private fun ThinkingRingReduceMotionLightPreview() {
    VeriSphereTheme {
        ThinkingRing(reduceMotion = true)
    }
}

@Preview(
    showBackground = true,
    showSystemUi = true,
    name = "Thinking Ring - Reduce-motion - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ThinkingRingReduceMotionDarkPreview() {
    VeriSphereTheme {
        ThinkingRing(reduceMotion = true)
    }
}
