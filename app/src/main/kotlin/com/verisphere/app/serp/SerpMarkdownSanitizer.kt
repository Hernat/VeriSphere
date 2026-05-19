package com.verisphere.app.serp

/**
 * Epic 9 hotfix 2026-05-19 — sanitises SerpAPI's `reconstructed_markdown`
 * field into clean readable plain text for the detail panel's
 * "Synthèse Google" sub-section.
 *
 * The Compose detail surface renders the synthesis as a single
 * [androidx.compose.material3.Text], so any GFM markdown leaks through as
 * literal characters. SerpAPI AI mode returns several constructs that look
 * terrible as plain text:
 *
 *  1. GFM tables `| Header | … |` — render as a wall of pipes.
 *  2. "Safe markdown" backslash escapes (`\(`, `\-`, `\.`) — render as
 *     visible backslashes.
 *  3. `### References` heading + reference list with raw URL blobs —
 *     duplicates what [com.verisphere.app.ui.detail.SourcesSection] already
 *     renders from [SerpApiResponse.references], and the URL-encoded
 *     mathematical-bold Facebook posts come out as multi-line
 *     `%F0%9D%90…` strings.
 *  4. Inline `[N]` citation markers — useless without the linked refs.
 *
 * Best-effort plain-text simplification (no full markdown parser pulled in
 * for ~70 LoC of cleanup):
 *  1. Drop the `### References` / `## Références` block and everything
 *     after it (references already surface as chips in the Sources
 *     section).
 *  2. Convert GFM tables to bullet lines with `—` (em-dash) cell
 *     separators ; drop the header + separator rows.
 *  3. Unescape backslash-escaped punctuation.
 *  4. Strip inline `[N]` citation markers.
 *  5. Collapse runs of blank lines.
 *
 * Pure function — `internal` so the JVM test suite
 * (`SerpMarkdownSanitizerTest`) can exercise every branch without booting
 * Compose.
 */
internal fun sanitizeSerpMarkdown(raw: String): String {
    if (raw.isBlank()) return ""

    val withoutReferences = dropReferencesBlock(raw)
    val tablesFolded = foldTables(withoutReferences)
    val headingsStripped = stripHeadingMarkers(tablesFolded)
    val listsNormalized = normalizeListMarkers(headingsStripped)
    val emphasisStripped = stripEmphasis(listsNormalized)
    val linksFlattened = flattenInlineLinks(emphasisStripped)
    val unescaped = unescapeBackslashes(linksFlattened)
    val withoutCitations = stripCitationMarkers(unescaped)
    return collapseBlankLines(withoutCitations).trim()
}

/**
 * Match the first occurrence of a "References" heading (English or French,
 * markdown heading or bare line) and drop everything from it onwards.
 * Multiline anchors so the regex matches on any line, not just at the
 * start of the input.
 */
private val REFERENCES_HEADING = Regex(
    pattern = "^\\s*(#{1,6}\\s*)?(R[ée]f[ée]rences?|References?|Sources?)\\s*:?\\s*$",
    options = setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
)

private fun dropReferencesBlock(raw: String): String {
    val match = REFERENCES_HEADING.find(raw) ?: return raw
    return raw.substring(0, match.range.first)
}

/** Any row that starts and ends with `|`. */
private val TABLE_ROW = Regex("^\\s*\\|.*\\|\\s*$")

/** GFM table-separator row: `| --- | --- |` (with optional `:` alignment markers). */
private val TABLE_SEPARATOR = Regex("^\\s*\\|?(\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?\\s*$")

private fun foldTables(input: String): String {
    val lines = input.lines()
    val out = ArrayList<String>(lines.size)
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val next = lines.getOrNull(i + 1)
        if (TABLE_ROW.matches(line) && next != null && TABLE_SEPARATOR.matches(next)) {
            // Skip the header + separator ; convert each subsequent body row
            // into a bullet line with em-dash cell separators.
            i += 2
            while (i < lines.size && TABLE_ROW.matches(lines[i])) {
                val cells = lines[i]
                    .trim()
                    .trim('|')
                    .split('|')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (cells.isNotEmpty()) {
                    out += "• " + cells.joinToString(" — ")
                }
                i++
            }
        } else {
            out += line
            i++
        }
    }
    return out.joinToString("\n")
}

