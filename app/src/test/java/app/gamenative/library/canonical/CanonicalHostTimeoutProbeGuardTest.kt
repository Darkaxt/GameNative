package app.gamenative.library.canonical

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalHostTimeoutProbeGuardTest {
    @Test(timeout = 5_000)
    fun probeRejectsMissingOrWrongEffectiveDeadlineBeforeBlocking() {
        val guard = guard()
        listOf(null, 0L, 5_000L, 1_800_000L).forEach { deadline ->
            val failure = runCatching { guard.invoke(null, deadline) }.exceptionOrNull()
            assertTrue(
                "Probe must refuse to block under deadline $deadline",
                failure is InvocationTargetException && failure.targetException is IllegalStateException,
            )
        }
    }

    @Test(timeout = 5_000)
    fun probeAllowsOnlyTheVerifiedOneMinuteDeadline() {
        guard().invoke(null, 60_000L)
    }

    private fun guard(): Method {
        val method = CanonicalHostTimeoutProbeTest::class.java.methods.singleOrNull {
            it.name == "requireVerifiedDeadline"
        }
        assertNotNull("Blocked-worker probe needs a fail-closed effective-deadline guard", method)
        return requireNotNull(method)
    }
}
