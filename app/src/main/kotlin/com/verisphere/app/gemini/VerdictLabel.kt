package com.verisphere.app.gemini

import kotlinx.serialization.Serializable

/**
 * The four verdict labels VeriSphere surfaces to the user (architecture
 * D1.2; PRD FR13). Story 1.9 introduces this enum because Gemini's
 * structured output (D3.4) returns the label as a string that
 * kotlinx.serialization deserialises directly into one of these cases.
 *
 * Each label carries a stable mapping to a UX flash variant — Story 1.10
 * wires the bubble's flash tooltip per the table below; Epic 3 wires the
 * failure flash variants alongside [VerificationOutcome.Failure.*].
 *
 * | Label             | Flash | UX rationale (PRD FR13)                            |
 * |-------------------|-------|----------------------------------------------------|
 * | [TRUE]            | ✅     | At least 2 independent corroborating sources (FR9). |
 * | [FALSE]           | ❌     | Claim contradicted by Search Grounding evidence.   |
 * | [DOUBTFUL]        | ⚠️     | Mixed evidence OR fewer than 2 corroborators.       |
 * | [NON_VERIFIABLE]  | ⚪     | No analyzable claim (FR27) OR no sources (FR26).    |
 *
 * The `SCREAMING_SNAKE_CASE` form follows architecture line 339 (enum
 * value naming) and matches Gemini's response envelope verbatim — no
 * `@SerialName` rename needed. Adding a fifth label is a deliberate
 * architecture amendment (FR13 fixes the V1 set at four).
 */
@Serializable
enum class VerdictLabel {
    /** ✅ True — claim corroborated by ≥ 2 independent sources (PRD FR9). */
    TRUE,

    /** ❌ False — claim contradicted by Search Grounding evidence (PRD FR13). */
    FALSE,

    /** ⚠️ Doubtful — mixed evidence or insufficient corroboration (PRD FR13). */
    DOUBTFUL,

    /**
     * ⚪ Non-verifiable — no analyzable claim in the image (PRD FR27) OR no
     * corroborating sources surfaced (PRD FR26). The verdict's `headline`
     * carries the explanation for the user.
     */
    NON_VERIFIABLE,
}
