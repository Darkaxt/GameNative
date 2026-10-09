package app.gamenative.library.canonical

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.PluviaDatabase
import app.gamenative.library.canonical.artwork.ArtworkFingerprint
import app.gamenative.library.canonical.artwork.ArtworkFingerprintSource
import app.gamenative.library.canonical.artwork.ArtworkFixtures
import app.gamenative.library.canonical.catalog.ResolutionWorkerTestApplication
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeAdapter
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.library.metadata.SystemMetadataLocaleProvider
import io.mockk.mockk
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.junit.rules.TimeoutRule

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = ResolutionWorkerTestApplication::class)
class CanonicalFamilyLibraryFlowTest {
    @get:Rule val timeout = TimeoutRule.seconds(30)
    private lateinit var db: PluviaDatabase
    private lateinit var registry: OwnedCopyRuntimeRegistry
    private val runtimes = ConcurrentHashMap<OwnedCopyKey, OwnedCopyRuntimeResult>()
    private val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val images = Images()
    private val firstKey = key(1, GameSource.GOG)
    private val secondKey = key(2, GameSource.EPIC)
    private var nativeReads = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PluviaDatabase::class.java)
            .allowMainThreadQueries().build()
        registry = OwnedCopyRuntimeRegistry(GameSource.entries.map { source -> Adapter(source) }.toSet(),
            db.libraryPlayHistoryDao(), mockk(relaxed = true))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun actualRoomLibraryFlowPublishesOneFamilyWithoutChangingMemberCatalogOrMatches() = runFlowTest {
        seed()
        val repository = repository()
        val before = db.canonicalLibraryDao().observePresentGames().first()
        val family = repository.observeCards().first { it.size == 1 }.single()
        assertEquals(mapOf(firstKey to id(1), secondKey to id(2)), family.copyCanonicalIds)
        assertEquals(mapOf(id(1) to 42, id(2) to 43), family.memberSteamAppIds)
        assertEquals(before, db.canonicalLibraryDao().observePresentGames().first())
        assertFalse(family.copies.any { it.source == GameSource.STEAM })
        assertEquals(setOf(GameSource.GOG, GameSource.EPIC), family.ownedSources)
        assertEquals(2, images.requests.size)
    }

    @Test
    fun rawLocalCardsAreUsableWhileOptionalArtworkIsBlocked() = runFlowTest {
        seed()
        val repository = repository()
        val entered = CompletableDeferred<Unit>()
        var cleaned = false
        images.action = {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cleaned = true }
        }
        withCards(repository) { channel ->
            assertEquals(2, next(channel) { it.size == 2 }.size)
            entered.await()
            assertTrue(nativeReads > 0)
        }
        assertTrue(cleaned)
    }

    @Test
    fun optionalTransportFailureRetainsExactRawLibraryCardsAndActions() = runFlowTest {
        seed()
        val repository = repository()
        val attempted = CompletableDeferred<Unit>()
        images.action = { attempted.complete(Unit); throw IOException("synthetic unavailable artwork") }
        withCards(repository) { channel ->
            val raw = next(channel) { it.size == 2 }
            attempted.await()
            assertEquals(setOf(firstKey, secondKey), raw.flatMap { it.copies }.map { it.key }.toSet())
            assertTrue(raw.flatMap { it.copies }.all { OwnedCopyOperation.INSTALL in it.capabilities })
        }
    }

    @Test
    fun recreatedRepositoryProducesTheSameAnchorAndExactMembership() = runFlowTest {
        seed()
        val first = repository().observeCards().first { it.size == 1 }.single()
        val recreated = repository().observeCards().first { it.size == 1 }.single()
        assertEquals(first, recreated)
        assertEquals(CanonicalCardKey.Grouped(id(1)), recreated.key)
        assertEquals(setOf("Fixture Game", "Fixture Game Deluxe"), recreated.copies.map { it.nativeTitle }.toSet())
    }

    @Test
    fun originalArtworkInvalidationCanDissolveTheFamilyWithoutRematchingMembers() = runFlowTest {
        seed()
        val repository = repository()
        withCards(repository) { channel ->
            next(channel) { it.size == 1 }
            images.action = { url -> fingerprint(distinct = url.endsWith("2.jpg")) }
            invalidations.emit(Unit)
            val raw = next(channel) { it.size == 2 }
            assertEquals(setOf(id(1), id(2)), raw.map { it.canonicalId }.toSet())
            assertEquals(42, db.canonicalGameDao().get(id(1).value)!!.steamAppId)
            assertEquals(43, db.canonicalGameDao().get(id(2).value)!!.steamAppId)
        }
    }

    @Test
    fun retiredRoomMemberCancelsPendingArtworkBeforeAnyStaleFamilyPublication() = runFlowTest {
        seed()
        val repository = repository()
        val entered = CompletableDeferred<Unit>()
        var cleaned = false
        images.action = { url ->
            if (url.endsWith("2.jpg")) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cleaned = true }
            } else fingerprint()
        }
        withCards(repository) { channel ->
            next(channel) { it.size == 2 }
            entered.await()
            db.storeMatchDao().upsert(match(1, firstKey).copy(isPresent = false))
            val current = next(channel) { it.sumOf { card -> card.copies.size } == 1 }.single()
            assertEquals(id(2), current.canonicalId)
            assertEquals(secondKey, current.copies.single().key)
            assertTrue(cleaned)
            assertEquals(mapOf(secondKey to id(2)), current.copyCanonicalIds)
            assertEquals(mapOf(id(2) to 43), current.memberSteamAppIds)
            assertFalse(current.isPresentationFamily)
        }
    }

    @Test
    fun stickyRoomKeepSeparateIsNeverRegroupedOrUsedForArtworkAcquisition() = runFlowTest {
        seed()
        db.storeMatchDao().upsert(match(2, secondKey).copy(
            matchMethod = MatchMethod.MANUAL, confidence = MatchConfidence.REJECTED,
            decisionSource = MatchDecisionSource.USER,
        ))
        val repository = repository()
        val raw = repository.observeCards().first()
        assertEquals(2, raw.size)
        assertTrue(raw.flatMap { it.copies }.any { it.confidence == MatchConfidence.REJECTED })
        assertTrue(images.requests.isEmpty())
    }

    @Test
    fun sameStoreInstalledEditionsKeepTheirLabelsPathsAndIndependentCapabilities() = runFlowTest {
        seed()
        val thirdKey = key(3, GameSource.GOG)
        seedMember(3, thirdKey, "Fixture Game Ultimate")
        for (copyKey in listOf(firstKey, thirdKey)) {
            val copy = (runtimes.getValue(copyKey) as OwnedCopyRuntimeResult.Available).copy
            runtimes[copyKey] = OwnedCopyRuntimeResult.Available(copy.copy(isInstalled = true,
                installPath = "synthetic-install-${copyKey.stableSourceId}", installedSizeBytes = copyKey.stableSourceId.toLong(),
                capabilities = setOf(OwnedCopyOperation.PLAY, OwnedCopyOperation.UNINSTALL)))
        }
        val family = repository().observeCards().first { it.size == 1 }.single()
        val installed = family.copies.filter { it.isInstalled }
        assertEquals(setOf("Fixture Game", "Fixture Game Ultimate"), installed.map { it.nativeTitle }.toSet())
        assertEquals(setOf("synthetic-install-1", "synthetic-install-3"), installed.map { it.installPath }.toSet())
        assertEquals(setOf(firstKey, thirdKey), installed.map { it.key }.toSet())
        assertTrue(installed.all { OwnedCopyOperation.PLAY in it.capabilities })
        assertEquals(OwnedCopyOperation.INSTALL, family.copies.single { it.key == secondKey }.capabilities.single())
    }

    @Test
    fun sharedFamilySnapshotRetainsSearchAliasesSourceMembershipAndDetailMemberIds() = runFlowTest {
        seed()
        val family = repository().observeCards().first { it.size == 1 }.single()
        assertTrue(family.aliases.any { it.contains("Deluxe") })
        assertTrue(GameSource.GOG in family.ownedSources)
        assertTrue(GameSource.EPIC in family.ownedSources)
        assertEquals(id(2), family.copyCanonicalIds[secondKey])
        assertEquals(43, family.memberSteamAppIds[id(2)])
        assertEquals(42, family.steamAppId)
        assertTrue(family.steamCollectionAppIds.isEmpty())
    }

    @Test
    fun changedNativeSourceAfterImagesCannotPublishTheOldRoomFamilySnapshot() = runFlowTest {
        seed()
        val repository = repository()
        val attempted = CompletableDeferred<Unit>()
        images.action = { url ->
            if (url.endsWith("2.jpg")) {
                val copy = (runtimes.getValue(firstKey) as OwnedCopyRuntimeResult.Available).copy
                runtimes[firstKey] = OwnedCopyRuntimeResult.Available(copy.copy(originalArtworkUrl = "https://images.gog.com/changed.jpg"))
                attempted.complete(Unit)
            }
            fingerprint()
        }
        withCards(repository) { channel ->
            val raw = next(channel) { it.size == 2 }
            attempted.await()
            assertEquals(setOf(firstKey, secondKey), raw.flatMap { it.copies }.map { it.key }.toSet())
        }
    }

    @Test
    fun actualSearchAndSourceFiltersRetainOneFamilyAndTheSameDetailRoute() = runFlowTest {
        seed()
        app.gamenative.PrefManager.init(ApplicationProvider.getApplicationContext())
        val family = repository().observeCards().first { it.size == 1 }.single()
        val state = app.gamenative.ui.data.LibraryState(
            appInfoSortType = java.util.EnumSet.of(app.gamenative.ui.enums.AppFilter.GAME),
            currentTab = app.gamenative.ui.enums.LibraryTab.ALL, searchQuery = "Deluxe",
            showSteamInLibrary = true, showGOGInLibrary = true, showEpicInLibrary = true,
            showAmazonInLibrary = true, showCustomGamesInLibrary = true,
            selectedSteamCollectionIds = emptySet(), selectedCuratedListIds = emptySet(), steamReviewMinimum = null,
        )
        for (tab in listOf(app.gamenative.ui.enums.LibraryTab.ALL, app.gamenative.ui.enums.LibraryTab.GOG,
            app.gamenative.ui.enums.LibraryTab.EPIC)) {
            val page = app.gamenative.ui.model.CanonicalLibraryFilter.project(
                cards = listOf(family), state = state.copy(currentTab = tab), paginationPage = 0, pageSize = 50,
                promotion = null, showRecommendations = false, compatibility = { null },
            )
            assertEquals(1, page.totalCount)
            assertEquals(app.gamenative.ui.data.LibraryCardIdentity.Canonical(family.key), page.cards.single().identity)
            assertEquals("Fixture Game", page.cards.single().name)
        }
    }

    private fun runFlowTest(body: suspend () -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(10_000) { body() } }
    }

    private fun repository(): CanonicalLibraryRepository {
        val constructor = CanonicalLibraryRepository::class.java.constructors.singleOrNull { it.parameterCount == 6 }
        assertTrue("Shared canonical library flow must consume native family evidence", constructor != null)
        val producer = CanonicalFamilyArtworkCorroborator(images, registry::resolve)
        return constructor!!.newInstance(db.canonicalLibraryDao(), registry, NoOpCanonicalLibraryDiagnosticSink,
            mockk<app.gamenative.library.discovery.GameFacetRepository>(relaxed = true), SystemMetadataLocaleProvider(), producer)
            as CanonicalLibraryRepository
    }

    private suspend fun withCards(repository: CanonicalLibraryRepository, body: suspend (Channel<List<CanonicalLibraryCard>>) -> Unit) = coroutineScope {
        val channel = Channel<List<CanonicalLibraryCard>>(Channel.UNLIMITED)
        val collector = launch { repository.observeCards().collect { channel.send(it) } }
        try { body(channel) } finally { collector.cancelAndJoin(); channel.close() }
    }

    private suspend fun next(channel: Channel<List<CanonicalLibraryCard>>, accept: (List<CanonicalLibraryCard>) -> Boolean): List<CanonicalLibraryCard> {
        while (true) {
            val cards = channel.receive()
            if (accept(cards)) return cards
        }
    }

    private suspend fun seed() {
        seedMember(1, firstKey, "Fixture Game")
        seedMember(2, secondKey, "Fixture Game Deluxe")
    }

    private suspend fun seedMember(index: Long, key: OwnedCopyKey, title: String) {
        db.canonicalGameDao().insert(CanonicalGameEntity(
            canonicalId = id(index).value, steamAppId = 41 + index.toInt(), displayName = title, matchTitleKey = title.lowercase(),
            primaryMetadataSource = key.source, appType = CanonicalAppType.GAME, releaseYear = null, developerKey = "",
            classificationState = ClassificationState.UNCLASSIFIED, steamReviewCount = null, createdAt = 1, updatedAt = 1,
        ))
        db.storeMatchDao().upsert(match(index, key, title))
        runtimes[key] = OwnedCopyRuntimeResult.Available(runtime(index, key, title))
    }

    private fun match(index: Long, key: OwnedCopyKey, title: String = if (index == 1L) "Fixture Game" else "Fixture Game Deluxe") = StoreMatchEntity(
        accountScope = key.accountScope.value, source = key.source, stableSourceId = key.stableSourceId,
        canonicalId = id(index).value, candidateSteamAppId = 41 + index.toInt(), matchMethod = MatchMethod.STEAM_CATALOG,
        confidence = MatchConfidence.HIGH, decisionSource = MatchDecisionSource.AUTOMATIC, resolverVersion = CURRENT_RESOLVER_VERSION,
        matchedAt = 100, isPresent = true, evidenceDisplayName = title, evidenceTitleKey = title.lowercase(),
        evidenceDeveloperKey = "", evidenceReleaseYear = null, evidenceAppType = CanonicalAppType.GAME,
    )

    private fun key(index: Long, source: GameSource) = OwnedCopyKey(AccountScope("2".repeat(64)), source,
        if (source == GameSource.EPIC) EpicStableSourceId.encode("fixture-namespace", "fixture-$index") else "$index")
    private fun id(index: Long) = CanonicalGameId.parse(UUID(0, index).toString())
    private fun id(index: Int) = id(index.toLong())

    private fun runtime(index: Long, key: OwnedCopyKey, title: String) = OwnedCopyRuntime(
        key = key, reference = if (key.source == GameSource.EPIC) SourceOwnedCopyReference.Epic(key, index.toInt(), "fixture-namespace", "fixture-$index")
            else SourceOwnedCopyReference.Gog(key, key.stableSourceId),
        libraryItem = LibraryItem(appId = "${key.source.name}_$index", name = title, gameSource = key.source),
        nativeTitle = title, aliases = emptySet(), developerKey = "", releaseYear = null, appType = CanonicalAppType.GAME,
        genreKeys = emptySet(), tagIds = emptySet(), featureKeys = emptySet(), iconUrl = "", capsuleImageUrl = "", headerImageUrl = "",
        heroImageUrl = "", gridHeroImageScale = 1f, installPath = null, installedSizeBytes = null, branchOrVersion = null,
        isInstalled = false, isDownloading = false, hasPartialDownload = false, updateAvailable = false, isShared = false,
        lastPlayedEpochMs = null, playtimeMinutes = null, capabilities = setOf(OwnedCopyOperation.INSTALL),
        originalArtworkUrl = if (key.source == GameSource.EPIC) "https://cdn1.epicgames.com/fixture-$index.jpg" else "https://images.gog.com/fixture-$index.jpg",
    )

    private inner class Adapter(override val source: GameSource) : OwnedCopyRuntimeAdapter {
        override fun invalidations(): Flow<Unit> = if (source == GameSource.GOG) invalidations else emptyFlow()
        override suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult {
            assertFalse("Native evidence lookup must stay outside Room transactions", db.inTransaction())
            nativeReads++
            return runtimes[key] ?: OwnedCopyRuntimeResult.Hidden
        }
        override suspend fun resolveAll(keys: Set<OwnedCopyKey>): Map<OwnedCopyKey, OwnedCopyRuntimeResult> = keys.associateWith {
            runtimes[it] ?: OwnedCopyRuntimeResult.Hidden
        }
    }

    private fun fingerprint(distinct: Boolean = false) = requireNotNull(ArtworkFingerprint.fromArgb(192, 288, ArtworkFixtures.cover(192, 288, distinct)))
    private inner class Images : ArtworkFingerprintSource {
        val requests = mutableListOf<String>()
        var action: suspend (String) -> ArtworkFingerprint? = { fingerprint() }
        override suspend fun original(source: GameSource, raw: String, steamAppId: Int?): ArtworkFingerprint? {
            assertFalse("Image acquisition must stay outside Room transactions", db.inTransaction())
            requests += raw
            return action(raw)
        }
        override suspend fun candidate(steamAppId: Int, raw: String): ArtworkFingerprint? = throw AssertionError("No catalog candidate image in family projection")
    }
}
