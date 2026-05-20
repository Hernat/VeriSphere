package com.verisphere.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Story 10.1 — JVM coverage for [validateGeminiKey] + [validateSerpKey].
 *
 * 8 cases per the story Tasks T2.3 :
 *   (a) empty, (b) blank (whitespace-only), (c) whitespace-padded valid
 *   (trim test), (d) valid 39-char AIza prefix, (e) valid 35-char floor,
 *   (f) 34-char too short, (g) wrong prefix, (h) numeric-only string.
 * Plus prefix-constant pin tests + SerpAPI coverage.
 */
class ApiKeyValidatorTest {

    // ─── (a-h) Gemini validation matrix ─────────────────────────────

    @Test
    fun `empty Gemini key returns Empty`() {
        assertEquals(GeminiKeyValidation.Empty, validateGeminiKey(""))
    }

    @Test
    fun `blank Gemini key returns Empty`() {
        assertEquals(GeminiKeyValidation.Empty, validateGeminiKey("   \t\n "))
    }

    @Test
    fun `whitespace-padded valid Gemini key trims and validates as Valid`() {
        // 39-char AIza-prefixed key, padded with whitespace on both sides.
        val core = "AIzaSy12345678901234567890123456789ABCD"
        assertEquals(39, core.length)
        val padded = "  $core  "
        assertEquals(GeminiKeyValidation.Valid, validateGeminiKey(padded))
    }

    @Test
    fun `valid 39-char AIza-prefixed Gemini key returns Valid`() {
        // Real-world Gemini API keys today are uniformly 39 chars.
        val key = "AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ1234567"
        assertEquals(39, key.length)
        assertEquals(GeminiKeyValidation.Valid, validateGeminiKey(key))
    }

    @Test
    fun `exactly GEMINI_MIN_LENGTH chars AIza-prefixed Gemini key returns Valid`() {
        // Pinned to the exact length (post-P9 — 39 exact, no longer a floor).
        val key = "AIza" + "x".repeat(GEMINI_MIN_LENGTH - 4)
        assertEquals(GEMINI_MIN_LENGTH, key.length)
        assertEquals(GeminiKeyValidation.Valid, validateGeminiKey(key))
    }

    @Test
    fun `38-char Gemini key one short of exact length returns InvalidFormat`() {
        // P9 — tightened from "≥ 35 floor" to "== 39 exact". A 38-char
        // key (1 char short of canonical) is rejected.
        val key = "AIza" + "x".repeat(GEMINI_MIN_LENGTH - 5)
        assertEquals(GEMINI_MIN_LENGTH - 1, key.length)
        assertEquals(GeminiKeyValidation.InvalidFormat, validateGeminiKey(key))
    }

    @Test
    fun `40-char Gemini key one over exact length returns InvalidFormat`() {
        // P9 — even paste-with-trailing-char must fail. (Real keys are
        // never 40 chars, but a stray paste-suffix like a quote or a
        // zero-width invisible AFTER normalization should still be caught.)
        val key = "AIza" + "x".repeat(GEMINI_MIN_LENGTH - 3)
        assertEquals(GEMINI_MIN_LENGTH + 1, key.length)
        assertEquals(GeminiKeyValidation.InvalidFormat, validateGeminiKey(key))
    }

    // ─── P6 Unicode invisibles ──────────────────────────────────────

    @Test
    fun `Gemini key with zero-width space U+200B is normalized and validates`() {
        // P6 — paste from a markdown code-block on iOS Safari can leak
        // a zero-width space ; trim() doesn't catch it, but
        // normalizeApiKey does.
        val withZwsp = "AIza" + "x".repeat(GEMINI_MIN_LENGTH - 4 - 1) + "​x"
        // 40 chars including the ZWSP ; should normalize back to 39.
        assertEquals(GEMINI_MIN_LENGTH + 1, withZwsp.length)
        assertEquals(GeminiKeyValidation.Valid, validateGeminiKey(withZwsp))
    }

    @Test
    fun `Gemini key with BOM U+FEFF is normalized and validates`() {
        // Same trap, different code point (byte-order mark / zero-width
        // no-break space).
        val withBom = "﻿AIza" + "x".repeat(GEMINI_MIN_LENGTH - 4)
        assertEquals(GeminiKeyValidation.Valid, validateGeminiKey(withBom))
    }

    @Test
    fun `Gemini key with LRM U+200E is normalized and validates`() {
        // Left-to-right mark — survives trim().
        val withLrm = "AIza" + "x".repeat(GEMINI_MIN_LENGTH - 4) + "‎"
        assertEquals(GeminiKeyValidation.Valid, validateGeminiKey(withLrm))
    }

    @Test
    fun `normalizeApiKey strips internal spaces too`() {
        // Defensive : if a user pastes a key with a literal space
        // mid-string (typo or accidental concatenation), strip it
        // rather than reject — likely the user's intent.
        val withSpaces = "AIza Sy ABC DEF GHI JKL MNO PQR STU VWX YZ123 4567"
        assertEquals(GeminiKeyValidation.Valid, validateGeminiKey(withSpaces))
    }

    @Test
    fun `wrong-prefix Gemini key returns InvalidFormat`() {
        // Long enough to pass the length gate but doesn't start with
        // "AIza" — typical paste-mistake.
        val key = "AIzy-prefix-typo-12345678901234567890123"
        assertEquals(GeminiKeyValidation.InvalidFormat, validateGeminiKey(key))
    }

    @Test
    fun `numeric-only Gemini key returns InvalidFormat`() {
        // No "AIza" prefix — even if long enough, fails format gate.
        val key = "1234567890123456789012345678901234567890"
        assertEquals(GeminiKeyValidation.InvalidFormat, validateGeminiKey(key))
    }

    // ─── Constant pin tests ─────────────────────────────────────────

    @Test
    fun `gemini prefix constant matches AIza`() {
        // Pin the current prefix — fails loud if rotated without test update.
        assertEquals("AIza", GEMINI_PREFIX)
    }

    @Test
    fun `gemini length constant is 39`() {
        // P9 — tightened from "35 minimum floor" to "39 exact" per
        // 2026-05-20 code-review D2 resolution. Real Gemini keys are
        // uniformly 39 chars ; the floor was over-permissive and
        // admitted 35-38 char strings that fail server-side silently.
        assertEquals(39, GEMINI_MIN_LENGTH)
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
