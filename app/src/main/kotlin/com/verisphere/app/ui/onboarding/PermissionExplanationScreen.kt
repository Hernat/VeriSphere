package com.verisphere.app.ui.onboarding

import android.content.res.Configuration
import androidx.compose.foundation.Image
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
import java.util.Locale

/**
 * Story 5.3 / AR26 / D5.8 — manufacturers whose battery managers
 * aggressively kill foreground services. Matched case-insensitively
 * against `Build.MANUFACTURER.lowercase(Locale.ROOT)`. Limited to 8
 * manufacturers initially (architecture L991 / L1006); expand based on
 * user reports.
 *
 * Lowercase only — match-time uses `.lowercase(Locale.ROOT)` per
 * Story 5.3 CDN #2 (Turkish dotless-i hazard would break "İ" → "i"
 * matching on Turkish locales if `Locale.getDefault()` were used).
 *
 * Co-located with [PermissionExplanationScreen] per epics line 846 and
 * architecture L991: "documenting the list in
 * `ui/onboarding/PermissionExplanationScreen.kt` (or a const file)
 * avoids future ambiguity".
 */
val HOSTILE_OEMS: Set<String> = setOf(
    "samsung",
    "xiaomi",
    "huawei",
    "honor",
    "oppo",
    "vivo",
    "realme",
    "oneplus",
)

/**
 * Story 5.3 — pure helper that decides whether [manufacturer] (as
 * provided by `Build.MANUFACTURER` at the call site) belongs to the
 * [HOSTILE_OEMS] closed set. Case-insensitive via
 * `lowercase(Locale.ROOT)` per CDN #2.
 *
 * Pure-function form (no `Build.MANUFACTURER` read inside) so JVM unit
 * tests can exercise the boolean algebra without Robolectric — mirrors
 * [OnboardingOrchestrator.canStartBubbleService]'s `apiLevel` injection
 * pattern (Story 5.2 lambda-seam discipline).
 */
fun isHostileOem(manufacturer: String): Boolean =
    manufacturer.lowercase(Locale.ROOT) in HOSTILE_OEMS

/**
 * Permission-explanation surface shown by [com.verisphere.app.MainActivity]
 * when a runtime permission is denied (Story 1.6).
 *
 * **WisprFlow refonte 2026-05-19** — aligned with the editorial pattern
 * shared by [AccessibilityExplanationScreen] + [OnboardingTutorialOverlay] :
 * 72 dp sage rounded-square logo top-centre, EB Garamond 28 sp title,
 * Figtree 17 sp body (inkMuted), bottom-anchored full-width sage CTA
 * (52 dp / 14 dp corners), "Quitter" `TextButton` below.
 *
 * Two variants drive the displayed copy + CTA action :
 *
 *   - [PermissionVariant.OVERLAY]      — Story 1.6 path. `Settings.canDrawOverlays`
 *     returned `false` ; the host's [onPrimaryClick] should launch
 *     `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` with the package URI.
 *   - [PermissionVariant.NOTIFICATION] — Story 5.2 path (legacy ; no
 *     longer routed in production after the 2026-05-19 notification-gate
 *     cleanup, but kept in the enum + preview catalogue so an eventual
 *     opt-in surface can re-use the same composable without a structural
 *     refactor).
 *
 * **Stateless composable** — same pattern as
 * [AccessibilityExplanationScreen] (Story 1.8.5 P7) : the side-effects
 * (Intent construction, launcher invocation, `ActivityNotFoundException`
 * fallback Toast) are the host's responsibility. The composable owns
 * only layout + string resolution.
 *
 * @param variant Which permission is denied — drives the title + body +
 *   CTA-label strings.
 * @param onPrimaryClick Invoked when the user taps the variant's primary
 *   CTA. The host owns the Intent / launcher invocation.
 * @param onExit Invoked when the user taps the `R.string.permission_exit`
 *   `TextButton` (`"Quitter"`). The host should `finish()` the activity.
 */
