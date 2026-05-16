package com.verisphere.app.ui.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.verisphere.app.R
import com.verisphere.app.ui.theme.VeriSphereTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Story 5.1 — Compose UI coverage for [OnboardingTutorialOverlay].
 *
 * Test method names use `snake_case` per architecture line 426 + Story 4.4
 * D7 reaffirmation (DEX < 040 constraint at `minSdk = 30`).
 *
 * Pattern mirrors [`DetailPanelContentUiTest`](../detail/DetailPanelContentUiTest.kt):
 *   - `createComposeRule()` (NOT `createAndroidComposeRule<MainActivity>()`).
 *   - String assertions go through
 *     `InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.…)`
 *     so the test stays resilient to future copy edits (Story 4.4 D7 path).
 *   - A small [CapturedCallbacks] holder counts how often each callback
 *     fires — same shape as `DetailPanelContentUiTest.CapturedHolder`.
 *
 * The 6 tests cover AC #8:
 *  1. Card 1 does not advance while the accessibility service is off
 *     (CTA invokes the callback but the card stays); no Skip button on card 1.
 *  2. Card 1 auto-advances the moment the parameter flips to `true`
 *     (`LaunchedEffect` advance, mirror of `AnchoredDetailPanelUiTest`'s
 *     `MutableState<Boolean>`-driven pattern).
 *  3. Next chains card 2 → 3 → 4; card 4 CTA is "Got it" and invokes
 *     `onComplete`.
 *  4. Skip on card 2 invokes `onSkip` exactly once.
 *  5. Skip on card 3 then card 4 invokes `onSkip` twice (cumulative).
 *  6. fontScale 1.5 does not truncate card 2 body or CTA (NFR14 lock-in).
 */
class OnboardingTutorialOverlayUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun str(resId: Int): String = targetContext.getString(resId)

    @Test
    fun card_1_does_not_advance_while_accessibility_service_disabled() {
        val callbacks = CapturedCallbacks()

        composeTestRule.setContent {
            VeriSphereTheme {
                OnboardingTutorialOverlay(
                    accessibilityServiceEnabled = false,
                    onActivateAccessibilityClick = { callbacks.activateCount += 1 },
                    onComplete = { callbacks.completeCount += 1 },
                    onSkip = { callbacks.skipCount += 1 },
                )
            }
        }

        // Pre-state: card 1 visible with title + body + Activer CTA +
        // waiting hint; no Skip button. Review patch P4 — body +
        // waiting hint were not asserted before, which would let a
        // future regression hide the privacy disclosure (FR19's whole
        // point) silently.
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_1_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_1_body))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_1_cta_activate))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_1_waiting_hint))
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText(str(R.string.tutorial_cta_skip))
            .assertCountEquals(0)

        // Tap "Activer".
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_1_cta_activate))
            .performClick()
        composeTestRule.waitForIdle()

        // Callback fired exactly once; card 1 still visible (title +
        // body + waiting hint); card 2 not yet shown.
        assertEquals(1, callbacks.activateCount)
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_1_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_1_body))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_1_waiting_hint))
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText(str(R.string.tutorial_card_2_title))
            .assertCountEquals(0)
        // Skip still not present (gate AC #3).
        composeTestRule.onAllNodesWithText(str(R.string.tutorial_cta_skip))
            .assertCountEquals(0)
    }

    @Test
    fun card_1_auto_advances_to_card_2_when_accessibility_service_enabled_flips_true() {
        val callbacks = CapturedCallbacks()
        val enabledState: MutableState<Boolean> = mutableStateOf(false)

        composeTestRule.setContent {
            VeriSphereTheme {
                OnboardingTutorialOverlay(
                    accessibilityServiceEnabled = enabledState.value,
                    onActivateAccessibilityClick = { callbacks.activateCount += 1 },
                    onComplete = { callbacks.completeCount += 1 },
                    onSkip = { callbacks.skipCount += 1 },
                )
            }
        }

        // Pre-flip: card 1 visible, card 2 absent.
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_1_title))
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText(str(R.string.tutorial_card_2_title))
            .assertCountEquals(0)

        // Flip parameter from the test side. `LaunchedEffect(accessibilityServiceEnabled)`
        // inside the composable advances `currentCardIndex` from 0 to 1.
        composeTestRule.runOnUiThread { enabledState.value = true }
        composeTestRule.waitForIdle()

        // Post-flip: card 2 visible, card 1 gone, Skip now rendered.
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_2_title))
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText(str(R.string.tutorial_card_1_title))
            .assertCountEquals(0)
        // Review patch P5 — waiting hint MUST disappear once the auto-
        // advance fires (the hint is only visible while
        // currentCardIndex == 0 && !accessibilityServiceEnabled per the
        // card-1 conditional in TutorialCardContent).
        composeTestRule.onAllNodesWithText(str(R.string.tutorial_card_1_waiting_hint))
            .assertCountEquals(0)
        composeTestRule.onNodeWithText(str(R.string.tutorial_cta_skip))
            .assertIsDisplayed()
        // No callbacks fired during the auto-advance — only on user gestures.
        assertEquals(0, callbacks.activateCount)
        assertEquals(0, callbacks.completeCount)
        assertEquals(0, callbacks.skipCount)
    }

    @Test
    fun next_advances_card_2_to_card_3_to_card_4_then_got_it_invokes_oncomplete() {
        val callbacks = CapturedCallbacks()

        composeTestRule.setContent {
            VeriSphereTheme {
                OnboardingTutorialOverlay(
                    accessibilityServiceEnabled = true,
                    onActivateAccessibilityClick = { callbacks.activateCount += 1 },
                    onComplete = { callbacks.completeCount += 1 },
                    onSkip = { callbacks.skipCount += 1 },
                )
            }
        }
        composeTestRule.waitForIdle()

        // Auto-advance lands on card 2 immediately (accessibilityServiceEnabled = true at mount).
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_2_title))
            .assertIsDisplayed()

        // Card 2 → Card 3.
        composeTestRule.onNodeWithText(str(R.string.tutorial_cta_next))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_3_title))
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText(str(R.string.tutorial_card_2_title))
            .assertCountEquals(0)

        // Card 3 → Card 4.
        composeTestRule.onNodeWithText(str(R.string.tutorial_cta_next))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_4_title))
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText(str(R.string.tutorial_card_3_title))
            .assertCountEquals(0)

        // Card 4 CTA must be "Got it" (NOT "Next").
        composeTestRule.onNodeWithText(str(R.string.tutorial_cta_got_it))
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText(str(R.string.tutorial_cta_next))
            .assertCountEquals(0)

        // Tap "Got it" → onComplete fires exactly once; onSkip / onActivate stay zero.
        composeTestRule.onNodeWithText(str(R.string.tutorial_cta_got_it))
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, callbacks.completeCount)
        assertEquals(0, callbacks.skipCount)
        assertEquals(0, callbacks.activateCount)
    }

    @Test
    fun skip_on_card_2_invokes_onskip_exactly_once() {
        val callbacks = CapturedCallbacks()

        composeTestRule.setContent {
            VeriSphereTheme {
                OnboardingTutorialOverlay(
                    accessibilityServiceEnabled = true,
                    onActivateAccessibilityClick = { callbacks.activateCount += 1 },
                    onComplete = { callbacks.completeCount += 1 },
                    onSkip = { callbacks.skipCount += 1 },
                )
            }
        }
        composeTestRule.waitForIdle()

        // Auto-advance lands on card 2; Skip is visible on cards 2-4.
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_2_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.tutorial_cta_skip))
            .assertIsDisplayed()

        // Tap Skip → onSkip fires once; onComplete / onActivate stay zero.
        composeTestRule.onNodeWithText(str(R.string.tutorial_cta_skip))
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, callbacks.skipCount)
        assertEquals(0, callbacks.completeCount)
        assertEquals(0, callbacks.activateCount)
    }

    @Test
    fun skip_on_card_3_and_card_4_invokes_onskip_twice_cumulative() {
        val callbacks = CapturedCallbacks()

        composeTestRule.setContent {
            VeriSphereTheme {
                OnboardingTutorialOverlay(
                    accessibilityServiceEnabled = true,
                    onActivateAccessibilityClick = { callbacks.activateCount += 1 },
                    onComplete = { callbacks.completeCount += 1 },
                    onSkip = { callbacks.skipCount += 1 },
                )
            }
        }
        composeTestRule.waitForIdle()

        // Auto-advance lands on card 2 → tap Next → card 3.
        composeTestRule.onNodeWithText(str(R.string.tutorial_cta_next))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_3_title))
            .assertIsDisplayed()
        // Review patch P5 — assert card-3 BODY too (not just the title),
        // so a future copy-paste swap of `R.string.tutorial_card_3_body`
        // vs `_4_body` in the `Triple` mapping inside
        // [TutorialCardContent] is caught.
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_3_body))
            .assertIsDisplayed()

        // Skip on card 3 → count = 1. The composable does NOT unmount itself
        // on skip — the host owns that decision (Story 5.2). The card stays
        // visible so the test can continue exercising the same instance.
        composeTestRule.onNodeWithText(str(R.string.tutorial_cta_skip))
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, callbacks.skipCount)
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_3_title))
            .assertIsDisplayed()

        // Advance card 3 → card 4. Next is still visible because skip does
        // not mutate `currentCardIndex`.
        composeTestRule.onNodeWithText(str(R.string.tutorial_cta_next))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_4_title))
            .assertIsDisplayed()
        // Review patch P5 — assert card-4 BODY too (same rationale).
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_4_body))
            .assertIsDisplayed()

        // Skip on card 4 → count = 2.
        composeTestRule.onNodeWithText(str(R.string.tutorial_cta_skip))
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals(2, callbacks.skipCount)
        // onComplete should still be zero — Skip on card 4 must not invoke
        // the "Got it" callback.
        assertEquals(0, callbacks.completeCount)
        assertEquals(0, callbacks.activateCount)
    }

    @Test
    fun font_scale_1_5_does_not_truncate_card_2_body_or_cta() {
        val callbacks = CapturedCallbacks()

        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density,
                    fontScale = LARGE_FONT_SCALE,
                ),
            ) {
                VeriSphereTheme {
                    OnboardingTutorialOverlay(
                        accessibilityServiceEnabled = true,
                        onActivateAccessibilityClick = { callbacks.activateCount += 1 },
                        onComplete = { callbacks.completeCount += 1 },
                        onSkip = { callbacks.skipCount += 1 },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // Auto-advance lands on card 2 — assert title, body, both CTAs visible.
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_2_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.tutorial_card_2_body))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.tutorial_cta_next))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.tutorial_cta_skip))
            .assertIsDisplayed()

        // Truncation marker (NFR14 lock-in — same pattern as DetailPanelContentUiTest).
        composeTestRule.onAllNodesWithText("…", substring = true)
            .assertCountEquals(0)
    }

    /**
     * Mutable counter holder for callback assertions. Same shape as
     * `DetailPanelContentUiTest.CapturedHolder` — one fresh instance per
     * test method so state never leaks across tests.
     */
    private class CapturedCallbacks {
        var activateCount: Int = 0
        var completeCount: Int = 0
        var skipCount: Int = 0
    }

    private companion object {
        const val LARGE_FONT_SCALE: Float = 1.5f
    }
}
