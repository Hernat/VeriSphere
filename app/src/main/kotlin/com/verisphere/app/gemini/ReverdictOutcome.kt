package com.verisphere.app.gemini

/**
 * Outcome of [GeminiClient.reverdict] — the second Gemini call that
 * re-evaluates the verdict using the SerpAPI Google synthesis as the
 * authoritative ground-truth.
 *
 * Single error funnel: [reverdict][GeminiClient.reverdict] never throws.
 * Every failure (network, parse, timeout, missing API key, blank inputs)
 * collapses to [Failure]; the pipeline then falls back to the Gemini #1
 * verdict gracefully.
 *
 * Only the 4 fields that the reverdict can legitimately re-issue are
 * surfaced — the OCR text, extracted claim, Gemini Search Grounding
 * citations, and injection self-report all come from the first pass and
 * are preserved by the pipeline merge step.
 */
sealed class ReverdictOutcome {

    data class Success(
        val verdictLabel: VerdictLabel,
        val headline: String,
        val contextLines: List<String>,
        val regionalBiasNote: String?,
    ) : ReverdictOutcome()

    data object Failure : ReverdictOutcome()
}
