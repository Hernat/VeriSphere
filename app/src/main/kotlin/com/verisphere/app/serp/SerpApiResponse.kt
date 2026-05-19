package com.verisphere.app.serp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Epic 9 Story 9.1 — partial wire-format of SerpAPI's `/search` endpoint.
 *
 * Only the fields used by [SerpApiClient] are modelled. The [Json] config
 * passes `ignoreUnknownKeys = true` so SerpAPI can add new top-level
 * fields without breaking deserialization.
 *
 * Two shapes are supported in the same envelope: `engine=google_ai_mode`
 * returns `references` + `reconstructed_markdown` (plus `quick_results` /
 * `text_blocks` / `shopping_results` which V1 absorbs via
 * `ignoreUnknownKeys = true` but does NOT model — adding them would be
 * dead surface area until a consumer surfaces them); `engine=google`
 * returns `organic_results` + `knowledge_graph`. The client tries AI
 * mode first and falls back to classic search when the AI-mode payload
 * is empty.
 *
 * `error` is present when SerpAPI rejects the request (quota, bad params,
 * missing engine support for the locale, etc.). The client maps non-null
 * `error` strings containing "limit", "quota", "credits" to [SerpOutcome.Failure.Quota];
 * otherwise to [SerpOutcome.Failure.MalformedResponse].
 */
@Serializable
internal data class SerpApiResponse(
    // ─── google_ai_mode shape ───────────────────────────────────────
    @SerialName("reconstructed_markdown")
    val reconstructedMarkdown: String? = null,
    val references: List<AiModeReference> = emptyList(),

    // ─── google shape ───────────────────────────────────────────────
    @SerialName("organic_results")
    val organicResults: List<OrganicResult> = emptyList(),

    // ─── Common error envelope ──────────────────────────────────────
    val error: String? = null,
) {

    @Serializable
    data class AiModeReference(
        val title: String = "",
        val link: String = "",
        val snippet: String = "",
        val source: String = "",
        val index: Int = -1,
    )

    @Serializable
    data class OrganicResult(
        val title: String = "",
        val link: String = "",
        val snippet: String = "",
        val source: String = "",
        val position: Int = -1,
        val date: String? = null,
        @SerialName("displayed_link")
        val displayedLink: String = "",
    )
}