@Composable
fun PermissionExplanationScreen(
    onPrimaryClick: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    variant: PermissionVariant = PermissionVariant.OVERLAY,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = VSPalette.canvas,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = VSSpacing.space32,
                    vertical = VSSpacing.space40,
                )
                .heightIn(min = PERMISSION_SCREEN_MIN_CONTENT_DP.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(VSSpacing.space40))

            // Sage rounded-square frame containing the VeriSphere logo
            // ([R.drawable.logo_vs]) — same brand mark used by the
            // launcher icon adaptive foreground + the AccessibilityExplanationScreen
            // + the onboarding tutorial sage frame.
            Surface(
                modifier = Modifier.size(72.dp),
                shape = RoundedCornerShape(20.dp),
                color = VSPalette.accentSage.copy(alpha = 0.18f),
            ) {
                Box(contentAlignment = Alignment.Center) {
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

            Text(
                text = stringResource(variant.titleRes),
                style = VSTypography.headlineSerif,
                color = VSPalette.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )

            Spacer(modifier = Modifier.height(VSSpacing.space20))

            Text(
                text = stringResource(variant.bodyRes),
                style = VSTypography.bodyLargeSans,
                color = VSPalette.inkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 340.dp),
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onPrimaryClick,
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
                    text = stringResource(variant.primaryCtaRes),
                    style = VSTypography.headlineBodySans,
                )
            }

            Spacer(modifier = Modifier.height(VSSpacing.space8))

            TextButton(onClick = onExit) {
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
 * Bottom-anchor floor for the scrollable column — mirrors the
 * [AccessibilityExplanationScreen] `ACCESSIBILITY_SCREEN_MIN_CONTENT_DP`
 * pattern : on a phone tall enough to fit all content the floor matches
 * the parent height so `Spacer.weight(1f)` keeps the CTA bottom-anchored ;
 * on smaller phones / fontScale ≥ 1.5 the actual content height exceeds
 * the floor and the column scrolls.
 */
private const val PERMISSION_SCREEN_MIN_CONTENT_DP: Int = 560

/**
 * Permission-variant enum driving the strings rendered by
 * [PermissionExplanationScreen]. Both variants are non-null `titleRes`
 * since the WisprFlow refonte 2026-05-19 (the editorial pattern needs a
 * big serif headline — there's no graceful "no title" fallback the way
 * the pre-refonte Material layout had).
 */
enum class PermissionVariant(
    val titleRes: Int,
    val bodyRes: Int,
    val primaryCtaRes: Int,
) {
    OVERLAY(
        titleRes = R.string.permission_overlay_explanation_title,
        bodyRes = R.string.permission_overlay_explanation,
        primaryCtaRes = R.string.permission_allow_overlay,
    ),
    NOTIFICATION(
        titleRes = R.string.permission_notification_explanation_title,
        bodyRes = R.string.permission_notification_explanation_body,
        primaryCtaRes = R.string.permission_notification_explanation_cta,
    ),
}

@Preview(showBackground = true, name = "Overlay Light")
@Preview(
    showBackground = true,
    name = "Overlay Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PermissionExplanationScreenOverlayPreview() {
    VeriSphereTheme {
        PermissionExplanationScreen(
            variant = PermissionVariant.OVERLAY,
            onPrimaryClick = {},
            onExit = {},
        )
    }
}

@Preview(showBackground = true, name = "Overlay Large Font", fontScale = 1.5f)
@Composable
private fun PermissionExplanationScreenOverlayLargeFontPreview() {
    VeriSphereTheme {
        PermissionExplanationScreen(
            variant = PermissionVariant.OVERLAY,
            onPrimaryClick = {},
            onExit = {},
        )
    }
}

@Preview(showBackground = true, name = "Notification Light")
@Preview(
    showBackground = true,
    name = "Notification Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PermissionExplanationScreenNotificationPreview() {
    VeriSphereTheme {
        PermissionExplanationScreen(
            variant = PermissionVariant.NOTIFICATION,
            onPrimaryClick = {},
            onExit = {},
        )
    }
}
