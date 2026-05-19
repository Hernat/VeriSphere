package com.verisphere.app.ui.detail

import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.serp.SerpReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Epic 9 Story 9.2 + hotfix 2026-05-19 — unit tests for the source
 * fusion + filter helper backing the combined Sources section.
 *
 * Covers :
 *   (a) SerpAPI precedence — appears before Gemini sources in output
 *   (b) vertexaisearch redirect filter — Gemini 404 URLs dropped
 *   (c) Dedup by canonicalised URL — same outlet not duplicated
 *   (d) Empty / edge cases
 */
class FuseSourcesTest {

    // ─── (a) Precedence: SerpAPI first ──────────────────────────────

    @Test
    fun `SerpAPI references appear before Gemini sources`() {
        val gemini = listOf(
            SourceCitation(
                title = "Gemini direct",
                url = "https://direct-gemini.example/article",
                publisher = "GeminiDirect",
            ),
        )
        val serp = listOf(
            SerpReference(
                title = "SerpAPI first",
                url = "https://serp.example/article",
                publisher = "SerpFirst",
                snippet = "",
            ),
        )

        val fused = fuseSources(gemini, serp)

        assertEquals(2, fused.size)
        assertEquals("https://serp.example/article", fused[0].url)
        assertEquals("SerpFirst", fused[0].publisher)
        assertEquals("https://direct-gemini.example/article", fused[1].url)
    }

    // ─── (b) vertexaisearch filter ──────────────────────────────────

    @Test
    fun `Gemini vertexaisearch redirect URLs are dropped`() {
        val gemini = listOf(
            SourceCitation(
                title = "Broken redirect",
                url = "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGnyI...",
                publisher = "Gemini",
            ),
        )

        val fused = fuseSources(gemini, emptyList())

        assertTrue("expected empty, got $fused", fused.isEmpty())
    }

    @Test
    fun `vertexaisearch filter is case insensitive`() {
        val gemini = listOf(
            SourceCitation(
                title = "Mixed case",
                url = "https://VertexAISearch.Cloud.Google.com/grounding-api-redirect/foo",
                publisher = "Gemini",
            ),
        )

        val fused = fuseSources(gemini, emptyList())

        assertTrue(fused.isEmpty())
    }

    @Test
    fun `non-vertexaisearch Gemini sources are kept`() {
        val gemini = listOf(
            SourceCitation(
                title = "Direct Wikipedia",
                url = "https://en.wikipedia.org/wiki/Tour_Eiffel",
                publisher = "Wikipedia",
            ),
        )

        val fused = fuseSources(gemini, emptyList())

        assertEquals(1, fused.size)
        assertEquals("https://en.wikipedia.org/wiki/Tour_Eiffel", fused[0].url)
    }

    @Test
    fun `vertexaisearch SerpAPI references are also filtered (defensive)`() {
        // SerpAPI shouldn't return vertexaisearch URLs, but be defensive.
        val serp = listOf(
            SerpReference(
                title = "Defensive",
                url = "https://vertexaisearch.cloud.google.com/redirect/x",
                publisher = "Whatever",
                snippet = "",
            ),
        )

        val fused = fuseSources(emptyList(), serp)

        assertTrue(fused.isEmpty())
    }

    // ─── (c) Dedup by URL ───────────────────────────────────────────

    @Test
    fun `same URL in both lists yields one entry (SerpAPI wins)`() {
        val sharedUrl = "https://www.bbc.com/news/world-1"
        val gemini = listOf(
            SourceCitation(
                title = "Gemini-side title",
                url = sharedUrl,
                publisher = "BBC News",
                dateYearMonth = "2026-04",
            ),
        )
        val serp = listOf(
            SerpReference(
                title = "SerpAPI-side title",
                url = sharedUrl,
                publisher = "BBC",
                snippet = "",
            ),
        )

        val fused = fuseSources(gemini, serp)

        assertEquals(1, fused.size)
        assertEquals("SerpAPI-side title", fused[0].title) // SerpAPI precedence
    }

    @Test
    fun `URL dedup is case insensitive and ignores trailing slash`() {
        val gemini = listOf(
            SourceCitation(
                title = "Gemini",
                url = "https://Example.com/Path",
                publisher = "Ex",
            ),
        )
        val serp = listOf(
            SerpReference(
                title = "Serp",
                url = "https://example.com/path/",
                publisher = "Ex",
                snippet = "",
            ),
        )

        val fused = fuseSources(gemini, serp)

        assertEquals(1, fused.size)
        assertEquals("Serp", fused[0].title) // SerpAPI seen first → wins
    }

    // ─── (d) Empty / edge cases ─────────────────────────────────────

    @Test
    fun `both empty yields empty`() {
        assertTrue(fuseSources(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `blank URL in Gemini source is skipped`() {
        val gemini = listOf(
            SourceCitation(title = "No URL", url = "", publisher = "??"),
            SourceCitation(title = "Real", url = "https://real.example/x", publisher = "Real"),
        )

        val fused = fuseSources(gemini, emptyList())

        assertEquals(1, fused.size)
        assertEquals("Real", fused[0].title)
    }

    @Test
    fun `mixed real-world scenario produces clean ordered list`() {
        val gemini = listOf(
            SourceCitation(
                title = "Broken redirect",
                url = "https://vertexaisearch.cloud.google.com/grounding-api-redirect/foo",
                publisher = "Gemini",
            ),
            SourceCitation(
                title = "Direct lemonde",
                url = "https://www.lemonde.fr/article/42",
                publisher = "Le Monde",
                dateYearMonth = "2026-03",
            ),
        )
        val serp = listOf(
            SerpReference(
                title = "Wikipedia",
                url = "https://en.wikipedia.org/wiki/Topic",
                publisher = "Wikipedia",
                snippet = "...",
            ),
            SerpReference(
                title = "BBC",
                url = "https://www.bbc.com/news/world-1",
                publisher = "BBC News",
                snippet = "...",
            ),
        )

        val fused = fuseSources(gemini, serp)

        // Expected: SerpAPI 2 + Gemini direct (vertexaisearch dropped) = 3
        assertEquals(3, fused.size)
        assertEquals("Wikipedia", fused[0].title)
        assertEquals("BBC", fused[1].title)
        assertEquals("Direct lemonde", fused[2].title)
    }
}
