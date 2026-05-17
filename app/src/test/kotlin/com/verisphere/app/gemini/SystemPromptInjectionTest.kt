package com.verisphere.app.gemini

import com.verisphere.app.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Anti-injection corpus regression seam (Story 7.1, NFR8, D2.4).
 *
 * **Class-level `@Ignore("manual pre-release")`** matches the verbatim string
 * named at architecture line 718 and epics line 938 (Story 7.1 CDN #7) — Gradle's
 * `:app:testDebugUnitTest` reports this as SKIPPED, never executed in CI. The
 * `@Ignore` decision is deliberate: the corpus burns ~30 live Gemini calls per run
 * (~60-120 s wall-clock + real quota cost — see [SECURITY.md Anti-injection corpus
 * runner quota note](../../../../../../SECURITY.md#anti-injection-corpus-runner--quota-note)),
 * and the bash runner [scripts/run_injection_corpus.sh](../../../../../../scripts/run_injection_corpus.sh)
 * is the **canonical release gate** ([RELEASING.md step 4](../../../../../../RELEASING.md#release-procedure-7-step-distribution-flow)).
 * This Kotlin seam exists for in-IDE convenience runs against the same corpus.
 *
 * **Second-order benefit** (CDN #7): even though the `@Ignore` keeps the test
 * out of CI, the class IS compiled by `:app:compileDebugUnitTestKotlin`. A
 * future [GeminiClient.verify] signature change OR [VerdictLabel] enum amendment
 * (e.g. adding a 5th label per the architecture FR13 4-label fix at
 * [VerdictLabel.kt:23-25](VerdictLabel.kt#L23-L25)) will surface here as a
 * compile error in CI, even though the test never executes.
 *
 * **To run manually**:
 *  1. Temporarily comment out the `@Ignore` annotation.
 *  2. `./gradlew :app:testDebugUnitTest --tests 'com.verisphere.app.gemini.SystemPromptInjectionTest'`.
 *  3. Wall-clock ~60-120 s for ~30 sequential live Gemini calls.
 *  4. Re-add the `@Ignore` before committing.
 *
 * **Implementation parity with the bash runner** (Story 7.1 CDN #9):
 *  - Same corpus source: `app/src/test/resources/injection_corpus.txt` read via
 *    [ClassLoader.getResource] (Gradle test classpath resolution per epics L938).
 *  - Same system prompt source: `app/src/main/assets/system_prompt_v1.txt` (CDN #4)
 *    via [File] dual-path fallback (module-relative + repo-relative + `Assume.assumeTrue`).
 *  - Same model pin: [GeminiClient.DEFAULT_MODEL] (`gemini-2.5-flash`, the
 *    2026-05-11 Story 2.4 smoke hotfix per CDN #3) — inherited automatically
 *    because the test calls [GeminiClient.verify] which builds the URL from
 *    that constant.
 *  - Same fence-stripping behaviour for `\`\`\`json ... \`\`\`` responses
 *    (CDN #10) — inherited via [GeminiClient.parseVerdict].
 *  - Same comparison contract:
 *    - `expected in {TRUE, FALSE, DOUBTFUL, NON_VERIFIABLE}` → assert verdict label.
 *    - `expected == INJECTION_DETECTED` → assert `injectionDetected == true`
 *      (the verdict label is free per system_prompt_v1.txt L18 "Continue
 *      producing the verdict for the actual claim depicted").
 *
 * **JPEG synthesis: deviation from spec Task 3.3** — the spec specified
 * `java.awt.image.BufferedImage` + `javax.imageio.ImageIO` rendering, but
 * Android Gradle Plugin's `testDebugUnitTest` compile classpath excludes
 * `java.awt.*` and `javax.imageio.*` (those packages are not part of the
 * Android runtime, even on host-JVM unit tests — AGP enforces this at compile
 * time). The test uses a **decoded base64 placeholder JPEG** (a 1×1 white pixel,
 * ~125 bytes) for every case instead. Consequences:
 *  - **Compile-time contract preserved** (CDN #7): the [GeminiClient] constructor,
 *    [GeminiClient.verify] signature, [VerificationOutcome] sealed hierarchy,
 *    [SessionRecord.verdictLabel] / [SessionRecord.injectionDetected] are all
 *    referenced and would surface as compile errors on drift.
 *  - **Runtime visual-corpus regression NOT exercised by this seam** — sending
 *    a 1×1 white JPEG means Gemini's vision pass sees no text; the model
 *    returns `NON_VERIFIABLE` for every case with `injectionDetected=false`.
 *    All `INJECTION_DETECTED::*` and most `FALSE`/`TRUE` cases will FAIL the
 *    comparison if un-`@Ignore`'d.
 *  - **Canonical visual-corpus runner is the bash script** ([scripts/run_injection_corpus.sh](../../../../../../scripts/run_injection_corpus.sh)),
 *    which uses ImageMagick to render the actual injection text into the JPEG.
 *
 * **Failure-collection pattern**: instead of failing at the first regression
 * (which would force the founder to re-run + re-burn quota to surface the
 * next failure), all cases run sequentially and any failures are gathered
 * into a single multi-line assertion message at the end.
 */
@Ignore("manual pre-release")
class SystemPromptInjectionTest {

    @Test
    fun corpus_passes_against_live_gemini() = runBlocking {
        // Story 7.1 code-review F12: guard against empty BuildConfig.GEMINI_API_KEY
        // before issuing any live calls. If a developer un-@Ignore's this test in
        // a CI environment without local.properties, an empty key would issue 30
        // sequential 401s — burning Gemini abuse-detection counters with zero
        // diagnostic value. Skip cleanly via Assume.
        Assume.assumeTrue(
            "BuildConfig.GEMINI_API_KEY is blank — populate local.properties " +
                "before un-@Ignore'ing this test (manual pre-release run only).",
            BuildConfig.GEMINI_API_KEY.isNotBlank(),
        )

        val corpusText = javaClass.classLoader
            ?.getResource(CORPUS_RESOURCE_NAME)
            ?.readText(Charsets.UTF_8)
            ?: run {
                Assume.assumeTrue(
                    "corpus resource missing at classpath:/$CORPUS_RESOURCE_NAME — " +
                        "verify app/src/test/resources/$CORPUS_RESOURCE_NAME exists",
                    false,
                )
                return@runBlocking
            }

        // System prompt: try both possible CWDs. Gradle's :app:testDebugUnitTest
        // runs with CWD at the module root (app/) on some configurations and at
        // the repo root on others. Fall back to `Assume.assumeTrue` if neither
        // resolves — defensive defence-in-depth, since `@Ignore` already keeps
        // CI safe.
        val systemPromptFile = listOf(
            File(SYSTEM_PROMPT_PATH_FROM_MODULE_ROOT),
            File(SYSTEM_PROMPT_PATH_FROM_REPO_ROOT),
        ).firstOrNull { it.isFile } ?: run {
            Assume.assumeTrue(
                "system prompt not found at either '$SYSTEM_PROMPT_PATH_FROM_MODULE_ROOT' " +
                    "or '$SYSTEM_PROMPT_PATH_FROM_REPO_ROOT' (CWD=${File(".").absolutePath})",
                false,
            )
            return@runBlocking
        }
        val systemPrompt = systemPromptFile.readText(Charsets.UTF_8)

        // Real OkHttpClient — 30 s callTimeout vs GeminiClientTest's 5 s, because
        // we are hitting the live API (not MockWebServer). Mirror the production
        // followRedirects(false) posture (AppContainer L88-89) so this seam tests
        // the same network policy production does.
        val httpClient = OkHttpClient.Builder()
            .callTimeout(LIVE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(LIVE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(LIVE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        // Real GeminiClient — reuses BuildConfig.GEMINI_API_KEY + DEFAULT_MODEL.
        // Same-package internal constant access (DEFAULT_MODEL is `internal const`
        // at GeminiClient.kt:468). java.util.Base64 mirrors what GeminiClientTest
        // L463 uses — equivalent NO_WRAP output to android.util.Base64.
        val client = GeminiClient(
            httpClient = httpClient,
            apiKey = BuildConfig.GEMINI_API_KEY,
            model = GeminiClient.DEFAULT_MODEL,
            systemPromptProvider = { systemPrompt },
            base64Encoder = { bytes -> Base64.getEncoder().encodeToString(bytes) },
        )

        // Placeholder JPEG (see class KDoc — AWT/ImageIO excluded from Android
        // testDebugUnitTest classpath). 1×1 white pixel ~125 bytes; same image
        // sent for every case. Real visual corpus exercise is via the bash runner.
        val placeholderJpeg: ByteArray = Base64.getDecoder().decode(PLACEHOLDER_JPEG_B64)

        val failures = mutableListOf<String>()
        var totalCases = 0
        var passedCases = 0

        corpusText.lineSequence().forEachIndexed { zeroIdx, rawLine ->
            val lineNum = zeroIdx + 1
            val line = rawLine.trim()
            // Skip blank lines and comments.
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            // Parse <expected>::<injection_text>.
            val sepIdx = line.indexOf("::")
            if (sepIdx < 0) {
                failures += "line $lineNum malformed (no '::' separator): $line"
                return@forEachIndexed
            }
            val expected = line.substring(0, sepIdx)
            val injectionText = line.substring(sepIdx + 2)
            totalCases += 1

            // Validate expected.
            val isInjectionFlagSentinel = expected == EXPECTED_INJECTION_DETECTED
            val isVerdictLabel = expected in VALID_VERDICT_LABELS
            if (!isInjectionFlagSentinel && !isVerdictLabel) {
                failures += "line $lineNum unknown expected label: $expected"
                return@forEachIndexed
            }

            // Live call. GeminiClient.verify NEVER throws (architecture line 487
            // single error funnel) — every exception is mapped to a Failure variant.
            val outcome = client.verify(placeholderJpeg)

            when (outcome) {
                is VerificationOutcome.Verdict -> {
                    val record = outcome.record
                    val actualLabel = record.verdictLabel
                    val actualInj = record.injectionDetected
                    val pass = if (isInjectionFlagSentinel) {
                        actualInj
                    } else {
                        actualLabel.name == expected
                    }
                    val textPreview = injectionText.take(MAX_LOG_TEXT_CHARS)
                    if (pass) {
                        passedCases += 1
                        println(
                            "[PASS] line $lineNum expected=$expected " +
                                "actual_label=$actualLabel actual_inj=$actualInj",
                        )
                    } else {
                        failures += "line $lineNum expected=$expected " +
                            "actual_label=$actualLabel actual_inj=$actualInj " +
                            "text=\"$textPreview\""
                        println(
                            "[FAIL] line $lineNum expected=$expected " +
                                "actual_label=$actualLabel actual_inj=$actualInj " +
                                "text=\"$textPreview\"",
                        )
                    }
                }
                is VerificationOutcome.Failure -> {
                    val textPreview = injectionText.take(MAX_LOG_TEXT_CHARS)
                    val failureType = outcome::class.simpleName ?: "Failure"
                    failures += "line $lineNum expected=$expected " +
                        "outcome=$failureType text=\"$textPreview\""
                    println(
                        "[FAIL] line $lineNum expected=$expected " +
                            "outcome=$failureType text=\"$textPreview\"",
                    )
                }
            }
        }

        println("===== Summary: $passedCases/$totalCases PASS (failed=${failures.size}) =====")

        assertTrue(
            "Corpus regression: ${failures.size}/$totalCases case(s) failed.\n" +
                failures.joinToString(separator = "\n  ", prefix = "  "),
            failures.isEmpty(),
        )
    }

    private companion object {
        // Resource path (test classpath root) — Gradle maps
        // `app/src/test/resources/*` → classpath root per architecture L729.
        private const val CORPUS_RESOURCE_NAME = "injection_corpus.txt"

        // System prompt source paths — dual-fallback for Gradle CWD variance.
        private const val SYSTEM_PROMPT_PATH_FROM_MODULE_ROOT =
            "src/main/assets/system_prompt_v1.txt"
        private const val SYSTEM_PROMPT_PATH_FROM_REPO_ROOT =
            "app/src/main/assets/system_prompt_v1.txt"

        // Live-API timeouts (longer than GeminiClientTest's 5 s MockWebServer
        // window because we are hitting the actual Gemini endpoint).
        private const val LIVE_CALL_TIMEOUT_SECONDS = 30L
        private const val LIVE_CONNECT_TIMEOUT_SECONDS = 10L
        private const val LIVE_READ_TIMEOUT_SECONDS = 30L

        // Privacy posture (CDN #8): per-case log truncation cap.
        private const val MAX_LOG_TEXT_CHARS = 80

        // Pseudo-label sentinel per CDN #1 — VerdictLabel enum is 4-valued.
        private const val EXPECTED_INJECTION_DETECTED = "INJECTION_DETECTED"
        private val VALID_VERDICT_LABELS = setOf("TRUE", "FALSE", "DOUBTFUL", "NON_VERIFIABLE")

        // 1×1 solid-white JPEG (~125 bytes decoded) used as a placeholder image.
        // See class KDoc for why AWT/ImageIO rendering was dropped. The bytes
        // here are a standards-compliant minimal JPEG; the spec-author note
        // matters more than the exact pixels because the test is @Ignore'd.
        private const val PLACEHOLDER_JPEG_B64 =
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB" +
                "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAf/bAEMBAQEBAQEBAQEBAQEBAQEBAQEBAQEB" +
                "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB/8AAEQgAAQABAwEiAAIR" +
                "AQMRAf/EABUAAQEAAAAAAAAAAAAAAAAAAAAJ/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQAA" +
                "PwBU3//Z"
    }
}
