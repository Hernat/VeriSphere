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
import kotlinx.coroutines.withTimeoutOrNull

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
 *   - [onLongPress] fires AT the 1 s mark while the finger is still down
 *     on the visible 56 dp bubble (PRD FR3, UX-DR5, UX-DR14). Triggers
 *     the silent `AccessibilityService.takeScreenshot()` capture path
 *     (Story 1.8.5, architecture D5.13; previously `MEDIA_PROJECTION`
 *     in Story 1.8 — superseded). Fires at most once per gesture;
 *     subsequent finger movement / release does NOT re-fire.
 *   - [onDragDelta] fires on every drag tick once the gesture has been
 *     promoted to drag mode (motion > 4 dp on bubble).
 *   - [onDragEnd] fires when the dragging pointer goes up.
 *
 * Gesture pass: a single [awaitEachGesture] block disambiguates
 * tap / drag / tap-near-miss / long-press. The detection of the 1 s
 * long-press uses [withTimeoutOrNull] around [awaitPointerEvent] so the
 * deadline fires while the user is holding the bubble (no further pointer
 * events arrive during a still hold; a passive `if elapsed >= LONG_PRESS_MS`
 * inside the loop would never run). Multi-touch: the gesture tracks a
 * single pointer by id (the one that landed first); secondary fingers
 * are ignored.
 */
@Composable
fun BubbleOverlay(
    state: BubbleState,
    onUserActivity: () -> Unit,
    onTap: () -> Unit,
    onTapNearMiss: () -> Unit,
    onLongPress: () -> Unit,
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
                        onLongPress = onLongPress,
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
 *   3. While the deadline is in the future and the long-press has not
 *      yet fired, await pointer events under [withTimeoutOrNull] so the
 *      1 s deadline fires WHILE THE FINGER IS HELD (no further events
 *      arrive during a still hold; passive `if elapsed >= 1 s` inside
 *      the loop would never run).
 *   4. As soon as cumulative motion exceeds the 4 dp threshold AND the
 *      down was on the bubble, promote to drag mode and emit deltas.
 *      Drag mode also disables further long-press detection (a hand
 *      that's moving is a drag, not a press).
 *   5. On pointer up:
 *      - drag mode → [onDragEnd].
 *      - long-press already fired → silent (no [onTap] double-fire).
 *      - no drag, on bubble, elapsed < 200 ms → [onTap].
 *      - no drag, off bubble (halo) → [onTapNearMiss].
 *      - else (long-press shape that didn't reach 1 s — e.g. release at
 *        500 ms with no motion) → silently drop.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // single linear gesture loop — extracting helpers fragments the state machine across functions
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleGesture(
    density: Density,
    onUserActivity: () -> Unit,
    onTap: () -> Unit,
    onTapNearMiss: () -> Unit,
    onLongPress: () -> Unit,
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
    val longPressDeadlineMs = downTime + LONG_PRESS_MS

    // Touch-down is itself user activity — the service resets the fade
    // timer here so that long-press releases (which emit no other
    // callback in 1.7) still keep the bubble opaque while the user is
    // touching it.
    onUserActivity()

    var totalDragPx = 0f
    var inDragMode = false
    var lastPosition = down.position
    var longPressFired = false

    while (true) {
        // Passive elapsed check (Story 1.8.5 code-review patch P10
        // robustness): fire the long-press if 1 s has elapsed since
        // touch-down, REGARDLESS of whether the timeout below was
        // about to fire. This covers two cases the timeout-only path
        // misses: (a) synthetic gestures from instrumented tests that
        // inject MOVE events every ~5 ms (defeating the `withTimeoutOrNull`),
        // (b) real users whose finger pressure jitters generate
        // redundant pointer events at the same position. Real users
        // who hold perfectly still still trigger the timeout below;
        // this passive check is additive, not a replacement.
        if (
            !longPressFired && !inDragMode &&
            downOnBubble && totalDragPx <= dragThresholdPx &&
            SystemClock.uptimeMillis() - downTime >= LONG_PRESS_MS
        ) {
            longPressFired = true
            onLongPress()
        }

        // Compute the per-iteration timeout: the time remaining until
        // the 1 s long-press deadline. Once the long-press has fired
        // (or the gesture became a drag), we fall back to an indefinite
        // awaitPointerEvent — no more deadline to chase.
        val remainingMs = longPressDeadlineMs - SystemClock.uptimeMillis()
        val event = if (!longPressFired && !inDragMode && remainingMs > 0L) {
            withTimeoutOrNull(remainingMs) { awaitPointerEvent() }
        } else {
            awaitPointerEvent()
        }

        if (event == null) {
            // The long-press deadline elapsed while the finger is still
            // down with no events arriving. Promote to long-press if
            // the gesture shape qualifies (on the visible bubble, no
            // significant motion). Halo holds and on-bubble holds that
            // have already drifted past 4 dp do NOT promote.
            if (downOnBubble && totalDragPx <= dragThresholdPx) {
                longPressFired = true
                onLongPress()
            }
            continue
        }

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
                longPressFired = longPressFired,
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
    longPressFired: Boolean,
    elapsedMs: Long,
    onTap: () -> Unit,
    onTapNearMiss: () -> Unit,
    onDragEnd: () -> Unit,
) {
    when {
        inDragMode -> onDragEnd()
        // Long-press already fired during the hold — releasing the finger
        // afterwards is just a silent end-of-gesture. Do NOT also fire
        // onTap (which would request a second capture for the same press).
        longPressFired -> Unit
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

// Long-press duration (PRD FR3 — "≈ 1 second", UX-DR5). Detection happens
// AT this mark while the finger is still down; releasing earlier silently
// drops the gesture (no event emitted). UX-DR14 routes `Idle × Long-press`
// to the silent `AccessibilityService.takeScreenshot()` capture path
// (Story 1.8.5, architecture D5.13 — superseded MEDIA_PROJECTION from Story 1.8).
private const val LONG_PRESS_MS = 1_000L

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
            onLongPress = {},
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
            onLongPress = {},
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
            onLongPress = {},
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
            onLongPress = {},
            onDragDelta = { _, _ -> },
            onDragEnd = {},
        )
    }
}
