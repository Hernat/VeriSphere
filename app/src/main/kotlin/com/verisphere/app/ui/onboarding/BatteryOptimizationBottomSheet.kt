package com.verisphere.app.ui.onboarding

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.verisphere.app.R
import com.verisphere.app.ui.theme.VSSpacing
import com.verisphere.app.ui.theme.VeriSphereTheme

/**
 * Story 5.3 — single-show bottom-sheet shown on hostile OEMs after
 * first-launch onboarding completes (AR26, D5.8). Encourages the user
 * to disable battery optimisation for VeriSphere so the foreground
 * service is not killed by aggressive OEM battery managers (Samsung
 * One UI / Xiaomi HyperOS / Huawei EMUI / Honor MagicOS / Oppo
 * ColorOS / Vivo OriginOS / Realme UI / OnePlus OxygenOS).
 *
 * **Stateless callbacks pattern** (Story 5.1 / 5.2 CDN #1). MainActivity
 * OWNS: (a) Intent construction for
 * `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (CDN #3 — NEVER
 * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` which would require the
 * forbidden `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission per
 * D5.8 / AR26 / NFR10 minimum-permission posture); (b)
 * `battery_optimization_prompted` flag write via
 * [com.verisphere.app.onboarding.OnboardingOrchestrator.markBatteryOptimizationPrompted]
 * wrapped in `NonCancellable + Dispatchers.IO` (CDN #5 mirrors Story 5.2
 * P4 / P7 pattern); (c) `Build.MANUFACTURER` read; (d)
 * `ActivityNotFoundException` catch + Toast fallback. The composable
 * owns ONLY layout + string resolution + `ModalBottomSheet` state.
 *
 * **`onDismissRequest` is the single dismissal seam** (CDN #4). M3's
 * `ModalBottomSheet` routes all four dismissal paths through this
 * callback: scrim tap, swipe-down gesture, predictive-back gesture
 * (via the internal `Dialog`'s back-press flags), and programmatic
 * `isVisible = false`. The host forwards as [onDismiss]; persistence
 * happens exactly once per sheet lifecycle.
 *
 * **`skipPartiallyExpanded = true`** because the content is small
 * (one paragraph + one button) — partially-expanded "peek" state would
 * crop the CTA and force the user to drag it up before they can tap.
 *
 * **`@OptIn(ExperimentalMaterial3Api::class)`** — `ModalBottomSheet`
 * has been experimental in Compose M3 since 1.4.0 (still experimental
 * in 1.11.0; the API has been stable for 18+ months but the annotation
 * persists). Matches [com.verisphere.app.ui.detail.AnchoredDetailPanel]
 * usage precedent.
 *
 * @param onOpenSettings Invoked when the user taps the primary CTA. The
 *   host should launch `Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)`
 *   AND persist the dismissal flag BEFORE the `startActivity` call
 *   (CDN #12 — defends single-show invariant against process kill in
 *   Settings).
 * @param onDismiss Invoked when the sheet dismisses by any path. The
 *   host MUST persist `battery_optimization_prompted = true` and flip
 *   the Compose state field so the sheet does not re-mount.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryOptimizationBottomSheet(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = VSSpacing.space24,
                    end = VSSpacing.space24,
                    bottom = VSSpacing.space24,
                ),
            verticalArrangement = Arrangement.spacedBy(VSSpacing.space16),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = stringResource(R.string.battery_optimization_explainer),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onOpenSettings) {
                Text(text = stringResource(R.string.battery_optimization_open_settings))
            }
        }
    }
}

@Preview(showBackground = true, name = "Battery Optimisation Light")
@Preview(
    showBackground = true,
    name = "Battery Optimisation Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun BatteryOptimizationBottomSheetPreview() {
    VeriSphereTheme {
        BatteryOptimizationBottomSheet(
            onOpenSettings = {},
            onDismiss = {},
        )
    }
}
