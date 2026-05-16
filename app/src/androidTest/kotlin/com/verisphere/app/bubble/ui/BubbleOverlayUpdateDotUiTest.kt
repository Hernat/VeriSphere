package com.verisphere.app.bubble.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.verisphere.app.bubble.BubbleState
import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.storage.SessionRecord
import com.verisphere.app.ui.theme.VeriSphereTheme
import org.junit.Rule
import org.junit.Test

/**
 * Story 6.2 — Compose UI coverage for the orthogonal update-available
 * dot overlay on [BubbleOverlay].
 *
 * One test per state to avoid the
 * `composeTestRule.setContent` single-call-per-test contract (calling
 * it twice in the same method throws). Mirrors
 * [FailureFlashTooltipUiTest]'s per-variant-per-test layout.
 *
 * The dot is identified by [TEST_TAG_BUBBLE_UPDATE_DOT] (`"bubble_update_dot"`).
 *
 * **Orthogonality contract (CDN #2)** — code-review patch P7 (2026-05-16)
 * clarification: orthogonality is **LOGICAL** (the dot is not a new
 * `BubbleState` variant and the `when (state)` block has no dot-specific
 * arm), not strictly **VISUAL**. The `SuctionAnimation` (88 dp,
 * Capturing) and `ThinkingRing` (72 dp, Thinking) are drawn AFTER the
 * inner 56 dp Box and may briefly cover the dot during those transient
 * states — this is intentional: the user is engaged with the active
 * state animation and the dot is for at-rest signaling. Tests below
 * cover Idle (both faded variants), Pressing, Verdict, and FailureState
 * — the states where the dot is visually unobscured.
 */
class BubbleOverlayUpdateDotUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dot_visible_when_has_update_available_is_true_and_state_is_idle_opaque() {
        composeTestRule.setContent {
            VeriSphereTheme {
                BubbleOverlay(
                    state = BubbleState.Idle(faded = false),
                    onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
                    onTap = {}, onTapNearMiss = {}, onLongPress = {},
                    onDragDelta = { _, _ -> }, onDragEnd = {},
                    hasUpdateAvailable = true,
                )
            }
        }
        composeTestRule.onNodeWithTag(TEST_TAG_BUBBLE_UPDATE_DOT).assertIsDisplayed()
    }

    @Test
    fun dot_absent_when_has_update_available_is_false_and_state_is_idle_opaque() {
        composeTestRule.setContent {
            VeriSphereTheme {
                BubbleOverlay(
                    state = BubbleState.Idle(faded = false),
                    onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
                    onTap = {}, onTapNearMiss = {}, onLongPress = {},
                    onDragDelta = { _, _ -> }, onDragEnd = {},
                    hasUpdateAvailable = false,
                )
            }
        }
        composeTestRule.onNodeWithTag(TEST_TAG_BUBBLE_UPDATE_DOT).assertDoesNotExist()
    }

    @Test
    fun dot_visible_when_state_is_idle_faded() {
        composeTestRule.setContent {
            VeriSphereTheme {
                BubbleOverlay(
                    state = BubbleState.Idle(faded = true),
                    onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
                    onTap = {}, onTapNearMiss = {}, onLongPress = {},
                    onDragDelta = { _, _ -> }, onDragEnd = {},
                    hasUpdateAvailable = true,
                )
            }
        }
        composeTestRule.onNodeWithTag(TEST_TAG_BUBBLE_UPDATE_DOT).assertIsDisplayed()
    }

    @Test
    fun dot_visible_when_state_is_pressing() {
        // Code-review patch P3 (2026-05-16) — locks the orthogonality
        // contract for `BubbleState.Pressing` per spec AC #9's
        // parameterised test list. The dot must persist during the
        // 200 ms scale-pulse (1.0 → 1.1 → 1.0); the dot itself does
        // NOT pulse (it's a sibling of the scaling Surface).
        composeTestRule.setContent {
            VeriSphereTheme {
                BubbleOverlay(
                    state = BubbleState.Pressing,
                    onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
                    onTap = {}, onTapNearMiss = {}, onLongPress = {},
                    onDragDelta = { _, _ -> }, onDragEnd = {},
                    hasUpdateAvailable = true,
                )
            }
        }
        composeTestRule.onNodeWithTag(TEST_TAG_BUBBLE_UPDATE_DOT).assertIsDisplayed()
    }

    @Test
    fun dot_visible_when_state_is_verdict() {
        val record = SessionRecord(
            id = "test-verdict-record",
            timestampMs = System.currentTimeMillis(),
            verdictLabel = VerdictLabel.TRUE,
            headline = "Test verdict",
            contextLines = emptyList(),
            sourceLinks = emptyList<SourceCitation>(),
            ocrText = "",
            regionalBiasNote = null,
            injectionDetected = false,
        )

        composeTestRule.setContent {
            VeriSphereTheme {
                BubbleOverlay(
                    state = BubbleState.Verdict(record = record, tooltipFaded = false),
                    onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
                    onTap = {}, onTapNearMiss = {}, onLongPress = {},
                    onDragDelta = { _, _ -> }, onDragEnd = {},
                    hasUpdateAvailable = true,
                )
            }
        }
        composeTestRule.onNodeWithTag(TEST_TAG_BUBBLE_UPDATE_DOT).assertIsDisplayed()
    }

    @Test
    fun dot_visible_when_state_is_failure_offline() {
        composeTestRule.setContent {
            VeriSphereTheme {
                BubbleOverlay(
                    state = BubbleState.FailureState.Offline(tooltipFaded = false),
                    onUserActivity = {}, onLongPressStart = {}, onPressCancelled = {},
                    onTap = {}, onTapNearMiss = {}, onLongPress = {},
                    onDragDelta = { _, _ -> }, onDragEnd = {},
                    hasUpdateAvailable = true,
                )
            }
        }
        composeTestRule.onNodeWithTag(TEST_TAG_BUBBLE_UPDATE_DOT).assertIsDisplayed()
    }
}
