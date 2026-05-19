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
 *
 * Epic 8 Story 8.1 — accessors below the V1 block (canvas, paper, ink,
 * accent_sage, accent_lavender, accent_pulse, hairline, verdict softs)
 * expose the Wispr Flow inspired token family. Existing V1 accessors
 * are retained for surfaces not yet migrated; new surfaces (Stories
 * 8.2-8.5) consume the Epic 8 accessors.
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

    // ────────────────────────────────────────────────────────────────
    // Epic 8 Story 8.1 — Wispr Flow inspired tokens
    // ────────────────────────────────────────────────────────────────

    /** Soft cream canvas — primary background for Epic 8 surfaces. */
    val canvas: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_canvas)

    /** Slightly lighter card / sheet surface. */
    val paper: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_paper)

    /** Muted surface variant — dividers, inactive states. */
    val paperAlt: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_paper_alt)

    /** Primary text — deep warm grey. */
    val ink: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_ink)

    /** Secondary text. */
    val inkMuted: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_ink_muted)

    /** Tertiary text / labels / timestamps. */
    val inkSoft: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_ink_soft)

    /** Hairline divider tint. */
    val hairline: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_hairline)

    /** Sage accent — Epic 8 idle bubble + primary CTA (Story 8.2 swap). */
    val accentSage: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_accent_sage)

    /** Sage deep — pressed state / hover. */
    val accentSageDeep: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_accent_sage_deep)

    /** Lavender accent — chips, dividers, secondary highlights. */
    val accentLavender: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_accent_lavender)

    /** Warm gold pulse — timeout / UpdateBanner / warning + AgreementBadgeRow.Disagree (code-review F8). */
    val accentPulse: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_accent_pulse)

    /** On-colour for content placed on accent_pulse (code-review F8). */
    val onAccentPulse: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_on_accent_pulse)

    /**
     * On-colour for content placed on accent_sage backgrounds. DARK
     * text in light mode (sage #7BA889 is too light for white at AA).
     * White text on sage requires [accentSageDeep] instead.
     */
    val onAccentSage: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_on_accent_sage)

    /** On-colour for content placed on accent_sage_deep — white in light mode. */
    val onAccentSageDeep: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_on_accent_sage_deep)

    // ─── Verdict softs (Epic 8) ─────────────────────────────────────

    val verdictTrueSoft: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_verdict_true_soft)

    val verdictFalseSoft: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_verdict_false_soft)

    val verdictDoubtfulSoft: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_verdict_doubtful_soft)

    val verdictNonVerifiableSoft: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_verdict_non_verifiable_soft)

    val onVerdictTrueSoft: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_on_verdict_true_soft)

    val onVerdictFalseSoft: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_on_verdict_false_soft)

    val onVerdictDoubtfulSoft: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_on_verdict_doubtful_soft)

    val onVerdictNonVerifiableSoft: Color
        @Composable @ReadOnlyComposable
        get() = colorResource(R.color.vs_on_verdict_non_verifiable_soft)
}
