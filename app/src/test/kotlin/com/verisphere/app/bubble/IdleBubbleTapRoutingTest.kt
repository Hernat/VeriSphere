package com.verisphere.app.bubble

import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.storage.SessionRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Story 4.3 — Pure JVM coverage for idle-bubble-tap routing extracted
 * into [handleIdleBubbleTap] (file: `bubble/IdleBubbleTapRouting.kt`).
 *
 * The helper takes a **pre-touch-down snapshot** [BubbleState]? and
 * invokes `onLaunchHistory()` when (and only when) the snapshot is
 * [BubbleState.Idle] (faded or opaque). Every other source state — and
 * `null` — is a no-op. Test goal: lock the binary contract in a
 * JVM-only test so future regressions are caught without requiring an
 * instrumented test.
 *
 * **Why a snapshot, not the live state.** The bubble's gesture handler
 * dispatches `LongPressStarted` on touch-down — transitioning Idle /
 * Verdict / FailureState → Pressing — BEFORE the tap-vs-long-press
 * deadline resolves. If the helper read `bubbleStateMachine.state.value`
 * on the tap callback, it would always see `Pressing` and never fire
 * the history-open. The service maintains `lastNonPressingState` (set
 * by the state observer when entering any non-Pressing state) and
 * passes that value here as `sourceState`.
 *
 * **No Android runtime touched** — the helper has zero Android-API
 * dependencies (no `Intent`, no `Context`, no `Log`). The state types
 * themselves are POKO data classes. Backed by the JVM-suite default
 * dispatcher; no `unitTests.isReturnDefaultValues` stub concerns.
 *
 * Method names follow the JVM backtick-English convention (architecture
 * line 426 — JVM tests use backtick English; androidTest uses
 * underscore_snake_case). Mirrors the [BubbleOverlayServiceTapHandlerTest]
 * structure verbatim.
 *
 * **Lambda-seam pattern** — 8th application across the codebase
 * (Stories 1.5, 1.7, 2.1, 2.2, 2.3, 2.4, 3.3 + this Story 4.3).
 */
class IdleBubbleTapRoutingTest {

    @Test
    fun `tap with Idle source state invokes onLaunchHistory`() {
        var captured = false

        handleIdleBubbleTap(
            sourceState = BubbleState.Idle(faded = false),
            onLaunchHistory = { captured = true },
        )

        assertTrue(captured)
    }

    @Test
    fun `tap with faded Idle source state still invokes onLaunchHistory`() {
        var captured = false

        handleIdleBubbleTap(
            sourceState = BubbleState.Idle(faded = true),
            onLaunchHistory = { captured = true },
        )

        assertTrue(captured)
    }

    @Test
    fun `tap with Verdict source state does not invoke onLaunchHistory`() {
        var captured = false
        val state = BubbleState.Verdict(record = sampleRecord(id = "verdict-id"))

        handleIdleBubbleTap(
            sourceState = state,
            onLaunchHistory = { captured = true },
        )

        assertFalse(captured)
    }

    @Test
    fun `tap with PossibleInjection source state does not invoke onLaunchHistory`() {
        var captured = false
        val state = BubbleState.FailureState.PossibleInjection(
            record = sampleRecord(id = "injection-id", injectionDetected = true),
        )

        handleIdleBubbleTap(
            sourceState = state,
            onLaunchHistory = { captured = true },
        )

        assertFalse(captured)
    }

    @Test
    fun `tap with Offline source state does not invoke onLaunchHistory`() {
        var captured = false

        handleIdleBubbleTap(
            sourceState = BubbleState.FailureState.Offline(),
            onLaunchHistory = { captured = true },
        )

        assertFalse(captured)
    }

    @Test
    fun `tap with Timeout source state does not invoke onLaunchHistory`() {
        var captured = false

        handleIdleBubbleTap(
            sourceState = BubbleState.FailureState.Timeout(),
            onLaunchHistory = { captured = true },
        )

        assertFalse(captured)
    }

    @Test
    fun `tap with DailyLimit source state does not invoke onLaunchHistory`() {
        var captured = false

        handleIdleBubbleTap(
            sourceState = BubbleState.FailureState.DailyLimit(),
            onLaunchHistory = { captured = true },
        )

        assertFalse(captured)
    }

    @Test
    fun `tap with QuotaExhausted source state does not invoke onLaunchHistory`() {
        var captured = false

        handleIdleBubbleTap(
            sourceState = BubbleState.FailureState.QuotaExhausted(),
            onLaunchHistory = { captured = true },
        )

        assertFalse(captured)
    }

    @Test
    fun `tap with null source state does not invoke onLaunchHistory`() {
        var captured = false

        handleIdleBubbleTap(
            sourceState = null,
            onLaunchHistory = { captured = true },
        )

        assertFalse(captured)
    }

    @Test
    fun `tap with Capturing source state does not invoke onLaunchHistory`() {
        var captured = false

        handleIdleBubbleTap(
            sourceState = BubbleState.Capturing,
            onLaunchHistory = { captured = true },
        )

        assertFalse(captured)
    }

    @Test
    fun `tap with Thinking source state does not invoke onLaunchHistory`() {
        var captured = false

        handleIdleBubbleTap(
            sourceState = BubbleState.Thinking,
            onLaunchHistory = { captured = true },
        )

        assertFalse(captured)
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
