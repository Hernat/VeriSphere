package com.verisphere.app.serp

/**
 * Epic 9 Story 9.1 — sealed result type returned by [SerpApiClient.search].
 *
 * Mirrors the [com.verisphere.app.gemini.VerificationOutcome] pattern
 * (single error funnel, never throws). [Success] carries the parsed
 * payload + the merged `SerpReference[]` + the AI-mode reconstructed
 * markdown (or a synthetic one composed from `google` engine snippets
 * when AI mode was unavailable for the query).
 *
 * Failure variants are NOT user-visible by themselves — the orchestrator
 * (CapturePipeline) handles them with graceful degradation: log + fall
 * back to Gemini-only verdict. Only [Failure.Quota] additionally trips
 * the [SerpQuotaGate] cooldown.
 */
sealed class SerpOutcome {

    data class Success(
        val references: List<SerpReference>,
        val markdown: String,
        val engineUsed: SerpEngine,
    ) : SerpOutcome()

    sealed class Failure : SerpOutcome() {
        data object Offline : Failure()
        data object Timeout : Failure()
        /** HTTP 429 or quota-exhausted body. Trips [SerpQuotaGate]. */
        data object Quota : Failure()
        /** Genuine parse failure on a non-empty response body. */
        data object MalformedResponse : Failure()
        /**
         * AI-mode returned 200 OK with no references AND no markdown —
         * SerpAPI semantically means "no AI-mode coverage for this
         * query" (code-review P5 split from MalformedResponse). Triggers
         * the `engine=google` fallback path in [SerpApiClient.search];
         * NOT user-visible.
         */
        data object EmptyPayload : Failure()
        data class HttpError(val code: Int) : Failure()
        /** SERP_API_KEY missing or empty — SerpAPI disabled at config. */
        data object NotConfigured : Failure()
        /**
         * Gemini returned an empty `extractedClaim` so there is nothing
         * to fact-check (code-review P4 split from NotConfigured).
         * Distinguishes "no claim to search" from "no SerpAPI key" at
         * the telemetry layer.
         */
        data object EmptyQuery : Failure()
    }
}

/**
 * Which SerpAPI engine produced the [SerpOutcome.Success] payload.
 * Useful for logging + telemetry; not surfaced to the user UI in V1.
 */
enum class SerpEngine {
    GoogleAiMode,
    Google,
}
