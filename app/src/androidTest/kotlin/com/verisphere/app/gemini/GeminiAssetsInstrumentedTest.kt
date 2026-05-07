package com.verisphere.app.gemini

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for the system prompt asset shipping (code-review
 * patch P8).
 *
 * Runs on a real Android device / emulator because Android-side
 * `AssetManager.list("")` requires the actual APK assets to be packaged.
 *
 * Without this test, a future shrinker rule, `aaptOptions { noCompress }`
 * change, or `androidResources { noCompress = ['txt'] }` regression
 * could exclude `.txt` files from `assets/` — `GeminiClient.verify`
 * would then `FileNotFoundException` on first call → caught as `IOException`
 * → mapped to `Failure.HttpError(0)`. The user-visible UX is "verify
 * always fails" with no specific signal — a build-time fault hidden as
 * a runtime opaque error. This test fires at install time and surfaces
 * the missing asset before code-review approval.
 *
 * Test method naming: same convention as [`SecureStorageInstrumentedTest`]
 * (Story 1.4 + architecture line 426 with the DEX-< 040 fallback). Backtick
 * quotes preserved; spaces replaced by `_` to satisfy D8's restriction
 * on method `SimpleName`s prior to DEX version 040.
 */
@RunWith(AndroidJUnit4::class)
class GeminiAssetsInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun system_prompt_v1_asset_is_packaged_in_apk() {
        val assets = context.assets.list("") ?: emptyArray()
        assertTrue(
            "Expected system_prompt_v1.txt in APK assets/, found ${assets.toList()}",
            assets.contains("system_prompt_v1.txt"),
        )
    }

    @Test
    fun system_prompt_v1_first_line_is_versioning_header() {
        // Architecture D2.4 mandates the versioning header so future
        // bumps are explicit code changes, not asset mutations. If a
        // future maintainer accidentally renames the file, drops the
        // header, or saves with a UTF-8 BOM (Windows Notepad default),
        // this test catches it before it reaches a release.
        val firstLine = context.assets.open("system_prompt_v1.txt")
            .bufferedReader()
            .use { it.readLine() }
        assertEquals(
            "First line MUST be the versioning header (D2.4)",
            "# System prompt v1 — VeriSphere fact-check / anti-injection",
            firstLine,
        )
    }

    @Test
    fun system_prompt_v1_is_non_empty_and_under_budget() {
        val text = context.assets.open("system_prompt_v1.txt")
            .bufferedReader()
            .use { it.readText() }
        assertTrue("Asset must be non-empty", text.isNotBlank())
        // Sanity bound — AC #7 budget is ≤ 600 words / ≤ 6 KB. We assert
        // the byte size only because word counting on JVM differs from
        // `wc -w`; the byte budget is a stable proxy.
        val sizeBytes = text.toByteArray(Charsets.UTF_8).size
        assertTrue(
            "Asset size $sizeBytes bytes exceeds 6 KB budget",
            sizeBytes in 1..6_144,
        )
    }
}
