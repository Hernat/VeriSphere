package com.verisphere.app.storage

import kotlinx.coroutines.flow.Flow

/**
 * Read/write boundary over the persisted [SessionRecord] list
 * (architecture: AR13, D1.1, D1.2, D1.3; PRD: FR15, FR16, FR17, NFR16).
 *
 * Decouples call sites (the bubble service writes via [append]; future
 * Story 4 history view reads via [observe]; future Story 2.4 detail
 * panel reads via [getById]) from the storage primitive ([SecureStorage]
 * over `EncryptedSharedPreferences`). The migration path to Room +
 * SQLCipher in V2 is mechanical because every consumer receives this
 * interface, not the concrete implementation.
 *
 * **State-flow-as-cache pattern** (architecture line 990, validation
 * Gap #3): the implementation backs [observe] with a
 * `MutableStateFlow<List<SessionRecord>>` populated lazily on first
 * access. Concurrent reads observe atomic emissions; writes are
 * serialised through a [kotlinx.coroutines.sync.Mutex].
 */
interface HistoryRepository {

    /**
     * Hot stream of the persisted history list. Emits the full current
     * list on subscription, then a fresh list on every [append].
     *
     * The first subscription triggers the lazy load from
     * [SecureStorage]; subsequent subscriptions hit the in-memory cache.
     * Subscribers receive the persisted state — never an in-memory
     * value that hasn't been written yet (NFR16 + Story 1.10 Critical
     * Dev Note #6).
     */
    fun observe(): Flow<List<SessionRecord>>

    /**
     * Append a fresh verdict record, persist to encrypted storage, then
     * publish to [observe] subscribers. FIFO eviction at
     * [HistoryRepositoryImpl.MAX_HISTORY_ENTRIES] (architecture D1.3).
     *
     * Suspends on [kotlinx.coroutines.Dispatchers.IO]; safe to call from
     * any coroutine scope. Never throws — `SecureStorage.writeJson`
     * fails soft via internal `Log.e` and the cache update is skipped
     * (the next write attempt retries).
     */
    suspend fun append(record: SessionRecord)

    /**
     * Random-access lookup. Returns `null` if no record matches.
     * Story 2.4's MainActivity uses this to populate the detail panel.
     *
     * Suspends because the first call may trigger the lazy SecureStorage
     * load; subsequent calls are O(N) over the in-memory cache.
     */
    suspend fun getById(id: String): SessionRecord?
}
