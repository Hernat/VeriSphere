package com.verisphere.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verisphere.app.bubble.buildOpenHistoryIntent
import com.verisphere.app.storage.HistoryRepositoryImpl
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Story 4.3 — Compose UI coverage for the empty-history case of the
 * [com.verisphere.app.ui.history.HistoryScreenIntent.ACTION_OPEN] arm.
 *
 * **Why a separate class** — the [MainActivityHistoryActionTest] sibling
 * class seeds the production `HistoryRepository` in `@Before`, which
 * would mask the empty-state assertion. Splitting into a dedicated
 * class avoids per-test conditional fixture seeding (cleaner, matches
 * V1 simplicity posture).
 *
 * The `@Before` here ACTIVELY clears any persisted history (in case
 * a prior test class left residue in `SecureStorage`), then sends the
 * history-open Intent and asserts the empty-state placeholder
 * (`history_empty_placeholder` testTag from Story 4.1 / 4.2) is
 * displayed.
 *
 * **Caveat — in-memory cache**: the shared `HistoryRepositoryImpl`
 * singleton retains appended records in its in-memory cache for the
 * lifetime of the instrumentation process. If the prior test class
 * (e.g. [MainActivityDetailPanelTest]) appended records, this class's
 * `@Before` `clear(KEY_HISTORY)` removes them from disk but the cache
 * still serves them on `observe()`. The empty-state assertion may
 * therefore flake on test ORDER. Mitigation: this assertion uses the
 * structural `testTag("history_empty_placeholder")` which renders only
 * when `HistoryUiState.Empty` — if the cache returns records, the test
 * legitimately fails and surfaces a fixture-isolation issue. **Run
 * this class first in the suite** (`testInstrumentationRunnerArguments` +
 * `class:` filter) when verifying the empty-state behaviour
 * specifically.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityHistoryActionEmptyTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        MainActivity.bypassGatesForTest = true
        // Aggressively clear any persisted history before launch.
        // `SecureStorage.clear` is non-suspend; no `runBlocking` needed
        // (matches the sibling MainActivityHistoryActionTest.tearDown
        // pattern).
        val container = appContainer()
        container.secureStorage.clear(HistoryRepositoryImpl.KEY_HISTORY)
    }

    @After
    fun tearDown() {
        val container = appContainer()
        container.secureStorage.clear(HistoryRepositoryImpl.KEY_HISTORY)
    }

    @Test
    fun idle_tap_intent_with_empty_history_renders_empty_placeholder() {
        // Send the history-open Intent on the freshly-launched activity.
        composeTestRule.activity.runOnUiThread {
            val context = ApplicationProvider.getApplicationContext<VeriSphereApplication>()
            composeTestRule.activity.startActivity(buildOpenHistoryIntent(context))
        }
        composeTestRule.waitForIdle()

        // Empty-state placeholder is visible (Story 4.1 / 4.2 contract
        // — testTag set on the centred Column containing the glyph +
        // verbatim copy).
        composeTestRule.onNodeWithTag("history_empty_placeholder").assertIsDisplayed()
    }

    private fun appContainer(): AppContainer {
        val app = ApplicationProvider.getApplicationContext<VeriSphereApplication>()
        return app.container
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            MainActivity.bypassGatesForTest = true
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            MainActivity.bypassGatesForTest = false
        }
    }
}
