package app.gamenative.ui.model

import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.library.metadata.CanonicalGameMetadata
import app.gamenative.library.metadata.GameDetailState
import app.gamenative.library.metadata.GameMetadataRepository
import app.gamenative.library.metadata.MetadataRefreshResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameDetailViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadObservesCanonicalMetadataWithoutSavedStateIdentifier() = runTest(scheduler) {
        val id = canonicalId("11111111-1111-1111-1111-111111111111")
        val repository = FakeRepository(
            mutableMapOf(
                id to MutableStateFlow(
                    GameDetailState.Content(metadata("Visible details"), stale = false),
                ),
            ),
        )
        val viewModel = GameDetailViewModel(repository)

        viewModel.load(id)
        runCurrent()

        val state = viewModel.state.value as GameDetailState.Content
        assertEquals("Visible details", state.metadata.title)
        assertEquals(listOf(id), repository.observeCalls)
    }

    @Test
    fun selectingAnotherCanonicalCancelsOldObservationAndShowsNewContent() = runTest(scheduler) {
        val first = canonicalId("11111111-1111-1111-1111-111111111111")
        val second = canonicalId("22222222-2222-2222-2222-222222222222")
        val repository = FakeRepository(
            mutableMapOf(
                first to MutableStateFlow(
                    GameDetailState.Content(metadata("First"), stale = false),
                ),
                second to MutableStateFlow(
                    GameDetailState.Content(metadata("Second"), stale = true),
                ),
            ),
        )
        val viewModel = GameDetailViewModel(repository)

        viewModel.load(first)
        runCurrent()
        viewModel.load(second)
        runCurrent()

        val state = viewModel.state.value as GameDetailState.Content
        assertEquals("Second", state.metadata.title)
        assertTrue(state.stale)
        assertEquals(listOf(first, second), repository.observeCalls)
    }

    @Test
    fun retryRestartsTheCurrentRepositoryObservation() = runTest(scheduler) {
        val id = canonicalId("33333333-3333-3333-3333-333333333333")
        val repository = FakeRepository(
            mutableMapOf(id to MutableStateFlow(GameDetailState.Unavailable(null))),
        )
        val viewModel = GameDetailViewModel(repository)
        viewModel.load(id)
        runCurrent()

        viewModel.retry()
        runCurrent()

        assertEquals(listOf(id, id), repository.observeCalls)
    }

    private class FakeRepository(
        private val states: MutableMap<CanonicalGameId, MutableStateFlow<GameDetailState>>,
    ) : GameMetadataRepository {
        val observeCalls = mutableListOf<CanonicalGameId>()

        override fun observe(canonicalId: CanonicalGameId): Flow<GameDetailState> {
            observeCalls += canonicalId
            return requireNotNull(states[canonicalId])
        }

        override suspend fun refresh(canonicalId: CanonicalGameId): MetadataRefreshResult =
            MetadataRefreshResult.Refreshed
    }

    private fun metadata(title: String): CanonicalGameMetadata = CanonicalGameMetadata(
        title = title,
        shortDescription = null,
        about = null,
        headerImageUrl = null,
        screenshots = emptyList(),
        movies = emptyList(),
        developers = emptyList(),
        publishers = emptyList(),
        releaseDate = null,
        platforms = emptySet(),
        languages = emptyList(),
        requirements = null,
        features = emptyList(),
        achievementCount = null,
        dlcCount = null,
        fetchedAtEpochMs = 1L,
    )

    private fun canonicalId(value: String) = CanonicalGameId.parse(value)
}