/**
 * ATX heading marker at the start of a line : 1-6 `#` characters followed
 * by whitespace. Strips the markers ; keeps the heading text as plain
 * prose (the surrounding section already provides visual hierarchy via
 * "Information supplémentaire" / "Synthèse Google" headers, so a styled
 * sub-heading would only add chrome).
 */
// Horizontal-whitespace only (`[ \t]`) ; using `\s` would consume the
// newline that anchors `^` and eat blank lines above the heading.
private val HEADING_MARKER = Regex("(?m)^[ \\t]*#{1,6}[ \\t]+")

private fun stripHeadingMarkers(input: String): String =
    HEADING_MARKER.replace(input, "")

/**
 * Bullet-list markers at the start of a line (`-`, `*`, `+`) — normalised
 * to `•` so the synthesis aligns with the surrounding bullet rendering
 * convention (Gemini contextLines + the folded-table bullets already use
 * `•`). Indentation is preserved so nested lists keep their visual offset.
 */
private val LIST_MARKER = Regex("(?m)^([ \\t]*)[-*+][ \\t]+")

private fun normalizeListMarkers(input: String): String =
    LIST_MARKER.replace(input) { "${it.groupValues[1]}• " }

/**
 * Bold (`**foo**` and `__foo__`) — markers stripped, content kept. Italic
 * is NOT handled : the single-`*` / single-`_` regex has a high false-
 * positive rate on French prose (`l'attaquant_du_jour`, math expressions,
 * etc.). If italic markers leak through cosmetically, that's preferable to
 * silently eating legitimate text.
 */
private val BOLD = Regex("\\*\\*([^*\\n]+?)\\*\\*|__([^_\\n]+?)__")

private fun stripEmphasis(input: String): String =
    BOLD.replace(input) { match ->
        match.groupValues[1].ifEmpty { match.groupValues[2] }
    }

/**
 * Inline Markdown links `[text](url)` collapsed to just `text`. The
 * dedicated SerpAPI references list already surfaces URLs as clickable
 * chips in the Sources section ; any link that survived
 * [dropReferencesBlock] (mid-paragraph mentions, inline anchors) would
 * otherwise render its raw URL parens-and-all as literal text. Empty
 * link-text falls back to the URL so we never silently drop content.
 */
private val INLINE_LINK = Regex("\\[([^\\[\\]\\n]*)]\\(([^)\\n]+)\\)")

private fun flattenInlineLinks(input: String): String =
    INLINE_LINK.replace(input) { match ->
        match.groupValues[1].ifBlank { match.groupValues[2] }
    }

/**
 * Common GFM-escape characters. Matches `\X` for any X in the set and
 * collapses to the bare character.
 */
private val BACKSLASH_ESCAPE = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!,:;?<>|/~=])")

private fun unescapeBackslashes(input: String): String =
    BACKSLASH_ESCAPE.replace(input) { it.groupValues[1] }

/** Inline numeric citation marker (e.g. `[2]`, `[42]`). Whitespace around
 *  the marker is consumed so we don't leave double spaces behind. */
private val CITATION_MARKER = Regex("\\s*\\[\\d+\\]")

private fun stripCitationMarkers(input: String): String =
    CITATION_MARKER.replace(input, "")

private fun collapseBlankLines(input: String): String {
    val out = ArrayList<String>()
    var prevBlank = false
    for (raw in input.lines()) {
        val line = raw.trimEnd()
        val isBlank = line.isBlank()
        if (isBlank && prevBlank) continue
        out += line
        prevBlank = isBlank
    }
    return out.joinToString("\n")
}
