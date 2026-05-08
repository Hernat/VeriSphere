package com.verisphere.app.bubble.ui

import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisphere.app.R
import com.verisphere.app.bubble.BubbleState
import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.storage.SessionRecord
import com.verisphere.app.ui.theme.VeriSphereTheme
import kotlin.math.hypot
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The persistent floating bubble — central component of the Story 1.10
 * happy-path UI (UX-DR5; PRD: FR1, FR2, FR4, FR11, NFR4, NFR12, NFR13).
 *
 * Stateless consumer per architecture lines 762–764: visible
 * appearance and overlay decoration are driven by [state]; every gesture
 * surfaces through callback lambdas. The host service owns the
 * `WindowManager.LayoutParams` and any sibling windows
 * (e.g. the [FlashTooltip] window in [BubbleState.Verdict]).
 *
 * **Window scope.** This composable renders inside the 104 dp halo
 * window owned by [com.verisphere.app.bubble.BubbleOverlayService].
 * The visible bubble stays 56 dp; the surrounding 24 dp halo on each
 * axis (a) intercepts near-miss taps for adaptive presence and (b)
 * provides a 12 dp ring around the visible bubble for the
 * [SuctionAnimation] (88 dp) and [ThinkingRing] (72 dp) decorations,
 * both of which fit comfortably within the halo. The [FlashTooltip] is
 * rendered in a separate WindowManager window — see Critical Dev Note #3
 * in the story file. Story 1.10 keeps the halo window unchanged.
 *
 * **State rendering**:
 *  - [BubbleState.Idle] — alpha animates between 1.0 (opaque) and 0.4
 *    (faded) per `state.faded`. Background = `colorScheme.primary`
 *    (Google blue brand accent).
 *  - [BubbleState.Pressing] — bubble pulses ~400 ms scale 1.0 → 1.1 → 1.0.
 *  - [BubbleState.Capturing] — [SuctionAnimation] overlaid on the bubble.
 *  - [BubbleState.Thinking] — [ThinkingRing] rotates around the bubble.
 *  - [BubbleState.Verdict] — bubble adopts the verdict's semantic colour
 *    (UX Step 8 palette via [verdictBackgroundFor]). The verdict's
 *    [SessionRecord.headline] is NOT rendered here — the [FlashTooltip]
 *    window owns that.
 *
 * **Callback semantics**:
 *   - [onUserActivity] fires on EVERY touch-down, regardless of how the
 *     gesture ends.
 *   - [onLongPressStart] (Story 1.10) fires on touch-down on the visible
 *     56 dp bubble (i.e. when `downOnBubble == true`). Maps to
 *     [com.verisphere.app.bubble.BubbleEvent.LongPressStarted] in the
 *     service.
 *   - [onPressCancelled] (Story 1.10) fires when the press is released
 *     before the long-press deadline AND the gesture was on the bubble
 *     AND no drag occurred. The service routes this to
 *     [com.verisphere.app.bubble.BubbleEvent.BackToIdle] when the state
 *     machine is currently in [BubbleState.Pressing].
 *   - [onTap] fires only on confirmed tap (motion ≤ 4 dp on bubble,
 *     elapsed < 200 ms). Story 4.3 wires history navigation; Story 2.4
 *     wires detail panel from `Verdict` state.
 *   - [onTapNearMiss] fires on tap in the 24 dp halo (motion ≤ 4 dp,
 *     off bubble).
 *   - [onLongPress] fires AT the 1 s mark while the finger is still
 *     down on the visible 56 dp bubble (PRD FR3, UX-DR5, UX-DR14).
 *   - [onDragDelta] / [onDragEnd] cover the drag gesture.
 */
