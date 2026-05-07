package com.verisphere.app.gemini

import kotlinx.serialization.Serializable

/**
 * Gemini's structured-output response shape (architecture D3.4).
 *
 * Story 1.9 ships this as the wire-format envelope for the model's
 * fact-check verdict. The schema is described to Gemini via
 * [GeminiRequest.buildResponseSchema] (`generationConfig.responseJsonSchema`)
 * and returned by Gemini as JSON inside `candidates[0].content.parts[0].text`
 * (note: the model's output JSON is wrapped in the candidates envelope —
 * see [GeminiClient] for the two-stage parse).
 *
 * Closes PRD FR5 (Gemini Vision + Search Grounding submission), FR7
 * ([ocrText] surfacing), FR8 (anti-injection self-report via
 * [injectionDetected]), FR9 (≥ 2 corroborating sources for `TRUE` —
 * enforced by the system prompt, not the schema), FR13 ([verdictLabel]
 * = one of 4), FR26 / FR27 (`NON_VERIFIABLE` cases).
 *
 * **Field-by-field**:
 *  - [verdictLabel] — one of 4 V1 labels (PRD FR13). The Gemini-side
 *    JSON Schema constrains the value to the 4-element enum; an unknown
 *    value triggers a kotlinx.serialization parse failure → `MalformedResponse`.
 *  - [headline] — single-sentence verdict summary (≤ 140 chars per
 *    system prompt v1). Surfaced in the bubble flash tooltip + detail
 *    panel header.
 *  - [contextLines] — 0–3 short bullets (each ≤ 100 chars) for the
 *    detail panel. May be empty.
 *  - [sources] — Search Grounding citations. Renamed to [`SessionRecord.sourceLinks`]
 *    in the domain mapping (architecture D1.2 naming).
 *  - [ocrText] — verbatim text extracted from the image (FR7 + FR8 —
 *    surfacing this to the user is the anti-injection self-revealing
 *    posture per PRD User Journey 4b).
 *  - [regionalBiasNote] — optional brief note when the topic has known
 *    regional reporting variance. Null when not applicable.
 *  - [injectionDetected] — model's self-report flag. Defaults to `false`
 *    for forward-compat with Story 7.1's corpus runner. The value
 *    propagates to [`SessionRecord`] only via Story 1.10's downstream
 *    refactor (V1 surfaces injection only via [ocrText] inspection per
 *    PRD User Journey 4b).
 */
@Serializable
internal data class GeminiVerdictResponse(
    val verdictLabel: VerdictLabel = VerdictLabel.NON_VERIFIABLE,
    val headline: String = "",
    val contextLines: List<String> = emptyList(),
    val sources: List<SourceCitation> = emptyList(),
    val ocrText: String = "",
    val regionalBiasNote: String? = null,
    val injectionDetected: Boolean = false,
)
// NOTE: defaults added by code-review patch P6. Gemini's structured
// output is best-effort — if the model omits a key, the parser falls
// back to the default rather than crashing with SerializationException.
// Combined with `Json { coerceInputValues = true; ignoreUnknownKeys = true }`
// in [GeminiClient], this guards every wire-format omission. The defaults
// are deliberately unhelpful (`NON_VERIFIABLE`, empty strings/lists) so
// a Gemini schema breach is observable in the user's flash UX.
