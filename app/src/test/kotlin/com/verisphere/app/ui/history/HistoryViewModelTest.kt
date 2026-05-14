package com.verisphere.app.ui.history

import app.cash.turbine.test
import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.gemini.VerdictLabel
import com.verisphere.app.storage.HistoryRepository
import com.verisphere.app.storage.SessionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for [HistoryViewModel] (Story 4.1).
 *
 * Architecture line 426 backtick-quoted English-sentence method names.
 * Pure-JVM mode — no Android SDK stubs, no Robolectric. Sources are
 * exercised via [FakeHistoryRepository] which surfaces a
 * [MutableSharedFlow] the test fully controls.
 *
 * The ViewModel uses `viewModelScope` (defaults to
 * `Dispatchers.Main.immediate`); the `@Before`/`@After` `setMain` /
 * `resetMain` boilerplate replaces it with [UnconfinedTestDispatcher]
 * so the `init { }` collect runs synchronously inside `runTest { }`.
 * Without this setup, every test would fail with
 * `IllegalStateException: Module with the Main dispatcher had failed
 * to initialize`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeHistoryRepository : HistoryRepository {
        val emissions: MutableSharedFlow<List<SessionRecord>> =
            MutableSharedFlow(replay = 1)

        override fun observe(): Flow<List<SessionRecord>> = emissions.asSharedFlow()

        override suspend fun append(record: SessionRecord): Unit = error("not used in HistoryViewModelTest")

        override suspend fun getById(id: String): SessionRecord? = error("not used in HistoryViewModelTest")

        suspend fun emit(records: List<SessionRecord>) {
            emissions.emit(records)
        }
    }

    private fun sampleRecord(id: String, timestampMs: Long): SessionRecord =
        SessionRecord(
            id = id,
            timestampMs = timestampMs,
            verdictLabel = VerdictLabel.TRUE,
            headline = "headline-$id",
            contextLines = emptyList(),
            sourceLinks = emptyList<SourceCitation>(),
            ocrText = "",
            regionalBiasNote = null,
            injectionDetected = false,
        )

    @Test
    fun `HistoryViewModel observes Empty when the repository emits an empty list`() = runTest {
        val fake = FakeHistoryRepository()
        val vm = HistoryViewModel(historyRepository = fake)

        fake.emit(emptyList())
        runCurrent()

        assertEquals(HistoryUiState.Empty, vm.uiState.value)
    }

    @Test
    fun `HistoryViewModel observes Content sorted by timestampMs descending`() = runTest {
        val fake = FakeHistoryRepository()
        val recT0 = sampleRecord(id = "r0", timestampMs = 1_000L)
        val recT1 = sampleRecord(id = "r1", timestampMs = 2_000L)
        val recT2 = sampleRecord(id = "r2", timestampMs = 3_000L)

        val vm = HistoryViewModel(historyRepository = fake)
        // Emit in non-sorted order to confirm the reducer sorts.
        fake.emit(listOf(recT0, recT2, recT1))
        runCurrent()

        val state = vm.uiState.value
        assertTrue("Expected Content state but was $state", state is HistoryUiState.Content)
        val content = state as HistoryUiState.Content
        assertEquals(listOf(recT2, recT1, recT0), content.records)
    }

    @Test
    fun `HistoryViewModel emits Loading then Content on first repo emission`() = runTest {
        val fake = FakeHistoryRepository()
        val vm = HistoryViewModel(historyRepository = fake)

        // Before any emission, the StateFlow holds the initial Loading.
        assertEquals(HistoryUiState.Loading, vm.uiState.value)

        val rec = sampleRecord(id = "r1", timestampMs = 1_000L)
        fake.emit(listOf(rec))
        runCurrent()

        val state = vm.uiState.value
        assertTrue("Expected Content state but was $state", state is HistoryUiState.Content)
    }

    @Test
    fun `HistoryViewModel re-reduces when repository evicts oldest record (FIFO propagation)`() = runTest {
        val fake = FakeHistoryRepository()
        val a = sampleRecord(id = "a", timestampMs = 1_000L)
        val b = sampleRecord(id = "b", timestampMs = 2_000L)
        val c = sampleRecord(id = "c", timestampMs = 3_000L)
        val d = sampleRecord(id = "d", timestampMs = 4_000L)
        val vm = HistoryViewModel(historyRepository = fake)

        vm.uiState.test {
            // Loading initial value.
            assertEquals(HistoryUiState.Loading, awaitItem())

            // First emission: [a, b, c] -> Content sorted desc.
            fake.emit(listOf(a, b, c))
            assertEquals(
                HistoryUiState.Content(listOf(c, b, a)),
                awaitItem(),
            )

            // Second emission simulates FIFO eviction of `a` and append of `d`.
            fake.emit(listOf(b, c, d))
            assertEquals(
                HistoryUiState.Content(listOf(d, c, b)),
                awaitItem(),
            )

            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `HistoryViewModel uiState is hot — second subscriber sees current value immediately`() = runTest {
        val fake = FakeHistoryRepository()
        val rec = sampleRecord(id = "r1", timestampMs = 1_000L)
        val vm = HistoryViewModel(historyRepository = fake)

        fake.emit(listOf(rec))
        runCurrent()

        // A late subscriber should observe the latest cached value
        // without triggering any upstream re-emission.
        vm.uiState.test {
            val first = awaitItem()
            assertTrue("Expected Content state but was $first", first is HistoryUiState.Content)
            cancelAndConsumeRemainingEvents()
        }
    }
}
