package com.verisphere.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.verisphere.app.ui.theme.VeriSphereTheme

/**
 * Placeholder Activity — confirms the project boots, the manifest is
 * wired, and the theme renders end-to-end.
 *
 * Story 4.x replaces the body with the real history list. Until then,
 * this Activity exists only as a launchable target and a smoke test
 * surface for the bootstrap (Story 1.1, AC #14).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VeriSphereTheme {
                BootstrapPlaceholder()
            }
        }
    }
}

@Composable
private fun BootstrapPlaceholder() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = stringResource(R.string.app_name))
        }
    }
}

@Preview(showBackground = true, name = "Light")
@Preview(showBackground = true, name = "Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BootstrapPlaceholderPreview() {
    VeriSphereTheme {
        BootstrapPlaceholder()
    }
}
