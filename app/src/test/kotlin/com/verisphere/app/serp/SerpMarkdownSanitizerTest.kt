package com.verisphere.app.serp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Epic 9 hotfix 2026-05-19 — coverage for [sanitizeSerpMarkdown].
 *
 * Six concern groups :
 *   (a) Empty / whitespace input
 *   (b) Backslash-escape unescaping
 *   (c) GFM table folding to bullet lines
 *   (d) `### References` block dropped (English + French + bare)
 *   (e) Inline `[N]` citation markers stripped
 *   (f) Multi-line blank-line collapsing and idempotency
 */
class SerpMarkdownSanitizerTest {

    // ─── (a) Empty / whitespace ─────────────────────────────────────

    @Test
    fun `empty input returns empty`() {
        assertEquals("", sanitizeSerpMarkdown(""))
    }

    @Test
    fun `whitespace-only input returns empty`() {
        assertEquals("", sanitizeSerpMarkdown("   \n\t\n  "))
    }

    @Test
    fun `plain text passes through unchanged`() {
        val input = "Cristiano Ronaldo a marqué dans 5 éditions différentes."
        assertEquals(input, sanitizeSerpMarkdown(input))
    }

    // ─── (b) Backslash-escape unescaping ────────────────────────────

    @Test
    fun `backslash-escaped parentheses are unescaped`() {
        val input = "Marqué dans 5 éditions \\(2006, 2010, 2014, 2018, 2022\\)."
        assertEquals(
            "Marqué dans 5 éditions (2006, 2010, 2014, 2018, 2022).",
            sanitizeSerpMarkdown(input),
        )
    }

    @Test
    fun `backslash-escaped hyphen is unescaped`() {
        assertEquals(
            "Souhaitez-vous suivre Al-Nassr ?",
            sanitizeSerpMarkdown("Souhaitez\\-vous suivre Al\\-Nassr ?"),
        )
    }

    @Test
    fun `multiple backslash escapes on same line are all unescaped`() {
        assertEquals(
            "(a), (b). (c)!",
            sanitizeSerpMarkdown("\\(a\\), \\(b\\)\\. \\(c\\)\\!"),
        )
    }

    @Test
    fun `lone backslash without trailing punctuation is preserved`() {
        // The escape regex requires a specific follow-up character. A
        // dangling `\` (e.g. mid-prose typo) must survive.
        val input = "Path C:\\users"
        assertEquals(input, sanitizeSerpMarkdown(input))
    }

    // ─── (c) GFM table folding ──────────────────────────────────────

    @Test
    fun `simple GFM table is folded to bullet lines`() {
        val input = """
            | Record | Détails | Statut |
            | --- | --- | --- |
            | Buteur sur plusieurs éditions | Seul joueur. | Détenu |
            | Bilan global | 18 matchs disputés. | Actuel |
        """.trimIndent()
        val expected = """
            • Buteur sur plusieurs éditions — Seul joueur. — Détenu
            • Bilan global — 18 matchs disputés. — Actuel
        """.trimIndent()
        assertEquals(expected, sanitizeSerpMarkdown(input))
    }

    @Test
    fun `table with alignment colons in separator is folded`() {
        val input = """
            | A | B |
            | :--- | ---: |
            | foo | bar |
        """.trimIndent()
        assertEquals("• foo — bar", sanitizeSerpMarkdown(input))
    }

    @Test
    fun `lone pipe lines without a separator row are not treated as a table`() {
        // A single pipe-decorated line is not a GFM table and must be left
        // alone so the sanitizer doesn't eat user text that happens to
        // contain pipes.
        val input = "Result: |success|"
        assertEquals(input, sanitizeSerpMarkdown(input))
    }

    // ─── (d) References block dropped ───────────────────────────────

    @Test
    fun `English Markdown References heading drops everything after`() {
        val input = """
            Body paragraph.

            ### References

            [0] [beIN SPORTS](https://www.facebook.com/beINSPORTSFrance/posts/garbage)
            [1] [Le Monde](https://www.lemonde.fr/whatever)
        """.trimIndent()
        val cleaned = sanitizeSerpMarkdown(input)
        assertEquals("Body paragraph.", cleaned)
        assertFalse("References must not survive", cleaned.contains("References"))
        assertFalse("URLs must not survive", cleaned.contains("facebook.com"))
    }

    @Test
    fun `French Markdown References heading drops everything after`() {
        val input = """
            Synthèse.

            ## Références
            [1] (Le Monde)[https://example]
        """.trimIndent()
        assertEquals("Synthèse.", sanitizeSerpMarkdown(input))
    }

    @Test
    fun `bare References line without heading marker drops everything after`() {
        val input = """
            Body.
            References:
            [1] foo
        """.trimIndent()
        assertEquals("Body.", sanitizeSerpMarkdown(input))
    }

