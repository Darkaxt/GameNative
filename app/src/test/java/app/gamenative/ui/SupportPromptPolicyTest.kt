package app.gamenative.ui

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportPromptPolicyTest {
    private val sevenDays = TimeUnit.DAYS.toMillis(7)

    @Test
    fun firstSupportPromptIsDue() {
        assertTrue(isSupportPromptDue(lastShownAtMillis = 0L, nowMillis = 1L))
    }

    @Test
    fun shownPromptRemainsSuppressedUntilSevenDaysElapse() {
        val lastShownAtMillis = 1_000L

        assertFalse(
            isSupportPromptDue(
                lastShownAtMillis = lastShownAtMillis,
                nowMillis = lastShownAtMillis + sevenDays - 1L,
            ),
        )
        assertTrue(
            isSupportPromptDue(
                lastShownAtMillis = lastShownAtMillis,
                nowMillis = lastShownAtMillis + sevenDays,
            ),
        )
    }

    @Test
    fun clockRollbackDoesNotBypassSupportPromptThrottle() {
        assertFalse(isSupportPromptDue(lastShownAtMillis = 10_000L, nowMillis = 9_999L))
    }

    @Test
    fun dueSupportPromptIsPersistedBeforeClaimCompletes() = runTest {
        var persistedAtMillis: Long? = null

        val claimed = claimSupportPrompt(
            lastShownAtMillis = 0L,
            nowMillis = 42L,
            persistShownAt = { persistedAtMillis = it },
        )

        assertTrue(claimed)
        assertEquals(42L, persistedAtMillis)
    }

    @Test
    fun suppressedSupportPromptDoesNotChangePersistence() = runTest {
        var persistedAtMillis: Long? = null

        val claimed = claimSupportPrompt(
            lastShownAtMillis = 1_000L,
            nowMillis = 1_001L,
            persistShownAt = { persistedAtMillis = it },
        )

        assertFalse(claimed)
        assertNull(persistedAtMillis)
    }
}
