package com.verisphere.app.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.verisphere.app.ui.theme.VeriSphereTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * Story 2.2 — Compose UI coverage for [AnchoredDetailPanel] visibility,
 * dismiss-via-back-gesture, and dismiss-via-swipe-down. Test method
 * names use underscore_snake_case per existing androidTest convention
 * ([`SourceLinkChipUiTest`](./SourceLinkChipUiTest.kt)) and the DEX < 040
 * constraint at minSdk 30 (deferred-work line 91).
 *
 * **Why no scrim-tap test:** M3 1.3's `ModalBottomSheet` does NOT expose
 * a stable testTag on the internal scrim, and `composeTestRule.onRoot()`
 * in this BOM version traverses the host Activity's tree rather than
 * the Dialog's window — tapping at `Offset(50f, 50f)` targets the
 * activity background, not the scrim. Coverage shifts to swipe-down
 * dismissal (which exercises the same `onDismissRequest` callback path).
 * Back-press dismissal is covered separately. Documented in `deferred-
 * work.md` as a spec-author follow-up: the four-test count in AC #13
 * remains met (visibility on/off + back-press + swipe-down).
 *
 * Tests use [AnchoredDetailPanelImpl] (the internal seam) rather than
 * the public [AnchoredDetailPanel] so we can drive both `BuildConfig`
 * paths from the test without rebuilding. Production behaviour binds
 * to `BuildConfig.USE_STANDARD_BOTTOM_SHEET` — not exercised here; the
 * dispatch logic is covered by the JVM unit tests on
 * [computeEmergenceEdge] + this file's coverage of [AnchoredDetailPanelImpl].
 */
class AnchoredDetailPanelUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun panel_is_invisible_when_isvisible_is_false() {
        composeTestRule.setContent {
            VeriSphereTheme {
                AnchoredDetailPanelImpl(
                    useStandardBottomSheet = true,
                    edge = EmergenceEdge.BOTTOM,
                    isVisible = false,
                    onDismiss = {},
                ) {
                    Text(text = PLACEHOLDER_TEXT)
                }
            }
        }

        composeTestRule
            .onNodeWithText(PLACEHOLDER_TEXT)
            .assertDoesNotExist()
    }

    @Test
    fun panel_renders_placeholder_content_when_isvisible_is_true() {
        composeTestRule.setContent {
            VeriSphereTheme {
                AnchoredDetailPanelImpl(
                    useStandardBottomSheet = true,
                    edge = EmergenceEdge.BOTTOM,
                    isVisible = true,
                    onDismiss = {},
                ) {
                    Text(text = PLACEHOLDER_TEXT)
                }
            }
        }

        composeTestRule
            .onNodeWithText(PLACEHOLDER_TEXT)
            .assertIsDisplayed()
    }

    @Test
    fun back_press_invokes_on_dismiss_lambda() {
        lateinit var dismissedState: MutableState<Boolean>

        composeTestRule.setContent {
            dismissedState = remember { mutableStateOf(false) }
            VeriSphereTheme {
                AnchoredDetailPanelImpl(
                    useStandardBottomSheet = true,
                    edge = EmergenceEdge.BOTTOM,
                    isVisible = !dismissedState.value,
                    onDismiss = { dismissedState.value = true },
                ) {
                    Text(text = PLACEHOLDER_TEXT)
                }
            }
        }

        // Sanity: the panel is initially shown.
        composeTestRule
            .onNodeWithText(PLACEHOLDER_TEXT)
            .assertIsDisplayed()
        assertFalse(dismissedState.value)

        // M3 ModalBottomSheet's internal Dialog routes back-press through
        // its own onBackPressedDispatcher → ModalBottomSheet.onDismissRequest.
        // Espresso.pressBack() simulates the same gesture path the OS uses.
        Espresso.pressBack()

        // Wait for the dismiss callback to flip our state. The sheet's
        // animation can take 300+ ms on cold-boot AVDs. waitUntil throws
        // ComposeTimeoutException on timeout — no trailing assertEquals
        // needed (review F6).
        composeTestRule.waitUntil(timeoutMillis = 5_000) { dismissedState.value }
    }

    @Test
    fun swipe_down_invokes_on_dismiss_lambda() {
        lateinit var dismissedState: MutableState<Boolean>

        composeTestRule.setContent {
            dismissedState = remember { mutableStateOf(false) }
            VeriSphereTheme {
                AnchoredDetailPanelImpl(
                    useStandardBottomSheet = true,
                    edge = EmergenceEdge.BOTTOM,
                    isVisible = !dismissedState.value,
                    onDismiss = { dismissedState.value = true },
                ) {
                    // Wrap in a tall Box so the swipe gesture has enough
                    // distance to exceed M3's velocity threshold (review F7).
                    Box(modifier = Modifier.heightIn(min = 400.dp)) {
                        Text(text = PLACEHOLDER_TEXT)
                    }
                }
            }
        }

        // Sanity: the panel is initially shown.
        composeTestRule
            .onNodeWithText(PLACEHOLDER_TEXT)
            .assertIsDisplayed()

        // Swipe down on the sheet content — M3 ModalBottomSheet's stock
        // SheetState.confirmValueChange handles the gesture and routes
        // through onDismissRequest when the velocity / distance threshold
        // is exceeded. Explicit start/end Y + short duration (review F7) so
        // the gesture is unambiguously fast + far enough on real AVDs.
        composeTestRule
            .onNodeWithText(PLACEHOLDER_TEXT)
            .performTouchInput {
                swipeDown(startY = 0f, endY = 800f, durationMillis = 100)
            }

        composeTestRule.waitUntil(timeoutMillis = 5_000) { dismissedState.value }
    }

    @Test
    fun anchored_left_path_renders_content_when_visible() {
        // F3 — exercise the useStandardBottomSheet = false code path so the
        // anchored-slide branch in AnchoredDetailPanelImpl + the helper
        // Modifier.anchoredHorizontalSlide are instrumented at least once.
        // The other four tests cover the standard path; without this case
        // the F1 animation bug (Approach A's slide never plays) shipped
        // unnoticed by the test harness.
        composeTestRule.setContent {
            VeriSphereTheme {
                AnchoredDetailPanelImpl(
                    useStandardBottomSheet = false,
                    edge = EmergenceEdge.LEFT,
                    isVisible = true,
                    onDismiss = {},
                ) {
                    Text(text = PLACEHOLDER_TEXT)
                }
            }
        }

        composeTestRule
            .onNodeWithText(PLACEHOLDER_TEXT)
            .assertIsDisplayed()
    }

    private companion object {
        const val PLACEHOLDER_TEXT = "Detail panel content placeholder"
    }
}
