package app.gamenative.library.canonical

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object AccountLifecycleSerialization {
    private val mutex = Mutex()

    fun <T> blocking(block: () -> T): T = runBlocking {
        mutex.withLock { block() }
    }

    suspend fun <T> suspending(block: suspend () -> T): T =
        mutex.withLock { block() }
}
