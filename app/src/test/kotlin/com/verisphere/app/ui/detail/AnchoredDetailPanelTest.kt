package com.verisphere.app.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Story 2.2 — JVM unit coverage for [computeEmergenceEdge] (architecture
 * line 426: backtick English-sentence test names; JVM only).
 *
 * The helper is pure (Int / Boolean → enum) so all eight cases cover
 * portrait + landscape branches plus midpoint tie-break and out-of-range
 * defensive inputs — no Compose runtime, no Android stubs needed.
 */
class AnchoredDetailPanelTest {

    @Test
    fun `computeEmergenceEdge returns LEFT when the bubble sits at the left edge in portrait`() {
        val edge = computeEmergenceEdge(
            bubbleAnchorXPx = 0,
            screenWidthPx = SCREEN_WIDTH_1080,
            isLandscape = false,
        )

        assertEquals(EmergenceEdge.LEFT, edge)
    }

    @Test
    fun `computeEmergenceEdge returns LEFT when the bubble sits just below the midpoint in portrait`() {
        val edge = computeEmergenceEdge(
            bubbleAnchorXPx = (SCREEN_WIDTH_1080 / 2) - 1,
            screenWidthPx = SCREEN_WIDTH_1080,
            isLandscape = false,
        )

        assertEquals(EmergenceEdge.LEFT, edge)
    }

    @Test
    fun `computeEmergenceEdge returns RIGHT at the exact midpoint as the documented tie-break`() {
        // Tie-break (bubbleAnchorXPx == screenWidthPx / 2) goes to RIGHT
        // per the helper's `else` branch — Story 2.2 AC #7.
        val edge = computeEmergenceEdge(
            bubbleAnchorXPx = SCREEN_WIDTH_1080 / 2,
            screenWidthPx = SCREEN_WIDTH_1080,
            isLandscape = false,
        )

        assertEquals(EmergenceEdge.RIGHT, edge)
    }

    @Test
    fun `computeEmergenceEdge returns RIGHT when the bubble sits at the right edge in portrait`() {
        val edge = computeEmergenceEdge(
            bubbleAnchorXPx = SCREEN_WIDTH_1080,
            screenWidthPx = SCREEN_WIDTH_1080,
            isLandscape = false,
        )

        assertEquals(EmergenceEdge.RIGHT, edge)
    }

    @Test
    fun `computeEmergenceEdge falls through to BOTTOM when the bubble sits in the left half in landscape`() {
        // Landscape always falls through to the bottom-emergence path
        // regardless of bubble X (Story 2.2 AC #3 + UX spec line 821).
        val edge = computeEmergenceEdge(
            bubbleAnchorXPx = 100,
            screenWidthPx = SCREEN_WIDTH_LANDSCAPE,
            isLandscape = true,
        )

        assertEquals(EmergenceEdge.BOTTOM, edge)
    }

    @Test
    fun `computeEmergenceEdge falls through to BOTTOM when the bubble sits in the right half in landscape`() {
        val edge = computeEmergenceEdge(
            bubbleAnchorXPx = SCREEN_WIDTH_LANDSCAPE - 100,
            screenWidthPx = SCREEN_WIDTH_LANDSCAPE,
            isLandscape = true,
        )

        assertEquals(EmergenceEdge.BOTTOM, edge)
    }

    @Test
    fun `computeEmergenceEdge returns LEFT for negative anchor X without clamping`() {
        // Defensive: caller is expected to pass an on-screen X (BubbleOverlayService
        // already coerceIn-clamps). The helper does NOT clamp; off-screen-left
        // still resolves to LEFT via the strict less-than comparison.
        val edge = computeEmergenceEdge(
            bubbleAnchorXPx = -50,
            screenWidthPx = SCREEN_WIDTH_1080,
            isLandscape = false,
        )

        assertEquals(EmergenceEdge.LEFT, edge)
    }

    @Test
    fun `computeEmergenceEdge returns RIGHT for out-of-range anchor X without clamping`() {
        val edge = computeEmergenceEdge(
            bubbleAnchorXPx = 1500,
            screenWidthPx = SCREEN_WIDTH_1080,
            isLandscape = false,
        )

        assertEquals(EmergenceEdge.RIGHT, edge)
    }

    @Test
    fun `computeEmergenceEdge returns RIGHT for screen width zero — defensive boundary`() {
        // Defensive: review F8. Caller-clamping is documented but not
        // enforced by the helper. With screenWidthPx == 0 the integer
        // midpoint is also 0, so any non-negative anchor (including the
        // tie-break value 0) hits the `else` branch → RIGHT. Negative
        // anchors still resolve to LEFT via the strict less-than.
        val edge = computeEmergenceEdge(
            bubbleAnchorXPx = 0,
            screenWidthPx = 0,
            isLandscape = false,
        )

        assertEquals(EmergenceEdge.RIGHT, edge)
    }

    @Test
    fun `computeEmergenceEdge handles odd screen width by flooring the midpoint`() {
        // Odd-width portrait reference (review F8). Integer division floors
        // 1081 / 2 = 540, so the helper's effective midpoint is one pixel
        // left of the true geometric centre 540.5. Anchor at 540 → RIGHT
        // (else branch); anchor at 539 → LEFT (strict less-than). This
        // pixel-level off-by-one is documented behaviour, not a bug — the
        // tie-break already favours RIGHT per AC #7.
        val midpointFloor = computeEmergenceEdge(
            bubbleAnchorXPx = SCREEN_WIDTH_ODD / 2,
            screenWidthPx = SCREEN_WIDTH_ODD,
            isLandscape = false,
        )
        val onePixelLeft = computeEmergenceEdge(
            bubbleAnchorXPx = (SCREEN_WIDTH_ODD / 2) - 1,
            screenWidthPx = SCREEN_WIDTH_ODD,
            isLandscape = false,
        )

        assertEquals(EmergenceEdge.RIGHT, midpointFloor)
        assertEquals(EmergenceEdge.LEFT, onePixelLeft)
    }

    private companion object {
        // Pixel 7-class density-px width — representative portrait reference.
        const val SCREEN_WIDTH_1080 = 1080

        // Representative landscape width (Pixel 7 rotated).
        const val SCREEN_WIDTH_LANDSCAPE = 2400

        // Odd portrait width — exercises integer-division floor in the
        // tie-break path (review F8).
        const val SCREEN_WIDTH_ODD = 1081
    }
}
