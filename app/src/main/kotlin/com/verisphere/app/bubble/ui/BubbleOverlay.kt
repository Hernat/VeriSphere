package com.verisphere.app.bubble.ui

import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisphere.app.R
import com.verisphere.app.bubble.BubbleState
import com.verisphere.app.ui.theme.VeriSphereTheme
import kotlin.math.hypot

/**
 * The persistent floating bubble's idle composable (UX-DR5, UX-DR4,
 * UX-DR18; PRD: FR1, FR2, NFR4, NFR12, NFR13).
 *
 * Stateless consumer per architecture lines 762–764: visible alpha is
 * driven by [state] (the only `BubbleState` variant in Story 1.7 is
 * `Idle(faded)`); every gesture surfaces through callback lambdas. The
 * host service owns the `WindowManager.LayoutParams` that drag and
 * edge-snap mutate.
 *
 * Why a 104 dp Box around a 56 dp Surface: `FLAG_NOT_TOUCH_MODAL` makes
 * touches outside our window go to the underlying app, so the bubble
 * window itself must be wide enough to capture the 24 dp adaptive-presence
 * halo (UX-DR5 — "tap-near-miss within 24 dp returns alpha to 1.0").
 * The visible bubble stays 56 dp; the surrounding 24 dp halo on each axis
 * intercepts near-miss taps. The trade-off — the underlying app loses
 * 24 dp of touch zone around the bubble — is the documented UX cost of
 * adaptive presence.
 *
 * Callback semantics:
 *   - [onUserActivity] fires on EVERY touch-down, regardless of how the
 *     gesture ends. This is the single signal the service routes to
 *     `BubbleEvent.UserActivity` for fade-timer reset. Covers the
 *     long-press-release case where no other callback would fire.
 *   - [onTap] fires only on confirmed tap (motion ≤ 4 dp on bubble,
 *     elapsed < 200 ms). Currently no-op; Story 4.3 wires history.
 *   - [onTapNearMiss] fires on tap in the 24 dp halo (motion ≤ 4 dp,
 *     off bubble). Functionally equivalent to [onUserActivity] in 1.7;
 *     kept distinct for future stories that may treat near-miss
 *     specifically.
 *   - [onDragDelta] fires on every drag tick once the gesture has been
 *     promoted to drag mode (motion > 4 dp on bubble).
 *   - [onDragEnd] fires when the dragging pointer goes up.
 *
 * Gesture pass: a single [awaitEachGesture] block disambiguates
 * tap / drag / tap-near-miss using motion ≤ 4 dp + elapsed < 200 ms for
 * tap, motion > 4 dp on the bubble for drag, and motion ≤ 4 dp off the
 * bubble for near-miss. Long-press is silently dropped in this story
 * (Story 1.8 wires it). Multi-touch: the gesture tracks a single pointer
 * by id (the one that landed first); secondary fingers are ignored.
 */