@Suppress("LongParameterList") // architecture line 762: stateless consumer; every gesture surfaces a distinct callback for clarity
@Composable
fun BubbleOverlay(
    state: BubbleState,
    onUserActivity: () -> Unit,
    onLongPressStart: () -> Unit,
    onPressCancelled: () -> Unit,
    onTap: () -> Unit,
    onTapNearMiss: () -> Unit,
    onLongPress: () -> Unit,
    onDragDelta: (dxPx: Float, dyPx: Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val contentDescriptionText = bubbleContentDescriptionFor(state)

    val targetAlpha = if (state is BubbleState.Idle && state.faded) FADED_ALPHA else OPAQUE_ALPHA
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = FADE_DURATION_MS),
        label = "bubbleAlpha",
    )

    // Pressing-state pulse: 400 ms scale 1.0 → 1.1 → 1.0. Driven by an
    // Animatable inside a LaunchedEffect keyed on the state's CLASS
    // (code-review patch P6) so the pulse re-fires only on a state-class
    // transition (e.g. Idle → Pressing) and stays stable across data-class
    // copy emissions of the same kind (e.g. Verdict.copy(tooltipFaded=true)
    // would otherwise re-fire the else-branch's redundant snapTo(1f)).
    val pulseScale = remember { Animatable(initialValue = 1f) }
    LaunchedEffect(state::class) {
        if (state is BubbleState.Pressing) {
            pulseScale.snapTo(1f)
            pulseScale.animateTo(
                targetValue = PRESSING_PULSE_PEAK,
                animationSpec = tween(
                    durationMillis = PRESSING_PULSE_HALF_MS,
                    easing = LinearEasing,
                ),
            )
            pulseScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            )
        } else {
            pulseScale.snapTo(1f)
        }
    }

    val bubbleBackground = bubbleBackgroundColorFor(state)

    Box(
        modifier = modifier
            .size(BUBBLE_HALO_DIAMETER_DP)
            .semantics(mergeDescendants = false) { }
            .pointerInput(Unit) {
                awaitEachGesture {
                    handleGesture(
                        density = density,
                        onUserActivity = onUserActivity,
                        onLongPressStart = onLongPressStart,
                        onPressCancelled = onPressCancelled,
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
        // The 56 dp visible bubble. Alpha + pulse scale apply only to
        // this layer — the halo Box is invisible.
        Surface(
            modifier = Modifier
                .size(BUBBLE_DIAMETER_DP)
                .clip(CircleShape)
                .alpha(animatedAlpha)
                .scale(pulseScale.value)
                .semantics {
                    contentDescription = contentDescriptionText
                },
            color = bubbleBackground,
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

        // State-specific overlays drawn on top of the bubble. Each lives
        // for as long as the corresponding state composes — entering /
        // leaving the state mounts / unmounts the overlay automatically.
        when (state) {
            BubbleState.Capturing -> SuctionAnimation()
            BubbleState.Thinking -> ThinkingRing()
            else -> Unit
        }
    }
}

@Composable
private fun bubbleContentDescriptionFor(state: BubbleState): String = when (state) {
    is BubbleState.Idle -> stringResource(R.string.bubble_idle_content_description)
    BubbleState.Pressing -> stringResource(R.string.bubble_pressing_content_description)
    BubbleState.Capturing -> stringResource(R.string.bubble_capturing_content_description)
    BubbleState.Thinking -> stringResource(R.string.bubble_thinking_content_description)
    is BubbleState.Verdict -> {
        // Code-review patch P3 — emit the verdict label only on the
        // bubble's contentDescription. The verdict headline is
        // announced by [com.verisphere.app.bubble.ui.FlashTooltip]'s
        // `LiveRegionMode.Polite` semantic. Concatenating the headline
        // here would (a) double-announce on every TalkBack focus and
        // (b) couple session-content (`record.headline`) to the
        // bubble's persistent accessibility node beyond the tooltip's
        // visible lifetime.
        val labelRes = when (state.record.verdictLabel) {
            VerdictLabel.TRUE -> R.string.bubble_verdict_true_content_description
            VerdictLabel.FALSE -> R.string.bubble_verdict_false_content_description
            VerdictLabel.DOUBTFUL -> R.string.bubble_verdict_doubtful_content_description
            VerdictLabel.NON_VERIFIABLE -> R.string.bubble_verdict_non_verifiable_content_description
        }
        stringResource(labelRes)
    }
}

@Composable
private fun bubbleBackgroundColorFor(state: BubbleState): Color = when (state) {
    is BubbleState.Verdict -> colorResource(verdictBackgroundFor(state.record.verdictLabel))
    else -> MaterialTheme.colorScheme.primary
}

/**
 * Single-pass gesture disambiguation. Returns by completing the
 * [awaitEachGesture] block; the outer [pointerInput] re-arms the next
 * gesture automatically.
 *
 * Story 1.10 additions:
 *  - [onLongPressStart] fires immediately after [onUserActivity] when
 *    the touch-down landed on the visible 56 dp bubble.
 *  - [onPressCancelled] fires from the new branch in [handlePointerUp]
 *    when the press is released before the long-press fires AND the
 *    gesture was on the bubble AND no drag occurred.
 *
 * Algorithm (unchanged from Story 1.7 + 1.8.5 P10 patch):
 *   1. Capture down position + timestamp + whether down landed inside
 *      the visible 56 dp circle. Fire [onUserActivity] immediately so
 *      the service resets its fade timer regardless of how the gesture
 *      ends. If the down was on the bubble, also fire
 *      [onLongPressStart] so the state machine transitions to Pressing.
 *   2. Track cumulative drag distance using a single pointer ID — only
 *      the pointer that landed first counts.
 *   3. While the deadline is in the future and the long-press has not
 *      yet fired, await pointer events under [withTimeoutOrNull] so the
 *      1 s deadline fires WHILE THE FINGER IS HELD.
 *   4. As soon as cumulative motion exceeds the 4 dp threshold AND the
 *      down was on the bubble, promote to drag mode and emit deltas.
 *   5. On pointer up:
 *      - drag mode → [onDragEnd].
 *      - long-press already fired → silent.
 *      - no drag, on bubble, elapsed < 200 ms → [onTap].
 *      - no drag, off bubble (halo) → [onTapNearMiss].
 *      - no drag, on bubble, elapsed ≥ 200 ms but no long-press →
 *        [onPressCancelled] (Story 1.10 — was silent in 1.7).
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList") // single linear gesture loop
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleGesture(
    density: Density,
    onUserActivity: () -> Unit,
    onLongPressStart: () -> Unit,
    onPressCancelled: () -> Unit,
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

    // Touch-down is itself user activity — always fire so the service
    // can reset the fade timer. If the down landed on the visible
    // bubble, ALSO fire onLongPressStart (state machine transitions
    // Idle/Verdict → Pressing). Halo-only touches DO NOT fire
    // onLongPressStart (they're tap-near-miss or drag candidates).
    onUserActivity()
    if (downOnBubble) {
        onLongPressStart()
    }

    var totalDragPx = 0f
    var inDragMode = false
    var lastPosition = down.position
    var longPressFired = false

    while (true) {
        // Passive elapsed check (Story 1.8.5 patch P10): fire long-press
        // if 1 s has elapsed since touch-down regardless of the timeout
        // path. Covers synthetic gestures + finger-pressure jitter.
        if (
            !longPressFired && !inDragMode &&
            downOnBubble && totalDragPx <= dragThresholdPx &&
            SystemClock.uptimeMillis() - downTime >= LONG_PRESS_MS
        ) {
            longPressFired = true
            onLongPress()
        }

        val remainingMs = longPressDeadlineMs - SystemClock.uptimeMillis()
        val event = if (!longPressFired && !inDragMode && remainingMs > 0L) {
            withTimeoutOrNull(remainingMs) { awaitPointerEvent() }
        } else {
            awaitPointerEvent()
        }

        if (event == null) {
            if (downOnBubble && totalDragPx <= dragThresholdPx) {
                longPressFired = true
                onLongPress()
            }
            continue
        }

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
                onPressCancelled = onPressCancelled,
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

@Suppress("LongParameterList") // mirrors handleGesture's callback surface
private fun handlePointerUp(
    inDragMode: Boolean,
    downOnBubble: Boolean,
    totalDragPx: Float,
    dragThresholdPx: Float,
    longPressFired: Boolean,
    elapsedMs: Long,
    onTap: () -> Unit,
    onTapNearMiss: () -> Unit,
    onPressCancelled: () -> Unit,
    onDragEnd: () -> Unit,
) {
    when {
        inDragMode -> onDragEnd()
        longPressFired -> Unit                                       // long-press fired during hold; silent end-of-gesture
        totalDragPx > dragThresholdPx -> Unit                        // drag never promoted past threshold but moved off-bubble
        downOnBubble && elapsedMs < TAP_TIMEOUT_MS -> onTap()
        !downOnBubble -> onTapNearMiss()
        // Story 1.10: press released BEFORE the long-press fired AND on
        // the bubble AND elapsed >= 200 ms → user started a long-press
        // and let go early. Surface as onPressCancelled so the service
        // can route back to Idle from Pressing (the previous Story 1.7
        // path was a silent drop).
        downOnBubble && elapsedMs >= TAP_TIMEOUT_MS -> onPressCancelled()
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

// UX-DR4 spacing token (56 dp).
private val BUBBLE_DIAMETER_DP = 56.dp

// 56 dp visible bubble + 24 dp halo on each side = 104 dp window.
private val BUBBLE_HALO_DIAMETER_DP = 104.dp

private const val FADE_DURATION_MS = 300

// Tap-vs-drag and tap-vs-long-press thresholds per UX spec line 372.
private const val DRAG_THRESHOLD_DP = 4
private const val TAP_TIMEOUT_MS = 200L

// Long-press duration (PRD FR3 — "≈ 1 second", UX-DR5).
private const val LONG_PRESS_MS = 1_000L

private const val OPAQUE_ALPHA = 1.0f
private const val FADED_ALPHA = 0.4f

// Story 1.10 — Pressing pulse: 400 ms scale 1.0 → 1.1 → 1.0.
private const val PRESSING_PULSE_PEAK = 1.1f
private const val PRESSING_PULSE_HALF_MS = 200

// ----- Previews -------------------------------------------------------

@Preview(showBackground = true, name = "Idle - opaque - Light")
@Composable
private fun BubbleOverlayIdleOpaqueLightPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Idle(faded = false),
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
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
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
        )
    }
}

@Preview(showBackground = true, name = "Idle - faded - Light")
@Composable
private fun BubbleOverlayIdleFadedLightPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Idle(faded = true),
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
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
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
        )
    }
}

@Preview(showBackground = true, name = "Pressing - Light")
@Composable
private fun BubbleOverlayPressingLightPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Pressing,
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
        )
    }
}

@Preview(
    showBackground = true,
    name = "Pressing - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun BubbleOverlayPressingDarkPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Pressing,
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
        )
    }
}

@Preview(showBackground = true, name = "Capturing - Light")
@Composable
private fun BubbleOverlayCapturingLightPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Capturing,
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
        )
    }
}

@Preview(
    showBackground = true,
    name = "Capturing - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun BubbleOverlayCapturingDarkPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Capturing,
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
        )
    }
}

@Preview(showBackground = true, name = "Thinking - Light")
@Composable
private fun BubbleOverlayThinkingLightPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Thinking,
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
        )
    }
}

@Preview(
    showBackground = true,
    name = "Thinking - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun BubbleOverlayThinkingDarkPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Thinking,
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
        )
    }
}

private fun previewVerdictRecord(label: VerdictLabel): SessionRecord = SessionRecord(
    id = "preview-id",
    timestampMs = 0L,
    verdictLabel = label,
    headline = "Preview verdict for $label",
    contextLines = emptyList(),
    sourceLinks = emptyList<SourceCitation>(),
    ocrText = "",
    regionalBiasNote = null,
)

@Preview(showBackground = true, name = "Verdict TRUE - Light")
@Composable
private fun BubbleOverlayVerdictTrueLightPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Verdict(previewVerdictRecord(VerdictLabel.TRUE)),
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
        )
    }
}

@Preview(
    showBackground = true,
    name = "Verdict FALSE - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun BubbleOverlayVerdictFalseDarkPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Verdict(previewVerdictRecord(VerdictLabel.FALSE)),
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
        )
    }
}

@Preview(showBackground = true, name = "Verdict DOUBTFUL - Light")
@Composable
private fun BubbleOverlayVerdictDoubtfulLightPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Verdict(previewVerdictRecord(VerdictLabel.DOUBTFUL)),
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
        )
    }
}

@Preview(showBackground = true, name = "Verdict NON-VERIFIABLE - Light")
@Composable
private fun BubbleOverlayVerdictNonVerifiableLightPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Verdict(previewVerdictRecord(VerdictLabel.NON_VERIFIABLE)),
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
        )
    }
}

// Code-review patch P7 — 4 previously-missing dark/light Verdict previews
// added so the catalogue covers every (verdictLabel × theme) pair (16 total).

@Preview(
    showBackground = true,
    name = "Verdict TRUE - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun BubbleOverlayVerdictTrueDarkPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Verdict(previewVerdictRecord(VerdictLabel.TRUE)),
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
        )
    }
}

@Preview(showBackground = true, name = "Verdict FALSE - Light")
@Composable
private fun BubbleOverlayVerdictFalseLightPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Verdict(previewVerdictRecord(VerdictLabel.FALSE)),
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
        )
    }
}

@Preview(
    showBackground = true,
    name = "Verdict DOUBTFUL - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun BubbleOverlayVerdictDoubtfulDarkPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Verdict(previewVerdictRecord(VerdictLabel.DOUBTFUL)),
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
        )
    }
}

@Preview(
    showBackground = true,
    name = "Verdict NON-VERIFIABLE - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun BubbleOverlayVerdictNonVerifiableDarkPreview() {
    VeriSphereTheme {
        BubbleOverlay(
            state = BubbleState.Verdict(previewVerdictRecord(VerdictLabel.NON_VERIFIABLE)),
            onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
            onTap = {}, onTapNearMiss = {}, onLongPress = {},
            onDragDelta = { _, _ -> }, onDragEnd = {},
        )
    }
}
