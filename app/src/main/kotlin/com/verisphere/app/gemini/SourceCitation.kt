package com.verisphere.app.gemini

import kotlinx.serialization.Serializable

/**
 * One source link returned by Gemini's Search Grounding (architecture
 * D1.2 + UX detail-panel spec). Story 2.1's `SourceLinkChip` composable
 * renders a [List]<[SourceCitation]> in the detail panel; Story 2.3 wires
 * the tap handler that opens [url] in the system browser.
 *
 * **Field semantics** (Gemini surfaces these via the structured-output
 * schema in [GeminiRequest.buildResponseSchema]):
 *
 *  - [title] is the source page's headline / article title — what the
 *    user reads on the chip.
 *  - [url] is the canonical URL Gemini's Search Grounding resolved
 *    against. Story 2.3 opens this with `Intent.ACTION_VIEW`.
 *  - [publisher] is the outlet name ("BBC News", "Reuters", "Le Monde")
 *    — separates editorial source from headline. Used by the detail
 *    panel for source-credibility cues.
 *  - [dateYearMonth] is `YYYY-MM` per architecture line 453 (UX spec
 *    format — sub-month precision is more noise than signal for fact
 *    checks). Nullable because not every Gemini-grounded source carries
 *    a publication date — e.g. evergreen Wikipedia pages, ABOUT pages,
 *    archived snapshots without preserved metadata. Each rendering
 *    surface decides how to handle the null case: the
 *    [`ui.detail.SourceLinkChip`](../ui/detail/SourceLinkChip.kt) drops
 *    the date suffix entirely (Story 2.1 AC #3); the Story 2.3 detail
 *    panel may surface a fuller "(date unknown)" affordance there.
 *
 * **JSON wire format** is Kotlin-property-aligned camelCase — no
 * `@SerialName` annotations needed (architecture line 443 + 447).
 */
@Serializable
data class SourceCitation(
    val title: String,
    val url: String,
    val publisher: String,
    val dateYearMonth: String? = null,
    /**
     * Code-review F11 (Group B) — optional per-source snippet. Gemini
     * Search Grounding does NOT emit snippets ; SerpAPI does (1-2
     * sentence outlet summary). Field is nullable so Gemini-sourced
     * citations stay shape-compatible while [fuseSources] preserves the
     * SerpAPI snippet at the UI boundary. Renderer ([SourceLinkChip])
     * is free to show or hide based on chip-row vs detail-list mode.
     */
    val snippet: String? = null,
)
