package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamOwnershipReadinessTest {
    @Test
    fun accountTransitionInvalidatesReadinessAndRejectsStaleCallback() = runTest {
        val lifecycleState = InMemoryAccountLifecycleState()
        val readiness = SteamOwnershipReadiness(lifecycleState)
        var commits = 0

        assertTrue(
            readiness.commitLicenseSnapshot(expectedGeneration = 0L) {
                commits++
            },
        )
        assertEquals(0L, lifecycleState.readyGeneration(GameSource.STEAM))

        val nextGeneration = readiness.transitionAccount {}
        assertEquals(1L, nextGeneration)
        assertNull(lifecycleState.readyGeneration(GameSource.STEAM))
        assertFalse(
            readiness.commitLicenseSnapshot(expectedGeneration = 0L) {
                commits++
            },
        )
        assertEquals(1, commits)

        assertTrue(
            readiness.commitLicenseSnapshot(expectedGeneration = nextGeneration) {
                commits++
            },
        )
        assertEquals(nextGeneration, lifecycleState.readyGeneration(GameSource.STEAM))
        assertEquals(2, commits)
    }

    @Test
    fun accountTransitionWaitsForStartedLicenseCommitThenInvalidatesIt() = runTest {
        val lifecycleState = InMemoryAccountLifecycleState()
        val readiness = SteamOwnershipReadiness(lifecycleState)
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()

        val commit = async {
            readiness.commitLicenseSnapshot(expectedGeneration = 0L) {
                commitStarted.complete(Unit)
                releaseCommit.await()
            }
        }
        commitStarted.await()
        val transitionStarted = CompletableDeferred<Unit>()
        val transition = async(Dispatchers.Default) {
            transitionStarted.complete(Unit)
            readiness.transitionAccount {}
        }
        transitionStarted.await()
        assertFalse(transition.isCompleted)

        releaseCommit.complete(Unit)

        assertTrue(commit.await())
        assertEquals(1L, transition.await())
        assertEquals(1L, lifecycleState.generation(GameSource.STEAM))
        assertNull(lifecycleState.readyGeneration(GameSource.STEAM))
    }

    @Test
    fun failedLicenseCommitNeverPublishesNewReadiness() = runTest {
        val lifecycleState = InMemoryAccountLifecycleState()
        val readiness = SteamOwnershipReadiness(lifecycleState)

        val initialFailure = runCatching {
            readiness.commitLicenseSnapshot(expectedGeneration = 0L) {
                error("transaction failed")
            }
        }
        assertTrue(initialFailure.isFailure)
        assertNull(lifecycleState.readyGeneration(GameSource.STEAM))

        assertTrue(readiness.commitLicenseSnapshot(expectedGeneration = 0L) {})
        val refreshFailure = runCatching {
            readiness.commitLicenseSnapshot(expectedGeneration = 0L) {
                error("refresh failed")
            }
        }
        assertTrue(refreshFailure.isFailure)
        assertEquals(0L, lifecycleState.readyGeneration(GameSource.STEAM))
    }

    @Test
    fun staleLogoutClearCannotDeleteCurrentGenerationRows() = runTest {
        val lifecycleState = InMemoryAccountLifecycleState()
        val readiness = SteamOwnershipReadiness(lifecycleState)
        var clears = 0
        val staleGeneration = readiness.currentGeneration()
        readiness.transitionAccount {}

        assertFalse(
            readiness.clearLicenseSnapshot(staleGeneration) {
                clears++
            },
        )
        assertEquals(0, clears)
    }
}
