package app.gamenative.library.canonical

import android.content.Context
import android.content.SharedPreferences
import app.gamenative.data.GameSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLongArray
import javax.inject.Inject
import javax.inject.Singleton

interface AccountLifecycleState {
    fun generation(source: GameSource): Long

    fun advanceGeneration(source: GameSource): Long
}

internal class InMemoryAccountLifecycleState : AccountLifecycleState {
    private val generations = AtomicLongArray(GameSource.entries.size)

    override fun generation(source: GameSource): Long = generations.get(source.ordinal)

    override fun advanceGeneration(source: GameSource): Long {
        while (true) {
            val current = generation(source)
            check(current < Long.MAX_VALUE) { "ACCOUNT_LIFECYCLE_GENERATION_EXHAUSTED" }
            if (generations.compareAndSet(source.ordinal, current, current + 1)) {
                return current + 1
            }
        }
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
            set(source.ordinal, preferences.getLong(source.preferenceKey(), 0L).coerceAtLeast(0L))
        }
    }

    override fun generation(source: GameSource): Long = generations.get(source.ordinal)

    override fun advanceGeneration(source: GameSource): Long = synchronized(lock) {
        val current = generation(source)
        check(current < Long.MAX_VALUE) { "ACCOUNT_LIFECYCLE_GENERATION_EXHAUSTED" }
        val next = current + 1
        check(preferences.edit().putLong(source.preferenceKey(), next).commit()) {
            "ACCOUNT_LIFECYCLE_PERSIST_FAILED"
        }
        generations.set(source.ordinal, next)
        next
    }

    companion object {
        private const val PREFERENCES_NAME = "canonical_account_lifecycle"
        private const val KEY_PREFIX = "generation_"

        private fun preferences(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        private fun GameSource.preferenceKey(): String = KEY_PREFIX + name.lowercase()

        internal fun createForTest(preferences: SharedPreferences): SharedPreferencesAccountLifecycleState =
            SharedPreferencesAccountLifecycleState(preferences)

        internal fun clearForTest(context: Context) {
            check(preferences(context).edit().clear().commit())
        }
    }
}
