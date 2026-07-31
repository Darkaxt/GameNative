package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.dao.CanonicalLibraryAggregate
import app.gamenative.db.dao.CanonicalLibraryDao
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.diagnostics.DiagnosticLogStore
import app.gamenative.diagnostics.DiagnosticReportBuilder
import app.gamenative.diagnostics.DiagnosticReportHeader
import app.gamenative.diagnostics.FeatureDiagnostics
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeAdapter
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.ui.data.LibraryCardIdentity
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.SortOption
import app.gamenative.ui.model.CanonicalLibraryFilter
import io.mockk.every
import io.mockk.mockk
import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CanonicalLibraryScaleTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var originalDiagnosticStore: DiagnosticLogStore? = null

    @Before
    fun installDiagnosticStore() {
        originalDiagnosticStore = featureDiagnosticsStore()
        setFeatureDiagnosticsStore(
            DiagnosticLogStore(
                directory = temporaryFolder.newFolder("scale-diagnostics"),
                json = Json { ignoreUnknownKeys = true },
            ),
        )
    }

    @After
    fun restoreDiagnosticStore() {
        setFeatureDiagnosticsStore(originalDiagnosticStore)
    }

    @Test
    fun `fixed fifteen hundred copy fixture stays grouped before filtering and pagination`() = runTest {
        val fixture = ScaleFixture()
        val repository = fixture.repository(FeatureCanonicalLibraryDiagnostics())

        val firstAssemblyStarted = System.nanoTime()
        val firstCards = repository.observeCards().first()
        val firstAssemblyMs = elapsedMs(firstAssemblyStarted)
        val firstBatchCalls = fixture.adapters.values.sumOf { it.batches.size }
        val firstDaoCalls = fixture.dao.observeCalls.get()

        val secondAssemblyStarted = System.nanoTime()
        val secondCards = repository.observeCards().first()
        val secondAssemblyMs = elapsedMs(secondAssemblyStarted)
        val secondBatchCalls = fixture.adapters.values.sumOf { it.batches.size } - firstBatchCalls
        val secondDaoCalls = fixture.dao.observeCalls.get() - firstDaoCalls

        assertEquals(SCALE_CARD_COUNT, firstCards.size)
        assertEquals(SCALE_COPY_COUNT, firstCards.sumOf { it.copies.size })
        assertEquals(firstCards, secondCards)
        assertEquals(SCALE_CARD_COUNT, firstCards.map { it.key }.toSet().size)
        assertEquals(SCALE_COPY_COUNT, firstCards.flatMap { it.copies }.map { it.key }.toSet().size)
        assertTrue(firstCards.all { it.key is CanonicalCardKey.Grouped })
        assertMembership(firstCards)

        assertEquals(MAX_SOURCE_BATCH_CALLS, firstBatchCalls)
        assertEquals(MAX_SOURCE_BATCH_CALLS, secondBatchCalls)
        assertEquals(1, firstDaoCalls)
        assertEquals(1, secondDaoCalls)
        fixture.adapters.values.forEach { adapter ->
            assertEquals(0, adapter.pointResolveCalls.get())
            assertEquals(2, adapter.batches.size)
            assertTrue(adapter.maxConcurrentBatchCalls.get() <= MAX_SOURCE_BATCH_CALLS)
        }
        assertEquals(SCALE_CARD_COUNT, fixture.adapters.getValue(GameSource.STEAM).batches.first().size)
        NON_STEAM_SOURCES.forEach { source ->
            assertEquals(MATCHED_COPIES_PER_NON_STEAM_SOURCE, fixture.adapters.getValue(source).batches.first().size)
        }

        val filterStarted = System.nanoTime()
        val pageTwo = project(firstCards, LibraryTab.ALL, paginationPage = 2)
        val filterMs = elapsedMs(filterStarted)
        val firstPage = project(firstCards, LibraryTab.ALL, paginationPage = 0)
        val secondPage = project(firstCards, LibraryTab.ALL, paginationPage = 1)
        val finalPage = project(firstCards, LibraryTab.ALL, paginationPage = FINAL_PAGE_INDEX)

        assertEquals(SCALE_CARD_COUNT, pageTwo.totalCount)
        assertEquals(SCALE_CARD_COUNT, pageTwo.allCount)
        assertEquals(SCALE_CARD_COUNT, pageTwo.sourceCounts.getValue(GameSource.STEAM))
        NON_STEAM_SOURCES.forEach { source ->
            assertEquals(MATCHED_COPIES_PER_NON_STEAM_SOURCE, pageTwo.sourceCounts.getValue(source))
            assertEquals(
                MATCHED_COPIES_PER_NON_STEAM_SOURCE,
                project(firstCards, source.libraryTab(), paginationPage = FINAL_PAGE_INDEX).totalCount,
            )
        }
        assertEquals(PAGE_SIZE, firstPage.cards.size)
        assertEquals(PAGE_SIZE * 2, secondPage.cards.size)
        assertEquals(PAGE_SIZE * 3, pageTwo.cards.size)
        assertEquals(SCALE_CARD_COUNT, finalPage.cards.size)
        assertEquals(FINAL_PAGE_INDEX, finalPage.lastPage)
        assertEquals(SCALE_CARD_COUNT, finalPage.cards.map { it.composeKey }.toSet().size)
        assertBoundaryCard(pageTwo, index = 49, gameNumber = 50, expectedSource = GameSource.GOG)
        assertBoundaryCard(pageTwo, index = 50, gameNumber = 51, expectedSource = GameSource.GOG)
        assertBoundaryCard(pageTwo, index = 99, gameNumber = 100, expectedSource = GameSource.GOG)
        assertBoundaryCard(pageTwo, index = 100, gameNumber = 101, expectedSource = GameSource.GOG)
        assertEquals(gameTitle(SCALE_CARD_COUNT), finalPage.cards.last().name)
        assertEquals(setOf(GameSource.STEAM), finalPage.cards.last().ownedSources)
        assertTrue(finalPage.cards.all { it.identity is LibraryCardIdentity.Canonical })

        val events = FeatureDiagnostics.recent()
        val report = DiagnosticReportBuilder.build(
            header = DiagnosticReportHeader(
                appVersion = "scale-test-version",
                buildFlavor = "scale-test-flavor",
                device = "scale-test-device",
                androidVersion = "scale-test-android",
            ),
            events = events,
        )
        assertEquals(2, events.size)
        events.forEach { event ->
            assertEquals("900", event.attributes.getValue("result_count"))
            assertEquals("900", event.attributes.getValue("canonical_count"))
            assertEquals("1500", event.attributes.getValue("copy_count"))
            assertTrue(event.attributes.keys.all(SAFE_DIAGNOSTIC_ATTRIBUTES::contains))
        }
        fixture.forbiddenDiagnosticValues.forEach { privateValue ->
            assertFalse("Private scale value reached diagnostics: $privateValue", report.contains(privateValue))
        }

        println(
            "TASK12_SCALE_METRICS " +
                "assembly_first_ms=$firstAssemblyMs " +
                "assembly_repeat_ms=$secondAssemblyMs " +
                "filter_ms=$filterMs cards=${firstCards.size} copies=$SCALE_COPY_COUNT",
        )
    }

    private fun project(
        cards: List<CanonicalLibraryCard>,
        tab: LibraryTab,
        paginationPage: Int,
    ) = CanonicalLibraryFilter.project(
        cards = cards,
        state = LibraryState(
            appInfoSortType = EnumSet.of(AppFilter.GAME),
            currentSortOption = SortOption.NAME_ASC,
            currentTab = tab,
            showSteamInLibrary = true,
            showGOGInLibrary = true,
            showEpicInLibrary = true,
            showAmazonInLibrary = true,
            showCustomGamesInLibrary = true,
            selectedSteamCollectionIds = emptySet(),
        ),
        paginationPage = paginationPage,
        pageSize = PAGE_SIZE,
        promotion = null,
        showRecommendations = false,
        compatibility = { null },
    )

    private fun assertMembership(cards: List<CanonicalLibraryCard>) {
        cards.forEachIndexed { zeroBasedIndex, card ->
            val gameNumber = zeroBasedIndex + 1
            val expectedSources = buildSet {
                add(GameSource.STEAM)
                nonSteamSource(gameNumber)?.let(::add)
            }
            assertEquals(gameTitle(gameNumber), card.displayName)
            assertEquals(expectedSources, card.ownedSources)
            assertEquals(expectedSources, card.copies.mapTo(linkedSetOf()) { it.source })
            assertEquals(expectedSources.size, card.copies.size)
        }
    }

    private fun assertBoundaryCard(
        page: app.gamenative.ui.model.CanonicalLibraryPage,
        index: Int,
        gameNumber: Int,
        expectedSource: GameSource,
    ) {
        val card = page.cards[index]
        assertEquals(gameTitle(gameNumber), card.name)
        assertEquals(setOf(GameSource.STEAM, expectedSource), card.ownedSources)
    }

    private fun featureDiagnosticsStore(): DiagnosticLogStore? =
        STORE_FIELD.get(null) as DiagnosticLogStore?

    private fun setFeatureDiagnosticsStore(store: DiagnosticLogStore?) {
        STORE_FIELD.set(null, store)
    }

    private class ScaleFixture {
        val dao: RecordingDao
        val adapters: Map<GameSource, RecordingRuntimeAdapter>
        val forbiddenDiagnosticValues: Set<String>

        init {
            val privateValues = linkedSetOf(
                PRIVATE_SCOPE.value,
                PRIVATE_TOKEN,
                PRIVATE_URL,
                PRIVATE_PATH_ROOT,
            )
            val aggregates = (1..SCALE_CARD_COUNT).map { gameNumber ->
                val game = game(gameNumber)
                privateValues += game.canonicalId
                privateValues += game.displayName
                val matches = buildList {
                    add(match(game, GameSource.STEAM, steamId(gameNumber), MatchConfidence.VERIFIED))
                    nonSteamSource(gameNumber)?.let { source ->
                        add(match(game, source, stableId(source, gameNumber), MatchConfidence.HIGH))
                    }
                }
                matches.forEach { match -> privateValues += match.stableSourceId }
                CanonicalLibraryAggregate(game, matches, emptyList())
            }
            dao = RecordingDao(flowOf(aggregates))
            adapters = SOURCE_ORDER.associateWith(::RecordingRuntimeAdapter)
            forbiddenDiagnosticValues = privateValues
        }

        fun repository(diagnostics: CanonicalLibraryDiagnosticSink): CanonicalLibraryRepository {
            val history = mockk<LibraryPlayHistoryDao>()
            every { history.getAll() } returns emptyFlow()
            val registry = OwnedCopyRuntimeRegistry(
                adapters = adapters.values.toSet(),
                playHistoryDao = history,
                diagnostics = mockk(relaxed = true),
            )
            return CanonicalLibraryRepository(dao, registry, diagnostics)
        }
    }

    private class RecordingDao(
        private val aggregates: Flow<List<CanonicalLibraryAggregate>>,
    ) : CanonicalLibraryDao {
        val observeCalls = AtomicInteger()

        override fun observePresentGames(): Flow<List<CanonicalLibraryAggregate>> {
            observeCalls.incrementAndGet()
            return aggregates
        }
    }

    private class RecordingRuntimeAdapter(
        override val source: GameSource,
    ) : OwnedCopyRuntimeAdapter {
        val pointResolveCalls = AtomicInteger()
        val batches = Collections.synchronizedList(mutableListOf<Set<OwnedCopyKey>>())
        val maxConcurrentBatchCalls = AtomicInteger()
        private val activeBatchCalls = AtomicInteger()

        override fun invalidations(): Flow<Unit> = emptyFlow()

        override suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult {
            pointResolveCalls.incrementAndGet()
            error("Scale assembly must use source batches, not point resolution")
        }

        override suspend fun resolveAll(
            keys: Set<OwnedCopyKey>,
        ): Map<OwnedCopyKey, OwnedCopyRuntimeResult> {
            require(keys.all { it.source == source })
            val active = activeBatchCalls.incrementAndGet()
            maxConcurrentBatchCalls.updateAndGet { current -> max(current, active) }
            return try {
                batches += keys.toSet()
                keys.associateWith(::available)
            } finally {
                activeBatchCalls.decrementAndGet()
            }
        }
    }

    private companion object {
        const val SCALE_CARD_COUNT = 900
        const val SCALE_COPY_COUNT = 1_500
        const val MATCHED_COPIES_PER_NON_STEAM_SOURCE = 150
        const val PAGE_SIZE = 50
        const val FINAL_PAGE_INDEX = 17
        const val MAX_SOURCE_BATCH_CALLS = 5
        const val PRIVATE_TOKEN = "private-scale-token-qzx-314159"
        const val PRIVATE_URL = "https://private-scale.invalid/secret/library"
        const val PRIVATE_PATH_ROOT = "C:\\PrivateScalePath\\SecretLibrary"
        val PRIVATE_SCOPE = AccountScope.parse("f".repeat(64))
        val SOURCE_ORDER = listOf(
            GameSource.STEAM,
            GameSource.GOG,
            GameSource.EPIC,
            GameSource.AMAZON,
            GameSource.CUSTOM_GAME,
        )
        val NON_STEAM_SOURCES = SOURCE_ORDER.drop(1)
        val SAFE_DIAGNOSTIC_ATTRIBUTES = setOf(
            "source",
            "operation",
            "capability",
            "selection_policy",
            "reason",
            "result_count",
            "canonical_count",
            "copy_count",
            "error_type",
        )
        val STORE_FIELD = FeatureDiagnostics::class.java.getDeclaredField("store").apply {
            isAccessible = true
        }

        fun elapsedMs(startedAtNanos: Long): Long =
            ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)

        fun canonicalId(gameNumber: Int): String =
            "00000000-0000-0000-0000-${gameNumber.toString().padStart(12, '0')}"

        fun gameTitle(gameNumber: Int): String =
            "Private Scale Title ${gameNumber.toString().padStart(4, '0')} QZX"

        fun game(gameNumber: Int) = CanonicalGameEntity(
            canonicalId = canonicalId(gameNumber),
            steamAppId = steamId(gameNumber).toInt(),
            displayName = gameTitle(gameNumber),
            matchTitleKey = "private-scale-title-$gameNumber-qzx",
            primaryMetadataSource = GameSource.STEAM,
            appType = CanonicalAppType.GAME,
            releaseYear = 2026,
            developerKey = "private-scale-developer-qzx",
            classificationState = ClassificationState.CLASSIFIED,
            steamReviewCount = null,
            createdAt = 1L,
            updatedAt = 1L,
        )

        fun match(
            game: CanonicalGameEntity,
            source: GameSource,
            stableSourceId: String,
            confidence: MatchConfidence,
        ) = StoreMatchEntity(
            accountScope = PRIVATE_SCOPE.value,
            source = source,
            stableSourceId = stableSourceId,
            canonicalId = game.canonicalId,
            candidateSteamAppId = game.steamAppId,
            matchMethod = if (source == GameSource.STEAM) {
                MatchMethod.DIRECT_STEAM
            } else {
                MatchMethod.EXACT_METADATA
            },
            confidence = confidence,
            decisionSource = MatchDecisionSource.AUTOMATIC,
            resolverVersion = 1,
            matchedAt = 1L,
            isPresent = true,
            evidenceDisplayName = "${game.displayName} ${source.name}",
            evidenceTitleKey = "private-evidence-$source-${game.canonicalId}",
            evidenceDeveloperKey = "private-scale-developer-qzx",
            evidenceReleaseYear = 2026,
            evidenceAppType = CanonicalAppType.GAME,
        )

        fun available(key: OwnedCopyKey): OwnedCopyRuntimeResult {
            val reference = reference(key)
            val executableId = when (reference) {
                is SourceOwnedCopyReference.Steam -> "STEAM_${reference.appId}"
                is SourceOwnedCopyReference.Gog -> "GOG_${reference.gameId}"
                is SourceOwnedCopyReference.Epic -> "EPIC_${reference.localRowId}"
                is SourceOwnedCopyReference.Amazon -> "AMAZON_${reference.localRowId}"
                is SourceOwnedCopyReference.Custom -> "CUSTOM_GAME_${reference.appId}"
            }
            return OwnedCopyRuntimeResult.Available(
                OwnedCopyRuntime(
                    key = key,
                    reference = reference,
                    libraryItem = LibraryItem(
                        appId = executableId,
                        name = "Private Runtime Title ${key.source} ${key.stableSourceId}",
                        gameSource = key.source,
                    ),
                    nativeTitle = "Private Runtime Title ${key.source} ${key.stableSourceId}",
                    aliases = setOf(PRIVATE_TOKEN, PRIVATE_URL),
                    developerKey = "private-runtime-developer-qzx",
                    releaseYear = 2026,
                    appType = CanonicalAppType.GAME,
                    genreKeys = setOf("private-genre-qzx"),
                    tagIds = setOf(314159),
                    featureKeys = setOf("private-feature-qzx"),
                    iconUrl = "$PRIVATE_URL/icon/${key.stableSourceId}",
                    capsuleImageUrl = "$PRIVATE_URL/capsule/${key.stableSourceId}",
                    headerImageUrl = "$PRIVATE_URL/header/${key.stableSourceId}",
                    heroImageUrl = "$PRIVATE_URL/hero/${key.stableSourceId}",
                    gridHeroImageScale = 1f,
                    installPath = "$PRIVATE_PATH_ROOT\\${key.source}\\${key.stableSourceId}",
                    installedSizeBytes = 1_000L,
                    branchOrVersion = "private-version-qzx",
                    isInstalled = true,
                    isDownloading = false,
                    hasPartialDownload = false,
                    updateAvailable = false,
                    isShared = false,
                    lastPlayedEpochMs = 1L,
                    playtimeMinutes = 1L,
                    capabilities = setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS),
                ),
            )
        }

        fun reference(key: OwnedCopyKey): SourceOwnedCopyReference = when (key.source) {
            GameSource.STEAM -> SourceOwnedCopyReference.Steam(key, key.stableSourceId.toInt())
            GameSource.GOG -> SourceOwnedCopyReference.Gog(key, key.stableSourceId)
            GameSource.EPIC -> EpicStableSourceId.decode(key.stableSourceId).let { (namespace, catalogId) ->
                SourceOwnedCopyReference.Epic(
                    key,
                    localRowId = localRowId(key),
                    namespace = namespace,
                    catalogId = catalogId,
                )
            }
            GameSource.AMAZON -> SourceOwnedCopyReference.Amazon(
                key,
                localRowId = localRowId(key),
                productId = key.stableSourceId,
                entitlementId = "private-entitlement-${key.stableSourceId}",
            )
            GameSource.CUSTOM_GAME -> SourceOwnedCopyReference.Custom(key, key.stableSourceId.toInt())
        }

        fun localRowId(key: OwnedCopyKey): Int =
            (key.stableSourceId.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)

        fun nonSteamSource(gameNumber: Int): GameSource? = when (gameNumber) {
            in 1..150 -> GameSource.GOG
            in 151..300 -> GameSource.EPIC
            in 301..450 -> GameSource.AMAZON
            in 451..600 -> GameSource.CUSTOM_GAME
            else -> null
        }

        fun steamId(gameNumber: Int): String = (1_000_000 + gameNumber).toString()

        fun stableId(source: GameSource, gameNumber: Int): String = when (source) {
            GameSource.STEAM -> steamId(gameNumber)
            GameSource.GOG -> (2_000_000 + gameNumber).toString()
            GameSource.EPIC -> EpicStableSourceId.encode(
                namespace = "private-namespace-$gameNumber-qzx",
                catalogId = "private-catalog-$gameNumber-qzx",
            )
            GameSource.AMAZON -> "private-product-$gameNumber-qzx"
            GameSource.CUSTOM_GAME -> (3_000_000 + gameNumber).toString()
        }

        fun GameSource.libraryTab(): LibraryTab = when (this) {
            GameSource.STEAM -> LibraryTab.STEAM
            GameSource.GOG -> LibraryTab.GOG
            GameSource.EPIC -> LibraryTab.EPIC
            GameSource.AMAZON -> LibraryTab.AMAZON
            GameSource.CUSTOM_GAME -> LibraryTab.LOCAL
        }
    }
}
