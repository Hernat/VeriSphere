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

/**
 * Minimal accessibility-explanation surface shown by
 * [com.verisphere.app.MainActivity] when the
 * `VeriSphereAccessibilityService` is not enabled but the overlay
 * permission IS granted (Story 1.8.5, AC #11).
 *
 * Why minimal: the FULL onboarding tutorial (4 cards including
 * accessibility activation as card 1) is Story 5.1. This screen
 * exists in 1.8.5 only to render the "overlay-granted but
 * accessibility-off → explain → activate or exit" path so the bubble
 * service can actually capture screens.
 *
 * **Stateless composable** — both side-effects (deep-link to
 * `Settings.ACTION_ACCESSIBILITY_SETTINGS` AND back-stack `finish()`)
 * are passed in as callbacks. The host
 * [com.verisphere.app.MainActivity.launchAccessibilitySettings]
 * owns the Intent construction + `ActivityNotFoundException` toast
 * fallback so this composable stays trivially testable in
 * `@Preview` and any future Compose UI test (Story 5.1's tutorial
 * card 1 will reuse this composable signature).
 *
 * Pattern mirrors [PermissionExplanationScreen] (Story 1.6) — same
 * scaffold, same warm-tone copy authoring (UX-DR17). Difference:
 * [PermissionExplanationScreen] hardcodes the Settings deep-link
 * inline (Story 1.6's pattern); this screen pulls it out per the
 * Story 1.8.5 code-review patch P7 rationale ("stateless callbacks
 * for testability").
 *
 * @param onActivateClick Invoked when the user taps "Activer". The
 *   host should `startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))`
 *   and handle `ActivityNotFoundException` with a toast.
 * @param onExitClick Invoked when the user taps "Exit". The host
 *   should `finish()` the activity.
 */
@Composable
fun AccessibilityExplanationScreen(
    onActivateClick: () -> Unit,
    onExitClick: () -> Unit,
    modifier: Modifier = Modifier,
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
            Text(
                text = stringResource(R.string.accessibility_explanation_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.accessibility_explanation_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onActivateClick) {
                Text(text = stringResource(R.string.accessibility_action_activate))
            }
            TextButton(onClick = onExitClick) {
                Text(text = stringResource(R.string.permission_exit))
            }
        }
    }
}

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
