package com.verisphere.app.gemini

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Stateless request-body builder for Gemini's `generateContent` endpoint
 * (architecture D3.3 + D3.4).
 *
 * **Body shape** (verbatim from architecture D3.3):
 * ```json
 * {
 *   "contents": [
 *     {
 *       "role": "user",
 *       "parts": [
 *         { "text": "<systemPrompt>" },
 *         { "inlineData": { "mimeType": "image/jpeg", "data": "<base64>" } }
 *       ]
 *     }
 *   ],
 *   "tools": [ { "googleSearch": {} } ],
 *   "generationConfig": {
 *     "responseMimeType": "application/json",
 *     "responseJsonSchema": { ...derived from GeminiVerdictResponse... }
 *   }
 * }
 * ```
 *
 * **Boundary discipline** (architecture line 752): only consumed by
 * [GeminiClient]. `internal` visibility prevents accidental cross-package
 * usage; tests exercise the builder indirectly through `GeminiClient.verify`
 * (the boundary test is the request body assertion in `GeminiClientTest`'s
 * `MockWebServer.takeRequest()` step).
 *
 * **Threading**: the [build] function is pure / blocking-safe (no I/O,
 * no Android API calls). The CALLER (GeminiClient) wraps `build` in
 * `withContext(Dispatchers.Default)` because JSON encoding is CPU-bound
 * (architecture line 473). Base64 encoding of the image bytes ALSO happens
 * on the caller side — `build` accepts the already-encoded base64 string
 * so its body remains pure-Kotlin string-and-JsonObject manipulation.
 */
internal object GeminiRequest {

