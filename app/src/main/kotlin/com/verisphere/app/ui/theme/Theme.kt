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
 * "Built with Gemini" trust narrative requires consistent branding
 * across the user base — not a per-device wallpaper-derived palette.
 * (Phrase pinned in Sprint Change 2026-05-18 / Story 7.4 MS7 — see
 * CHANGELOG for the rename audit trail.)
 */
@Composable
fun VeriSphereTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Epic 8 Story 8.1 — wholesale M3 surface swap from V1 (white/grey
    // Google palette) to Wispr Flow inspired editorial tokens. Every
    // M3 composable reading colorScheme.background / surface /
    // onBackground inherits the cream canvas + warm ink palette
    // automatically — HistoryScreen, all Scaffolds, Surface containers,
    // Card variants. Legacy V1 vs_background / vs_surface_variant /
    // vs_on_background tokens stay in colors.xml for downstream
    // migration safety but are unused.
    val background = colorResource(R.color.vs_canvas)
    val surfaceVariant = colorResource(R.color.vs_paper)
    val onBackground = colorResource(R.color.vs_ink)
    val onBackgroundMuted = colorResource(R.color.vs_ink_muted)
    val brandAccent = colorResource(R.color.vs_accent_sage_deep)
    val onBrandAccent = colorResource(R.color.vs_on_accent_sage_deep)
    // Code-review F13 (Group B) — every M3 slot that was previously
    // left at the baseline purple/red palette is now mapped into the
    // editorial token family so any M3 composable that reads
    // `secondary` / `tertiary` / `error` / `errorContainer` /
    // `inversePrimary` (Snackbar, FilterChip, NavigationBar indicator,
    // ModalBottomSheet scrim) paints on cream + sage + warm gold
    // instead of the M3-default lilac.
    val accentLavender = colorResource(R.color.vs_accent_lavender)
    val accentPulse = colorResource(R.color.vs_accent_pulse)
    val onAccentPulse = colorResource(R.color.vs_on_accent_pulse)
    val verdictFalse = colorResource(R.color.vs_verdict_false)

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = brandAccent,
            onPrimary = onBrandAccent,
            secondary = accentLavender,
            onSecondary = onBackground,
            tertiary = accentPulse,
            onTertiary = onAccentPulse,
            error = verdictFalse,
            onError = onBackground,
            background = background,
            surface = background,
            surfaceVariant = surfaceVariant,
            onBackground = onBackground,
            onSurface = onBackground,
            onSurfaceVariant = onBackgroundMuted,
            inversePrimary = brandAccent,
        )
    } else {
        lightColorScheme(
            primary = brandAccent,
            onPrimary = onBrandAccent,
            secondary = accentLavender,
            onSecondary = onBackground,
            tertiary = accentPulse,
            onTertiary = onAccentPulse,
            error = verdictFalse,
            onError = colorResource(R.color.vs_on_brand_google_blue),
            background = background,
            surface = background,
            surfaceVariant = surfaceVariant,
            onBackground = onBackground,
            onSurface = onBackground,
            onSurfaceVariant = onBackgroundMuted,
            inversePrimary = brandAccent,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VeriSphereTypography,
        content = content,
    )
}
