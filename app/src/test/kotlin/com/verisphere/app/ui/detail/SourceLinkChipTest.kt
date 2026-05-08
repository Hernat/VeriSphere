package com.verisphere.app.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Story 2.1 — JVM unit coverage for [extractDisplayDomain] (architecture
 * line 426: backtick English-sentence test names; JVM only).
 *
 * **Why this test class only covers `extractDisplayDomain` and not
 * `buildSourceLinkIntent`:** the chip's Intent factory was originally
 * spec'd to ship JVM unit tests asserting `intent.action`, `intent.flags`,
 * and `intent.dataString` (Story 2.1 AC #11 + Task 3). In practice those
 * accessors return `null` / `0` on the Android JVM stub
 * (`unitTests.isReturnDefaultValues = true`, [`build.gradle.kts:146`](../../../../../../../app/build.gradle.kts))
 * — the same JVM-stub limitation that forced the `base64Encoder`
 * constructor seam in Story 1.9 ([`GeminiClient.kt:524-525`](../../../../main/kotlin/com/verisphere/app/gemini/GeminiClient.kt)).
 * Intent-factory coverage therefore lives in
 * [`SourceLinkChipUiTest`](../../../../androidTest/kotlin/com/verisphere/app/ui/detail/SourceLinkChipUiTest.kt)'s
 * `buildSourceLinkIntent_*` tests, which run in the instrumented runtime
 * where `Intent` accessors return real values.
 *
 * The composable itself is exercised via `@Preview` (Story 1.10
 * precedent — [`FlashTooltip.kt`](../../../../main/kotlin/com/verisphere/app/bubble/ui/FlashTooltip.kt))
 * and the androidTest. Neither is duplicated here.
 */
class SourceLinkChipTest {

    @Test
    fun `extractDisplayDomain strips www prefix from a canonical URL`() {
        val domain = extractDisplayDomain("https://www.bbc.com/news/world-12345")

        assertEquals("bbc.com", domain)
    }

    @Test
    fun `extractDisplayDomain returns the host unchanged when www is absent`() {
        val domain = extractDisplayDomain("https://bbc.com/news/world-12345")

        assertEquals("bbc.com", domain)
    }

    @Test
    fun `extractDisplayDomain preserves multi-level subdomains other than www`() {
        val domain = extractDisplayDomain("https://news.bbc.co.uk/article/42")

        // Only the leading 'www.' label is stripped — every other
        // subdomain is editorial signal worth showing on the chip.
        assertEquals("news.bbc.co.uk", domain)
    }

    @Test
    fun `extractDisplayDomain returns the host when URL carries query and fragment`() {
        val domain = extractDisplayDomain("https://www.lemonde.fr/article/42?utm_source=verisphere#section-3")

        assertEquals("lemonde.fr", domain)
    }

    @Test
    fun `extractDisplayDomain falls back to the raw input on a malformed URL`() {
        // Spaces are invalid in URI syntax and trigger
        // URISyntaxException — the runCatching wrapper catches it and we
        // return the raw input so the chip still renders SOMETHING the
        // user can scan rather than crashing the parent layout.
        val domain = extractDisplayDomain("not a url with spaces")

        assertEquals("not a url with spaces", domain)
    }

    @Test
    fun `extractDisplayDomain falls back to the raw input on an empty string`() {
        val domain = extractDisplayDomain("")

        assertEquals("", domain)
    }

    @Test
    fun `extractDisplayDomain lowercases the host before stripping www`() {
        // java.net.URI preserves the original case of the host (RFC 3986
        // §3.2.2 says hosts are case-insensitive but Java's URI does NOT
        // normalise). Without the lowercase() call in the helper, an
        // uppercase `WWW.BBC.COM` would slip past the prefix-strip and
        // render as `WWW.BBC.COM` on the chip — code-review patch P2.
        val uppercaseHost = extractDisplayDomain("https://WWW.BBC.COM/news/world-12345")
        assertEquals("bbc.com", uppercaseHost)

        val mixedCaseHost = extractDisplayDomain("https://Www.LeMonde.FR/article/42")
        assertEquals("lemonde.fr", mixedCaseHost)
    }
}
