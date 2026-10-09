package app.gamenative.library.canonical

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.db.PluviaDatabase
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.junit.runners.model.TestTimedOutException
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.junit.rules.TimeoutRule

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [29])
class CanonicalHostTestContainmentTest {
    @get:Rule val timeout = TimeoutRule.seconds(30)

    @Test
    fun mutationTestsHaveAnInterruptingWallClockDeadline() {
        assertHasTimeoutRule(CanonicalMutationRepositoryTest::class.java)
    }

    @Test
    fun ledgerTestsHaveAnInterruptingWallClockDeadline() {
        assertHasTimeoutRule(AccountScopedOwnershipLedgerTest::class.java)
    }

    @Test
    fun timeoutCancelsBlockedCoroutineAndRunsItsFinallyWithoutMovingTheTestThread() {
        val testThread = Thread.currentThread()
        val cancelled = AtomicBoolean(false)
        val statement = object : Statement() {
            override fun evaluate() {
                runBlocking {
                    assertEquals(testThread, Thread.currentThread())
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.set(true)
                    }
                }
            }
        }
        val startedAt = System.nanoTime()
        val failure = runCatching {
            TimeoutRule.millis(100).apply(
                statement,
                Description.createTestDescription(javaClass, "blockedCoroutine"),
            ).evaluate()
        }.exceptionOrNull()

        assertTrue("Blocked coroutine must fail, not be reported as passed", failure is TestTimedOutException)
        assertTrue("Timeout must run coroutine cleanup", cancelled.get())
        assertTrue("Timeout must return promptly", System.nanoTime() - startedAt < 5_000_000_000L)
        assertFalse("Interruption must not poison the next test", Thread.currentThread().isInterrupted)
    }

    @Test
    fun cancelledRoomCollectorsAreJoinedBeforeCloseAndTheNextDatabaseOpens() = runBlocking {
        withTimeout(10_000) {
            repeat(8) {
                val context = ApplicationProvider.getApplicationContext<Application>()
                val database = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
                try {
                    val initialEmission = CompletableDeferred<Unit>()
                    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                        database.canonicalLibraryDao().observePresentGames().collect { games ->
                            assertTrue(games.isEmpty())
                            initialEmission.complete(Unit)
                        }
                    }
                    try {
                        initialEmission.await()
                    } finally {
                        collector.cancelAndJoin()
                    }
                    assertTrue(collector.isCompleted)
                } finally {
                    database.close()
                }
                assertFalse(database.isOpen)
            }
        }
    }

    private fun assertHasTimeoutRule(testClass: Class<*>) {
        val rules = testClass.methods.filter { method ->
            method.isAnnotationPresent(Rule::class.java) &&
                TimeoutRule::class.java.isAssignableFrom(method.returnType)
        }
        assertEquals("${testClass.simpleName} must bound setup, test body and teardown", 1, rules.size)
    }
}
