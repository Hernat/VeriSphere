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
 * **Reduce-motion** — Story 3.4 / UX-DR16 will replace this with a
 * static alpha-cycling dot when `ANIMATOR_DURATION_SCALE = 0`. Story
 * 1.10 ships the unconditional rotation.
 */
@Composable
fun ThinkingRing(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = STROKE_WIDTH_DP.dp,
    sweepAngleDeg: Float = SWEEP_ANGLE_DEFAULT_DEG,
    color: Color = MaterialTheme.colorScheme.primary,
) {
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

private const val RING_DIAMETER_DP = 72f             // 56 dp bubble + 8 dp ring per side
private const val STROKE_WIDTH_DP = 2f
private const val SWEEP_ANGLE_DEFAULT_DEG = 90f
private const val FULL_ROTATION_DEG = 360f
private const val ROTATION_PERIOD_MS = 1_000          // 1 full rotation per second — matches UX motion language

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