    @Test
    fun `references inside a paragraph mid-sentence are NOT dropped`() {
        // Only line-anchored "References" should be treated as a section
        // marker. Inline mentions in prose must survive.
        val input = "These references support the claim."
        assertEquals(input, sanitizeSerpMarkdown(input))
    }

    // ─── (e) Inline citation markers stripped ───────────────────────

    @Test
    fun `single inline citation marker is removed`() {
        assertEquals(
            "Le Portugal a validé sa qualification.",
            sanitizeSerpMarkdown("Le Portugal a validé sa qualification. [3]"),
        )
    }

    @Test
    fun `multiple inline citation markers are removed`() {
        assertEquals(
            "il s'apprête à disputer sa 6ème phase finale.",
            sanitizeSerpMarkdown("il s'apprête à disputer sa 6ème phase finale. [3] [1] [7]"),
        )
    }

    @Test
    fun `citation markers in middle of sentence are removed cleanly`() {
        val cleaned = sanitizeSerpMarkdown("foo [2] [8] bar")
        assertEquals("foo bar", cleaned)
    }

    // ─── (f) Blank-line collapsing + idempotency ────────────────────

    @Test
    fun `multiple blank lines are collapsed to a single blank line`() {
        val input = "Paragraph A.\n\n\n\nParagraph B."
        assertEquals("Paragraph A.\n\nParagraph B.", sanitizeSerpMarkdown(input))
    }

    @Test
    fun `sanitizer is idempotent`() {
        val raw = """
            | Record | Statut |
            | --- | --- |
            | Buts \(5 éditions\) | Détenu |

            Souhaitez\-vous continuer ? [4]

            ### References
            [0] [beIN](https://facebook.com/garbage)
        """.trimIndent()
        val once = sanitizeSerpMarkdown(raw)
        val twice = sanitizeSerpMarkdown(once)
        assertEquals(once, twice)
        // And the result must not contain any leftover markdown chrome.
        assertFalse(once.contains("\\("))
        assertFalse(once.contains("\\-"))
        assertFalse(once.contains("---"))
        assertFalse(once.contains("References"))
        assertFalse(once.contains("https://"))
        assertTrue(once.contains("• Buts (5 éditions) — Détenu"))
    }

    // ─── (g) Heading markers stripped ───────────────────────────────

    @Test
    fun `H3 heading marker is stripped`() {
        assertEquals(
            "Les conclusions clés du rapport fiscal",
            sanitizeSerpMarkdown("### Les conclusions clés du rapport fiscal"),
        )
    }

    @Test
    fun `H1 through H6 markers are all stripped`() {
        for (level in 1..6) {
            val prefix = "#".repeat(level)
            assertEquals(
                "Titre niveau $level",
                sanitizeSerpMarkdown("$prefix Titre niveau $level"),
            )
        }
    }

    @Test
    fun `hash signs mid-line are preserved`() {
        // Only line-anchored `#` should be stripped. Inline mentions like
        // "C# language" or "issue #42" must survive.
        val input = "Voir le bug #42 dans le projet."
        assertEquals(input, sanitizeSerpMarkdown(input))
    }

    @Test
    fun `heading inside a paragraph is stripped on its own line`() {
        val input = """
            Préambule.

            ## Sous-titre
            Texte qui suit.
        """.trimIndent()
        val expected = """
            Préambule.

            Sous-titre
            Texte qui suit.
        """.trimIndent()
        assertEquals(expected, sanitizeSerpMarkdown(input))
    }

    // ─── (h) Bullet-list markers normalised ─────────────────────────

    @Test
    fun `dash bullet markers are normalised to bullet glyph`() {
        val input = """
            - premier point
            - second point
        """.trimIndent()
        val expected = """
            • premier point
            • second point
        """.trimIndent()
        assertEquals(expected, sanitizeSerpMarkdown(input))
    }

    @Test
    fun `asterisk bullet markers are normalised to bullet glyph`() {
        assertEquals("• point", sanitizeSerpMarkdown("* point"))
    }

    @Test
    fun `plus bullet markers are normalised to bullet glyph`() {
        assertEquals("• point", sanitizeSerpMarkdown("+ point"))
    }

    @Test
    fun `indented list markers preserve their indentation`() {
        // Indentation only survives mid-document — the final .trim() on
        // the sanitizer's return value strips leading whitespace from the
        // very first line, so the assertion uses a top-level bullet
        // followed by an indented sub-bullet.
        val input = """
            - top
              - sub
        """.trimIndent()
        val expected = """
            • top
              • sub
        """.trimIndent()
        assertEquals(expected, sanitizeSerpMarkdown(input))
    }

