package com.verisphere.app.util

/**
 * Story 10.1 — pure-helper validation for the 2 user-entered API keys
 * surfaced by the new `SettingsScreen`. Centralises the format-check
 * rules so the same validation runs on every user save without
 * duplicated logic between the ViewModel + the Composable error
 * supporting text.
 *
 * Validation rules :
 *
 *  - **Gemini** (`validateGeminiKey`) — `Valid` for any non-blank
 *    normalized result, `Empty` otherwise. No format gate at all : the
 *    earlier "must start with `AIza` AND be exactly 39 chars" gate (and
 *    its 2026-05-30 successor, a length floor) rejected valid keys —
 *    Google does not issue the `AIza` prefix for every Gemini key, and
 *    any length assumption is just as brittle. A wrong key now surfaces
 *    server-side rather than being blocked locally.
 *  - **SerpAPI** (`validateSerpKey`) — identical contract : `Valid` for
 *    any non-blank normalized result, `Empty` otherwise. SerpAPI
 *    publishes no key format either.
 */
sealed class GeminiKeyValidation {
    /** The key is non-blank ; safe to persist + use. */
    data object Valid : GeminiKeyValidation()

    /** The key is missing or blank ; Settings save with empty drafts
     *  is treated as "no Gemini configured yet". */
    data object Empty : GeminiKeyValidation()
}

sealed class SerpKeyValidation {
    /** Non-blank key ; persist verbatim. */
    data object Valid : SerpKeyValidation()

    /** Blank ; treated as "user opted out of SerpAPI". */
    data object Empty : SerpKeyValidation()
}

// Unicode-invisible code points caught in addition to `Char.isWhitespace`
// during [normalizeApiKey]. Built via integer code points (not literal
// characters) so the source file stays free of literal BOM / ZWSP
// bytes that the `ByteOrderMark` lint rule flags + Git's auto-CRLF
// can mangle.
private val ZWSP: Char = Char(0x200B)    // zero-width space
private val RLM: Char = Char(0x200F)     // right-to-left mark (range end of ZWSP..RLM)
private val BOM: Char = Char(0xFEFF)     // byte-order-mark / zero-width no-break space

/**
 * Story 10.1 code-review P6 — strip Unicode invisibles that
 * Kotlin `String.trim()` does NOT catch : zero-width space
 * (`U+200B`), zero-width no-break space / BOM (`U+FEFF`), left-to-right
 * mark (`U+200E`), right-to-left mark (`U+200F`). These survive
 * `trim()` because `Char.isWhitespace()` does not classify them as
 * whitespace, but they leak into pasted keys from rich-text sources
 * (iOS Safari paste from a markdown code-block, Slack copy, certain
 * email clients). A leaked invisible would be persisted verbatim and
 * fail server-side with HTTP 400 → silent Idle. Stripping here keeps
 * the stored key byte-identical to what the user copied.
 */
internal fun normalizeApiKey(raw: String): String =
    raw.filterNot { ch ->
        // ASCII whitespace + Unicode whitespace caught by isWhitespace
        // (incl. U+00A0 non-breaking space).
        ch.isWhitespace() ||
            // Zero-width formatting marks U+200B through U+200F :
            // ZWSP, ZWNJ, ZWJ, LRM, RLM. None of these are isWhitespace.
            ch in ZWSP..RLM ||
            // U+FEFF (BOM / zero-width no-break space) — also caught
            // separately by the `ByteOrderMark` lint rule when it
            // appears as a literal in source, which is why we defer
            // to a named constant here.
            ch == BOM
    }

/**
 * Returns the validation outcome for [raw]. Normalizes the input via
 * [normalizeApiKey] (strips ALL whitespace + Unicode invisibles) ;
 * callers should pass the normalized form to `writeUserGeminiApiKey` so
 * the stored value matches what was validated. No format gate — any
 * non-blank result is `Valid` (prefix / length assumptions rejected
 * valid keys, see the class KDoc).
 */
fun validateGeminiKey(raw: String): GeminiKeyValidation {
    val cleaned = normalizeApiKey(raw)
    return if (cleaned.isEmpty()) GeminiKeyValidation.Empty else GeminiKeyValidation.Valid
}

/**
 * Returns the validation outcome for [raw] under the SerpAPI gate.
 * Same normalization as Gemini ; any non-blank result passes (no
 * format check — SerpAPI publishes no key format).
 */
fun validateSerpKey(raw: String): SerpKeyValidation {
    val cleaned = normalizeApiKey(raw)
    return if (cleaned.isEmpty()) SerpKeyValidation.Empty else SerpKeyValidation.Valid
}
