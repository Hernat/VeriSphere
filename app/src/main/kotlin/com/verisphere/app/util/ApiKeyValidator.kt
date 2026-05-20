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
 *  - **Gemini** (`validateGeminiKey`) — `normalizeApiKey(raw)` non-empty
 *    AND starts with [`GEMINI_PREFIX`] (`"AIza"`) AND length =
 *    [`GEMINI_MIN_LENGTH`] (39 chars per code-review D2 resolution
 *    2026-05-20). Real Gemini API keys today are uniformly 39 chars ;
 *    the pre-review "≥ 35 floor" admitted strings the server rejects
 *    silently.
 *  - **SerpAPI** (`validateSerpKey`) — `Valid` for any non-blank
 *    normalized result, `Empty` otherwise. SerpAPI publishes no key
 *    format ; we accept anything non-blank rather than introduce a
 *    brittle prefix gate.
 *
 * If Google ever rotates the Gemini key prefix or length, update
 * [`GEMINI_PREFIX`] / [`GEMINI_MIN_LENGTH`] here — these are the
 * single touch-points and the pinned-constant tests guard them.
 */
sealed class GeminiKeyValidation {
    /** The key passes the format gate ; safe to persist + use. */
    data object Valid : GeminiKeyValidation()

    /** The key is missing or blank ; Settings save with empty drafts
     *  is treated as "no Gemini configured yet". */
    data object Empty : GeminiKeyValidation()

    /** The key was non-empty but failed the format gate (wrong prefix
     *  or wrong length) ; show inline error supporting text. */
    data object InvalidFormat : GeminiKeyValidation()
}

sealed class SerpKeyValidation {
    /** Non-blank key ; persist verbatim. */
    data object Valid : SerpKeyValidation()

    /** Blank ; treated as "user opted out of SerpAPI". */
    data object Empty : SerpKeyValidation()
}

/** Story 10.1 — Gemini key prefix (single touch-point if Google rotates). */
const val GEMINI_PREFIX: String = "AIza"

/**
 * Story 10.1 — Gemini key length. Real keys today are uniformly 39
 * characters ; the pre-2026-05-20-review draft used 35 as a "minimum
 * floor" but that admitted 35-38 char strings that pass the validator
 * + fail server-side with HTTP 400 → silent `Idle` (UX-DR15 silent
 * bucket). Tightened to exact 39 per code-review D2 resolution. If
 * Google ever publishes a different length, update this constant.
 */
const val GEMINI_MIN_LENGTH: Int = 39

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
 * email clients). A leaked invisible would make the key 40 chars
 * long, pass our `length == 39` check after stripping (we strip
 * before measuring), and otherwise fail server-side with HTTP 400 →
 * silent Idle. Normalising here closes the trap.
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
 * Returns the validation outcome for [raw] under the Gemini-format gate.
 * Normalizes the input via [normalizeApiKey] (strips ALL whitespace +
 * Unicode invisibles) ; callers should pass the normalized form to
 * `writeUserGeminiApiKey` so the stored value matches what was
 * validated.
 */
fun validateGeminiKey(raw: String): GeminiKeyValidation {
    val cleaned = normalizeApiKey(raw)
    if (cleaned.isEmpty()) return GeminiKeyValidation.Empty
    if (!cleaned.startsWith(GEMINI_PREFIX)) return GeminiKeyValidation.InvalidFormat
    if (cleaned.length != GEMINI_MIN_LENGTH) return GeminiKeyValidation.InvalidFormat
    return GeminiKeyValidation.Valid
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
