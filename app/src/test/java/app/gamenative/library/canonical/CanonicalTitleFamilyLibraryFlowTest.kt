package app.gamenative.library.canonical

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.*
import app.gamenative.db.PluviaDatabase
import app.gamenative.library.canonical.action.OwnedCopyActionRouter
import app.gamenative.library.canonical.action.OwnedCopyRouteResult
import app.gamenative.library.canonical.artwork.*
import app.gamenative.library.canonical.catalog.ResolutionWorkerTestApplication
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.library.metadata.MetadataClock
import app.gamenative.library.metadata.SystemMetadataLocaleProvider
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.model.CanonicalLibraryFilter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.EnumSet
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.junit.rules.TimeoutRule

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = ResolutionWorkerTestApplication::class, sdk = [29])
class CanonicalTitleFamilyLibraryFlowTest {
    @get:Rule val timeout = TimeoutRule.seconds(30)
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var db: PluviaDatabase
    private val registry = mockk<OwnedCopyRuntimeRegistry>()
    private val matcher = mockk<ArtworkFeatureMatcher>()
    private val runtimes = linkedMapOf<OwnedCopyKey, OwnedCopyRuntimeResult>()
    private val firstKey = key(1, GameSource.GOG)
    private val secondKey = key(2, GameSource.EPIC)
    private val cacheDirectory by lazy { temporary.newFolder() }
    private val originals = object : ArtworkFeatureSource {
        override suspend fun original(reference: ArtworkReference): ArtworkFeatures? = null
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PluviaDatabase::class.java)
            .allowMainThreadQueries().build()
        every { registry.invalidations() } returns emptyFlow()
        coEvery { registry.resolve(any()) } answers { runtimes[firstArg()] ?: OwnedCopyRuntimeResult.Hidden }
        coEvery { registry.resolveAll(any(), any()) } answers {
            secondArg<Set<OwnedCopyKey>>().associateWith { runtimes[it] ?: OwnedCopyRuntimeResult.Hidden }
        }
        PrefManager.init(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() = db.close()

    @Test fun sameNameUnavailableArtGroupsInActualRoomSearchSourceDetailAndExactActionPaths() = runTest {
        withContext(Dispatchers.Default) { withTimeout(10_000) {
            seed()
            val before = db.canonicalLibraryDao().observePresentGames().first()
            val family = repository().observeCards().first().single()
            assertEquals(mapOf(firstKey to id(1), secondKey to id(2)), family.copyCanonicalIds)
            assertEquals(mapOf(id(1) to 42, id(2) to 43), family.memberSteamAppIds)
            assertEquals(setOf(firstKey, secondKey), family.copies.map { it.key }.toSet())
            assertEquals(before, db.canonicalLibraryDao().observePresentGames().first())
            assertFalse(family.copies.any { it.source == GameSource.STEAM })
            assertEquals(family, repository().observeCards().first().single())
            for (member in listOf(id(1), id(2))) {
                assertEquals(family, CanonicalCardLookup.resolve(listOf(family), CanonicalCardKey.Grouped(member)))
            }
            val state = LibraryState(appInfoSortType = EnumSet.of(AppFilter.GAME), currentTab = LibraryTab.ALL,
                searchQuery = "Same public game", showSteamInLibrary = true, showGOGInLibrary = true,
                showEpicInLibrary = true, showAmazonInLibrary = true, showCustomGamesInLibrary = true,
                selectedSteamCollectionIds = emptySet(), selectedCuratedListIds = emptySet(), steamReviewMinimum = null)
            for (tab in listOf(LibraryTab.ALL, LibraryTab.GOG, LibraryTab.EPIC)) {
                val page = CanonicalLibraryFilter.project(listOf(family), state.copy(currentTab = tab), 0, 50,
                    null, false, compatibility = { null })
                assertEquals(1, page.totalCount)
                assertEquals(family.displayName, page.cards.single().name)
            }
            val gate = mockk<CanonicalPublicLibraryGate>().also { every { it.isEnabled() } returns true }
            val clock = mockk<CanonicalProjectionClock>().also { every { it.nowEpochMs() } returns 500L }
            val router = OwnedCopyActionRouter(registry, PreferredCopyRepository(db), gate, clock, NoOpCanonicalLibraryDiagnosticSink)
            assertEquals(OwnedCopyRouteResult.NeedsChooser(listOf(firstKey, secondKey)), router.route(family, OwnedCopyOperation.INSTALL))
            val exact = router.route(family, OwnedCopyOperation.INSTALL, secondKey) as OwnedCopyRouteResult.Ready
            assertEquals(secondKey, exact.guard.key)
            assertEquals("EPIC_2", exact.guard.initialLibraryItem.appId)
            assertEquals(before, db.canonicalLibraryDao().observePresentGames().first())
            verify(exactly = 0) { matcher.compare(any(), any()) }
        } }
    }

    @Test fun sameNameRoomUserRejectionStaysSeparateWithoutChangingItsDecision() = runTest {
        withContext(Dispatchers.Default) { withTimeout(10_000) {
            seed()
            val match = db.storeMatchDao().get(secondKey.accountScope.value, secondKey.source, secondKey.stableSourceId)!!
            db.storeMatchDao().upsert(match.copy(matchMethod = MatchMethod.MANUAL, confidence = MatchConfidence.REJECTED,
                decisionSource = MatchDecisionSource.USER))
            val before = db.canonicalLibraryDao().observePresentGames().first()
            val cards = repository().observeCards().first()
            assertEquals(2, cards.size)
            assertTrue(cards.flatMap { it.copies }.any { it.confidence == MatchConfidence.REJECTED })
            assertEquals(before, db.canonicalLibraryDao().observePresentGames().first())
        } }
    }

    @Test fun actualOneShotAggregationReportPreservesRoomNativeStateAndDoesNotLaunchArtworkWorker() = runTest {
        withContext(Dispatchers.Default) { withTimeout(10_000) {
            seed()
            val selected = repository().observeCards().first().single()
            val before = db.canonicalLibraryDao().getPresentGames()
            val cacheBefore = cacheDirectory.listFiles().orEmpty().associate { it.name to it.readBytes().toList() }
            val report = repository().explainAggregation(selected)
            assertTrue(report.contains("Admitted support: TITLE"))
            assertTrue(report.contains("neither a matching known developer nor release years within one"))
            assertTrue(report.contains("no admitted original references"))
            assertTrue(report.contains("title alone is sufficient"))
            assertFalse(report.contains(firstKey.accountScope.value))
            assertFalse(report.contains(firstKey.toString()))
            assertEquals(before, db.canonicalLibraryDao().getPresentGames())
            assertEquals(cacheBefore, cacheDirectory.listFiles().orEmpty().associate { it.name to it.readBytes().toList() })
            verify(exactly = 0) { matcher.compare(any(), any()) }
        } }
    }

    @Test fun oneShotAggregationReportRejectsStaleAndDisappearedSelectionWithoutMutation() = runTest {
        withContext(Dispatchers.Default) { withTimeout(10_000) {
            seed()
            val selected = repository().observeCards().first().single()
            val match = db.storeMatchDao().get(secondKey.accountScope.value, secondKey.source, secondKey.stableSourceId)!!
            db.storeMatchDao().upsert(match.copy(isPresent = false))
            val before = db.canonicalLibraryDao().getPresentGames()
            assertTrue(repository().explainAggregation(selected).contains("STALE"))
            assertEquals(before, db.canonicalLibraryDao().getPresentGames())
            verify(exactly = 0) { matcher.compare(any(), any()) }
        } }
    }

    @Test fun existingSharedMetadataAssociationIsReportedWithoutInventingABannerOrTitleEdge() = runTest {
        withContext(Dispatchers.Default) { withTimeout(10_000) {
            seed()
            val epic = db.storeMatchDao().get(secondKey.accountScope.value, secondKey.source, secondKey.stableSourceId)!!
            db.storeMatchDao().upsert(epic.copy(canonicalId = id(1).value, candidateSteamAppId = 42))
            val selected = repository().observeCards().first().single()
            val before = db.canonicalLibraryDao().getPresentGames()
            val report = repository().explainAggregation(selected)
            assertTrue(report.contains("Admitted support: METADATA"))
            assertTrue(report.contains("Metadata association: ACCEPTED/STORED"))
            assertTrue(report.contains("SKIPPED — no separate admitted original pair"))
            assertEquals(setOf(firstKey, secondKey), selected.copies.map { it.key }.toSet())
            assertEquals(before, db.canonicalLibraryDao().getPresentGames())
            verify(exactly = 0) { matcher.compare(any(), any()) }
        } }
    }

    @Test fun malformedDuplicateSelectionReturnsFixedStaleReportWithoutMutation() = runTest {
        seed()
        val selected = repository().observeCards().first().single()
        val before = db.canonicalLibraryDao().getPresentGames()
        assertTrue(repository().explainAggregation(selected.copy(copies = selected.copies + selected.copies.first())).contains("STALE"))
        assertEquals(before, db.canonicalLibraryDao().getPresentGames())
        verify(exactly = 0) { matcher.compare(any(), any()) }
    }

    @Test fun reportHonorsCurrentRoomManualSeparationInsteadOfTitleFallback() = runTest {
        withContext(Dispatchers.Default) { withTimeout(10_000) {
            seed()
            val match = db.storeMatchDao().get(secondKey.accountScope.value, secondKey.source, secondKey.stableSourceId)!!
            db.storeMatchDao().upsert(match.copy(matchMethod = MatchMethod.MANUAL, confidence = MatchConfidence.REJECTED,
                decisionSource = MatchDecisionSource.USER))
            val selected = repository().observeCards().first().first { it.copies.any { copy -> copy.key == firstKey } }
            val before = db.canonicalLibraryDao().getPresentGames()
            val report = repository().explainAggregation(selected)
            assertTrue(report.contains("Admitted support: NONE"))
            assertTrue(report.contains("SKIPPED — manual/native/identity safeguard"))
            assertTrue(report.contains("MANUAL/REJECTED, USER"))
            assertEquals(before, db.canonicalLibraryDao().getPresentGames())
        } }
    }

    private fun repository(): CanonicalLibraryRepository {
        val owner = CanonicalFamilyArtworkCorroborator(originals, ArtworkFeatureCache(cacheDirectory, MetadataClock { 100L }),
            matcher, registry::resolve)
        return CanonicalLibraryRepository(db.canonicalLibraryDao(), registry, NoOpCanonicalLibraryDiagnosticSink,
            mockk(relaxed = true), SystemMetadataLocaleProvider(), owner)
    }

    private suspend fun seed() {
        for ((index, copyKey) in listOf(1L to firstKey, 2L to secondKey)) {
            val title = "Same public game"
            db.canonicalGameDao().insert(CanonicalGameEntity(canonicalId = id(index).value, steamAppId = 41 + index.toInt(),
                displayName = title, matchTitleKey = title.lowercase(), primaryMetadataSource = copyKey.source,
                appType = CanonicalAppType.GAME, releaseYear = null, developerKey = "",
                classificationState = ClassificationState.UNCLASSIFIED, steamReviewCount = null, createdAt = 1, updatedAt = 1))
            db.storeMatchDao().upsert(StoreMatchEntity(accountScope = copyKey.accountScope.value, source = copyKey.source,
                stableSourceId = copyKey.stableSourceId, canonicalId = id(index).value, candidateSteamAppId = 41 + index.toInt(),
                matchMethod = MatchMethod.STEAM_CATALOG, confidence = MatchConfidence.HIGH,
                decisionSource = MatchDecisionSource.AUTOMATIC, resolverVersion = CURRENT_RESOLVER_VERSION,
                matchedAt = 100, isPresent = true, evidenceDisplayName = title, evidenceTitleKey = title.lowercase(),
                evidenceDeveloperKey = "", evidenceReleaseYear = null, evidenceAppType = CanonicalAppType.GAME))
            runtimes[copyKey] = OwnedCopyRuntimeResult.Available(OwnedCopyRuntime(copyKey,
                if (copyKey.source == GameSource.EPIC) SourceOwnedCopyReference.Epic(copyKey, index.toInt(), "fixture", "item-$index")
                else SourceOwnedCopyReference.Gog(copyKey, copyKey.stableSourceId),
                LibraryItem(appId = "${copyKey.source.name}_$index", name = title, gameSource = copyKey.source),
                title, emptySet(), "", null, CanonicalAppType.GAME, emptySet(), emptySet(), emptySet(),
                "", "", "", "", 1f, null, null, null, false, false, false, false, false, null, null,
                setOf(OwnedCopyOperation.INSTALL)))
        }
    }

    private fun key(index: Long, source: GameSource) = OwnedCopyKey(AccountScope("2".repeat(64)), source,
        if (source == GameSource.EPIC) EpicStableSourceId.encode("fixture", "item-$index") else "$index")
    private fun id(index: Long) = CanonicalGameId.parse(UUID(0, index).toString())
}
