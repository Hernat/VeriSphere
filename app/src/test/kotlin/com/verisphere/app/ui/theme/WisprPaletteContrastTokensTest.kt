package com.verisphere.app.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * Epic 8 Story 8.1 — WCAG 2.1 AA contrast validation for the new Wispr
 * Flow inspired soft-pastel verdict palette.
 *
 * Sibling to [com.verisphere.app.bubble.ui.FlashTooltipContrastTokensTest]
 * (Story 7.5 C13). That test guards the per-state on-colour `@ColorRes`
 * MAPPING (which token routes to which); THIS test guards the
 * underlying HEX VALUES — i.e. that the new soft-pastel backgrounds in
 * `values/colors.xml` + `values-night/colors.xml` paired with their
 * `vs_on_verdict_*_soft` foregrounds clear AA body-text contrast (≥
 * 4.5:1) per WCAG 2.1.
 *
 * Robustness: hex values are parsed straight from the on-disk
 * `colors.xml` resources, NOT hard-coded in the test. If a developer
 * adjusts a soft-pastel pair in the XML and breaks AA without updating
 * here, this test fails immediately at the offending pair.
 *
 * Failure UX: each assertion prints the offending pair, computed
 * ratio, and the expected floor — so review remediation is unambiguous.
 */
class WisprPaletteContrastTokensTest {

    private data class Pair(
        val name: String,
        val bgHex: String,
        val fgHex: String,
    )

    @Test
    fun `light variant — all soft-pastel pairs clear WCAG AA body text 4_5 to 1`() {
        val pairs = pairsFor(loadColors("app/src/main/res/values/colors.xml"))
        assertEachClearsAA(pairs, variant = "light")
    }

    @Test
    fun `dark variant — all soft-pastel pairs clear WCAG AA body text 4_5 to 1`() {
        val pairs = pairsFor(loadColors("app/src/main/res/values-night/colors.xml"))
        assertEachClearsAA(pairs, variant = "dark")
    }

