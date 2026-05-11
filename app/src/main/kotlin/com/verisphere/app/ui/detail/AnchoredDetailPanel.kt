package com.verisphere.app.ui.detail

import android.content.res.Configuration
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verisphere.app.BuildConfig
import com.verisphere.app.ui.theme.VeriSphereTheme

/**
 * Material 3 `ModalBottomSheet` extension that surfaces the verdict's full
 * evidence (Story 2.3 fills the content slot). Anchored emergence is the
 * UX-DR8 contract: the panel slides in from the side of the screen nearest
 * the bubble's last known X position so the trigger and the result keep a
 * continuous spatial relationship (UX spec — anchored emergence section).
 *
 * Story 2.2 ships TWO implementation paths in a single file, dispatched on
 * [BuildConfig.USE_STANDARD_BOTTOM_SHEET]:
 *
 *  - **`true` → standard sheet** (the documented Phase 1 fallback per the
 *    UX spec acceptance + architecture detail-panel section). Stock M3
 *    [ModalBottomSheet] sliding from the screen bottom. **V1 default.**
 *  - **`false` → edge-anchored override** (Approach A). The [ModalBottomSheet]
 *    still vertically grows from the bottom (M3's animation is internal
 *    and cannot be overridden without forking the component — a 250-LoC
 *    rabbit hole the V1 anti-feature list rules out), but the inner
 *    content additionally slides horizontally for ~280 ms via
 *    [Modifier.graphicsLayer] `translationX`. The combined motion reads as
 *    "anchored to the bubble's side". The architect can flip the flag
 *    once on-device validation confirms the hybrid emergence is
 *    acceptable.
 *
 * **Landscape always falls through to BOTTOM** regardless of the flag —
 * the UX spec mandates landscape adapts via stock M3, and a tall sheet
 * sliding from a wide screen's left or right edge looks awkward on every
 * flagship device.
 *
 * **Architecture boundary** (Activity-side mounting): the panel is mounted
 * by [com.verisphere.app.MainActivity]. NEVER mount it inside the bubble's
 * [android.view.WindowManager] overlay tree — `ModalBottomSheet`'s internal
 * `Dialog` requires an Activity window token, and predictive-back
 * dispatching depends on `Dialog`'s flags.
 *
 * **Dismissal** (AC #5): no `BackHandler`, no scrim override, no
 * [androidx.compose.material3.ModalBottomSheetProperties] override. The
 * stock M3 surface routes back-press, swipe-down, and scrim-tap through
 * its `onDismissRequest`, which we forward as the [onDismiss] lambda.
 * Trust the framework.
 *
 * **Lambda-seam** (Story 2.2 Critical Dev Note #5): no [android.content.Context]
 * parameter. `LocalConfiguration` and `LocalContext` belong at the call
 * site (Stories 2.3 / 2.4). The composable body stays preview-friendly
 * and JVM-test-friendly for the [computeEmergenceEdge] helper.
 *
 * @param isVisible When `false`, the entire `ModalBottomSheet` is pulled
 *                  out of the composition (the function returns early).
 *                  Matches the standard M3 idiom — do NOT call
 *                  `sheetState.hide()` imperatively.
 * @param onDismiss Invoked when the user dismisses the panel via back
 *                  gesture, swipe-down, or scrim tap. Call sites flip
 *                  their own `isVisible` state in response.
 * @param bubbleAnchorXPx The X coordinate (in raw window pixels) of the
 *                  visible bubble's centre at panel-open time. Story 2.4
 *                  reads `params.x + haloOffsetPx + bubbleSizePx / 2`
 *                  from [com.verisphere.app.bubble.BubbleOverlayService].
 * @param screenWidthPx The current display width in raw pixels. Pass
 *                  `LocalConfiguration.current.screenWidthDp * density.density`
 *                  from the call site so this composable stays Compose-runtime-pure.
 * @param isLandscape `true` if the current orientation is landscape.
 *                  Forces the BOTTOM emergence path even when
 *                  [BuildConfig.USE_STANDARD_BOTTOM_SHEET] is `false`.
 * @param content Story 2.3's content composable. Receives a [ColumnScope]
 *                  with 16 dp horizontal+vertical padding, 16 dp vertical
 *                  inter-item spacing, AND `verticalScroll(rememberScrollState())`
 *                  already applied (the last added in Story 2.4 to close
 *                  Story 2.3 deferred-work D1 — long OCR content overflows
 *                  cleanly via scroll). The content composable drops section
 *                  composables in sequence without re-applying padding,
 *                  spacing, or scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnchoredDetailPanel(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    bubbleAnchorXPx: Int,
    screenWidthPx: Int,
    isLandscape: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!isVisible) return

    val edge = computeEmergenceEdge(
        bubbleAnchorXPx = bubbleAnchorXPx,
        screenWidthPx = screenWidthPx,
        isLandscape = isLandscape,
    )

    AnchoredDetailPanelImpl(
        useStandardBottomSheet = BuildConfig.USE_STANDARD_BOTTOM_SHEET,
        edge = edge,
        isVisible = true,
        onDismiss = onDismiss,
        modifier = modifier,
        content = content,
    )
}

/**
 * Internal composable that takes [useStandardBottomSheet] as a parameter
 * (instead of reading [BuildConfig] directly) so the `@Preview` catalogue
 * can exercise both paths without rebuilding `BuildConfig` per preview.
 * Production callers go through the public [AnchoredDetailPanel] which
 * binds the flag to the BuildConfig value at compile time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnchoredDetailPanelImpl(
    useStandardBottomSheet: Boolean,
    edge: EmergenceEdge,
    isVisible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!isVisible) return

    // Standard-sheet path is taken whenever the flag is true OR the resolved
    // emergence edge is BOTTOM (which already covers landscape per
    // computeEmergenceEdge's branch). `edge in {LEFT, RIGHT}` AND the flag is
    // false is the only way into the edge-anchored path.
    val takeAnchoredPath = !useStandardBottomSheet && edge != EmergenceEdge.BOTTOM
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        // The anchored slide modifier is a no-op on the standard path: it
        // returns its receiver unchanged so neither animateFloatAsState nor
        // graphicsLayer runs on a path that doesn't need them (review F1+F4).
        //
        // Story 2.4 — verticalScroll closes Story 2.3 deferred-work D1
        // (long OCR content clipped off-screen at fontScale = 1.5f).
        // Placement: AFTER .padding (so the scroll handle does not extend
        // past the panel's 16 dp content padding) and BEFORE
        // .anchoredHorizontalSlide (which is a draw-only graphicsLayer
        // transform — does not affect layout / hit-testing). The scroll
        // state resets per open because the parent if (!isVisible) return
        // unmounts on dismiss — desired UX (user sees the verdict first).
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .anchoredHorizontalSlide(takeAnchoredPath, edge),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
        }
    }
}

/**
 * Applies the Approach A horizontal slide-in to the receiver `Modifier`
 * when [enabled] is `true`; returns the receiver unchanged otherwise.
 *
 * **Why the two-stage `armed` trick:** `animateFloatAsState` seeds its
 * initial value from the FIRST `targetValue` it sees, so writing
 * `targetValue = 0f` on the first composition would freeze the slide at
 * 0 and never animate (the bug Story 2.2's first revision shipped — review
 * finding F1). To actually animate from off-screen to 0, the helper:
 *
 *  1. seeds `targetValue = initialTranslationX` (±[ANCHORED_TRANSLATION_DP])
 *     on the first composition, so the [animateFloatAsState] internal
 *     `Animatable` initialises off-screen,
 *  2. flips `armed` to `true` from a [SideEffect] (synchronous, runs after
 *     the composition completes successfully),
 *  3. on the recomposition triggered by the `armed` write, evaluates
 *     `targetValue = 0f`, which makes [animateFloatAsState] animate from
 *     the off-screen initial to 0 over [ANCHORED_SLIDE_DURATION_MS] ms.
 *
 * The `Animatable + LaunchedEffect(Unit) { animateTo(0f) }` pattern is the
 * idiomatic Compose alternative but Critical Dev Note #9 explicitly bans
 * `LaunchedEffect` and `rememberCoroutineScope`. [SideEffect] is
 * spec-compliant — synchronous, scoped to the composition, no coroutine
 * machinery.
 *
 * **Density:** the slide distance is stored in dp ([ANCHORED_TRANSLATION_DP])
 * and converted to pixels via [LocalDensity] inside the helper — review
 * F2 caught the original `300f` raw-pixel constant which produced ~75 dp
 * of slide on xxxhdpi vs ~300 dp on mdpi.
 */
