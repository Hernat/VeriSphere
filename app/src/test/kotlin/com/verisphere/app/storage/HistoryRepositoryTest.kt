package com.verisphere.app.storage

import app.cash.turbine.test
import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * JVM unit tests for [HistoryRepositoryImpl] (Story 1.10).
 *
 * Architecture-line-426 naming: backtick-quoted English-sentence method
 * names. JVM tests under `src/test/`, no Robolectric, no Android SDK
 * stubs — the lambda-seam pattern (`readHistory` / `writeHistory`)
 * lets the impl be exercised in pure-JVM mode.
 *
 * `FakeStore` holds the persisted list in memory. The JSON
 * round-trip itself is covered by `SessionRecordSerializationTest` +
 * `SecureStorageInstrumentedTest` — this test focuses on the
 * repository's invariants (state-flow-as-cache, mutex serialisation,
 * FIFO eviction, lazy load, persist-then-publish ordering).
 */
class HistoryRepositoryTest {

    private class FakeStore {
        val readCount = AtomicInteger(0)
        val current = AtomicReference<List<SessionRecord>?>(null)
        fun read(): List<SessionRecord>? {
            readCount.incrementAndGet()
            return current.get()
        }
        fun write(list: List<SessionRecord>) {
            current.set(list)
        }
    }

    private fun newRepo(store: FakeStore = FakeStore()): Pair<HistoryRepositoryImpl, FakeStore> {
        val repo = HistoryRepositoryImpl(
            readHistory = { store.read() },
            writeHistory = { list -> store.write(list) },
        )
        return repo to store
    }

    private fun sampleRecord(id: String, label: VerdictLabel = VerdictLabel.TRUE): SessionRecord =
        SessionRecord(
            id = id,
            timestampMs = id.hashCode().toLong(),
            verdictLabel = label,
            headline = "Headline for $id",
            contextLines = emptyList(),
            sourceLinks = emptyList<SourceCitation>(),
            ocrText = "",
            regionalBiasNote = null,
        )

    @Test
    fun `append persists the record and emits via observe`() = runBlocking {
        val (repo, store) = newRepo()
        val record = sampleRecord("r1")

        repo.append(record)

        assertEquals(listOf(record), store.current.get())
        repo.observe().test {
            assertEquals(listOf(record), awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `append twice produces an observe sequence in append order`() = runBlocking {
        val (repo, _) = newRepo()
        val r1 = sampleRecord("r1")
        val r2 = sampleRecord("r2", VerdictLabel.FALSE)

        repo.append(r1)
        repo.append(r2)

        repo.observe().test {
            // The first emission after subscription is the post-append cache.
            assertEquals(listOf(r1, r2), awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `append at MAX_HISTORY_ENTRIES + 1 evicts the oldest record (FIFO at 501 entries)`() = runBlocking {
        val (repo, store) = newRepo()
        // Pre-populate the store with MAX_HISTORY_ENTRIES records.
        val seed = (1..HistoryRepositoryImpl.MAX_HISTORY_ENTRIES).map { sampleRecord("seed-$it") }
        store.write(seed)

        val freshest = sampleRecord("fresh")
        repo.append(freshest)

        val current = store.current.get()
        assertNotNull(current)
        current!!
        assertEquals(HistoryRepositoryImpl.MAX_HISTORY_ENTRIES, current.size)
        // The oldest (seed-1) should be evicted; the freshest is at the tail.
        assertEquals("seed-2", current.first().id)
        assertEquals("fresh", current.last().id)
    }

    @Test
    fun `getById returns the matching record after append`() = runBlocking {
        val (repo, _) = newRepo()
        val record = sampleRecord("target")

        repo.append(sampleRecord("other"))
        repo.append(record)

        assertEquals(record, repo.getById("target"))
    }

    @Test
    fun `getById returns null for an unknown id`() = runBlocking {
        val (repo, _) = newRepo()
        repo.append(sampleRecord("known"))

        assertNull(repo.getById("does-not-exist"))
    }

    @Test
    fun `observe replays the persisted state on first subscription`() = runBlocking {
        val store = FakeStore()
        store.write(listOf(sampleRecord("seeded-1"), sampleRecord("seeded-2")))
        val (repo, _) = newRepo(store)

        repo.observe().test {
            assertEquals(listOf(sampleRecord("seeded-1"), sampleRecord("seeded-2")), awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `concurrent append and getById are linearizable`() = runBlocking {
        val (repo, _) = newRepo()
        val total = 50

        // Fan out: half coroutines append, half query getById on a known id.
        coroutineScope {
            val appenders = (1..total).map { idx ->
                async(Dispatchers.Default) {
                    repo.append(sampleRecord("rec-$idx"))
                }
            }
            val readers = (1..total).map { idx ->
                async(Dispatchers.Default) {
                    repo.getById("rec-$idx")
                }
            }
            appenders.awaitAll()
            readers.awaitAll()
        }

        // The final state must contain every appended record exactly once
        // (no duplicate appends, no lost writes).
        repo.observe().test {
            val final = awaitItem()
            assertEquals(total, final.size)
            assertEquals(total, final.map { it.id }.toSet().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `append survives a fresh repository instance reading from the same storage`() = runBlocking {
        val store = FakeStore()
        val (repo1, _) = newRepo(store)
        repo1.append(sampleRecord("persisted"))

        // Fresh repo instance over the same store — should observe the
        // record on first subscription.
        val (repo2, _) = newRepo(store)
        repo2.observe().test {
            assertEquals(listOf(sampleRecord("persisted")), awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `lazy load defers store read until first observe append or getById`() = runBlocking {
        val store = FakeStore()
        val (_, _) = newRepo(store)

        // Constructor alone must NOT trigger a read.
        assertEquals(0, store.readCount.get())

        // First operation triggers exactly one read; subsequent operations
        // hit the in-memory cache (no extra reads).
        val (repo, _) = newRepo(store)
        repo.getById("anything")
        val readsAfterFirst = store.readCount.get()
        repo.getById("another")
        repo.getById("third")
        val readsAfterThree = store.readCount.get()

        assertEquals(1, readsAfterFirst)
        assertEquals(1, readsAfterThree)
    }
}
