package app.gamenative.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalGamePreferenceEntity
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.PluviaDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class CanonicalLibraryDaoTest {
    private lateinit var database: PluviaDatabase
    private lateinit var dao: CanonicalLibraryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.canonicalLibraryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun presentParentsIncludeAllRelationshipsAndPreference() = runTest {
        val included = game(INCLUDED_ID)
        val absentOnly = game(ABSENT_ONLY_ID)
        database.canonicalGameDao().insert(included)
        database.canonicalGameDao().insert(absentOnly)
        val includedMatches = listOf(
            match(included.canonicalId, "steam-copy", GameSource.STEAM, isPresent = true),
            match(included.canonicalId, "gog-copy", GameSource.GOG, isPresent = true),
            match(included.canonicalId, "old-epic-copy", GameSource.EPIC, isPresent = false),
        )
        includedMatches.forEach { database.storeMatchDao().upsert(it) }
        database.storeMatchDao().upsert(
            match(absentOnly.canonicalId, "absent-copy", GameSource.AMAZON, isPresent = false),
        )
        val preference = preference(included.canonicalId, "preferred-copy")
        database.canonicalPreferenceDao().upsert(preference)

        val aggregates = dao.observePresentGames().first()

        assertEquals(listOf(included.canonicalId), aggregates.map { it.game.canonicalId })
        assertEquals(
            includedMatches
                .map { Triple(it.source, it.stableSourceId, it.isPresent) }
                .toSet(),
            aggregates.single().matches
                .map { Triple(it.source, it.stableSourceId, it.isPresent) }
                .toSet(),
        )
        assertEquals(preference, aggregates.single().preferenceOrNull())
    }

    @Test
    fun presentParentsAreOrderedByCanonicalId() = runTest {
        val later = game(LATER_ID)
        val earlier = game(EARLIER_ID)
        database.canonicalGameDao().insert(later)
        database.canonicalGameDao().insert(earlier)
        database.storeMatchDao().upsert(match(later.canonicalId, "later-copy", GameSource.GOG))
        database.storeMatchDao().upsert(match(earlier.canonicalId, "earlier-copy", GameSource.STEAM))

        val aggregates = dao.observePresentGames().first()

        assertEquals(listOf(earlier.canonicalId, later.canonicalId), aggregates.map { it.game.canonicalId })
    }

    @Test
    fun preferenceAccessorAcceptsOnlyZeroOrOneRow() {
        val game = game(INCLUDED_ID)
        val first = preference(game.canonicalId, "first")
        val second = preference(game.canonicalId, "second")

        assertNull(aggregate(game, emptyList()).preferenceOrNull())
        assertSame(first, aggregate(game, listOf(first)).preferenceOrNull())
        assertNull(aggregate(game, listOf(first, second)).preferenceOrNull())
    }

    @Test
    fun storeMatchPresenceChangeInvalidatesFlowAndFiltersParent() = runTest {
        val game = game(INCLUDED_ID)
        val presentMatch = match(game.canonicalId, "copy", GameSource.STEAM)
        database.canonicalGameDao().insert(game)
        database.storeMatchDao().upsert(presentMatch)
        val initial = CompletableDeferred<List<CanonicalLibraryAggregate>>()
        val emissions = async(start = CoroutineStart.UNDISPATCHED) {
            dao.observePresentGames()
                .onEach { aggregates ->
                    if (!initial.isCompleted) initial.complete(aggregates)
                }
                .take(2)
                .toList()
        }
        assertEquals(listOf(game.canonicalId), initial.await().map { it.game.canonicalId })

        database.storeMatchDao().upsert(presentMatch.copy(isPresent = false))

        assertEquals(emptyList<CanonicalLibraryAggregate>(), emissions.await().last())
    }

    @Test
    fun preferredCopyTripleChangeInvalidatesFlow() = runTest {
        val game = game(INCLUDED_ID)
        database.canonicalGameDao().insert(game)
        database.storeMatchDao().upsert(match(game.canonicalId, "copy", GameSource.STEAM))
        val initialPreference = preference(game.canonicalId, "first")
        database.canonicalPreferenceDao().upsert(initialPreference)
        val initial = CompletableDeferred<CanonicalGamePreferenceEntity?>()
        val emissions = async(start = CoroutineStart.UNDISPATCHED) {
            dao.observePresentGames()
                .map { aggregates -> aggregates.single().preferenceOrNull() }
                .distinctUntilChanged()
                .onEach { preference ->
                    if (!initial.isCompleted) initial.complete(preference)
                }
                .take(2)
                .toList()
        }
        assertEquals(initialPreference, initial.await())
        val changedPreference = initialPreference.copy(
            preferredAccountScope = SCOPE_B,
            preferredSource = GameSource.GOG,
            preferredStableSourceId = "second",
            updatedAt = 2L,
        )

        database.canonicalPreferenceDao().upsert(changedPreference)

        assertEquals(changedPreference, emissions.await().last())
    }

    @Test
    fun aggregateReadModelDoesNotAddAnEntityOrChangeSchemaVersion() {
        val sqlite = database.openHelper.readableDatabase

        assertEquals(27, sqlite.version)
        val tables = sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertFalse(
            tables.any { table ->
                table.replace("_", "").contains("canonicallibrary", ignoreCase = true)
            },
        )
    }

    private fun aggregate(
        game: CanonicalGameEntity,
        preferences: List<CanonicalGamePreferenceEntity>,
    ) = CanonicalLibraryAggregate(
        game = game,
        matches = emptyList(),
        preferences = preferences,
    )

    private fun game(canonicalId: String) = CanonicalGameEntity(
        canonicalId = canonicalId,
        steamAppId = null,
        displayName = "Game $canonicalId",
        matchTitleKey = canonicalId,
        primaryMetadataSource = GameSource.STEAM,
        appType = CanonicalAppType.GAME,
        releaseYear = 2026,
        developerKey = "developer",
        classificationState = ClassificationState.CLASSIFIED,
        steamReviewCount = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun match(
        canonicalId: String,
        stableSourceId: String,
        source: GameSource,
        isPresent: Boolean = true,
    ) = StoreMatchEntity(
        accountScope = SCOPE_A,
        source = source,
        stableSourceId = stableSourceId,
        canonicalId = canonicalId,
        candidateSteamAppId = null,
        matchMethod = MatchMethod.EXACT_METADATA,
        confidence = MatchConfidence.HIGH,
        decisionSource = MatchDecisionSource.AUTOMATIC,
        resolverVersion = 1,
        matchedAt = 1L,
        isPresent = isPresent,
        evidenceDisplayName = "Copy $stableSourceId",
        evidenceTitleKey = stableSourceId,
        evidenceDeveloperKey = "developer",
        evidenceReleaseYear = 2026,
        evidenceAppType = CanonicalAppType.GAME,
    )

    private fun preference(
        canonicalId: String,
        stableSourceId: String,
    ) = CanonicalGamePreferenceEntity(
        canonicalId = canonicalId,
        preferredAccountScope = SCOPE_A,
        preferredSource = GameSource.STEAM,
        preferredStableSourceId = stableSourceId,
        titleOverride = null,
        artworkOverrideJson = null,
        updatedAt = 1L,
    )

    private companion object {
        const val EARLIER_ID = "11111111-1111-1111-1111-111111111111"
        const val LATER_ID = "22222222-2222-2222-2222-222222222222"
        const val INCLUDED_ID = "33333333-3333-3333-3333-333333333333"
        const val ABSENT_ONLY_ID = "44444444-4444-4444-4444-444444444444"
        val SCOPE_A = "a".repeat(64)
        val SCOPE_B = "b".repeat(64)
    }
}