@Composable
private fun Modifier.anchoredHorizontalSlide(
    enabled: Boolean,
    edge: EmergenceEdge,
): Modifier {
    if (!enabled) return this

    val density = LocalDensity.current
    val initialTranslationX = remember(edge, density) {
        with(density) {
            when (edge) {
                EmergenceEdge.LEFT -> -ANCHORED_TRANSLATION_DP.toPx()
                EmergenceEdge.RIGHT -> ANCHORED_TRANSLATION_DP.toPx()
                EmergenceEdge.BOTTOM -> 0f
            }
        }
    }

    var armed by remember { mutableStateOf(false) }
    SideEffect { if (!armed) armed = true }

    val animatedTranslationX by animateFloatAsState(
        targetValue = if (armed) 0f else initialTranslationX,
        animationSpec = tween(
            durationMillis = ANCHORED_SLIDE_DURATION_MS,
            easing = FastOutSlowInEasing,
        ),
        label = "AnchoredDetailPanel.translationX",
    )

    return this.graphicsLayer { translationX = animatedTranslationX }
}

/**
 * Resolved emergence side for the anchored detail panel. [BOTTOM] is the
 * documented fallback (UX spec — anchored emergence + standard fallback
 * sections); [LEFT] / [RIGHT] are the UX-DR8 anchored variants taken in
 * portrait when [BuildConfig.USE_STANDARD_BOTTOM_SHEET] is `false`.
 */
