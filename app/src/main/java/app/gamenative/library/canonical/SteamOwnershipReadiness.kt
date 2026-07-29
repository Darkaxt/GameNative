package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SteamOwnershipReadiness @Inject constructor(
    private val lifecycleState: AccountLifecycleState,
) {
    fun currentGeneration(): Long = lifecycleState.generation(GameSource.STEAM)

    fun isReady(generation: Long): Boolean =
        lifecycleState.readyGeneration(GameSource.STEAM) == generation

    fun transitionAccount(block: () -> Unit): Long = AccountLifecycleSerialization.blocking {
        advanceAndPublish(block)
    }

    fun clearAccount(
        hasAccountIdentity: Boolean,
        block: () -> Unit,
    ): Long = AccountLifecycleSerialization.blocking {
        val lifecycleAlreadyClear =
            !hasAccountIdentity && lifecycleState.readyGeneration(GameSource.STEAM) == null
        if (lifecycleAlreadyClear) {
            block()
            currentGeneration()
        } else {
            advanceAndPublish(block)
        }
    }

    suspend fun commitLicenseSnapshot(
        expectedGeneration: Long,
        commit: suspend () -> Unit,
    ): Boolean = withCurrentGeneration(expectedGeneration) {
        commit()
        check(lifecycleState.markReady(GameSource.STEAM, expectedGeneration)) {
            "STEAM_OWNERSHIP_LIFECYCLE_CHANGED"
        }
        AccountScopeInvalidations.publishInvalidation(GameSource.STEAM)
    }

    suspend fun clearLicenseSnapshot(
        expectedGeneration: Long,
        clear: suspend () -> Unit,
    ): Boolean = withCurrentGeneration(expectedGeneration, clear)

    private suspend fun withCurrentGeneration(
        expectedGeneration: Long,
        block: suspend () -> Unit,
    ): Boolean = AccountLifecycleSerialization.suspending {
        if (currentGeneration() != expectedGeneration) return@suspending false
        block()
        true
    }

    private fun advanceAndPublish(block: () -> Unit): Long {
        val generation = lifecycleState.advanceGeneration(GameSource.STEAM)
        return try {
            block()
            generation
        } finally {
            AccountScopeInvalidations.publishInvalidation(GameSource.STEAM)
        }
    }
}
