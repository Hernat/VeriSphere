package com.verisphere.app.serp

import com.verisphere.app.gemini.VerdictLabel

/**
 * Epic 9 Story 9.1 — keyword-based heuristic that compares Gemini's
 * [VerdictLabel] to SerpAPI's reconstructed_markdown text.
 *
 * **Why heuristic, not NLP/ML** : V1 ships a simple keyword tally — fast,
 * no extra dependency, no privacy concern (runs locally), and good enough
 * to flag obvious contradictions. Refinement (tuning keyword lists, adding
 * embeddings) can come from real-traffic feedback in V2.
 *
 * **Algorithm** :
 * 1. Lowercase the markdown.
 * 2. Count occurrences of [SUPPORTING] indicators for the Gemini verdict.
 * 3. Count occurrences of [CONTRADICTING] indicators (the keyword sets
 *    that point to the OPPOSITE verdict).
 * 4. Decision table:
 *    - support ≥ 2 AND conflict == 0 → [AgreementVerdict.Agree]
 *    - support == 0 AND conflict ≥ 2 → [AgreementVerdict.Disagree]
 *    - otherwise → [AgreementVerdict.Inconclusive]
 *
 * The thresholds are deliberately conservative — better surface
 * [Inconclusive] (silent in the UI) than fire a false-positive
 * "sources contradictoires" badge on weak signal.
 *
 * **FR + EN** : VeriSphere is FR-first but SerpAPI sometimes returns
 * English snippets for international claims. The keyword lists cover
 * both languages.
 *
 * **Edge cases handled** :
 * - Empty / blank markdown → [Inconclusive] (no signal).
 * - [VerdictLabel.NON_VERIFIABLE] → [Inconclusive] always (there's
 *   nothing to disagree with — Gemini already said it can't verify).
 *
 * Pure function — safe to call on any thread, no allocation per call
 * other than the lowercase copy of the markdown.
 */
object AgreementScorer {

    fun score(label: VerdictLabel, markdown: String): AgreementVerdict {
        if (markdown.isBlank()) return AgreementVerdict.Inconclusive
        if (label == VerdictLabel.NON_VERIFIABLE) return AgreementVerdict.Inconclusive

        // Code-review P6 — regex carries `(?Ui)` for Unicode case
        // insensitivity, so we no longer lowercase the haystack
        // (redundant work + the lowercase-then-IGNORE_CASE pairing was
        // misleading about how matching is actually done).
        val supportHits = SUPPORTING_PATTERNS[label].orEmpty()
            .sumOf { pattern -> pattern.findAll(markdown).count() }
        val conflictHits = CONTRADICTING_PATTERNS[label].orEmpty()
            .sumOf { pattern -> pattern.findAll(markdown).count() }

        return when {
            supportHits >= AGREE_THRESHOLD && conflictHits == 0 -> AgreementVerdict.Agree
            supportHits == 0 && conflictHits >= DISAGREE_THRESHOLD -> AgreementVerdict.Disagree
            else -> AgreementVerdict.Inconclusive
        }
    }

    /**
     * Keywords that, when found in the SerpAPI markdown, support each
     * verdict. FR + EN per Epic 9 plan.
     *
     * **Stems only** (code-review P6 — keyword-overlap inflation fix):
     * since the regex uses `\b<stem>` (start-only boundary, free suffix),
     * `confirmé` matches `confirmé / confirmée / confirmées` all in one
     * pattern. Keeping BOTH the masculine + feminine forms double-counted
     * a single feminine plural word into 2 hits and falsely tripped the
     * ≥ 2 AGREE_THRESHOLD on a single weak keyword occurrence. Where the
     * masc/fem forms have DIFFERENT stems (faux ≠ fausse), both are kept.
     *
     * **Avoid keywords that are too generic** : the regex `\b` prefix
     * excludes the false-positive prefix case (`untrue`, `inexact`,
     * `falsehood`) but a pure-substring needle would not.
     */
    private val SUPPORTING: Map<VerdictLabel, List<String>> = mapOf(
        VerdictLabel.TRUE to listOf(
            "confirmé", "exact", "véridique", "avéré",
            "confirmed", "verified", "correct", "accurate", "true",
        ),
        VerdictLabel.FALSE to listOf(
            "faux", "fausse", "rejeté", "démenti",
            "incorrect", "erroné",
            "false", "debunked", "untrue", "wrong", "inaccurate",
        ),
        VerdictLabel.DOUBTFUL to listOf(
            "contradictoire", "débattu", "incertain", "controversé",
            "disputed", "unclear", "mixed", "contested", "uncertain",
        ),
    )

