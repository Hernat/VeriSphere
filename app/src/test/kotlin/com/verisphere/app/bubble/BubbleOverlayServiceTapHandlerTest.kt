package com.verisphere.app.bubble

import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.storage.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Story 2.4 — Pure JVM coverage for the `Verdict × Tap` routing
 * extracted into [handleVerdictBubbleTap] (file: `bubble/VerdictTapRouting.kt`).
 *
 * The helper is a 4-line pure function that reads a [BubbleState]
 * snapshot and invokes `onLaunchPanel(state.record.id)` when (and only
 * when) the state is [BubbleState.Verdict]. Test goal: lock the
 * single-branch contract in a JVM-only test so future regressions are
 * caught without requiring an instrumented test.
 *
 * **No Android runtime touched** — the helper has zero Android-API
 * dependencies (no `Intent`, no `Context`, no `Log`). The state types
 * themselves are POKO data classes. Backed by the JVM-suite default
 * dispatcher; no `unitTests.isReturnDefaultValues` stub concerns.
 *
 * Method names follow the JVM backtick-English convention (architecture
 * line 426 — JVM tests use backtick English; androidTest uses
 * underscore_snake_case).
 */
class BubbleOverlayServiceTapHandlerTest {

    @Test
    fun `tap on verdict state invokes onLaunchPanel with record id`() {
        var captured: String? = null
        val record = sampleRecord(id = "verdict-id-42")
        val state = BubbleState.Verdict(record = record)

        handleVerdictBubbleTap(
            state = state,
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertEquals("verdict-id-42", captured)
    }

    @Test
    fun `tap on idle state does not invoke onLaunchPanel`() {
        var captured: String? = null
        val state = BubbleState.Idle(faded = false)

        handleVerdictBubbleTap(
            state = state,
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertNull(captured)
    }

    @Test
    fun `tap on pressing state does not invoke onLaunchPanel`() {
        var captured: String? = null

        handleVerdictBubbleTap(
            state = BubbleState.Pressing,
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertNull(captured)
    }

    @Test
    fun `tap on capturing state does not invoke onLaunchPanel`() {
        var captured: String? = null

        handleVerdictBubbleTap(
            state = BubbleState.Capturing,
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertNull(captured)
    }

    @Test
    fun `tap on thinking state does not invoke onLaunchPanel`() {
        var captured: String? = null

        handleVerdictBubbleTap(
            state = BubbleState.Thinking,
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertNull(captured)
    }

    @Test
    fun `tap on faded idle state does not invoke onLaunchPanel`() {
        var captured: String? = null

        handleVerdictBubbleTap(
            state = BubbleState.Idle(faded = true),
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertNull(captured)
    }

    @Test
    fun `tap on verdict with faded tooltip still invokes onLaunchPanel`() {
        var captured: String? = null
        val record = sampleRecord(id = "faded-verdict")
        val state = BubbleState.Verdict(record = record, tooltipFaded = true)

        handleVerdictBubbleTap(
            state = state,
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertEquals("faded-verdict", captured)
    }

    private fun sampleRecord(id: String = "fixture-id"): SessionRecord = SessionRecord(
        id = id,
        timestampMs = 0L,
        verdictLabel = VerdictLabel.TRUE,
        headline = "Sample headline",
        contextLines = emptyList(),
        sourceLinks = listOf(
            SourceCitation(
                title = "Sample article",
                url = "https://www.bbc.com/news/sample",
                publisher = "BBC News",
                dateYearMonth = "2026-04",
            ),
        ),
        ocrText = "Sample OCR text",
        regionalBiasNote = null,
    )
}
