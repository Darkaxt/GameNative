package app.gamenative.ui.model

import app.gamenative.service.steam.SteamWebApiKeyRepository
import app.gamenative.service.steam.SteamWebApiKeySaveResult
import app.gamenative.service.steam.SteamWebApiKeyStatus
import app.gamenative.service.steam.SteamWebApiKeyValidationResult
import app.gamenative.service.steam.SteamWebApiKeyValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SteamWebApiKeySettingsViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var repository: RecordingRepository

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        repository = RecordingRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun validatedKeyCanBeSavedWithoutReentry() = runTest(scheduler) {
        val result = CompletableDeferred<SteamWebApiKeyValidationResult>()
        val viewModel = viewModel(SteamWebApiKeyValidator { result.await() })
        runCurrent()

        viewModel.test(KEY)
        runCurrent()
        assertEquals(SteamWebApiKeyValidationState.TESTING, viewModel.state.value.validation)
        assertFalse(viewModel.state.value.saveSucceeded)

        result.complete(SteamWebApiKeyValidationResult.VALID)
        runCurrent()
        assertEquals(SteamWebApiKeyValidationState.VALID, viewModel.state.value.validation)

        viewModel.save(OTHER_KEY)
        runCurrent()
        assertTrue(repository.savedKeys.isEmpty())

        viewModel.save(KEY)
        runCurrent()

        assertEquals(listOf(KEY), repository.savedKeys)
        assertTrue(viewModel.state.value.configured)
        assertTrue(viewModel.state.value.saveSucceeded)
    }

    @Test
    fun invalidUnavailableAndUntestedKeysAreNeverSaved() = runTest(scheduler) {
        var result = SteamWebApiKeyValidationResult.INVALID
        val viewModel = viewModel(SteamWebApiKeyValidator { result })
        runCurrent()

        viewModel.save(KEY)
        runCurrent()
        assertTrue(repository.savedKeys.isEmpty())

        viewModel.test(KEY)
        runCurrent()
        assertEquals(SteamWebApiKeyValidationState.INVALID, viewModel.state.value.validation)
        viewModel.save(KEY)
        runCurrent()
        assertTrue(repository.savedKeys.isEmpty())

        viewModel.clearFeedback()
        result = SteamWebApiKeyValidationResult.UNAVAILABLE
        viewModel.test(KEY)
        runCurrent()
        assertEquals(SteamWebApiKeyValidationState.UNAVAILABLE, viewModel.state.value.validation)
        viewModel.save(KEY)
        runCurrent()
        assertTrue(repository.savedKeys.isEmpty())
    }

    @Test
    fun changingInputCancelsValidationAndReturnsToUntested() = runTest(scheduler) {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val viewModel = viewModel(
            SteamWebApiKeyValidator {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        )
        runCurrent()
        viewModel.test(KEY)
        runCurrent()
        started.await()

        viewModel.clearFeedback()
        runCurrent()
        cancelled.await()

        assertEquals(SteamWebApiKeyValidationState.UNTESTED, viewModel.state.value.validation)
        assertFalse(viewModel.state.value.operationFailed)
    }

    @Test
    fun saveFailureKeepsValidatedStateAndReportsNoSuccess() = runTest(scheduler) {
        repository.saveFailure = IllegalStateException("fixed non-sensitive failure")
        val viewModel = viewModel(
            SteamWebApiKeyValidator { SteamWebApiKeyValidationResult.VALID },
        )
        runCurrent()
        viewModel.test(KEY)
        runCurrent()

        viewModel.save(KEY)
        runCurrent()

        assertTrue(viewModel.state.value.operationFailed)
        assertFalse(viewModel.state.value.saveSucceeded)
        assertEquals(SteamWebApiKeyValidationState.VALID, viewModel.state.value.validation)
    }

    private fun viewModel(validator: SteamWebApiKeyValidator) = SteamWebApiKeySettingsViewModel(
        repository = repository,
        validator = validator,
    )

    private class RecordingRepository : SteamWebApiKeyRepository {
        val savedKeys = mutableListOf<String>()
        var saveFailure: Exception? = null

        override suspend fun status(): SteamWebApiKeyStatus = SteamWebApiKeyStatus.NOT_CONFIGURED

        override suspend fun save(key: String): SteamWebApiKeySaveResult {
            saveFailure?.let { throw it }
            savedKeys += key
            return SteamWebApiKeySaveResult.SAVED
        }

        override suspend fun delete() = Unit
    }

    private companion object {
        const val KEY = "0123456789abcdef0123456789ABCDEF"
        const val OTHER_KEY = "1123456789abcdef0123456789ABCDEF"
    }
}
