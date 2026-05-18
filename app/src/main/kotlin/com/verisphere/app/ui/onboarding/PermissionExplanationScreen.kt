package com.verisphere.app.ui.onboarding

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.verisphere.app.R
import com.verisphere.app.ui.theme.VSSpacing
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
 * when a runtime permission is denied (Story 1.6, AC #7 / AC #8 +
 * Story 5.2 AC #2).
 *
 * Two variants drive the displayed copy + CTA action:
 *
 *   - [PermissionVariant.OVERLAY]      — Story 1.6 path. `Settings.canDrawOverlays`
 *     returned `false`; the host's [onPrimaryClick] should launch
 *     `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` with the package URI.
 *   - [PermissionVariant.NOTIFICATION] — Story 5.2 path. `POST_NOTIFICATIONS`
 *     denied on API 33+; the host's [onPrimaryClick] should invoke the
 *     `ActivityResultContracts.RequestPermission` launcher field-initialized
 *     in `MainActivity` (CDN #7).
 *
 * **Stateless composable** — same pattern as
 * [AccessibilityExplanationScreen] (Story 1.8.5 P7): the side-effects
 * (Intent construction, launcher invocation, `ActivityNotFoundException`
 * fallback Toast) are the host's responsibility. The composable owns
 * only layout + string resolution.
 *
 * Story 5.2 refactor: the Story 1.6 inline Intent launch + Toast
 * fallback have been moved to
 * [com.verisphere.app.MainActivity.launchOverlaySettings] so the
 * composable signature is stateless across both variants (CDN #1
 * stateless-callbacks pattern).
 *
 * @param variant Which permission is denied — drives the title + body +
 *   CTA-label strings.
 * @param onPrimaryClick Invoked when the user taps the variant's primary
 *   CTA (`R.string.permission_allow_overlay` = `"Autoriser"` post Story 7.5
 *   French baseline). The host owns the Intent / launcher invocation.
 * @param onExit Invoked when the user taps the `R.string.permission_exit`
 *   `TextButton` (`"Quitter"` post Story 7.5). The host should `finish()`
 *   the activity.
 */
@Composable
fun PermissionExplanationScreen(
    onPrimaryClick: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    variant: PermissionVariant = PermissionVariant.OVERLAY,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(VSSpacing.space24),
            verticalArrangement = Arrangement.spacedBy(
                space = VSSpacing.space16,
                alignment = Alignment.CenterVertically,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (variant.titleRes != null) {
                Text(
                    text = stringResource(variant.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = stringResource(variant.bodyRes),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onPrimaryClick) {
                Text(text = stringResource(variant.primaryCtaRes))
            }
            TextButton(onClick = onExit) {
                Text(text = stringResource(R.string.permission_exit))
            }
        }
    }
}

/**
 * Permission-variant enum driving the strings rendered by
 * [PermissionExplanationScreen]. Add a new variant when a new runtime
 * permission needs an explanation screen (and bring matching strings
 * in `strings.xml` / `strings_onboarding.xml`).
 */
enum class PermissionVariant(
    val titleRes: Int?,
    val bodyRes: Int,
    val primaryCtaRes: Int,
) {
    OVERLAY(
        titleRes = null,
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