internal enum class EmergenceEdge { LEFT, RIGHT, BOTTOM }

/**
 * Pure helper resolving the panel's emergence side from the bubble's
 * current X anchor and the display orientation. Kept `internal` and
 * top-level so the JVM unit test in [AnchoredDetailPanelTest] can drive
 * it without a Compose runtime.
 *
 * **Tie-break:** when [bubbleAnchorXPx] equals exactly `screenWidthPx / 2`,
 * the helper returns [EmergenceEdge.RIGHT] via the `else` branch (Story
 * 2.2 AC #7). This is documented behaviour, not an implementation
 * accident.
 *
 * **Boundary semantics:** the helper does NOT clamp `bubbleAnchorXPx`.
 * Negative values resolve to LEFT (strict less-than); values exceeding
 * `screenWidthPx` resolve to RIGHT. Clamping is the caller's
 * responsibility — [com.verisphere.app.bubble.BubbleOverlayService]
 * already coerceIn-clamps the visible bubble centre to the on-screen
 * range, so V1 callers never produce an out-of-range value in practice.
 *
 * **Landscape:** always returns [EmergenceEdge.BOTTOM] regardless of
 * X — the UX spec mandates landscape adapts via stock M3, so the
 * bottom-emergence path is the right choice on wide screens.
 */
internal fun computeEmergenceEdge(
    bubbleAnchorXPx: Int,
    screenWidthPx: Int,
    isLandscape: Boolean,
): EmergenceEdge = when {
    isLandscape -> EmergenceEdge.BOTTOM
    bubbleAnchorXPx < screenWidthPx / 2 -> EmergenceEdge.LEFT
    else -> EmergenceEdge.RIGHT
}

// ----- Constants ------------------------------------------------------