    /**
     * For each verdict, which OTHER verdict's supporting keywords count
     * as contradicting evidence. TRUE ↔ FALSE are direct opposites;
     * DOUBTFUL has no clean opposite (DOUBTFUL contradicted by a clearly
     * decisive TRUE or FALSE narrative).
     */
    private val CONTRADICTING: Map<VerdictLabel, List<String>> = mapOf(
        VerdictLabel.TRUE to SUPPORTING[VerdictLabel.FALSE].orEmpty(),
        VerdictLabel.FALSE to SUPPORTING[VerdictLabel.TRUE].orEmpty(),
        VerdictLabel.DOUBTFUL to
            SUPPORTING[VerdictLabel.TRUE].orEmpty() + SUPPORTING[VerdictLabel.FALSE].orEmpty(),
    )

    /**
     * Pre-compiled regex patterns — one per keyword — built once at
     * class load time (code-review P6 — was being recompiled per-call,
     * ~25 regex compilations per [score] invocation under the corpus
     * runner).
     *
     * **Android ICU regex constraint** (live-runtime hotfix 2026-05-19) :
     * the JVM `(?U)` UNICODE_CHARACTER_CLASS inline flag is NOT
     * supported by Android's ICU regex engine — class-load throws
     * `PatternSyntaxException` and the bubble service crashes on its
     * first enrichment. The JVM unit tests pass (Java `java.util.regex`
     * supports `(?U)`) but Android (ICU) does not. We drop `(?U)` and
     * use [RegexOption.IGNORE_CASE] for case insensitivity ; this
     * reverts the boundary to ASCII `\b` semantics. The dedupe-fix
     * benefits (one stem per inflection-family) still hold.
     */
    private val SUPPORTING_PATTERNS: Map<VerdictLabel, List<Regex>> =
        SUPPORTING.mapValues { (_, words) -> words.map { it.toBoundaryRegex() } }
    private val CONTRADICTING_PATTERNS: Map<VerdictLabel, List<Regex>> =
        CONTRADICTING.mapValues { (_, words) -> words.map { it.toBoundaryRegex() } }

    private const val AGREE_THRESHOLD: Int = 2
    private const val DISAGREE_THRESHOLD: Int = 2

    /**
     * Builds the case-insensitive word-start-boundary regex for a
     * stem. Inflected suffix forms (`exactes`, `confirmée`) match the
     * same stem pattern; words with a leading prefix (`inexact`,
     * `untrue`) do not because the `\b` boundary requires a non-word
     * character before the needle.
     *
     * **ASCII `\b` only** — Android ICU regex does not accept the JVM
     * `(?U)` UNICODE_CHARACTER_CLASS flag (crashed bubble service on
     * 2026-05-19 live smoke). For our keyword set this is acceptable :
     * boundaries are evaluated against whitespace / punctuation in the
     * SerpAPI markdown, and ASCII `\b` treats space + `.` + `,` + `"`
     * as boundaries just like Unicode `\b` would.
     */
    private fun String.toBoundaryRegex(): Regex =
        Regex("\\b${Regex.escape(this)}", RegexOption.IGNORE_CASE)
}
