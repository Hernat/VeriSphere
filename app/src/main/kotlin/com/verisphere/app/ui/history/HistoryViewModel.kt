package com.verisphere.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.verisphere.app.VeriSphereApplication
import com.verisphere.app.storage.HistoryRepository
import com.verisphere.app.storage.SessionRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Activity-side ViewModel for the history surface (Story 4.1; PRD FR16
 * partial — chronological list scaffold).
 *
 * **Architecture ViewModel pattern** (architecture lines 507–511):
 *  - Exposes a single read-only [StateFlow] of [HistoryUiState] — no
 *    per-Composable callbacks, no parallel state primitives, no
 *    nullable-data-plus-loading-flag duets (architecture line 495).
 *  - Constructor takes only the [HistoryRepository] interface — no
 *    `Context`, no `Application`, no concrete `*Impl`. The Factory
 *    (companion object [Factory]) reads the repo from [VeriSphereApplication.container].
 *  - `init { }` subscribes to the repo Flow — architecture line 510
 *    explicitly allows state subscriptions in `init`. Doing anything
 *    else (a one-shot API call, a side-effect on external state) is
 *    forbidden here.
 *
 * **Reduction discipline**:
 *  - Initial state is [HistoryUiState.Loading]. Observable until the
 *    first repo emission lands (the repo's
 *    [com.verisphere.app.storage.HistoryRepositoryImpl.onSubscription]
 *    triggers a lazy Keystore-backed load on [kotlinx.coroutines.Dispatchers.IO];
 *    on a first-cold-Keystore-read window the Loading state is
 *    observable for ~50–200 ms).
 *  - Empty repo emission → [HistoryUiState.Empty]. Story 4.2 owns the
 *    empty-state copy + glyph.
 *  - Non-empty repo emission → [HistoryUiState.Content], records sorted
 *    by [SessionRecord.timestampMs] descending (newest first).
 *
 * **FIFO propagation**: when [HistoryRepository.append] evicts the
 * oldest record at `MAX_HISTORY_ENTRIES = 500` (architecture D1.3), the
 * next emission lands in this collector and is re-reduced — no special
 * handling required.
 */
class HistoryViewModel(
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HistoryUiState>(HistoryUiState.Loading)

    /** Single read-only state surface — architecture line 509. */
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        // Architecture line 510 — init { } is reserved for state
        // subscriptions; collecting a repo Flow IS a state subscription.
        // The first subscription triggers the repo's lazy Keystore load
        // on Dispatchers.IO (HistoryRepositoryImpl.onSubscription) so
        // Main is not blocked.
        viewModelScope.launch {
            historyRepository.observe().collect { records ->
                _uiState.update {
                    if (records.isEmpty()) {
                        HistoryUiState.Empty
                    } else {
                        HistoryUiState.Content(records.sortedByDescending { it.timestampMs })
                    }
                }
            }
        }
    }

    companion object {
        /**
         * `ViewModelProvider.Factory` defined alongside the ViewModel
         * (architecture line 511). Uses the `CreationExtras`-based DSL
         * shipped with `androidx.lifecycle:lifecycle-viewmodel-compose`;
         * the Compose `viewModel(factory = ...)` integration routes
         * here, supplying `APPLICATION_KEY` from the host activity's
         * `Application`.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as VeriSphereApplication
                HistoryViewModel(
                    historyRepository = application.container.historyRepository,
                )
            }
        }
    }
}
