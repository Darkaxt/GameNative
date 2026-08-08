package app.gamenative.library.canonical

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GOGGame
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalGameFeatureCrossRef
import app.gamenative.data.canonical.CanonicalGameGenreCrossRef
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.CanonicalGamePreferenceEntity
import app.gamenative.data.canonical.CanonicalGameTagCrossRef
import app.gamenative.data.canonical.CanonicalIdGenerator
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.GameDetailSnapshotEntity
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.PluviaDatabase
import app.gamenative.library.canonical.source.OwnedCopyProjection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class CanonicalMutationRepositoryTest {

    private lateinit var db: PluviaDatabase
    private lateinit var idGenerator: SequentialCanonicalIdGenerator
    private lateinit var repository: RoomCanonicalMutationRepository

    private val primaryScope = AccountScope("1".repeat(64))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        idGenerator = SequentialCanonicalIdGenerator()
        repository = RoomCanonicalMutationRepository(db, idGenerator)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `confirming standalone match adds Steam identity without changing canonical id`() = runBlocking {
        val canonical = canonical(index = 1, steamAppId = null, createdAt = 100)
        val key = key(GameSource.GOG, "gog-1")
        val match = match(key, canonical.canonicalId)
        db.canonicalGameDao().insert(canonical)
        db.storeMatchDao().upsert(match)

        val result = repository.confirmSteamMatch(key, steamAppId = 99, nowEpochMs = 200)

        assertEquals(canonical.canonicalId, result)
        val storedCanonical = db.canonicalGameDao().getAll().single()
        assertEquals(canonical.canonicalId, storedCanonical.canonicalId)
        assertEquals(99, storedCanonical.steamAppId)
        assertEquals(100, storedCanonical.createdAt)
        assertEquals(200, storedCanonical.updatedAt)
        assertManualDecision(
            match = requireNotNull(db.storeMatchDao().get(key)),
            canonicalId = canonical.canonicalId,
            steamAppId = 99,
            confidence = MatchConfidence.VERIFIED,
            matchedAt = 200,
        )
    }

    @Test
    fun `confirming existing Steam target merges dependencies and preserves source rows`() = runBlocking {
        val loser = canonical(index = 1, steamAppId = null, createdAt = 100)
        val survivor = canonical(
            index = 2,
            steamAppId = 99,
            createdAt = 200,
            displayName = "Steam Game",
            primarySource = GameSource.STEAM,
            reviewCount = 500,
        )
        val selectedKey = key(GameSource.GOG, "gog-1")
        val directKey = key(GameSource.STEAM, "99")
        db.canonicalGameDao().insert(loser)
        db.canonicalGameDao().insert(survivor)
        db.storeMatchDao().upsert(match(selectedKey, loser.canonicalId))
        db.storeMatchDao().upsert(
            match(
                directKey,
                survivor.canonicalId,
                method = MatchMethod.DIRECT_STEAM,
                confidence = MatchConfidence.VERIFIED,
                candidateSteamAppId = 99,
            ),
        )
        seedFacets(
            canonicalId = loser.canonicalId,
            genres = setOf("gog:rpg"),
            tags = setOf(5),
            features = setOf("gog:offline"),
        )
        seedFacets(
            canonicalId = survivor.canonicalId,
            genres = setOf("steam:action"),
            tags = setOf(10),
            features = setOf("steam:controller"),
        )
        db.gameDetailSnapshotDao().upsert(snapshot(loser.canonicalId, "en", "US", "loser"))
        db.gameDetailSnapshotDao().upsert(snapshot(loser.canonicalId, "en", "GB", "loser-gb"))
        db.gameDetailSnapshotDao().upsert(snapshot(loser.canonicalId, "de", "DE", "loser-de"))
        db.gameDetailSnapshotDao().upsert(snapshot(survivor.canonicalId, "en", "US", "survivor"))
        db.canonicalPreferenceDao().upsert(
            preference(
                canonicalId = loser.canonicalId,
                preferredKey = selectedKey,
                titleOverride = "Loser title",
                artworkOverride = "loser-art",
                updatedAt = 110,
            ),
        )
        db.canonicalPreferenceDao().upsert(
            preference(
                canonicalId = survivor.canonicalId,
                preferredKey = directKey,
                titleOverride = "Survivor title",
                artworkOverride = null,
                updatedAt = 120,
            ),
        )
        val sourceRow = GOGGame(
            id = "gog-1",
            title = "Source title",
            isInstalled = true,
            installPath = "/synthetic/private/path",
        )
        db.gogGameDao().insert(sourceRow)

        val result = repository.confirmSteamMatch(selectedKey, steamAppId = 99, nowEpochMs = 300)

        assertEquals(survivor.canonicalId, result)
        assertNull(db.canonicalGameDao().get(loser.canonicalId))
        val storedSurvivor = requireNotNull(db.canonicalGameDao().get(survivor.canonicalId))
        assertEquals(99, storedSurvivor.steamAppId)
        assertEquals("Steam Game", storedSurvivor.displayName)
        assertEquals(500L, storedSurvivor.steamReviewCount)
        assertEquals(ClassificationState.CLASSIFIED, storedSurvivor.classificationState)
        assertTrue(db.storeMatchDao().getAll().all { it.canonicalId == survivor.canonicalId })
        assertManualDecision(
            match = requireNotNull(db.storeMatchDao().get(selectedKey)),
            canonicalId = survivor.canonicalId,
            steamAppId = 99,
            confidence = MatchConfidence.VERIFIED,
            matchedAt = 300,
        )
        assertEquals(MatchMethod.DIRECT_STEAM, requireNotNull(db.storeMatchDao().get(directKey)).matchMethod)
        assertEquals(
            listOf("gog:rpg", "steam:action"),
            db.canonicalFacetDao().getGenres(survivor.canonicalId).map { it.genreKey },
        )
        assertEquals(
            listOf(5, 10),
            db.canonicalFacetDao().getTags(survivor.canonicalId).map { it.tagId },
        )
        assertEquals(
            listOf("gog:offline", "steam:controller"),
            db.canonicalFacetDao().getFeatures(survivor.canonicalId).map { it.featureKey },
        )
        val snapshots = db.gameDetailSnapshotDao().getByCanonicalId(survivor.canonicalId)
        assertEquals(3, snapshots.size)
        assertEquals(
            "survivor",
            snapshots.single { it.locale == "en" && it.country == "US" }.payloadJson,
        )
        assertEquals(
            "loser-gb",
            snapshots.single { it.locale == "en" && it.country == "GB" }.payloadJson,
        )
        assertEquals("loser-de", snapshots.single { it.locale == "de" }.payloadJson)
        val mergedPreference = requireNotNull(db.canonicalPreferenceDao().get(survivor.canonicalId))
        assertEquals(directKey, mergedPreference.preferredCopyKeyOrNull())
        assertEquals("Survivor title", mergedPreference.titleOverride)
        assertEquals("loser-art", mergedPreference.artworkOverrideJson)
        assertEquals(sourceRow, db.gogGameDao().getById(sourceRow.id))
    }

    @Test
    fun `merge inherits loser preferred copy when survivor has none`() = runBlocking {
        val loser = canonical(index = 1, steamAppId = null, createdAt = 100)
        val survivor = canonical(index = 2, steamAppId = 99, createdAt = 200)
        val selectedKey = key(GameSource.GOG, "selected")
        val directKey = key(GameSource.STEAM, "99")
        db.canonicalGameDao().insert(loser)
        db.canonicalGameDao().insert(survivor)
        db.storeMatchDao().upsert(match(selectedKey, loser.canonicalId))
        db.storeMatchDao().upsert(directMatch(directKey, survivor.canonicalId, 99))
        db.canonicalPreferenceDao().upsert(
            preference(loser.canonicalId, selectedKey, "Loser title", "loser-art", 100),
        )
        db.canonicalPreferenceDao().upsert(
            preference(survivor.canonicalId, null, "Survivor title", null, 150),
        )

        repository.confirmSteamMatch(selectedKey, steamAppId = 99, nowEpochMs = 300)

        val merged = requireNotNull(db.canonicalPreferenceDao().get(survivor.canonicalId))
        assertEquals(selectedKey, merged.preferredCopyKeyOrNull())
        assertEquals(survivor.canonicalId, requireNotNull(db.storeMatchDao().get(selectedKey)).canonicalId)
        assertEquals("Survivor title", merged.titleOverride)
        assertEquals("loser-art", merged.artworkOverrideJson)
    }

    @Test
    fun `correction detaches only selected copy from conflicting Steam group`() = runBlocking {
        val oldCanonical = canonical(index = 1, steamAppId = 10, createdAt = 100)
        val newCanonical = canonical(index = 2, steamAppId = 20, createdAt = 200)
        val selectedKey = key(GameSource.GOG, "selected")
        val peerKey = key(GameSource.EPIC, "peer")
        val oldDirectKey = key(GameSource.STEAM, "10")
        val newDirectKey = key(GameSource.STEAM, "20")
        db.canonicalGameDao().insert(oldCanonical)
        db.canonicalGameDao().insert(newCanonical)
        db.storeMatchDao().upsert(match(selectedKey, oldCanonical.canonicalId))
        db.storeMatchDao().upsert(match(peerKey, oldCanonical.canonicalId))
        db.storeMatchDao().upsert(directMatch(oldDirectKey, oldCanonical.canonicalId, 10))
        db.storeMatchDao().upsert(directMatch(newDirectKey, newCanonical.canonicalId, 20))
        db.canonicalPreferenceDao().upsert(
            preference(oldCanonical.canonicalId, selectedKey, "Old title", "old-art", 100),
        )
        val targetPreference = preference(
            newCanonical.canonicalId,
            newDirectKey,
            "New title",
            "new-art",
            200,
        )
        db.canonicalPreferenceDao().upsert(targetPreference)

        val result = repository.confirmSteamMatch(selectedKey, steamAppId = 20, nowEpochMs = 300)

        assertEquals(newCanonical.canonicalId, result)
        assertEquals(newCanonical.canonicalId, requireNotNull(db.storeMatchDao().get(selectedKey)).canonicalId)
        assertEquals(oldCanonical.canonicalId, requireNotNull(db.storeMatchDao().get(peerKey)).canonicalId)
        assertEquals(oldCanonical.canonicalId, requireNotNull(db.storeMatchDao().get(oldDirectKey)).canonicalId)
        assertEquals(newCanonical.canonicalId, requireNotNull(db.storeMatchDao().get(newDirectKey)).canonicalId)
        assertEquals(10, requireNotNull(db.canonicalGameDao().get(oldCanonical.canonicalId)).steamAppId)
        assertEquals(20, requireNotNull(db.canonicalGameDao().get(newCanonical.canonicalId)).steamAppId)
        val oldPreference = requireNotNull(db.canonicalPreferenceDao().get(oldCanonical.canonicalId))
        assertNull(oldPreference.preferredCopyKeyOrNull())
        assertEquals("Old title", oldPreference.titleOverride)
        assertEquals("old-art", oldPreference.artworkOverrideJson)
        assertEquals(targetPreference, db.canonicalPreferenceDao().get(newCanonical.canonicalId))
    }

    @Test
    fun `correction without target updates sole copy in place or splits grouped copy`() = runBlocking {
        val soleCanonical = canonical(index = 1, steamAppId = 10, createdAt = 100)
        val soleKey = key(GameSource.GOG, "sole")
        db.canonicalGameDao().insert(soleCanonical)
        db.storeMatchDao().upsert(match(soleKey, soleCanonical.canonicalId))

        val inPlace = repository.confirmSteamMatch(soleKey, steamAppId = 20, nowEpochMs = 200)
        assertEquals(soleCanonical.canonicalId, inPlace)
        assertEquals(20, requireNotNull(db.canonicalGameDao().get(inPlace)).steamAppId)

        val groupedCanonical = canonical(index = 2, steamAppId = 30, createdAt = 300)
        val selectedKey = key(GameSource.GOG, "grouped-selected")
        val peerKey = key(GameSource.AMAZON, "grouped-peer")
        db.canonicalGameDao().insert(groupedCanonical)
        db.storeMatchDao().upsert(match(selectedKey, groupedCanonical.canonicalId))
        db.storeMatchDao().upsert(match(peerKey, groupedCanonical.canonicalId))

        val detached = repository.confirmSteamMatch(selectedKey, steamAppId = 40, nowEpochMs = 400)

        assertNotEquals(groupedCanonical.canonicalId, detached)
        assertEquals(40, requireNotNull(db.canonicalGameDao().get(detached)).steamAppId)
        assertEquals(detached, requireNotNull(db.storeMatchDao().get(selectedKey)).canonicalId)
        assertEquals(groupedCanonical.canonicalId, requireNotNull(db.storeMatchDao().get(peerKey)).canonicalId)
        assertEquals(30, requireNotNull(db.canonicalGameDao().get(groupedCanonical.canonicalId)).steamAppId)
    }

    @Test
    fun `confirmation detaches selected copy when sibling rejected requested Steam identity`() = runBlocking {
        val standalone = canonical(index = 1, steamAppId = null, createdAt = 100)
        val selectedKey = key(GameSource.GOG, "selected-no-target")
        val rejectedKey = key(GameSource.EPIC, "rejected-no-target")
        db.canonicalGameDao().insert(standalone)
        db.storeMatchDao().upsert(match(selectedKey, standalone.canonicalId))
        db.storeMatchDao().upsert(
            match(
                key = rejectedKey,
                canonicalId = standalone.canonicalId,
                method = MatchMethod.MANUAL,
                confidence = MatchConfidence.REJECTED,
                candidateSteamAppId = 99,
            ).copy(decisionSource = MatchDecisionSource.USER),
        )

        val createdTarget = repository.confirmSteamMatch(selectedKey, 99, 200)

        assertNotEquals(standalone.canonicalId, createdTarget)
        assertNull(requireNotNull(db.canonicalGameDao().get(standalone.canonicalId)).steamAppId)
        assertEquals(createdTarget, requireNotNull(db.storeMatchDao().get(selectedKey)).canonicalId)
        val retainedRejection = requireNotNull(db.storeMatchDao().get(rejectedKey))
        assertEquals(standalone.canonicalId, retainedRejection.canonicalId)
        assertEquals(MatchConfidence.REJECTED, retainedRejection.confidence)
        assertEquals(99, retainedRejection.candidateSteamAppId)

        val secondStandalone = canonical(index = 2, steamAppId = null, createdAt = 300)
        val existingTarget = canonical(index = 3, steamAppId = 199, createdAt = 400)
        val secondSelectedKey = key(GameSource.GOG, "selected-with-target")
        val secondRejectedKey = key(GameSource.AMAZON, "rejected-with-target")
        db.canonicalGameDao().insert(secondStandalone)
        db.canonicalGameDao().insert(existingTarget)
        db.storeMatchDao().upsert(match(secondSelectedKey, secondStandalone.canonicalId))
        db.storeMatchDao().upsert(
            match(
                key = secondRejectedKey,
                canonicalId = secondStandalone.canonicalId,
                method = MatchMethod.MANUAL,
                confidence = MatchConfidence.REJECTED,
                candidateSteamAppId = 199,
            ).copy(decisionSource = MatchDecisionSource.USER),
        )

        val reusedTarget = repository.confirmSteamMatch(secondSelectedKey, 199, 500)

        assertEquals(existingTarget.canonicalId, reusedTarget)
        assertNotNull(db.canonicalGameDao().get(secondStandalone.canonicalId))
        assertNull(requireNotNull(db.canonicalGameDao().get(secondStandalone.canonicalId)).steamAppId)
        assertEquals(existingTarget.canonicalId, requireNotNull(db.storeMatchDao().get(secondSelectedKey)).canonicalId)
        assertEquals(
            secondStandalone.canonicalId,
            requireNotNull(db.storeMatchDao().get(secondRejectedKey)).canonicalId,
        )
    }

    @Test
    fun `reject and reset decisions remain sticky until explicit reevaluation`() = runBlocking {
        val canonical = canonical(index = 1, steamAppId = null, createdAt = 100)
        val key = key(GameSource.GOG, "gog-1")
        val current = ownedCopy(key, "Standalone Game")
        db.canonicalGameDao().insert(canonical)
        db.storeMatchDao().upsert(match(key, canonical.canonicalId))

        repository.rejectSteamCandidate(key, steamAppId = 99, nowEpochMs = 200)

        val resolver = CanonicalGameResolver(
            canonicalGameDao = db.canonicalGameDao(),
            storeMatchDao = db.storeMatchDao(),
            trustedSteamMappingProviders = emptySet(),
            idGenerator = idGenerator,
        )
        val rejected = resolver.resolve(current, nowEpochMs = 250)
        assertEquals(canonical.canonicalId, rejected.canonical.canonicalId)
        assertEquals(MatchConfidence.REJECTED, rejected.match.confidence)
        assertEquals(MatchDecisionSource.USER, rejected.match.decisionSource)
        assertEquals(99, rejected.match.candidateSteamAppId)
        assertFalse(rejected.createdCanonical)

        repository.resetDecision(key, nowEpochMs = 300)
        val reset = requireNotNull(db.storeMatchDao().get(key))
        assertEquals(canonical.canonicalId, reset.canonicalId)
        assertEquals(MatchDecisionSource.AUTOMATIC, reset.decisionSource)
        assertEquals(MatchConfidence.UNMATCHED, reset.confidence)
        assertEquals(MatchMethod.UNMATCHED, reset.matchMethod)
        assertNull(reset.candidateSteamAppId)
        assertEquals(0, reset.resolverVersion)
        assertEquals(300, reset.matchedAt)
        assertNotNull(db.canonicalGameDao().get(canonical.canonicalId))

        val reevaluated = resolver.resolve(current, nowEpochMs = 400)
        assertEquals(canonical.canonicalId, reevaluated.canonical.canonicalId)
        assertEquals(MatchMethod.UNMATCHED, reevaluated.match.matchMethod)
        assertEquals(MatchConfidence.UNMATCHED, reevaluated.match.confidence)
        assertEquals(MatchDecisionSource.AUTOMATIC, reevaluated.match.decisionSource)
        assertEquals(CURRENT_RESOLVER_VERSION, reevaluated.match.resolverVersion)
        assertFalse(reevaluated.createdCanonical)
        assertNotNull(db.canonicalGameDao().get(canonical.canonicalId))
    }

    @Test
    fun `rejecting current Steam identity detaches only selected grouped copy`() = runBlocking {
        val original = canonical(
            index = 1,
            steamAppId = 99,
            createdAt = 100,
            displayName = "Steam Group",
            primarySource = GameSource.STEAM,
        )
        val selectedKey = key(GameSource.GOG, "selected")
        val directKey = key(GameSource.STEAM, "99")
        db.canonicalGameDao().insert(original)
        db.storeMatchDao().upsert(match(selectedKey, original.canonicalId))
        db.storeMatchDao().upsert(directMatch(directKey, original.canonicalId, 99))
        seedFacets(
            original.canonicalId,
            genres = setOf("steam:action"),
            tags = setOf(10),
            features = setOf("steam:controller"),
        )
        db.canonicalPreferenceDao().upsert(
            preference(
                canonicalId = original.canonicalId,
                preferredKey = selectedKey,
                titleOverride = "Pinned title",
                artworkOverride = "pinned-art",
                updatedAt = 120,
            ),
        )
        db.gameDetailSnapshotDao().upsert(snapshot(original.canonicalId, "en", "US", "original"))

        repository.rejectSteamCandidate(selectedKey, steamAppId = 99, nowEpochMs = 200)

        val selected = requireNotNull(db.storeMatchDao().get(selectedKey))
        assertNotEquals(original.canonicalId, selected.canonicalId)
        assertManualDecision(
            match = selected,
            canonicalId = selected.canonicalId,
            steamAppId = 99,
            confidence = MatchConfidence.REJECTED,
            matchedAt = 200,
        )
        val detached = requireNotNull(db.canonicalGameDao().get(selected.canonicalId))
        assertNull(detached.steamAppId)
        assertEquals("Standalone Game", detached.displayName)
        assertEquals(GameSource.GOG, detached.primaryMetadataSource)
        assertEquals(ClassificationState.UNCLASSIFIED, detached.classificationState)
        assertEquals(original.canonicalId, requireNotNull(db.storeMatchDao().get(directKey)).canonicalId)
        assertEquals(99, requireNotNull(db.canonicalGameDao().get(original.canonicalId)).steamAppId)
        assertTrue(db.canonicalFacetDao().getGenres(detached.canonicalId).isEmpty())
        assertTrue(db.canonicalFacetDao().getTags(detached.canonicalId).isEmpty())
        assertTrue(db.canonicalFacetDao().getFeatures(detached.canonicalId).isEmpty())
        assertNull(db.canonicalPreferenceDao().get(detached.canonicalId))
        assertTrue(db.gameDetailSnapshotDao().getByCanonicalId(detached.canonicalId).isEmpty())
        val originalPreference = requireNotNull(db.canonicalPreferenceDao().get(original.canonicalId))
        assertNull(originalPreference.preferredCopyKeyOrNull())
        assertEquals("Pinned title", originalPreference.titleOverride)
        assertEquals("pinned-art", originalPreference.artworkOverrideJson)
        assertEquals(
            "original",
            db.gameDetailSnapshotDao().getByCanonicalId(original.canonicalId).single().payloadJson,
        )
    }

    @Test
    fun `rejecting sole current Steam identity clears association in place`() = runBlocking {
        val canonical = canonical(
            index = 1,
            steamAppId = 99,
            createdAt = 100,
            displayName = "Steam Game",
            primarySource = GameSource.STEAM,
            reviewCount = 500,
        )
        val key = key(GameSource.GOG, "sole")
        db.canonicalGameDao().insert(canonical)
        db.storeMatchDao().upsert(match(key, canonical.canonicalId))
        seedFacets(
            canonical.canonicalId,
            genres = setOf("steam:action"),
            tags = setOf(10),
            features = setOf("steam:controller"),
        )
        db.gameDetailSnapshotDao().upsert(snapshot(canonical.canonicalId, "en", "US", "steam"))
        val preference = preference(canonical.canonicalId, key, "Pinned", "pinned-art", 120)
        db.canonicalPreferenceDao().upsert(preference)

        repository.rejectSteamCandidate(key, steamAppId = 99, nowEpochMs = 200)

        val storedCanonical = requireNotNull(db.canonicalGameDao().get(canonical.canonicalId))
        assertNull(storedCanonical.steamAppId)
        assertEquals("Standalone Game", storedCanonical.displayName)
        assertEquals(GameSource.GOG, storedCanonical.primaryMetadataSource)
        assertEquals(ClassificationState.UNCLASSIFIED, storedCanonical.classificationState)
        assertNull(storedCanonical.steamReviewCount)
        assertEquals(100, storedCanonical.createdAt)
        assertEquals(200, storedCanonical.updatedAt)
        assertManualDecision(
            match = requireNotNull(db.storeMatchDao().get(key)),
            canonicalId = canonical.canonicalId,
            steamAppId = 99,
            confidence = MatchConfidence.REJECTED,
            matchedAt = 200,
        )
        assertTrue(db.canonicalFacetDao().getGenres(canonical.canonicalId).isEmpty())
        assertTrue(db.canonicalFacetDao().getTags(canonical.canonicalId).isEmpty())
        assertTrue(db.canonicalFacetDao().getFeatures(canonical.canonicalId).isEmpty())
        assertTrue(db.gameDetailSnapshotDao().getByCanonicalId(canonical.canonicalId).isEmpty())
        assertEquals(preference, db.canonicalPreferenceDao().get(canonical.canonicalId))
        assertEquals(1, db.canonicalGameDao().getAll().size)
    }

    @Test
    fun `direct Steam identity cannot be corrected rejected reset or unmerged`() = runBlocking {
        val canonical = canonical(index = 1, steamAppId = 10, createdAt = 100)
        val key = key(GameSource.STEAM, "10")
        val direct = directMatch(key, canonical.canonicalId, 10)
        db.canonicalGameDao().insert(canonical)
        db.storeMatchDao().upsert(direct)
        val beforeCanonicals = db.canonicalGameDao().getAll()
        val beforeMatches = db.storeMatchDao().getAll()
        val current = ownedCopy(key, "Steam Game", directSteamAppId = 10)

        val failures = listOf(
            runCatching { repository.confirmSteamMatch(key, 20, 200) }.exceptionOrNull(),
            runCatching { repository.rejectSteamCandidate(key, 20, 200) }.exceptionOrNull(),
            runCatching { repository.resetDecision(key, 200) }.exceptionOrNull(),
            runCatching { repository.unmergeCopy(key, current, 200) }.exceptionOrNull(),
        )

        assertTrue(failures.all { it is IllegalArgumentException })
        assertEquals(beforeCanonicals, db.canonicalGameDao().getAll())
        assertEquals(beforeMatches, db.storeMatchDao().getAll())
    }

    @Test
    fun `survivor selection prefers Steam then oldest creation and lexical id`() {
        val newerSteam = canonical(index = 3, steamAppId = 10, createdAt = 300)
        val olderStandalone = canonical(index = 2, steamAppId = null, createdAt = 100)
        assertEquals(
            newerSteam,
            selectCanonicalSurvivor(newerSteam, olderStandalone, confirmedSteamAppId = null),
        )

        val older = canonical(index = 2, steamAppId = null, createdAt = 100)
        val newer = canonical(index = 1, steamAppId = null, createdAt = 200)
        assertEquals(older, selectCanonicalSurvivor(newer, older, confirmedSteamAppId = null))

        val lexicalFirst = canonical(index = 1, steamAppId = null, createdAt = 100)
        val lexicalSecond = canonical(index = 2, steamAppId = null, createdAt = 100)
        assertEquals(
            lexicalFirst,
            selectCanonicalSurvivor(lexicalSecond, lexicalFirst, confirmedSteamAppId = null),
        )

        val conflict = runCatching {
            selectCanonicalSurvivor(
                canonical(index = 4, steamAppId = 40, createdAt = 100),
                canonical(index = 5, steamAppId = 50, createdAt = 200),
                confirmedSteamAppId = null,
            )
        }.exceptionOrNull()
        assertTrue(conflict is IllegalStateException)
    }

    @Test
    fun `unmerge detaches one copy without copying canonical wide overrides`() = runBlocking {
        val original = canonical(index = 1, steamAppId = 99, createdAt = 100)
        val selectedKey = key(GameSource.GOG, "selected")
        val directKey = key(GameSource.STEAM, "99")
        db.canonicalGameDao().insert(original)
        db.storeMatchDao().upsert(match(selectedKey, original.canonicalId))
        db.storeMatchDao().upsert(directMatch(directKey, original.canonicalId, 99))
        seedFacets(
            original.canonicalId,
            genres = setOf("steam:action"),
            tags = setOf(10),
            features = setOf("steam:controller"),
        )
        db.canonicalPreferenceDao().upsert(
            preference(
                canonicalId = original.canonicalId,
                preferredKey = selectedKey,
                titleOverride = "Pinned title",
                artworkOverride = "pinned-art",
                updatedAt = 120,
            ),
        )
        db.gameDetailSnapshotDao().upsert(snapshot(original.canonicalId, "en", "US", "original"))
        val current = ownedCopy(
            key = selectedKey,
            displayName = "Detached Game",
            genres = setOf("gog:rpg"),
            tags = setOf(5),
            features = setOf("gog:offline"),
        )

        val detachedId = repository.unmergeCopy(selectedKey, current, nowEpochMs = 200)

        assertNotEquals(original.canonicalId, detachedId)
        assertNotNull(db.canonicalGameDao().get(original.canonicalId))
        val detached = requireNotNull(db.canonicalGameDao().get(detachedId))
        assertNull(detached.steamAppId)
        assertEquals("Detached Game", detached.displayName)
        assertEquals(ClassificationState.CLASSIFIED, detached.classificationState)
        val selected = requireNotNull(db.storeMatchDao().get(selectedKey))
        assertManualDecision(
            selected,
            canonicalId = detachedId,
            steamAppId = 99,
            confidence = MatchConfidence.REJECTED,
            matchedAt = 200,
        )
        assertEquals(original.canonicalId, requireNotNull(db.storeMatchDao().get(directKey)).canonicalId)
        assertEquals(
            listOf("gog:rpg"),
            db.canonicalFacetDao().getGenres(detachedId).map { it.genreKey },
        )
        assertEquals(listOf(5), db.canonicalFacetDao().getTags(detachedId).map { it.tagId })
        assertEquals(
            listOf("gog:offline"),
            db.canonicalFacetDao().getFeatures(detachedId).map { it.featureKey },
        )
        assertNull(db.canonicalPreferenceDao().get(detachedId))
        assertTrue(db.gameDetailSnapshotDao().getByCanonicalId(detachedId).isEmpty())
        val originalPreference = requireNotNull(db.canonicalPreferenceDao().get(original.canonicalId))
        assertNull(originalPreference.preferredCopyKeyOrNull())
        assertEquals("Pinned title", originalPreference.titleOverride)
        assertEquals("pinned-art", originalPreference.artworkOverrideJson)
        assertEquals("original", db.gameDetailSnapshotDao().getByCanonicalId(original.canonicalId).single().payloadJson)
    }

    @Test
    fun `guarded automatic acceptance assigns catalog identity as automatic high`() = runBlocking {
        val canonical = canonical(index = 1, steamAppId = null, createdAt = 100)
        val selectedKey = key(GameSource.GOG, "catalog-auto")
        val selected = match(selectedKey, canonical.canonicalId)
        db.canonicalGameDao().insert(canonical)
        db.storeMatchDao().upsert(selected)

        val result = repository.guardedAcceptAutomaticSteamMatch(
            expected = expectedState(selectedKey, selected),
            steamAppId = 42,
            candidateAppType = CanonicalAppType.GAME,
            resolverVersion = CURRENT_RESOLVER_VERSION,
            nowEpochMs = 200,
        )

        assertEquals(CanonicalGuardedMutationResult.APPLIED, result)
        assertEquals(42, db.canonicalGameDao().get(canonical.canonicalId)?.steamAppId)
        val accepted = requireNotNull(db.storeMatchDao().get(selectedKey))
        assertEquals(canonical.canonicalId, accepted.canonicalId)
        assertEquals(42, accepted.candidateSteamAppId)
        assertEquals(MatchMethod.STEAM_CATALOG, accepted.matchMethod)
        assertEquals(MatchConfidence.HIGH, accepted.confidence)
        assertEquals(MatchDecisionSource.AUTOMATIC, accepted.decisionSource)
        assertEquals(CURRENT_RESOLVER_VERSION, accepted.resolverVersion)
        assertEquals(200, accepted.matchedAt)
    }

    @Test
    fun `guarded candidate recording requires review without assigning Steam identity`() = runBlocking {
        val canonical = canonical(index = 1, steamAppId = null, createdAt = 100)
        val selectedKey = key(GameSource.EPIC, "catalog-review")
        val selected = match(selectedKey, canonical.canonicalId)
        db.canonicalGameDao().insert(canonical)
        db.storeMatchDao().upsert(selected)

        val result = repository.guardedRecordCandidate(
            expected = expectedState(selectedKey, selected),
            steamAppId = 42,
            resolverVersion = CURRENT_RESOLVER_VERSION,
            nowEpochMs = 200,
        )

        assertEquals(CanonicalGuardedMutationResult.APPLIED, result)
        assertNull(db.canonicalGameDao().get(canonical.canonicalId)?.steamAppId)
        val review = requireNotNull(db.storeMatchDao().get(selectedKey))
        assertEquals(42, review.candidateSteamAppId)
        assertEquals(MatchMethod.STEAM_CATALOG, review.matchMethod)
        assertEquals(MatchConfidence.REVIEW_REQUIRED, review.confidence)
        assertEquals(MatchDecisionSource.AUTOMATIC, review.decisionSource)
        assertEquals(200, review.matchedAt)
    }

    @Test
    fun `guarded manual confirmation records verified user decision`() = runBlocking {
        val canonical = canonical(index = 1, steamAppId = null, createdAt = 100)
        val selectedKey = key(GameSource.AMAZON, "catalog-manual")
        val selected = match(selectedKey, canonical.canonicalId)
        db.canonicalGameDao().insert(canonical)
        db.storeMatchDao().upsert(selected)

        val result = repository.guardedConfirmSteamMatch(
            expected = expectedState(selectedKey, selected),
            steamAppId = 42,
            candidateAppType = CanonicalAppType.GAME,
            nowEpochMs = 200,
        )

        assertEquals(CanonicalGuardedMutationResult.APPLIED, result)
        assertEquals(42, db.canonicalGameDao().get(canonical.canonicalId)?.steamAppId)
        assertManualDecision(
            requireNotNull(db.storeMatchDao().get(selectedKey)),
            canonicalId = canonical.canonicalId,
            steamAppId = 42,
            confidence = MatchConfidence.VERIFIED,
            matchedAt = 200,
        )
    }

    @Test
    fun `guarded rejection is sticky and guarded reset makes copy eligible again`() = runBlocking {
        val canonical = canonical(index = 1, steamAppId = 42, createdAt = 100)
        val selectedKey = key(GameSource.GOG, "catalog-reject")
        val accepted = match(
            selectedKey,
            canonical.canonicalId,
            method = MatchMethod.STEAM_CATALOG,
            confidence = MatchConfidence.HIGH,
            candidateSteamAppId = 42,
        )
        db.canonicalGameDao().insert(canonical)
        db.storeMatchDao().upsert(accepted)

        val rejectedResult = repository.guardedRejectSteamMatch(
            expected = expectedState(selectedKey, accepted),
            steamAppId = 42,
            nowEpochMs = 200,
        )

        assertEquals(CanonicalGuardedMutationResult.APPLIED, rejectedResult)
        assertNull(db.canonicalGameDao().get(canonical.canonicalId)?.steamAppId)
        val rejected = requireNotNull(db.storeMatchDao().get(selectedKey))
        assertManualDecision(
            rejected,
            canonicalId = canonical.canonicalId,
            steamAppId = 42,
            confidence = MatchConfidence.REJECTED,
            matchedAt = 200,
        )

        val resetResult = repository.guardedResetDecision(
            expected = expectedState(selectedKey, rejected),
            nowEpochMs = 300,
        )

        assertEquals(CanonicalGuardedMutationResult.APPLIED, resetResult)
        val reset = requireNotNull(db.storeMatchDao().get(selectedKey))
        assertEquals(MatchMethod.UNMATCHED, reset.matchMethod)
        assertEquals(MatchConfidence.UNMATCHED, reset.confidence)
        assertEquals(MatchDecisionSource.AUTOMATIC, reset.decisionSource)
        assertNull(reset.candidateSteamAppId)
        assertEquals(0, reset.resolverVersion)
    }

    @Test
    fun `guarded catalog mutations reject stale absent direct and type-conflicting state`() = runBlocking {
        val canonical = canonical(index = 1, steamAppId = null, createdAt = 100)
        val selectedKey = key(GameSource.GOG, "catalog-stale")
        val selected = match(selectedKey, canonical.canonicalId)
        db.canonicalGameDao().insert(canonical)
        db.storeMatchDao().upsert(selected)
        val expected = expectedState(selectedKey, selected)

        db.storeMatchDao().upsert(selected.copy(matchedAt = selected.matchedAt + 1))
        val staleBefore = databaseState()
        assertEquals(
            CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED,
            repository.guardedRecordCandidate(expected, 42, CURRENT_RESOLVER_VERSION, 200),
        )
        assertEquals(staleBefore, databaseState())

        val absent = selected.copy(isPresent = false)
        db.storeMatchDao().upsert(absent)
        val absentBefore = databaseState()
        assertEquals(
            CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED,
            repository.guardedRecordCandidate(expectedState(selectedKey, absent), 42, CURRENT_RESOLVER_VERSION, 200),
        )
        assertEquals(absentBefore, databaseState())

        db.storeMatchDao().upsert(selected)
        val conflictBefore = databaseState()
        assertEquals(
            CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED,
            repository.guardedAcceptAutomaticSteamMatch(
                expectedState(selectedKey, selected),
                steamAppId = 42,
                candidateAppType = CanonicalAppType.DLC,
                resolverVersion = CURRENT_RESOLVER_VERSION,
                nowEpochMs = 200,
            ),
        )
        assertEquals(conflictBefore, databaseState())

        val steamCanonical = canonical(index = 2, steamAppId = 84, createdAt = 100)
        val directKey = key(GameSource.STEAM, "84")
        val direct = directMatch(directKey, steamCanonical.canonicalId, 84)
        db.canonicalGameDao().insert(steamCanonical)
        db.storeMatchDao().upsert(direct)
        val directBefore = databaseState()
        assertEquals(
            CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED,
            repository.guardedRecordCandidate(
                expectedState(directKey, direct),
                42,
                CURRENT_RESOLVER_VERSION,
                200,
            ),
        )
        assertEquals(directBefore, databaseState())
    }

    @Test
    fun `guarded automatic merge moves dependencies and copy keys exactly once`() = runBlocking {
        val source = canonical(index = 1, steamAppId = null, createdAt = 100)
        val target = canonical(index = 2, steamAppId = 42, createdAt = 200)
        val selectedKey = key(GameSource.EPIC, "catalog-merge")
        val directKey = key(GameSource.STEAM, "42")
        val selected = match(selectedKey, source.canonicalId)
        db.canonicalGameDao().insert(source)
        db.canonicalGameDao().insert(target)
        db.storeMatchDao().upsert(selected)
        db.storeMatchDao().upsert(directMatch(directKey, target.canonicalId, 42))
        seedFacets(source.canonicalId, genres = setOf("source:rpg"), tags = setOf(7))
        seedFacets(target.canonicalId, genres = setOf("steam:action"), tags = setOf(8))
        db.canonicalPreferenceDao().upsert(
            preference(source.canonicalId, selectedKey, "Pinned", null, 100),
        )
        db.gameDetailSnapshotDao().upsert(snapshot(source.canonicalId, "en", "US", "source"))

        val result = repository.guardedAcceptAutomaticSteamMatch(
            expected = expectedState(selectedKey, selected),
            steamAppId = 42,
            candidateAppType = CanonicalAppType.GAME,
            resolverVersion = CURRENT_RESOLVER_VERSION,
            nowEpochMs = 300,
        )

        assertEquals(CanonicalGuardedMutationResult.APPLIED, result)
        assertEquals(
            listOf(target.copy(classificationState = ClassificationState.CLASSIFIED, updatedAt = 300)),
            db.canonicalGameDao().getAll(),
        )
        assertEquals(target.canonicalId, db.storeMatchDao().get(selectedKey)?.canonicalId)
        assertEquals(target.canonicalId, db.storeMatchDao().get(directKey)?.canonicalId)
        assertEquals(
            setOf("source:rpg", "steam:action"),
            db.canonicalFacetDao().getGenres(target.canonicalId).map { it.genreKey }.toSet(),
        )
        assertEquals(setOf(7, 8), db.canonicalFacetDao().getTags(target.canonicalId).map { it.tagId }.toSet())
        assertEquals("Pinned", db.canonicalPreferenceDao().get(target.canonicalId)?.titleOverride)
        assertEquals(1, db.gameDetailSnapshotDao().getByCanonicalId(target.canonicalId).size)
    }

    @Test
    fun `guarded unmerge applies valid detach and preserves established preference behavior`() = runBlocking {
        val original = canonical(index = 1, steamAppId = 99, createdAt = 100)
        val selectedKey = key(GameSource.EPIC, "guarded-selected")
        val directKey = key(GameSource.STEAM, "99")
        db.canonicalGameDao().insert(original)
        db.storeMatchDao().upsert(
            match(
                key = selectedKey,
                canonicalId = original.canonicalId,
                method = MatchMethod.EXACT_METADATA,
                confidence = MatchConfidence.HIGH,
            ),
        )
        db.storeMatchDao().upsert(directMatch(directKey, original.canonicalId, 99))
        seedFacets(
            original.canonicalId,
            genres = setOf("steam:action"),
            tags = setOf(10),
            features = setOf("steam:controller"),
        )
        db.canonicalPreferenceDao().upsert(
            preference(
                canonicalId = original.canonicalId,
                preferredKey = selectedKey,
                titleOverride = "Pinned title",
                artworkOverride = "pinned-art",
                updatedAt = 120,
            ),
        )
        db.gameDetailSnapshotDao().upsert(snapshot(original.canonicalId, "en", "US", "original"))
        val current = ownedCopy(
            key = selectedKey,
            displayName = "Guarded Detached Game",
            genres = setOf("epic:rpg"),
            tags = setOf(5),
            features = setOf("epic:offline"),
        )

        val result = repository.guardedUnmergeCopy(
            key = selectedKey,
            current = current,
            expectedCanonicalId = original.canonicalId,
            nowEpochMs = 200,
        )

        assertEquals(CanonicalGuardedMutationResult.APPLIED, result)
        val selected = requireNotNull(db.storeMatchDao().get(selectedKey))
        val detachedId = selected.canonicalId
        assertNotEquals(original.canonicalId, detachedId)
        assertManualDecision(
            selected,
            canonicalId = detachedId,
            steamAppId = 99,
            confidence = MatchConfidence.REJECTED,
            matchedAt = 200,
        )
        val detached = requireNotNull(db.canonicalGameDao().get(detachedId))
        assertNull(detached.steamAppId)
        assertEquals("Guarded Detached Game", detached.displayName)
        assertEquals(ClassificationState.CLASSIFIED, detached.classificationState)
        assertEquals(original.canonicalId, requireNotNull(db.storeMatchDao().get(directKey)).canonicalId)
        assertEquals(
            listOf("epic:rpg"),
            db.canonicalFacetDao().getGenres(detachedId).map { it.genreKey },
        )
        assertEquals(listOf(5), db.canonicalFacetDao().getTags(detachedId).map { it.tagId })
        assertEquals(
            listOf("epic:offline"),
            db.canonicalFacetDao().getFeatures(detachedId).map { it.featureKey },
        )
        assertNull(db.canonicalPreferenceDao().get(detachedId))
        assertTrue(db.gameDetailSnapshotDao().getByCanonicalId(detachedId).isEmpty())
        val originalPreference = requireNotNull(db.canonicalPreferenceDao().get(original.canonicalId))
        assertNull(originalPreference.preferredCopyKeyOrNull())
        assertEquals("Pinned title", originalPreference.titleOverride)
        assertEquals("pinned-art", originalPreference.artworkOverrideJson)
        assertEquals(
            "original",
            db.gameDetailSnapshotDao().getByCanonicalId(original.canonicalId).single().payloadJson,
        )
    }

    @Test
    fun `guarded reset applies valid revision and preserves canonical preference`() = runBlocking {
        val canonical = canonical(index = 1, steamAppId = null, createdAt = 100)
        val selectedKey = key(GameSource.GOG, "guarded-reset")
        db.canonicalGameDao().insert(canonical)
        val rejected = match(
            key = selectedKey,
            canonicalId = canonical.canonicalId,
            method = MatchMethod.MANUAL,
            confidence = MatchConfidence.REJECTED,
            candidateSteamAppId = 99,
        ).copy(
            decisionSource = MatchDecisionSource.USER,
            matchedAt = 200,
        )
        db.storeMatchDao().upsert(rejected)
        val preference = preference(
            canonicalId = canonical.canonicalId,
            preferredKey = selectedKey,
            titleOverride = "Pinned standalone",
            artworkOverride = "standalone-art",
            updatedAt = 220,
        )
        db.canonicalPreferenceDao().upsert(preference)

        val result = repository.guardedResetDecision(
            key = selectedKey,
            expectedCanonicalId = canonical.canonicalId,
            expectedMatchMethod = rejected.matchMethod,
            expectedConfidence = rejected.confidence,
            expectedDecisionSource = rejected.decisionSource,
            expectedCandidateSteamAppId = rejected.candidateSteamAppId,
            expectedResolverVersion = rejected.resolverVersion,
            expectedDecisionRevision = rejected.matchedAt,
            nowEpochMs = 300,
        )

        assertEquals(CanonicalGuardedMutationResult.APPLIED, result)
        val reset = requireNotNull(db.storeMatchDao().get(selectedKey))
        assertEquals(canonical.canonicalId, reset.canonicalId)
        assertEquals(MatchDecisionSource.AUTOMATIC, reset.decisionSource)
        assertEquals(MatchConfidence.UNMATCHED, reset.confidence)
        assertEquals(MatchMethod.UNMATCHED, reset.matchMethod)
        assertNull(reset.candidateSteamAppId)
        assertEquals(0, reset.resolverVersion)
        assertEquals(300, reset.matchedAt)
        assertEquals(preference, db.canonicalPreferenceDao().get(canonical.canonicalId))
        assertEquals(listOf(canonical), db.canonicalGameDao().getAll())
    }

    @Test
    fun `guarded unmerge rejects copy moved to another canonical without clearing newer preference`() = runBlocking {
        val expected = canonical(index = 1, steamAppId = 99, createdAt = 100)
        val newer = canonical(index = 2, steamAppId = null, createdAt = 200)
        val selectedKey = key(GameSource.EPIC, "selected")
        val expectedPeer = key(GameSource.STEAM, "99")
        db.canonicalGameDao().insert(expected)
        db.canonicalGameDao().insert(newer)
        val selected = match(
            selectedKey,
            expected.canonicalId,
            method = MatchMethod.EXACT_METADATA,
            confidence = MatchConfidence.HIGH,
        )
        db.storeMatchDao().upsert(selected)
        db.storeMatchDao().upsert(directMatch(expectedPeer, expected.canonicalId, 99))

        db.storeMatchDao().upsert(selected.copy(canonicalId = newer.canonicalId))
        val newerPreference = preference(
            newer.canonicalId,
            selectedKey,
            "Newer title",
            "newer-art",
            250,
        )
        db.canonicalPreferenceDao().upsert(newerPreference)
        val before = databaseState()

        val result = repository.guardedUnmergeCopy(
            key = selectedKey,
            current = ownedCopy(selectedKey, "Stale detached title"),
            expectedCanonicalId = expected.canonicalId,
            nowEpochMs = 300,
        )

        assertEquals(CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED, result)
        assertEquals(before, databaseState())
        assertEquals(
            selectedKey,
            requireNotNull(db.canonicalPreferenceDao().get(newer.canonicalId)).preferredCopyKeyOrNull(),
        )
    }

    @Test
    fun `guarded reset rejects newer manual decision and leaves relationship unchanged`() = runBlocking {
        val canonical = canonical(index = 1, steamAppId = null, createdAt = 100)
        val selectedKey = key(GameSource.GOG, "selected")
        db.canonicalGameDao().insert(canonical)
        val rejected = match(
            selectedKey,
            canonical.canonicalId,
            method = MatchMethod.MANUAL,
            confidence = MatchConfidence.REJECTED,
        ).copy(decisionSource = MatchDecisionSource.USER)
        db.storeMatchDao().upsert(rejected)

        val newerDecision = rejected.copy(
            candidateSteamAppId = 77,
            confidence = MatchConfidence.VERIFIED,
            matchedAt = 250,
        )
        db.storeMatchDao().upsert(newerDecision)

        val result = repository.guardedResetDecision(
            key = selectedKey,
            expectedCanonicalId = canonical.canonicalId,
            expectedMatchMethod = rejected.matchMethod,
            expectedConfidence = rejected.confidence,
            expectedDecisionSource = rejected.decisionSource,
            expectedCandidateSteamAppId = rejected.candidateSteamAppId,
            expectedResolverVersion = rejected.resolverVersion,
            expectedDecisionRevision = rejected.matchedAt,
            nowEpochMs = 300,
        )

        assertEquals(CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED, result)
        assertEquals(newerDecision, db.storeMatchDao().get(selectedKey))
    }

    @Test
    fun `guarded reset rejects newer same-shape decision revision and preserves row`() = runBlocking {
        val canonical = canonical(index = 1, steamAppId = null, createdAt = 100)
        val selectedKey = key(GameSource.GOG, "same-shape")
        db.canonicalGameDao().insert(canonical)
        val displayedDecision = match(
            selectedKey,
            canonical.canonicalId,
            method = MatchMethod.MANUAL,
            confidence = MatchConfidence.REJECTED,
            candidateSteamAppId = 77,
        ).copy(decisionSource = MatchDecisionSource.USER, matchedAt = 100)
        db.storeMatchDao().upsert(displayedDecision)

        val newerSameShapeDecision = displayedDecision.copy(matchedAt = 250)
        db.storeMatchDao().upsert(newerSameShapeDecision)

        val result = repository.guardedResetDecision(
            key = selectedKey,
            expectedCanonicalId = canonical.canonicalId,
            expectedMatchMethod = displayedDecision.matchMethod,
            expectedConfidence = displayedDecision.confidence,
            expectedDecisionSource = displayedDecision.decisionSource,
            expectedCandidateSteamAppId = displayedDecision.candidateSteamAppId,
            expectedResolverVersion = displayedDecision.resolverVersion,
            expectedDecisionRevision = displayedDecision.matchedAt,
            nowEpochMs = 300,
        )

        assertEquals(CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED, result)
        assertEquals(newerSameShapeDecision, db.storeMatchDao().get(selectedKey))
    }

    @Test
    fun `guarded reset rejects different candidate at the same decision timestamp`() = runBlocking {
        val canonical = canonical(index = 1, steamAppId = null, createdAt = 100)
        val selectedKey = key(GameSource.GOG, "same-timestamp-candidate")
        db.canonicalGameDao().insert(canonical)
        val displayedDecision = match(
            selectedKey,
            canonical.canonicalId,
            method = MatchMethod.MANUAL,
            confidence = MatchConfidence.REJECTED,
            candidateSteamAppId = 77,
        ).copy(
            decisionSource = MatchDecisionSource.USER,
            resolverVersion = 4,
            matchedAt = 250,
        )
        db.storeMatchDao().upsert(displayedDecision)

        val newerDecision = displayedDecision.copy(candidateSteamAppId = 88)
        db.storeMatchDao().upsert(newerDecision)

        val result = repository.guardedResetDecision(
            key = selectedKey,
            expectedCanonicalId = canonical.canonicalId,
            expectedMatchMethod = displayedDecision.matchMethod,
            expectedConfidence = displayedDecision.confidence,
            expectedDecisionSource = displayedDecision.decisionSource,
            expectedCandidateSteamAppId = displayedDecision.candidateSteamAppId,
            expectedResolverVersion = displayedDecision.resolverVersion,
            expectedDecisionRevision = displayedDecision.matchedAt,
            nowEpochMs = 300,
        )

        assertEquals(CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED, result)
        assertEquals(newerDecision, db.storeMatchDao().get(selectedKey))
    }

    @Test
    fun `marking last copy absent retains canonical and user decision`() = runBlocking {
        val canonical = canonical(index = 1, steamAppId = null, createdAt = 100)
        val key = key(GameSource.GOG, "last")
        db.canonicalGameDao().insert(canonical)
        db.storeMatchDao().upsert(match(key, canonical.canonicalId))
        repository.rejectSteamCandidate(key, steamAppId = 99, nowEpochMs = 200)

        repository.markCopyAbsent(key)
        repository.markCopyAbsent(key)

        val stored = requireNotNull(db.storeMatchDao().get(key))
        assertFalse(stored.isPresent)
        assertEquals(MatchDecisionSource.USER, stored.decisionSource)
        assertEquals(MatchConfidence.REJECTED, stored.confidence)
        assertNotNull(db.canonicalGameDao().get(canonical.canonicalId))
    }

    @Test
    fun `merge failure rolls back dependencies and relationships`() = runBlocking {
        val loser = canonical(index = 1, steamAppId = null, createdAt = 100)
        val survivor = canonical(index = 2, steamAppId = 99, createdAt = 200)
        val selectedKey = key(GameSource.GOG, "selected")
        val directKey = key(GameSource.STEAM, "99")
        db.canonicalGameDao().insert(loser)
        db.canonicalGameDao().insert(survivor)
        db.storeMatchDao().upsert(match(selectedKey, loser.canonicalId))
        db.storeMatchDao().upsert(directMatch(directKey, survivor.canonicalId, 99))
        seedFacets(loser.canonicalId, genres = setOf("gog:rpg"))
        db.canonicalPreferenceDao().upsert(
            preference(loser.canonicalId, selectedKey, "Loser", "art", 100),
        )
        db.gameDetailSnapshotDao().upsert(snapshot(loser.canonicalId, "de", "DE", "loser"))
        val before = databaseState()
        installRepointFailureTrigger(loser.canonicalId)

        val failure = runCatching {
            repository.confirmSteamMatch(selectedKey, steamAppId = 99, nowEpochMs = 300)
        }.exceptionOrNull()

        assertInjectedFailure(failure)
        assertEquals(before, databaseState())
    }

    @Test
    fun `rejection detach failure rolls back canonical and preference changes`() = runBlocking {
        val original = canonical(index = 1, steamAppId = 99, createdAt = 100)
        val selectedKey = key(GameSource.GOG, "selected")
        val directKey = key(GameSource.STEAM, "99")
        db.canonicalGameDao().insert(original)
        db.storeMatchDao().upsert(match(selectedKey, original.canonicalId))
        db.storeMatchDao().upsert(directMatch(directKey, original.canonicalId, 99))
        db.canonicalPreferenceDao().upsert(
            preference(original.canonicalId, selectedKey, "Title", "art", 100),
        )
        val before = databaseState()
        installPreferenceFailureTrigger(original.canonicalId)

        val failure = runCatching {
            repository.rejectSteamCandidate(selectedKey, steamAppId = 99, nowEpochMs = 200)
        }.exceptionOrNull()

        assertInjectedFailure(failure)
        assertEquals(before, databaseState())
    }

    @Test
    fun `unmerge failure rolls back newly inserted canonical and preference cleanup`() = runBlocking {
        val original = canonical(index = 1, steamAppId = 99, createdAt = 100)
        val selectedKey = key(GameSource.GOG, "selected")
        db.canonicalGameDao().insert(original)
        db.storeMatchDao().upsert(match(selectedKey, original.canonicalId))
        db.canonicalPreferenceDao().upsert(
            preference(original.canonicalId, selectedKey, "Title", "art", 100),
        )
        val before = databaseState()
        installPreferenceFailureTrigger(original.canonicalId)

        val failure = runCatching {
            repository.unmergeCopy(
                selectedKey,
                ownedCopy(
                    selectedKey,
                    "Detached",
                    genres = setOf("gog:rpg"),
                    tags = setOf(5),
                    features = setOf("gog:offline"),
                ),
                200,
            )
        }.exceptionOrNull()

        assertInjectedFailure(failure)
        assertEquals(before, databaseState())
    }

    private suspend fun seedFacets(
        canonicalId: String,
        genres: Set<String> = emptySet(),
        tags: Set<Int> = emptySet(),
        features: Set<String> = emptySet(),
    ) {
        db.canonicalFacetDao().upsertGenres(
            genres.map { CanonicalGameGenreCrossRef(canonicalId, it) },
        )
        db.canonicalFacetDao().upsertTags(
            tags.map { CanonicalGameTagCrossRef(canonicalId, it) },
        )
        db.canonicalFacetDao().upsertFeatures(
            features.map { CanonicalGameFeatureCrossRef(canonicalId, it) },
        )
    }

    private suspend fun databaseState(): DatabaseState {
        val canonicals = db.canonicalGameDao().getAll()
        return DatabaseState(
            canonicals = canonicals,
            matches = db.storeMatchDao().getAll(),
            preferences = canonicals.mapNotNull { db.canonicalPreferenceDao().get(it.canonicalId) },
            genres = canonicals.flatMap { db.canonicalFacetDao().getGenres(it.canonicalId) },
            tags = canonicals.flatMap { db.canonicalFacetDao().getTags(it.canonicalId) },
            features = canonicals.flatMap { db.canonicalFacetDao().getFeatures(it.canonicalId) },
            snapshots = canonicals.flatMap { db.gameDetailSnapshotDao().getByCanonicalId(it.canonicalId) },
        )
    }

    private fun installRepointFailureTrigger(fromCanonicalId: String) {
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_canonical_repoint
            BEFORE UPDATE OF canonical_id ON store_match
            WHEN OLD.canonical_id = '$fromCanonicalId'
            BEGIN
                SELECT RAISE(ABORT, 'injected failure');
            END
            """.trimIndent(),
        )
    }

    private fun installPreferenceFailureTrigger(canonicalId: String) {
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_preference_cleanup
            BEFORE UPDATE ON canonical_game_preference
            WHEN OLD.canonical_id = '$canonicalId'
            BEGIN
                SELECT RAISE(ABORT, 'injected failure');
            END
            """.trimIndent(),
        )
    }

    private fun assertInjectedFailure(failure: Throwable?) {
        assertTrue(
            generateSequence(requireNotNull(failure)) { throwable -> throwable.cause }
                .any { throwable -> throwable.message?.contains("injected failure") == true },
        )
    }

    private fun assertManualDecision(
        match: StoreMatchEntity,
        canonicalId: String,
        steamAppId: Int,
        confidence: MatchConfidence,
        matchedAt: Long,
    ) {
        assertEquals(canonicalId, match.canonicalId)
        assertEquals(steamAppId, match.candidateSteamAppId)
        assertEquals(MatchMethod.MANUAL, match.matchMethod)
        assertEquals(confidence, match.confidence)
        assertEquals(MatchDecisionSource.USER, match.decisionSource)
        assertEquals(CURRENT_RESOLVER_VERSION, match.resolverVersion)
        assertEquals(matchedAt, match.matchedAt)
    }

    private fun expectedState(
        key: OwnedCopyKey,
        match: StoreMatchEntity,
    ) = ExpectedMatchState(
        key = key,
        canonicalId = match.canonicalId,
        matchMethod = match.matchMethod,
        confidence = match.confidence,
        decisionSource = match.decisionSource,
        candidateSteamAppId = match.candidateSteamAppId,
        resolverVersion = match.resolverVersion,
        decisionRevision = match.matchedAt,
    )

    private fun canonical(
        index: Long,
        steamAppId: Int?,
        createdAt: Long,
        displayName: String = "Game $index",
        primarySource: GameSource = GameSource.GOG,
        reviewCount: Long? = null,
    ): CanonicalGameEntity = CanonicalGameEntity(
        canonicalId = uuid(index),
        steamAppId = steamAppId,
        displayName = displayName,
        matchTitleKey = displayName.lowercase(),
        primaryMetadataSource = primarySource,
        appType = CanonicalAppType.GAME,
        releaseYear = 2020,
        developerKey = "studio",
        classificationState = ClassificationState.UNCLASSIFIED,
        steamReviewCount = reviewCount,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun match(
        key: OwnedCopyKey,
        canonicalId: String,
        method: MatchMethod = MatchMethod.UNMATCHED,
        confidence: MatchConfidence = MatchConfidence.UNMATCHED,
        candidateSteamAppId: Int? = null,
    ): StoreMatchEntity = StoreMatchEntity(
        accountScope = key.accountScope.value,
        source = key.source,
        stableSourceId = key.stableSourceId,
        canonicalId = canonicalId,
        candidateSteamAppId = candidateSteamAppId,
        matchMethod = method,
        confidence = confidence,
        decisionSource = MatchDecisionSource.AUTOMATIC,
        resolverVersion = CURRENT_RESOLVER_VERSION,
        matchedAt = 100,
        isPresent = true,
        evidenceDisplayName = "Standalone Game",
        evidenceTitleKey = "standalone game",
        evidenceDeveloperKey = "studio",
        evidenceReleaseYear = 2020,
        evidenceAppType = CanonicalAppType.GAME,
    )

    private fun directMatch(
        key: OwnedCopyKey,
        canonicalId: String,
        steamAppId: Int,
    ): StoreMatchEntity = match(
        key = key,
        canonicalId = canonicalId,
        method = MatchMethod.DIRECT_STEAM,
        confidence = MatchConfidence.VERIFIED,
        candidateSteamAppId = steamAppId,
    )

    private fun preference(
        canonicalId: String,
        preferredKey: OwnedCopyKey?,
        titleOverride: String?,
        artworkOverride: String?,
        updatedAt: Long,
    ): CanonicalGamePreferenceEntity = CanonicalGamePreferenceEntity(
        canonicalId = canonicalId,
        preferredAccountScope = preferredKey?.accountScope?.value,
        preferredSource = preferredKey?.source,
        preferredStableSourceId = preferredKey?.stableSourceId,
        titleOverride = titleOverride,
        artworkOverrideJson = artworkOverride,
        updatedAt = updatedAt,
    )

    private fun snapshot(
        canonicalId: String,
        locale: String,
        country: String,
        payload: String,
    ): GameDetailSnapshotEntity = GameDetailSnapshotEntity(
        canonicalId = canonicalId,
        locale = locale,
        country = country,
        payloadJson = payload,
        provenanceJson = "{}",
        fetchedAt = 100,
        sourceRevision = "1",
    )

    private fun ownedCopy(
        key: OwnedCopyKey,
        displayName: String,
        directSteamAppId: Int? = null,
        genres: Set<String> = emptySet(),
        tags: Set<Int> = emptySet(),
        features: Set<String> = emptySet(),
    ): OwnedCopyProjection = OwnedCopyProjection(
        key = key,
        displayName = displayName,
        developer = "Studio",
        releaseYear = 2020,
        appType = CanonicalAppType.GAME,
        directSteamAppId = directSteamAppId,
        genreKeys = genres,
        tagIds = tags,
        featureKeys = features,
    )

    private fun key(source: GameSource, stableSourceId: String): OwnedCopyKey = OwnedCopyKey(
        accountScope = primaryScope,
        source = source,
        stableSourceId = stableSourceId,
    )

    private suspend fun app.gamenative.db.dao.StoreMatchDao.get(
        key: OwnedCopyKey,
    ): StoreMatchEntity? = get(key.accountScope.value, key.source, key.stableSourceId)

    private fun uuid(index: Long): String = UUID(0, index).toString()

    private data class DatabaseState(
        val canonicals: List<CanonicalGameEntity>,
        val matches: List<StoreMatchEntity>,
        val preferences: List<CanonicalGamePreferenceEntity>,
        val genres: List<CanonicalGameGenreCrossRef>,
        val tags: List<CanonicalGameTagCrossRef>,
        val features: List<CanonicalGameFeatureCrossRef>,
        val snapshots: List<GameDetailSnapshotEntity>,
    )

    private class SequentialCanonicalIdGenerator(
        start: Long = 1_000,
    ) : CanonicalIdGenerator {
        private var next = start

        override fun generate(): CanonicalGameId = CanonicalGameId(
            UUID(0, next++).toString(),
        )
    }
}