    /**
     * Assemble the JSON request body. The [base64Image] is the JPEG
     * frame already base64-encoded (`Base64.NO_WRAP`) by the caller.
     * The [systemPrompt] is the contents of `assets/system_prompt_v1.txt`
     * (escaping is handled by kotlinx.serialization — do NOT manually
     * quote-escape the prompt).
     */
    @Suppress("UnusedParameter")  // Story 1.9 design conflict — schema temporarily not wired; signature preserved for one-line revert
    fun build(
        systemPrompt: String,
        base64Image: String,
        responseSchema: JsonObject,
    ): String {
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", systemPrompt)
                        }
                        addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", "image/jpeg")
                                put("data", base64Image)
                            }
                        }
                    }
                }
            }
            putJsonArray("tools") {
                addJsonObject {
                    putJsonObject("googleSearch") {
                        // intentionally empty — enables Search Grounding
                    }
                }
            }
            putJsonObject("generationConfig") {
                // Story 1.9 design conflict surfaced by Story 2.4
                // smoke 2026-05-11: Gemini 2.5+ / 3.x rejects
                // `responseMimeType = "application/json"` +
                // `responseJsonSchema` when `tools = [googleSearch]`
                // is present with HTTP 400 "Tool use with a response
                // mime type: 'application/json' is unsupported". The
                // architecture D3.3 spec assumed both could coexist;
                // current Gemini API surface (verified 2026-05-11)
                // disallows the combination.
                //
                // **Smoke unblock** (temporary): drop the structured-
                // output config; the `system_prompt_v1.txt` already
                // instructs the model to emit JSON matching
                // `GeminiVerdictResponse`, and the two-stage parser
                // in `parseVerdict` tolerates JSON-in-text. Success
                // rate drops vs schema-enforced mode but the smoke
                // path is unblocked.
                //
                // **Permanent fix landed in Sprint Change 2026-05-18
                // (Story 7.4 MS2)** — formalise option (d): drop
                // responseMimeType + responseJsonSchema, rely on
                // system prompt v1 JSON discipline + defensive
                // markdown-fence-stripping parse in
                // GeminiClient.parseVerdict. Options (a) two-call /
                // (b) function calling / (c) drop FR9 deferred to V2
                // (see architecture D3.3 + D3.4 amended Reason
                // columns).
                //
                // `responseSchema` (unused param now) is kept in the
                // function signature so reverting to option (a) Call 1
                // (no tools, structured output OK) is a one-line
                // change when V2 lands the two-call rework.

                // Story 2.4 — `thinkingConfig.thinkingBudget = 0`
                // disables the "thinking" step on Gemini 2.5+ / 3.x
                // models. Without this, vision + structured-output
                // calls routinely exceed the 20 s D3.8 callTimeout
                // (verified via `verify HTTP returned in ms` log).
                // Reference: https://ai.google.dev/gemini-api/docs/thinking
                putJsonObject("thinkingConfig") {
                    put("thinkingBudget", 0)
                }
            }
        }
        return body.toString()
    }

    /**
     * Build the request body for the reverdict pass (Gemini #2 after
     * SerpAPI). Text-only — no image, no `tools` (Search Grounding would
     * be redundant since the SerpAPI markdown IS the search result). The
     * model still gets `thinkingConfig.thinkingBudget = 0` for latency
     * parity with the verify path.
     *
     * The body is intentionally tiny (a few KB of text vs ~50 KB JPEG in
     * [build]) so the call is fast and cheap.
     */
    fun buildReverdict(
        systemPrompt: String,
        claim: String,
        serpMarkdown: String,
    ): String {
        val userText = buildString {
            append("Claim extrait (verbatim image, source language) :\n")
            append(claim)
            append("\n\nSynthèse Google (SerpAPI, la plus à jour) :\n")
            append(serpMarkdown)
            append("\n\nRends le verdict final en JSON.")
        }
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", systemPrompt)
                        }
                        addJsonObject {
                            put("text", userText)
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                putJsonObject("thinkingConfig") {
                    put("thinkingBudget", 0)
                }
            }
        }
        return body.toString()
    }

    /**
     * Hand-rolled JSON Schema for [GeminiVerdictResponse]. We do NOT use
     * an external schema-derivation library (the architecture's
     * "weigh-before-add" rule — `kotlinx-serialization-json-okio` and
     * friends would add ~150-300 KB to the APK for a one-shot benefit).
     *
     * The schema mirrors [GeminiVerdictResponse] field-by-field. A
     * mismatch between this hand-written schema and the data class
     * surfaces in [GeminiClient]'s `MalformedResponse` mapping during
     * the manual smoke test (Task 12.7) — strong negative feedback if
     * we drift.
     *
     * Ref: [Gemini structured output](https://ai.google.dev/gemini-api/docs/structured-output).
     */
    fun buildResponseSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("verdictLabel") {
                put("type", "string")
                put("enum", buildJsonArray {
                    add("TRUE")
                    add("FALSE")
                    add("DOUBTFUL")
                    add("NON_VERIFIABLE")
                })
            }
            putJsonObject("headline") {
                put("type", "string")
            }
            putJsonObject("contextLines") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "string")
                }
            }
            putJsonObject("sources") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("title") { put("type", "string") }
                        putJsonObject("url") { put("type", "string") }
                        putJsonObject("publisher") { put("type", "string") }
                        putJsonObject("dateYearMonth") {
                            put("type", buildJsonArray {
                                add("string")
                                add("null")
                            })
                        }
                    }
                    putJsonArray("required") {
                        add("title")
                        add("url")
                        add("publisher")
                    }
                }
            }
            putJsonObject("ocrText") {
                put("type", "string")
            }
            putJsonObject("regionalBiasNote") {
                put("type", buildJsonArray {
                    add("string")
                    add("null")
                })
            }
            putJsonObject("injectionDetected") {
                put("type", "boolean")
            }
        }
        putJsonArray("required") {
            // Code-review patch P19 — injectionDetected added. Without
            // this, Gemini may omit the field; the data class default
            // `false` then masks "injection detected but field absent",
            // silently downgrading FR8 self-report. The schema requirement
            // tells Gemini the field is mandatory.
            add("verdictLabel")
            add("headline")
            add("contextLines")
            add("sources")
            add("ocrText")
            add("injectionDetected")
        }
    }
}
