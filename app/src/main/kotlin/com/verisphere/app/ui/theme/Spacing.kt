package com.verisphere.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * VeriSphere spacing scale — UX-DR4. 4 dp baseline grid.
 * All composable layout uses these tokens exclusively; inline `4.dp`,
 * `8.dp`, etc. in composables are forbidden so the spacing identity
 * stays centralised.
 *
 * Architecture reference: UX Step 8 — Spacing & Layout Foundation.
 * Story 1.1 closes UX-DR4 by introducing the scale; subsequent stories
 * apply the tokens to their composables (bubble, flash tooltip,
 * detail panel, history list, onboarding tutorial).
 */
object VSSpacing {
    val space4: Dp = 4.dp
    val space8: Dp = 8.dp
    val space12: Dp = 12.dp
    val space16: Dp = 16.dp
    val space24: Dp = 24.dp
    val space32: Dp = 32.dp
}
