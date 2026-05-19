package com.verisphere.app.serp

import kotlinx.serialization.Serializable

/**
 * Epic 9 Story 9.1 — cross-source agreement between Gemini's [VerdictLabel]
 * and SerpAPI's reconstructed narrative.
 *
 * Computed by [AgreementScorer]; persisted on [com.verisphere.app.storage.SessionRecord]
 * so the detail panel can render the "sources contradictoires" badge.
 *
 *  - [Agree]        : SerpAPI markdown clearly supports the Gemini verdict.
 *  - [Disagree]     : SerpAPI markdown contradicts the Gemini verdict.
 *  - [Inconclusive] : signal too weak in either direction to call it.
 *                     Default for empty markdown / failed SerpAPI calls.
 */
@Serializable
enum class AgreementVerdict {
    Agree,
    Disagree,
    Inconclusive,
}
