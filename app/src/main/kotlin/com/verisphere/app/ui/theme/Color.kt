package com.verisphere.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import com.verisphere.app.R

/**
 * VeriSphere palette tokens for surfaces that have no natural slot in
 * Material 3's standard ColorScheme — verdict colours, brand accent,
 * state/failure colours, and the privacy padlock tint.
 *
 * Light/dark variants are resolved automatically via the platform
 * configuration (values/colors.xml vs values-night/colors.xml).
 *
 * Pattern: sibling object with @Composable accessors. Alternative
 * (extension property on MaterialTheme.colorScheme) was rejected
 * because verdict/state colours are not part of the M3 contract.
 *
 * Usage:
 *
 *     val padlockTint = VSPalette.privacyPadlock
 *
 * NOT:
 *
 *     val padlockTint = Color(0xFF34A853)   // hex codes never inline
 */
object VSPalette {

    val brandGoogleBlue: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_brand_google_blue)

    val verdictTrue: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_verdict_true)

    val verdictFalse: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_verdict_false)

    val verdictDoubtful: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_verdict_doubtful)

    val verdictNonVerifiable: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_verdict_non_verifiable)

    val stateOffline: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_state_offline)

    val privacyPadlock: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_privacy_padlock)
}
