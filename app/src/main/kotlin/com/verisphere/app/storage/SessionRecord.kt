package com.verisphere.app.storage

import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import kotlinx.serialization.Serializable

/**
 * Persisted record of a single verification session. Schema: architecture
 * D1.2; PRD FR15.
 *
 * Created by [`GeminiClient.verify`][com.verisphere.app.gemini.GeminiClient.verify]
 * (Story 1.9) by mapping a `GeminiVerdictResponse` plus the locally-generated
 * `id` (UUID v4) and `timestampMs` (`System.currentTimeMillis()`). Persisted
 * by `HistoryRepository.append` (Story 1.10).
 *
 * **Field semantics:**
 *  - [id] — UUID v4 string. Stable across persistence. Story 1.10 uses it
 *    as the primary key in the encrypted history list.
 *  - [timestampMs] — epoch millis (architecture line 451 — in-memory and
 *    persisted use epoch millis; display layer formats per locale).
 *  - [verdictLabel] — one of the 4 V1 labels (PRD FR13).
 *  - [headline] — single-sentence verdict summary surfaced in the bubble's
 *    flash tooltip (Story 1.10) and the detail panel header (Story 2.3).
 *  - [contextLines] — 0–3 short supporting bullets shown in the detail
 *    panel (UX spec). May be empty (e.g. when [verdictLabel] is
 *    `NON_VERIFIABLE` for a no-claim image).
 *  - [sourceLinks] — Search Grounding citations (PRD FR9 — TRUE requires
 *    ≥ 2). Story 2.1's `SourceLinkChip` consumes this list.
 *  - [ocrText] — verbatim text extracted from the captured frame (PRD
 *    FR7 + FR8 anti-injection self-revealing posture). Story 2.3 renders
 *    this in the detail panel so the user can inspect for injection
 *    attempts.
 *  - [regionalBiasNote] — optional brief note when the claim concerns a
 *    topic with known regional reporting variance. Null when not
 *    applicable. Story 2.3 renders only when non-null.
 *  - [injectionDetected] — Gemini's self-report flag (PRD FR8 anti-
 *    injection self-revealing posture). Story 3.1 added the field to
 *    preserve the wire-format signal from [`GeminiVerdictResponse.injectionDetected`][com.verisphere.app.gemini.GeminiVerdictResponse.injectionDetected];
 *    Story 3.3 reads it in `BubbleStateMachine.reduce` to redirect a
 *    successful `Verdict(record)` outcome to
 *    `BubbleState.FailureState.PossibleInjection(record)` so the user
 *    sees the amber warning flash and can tap to inspect the OCR text.
 *    The record itself remains persisted to history via the existing
 *    Story 1.10 `runCaptureAndDispatch` ordering. Defaults to `false`
 *    so historic persisted
 *    blobs (Story 1.10 records written before Story 3.1) deserialise
 *    cleanly via kotlinx.serialization's `coerceInputValues = true;
 *    ignoreUnknownKeys = true` ([`SecureStorage`'s `Json` config][com.verisphere.app.storage.SecureStorage]) —
 *    no schema-version envelope is required (architecture deferred that
 *    to V2 per Story 1.4 deferred-work).
 *
 * **Wire format** is camelCase per architecture line 443; field names
 * match `GeminiVerdictResponse` so the mapping in `GeminiClient.verify`
 * is a 1-to-1 copy (the only renamed field is `sources` →
 * [sourceLinks] per architecture D1.2 naming).
 */
@Serializable
data class SessionRecord(
    val id: String,
    val timestampMs: Long,
    val verdictLabel: VerdictLabel,
    val headline: String,
    val contextLines: List<String>,
    val sourceLinks: List<SourceCitation>,
    val ocrText: String,
    val extractedClaim: String = "",
    val regionalBiasNote: String? = null,
    val injectionDetected: Boolean = false,
)