    @Test
    fun `mid-line dashes are not treated as list markers`() {
        val input = "Cristiano - meilleur buteur."
        assertEquals(input, sanitizeSerpMarkdown(input))
    }

    // ─── (i) Bold emphasis stripped ─────────────────────────────────

    @Test
    fun `double-asterisk bold markers are stripped, content kept`() {
        assertEquals(
            "Cristiano Ronaldo est le meilleur buteur.",
            sanitizeSerpMarkdown("Cristiano Ronaldo est le **meilleur** buteur."),
        )
    }

    @Test
    fun `double-underscore bold markers are stripped, content kept`() {
        assertEquals(
            "Le record est détenu.",
            sanitizeSerpMarkdown("Le record est __détenu__."),
        )
    }

    @Test
    fun `multiple bold spans on same line are all stripped`() {
        assertEquals(
            "foo bar baz",
            sanitizeSerpMarkdown("**foo** **bar** **baz**"),
        )
    }

    // ─── (j) Inline links flattened ─────────────────────────────────

    @Test
    fun `inline Markdown link collapses to link text`() {
        assertEquals(
            "Voir le site officiel pour plus d'infos.",
            sanitizeSerpMarkdown("Voir le [site officiel](https://example.com) pour plus d'infos."),
        )
    }

    @Test
    fun `inline link with empty text falls back to URL`() {
        assertEquals(
            "https://example.com",
            sanitizeSerpMarkdown("[](https://example.com)"),
        )
    }

    // ─── (k) Full real-world payload (regression for screenshot bug) ──

    @Test
    fun `regression — the screenshot payload yields readable plain text`() {
        val input = """
            | Record | Détails | Statut |
            | --- | --- | --- |
            | Buteur sur plusieurs éditions | Seul joueur de l'histoire à avoir marqué dans 5 éditions différentes \(2006, 2010, 2014, 2018, 2022\). | Détenu 🏆 |
            | Bilan global \(Phases finales\) | 18 matchs disputés pour un total de 8 buts inscrits. | Actuel 📊 |
            [2] [8]

            Souhaitez\-vous suivre le calendrier des matchs du Portugal pour la compétition ou consulter les dernières statistiques de Cristiano Ronaldo avec Al\-Nassr ? [4]

            ### References

            [0] [beIN SPORTS France \-Facebook](https://www.facebook.com/beINSPORTSFrance/posts/-%F0%9D%90%94)
        """.trimIndent()

        val cleaned = sanitizeSerpMarkdown(input)

        // Critical regressions from the screenshot :
        assertFalse("no raw table pipes", cleaned.contains("| Record |"))
        assertFalse("no separator row", cleaned.contains("---"))
        assertFalse("no backslash-escaped parens", cleaned.contains("\\("))
        assertFalse("no backslash-escaped hyphens", cleaned.contains("\\-"))
        assertFalse("no References heading", cleaned.contains("References"))
        assertFalse("no URL blob", cleaned.contains("%F0%9D"))
        assertFalse("no inline citation markers", cleaned.contains("[2]"))

        // What must survive :
        assertTrue("table folded as bullet", cleaned.contains("• Buteur sur plusieurs éditions"))
        assertTrue("parens unescaped", cleaned.contains("(2006, 2010, 2014, 2018, 2022)"))
        assertTrue("hyphen unescaped", cleaned.contains("Al-Nassr"))
    }

    @Test
    fun `regression — heading + bold + bullet payload renders cleanly`() {
        // 2nd screenshot 2026-05-19 — "### Les conclusions clés du rapport
        // fiscal" rendered literally. Sanitizer must strip the heading
        // marker, normalise list bullets, and strip bold emphasis without
        // eating any of the prose.
        val input = """
            ### Les conclusions clés du rapport fiscal

            Le rapport contient **trois** conclusions principales :

            - Hausse de la fiscalité sur les hauts revenus
            - Maintien des **niches** fiscales existantes
            * Renforcement des contrôles
        """.trimIndent()

        val cleaned = sanitizeSerpMarkdown(input)

        assertFalse("no heading marker", cleaned.contains("###"))
        assertFalse("no bold markers", cleaned.contains("**"))
        assertFalse("no raw dash bullets", cleaned.lineSequence().any { it.startsWith("- ") })
        assertFalse("no raw asterisk bullets", cleaned.lineSequence().any { it.startsWith("* ") })

        assertTrue("heading text survives", cleaned.contains("Les conclusions clés du rapport fiscal"))
        assertTrue("bold content survives", cleaned.contains("trois conclusions"))
        assertTrue("bullets normalised", cleaned.contains("• Hausse de la fiscalité"))
        assertTrue("bold inside bullet preserved", cleaned.contains("• Maintien des niches fiscales existantes"))
        assertTrue("asterisk bullet normalised", cleaned.contains("• Renforcement des contrôles"))
    }
}
