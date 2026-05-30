package com.verisphere.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM coverage for [validateGeminiKey] + [validateSerpKey].
 *
 * Both gates (2026-05-30 — relaxed) accept any non-blank normalized
 * result and return `Empty` otherwise : no prefix check, no length
 * check. The `AIza` prefix is not guaranteed and any length assumption
 * rejected valid keys, so format validation was dropped entirely. The
 * Unicode-invisible cases here guard [normalizeApiKey] directly, since
 * validation alone no longer distinguishes a stripped key from one that
 * still carries an invisible.
 */
class ApiKeyValidatorTest {

    // ─── Gemini validation : non-blank only ─────────────────────────

    @Test
    fun `empty Gemini key returns Empty`() {
        assertEquals(GeminiKeyValidation.Empty, validateGeminiKey(""))
    }

    @Test
    fun `blank Gemini key returns Empty`() {
        assertEquals(GeminiKeyValidation.Empty, validateGeminiKey("   \t\n "))
    }

    @Test
    fun `whitespace-padded Gemini key trims and validates as Valid`() {
        val core = "AIzaSy12345678901234567890123456789ABCD"
        assertEquals(GeminiKeyValidation.Valid, validateGeminiKey("  $core  "))
    }

    @Test
    fun `canonical 39-char Gemini key returns Valid`() {
        assertEquals(
            GeminiKeyValidation.Valid,
            validateGeminiKey("AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ1234567"),
        )
    }

    @Test
    fun `non-AIza-prefix Gemini key returns Valid (no prefix gate)`() {
        // The `AIza` prefix is not guaranteed on every Gemini key, so a
        // key with a different prefix must be accepted.
        assertEquals(GeminiKeyValidation.Valid, validateGeminiKey("gsk-some-other-prefix-1234567890"))
    }

    @Test
    fun `numeric-only Gemini key returns Valid (no format gate)`() {
        assertEquals(
            GeminiKeyValidation.Valid,
            validateGeminiKey("1234567890123456789012345678901234567890"),
        )
    }

    @Test
    fun `short Gemini key still Valid (no length gate)`() {
        // 2026-05-30 — length validation dropped ; any non-blank key
        // passes (mirrors the SerpAPI contract). A wrong key surfaces
        // server-side, not via a local format block.
        assertEquals(GeminiKeyValidation.Valid, validateGeminiKey("short"))
    }

    // ─── normalizeApiKey : whitespace + Unicode-invisible stripping ──

    @Test
    fun `normalizeApiKey strips a zero-width space U+200B`() {
        // P6 — paste from a markdown code-block can leak a zero-width
        // space ; trim() doesn't catch it, normalizeApiKey does. Built
        // from a code point so the source carries no literal invisible.
        val zwsp = Char(0x200B)
        assertEquals("AIzaKEY", normalizeApiKey("AIza${zwsp}KEY"))
    }

    @Test
    fun `normalizeApiKey strips a BOM U+FEFF`() {
        val bom = Char(0xFEFF)
        assertEquals("AIzaKEY", normalizeApiKey("${bom}AIzaKEY"))
    }

    @Test
    fun `normalizeApiKey strips a left-to-right mark U+200E`() {
        val lrm = Char(0x200E)
        assertEquals("AIzaKEY", normalizeApiKey("AIzaKEY${lrm}"))
    }

    @Test
    fun `normalizeApiKey strips internal and surrounding whitespace`() {
        // Defensive : a key pasted with stray spaces (typo / line wrap)
        // is stripped rather than rejected — likely the user's intent.
        assertEquals("AIzaSyABCDEF", normalizeApiKey("  AIza Sy ABC DEF  "))
    }

    // ─── SerpAPI validation ─────────────────────────────────────────

    @Test
    fun `empty SerpAPI key returns Empty`() {
        assertEquals(SerpKeyValidation.Empty, validateSerpKey(""))
    }

    @Test
    fun `blank SerpAPI key returns Empty`() {
        assertEquals(SerpKeyValidation.Empty, validateSerpKey("   \t  "))
    }

    @Test
    fun `non-blank SerpAPI key returns Valid`() {
        assertEquals(SerpKeyValidation.Valid, validateSerpKey("serp-token-anything-non-blank"))
    }

    @Test
    fun `short SerpAPI key still Valid (no format gate)`() {
        // SerpAPI publishes no key format — even "a" is technically Valid.
        assertEquals(SerpKeyValidation.Valid, validateSerpKey("a"))
    }
}
