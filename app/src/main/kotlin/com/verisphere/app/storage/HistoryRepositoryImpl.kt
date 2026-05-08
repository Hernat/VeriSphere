package com.verisphere.app.storage

import android.util.Log
import com.verisphere.app.util.tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * State-flow-as-cache implementation of [HistoryRepository] (architecture
 * AR13, validation Gap #3 — line 990).
 *
 * **State-flow-as-cache pattern**: a [MutableStateFlow] holds the
 * persisted list in memory; [observe] surfaces it as a [Flow]. Concurrent
 * reads observe atomic emissions (the StateFlow contract); concurrent
 * writes are serialised through [mutex]. The cache is initialised
 * lazily on first access (NFR3 cold-start budget) — the Keystore-backed
 * [SecureStorage] first-access stalls 50–200 ms.
 *
 * **Persist-then-publish ordering** (NFR16, Critical Dev Note #6 in
 * the story file): inside [append], we first call [writeHistory] under
 * [mutex], then update [_cache]. Reverse order would let observers see
 * a verdict that hasn't been persisted — a process kill in the gap
 * would lose the user's verdict from history while the bubble had
 * already shown it.
 *
 * **Lambda seam for testability** ([readHistory] / [writeHistory]):
 * production wires these to [SecureStorage.readJson] /
 * [SecureStorage.writeJson] with the `KEY_HISTORY` key (architecture
 * D1.4 single-prefs-file). Tests inject in-memory fakes — no Context,
 * no EncryptedSharedPreferences, no Keystore. JSON round-trip is
 * separately covered by `SessionRecordSerializationTest` +
 * `SecureStorageInstrumentedTest`.
 *
 * **FIFO eviction** (architecture D1.3): at [MAX_HISTORY_ENTRIES] + 1,
 * the oldest record is dropped via `takeLast(MAX_HISTORY_ENTRIES)`.
 *
 * **Failure handling**: [SecureStorage.writeJson] does not throw;
 * decode failures in [readHistory] return `null` and are treated as
 * an empty history. We do NOT propagate write failures because the
 * verdict is already in [BubbleState] (the user sees it); failing the
 * persist would not improve the user experience and would conflict
 * with architecture line 487 (no exception escapes the verification
 * pipeline). A write failure is logged via `Log.e` inside SecureStorage.
 */
class HistoryRepositoryImpl(
    private val readHistory: () -> List<SessionRecord>?,
    private val writeHistory: (List<SessionRecord>) -> Unit,
    private val mutex: Mutex = Mutex(),
) : HistoryRepository {

    /**
     * `null` means "not yet loaded from storage." The first observe /
     * append / getById triggers the load inside [ensureLoaded].
     * Filtering on [Flow.filterNotNull] in [observe] hides the unloaded
     * sentinel from subscribers — they only see the post-load list.
     */
    private val _cache = MutableStateFlow<List<SessionRecord>?>(null)
    private val cache = _cache.asStateFlow()

    override fun observe(): Flow<List<SessionRecord>> =
        cache
            .onSubscription {
                // Code-review patch P2 — `onSubscription` runs on the
                // collector's coroutine context. Subscribers on Main
                // (e.g. Story 4 history view's `viewModelScope`) would
                // stall on the Keystore-backed first read (~50–200 ms,
                // NFR3 violation) without this dispatcher hop. `append`
                // and `getById` already do `withContext(Dispatchers.IO)`
                // internally; `observe`'s lazy load needs the same.
                withContext(Dispatchers.IO) { ensureLoaded() }
            }
            .filterNotNull()

    override suspend fun append(record: SessionRecord): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoadedLocked()
            val current = _cache.value.orEmpty()
            val capped = (current + record).takeLast(MAX_HISTORY_ENTRIES)
            try {
                writeHistory(capped)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.e(TAG, "writeHistory failed for record id=${record.id}", e)
                return@withLock
            }
            // Publish AFTER persist (Critical Dev Note #6): observers
            // never see a record that hasn't been written.
            _cache.value = capped
        }
    }

    override suspend fun getById(id: String): SessionRecord? = withContext(Dispatchers.IO) {
        ensureLoaded()
        _cache.value?.firstOrNull { it.id == id }
    }

    /**
     * Lazy load with double-checked locking. First-access stalls on the
     * Keystore + EncryptedSharedPreferences round-trip (~50–200 ms);
     * subsequent calls are a single volatile read.
     */
    private suspend fun ensureLoaded() {
        if (_cache.value != null) return
        mutex.withLock { ensureLoadedLocked() }
    }

    /**
     * Caller MUST already hold [mutex].
     *
     * Code-review patch P8 — guard [readHistory] against exceptions.
     * The KDoc claim that the repository "never throws to the caller"
     * was previously broken on Keystore IO errors / MasterKey init
     * failures (rare but real on rooted devices, post-credential-reset,
     * or hardware-Keystore-absent ROMs). Fail soft to an empty list
     * matching the [SecureStorage] fail-soft pattern at line 138; the
     * user loses the persisted history (acceptable for a bundled-key
     * solo-dev V1 per architecture line 762's "founder-only posture")
     * but the app does not crash mid-verification.
     */
    private fun ensureLoadedLocked() {
        if (_cache.value != null) return
        val loaded = try {
            readHistory().orEmpty()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.e(TAG, "readHistory failed — falling back to empty list", e)
            emptyList()
        }
        _cache.value = loaded
    }

    companion object {
        /** Architecture D1.3 — FIFO retention cap, read-only in V1. */
        const val MAX_HISTORY_ENTRIES = 500

        /**
         * SecureStorage namespace shared with other persisted V1 values
         * per architecture D1.4 (single shared `vs_secure_prefs` file).
         * Never reuse this literal for any other key.
         */
        const val KEY_HISTORY = "history"

        private val TAG = tag("HistoryRepository")
    }
}
