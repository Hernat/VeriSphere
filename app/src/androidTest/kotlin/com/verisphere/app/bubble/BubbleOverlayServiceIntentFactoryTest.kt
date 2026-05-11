package com.verisphere.app.bubble

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verisphere.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Story 2.4 — androidTest coverage for [buildDetailPanelIntent]. Lives
 * under `androidTest/` (NOT `test/`) because the JVM Android-stub at
 * `unitTests.isReturnDefaultValues = true` returns default values from
 * `Intent.getStringExtra` / `Intent.getIntExtra` / `Intent.getFlags`,
 * which would silently neuter the assertions. Same root cause as
 * Story 2.1's `buildSourceLinkIntent` test split documented in
 * `deferred-work.md` line 45.
 *
 * Three assertion families (each a separate test method per the
 * one-assertion-cluster-per-method convention):
 *  1. `setAction(Intent.ACTION_VIEW)` produces the expected action.
 *  2. `putExtra(EXTRA_SESSION_ID, ...)` and `putExtra(EXTRA_BUBBLE_ANCHOR_X_PX, ...)`
 *     round-trip through `getStringExtra` / `getIntExtra`.
 *  3. `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP` are both set.
 *
 * Method names use underscore_snake_case per the DEX < 040 fallback +
 * Story 2.3 precedent.
 */
@RunWith(AndroidJUnit4::class)
class BubbleOverlayServiceIntentFactoryTest {

    @Test
    fun build_detail_panel_intent_sets_action_view() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = buildDetailPanelIntent(
            context = context,
            sessionId = "fixture-id",
            bubbleAnchorXPx = 123,
        )

        assertEquals(Intent.ACTION_VIEW, intent.action)
    }

    @Test
    fun build_detail_panel_intent_carries_session_id_and_anchor_x_extras() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = buildDetailPanelIntent(
            context = context,
            sessionId = "fixture-id",
            bubbleAnchorXPx = 123,
        )

        assertEquals("fixture-id", intent.getStringExtra(EXTRA_SESSION_ID))
        assertEquals(123, intent.getIntExtra(EXTRA_BUBBLE_ANCHOR_X_PX, -1))
    }

    @Test
    fun build_detail_panel_intent_carries_new_task_and_single_top_flags() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = buildDetailPanelIntent(
            context = context,
            sessionId = "fixture-id",
            bubbleAnchorXPx = 0,
        )

        assertNotEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertNotEquals(0, intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    @Test
    fun build_detail_panel_intent_targets_main_activity() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = buildDetailPanelIntent(
            context = context,
            sessionId = "fixture-id",
            bubbleAnchorXPx = 0,
        )

        // The explicit component routes to MainActivity (manifest's
        // singleTop launchMode + the SINGLE_TOP flag combine to deliver
        // via onNewIntent if MainActivity is already in the task).
        val component = intent.component
        assertTrue(
            "component should target MainActivity, got: $component",
            component != null && component.className == MainActivity::class.java.name,
        )
    }
}