    @Test
    fun `accent_sage_deep on canvas — AA body 4_5 to 1 (light + dark)`() {
        // Code-review F4 (Group B) — floor raised from 3:1 (AA-large) to
        // 4.5:1 (AA-body) because the actual usage of accent_sage_deep
        // includes M3 colorScheme.primary contentColor on `TextButton`
        // labels (12sp labelTrackedSans, well below the 18sp large-text
        // threshold). Floor was previously 3:1 which silently accepted
        // the 4.46:1 light-mode ratio for sage_deep #4E7B5E on
        // canvas — now darkened to #44704F (≥ 4.5:1).
        // Code-review F25 (Group B) — dark mode also exercised.
        listOf("light" to "values", "dark" to "values-night").forEach { (label, dir) ->
            val colors = loadColors("app/src/main/res/$dir/colors.xml")
            val canvas = colors.requireHex("vs_canvas")
            val sageDeep = colors.requireHex("vs_accent_sage_deep")
            val ratio = contrastRatio(canvas, sageDeep)
            assertTrue(
                "[$label] accent_sage_deep ($sageDeep) on canvas ($canvas) = " +
                    "${"%.2f".format(ratio)}:1 — below AA-body floor 4.5:1.",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `ink_soft on paper — AA body 4_5 to 1 (light + dark)`() {
        // Code-review F3 + F25 (Group B) — `inkSoft` is consumed by
        // HistoryItemRow timestamps + HistoryScreen subtitle at 12sp
        // tracked (body-sized per WCAG large-text threshold). Both
        // themes must clear AA body.
        listOf("light" to "values", "dark" to "values-night").forEach { (label, dir) ->
            val colors = loadColors("app/src/main/res/$dir/colors.xml")
            val paper = colors.requireHex("vs_paper")
            val inkSoft = colors.requireHex("vs_ink_soft")
            val ratio = contrastRatio(paper, inkSoft)
            assertTrue(
                "[$label] ink_soft ($inkSoft) on paper ($paper) = " +
                    "${"%.2f".format(ratio)}:1 — below AA-body floor 4.5:1.",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `accent_pulse on_accent_pulse — AA body 4_5 to 1 (light + dark)`() {
        // Code-review F8 (Group B) — AgreementBadgeRow Disagree
        // renders on accent_pulse with on_accent_pulse text. Both
        // themes must clear AA body.
        listOf("light" to "values", "dark" to "values-night").forEach { (label, dir) ->
            val colors = loadColors("app/src/main/res/$dir/colors.xml")
            val bg = colors.requireHex("vs_accent_pulse")
            val fg = colors.requireHex("vs_on_accent_pulse")
            val ratio = contrastRatio(bg, fg)
            assertTrue(
                "[$label] on_accent_pulse ($fg) on accent_pulse ($bg) = " +
                    "${"%.2f".format(ratio)}:1 — below AA-body floor 4.5:1.",
                ratio >= 4.5,
            )
        }
    }

    // ─── Pair enumeration (4 verdict softs + their on-colours) ──────

    private fun pairsFor(c: Map<String, String>): List<Pair> = listOf(
        Pair(
            "verdict_true_soft",
            c.requireHex("vs_verdict_true_soft"),
            c.requireHex("vs_on_verdict_true_soft"),
        ),
        Pair(
            "verdict_false_soft",
            c.requireHex("vs_verdict_false_soft"),
            c.requireHex("vs_on_verdict_false_soft"),
        ),
        Pair(
            "verdict_doubtful_soft",
            c.requireHex("vs_verdict_doubtful_soft"),
            c.requireHex("vs_on_verdict_doubtful_soft"),
        ),
        Pair(
            "verdict_non_verifiable_soft",
            c.requireHex("vs_verdict_non_verifiable_soft"),
            c.requireHex("vs_on_verdict_non_verifiable_soft"),
        ),
        Pair(
            "accent_sage / on_accent_sage",
            c.requireHex("vs_accent_sage"),
            c.requireHex("vs_on_accent_sage"),
        ),
    )

    private fun assertEachClearsAA(pairs: List<Pair>, variant: String) {
        val floorAa = 4.5
        val failures = mutableListOf<String>()
        for (p in pairs) {
            val ratio = contrastRatio(p.bgHex, p.fgHex)
            if (ratio < floorAa) {
                failures += "[$variant] ${p.name}: bg=${p.bgHex} fg=${p.fgHex} → " +
                    "${"%.2f".format(ratio)}:1 (< ${floorAa}:1 AA body floor)"
            }
        }
        if (failures.isNotEmpty()) {
            fail(
                "WCAG AA contrast regression in Epic 8 Story 8.1 palette:\n" +
                    failures.joinToString("\n"),
            )
        }
    }

    // ─── Hex parsing + WCAG 2.1 contrast computation ────────────────

    /**
     * Code-review F26 (Group B) — robust path resolution. Tests can run
     * from the module dir (`app/`), the repo root, or under varying
     * Gradle invocations (`./gradlew :app:test`, IDE green-bug, CI
     * `--continue` from repo root). Walk up the parent chain from `cwd`
     * until the requested path resolves, or fail with a clear message.
     */
    private fun loadColors(relativePath: String): Map<String, String> {
        var cursor: File? = File(".").canonicalFile
        var depth = 0
        while (cursor != null && depth < CWD_WALK_LIMIT) {
            val candidate = File(cursor, relativePath)
            if (candidate.exists()) return parseColors(candidate.readText())
            cursor = cursor.parentFile
            depth++
        }
        error("colors.xml not found at $relativePath (walked $depth dirs up from ${File(".").canonicalPath})")
    }

    private fun parseColors(xml: String): Map<String, String> {
        // <color name="vs_xyz">#AABBCC</color>
        val regex = Regex("""<color\s+name="([^"]+)"\s*>\s*(#[0-9A-Fa-f]{6,8})\s*</color>""")
        return regex.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun Map<String, String>.requireHex(name: String): String =
        this[name] ?: error("Missing color resource: $name")

    /**
     * WCAG 2.1 relative-luminance computation. Input hex must be #RRGGBB
     * or #AARRGGBB (alpha ignored for the contrast computation — text
     * is rendered opaque on opaque backgrounds in VeriSphere).
     */
    internal fun relativeLuminance(hex: String): Double {
        val rgb = parseRgb(hex)
        val rLin = channelLinear(rgb[0] / 255.0)
        val gLin = channelLinear(rgb[1] / 255.0)
        val bLin = channelLinear(rgb[2] / 255.0)
        return 0.2126 * rLin + 0.7152 * gLin + 0.0722 * bLin
    }

    private fun channelLinear(c: Double): Double =
        if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    internal fun contrastRatio(hexA: String, hexB: String): Double {
        val la = relativeLuminance(hexA)
        val lb = relativeLuminance(hexB)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun parseRgb(hex: String): IntArray {
        val cleaned = hex.removePrefix("#")
        val rgb = when (cleaned.length) {
            6 -> cleaned
            8 -> cleaned.substring(2) // drop alpha
            else -> error("Unexpected hex length: $hex")
        }
        return intArrayOf(
            rgb.substring(0, 2).toInt(16),
            rgb.substring(2, 4).toInt(16),
            rgb.substring(4, 6).toInt(16),
        )
    }

    private companion object {
        /** Code-review F26 — bound the cwd-walk so a misconfigured
         *  test environment fails loudly rather than walking to `/`. */
        private const val CWD_WALK_LIMIT: Int = 6
    }
}
