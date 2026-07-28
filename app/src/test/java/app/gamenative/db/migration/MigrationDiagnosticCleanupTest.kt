package app.gamenative.db.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationDiagnosticCleanupTest {
    @Test
    fun `unacknowledged diagnostic retains marker without cleanup`() {
        var markerPresent = true
        var cleanupAttempts = 0

        acknowledgeAndCleanupPendingMigrationSuccess(
            acknowledge = { false },
            cleanup = {
                cleanupAttempts++
                markerPresent = false
            },
            logCleanupFailure = { throw AssertionError("Cleanup failure was not expected") },
        )

        assertTrue(markerPresent)
        assertEquals(0, cleanupAttempts)
    }

    @Test
    fun `cleanup failure retains marker logs only error type and retries`() {
        var markerPresent = true
        var acknowledgements = 0
        var cleanupAttempts = 0
        val loggedErrorTypes = mutableListOf<String>()

        repeat(2) {
            acknowledgeAndCleanupPendingMigrationSuccess(
                acknowledge = {
                    acknowledgements++
                    true
                },
                cleanup = {
                    cleanupAttempts++
                    if (cleanupAttempts == 1) {
                        throw IllegalStateException("sensitive-database-detail")
                    }
                    markerPresent = false
                },
                logCleanupFailure = loggedErrorTypes::add,
            )
        }

        assertFalse(markerPresent)
        assertEquals(2, acknowledgements)
        assertEquals(2, cleanupAttempts)
        assertEquals(listOf("IllegalStateException"), loggedErrorTypes)
    }
}
