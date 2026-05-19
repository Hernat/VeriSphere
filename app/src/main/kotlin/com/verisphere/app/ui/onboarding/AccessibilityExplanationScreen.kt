package com.verisphere.app.ui.onboarding

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.verisphere.app.R
import com.verisphere.app.ui.theme.VSPalette
import com.verisphere.app.ui.theme.VSSpacing
import com.verisphere.app.ui.theme.VSTypography
import com.verisphere.app.ui.theme.VeriSphereTheme

/**
 * Accessibility-explanation surface (Story 1.8.5) — refonte Wispr Flow
 * Epic 8 Story 8.1. Pattern editorial : grande icône sage en haut, titre
 * EB Garamond 28sp, body Figtree 17sp, CTA sage 52dp hauteur.
 *
 * @param onActivateClick Invoked when the user taps "Activer". The
 *   host should `startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))`
 *   and handle `ActivityNotFoundException` with a toast.
 * @param onExitClick Invoked when the user taps "Plus tard". The host
 *   should `finish()` the activity.
 */
@Composable
fun AccessibilityExplanationScreen(
    onActivateClick: () -> Unit,
    onExitClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = VSPalette.canvas,
    ) { innerPadding ->
        // Code-review F6 (Group B) — verticalScroll added so content
        // never clips off-screen at fontScale ≥ 1.5 on small phones.
        // `Spacer(weight(1f))` previously needed an unbounded scroll
        // parent ; bumped to `heightIn(min = ...)` so the layout still
        // bottom-anchors the CTA when content is short, and scrolls
        // when content is long.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = VSSpacing.space32,
                    vertical = VSSpacing.space40,
                )
                .heightIn(min = ACCESSIBILITY_SCREEN_MIN_CONTENT_DP.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(VSSpacing.space40))

            // Sage rounded-square frame containing the VeriSphere logo
            // ([R.drawable.logo_vs]) — same brand mark used by the
            // launcher icon adaptive foreground + the onboarding
            // tutorial sage frame.
            Surface(
                modifier = Modifier.size(72.dp),
                shape = RoundedCornerShape(20.dp),
                color = VSPalette.accentSage.copy(alpha = 0.18f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Logo round-clip 2026-05-19 — clipped to a 14 dp
                    // rounded square so the source PNG's white corners
                    // mirror the parent sage frame's 20 dp roundedness.
                    // 14 / 48 ≈ 20 / 72, same ratio as the parent.
                    Image(
                        painter = painterResource(R.drawable.logo_vs),
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                }
            }

            Spacer(modifier = Modifier.height(VSSpacing.space32))

            // Editorial title — EB Garamond 28sp.
            Text(
                text = stringResource(R.string.accessibility_explanation_title),
                style = VSTypography.headlineSerif,
                color = VSPalette.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )

            Spacer(modifier = Modifier.height(VSSpacing.space20))

            // Body — Figtree 17sp.
            Text(
                text = stringResource(R.string.accessibility_explanation_body),
                style = VSTypography.bodyLargeSans,
                color = VSPalette.inkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 340.dp),
            )

            Spacer(modifier = Modifier.weight(1f))

            // CTA — sage_deep, 52dp height, 14dp corners (Wispr-pill).
            Button(
                onClick = onActivateClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VSPalette.accentSageDeep,
                    contentColor = VSPalette.onAccentSageDeep,
                ),
            ) {
                Text(
                    text = stringResource(R.string.accessibility_action_activate),
                    style = VSTypography.headlineBodySans,
                )
            }

            Spacer(modifier = Modifier.height(VSSpacing.space8))

            TextButton(onClick = onExitClick) {
                Text(
                    text = stringResource(R.string.permission_exit),
                    style = VSTypography.bodySans,
                    color = VSPalette.inkMuted,
                )
            }
        }
    }
}

/**
 * Code-review F6 (Group B) — bottom-anchor floor for the scrollable
 * column. On a phone tall enough to fit all content, the `heightIn`
 * floor matches the parent height so `Spacer.weight(1f)` keeps the
 * CTA bottom-anchored ; on smaller phones / fontScale ≥ 1.5 the
 * actual content height exceeds the floor and the column scrolls.
 */
private const val ACCESSIBILITY_SCREEN_MIN_CONTENT_DP: Int = 560

@Preview(showBackground = true, name = "Light")
@Preview(
    showBackground = true,
    name = "Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AccessibilityExplanationScreenPreview() {
    VeriSphereTheme {
        AccessibilityExplanationScreen(onActivateClick = {}, onExitClick = {})
    }
}
