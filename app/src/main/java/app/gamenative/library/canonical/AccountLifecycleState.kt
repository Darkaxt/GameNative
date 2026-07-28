package app.gamenative.library.canonical

import android.content.Context
import android.content.SharedPreferences
import app.gamenative.data.GameSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLongArray
import javax.inject.Inject
import javax.inject.Singleton

private const val NOT_READY = -1L

interface AccountLifecycleState {
    fun generation(source: GameSource): Long

    fun readyGeneration(source: GameSource): Long?

    fun advanceGeneration(source: GameSource): Long

    fun markReady(source: GameSource, expectedGeneration: Long): Boolean
}

internal class InMemoryAccountLifecycleState : AccountLifecycleState {
    private val lock = Any()
    private val generations = AtomicLongArray(GameSource.entries.size)
    private val readyGenerations = AtomicLongArray(GameSource.entries.size).apply {
        GameSource.entries.forEach { source -> set(source.ordinal, NOT_READY) }
    }

    override fun generation(source: GameSource): Long = generations.get(source.ordinal)

    override fun readyGeneration(source: GameSource): Long? =
        readyGenerations.get(source.ordinal).takeIf { it >= 0L }

    override fun advanceGeneration(source: GameSource): Long = synchronized(lock) {
        val current = generation(source)
        check(current < Long.MAX_VALUE) { "ACCOUNT_LIFECYCLE_GENERATION_EXHAUSTED" }
        val next = current + 1
        generations.set(source.ordinal, next)
        readyGenerations.set(source.ordinal, NOT_READY)
        next
    }

    override fun markReady(source: GameSource, expectedGeneration: Long): Boolean = synchronized(lock) {
        require(expectedGeneration >= 0)
        if (generation(source) != expectedGeneration) return@synchronized false
        readyGenerations.set(source.ordinal, expectedGeneration)
        true
    }
}

@Singleton
class SharedPreferencesAccountLifecycleState private constructor(
    private val preferences: SharedPreferences,
) : AccountLifecycleState {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(preferences(context))

    private val lock = Any()
    private val generations = AtomicLongArray(GameSource.entries.size).apply {
        GameSource.entries.forEach { source ->
            set(source.ordinal, preferences.getLong(source.generationKey(), 0L).coerceAtLeast(0L))
        }
    }
    private val readyGenerations = AtomicLongArray(GameSource.entries.size).apply {
        GameSource.entries.forEach { source ->
            set(source.ordinal, preferences.getLong(source.readyGenerationKey(), NOT_READY))
        }
    }

    override fun generation(source: GameSource): Long = generations.get(source.ordinal)

    override fun readyGeneration(source: GameSource): Long? =
        readyGenerations.get(source.ordinal).takeIf { it >= 0L }

    override fun advanceGeneration(source: GameSource): Long = synchronized(lock) {
        val current = generation(source)
        check(current < Long.MAX_VALUE) { "ACCOUNT_LIFECYCLE_GENERATION_EXHAUSTED" }
        val next = current + 1
        check(
            preferences.edit()
                .putLong(source.generationKey(), next)
                .remove(source.readyGenerationKey())
                .commit(),
        ) {
            "ACCOUNT_LIFECYCLE_PERSIST_FAILED"
        }
        generations.set(source.ordinal, next)
        readyGenerations.set(source.ordinal, NOT_READY)
        next
    }

    override fun markReady(source: GameSource, expectedGeneration: Long): Boolean = synchronized(lock) {
        require(expectedGeneration >= 0)
        if (generation(source) != expectedGeneration) return@synchronized false
        if (readyGeneration(source) == expectedGeneration) return@synchronized true
        check(
            preferences.edit()
                .putLong(source.readyGenerationKey(), expectedGeneration)
                .commit(),
        ) {
            "ACCOUNT_LIFECYCLE_PERSIST_FAILED"
        }
        readyGenerations.set(source.ordinal, expectedGeneration)
        true
    }

    companion object {
        private const val PREFERENCES_NAME = "canonical_account_lifecycle"
        private const val GENERATION_KEY_PREFIX = "generation_"
        private const val READY_GENERATION_KEY_PREFIX = "ready_generation_"

        private fun preferences(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        private fun GameSource.generationKey(): String = GENERATION_KEY_PREFIX + name.lowercase()

        private fun GameSource.readyGenerationKey(): String =
            READY_GENERATION_KEY_PREFIX + name.lowercase()

        internal fun createForTest(preferences: SharedPreferences): SharedPreferencesAccountLifecycleState =
            SharedPreferencesAccountLifecycleState(preferences)

        internal fun clearForTest(context: Context) {
            check(preferences(context).edit().clear().commit())
        }
    }
}
