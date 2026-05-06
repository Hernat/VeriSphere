package com.verisphere.app

import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.verisphere.app.ui.onboarding.PermissionExplanationScreen
import com.verisphere.app.ui.theme.VeriSphereTheme

/**
 * Single-Activity host for VeriSphere.
 *
 * Story 1.6 wires the `SYSTEM_ALERT_WINDOW` overlay-permission gate
 * (AC #8): if the permission is denied, the activity renders
 * [PermissionExplanationScreen]; otherwise it renders the bootstrap
 * placeholder. The recheck happens in `onResume` so the UI updates
 * after the user returns from the system settings screen.
 *
 * Story 4.x replaces the placeholder body with the real history list.
 * Story 5.2 owns the FULL first-launch orchestration (`POST_NOTIFICATIONS`,
 * service start, tutorial) — Story 1.6 ships only the minimal denied-overlay
 * path required by AC #7.
 */
class MainActivity : ComponentActivity() {

    // Held as a Compose-observable field on the Activity (not inside
    // setContent {}) so onResume() can update it and trigger
    // recomposition without rebuilding the content tree.
    private var overlayGranted: Boolean by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        overlayGranted = Settings.canDrawOverlays(this)
        setContent {
            VeriSphereTheme {
                if (overlayGranted) {
                    BootstrapPlaceholder()
                } else {
                    PermissionExplanationScreen(onExit = ::finish)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have returned from the system settings screen
        // after granting/denying the overlay permission; recheck so
        // the UI flips between PermissionExplanationScreen and
        // BootstrapPlaceholder accordingly.
        overlayGranted = Settings.canDrawOverlays(this)
    }
}

/**
 * Placeholder body. Wraps content in a Material 3 `Scaffold` so the
 * status / navigation bar insets propagate correctly under
 * `enableEdgeToEdge()` — without this the placeholder text would
 * draw under the system bars on devices that respect inset reporting.
 */
@Composable
private fun BootstrapPlaceholder() {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = stringResource(R.string.app_name))
        }
    }
}

@Preview(showBackground = true, name = "Light")
@Preview(showBackground = true, name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BootstrapPlaceholderPreview() {
    VeriSphereTheme {
        BootstrapPlaceholder()
    }
}
