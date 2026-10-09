package app.gamenative.library.canonical

import java.util.concurrent.CountDownLatch
import org.junit.Assume.assumeTrue
import org.junit.Test

class CanonicalHostTimeoutProbeTest {
    companion object {
        @JvmStatic
        fun requireVerifiedDeadline(effectiveDeadlineMs: Long?) {
            check(effectiveDeadlineMs == 60_000L) {
                "Probe refuses to block without the verified one-minute task deadline"
            }
        }
    }

    @Test(timeout = 75_000)
    fun explicitlyEnabledBlockedWorkerMustBeStoppedByGradleTaskTimeout() {
        assumeTrue(System.getenv("GAMENATIVE_HOST_TIMEOUT_PROBE") == "1")
        requireVerifiedDeadline(System.getProperty("gamenative.hostTimeoutProbeDeadlineMs")?.toLongOrNull())
        val processHandleClass = Class.forName("java.lang.ProcessHandle")
        val handle = processHandleClass.getMethod("current").invoke(null)
        val pid = processHandleClass.getMethod("pid").invoke(handle)
        println("HOST_TIMEOUT_PROBE_WORKER_PID:$pid")
        CountDownLatch(1).await()
    }
}
