package com.verisphere.app.bubble

import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.storage.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Story 2.4 (+ Story 3.3) — Pure JVM coverage for tappable-bubble-tap
 * routing extracted into [handleTappableBubbleTap]
 * (file: `bubble/TappableBubbleTapRouting.kt`).
 *
 * The helper reads a [BubbleState] snapshot and invokes
 * `onLaunchPanel(state.record.id)` when (and only when) the state is
 * [BubbleState.Verdict] OR [BubbleState.FailureState.PossibleInjection].
 * Test goal: lock the multi-branch contract in a JVM-only test so
 * future regressions are caught without requiring an instrumented test.
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

        handleTappableBubbleTap(
            state = state,
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertEquals("verdict-id-42", captured)
    }

    @Test
    fun `tap on idle state does not invoke onLaunchPanel`() {
        var captured: String? = null
        val state = BubbleState.Idle(faded = false)

        handleTappableBubbleTap(
            state = state,
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertNull(captured)
    }

    @Test
    fun `tap on pressing state does not invoke onLaunchPanel`() {
        var captured: String? = null

        handleTappableBubbleTap(
            state = BubbleState.Pressing,
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertNull(captured)
    }

    @Test
    fun `tap on capturing state does not invoke onLaunchPanel`() {
        var captured: String? = null

        handleTappableBubbleTap(
            state = BubbleState.Capturing,
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertNull(captured)
    }

    @Test
    fun `tap on thinking state does not invoke onLaunchPanel`() {
        var captured: String? = null

        handleTappableBubbleTap(
            state = BubbleState.Thinking,
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertNull(captured)
    }

    @Test
    fun `tap on faded idle state does not invoke onLaunchPanel`() {
        var captured: String? = null

        handleTappableBubbleTap(
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

        handleTappableBubbleTap(
            state = state,
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertEquals("faded-verdict", captured)
    }

    // ----- Story 3.3 — FailureState.PossibleInjection branch ----------

    @Test
    fun `tap on FailureState PossibleInjection invokes onLaunchPanel with record id`() {
        var captured: String? = null
        val record = sampleRecord(id = "injection-id-99", injectionDetected = true)
        val state = BubbleState.FailureState.PossibleInjection(record = record)

        handleTappableBubbleTap(
            state = state,
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertEquals("injection-id-99", captured)
    }

    @Test
    fun `tap on FailureState Offline does not invoke onLaunchPanel`() {
        var captured: String? = null

        handleTappableBubbleTap(
            state = BubbleState.FailureState.Offline(),
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertNull(captured)
    }

    @Test
    fun `tap on FailureState Timeout does not invoke onLaunchPanel`() {
        var captured: String? = null

        handleTappableBubbleTap(
            state = BubbleState.FailureState.Timeout(),
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertNull(captured)
    }

    @Test
    fun `tap on FailureState DailyLimit does not invoke onLaunchPanel`() {
        var captured: String? = null

        handleTappableBubbleTap(
            state = BubbleState.FailureState.DailyLimit(),
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertNull(captured)
    }

    @Test
    fun `tap on FailureState QuotaExhausted does not invoke onLaunchPanel`() {
        var captured: String? = null

        handleTappableBubbleTap(
            state = BubbleState.FailureState.QuotaExhausted(),
            onLaunchPanel = { sessionId -> captured = sessionId },
        )

        assertNull(captured)
    }

    private fun sampleRecord(
        id: String = "fixture-id",
        injectionDetected: Boolean = false,
    ): SessionRecord = SessionRecord(
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
        injectionDetected = injectionDetected,
    )
}
