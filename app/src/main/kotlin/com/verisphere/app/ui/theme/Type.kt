package com.verisphere.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material 3 type scale with explicit overrides per UX-DR2 / NFR11.
 * Roboto only (no bundled font asset — preserves the < 10 MB APK budget,
 * NFR2). All sizes in sp; all layout in dp; no px anywhere in the codebase.
 *
 * NFR11 floors:
 *   - verdict word ≥ 16 sp → headlineMedium = 18 sp
 *   - body context  ≥ 14 sp → bodyMedium    = 14 sp
 *
 * The other M3 type tokens stay at stock values; we do not need them
 * in V1 and overriding them risks drift between surfaces.
 */
val VeriSphereTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)
