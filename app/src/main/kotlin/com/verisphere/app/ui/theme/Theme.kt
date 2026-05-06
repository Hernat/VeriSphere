package com.verisphere.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import com.verisphere.app.R

/**
 * VeriSphere Material 3 theme — fixed palette, auto-follow-system
 * light/dark, dynamicColor INTENTIONALLY disabled.
 *
 * Shared between MainActivity and BubbleOverlayService Compose hosts;
 * do not move out of `ui/theme/` without updating both call sites.
 *
 * Material You / dynamic colour is disabled in V1 because the
 * "Powered by Google" trust narrative requires consistent branding
 * across the user base — not a per-device wallpaper-derived palette.
 */
@Composable
fun VeriSphereTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Resolve palette tokens once per composition; values/colors.xml vs
    // values-night/colors.xml are picked by the platform automatically.
    val background = colorResource(R.color.vs_background)
    val surfaceVariant = colorResource(R.color.vs_surface_variant)
    val onBackground = colorResource(R.color.vs_on_background)
    val onBackgroundMuted = colorResource(R.color.vs_on_background_muted)
    val brandAccent = colorResource(R.color.vs_brand_google_blue)

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = brandAccent,
            background = background,
            surface = background,
            surfaceVariant = surfaceVariant,
            onBackground = onBackground,
            onSurface = onBackground,
            onSurfaceVariant = onBackgroundMuted,
        )
    } else {
        lightColorScheme(
            primary = brandAccent,
            background = background,
            surface = background,
            surfaceVariant = surfaceVariant,
            onBackground = onBackground,
            onSurface = onBackground,
            onSurfaceVariant = onBackgroundMuted,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VeriSphereTypography,
        content = content,
    )
}