@Composable
fun BubbleOverlay(
    state: BubbleState,
    onUserActivity: () -> Unit,
    onTap: () -> Unit,
    onTapNearMiss: () -> Unit,
    onDragDelta: (dxPx: Float, dyPx: Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val idleContentDescription = stringResource(R.string.bubble_idle_content_description)

    val targetAlpha = if (state is BubbleState.Idle && state.faded) FADED_ALPHA else OPAQUE_ALPHA
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = FADE_DURATION_MS),
        label = "bubbleAlpha",
    )

    Box(
        modifier = modifier
            .size(BUBBLE_HALO_DIAMETER_DP)
            // mergeDescendants = false keeps TalkBack focused only on the
            // visible 56 dp Surface (which carries contentDescription),
            // not on the invisible 104 dp halo Box (UX-DR18 + AC #5).
            .semantics(mergeDescendants = false) { }
            .pointerInput(Unit) {
                awaitEachGesture {
                    handleGesture(
                        density = density,
                        onUserActivity = onUserActivity,
                        onTap = onTap,
                        onTapNearMiss = onTapNearMiss,
                        onDragDelta = onDragDelta,
                        onDragEnd = onDragEnd,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .size(BUBBLE_DIAMETER_DP)
                .clip(CircleShape)
                .alpha(animatedAlpha)
                .semantics {
                    contentDescription = idleContentDescription
                },
            color = MaterialTheme.colorScheme.primary,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "G",
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * Single-pass gesture disambiguation. Returns by completing the
 * [awaitEachGesture] block; the outer [pointerInput] re-arms the next
 * gesture automatically.
 *
 * Algorithm:
 *   1. Capture down position + timestamp + whether down landed inside
 *      the visible 56 dp circle. Fire [onUserActivity] immediately so
 *      the service resets its fade timer regardless of how the gesture
 *      ends (covers long-press-release, drag-cancel, etc.).
 *   2. Track cumulative drag distance using a single pointer ID — only
 *      the pointer that landed first counts. Secondary fingers are
 *      ignored.
 *   3. As soon as cumulative motion exceeds the 4 dp threshold AND the
 *      down was on the bubble, promote to drag mode and emit deltas.
 *   4. On pointer up:
 *      - drag mode → [onDragEnd].
 *      - no drag, on bubble, elapsed < 200 ms → [onTap].
 *      - no drag, off bubble (halo) → [onTapNearMiss].
 *      - else (long-press shape — on bubble, elapsed ≥ 200 ms, no drag)
 *        → silently drop. Story 1.8 wires long-press.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleGesture(
    density: Density,
    onUserActivity: () -> Unit,
    onTap: () -> Unit,
    onTapNearMiss: () -> Unit,
    onDragDelta: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val dragThresholdPx = with(density) { DRAG_THRESHOLD_DP.dp.toPx() }
    val bubbleRadiusPx = with(density) { (BUBBLE_DIAMETER_DP / 2).toPx() }
    val haloCentrePx = with(density) { (BUBBLE_HALO_DIAMETER_DP / 2).toPx() }

    val down = awaitFirstDown(requireUnconsumed = false)
    val pointerId: PointerId = down.id
    val downTime = SystemClock.uptimeMillis()
    val downOnBubble = isInsideBubbleCircle(
        point = down.position,
        haloCentrePx = haloCentrePx,
        bubbleRadiusPx = bubbleRadiusPx,
    )

    // Touch-down is itself user activity — the service resets the fade
    // timer here so that long-press releases (which emit no other
    // callback in 1.7) still keep the bubble opaque while the user is
    // touching it.
    onUserActivity()

    var totalDragPx = 0f
    var inDragMode = false
    var lastPosition = down.position

    while (true) {
        val event = awaitPointerEvent()
        // Track only the pointer that landed first. If it's no longer
        // in the changes list (system cancelled, or a different pointer
        // is producing events while ours is gone), terminate the gesture.
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return

        if (change.changedToUp()) {
            handlePointerUp(
                inDragMode = inDragMode,
                downOnBubble = downOnBubble,
                totalDragPx = totalDragPx,
                dragThresholdPx = dragThresholdPx,
                elapsedMs = SystemClock.uptimeMillis() - downTime,
                onTap = onTap,
                onTapNearMiss = onTapNearMiss,
                onDragEnd = onDragEnd,
            )
            return
        }

        val dx = change.position.x - lastPosition.x
        val dy = change.position.y - lastPosition.y
        lastPosition = change.position
        totalDragPx += hypot(dx, dy)

        if (!inDragMode && totalDragPx > dragThresholdPx && downOnBubble) {
            inDragMode = true
        }

        if (inDragMode) {
            onDragDelta(dx, dy)
            change.consume()
        }
    }
}

private fun handlePointerUp(
    inDragMode: Boolean,
    downOnBubble: Boolean,
    totalDragPx: Float,
    dragThresholdPx: Float,
    elapsedMs: Long,
    onTap: () -> Unit,
    onTapNearMiss: () -> Unit,
    onDragEnd: () -> Unit,
) {
    when {
        inDragMode -> onDragEnd()
        totalDragPx > dragThresholdPx -> Unit
        downOnBubble && elapsedMs < TAP_TIMEOUT_MS -> onTap()
        !downOnBubble -> onTapNearMiss()
        else -> Unit
    }
}

private fun isInsideBubbleCircle(
    point: Offset,
    haloCentrePx: Float,
    bubbleRadiusPx: Float,
): Boolean {
    val dx = point.x - haloCentrePx
    val dy = point.y - haloCentrePx
    return hypot(dx, dy) <= bubbleRadiusPx
}

// UX-DR4 spacing token (56 dp) — kept inline because the composable has
// no Density at construction time; the SERVICE has the matching constant
// in pixels for WindowManager arithmetic.
private val BUBBLE_DIAMETER_DP = 56.dp

// 56 dp visible bubble + 24 dp halo on each side = 104 dp window.
// See KDoc on [BubbleOverlay] for the rationale.
private val BUBBLE_HALO_DIAMETER_DP = 104.dp

private const val FADE_DURATION_MS = 300

// Tap-vs-drag and tap-vs-long-press thresholds per UX spec line 372.
private const val DRAG_THRESHOLD_DP = 4
private const val TAP_TIMEOUT_MS = 200L

private const val OPAQUE_ALPHA = 1.0f
private const val FADED_ALPHA = 0.4f

@Preview(showBackground = true, name = "Idle - opaque - Light")
@Composable
private fun BubbleOverlayIdleOpaqueLightPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Idle(faded = false),
            onUserActivity = {},
            onTap = {},
            onTapNearMiss = {},
            onDragDelta = { _, _ -> },
            onDragEnd = {},
        )
    }
}

@Preview(
    showBackground = true,
    name = "Idle - opaque - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun BubbleOverlayIdleOpaqueDarkPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Idle(faded = false),
            onUserActivity = {},
            onTap = {},
            onTapNearMiss = {},
            onDragDelta = { _, _ -> },
            onDragEnd = {},
        )
    }
}

@Preview(showBackground = true, name = "Idle - faded - Light")
@Composable
private fun BubbleOverlayIdleFadedLightPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Idle(faded = true),
            onUserActivity = {},
            onTap = {},
            onTapNearMiss = {},
            onDragDelta = { _, _ -> },
            onDragEnd = {},
        )
    }
}

@Preview(
    showBackground = true,
    name = "Idle - faded - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun BubbleOverlayIdleFadedDarkPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Idle(faded = true),
            onUserActivity = {},
            onTap = {},
            onTapNearMiss = {},
            onDragDelta = { _, _ -> },
            onDragEnd = {},
        )
    }
}
