package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

internal class AccountLifecycleChange(
    val source: GameSource,
)

object AccountScopeInvalidations : AccountLifecycleState {
    @Volatile
    private var lifecycleState: AccountLifecycleState = InMemoryAccountLifecycleState()

    private val changes = MutableSharedFlow<GameSource>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun forSource(source: GameSource): Flow<Unit> = changes
        .filter { it == source }
        .map { Unit }

    override fun generation(source: GameSource): Long = lifecycleState.generation(source)

    override fun readyGeneration(source: GameSource): Long? =
        lifecycleState.readyGeneration(source)

    override fun advanceGeneration(source: GameSource): Long =
        lifecycleState.advanceGeneration(source)

    override fun markReady(source: GameSource, expectedGeneration: Long): Boolean =
        lifecycleState.markReady(source, expectedGeneration)

    internal fun install(state: AccountLifecycleState) {
        lifecycleState = state
    }

    internal fun beginChange(source: GameSource): AccountLifecycleChange {
        advanceGeneration(source)
        return AccountLifecycleChange(source)
    }

    internal fun publishChange(change: AccountLifecycleChange) {
        publishInvalidation(change.source)
    }

    internal fun publishInvalidation(source: GameSource) {
        changes.tryEmit(source)
    }

    internal inline fun <T> runLifecycleChange(
        source: GameSource,
        shouldAdvance: Boolean = true,
        block: () -> T,
    ): T {
        if (!shouldAdvance) return block()

        val change = beginChange(source)
        return try {
            block()
        } finally {
            publishChange(change)
        }
    }

    fun notifyChanged(source: GameSource) {
        publishChange(beginChange(source))
    }
}