/** Approach A horizontal-slide initial offset, density-independent. The
 *  300 dp figure is empirically chosen — wide enough to feel distinct from
 *  the standard vertical animation without overshooting on small screens.
 *  Stored in [Dp] (not raw pixels) so the slide distance is consistent
 *  across mdpi → xxxhdpi devices (review F2). Converted via
 *  [LocalDensity] inside [Modifier.anchoredHorizontalSlide]. */
private val ANCHORED_TRANSLATION_DP: Dp = 300.dp

/** Approach A horizontal-slide duration (milliseconds). M3 standard for
 *  medium-emphasis transitions (Material spec recommends 200–300 ms). */
private const val ANCHORED_SLIDE_DURATION_MS = 280

// ----- Previews -------------------------------------------------------

/**
 * Preview placeholder content — a developer-facing literal that documents
 * Story 2.3's slot contract without polluting `strings.xml` with an
 * English placeholder. Story 2.3 will replace every preview's content
 * with the real verdict / OCR / sources / footer composable.
 */
@Composable
private fun PreviewPlaceholderContent() {
    Box(modifier = Modifier.heightIn(min = 200.dp)) {
        Text(text = "Detail panel content placeholder")
    }
}

@Preview(
    name = "Standard sheet, light",
    showSystemUi = true,
    device = "spec:width=400dp,height=800dp",
)
@Composable
private fun AnchoredDetailPanelStandardLightPreview() {
    VeriSphereTheme(darkTheme = false) {
        AnchoredDetailPanelImpl(
            useStandardBottomSheet = true,
            edge = EmergenceEdge.BOTTOM,
            isVisible = true,
            onDismiss = {},
        ) {
            PreviewPlaceholderContent()
        }
    }
}

@Preview(
    name = "Standard sheet, dark",
    showSystemUi = true,
    device = "spec:width=400dp,height=800dp",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AnchoredDetailPanelStandardDarkPreview() {
    VeriSphereTheme(darkTheme = true) {
        AnchoredDetailPanelImpl(
            useStandardBottomSheet = true,
            edge = EmergenceEdge.BOTTOM,
            isVisible = true,
            onDismiss = {},
        ) {
            PreviewPlaceholderContent()
        }
    }
}

@Preview(
    name = "Anchored LEFT, light",
    showSystemUi = true,
    device = "spec:width=400dp,height=800dp",
)
@Composable
private fun AnchoredDetailPanelLeftLightPreview() {
    VeriSphereTheme(darkTheme = false) {
        AnchoredDetailPanelImpl(
            useStandardBottomSheet = false,
            edge = EmergenceEdge.LEFT,
            isVisible = true,
            onDismiss = {},
        ) {
            PreviewPlaceholderContent()
        }
    }
}

@Preview(
    name = "Anchored RIGHT, dark",
    showSystemUi = true,
    device = "spec:width=400dp,height=800dp",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AnchoredDetailPanelRightDarkPreview() {
    VeriSphereTheme(darkTheme = true) {
        AnchoredDetailPanelImpl(
            useStandardBottomSheet = false,
            edge = EmergenceEdge.RIGHT,
            isVisible = true,
            onDismiss = {},
        ) {
            PreviewPlaceholderContent()
        }
    }
}

@Preview(
    name = "Landscape forced BOTTOM, light",
    showSystemUi = true,
    device = "spec:width=800dp,height=400dp",
)
@Composable
private fun AnchoredDetailPanelLandscapePreview() {
    // Landscape simulation: even with useStandardBottomSheet = false, the
    // edge resolves to BOTTOM via computeEmergenceEdge's `isLandscape`
    // branch (which the call site would invoke). For this preview we wire
    // the BOTTOM edge directly to verify the standard-sheet path renders
    // cleanly on a wide canvas.
    VeriSphereTheme(darkTheme = false) {
        AnchoredDetailPanelImpl(
            useStandardBottomSheet = false,
            edge = EmergenceEdge.BOTTOM,
            isVisible = true,
            onDismiss = {},
        ) {
            PreviewPlaceholderContent()
        }
    }
}
