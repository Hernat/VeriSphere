package com.verisphere.app.gemini

import android.util.Log
import com.verisphere.app.util.tag

/**
 * Epic 8 — utility for stripping the verdict prefix Gemini's system
 * prompt v1 asks the model to write at headline start ("C'EST VRAI :",
 * "C'EST FAUX :", "DOUTEUX :", "NON VÉRIFIABLE :").
 *
 * **Why a utility, not inlined**: applied in two places — at PARSE
 * time inside [GeminiClient.toSessionRecord] (so new records persist
 * without the prefix) AND at RENDER time inside `HistoryItemRow`,
 * `FlashTooltip`, `DetailPanelContent` (defence-in-depth for legacy
 * records persisted before the parse-side fix shipped, and for any
 * future Gemini drift).
 *
 * Ordered so the longest match wins first — "NON VÉRIFIABLE" before
 * any shorter "NON" prefix. Unaccented variants ("NON VERIFIABLE")
 * kept as defence against Gemini accent drops.
 */
private val VERDICT_PREFIXES: List<Pair<String, VerdictLabel>> = listOf(
    "NON VÉRIFIABLE" to VerdictLabel.NON_VERIFIABLE,
    "NON VERIFIABLE" to VerdictLabel.NON_VERIFIABLE,
    "C'EST VRAI" to VerdictLabel.TRUE,
    // U+2019 RIGHT SINGLE QUOTATION MARK variant — Gemini drift towards
    // typographic French apostrophe is a real risk (code-review F13).
    "C’EST VRAI" to VerdictLabel.TRUE,
    "C'EST FAUX" to VerdictLabel.FALSE,
    "C’EST FAUX" to VerdictLabel.FALSE,
    "DOUTEUX" to VerdictLabel.DOUBTFUL,
)

private val TAG = tag("VerdictHeadline")

/**
 * Removes any of the 4 verdict-prefix variants from the start of
 * [rawHeadline] plus the `:` / `.` / whitespace separator that
 * follows. When the detected prefix disagrees with [label], logs a
 * warning (Gemini self-contradiction telemetry).
 *
 * Headlines without a prefix are returned trimmed-but-otherwise
 * verbatim. Safe to call at every render — idempotent.
 */
fun stripVerdictPrefix(rawHeadline: String, label: VerdictLabel): String {
    val trimmed = rawHeadline.trim()
    val matched = VERDICT_PREFIXES.firstOrNull { (prefixToken, _) ->
        trimmed.startsWith(prefixToken, ignoreCase = true)
    } ?: return trimmed

    val (prefixToken, prefixLabel) = matched
    if (prefixLabel != label) {
        Log.w(
            TAG,
            "stripVerdictPrefix: headline prefix '$prefixToken' (→ $prefixLabel) " +
                "disagrees with verdictLabel=$label — strip + trust label",
        )
    }

    return trimmed
        .substring(prefixToken.length)
        .trimStart(':', '.', ' ', '\t')
        .trim()
}
