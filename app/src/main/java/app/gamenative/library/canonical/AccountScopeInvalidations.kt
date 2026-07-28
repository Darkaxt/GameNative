package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import java.util.concurrent.atomic.AtomicLongArray
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

object AccountScopeInvalidations {
    private val generations = AtomicLongArray(GameSource.entries.size)
    private val changes = MutableSharedFlow<GameSource>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun forSource(source: GameSource): Flow<Unit> = changes
        .filter { it == source }
        .map { Unit }

    fun generation(source: GameSource): Long = generations.get(source.ordinal)

    fun notifyChanged(source: GameSource) {
        generations.incrementAndGet(source.ordinal)
        changes.tryEmit(source)
    }
}
