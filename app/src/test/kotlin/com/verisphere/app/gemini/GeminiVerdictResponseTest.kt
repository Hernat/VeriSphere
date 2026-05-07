package com.verisphere.app.gemini

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GeminiVerdictResponse] (Story 1.9, AC #16).
 *
 * Loads hand-crafted JSON fixtures from `app/src/test/resources/gemini_responses/`
 * and asserts kotlinx.serialization deserialises them into the expected
 * shape. The fixtures contain ONLY the inner verdict JSON — the outer
 * Gemini-API envelope (`candidates[0].content.parts[0].text`) is
 * exercised by [GeminiClientTest] via MockWebServer.
 *
 * Tolerance posture: uses `Json { ignoreUnknownKeys = true }` matching
 * [GeminiClient]'s parser (architecture validation Gap #2).
 */
class GeminiVerdictResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `bytes from gemini_response_true_fixture deserialise into GeminiVerdictResponse with VerdictLabel TRUE`() {
        val raw = loadFixture("verdict_true.json")

        val verdict = json.decodeFromString(GeminiVerdictResponse.serializer(), raw)

        assertEquals(VerdictLabel.TRUE, verdict.verdictLabel)
        assertEquals(2, verdict.sources.size)
        assertEquals("The Eiffel Tower is in Paris.", verdict.ocrText)
        assertNull(verdict.regionalBiasNote)
        assertFalse(verdict.injectionDetected)
    }

    @Test
    fun `verdict_false fixture deserialises with VerdictLabel FALSE and contradicting source`() {
        val raw = loadFixture("verdict_false.json")

        val verdict = json.decodeFromString(GeminiVerdictResponse.serializer(), raw)

        assertEquals(VerdictLabel.FALSE, verdict.verdictLabel)
        assertEquals(1, verdict.sources.size)
        assertEquals("BBC News", verdict.sources[0].publisher)
    }

    @Test
    fun `verdict_doubtful fixture deserialises with VerdictLabel DOUBTFUL and a single source`() {
        val raw = loadFixture("verdict_doubtful.json")

        val verdict = json.decodeFromString(GeminiVerdictResponse.serializer(), raw)

        assertEquals(VerdictLabel.DOUBTFUL, verdict.verdictLabel)
        assertEquals(1, verdict.sources.size)
        assertEquals(3, verdict.contextLines.size)
    }

    @Test
    fun `verdict_non_verifiable fixture deserialises with empty sources and explanatory headline`() {
        val raw = loadFixture("verdict_non_verifiable.json")

        val verdict = json.decodeFromString(GeminiVerdictResponse.serializer(), raw)

        assertEquals(VerdictLabel.NON_VERIFIABLE, verdict.verdictLabel)
        assertTrue(verdict.sources.isEmpty())
        assertTrue(verdict.contextLines.isEmpty())
        assertTrue(verdict.headline.isNotBlank())
    }

    @Test
    fun `injection_detected fixture sets injectionDetected to true and still returns a verdict for the actual claim`() {
        val raw = loadFixture("injection_detected.json")

        val verdict = json.decodeFromString(GeminiVerdictResponse.serializer(), raw)

        // The fixture's ocrText carries the injection attempt verbatim
        // (anti-injection self-revealing posture per PRD User Journey 4b).
        assertTrue(
            "ocrText must surface the injection attempt verbatim",
            verdict.ocrText.contains("ignore previous instructions"),
        )
        // The verdict is for the ACTUAL claim ("vaccines cause autism") —
        // the model produced FALSE despite the in-image instruction.
        assertEquals(VerdictLabel.FALSE, verdict.verdictLabel)
        assertTrue("injectionDetected must be true on injection fixture", verdict.injectionDetected)
    }

    @Test
    fun `regional_bias fixture populates regionalBiasNote with a non-null description`() {
        val raw = loadFixture("regional_bias.json")

        val verdict = json.decodeFromString(GeminiVerdictResponse.serializer(), raw)

        assertNotNull(verdict.regionalBiasNote)
        assertTrue(verdict.regionalBiasNote!!.isNotBlank())
        assertEquals(VerdictLabel.DOUBTFUL, verdict.verdictLabel)
    }

    @Test(expected = SerializationException::class)
    fun `unknown VerdictLabel string fails parsing with SerializationException`() {
        val raw = """
            {
              "verdictLabel": "MAYBE",
              "headline": "Unknown label",
              "contextLines": [],
              "sources": [],
              "ocrText": ""
            }
        """.trimIndent()

        json.decodeFromString(GeminiVerdictResponse.serializer(), raw)
    }

    private fun loadFixture(name: String): String =
        javaClass.getResourceAsStream("/gemini_responses/$name")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Fixture missing: /gemini_responses/$name")
}
