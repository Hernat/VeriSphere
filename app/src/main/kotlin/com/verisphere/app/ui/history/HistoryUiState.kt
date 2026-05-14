package com.verisphere.app.ui.history

import com.verisphere.app.storage.SessionRecord

/**
 * Sealed UI state for the history surface (Story 4.1).
 *
 * Three exhaustive variants per architecture line 495 — no
 * nullable-data-plus-loading-flag duets, no [Error] state (the
 * underlying [com.verisphere.app.storage.HistoryRepositoryImpl] falls
 * soft to an empty list on Keystore failure, see its P8 patch).
 *
 *  - [Loading] — first-emit window (~50–200 ms first-cold-Keystore-read).
 *  - [Empty] — repo emitted an empty list; Story 4.2 owns the
 *    empty-state copy + glyph.
 *  - [Content] — repo emitted ≥ 1 record. The records list is sorted
 *    newest first by [SessionRecord.timestampMs] descending — the
 *    [HistoryViewModel] applies the sort before publishing, so all
 *    downstream consumers (Compose, tests) can trust the ordering.
 *
 * Top-level rather than nested inside [HistoryViewModel] so previews
 * and Compose UI tests can construct instances with terse
 * `HistoryUiState.Empty` / `HistoryUiState.Content(...)` references
 * (matches the [com.verisphere.app.bubble.BubbleState] precedent —
 * top-level sealed types in their package root).
 */
sealed interface HistoryUiState {
    data object Loading : HistoryUiState
    data object Empty : HistoryUiState
    data class Content(val records: List<SessionRecord>) : HistoryUiState
}
