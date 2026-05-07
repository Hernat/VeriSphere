package com.verisphere.app.storage

/**
 * Persisted record of a single verification session.
 *
 * **Story 1.8 placeholder.** This is the MINIMAL shape required by the
 * Story 1.8 capture pipeline so it can return a `VerificationOutcome.Verdict`
 * end-to-end without depending on the Gemini client (Story 1.9) or the
 * full history persistence path (Story 1.10).
 *
 * TODO Story 1.10: extend (do NOT replace) with the FR15 final shape:
 *   - `verdictLabel: VerdictLabel` (enum: TRUE / FALSE / DOUBTFUL / NON_VERIFIABLE)
 *   - `headline: String`
 *   - `contextLines: List<String>`
 *   - `sourceLinks: List<SourceCitation>`
 *   - `ocrText: String`
 *   - `regionalBiasNote: String?`
 *   - `@Serializable` annotation + kotlinx.serialization wiring
 *
 * Story 1.10 will also wire `HistoryRepository.append(record)` (AR13). In
 * Story 1.8 the record is only consumed by `BubbleStateMachine` reducer
 * branches that 1.10 introduces — no persistence happens yet.
 */
data class SessionRecord(
    val id: String,
    val timestampMs: Long,
    val placeholderHeadline: String = "captured $timestampMs",
)
