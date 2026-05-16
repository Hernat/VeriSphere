package com.verisphere.app.ui.banner

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Story 6.2 — androidTest coverage for [buildUpdateDownloadIntent].
 * Lives under `androidTest/` (NOT `test/`) because the JVM Android-stub
 * at `unitTests.isReturnDefaultValues = true` returns default values
 * from `Intent.getAction` / `Intent.getData` / `Uri.parse`, which
 * silently neuter the assertions. Same root cause as Story 2.4's
 * [com.verisphere.app.bubble.BubbleOverlayServiceIntentFactoryTest]
 * split.
 *
 * Method names use underscore_snake_case per the DEX < 040 fallback +
 * Story 2.4 precedent.
 */
@RunWith(AndroidJUnit4::class)
class UpdateBannerIntentFactoryTest {

    @Test
    fun build_update_download_intent_sets_action_view() {
        val intent = buildUpdateDownloadIntent("https://drive.google.com/file/d/1abc/view")

        assertEquals(Intent.ACTION_VIEW, intent.action)
    }

    @Test
    fun build_update_download_intent_preserves_url_as_data_verbatim() {
        // CDN #8 — the factory MUST NOT re-validate the URL.
        // VersionChecker patch P3 already gates non-https upstream;
        // duplicating validation here would create a maintenance
        // liability. This test locks the verbatim-passthrough contract.
        val url =
            "https://github.com/Hernat/VeriSphere/releases/download/v1.2.3/verisphere-1.2.3.apk"
        val intent = buildUpdateDownloadIntent(url)

        assertEquals(url, intent.data?.toString())
    }

    @Test
    fun build_update_download_intent_sets_flag_activity_new_task() {
        val intent = buildUpdateDownloadIntent("https://drive.google.com/x")

        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK,
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK,
        )
    }
}
