package com.verisphere.app.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.verisphere.app.R
import com.verisphere.app.ui.theme.VSSpacing
import com.verisphere.app.ui.theme.VeriSphereTheme
import com.verisphere.app.util.tag

/**
 * Minimal permission-explanation surface shown by [com.verisphere.app.MainActivity]
 * when `Settings.canDrawOverlays` returns false (Story 1.6, AC #7, AC #8).
 *
 * Why minimal: the FULL first-launch permission orchestration
 * (`POST_NOTIFICATIONS` + `SYSTEM_ALERT_WINDOW` + service start +
 * tutorial) is Story 5.2. This screen exists in 1.6 only to render
 * the "denied → explain → re-attempt or exit" path required by AC #7.
 *
 * `SYSTEM_ALERT_WINDOW` is a special "appop" permission (API 23+) —
 * it cannot be granted via `ActivityCompat.requestPermissions`. The
 * "Allow overlay" button launches `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`
 * with the app's package URI; the result becomes visible to MainActivity
 * via `onResume` re-checking `Settings.canDrawOverlays`.
 */
@Composable
fun PermissionExplanationScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
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
                text = stringResource(R.string.permission_overlay_explanation),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                onClick = {
                    // No FLAG_ACTIVITY_NEW_TASK: LocalContext is the host
                    // Activity, so the Settings screen launches in the
                    // same task. Pressing Back from Settings then returns
                    // to MainActivity, whose onResume re-checks the
                    // overlay permission and flips the gate. With
                    // FLAG_ACTIVITY_NEW_TASK Back would land on the home
                    // screen and the recheck would never fire.
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        // Some stripped Android distributions (heavily-modified
                        // OEM skins, GSI, automotive variants) don't expose
                        // ACTION_MANAGE_OVERLAY_PERMISSION. Fall back to a
                        // user-facing instruction rather than crashing on
                        // the recovery path.
                        Log.w(TAG, "ACTION_MANAGE_OVERLAY_PERMISSION not resolvable on this device", e)
                        Toast.makeText(
                            context,
                            R.string.permission_settings_unavailable,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            ) {
                Text(text = stringResource(R.string.permission_allow_overlay))
            }
            TextButton(onClick = onExit) {
                Text(text = stringResource(R.string.permission_exit))
            }
        }
    }
}

private val TAG = tag("PermissionExplanationScreen")

@Preview(showBackground = true, name = "Light")
@Preview(
    showBackground = true,
    name = "Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PermissionExplanationScreenPreview() {
    VeriSphereTheme {
        PermissionExplanationScreen(onExit = {})
    }
}
