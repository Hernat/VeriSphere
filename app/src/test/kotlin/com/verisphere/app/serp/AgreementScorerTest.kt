package com.verisphere.app.serp

import com.verisphere.app.gemini.VerdictLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Epic 9 Story 9.1 — keyword-based agreement scoring.
 *
 * Tests cover :
 *   (a) AGREE path : ≥ 2 supporting keywords, 0 contradicting.
 *   (b) DISAGREE path : 0 supporting, ≥ 2 contradicting.
 *   (c) INCONCLUSIVE : weak signal, mixed signal, empty markdown.
 *   (d) NON_VERIFIABLE shortcut : always INCONCLUSIVE (no opposable claim).
 *   (e) Case-insensitive matching + FR / EN parity.
 */
class AgreementScorerTest {

    // ─── (a) Agree path ──────────────────────────────────────────────

    @Test
    fun `TRUE agrees with markdown containing 2 supporting keywords FR`() {
        val md = "L'affirmation est confirmée par les experts. " +
            "Les sources sont exactes et corroborées."
        assertEquals(
            AgreementVerdict.Agree,
            AgreementScorer.score(VerdictLabel.TRUE, md),
        )
    }

    @Test
    fun `TRUE agrees with mixed FR + EN supporting keywords`() {
        val md = "The claim is confirmed by multiple sources. " +
            "Ce fait est exact d'après les chercheurs."
        assertEquals(
            AgreementVerdict.Agree,
            AgreementScorer.score(VerdictLabel.TRUE, md),
        )
    }

    @Test
    fun `FALSE agrees with markdown containing 2 supporting keywords EN`() {
        val md = "This claim has been debunked by fact-checkers. " +
            "The viral post is false and misleading."
        assertEquals(
            AgreementVerdict.Agree,
            AgreementScorer.score(VerdictLabel.FALSE, md),
        )
    }

    @Test
    fun `DOUBTFUL agrees when markdown surfaces contradictory + disputed keywords`() {
        val md = "Les sources sont contradictoires sur ce point. " +
            "Le sujet reste débattu parmi les chercheurs."
        assertEquals(
            AgreementVerdict.Agree,
            AgreementScorer.score(VerdictLabel.DOUBTFUL, md),
        )
    }

    // ─── (b) Disagree path ──────────────────────────────────────────

    @Test
    fun `TRUE disagrees when markdown clearly says FALSE`() {
        val md = "This claim is false and has been debunked. " +
            "Multiple reputable outlets have rejected the assertion as untrue."
        assertEquals(
            AgreementVerdict.Disagree,
            AgreementScorer.score(VerdictLabel.TRUE, md),
        )
    }

    @Test
    fun `FALSE disagrees when markdown clearly says TRUE`() {
        val md = "L'information est confirmée par plusieurs sources indépendantes. " +
            "Le fait est exact et corroboré par les autorités."
        assertEquals(
            AgreementVerdict.Disagree,
            AgreementScorer.score(VerdictLabel.FALSE, md),
        )
    }

    @Test
    fun `DOUBTFUL disagrees when markdown is clearly decisive`() {
        val md = "The story has been verified and confirmed by independent fact-checkers. " +
            "All evidence points to this being accurate."
        assertEquals(
            AgreementVerdict.Disagree,
            AgreementScorer.score(VerdictLabel.DOUBTFUL, md),
        )
    }

    // ─── (c) Inconclusive path ──────────────────────────────────────

    @Test
    fun `empty markdown is always Inconclusive`() {
        VerdictLabel.entries.forEach { label ->
            assertEquals(
                "label $label / empty markdown",
                AgreementVerdict.Inconclusive,
                AgreementScorer.score(label, ""),
            )
        }
    }

    @Test
    fun `blank markdown is always Inconclusive`() {
        assertEquals(
            AgreementVerdict.Inconclusive,
            AgreementScorer.score(VerdictLabel.TRUE, "   \n\t  "),
        )
    }

    @Test
    fun `single supporting keyword is below threshold`() {
        val md = "The claim is exact. Nothing else relevant."
        assertEquals(
            AgreementVerdict.Inconclusive,
            AgreementScorer.score(VerdictLabel.TRUE, md),
        )
    }

    @Test
    fun `mixed signal yields Inconclusive`() {
        // Both "confirmed" (TRUE-support) and "debunked" (TRUE-contradiction)
        val md = "Some say the claim is confirmed by experts; " +
            "others say it has been debunked and is incorrect."
        assertEquals(
            AgreementVerdict.Inconclusive,
            AgreementScorer.score(VerdictLabel.TRUE, md),
        )
    }

    // ─── (d) NON_VERIFIABLE shortcut ────────────────────────────────

    @Test
    fun `NON_VERIFIABLE is always Inconclusive even with strong markdown`() {
        // Even if SerpAPI surfaces a decisive narrative, Gemini said
        // "can't verify" — there's nothing to agree or disagree with.
        val md = "This is absolutely confirmed and verified by all sources. " +
            "100% true and exact and accurate."
        assertEquals(
            AgreementVerdict.Inconclusive,
            AgreementScorer.score(VerdictLabel.NON_VERIFIABLE, md),
        )
    }

    // ─── (e) Case-insensitive matching ──────────────────────────────

    @Test
    fun `matching is case insensitive`() {
        val md = "CONFIRMED by experts. The claim is VERIFIED."
        assertEquals(
            AgreementVerdict.Agree,
            AgreementScorer.score(VerdictLabel.TRUE, md),
        )
    }
}
