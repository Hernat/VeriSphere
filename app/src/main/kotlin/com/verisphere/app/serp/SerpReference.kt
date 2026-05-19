package com.verisphere.app.serp

import kotlinx.serialization.Serializable

/**
 * Epic 9 Story 9.1 — single search-result entry surfaced by SerpAPI.
 *
 * Sibling to [com.verisphere.app.gemini.SourceCitation] (Gemini's per-source
 * shape) but with a SerpAPI-specific snippet (Gemini doesn't return
 * snippets — only title + url + publisher + date). The two source types
 * are merged at render time in [com.verisphere.app.ui.detail.DetailPanelContent]
 * via URL canonicalisation.
 *
 * `@Serializable` so [com.verisphere.app.storage.SessionRecord] can
 * persist a `List<SerpReference>` to encrypted SharedPrefs.
 */
@Serializable
data class SerpReference(
    val title: String = "",
    val url: String = "",
    val publisher: String = "",
    val snippet: String = "",
)
