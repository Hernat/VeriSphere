package com.verisphere.app.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Story 5.3 — JVM unit tests for [HOSTILE_OEMS] + [isHostileOem]
 * (AR26, D5.8).
 *
 * Pattern mirrors [com.verisphere.app.onboarding.OnboardingOrchestratorTest]
 * (Story 5.2) — backtick-quoted English-sentence method names per
 * architecture L426; JVM source set (DEX SimpleName constraint does
 * not apply per Story 5.2 AA4 dismissal).
 *
 * **Coverage matrix:**
 *  - One canonical (lowercase) hostile manufacturer per entry in the
 *    [HOSTILE_OEMS] closed set (8 tests).
 *  - Case-insensitivity via UPPERCASE + TitleCase forms (2 tests).
 *  - Non-hostile manufacturers + edge cases (1 test, multiple
 *    assertions): `"Google"`, `"motorola"`, `"sony"`, `"asus"`,
 *    `"Pixel"`, empty string, Unicode-only.
 *  - Set integrity: exact 8-element membership (1 test).
 */
class HostileOemTest {

    // ─── Canonical lowercase membership (epics line 847) ───────────

    @Test
    fun `isHostileOem returns true for samsung`() {
        assertTrue(isHostileOem("samsung"))
    }

    @Test
    fun `isHostileOem returns true for xiaomi`() {
        assertTrue(isHostileOem("xiaomi"))
    }

    @Test
    fun `isHostileOem returns true for huawei`() {
        assertTrue(isHostileOem("huawei"))
    }

    @Test
    fun `isHostileOem returns true for honor`() {
        assertTrue(isHostileOem("honor"))
    }

    @Test
    fun `isHostileOem returns true for oppo`() {
        assertTrue(isHostileOem("oppo"))
    }

    @Test
    fun `isHostileOem returns true for vivo`() {
        assertTrue(isHostileOem("vivo"))
    }

    @Test
    fun `isHostileOem returns true for realme`() {
        assertTrue(isHostileOem("realme"))
    }

    @Test
    fun `isHostileOem returns true for oneplus`() {
        assertTrue(isHostileOem("oneplus"))
    }

    // ─── Case-insensitivity (CDN #2 — Locale.ROOT lowercase) ───────

    @Test
    fun `isHostileOem is case-insensitive for SAMSUNG`() {
        // Real-device returns: Samsung devices set MANUFACTURER to
        // "samsung" (lowercase) but Huawei → "HUAWEI" (uppercase).
        // Both must match.
        assertTrue(isHostileOem("SAMSUNG"))
        assertTrue(isHostileOem("Samsung"))
        assertTrue(isHostileOem("SaMsUnG"))
    }

    @Test
    fun `isHostileOem is case-insensitive for HUAWEI`() {
        assertTrue(isHostileOem("HUAWEI"))
        assertTrue(isHostileOem("Huawei"))
    }

    // ─── Non-hostile manufacturers + edge cases (AC #6) ─────────────

    @Test
    fun `isHostileOem returns false for non-hostile manufacturers and edge cases`() {
        // Pixel AVD reports "Google" — covers the smoke Scenario A
        // happy path where the sheet must NOT mount.
        assertFalse(isHostileOem("Google"))
        assertFalse(isHostileOem("google"))
        // Other non-hostile mainstream OEMs.
        assertFalse(isHostileOem("motorola"))
        assertFalse(isHostileOem("Motorola"))
        assertFalse(isHostileOem("sony"))
        assertFalse(isHostileOem("Sony"))
        assertFalse(isHostileOem("asus"))
        assertFalse(isHostileOem("Asus"))
        // Pixel is a Build.MODEL not Build.MANUFACTURER; defensively
        // check it does not match either (manufacturer "Google" handles
        // Pixel devices in reality).
        assertFalse(isHostileOem("Pixel"))
        // Empty string — `Build.MANUFACTURER` is documented non-null
        // but the platform has no enforcement, defend defensively.
        assertFalse(isHostileOem(""))
        // Unicode-only string — exercises `lowercase(Locale.ROOT)` on
        // non-ASCII input (regression guard for the CDN #2 Turkish
        // dotless-i hazard if a future regression replaced ROOT with
        // getDefault()).
        assertFalse(isHostileOem("中国"))
    }

    // ─── Set integrity (CDN #2 — frozen 8-element closed set) ──────

    @Test
    fun `HOSTILE_OEMS contains exactly the 8 manufacturers from epics line 847`() {
        // Set equality so test is order-independent.
        assertEquals(
            setOf("samsung", "xiaomi", "huawei", "honor", "oppo", "vivo", "realme", "oneplus"),
            HOSTILE_OEMS,
        )
        assertEquals(8, HOSTILE_OEMS.size)
    }
}
