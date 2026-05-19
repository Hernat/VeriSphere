package com.verisphere.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// ─── Epic 8 Story 8.1 — System font families (Wispr-close fallback) ───
//
// Initial plan called for Downloadable Fonts (Figtree + EB Garamond via
// GMS provider). Verified on emulator-5554: GMS is present but the
// download path does not surface anything visible in logcat (silent
// fallback to Roboto). Rather than ship the look behind a network
// download, Story 8.1 uses Android system fonts:
//   - FontFamily.Serif     → Noto Serif (Android system, bundled since
//                            API 21). Close to EB Garamond visually for
//                            editorial titles / verdict word.
//   - FontFamily.SansSerif → Roboto (Android system). Close enough to
//                            Figtree for body / labels.
// Zero APK weight, zero network, instant rendering on first launch.
//
// Code-review F5 (Group B) — these vals MUST stay above
// [VeriSphereTypography] so the eager `val = Typography(...)` can read
// them at file load. The previous `val ... get() = Typography(...)`
// form deferred resolution to first access, which masked the ordering
// requirement.

private val ebGaramondFamily = FontFamily.Serif
private val figtreeFamily = FontFamily.SansSerif

/**
 * Material 3 type scale with explicit overrides per UX-DR2 / NFR11.
 * Epic 8 Story 8.1 — Wispr Flow font swap : Figtree (body/title/label,
 * via [FontFamily.SansSerif] system Roboto fallback) + EB Garamond
 * (headline, via [FontFamily.Serif] system Noto Serif fallback).
 * Zero APK weight, zero network ; instant on first launch.
 *
 * NFR11 floors:
 *   - verdict word ≥ 16 sp → headlineMedium = 18 sp
 *   - body context  ≥ 14 sp → bodyMedium    = 15 sp (Epic 8 bumped from
 *     14 sp for editorial breathing room — still over the floor)
 *
 * Code-review F5 (Group B) — `val ... = Typography(...)` (eagerly
 * initialised single instance), NOT `val ... get() = Typography(...)`
 * which would reallocate the Typography + 4 inner TextStyles on every
 * recomposition of [VeriSphereTheme].
 *
 * Code-review F16 (Group B) — KDoc + the 4 explicit overrides below
 * mirror the actual sp values that ship ; floors line up with NFR11.
 */
val VeriSphereTypography: Typography = Typography(
        // Epic 8 Story 8.1 — Wispr Flow font swap. M3 type slots used by
        // every Compose surface now route to Figtree (body/title/label)
        // + EB Garamond (headline). Onboarding screens, FlashTooltip,
        // DetailPanel, UpdateBanner, HistoryItemRow, BubbleOverlay —
        // every Text() reading MaterialTheme.typography.* inherits the
        // editorial typography immediately.
        headlineMedium = TextStyle(
            fontFamily = ebGaramondFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            letterSpacing = (-0.005).em,
        ),
        titleMedium = TextStyle(
            fontFamily = ebGaramondFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 18.sp,
            lineHeight = 24.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = figtreeFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = figtreeFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.04.em,
        ),
    )


/**
 * Epic 8 Story 8.1 — Wispr Flow editorial type scale.
 *
 * Sibling to [VeriSphereTypography]. Consumed by Epic 8 surfaces (bubble
 * tooltip, detail panel, history list, onboarding hero titles, history
 * screen title). Existing V1 surfaces continue to use the M3
 * [VeriSphereTypography] until each is migrated.
 *
 * **Fonts** (code-review F12 (Group B) — KDoc rewritten to match what
 * actually ships) : [FontFamily.Serif] (Android system Noto Serif,
 * editorial-close stand-in for EB Garamond) + [FontFamily.SansSerif]
 * (Android system Roboto, Figtree-close stand-in). Both bundled since
 * API 21 ; zero APK weight, zero network, instant first render. The
 * Google Fonts Downloadable Fonts path was prototyped + abandoned per
 * the comment block above ; `font_certs.xml` has been deleted.
 */
object VSTypography {

    /** Display serif — section heroes (onboarding card 1). */
    val displaySerif: TextStyle = TextStyle(
        fontFamily = ebGaramondFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.005).em,
    )

    /** Headline serif — history screen title, onboarding hero. */
    val headlineSerif: TextStyle = TextStyle(
        fontFamily = ebGaramondFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.005).em,
    )

    /** Title serif — detail panel verdict headline. */
    val titleSerif: TextStyle = TextStyle(
        fontFamily = ebGaramondFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    )

    // Code-review F22 (Group B) — `verdictWordSerif` deleted. KDoc
    // described it as the verdict-word style for FlashTooltip + verdict
    // chip, but every actual call site reads `MaterialTheme.typography
    // .headlineMedium` (18 sp) or `titleMedium` (18 sp) instead.
    // Re-introduce only when an actual consumer needs the 14 sp variant.

    /** Body large — onboarding body paragraphs. */
    val bodyLargeSans: TextStyle = TextStyle(
        fontFamily = figtreeFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 28.sp,
    )

    /** Body — flash tooltip headline, detail panel body, history rows. */
    val bodySans: TextStyle = TextStyle(
        fontFamily = figtreeFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 24.sp,
    )

    /** Headline body — Figtree medium for list headlines. */
    val headlineBodySans: TextStyle = TextStyle(
        fontFamily = figtreeFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )

    /**
     * Tracked label — section headers, timestamps, subtitles.
     * 0.14 em tracking gives the "editorial label" feel matching the
     * HTML preview ("01 — DESIGN TOKENS").
     *
     * Code-review F23 (Group B) — KDoc previously claimed "uppercase
     * rendering" but no `textTransform` is applied here ; callers
     * uppercase the string at the call site if desired. Strings like
     * "Synthèse Google" render mixed-case as authored.
     */
    val labelTrackedSans: TextStyle = TextStyle(
        fontFamily = figtreeFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.14.em,
    )
}

