package app.gamenative.library.canonical.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.data.AmazonGame
import app.gamenative.data.AppInfo
import app.gamenative.data.EpicGame
import app.gamenative.data.GOGGame
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryAssetsInfo
import app.gamenative.data.LibraryCapsuleInfo
import app.gamenative.data.LibraryHeroInfo
import app.gamenative.data.LibraryPlayHistory
import app.gamenative.data.SteamApp
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.OwnedCopyPresenceEntity
import app.gamenative.db.PluviaDatabase
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.CompletedOwnedCopySnapshot
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.db.dao.OwnedCopyLedgerDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.enums.AppType
import app.gamenative.enums.Language
import app.gamenative.library.canonical.AccountLifecycleState
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.CanonicalDiagnosticSink
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.source.AmazonOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.CustomOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.EpicOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.GogOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.library.canonical.source.SteamOwnedCopySourceAdapter
import app.gamenative.service.amazon.AmazonArtwork
import app.gamenative.utils.GameMetadataManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OwnedCopyRuntimeAdapterTest {
    private lateinit var context: Context
    private val scope = AccountScope.parse("a".repeat(64))
    private val otherScope = AccountScope.parse("b".repeat(64))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefManager.init(context)
    }

    @After
    fun tearDown() {
        PrefManager.customGameManualFolders = emptySet()
    }

    @Test
    fun steamPointAndBatchCarryExactIdentityMetadataArtworkStateAndCapabilities() = runTest {
        val key = key(GameSource.STEAM, "42")
        val app = SteamApp(
            id = 42,
            name = " Steam Native ",
            developer = "Studio, LLC",
            releaseDate = 1_704_067_200L,
            type = AppType.game,
            clientIconHash = "client-icon",
            libraryAssets = LibraryAssetsInfo(
                libraryCapsule = LibraryCapsuleInfo(image = mapOf(Language.english to "capsule.png")),
                libraryHero = LibraryHeroInfo(image = mapOf(Language.english to "hero.png")),
            ),
            genreIds = listOf(4, 2, 4, 0),
            storeTagIds = listOf(492, 19, 492, -1),
            categoryIds = listOf(22, 2, 0),
            ownerAccountId = listOf(7),
        )
        val dao = mockk<SteamAppDao>()
        val source = sourceAdapter<SteamOwnedCopySourceAdapter>(key, SourceOwnedCopyReference.Steam(key, 42))
        val history = historyDao("STEAM_42", 800L)
        val runtimeState = mockk<SteamOwnedCopyRuntimeState>()
        val currentState = state(
            installPath = "/private/steam/path",
            installedSizeBytes = 9_000L,
            branchOrVersion = "beta",
            isInstalled = true,
            isDownloading = false,
            hasPartialDownload = false,
            updateAvailable = true,
            isShared = true,
            playtimeMinutes = 321L,
        )
        coEvery { dao.findOwnedApp(42, any(), any()) } returns app
        coEvery { dao._getAllOwnedAppsPaged(any(), any()) } returns listOf(app)
        coEvery { runtimeState.readPoint(app, scope, 0L) } returns currentState
        coEvery { runtimeState.readBatch(listOf(app), scope, 0L) } returns mapOf(42 to currentState)
        val adapter = SteamOwnedCopyRuntimeAdapter(
            steamAppDao = dao,
            accountScopeProvider = scopes(GameSource.STEAM),
            accountLifecycleState = readyLifecycle(GameSource.STEAM),
            sourceAdapter = source,
            playHistoryDao = history,
            runtimeState = runtimeState,
        )

        val point = available(adapter.resolve(key))
        val batch = available(adapter.resolveAll(setOf(key)).getValue(key))

        assertEquals(point, batch)
        assertEquals(key, point.key)
        assertEquals(SourceOwnedCopyReference.Steam(key, 42), point.reference)
        assertEquals("STEAM_42", point.libraryItem?.appId)
        assertEquals(" Steam Native ", point.nativeTitle)
        assertEquals(emptySet<String>(), point.aliases)
        assertEquals("studio", point.developerKey)
        assertEquals(2024, point.releaseYear)
        assertEquals(CanonicalAppType.GAME, point.appType)
        assertEquals(setOf("steam:2", "steam:4"), point.genreKeys)
        assertEquals(setOf(19, 492), point.tagIds)
        assertEquals(setOf("steam:2", "steam:22"), point.featureKeys)
        assertEquals(app.clientIconUrl, point.iconUrl)
        assertEquals(app.getCapsuleUrl(), point.capsuleImageUrl)
        assertEquals(app.headerUrl, point.headerImageUrl)
        assertEquals(app.getHeroUrl(), point.heroImageUrl)
        assertEquals(1f, point.gridHeroImageScale)
        assertVolatileState(point, currentState, lastPlayed = 800L)
        assertEquals(
            setOf(
                OwnedCopyOperation.PLAY,
                OwnedCopyOperation.UPDATE,
                OwnedCopyOperation.UNINSTALL,
                OwnedCopyOperation.EXPORT_SAVES,
                OwnedCopyOperation.IMPORT_SAVES,
                OwnedCopyOperation.OPEN_SOURCE_DETAILS,
            ),
            point.capabilities,
        )
        coVerify(exactly = 1) { history.get("STEAM_42") }
        verify(exactly = 1) { history.getAll() }
        coVerify(exactly = 2) { dao._getAllOwnedAppsPaged(any(), any()) }
    }

    @Test
    fun gogPointAndBatchCarryProviderFacetsArtworkAndNoUpdateCapability() = runTest {
        val key = key(GameSource.GOG, "12345")
        val game = GOGGame(
            id = "12345",
            title = "GOG Native",
            developer = "GOG Studio Ltd.",
            releaseDate = "2023-04-01",
            type = AppType.application,
            genres = listOf("Role-Playing", "Action", "action"),
            iconUrl = "https://images/icon",
            imageUrl = "https://images/wide",
            verticalCoverUrl = "https://images/vertical",
            installPath = "/private/gog/path",
            installSize = 4_000L,
            isInstalled = true,
            lastPlayed = 700L,
            playTime = 91L,
        )
        val dao = mockk<GOGGameDao>()
        val ledger = ledger(GameSource.GOG, mapOf("12345" to null))
        val source = sourceAdapter<GogOwnedCopySourceAdapter>(key, SourceOwnedCopyReference.Gog(key, "12345"))
        val history = historyDao("GOG_12345", 999L)
        val runtimeState = mockk<GogOwnedCopyRuntimeState>()
        coEvery { dao.getById("12345") } returns game
        coEvery { dao.getAllAsList() } returns listOf(game)
        coEvery { runtimeState.read(listOf(game)) } returns mapOf(
            "12345" to state(
                installPath = game.installPath,
                installedSizeBytes = game.installSize,
                isInstalled = true,
                isDownloading = true,
                hasPartialDownload = true,
                playtimeMinutes = game.playTime,
            ),
        )
        val adapter = GogOwnedCopyRuntimeAdapter(
            gogGameDao = dao,
            accountScopeProvider = scopes(GameSource.GOG),
            ownedCopyLedgerDao = ledger,
            accountLifecycleState = readyLifecycle(GameSource.GOG),
            sourceAdapter = source,
            playHistoryDao = history,
            runtimeState = runtimeState,
        )

        val point = available(adapter.resolve(key))
        val batch = available(adapter.resolveAll(setOf(key)).getValue(key))

        assertEquals(point, batch)
        assertEquals(SourceOwnedCopyReference.Gog(key, "12345"), point.reference)
        assertEquals("GOG_12345", point.libraryItem?.appId)
        assertEquals("gog studio", point.developerKey)
        assertEquals(2023, point.releaseYear)
        assertEquals(CanonicalAppType.APPLICATION, point.appType)
        assertEquals(setOf("gog:action", "gog:role playing"), point.genreKeys)
        assertTrue(point.tagIds.isEmpty())
        assertTrue(point.featureKeys.isEmpty())
        assertEquals(game.iconUrl, point.iconUrl)
        assertEquals(game.verticalCoverUrl, point.capsuleImageUrl)
        assertEquals(game.imageUrl, point.headerImageUrl)
        assertEquals(game.imageUrl, point.heroImageUrl)
        assertEquals(999L, point.lastPlayedEpochMs)
        assertEquals(game.playTime, point.playtimeMinutes)
        assertEquals(
            setOf(
                OwnedCopyOperation.PLAY,
                OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD,
                OwnedCopyOperation.CANCEL_DOWNLOAD,
                OwnedCopyOperation.UNINSTALL,
                OwnedCopyOperation.OPEN_SOURCE_DETAILS,
            ),
            point.capabilities,
        )
        assertFalse(OwnedCopyOperation.UPDATE in point.capabilities)
        coVerify(exactly = 1) { history.get("GOG_12345") }
        verify(exactly = 1) { history.getAll() }
        coVerify(exactly = 2) { ledger.getCompletedSnapshotForLifecycle(scope.value, GameSource.GOG, 0L) }
        coVerify(exactly = 1) { dao.getAllAsList() }
    }

    @Test
    fun epicPointAndBatchKeepDurableProviderIdentityButBridgeCurrentLocalRow() = runTest {
        val stableId = EpicStableSourceId.encode("namespace", "catalog")
        val key = key(GameSource.EPIC, stableId)
        val game = EpicGame(
            id = 77,
            namespace = "namespace",
            catalogId = "catalog",
            appName = "launch-name",
            title = "Epic Native",
            developer = "Epic Studio, Inc.",
            releaseDate = "2022-10-01T00:00:00Z",
            type = AppType.tool,
            genres = listOf("Action RPG", "action-rpg"),
            tags = listOf("not-a-steam-id"),
            artCover = "https://epic/cover",
            artSquare = "https://epic/square",
            artPortrait = "https://epic/portrait",
            installPath = "/private/epic/path",
            installSize = 5_000L,
            version = "1.2.3",
            isInstalled = true,
            lastPlayed = 600L,
            playTime = 81L,
        )
        val dao = mockk<EpicGameDao>()
        val ledger = ledger(GameSource.EPIC, mapOf(stableId to null))
        val reference = SourceOwnedCopyReference.Epic(key, 77, "namespace", "catalog")
        val source = sourceAdapter<EpicOwnedCopySourceAdapter>(key, reference)
        val history = historyDao("EPIC_77", 999L)
        val runtimeState = mockk<EpicOwnedCopyRuntimeState>()
        coEvery { dao.getById(77) } returns game
        coEvery { dao.getByProviderIdentity("namespace", "catalog") } returns game
        coEvery { dao.getAllForCanonicalProjection() } returns listOf(game)
        coEvery { runtimeState.read(listOf(game)) } returns mapOf(
            77 to state(
                installPath = game.installPath,
                installedSizeBytes = game.installSize,
                branchOrVersion = game.version,
                isInstalled = true,
                playtimeMinutes = game.playTime,
            ),
        )
        val adapter = EpicOwnedCopyRuntimeAdapter(
            epicGameDao = dao,
            accountScopeProvider = scopes(GameSource.EPIC),
            ownedCopyLedgerDao = ledger,
            accountLifecycleState = readyLifecycle(GameSource.EPIC),
            sourceAdapter = source,
            playHistoryDao = history,
            runtimeState = runtimeState,
        )

        val point = available(adapter.resolve(key))
        val batch = available(adapter.resolveAll(setOf(key)).getValue(key))

        assertEquals(point, batch)
        assertEquals(reference, point.reference)
        assertEquals("EPIC_77", point.libraryItem?.appId)
        assertFalse(point.key.stableSourceId.contains("77"))
        assertEquals("epic studio", point.developerKey)
        assertEquals(2022, point.releaseYear)
        assertEquals(CanonicalAppType.TOOL, point.appType)
        assertEquals(setOf("epic:action rpg"), point.genreKeys)
        assertTrue(point.tagIds.isEmpty())
        assertTrue(point.featureKeys.isEmpty())
        assertEquals(game.artSquare, point.iconUrl)
        assertEquals(game.artCover, point.capsuleImageUrl)
        assertEquals(game.artPortrait, point.headerImageUrl)
        assertEquals(game.artPortrait, point.heroImageUrl)
        assertEquals(999L, point.lastPlayedEpochMs)
        assertEquals(game.playTime, point.playtimeMinutes)
        assertEquals(
            setOf(
                OwnedCopyOperation.PLAY,
                OwnedCopyOperation.UNINSTALL,
                OwnedCopyOperation.OPEN_SOURCE_DETAILS,
            ),
            point.capabilities,
        )
        assertFalse(OwnedCopyOperation.UPDATE in point.capabilities)
        coVerify(exactly = 1) { history.get("EPIC_77") }
        verify(exactly = 1) { history.getAll() }
        coVerify(exactly = 1) { dao.getAllForCanonicalProjection() }
    }

    @Test
    fun amazonPointAndBatchCaptureCurrentProductEntitlementAndArtwork() = runTest {
        val key = key(GameSource.AMAZON, "product-id")
        val productJson = JSONObject().put(
            "productDetail",
            JSONObject().put(
                "details",
                JSONObject()
                    .put("pgCrownImageUrl", "https://amazon/crown")
                    .put("backgroundUrl1", "https://amazon/background"),
            ),
        ).toString()
        val game = AmazonGame(
            appId = 88,
            productId = "product-id",
            entitlementId = "stale-row-entitlement",
            title = "Amazon Native",
            developer = "Amazon Studio GmbH",
            releaseDate = "2021-06-01",
            artUrl = "https://amazon/art",
            heroUrl = "https://amazon/row-hero",
            productJson = productJson,
            installPath = "/private/amazon/path",
            installSize = 6_000L,
            versionId = "version-id",
            isInstalled = true,
            lastPlayed = 500L,
            playTimeMinutes = 71L,
        )
        val dao = mockk<AmazonGameDao>()
        val ledger = ledger(GameSource.AMAZON, mapOf("product-id" to "current-entitlement"))
        val reference = SourceOwnedCopyReference.Amazon(
            key = key,
            localRowId = 88,
            productId = "product-id",
            entitlementId = "current-entitlement",
        )
        val source = sourceAdapter<AmazonOwnedCopySourceAdapter>(key, reference)
        val history = historyDao("AMAZON_88", 999L)
        val runtimeState = mockk<AmazonOwnedCopyRuntimeState>()
        coEvery { dao.getByAppId(88) } returns game
        coEvery { dao.getByProductId("product-id") } returns game
        coEvery { dao.getAllAsList() } returns listOf(game)
        val currentState = state(
            installPath = game.installPath,
            installedSizeBytes = game.installSize,
            branchOrVersion = game.versionId,
            isInstalled = true,
            updateAvailable = true,
            playtimeMinutes = game.playTimeMinutes,
        )
        coEvery { runtimeState.readPoint(game, scope, 0L) } returns currentState
        coEvery { runtimeState.readBatch(listOf(game), scope, 0L) } returns mapOf(88 to currentState)
        val adapter = AmazonOwnedCopyRuntimeAdapter(
            amazonGameDao = dao,
            accountScopeProvider = scopes(GameSource.AMAZON),
            ownedCopyLedgerDao = ledger,
            accountLifecycleState = readyLifecycle(GameSource.AMAZON),
            sourceAdapter = source,
            playHistoryDao = history,
            runtimeState = runtimeState,
        )

        val point = available(adapter.resolve(key))
        val batch = available(adapter.resolveAll(setOf(key)).getValue(key))

        assertEquals(point, batch)
        assertEquals(reference, point.reference)
        assertEquals("AMAZON_88", point.libraryItem?.appId)
        assertEquals("amazon studio", point.developerKey)
        assertEquals(2021, point.releaseYear)
        assertEquals(CanonicalAppType.GAME, point.appType)
        assertTrue(point.genreKeys.isEmpty())
        assertEquals(game.artUrl, point.iconUrl)
        assertEquals(game.artUrl, point.capsuleImageUrl)
        assertEquals(AmazonArtwork.layoutHeroFromProductJson(productJson), point.headerImageUrl)
        assertEquals(AmazonArtwork.layoutHeroFromProductJson(productJson), point.heroImageUrl)
        assertEquals(AmazonArtwork.GRID_HERO_ZOOM_SCALE, point.gridHeroImageScale)
        assertEquals(999L, point.lastPlayedEpochMs)
        assertEquals(game.playTimeMinutes, point.playtimeMinutes)
        assertEquals(
            setOf(
                OwnedCopyOperation.PLAY,
                OwnedCopyOperation.UPDATE,
                OwnedCopyOperation.UNINSTALL,
                OwnedCopyOperation.OPEN_SOURCE_DETAILS,
            ),
            point.capabilities,
        )
        coVerify(exactly = 1) { history.get("AMAZON_88") }
        verify(exactly = 1) { history.getAll() }
        coVerify(exactly = 1) { dao.getAllAsList() }
    }

    @Test
    fun customPointAndBatchUsePersistedIdScannerFolderImagesAndHistory() = runTest {
        val key = key(GameSource.CUSTOM_GAME, "99")
        val source = sourceAdapter<CustomOwnedCopySourceAdapter>(key, SourceOwnedCopyReference.Custom(key, 99))
        val history = historyDao("CUSTOM_GAME_99", 400L)
        val runtimeState = mockk<CustomOwnedCopyRuntimeState>()
        val row = CustomOwnedCopyRuntimeRow(
            appId = 99,
            nativeTitle = "Custom Native",
            installPath = "/private/custom/path",
            installedSizeBytes = 7_000L,
            iconUrl = "file:///private/custom/icon.ico",
            capsuleImageUrl = "file:///private/custom/coverv.png",
            headerImageUrl = "file:///private/custom/coverh.png",
            heroImageUrl = "file:///private/custom/coverh.png",
        )
        coEvery { runtimeState.read(setOf(99)) } returns mapOf(99 to row)
        val adapter = CustomOwnedCopyRuntimeAdapter(
            accountScopeProvider = scopes(GameSource.CUSTOM_GAME),
            sourceAdapter = source,
            playHistoryDao = history,
            runtimeState = runtimeState,
        )

        val point = available(adapter.resolve(key))
        val batch = available(adapter.resolveAll(setOf(key)).getValue(key))

        assertEquals(point, batch)
        assertEquals(SourceOwnedCopyReference.Custom(key, 99), point.reference)
        assertEquals("CUSTOM_GAME_99", point.libraryItem?.appId)
        assertEquals(CanonicalAppType.GAME, point.appType)
        assertEquals(row.iconUrl, point.iconUrl)
        assertEquals(row.capsuleImageUrl, point.capsuleImageUrl)
        assertEquals(row.headerImageUrl, point.headerImageUrl)
        assertEquals(row.heroImageUrl, point.heroImageUrl)
        assertEquals(row.installPath, point.installPath)
        assertEquals(row.installedSizeBytes, point.installedSizeBytes)
        assertTrue(point.isInstalled)
        assertEquals(400L, point.lastPlayedEpochMs)
        assertNull(point.playtimeMinutes)
        assertEquals(
            setOf(OwnedCopyOperation.PLAY, OwnedCopyOperation.OPEN_SOURCE_DETAILS),
            point.capabilities,
        )
        coVerify(exactly = 1) { history.get("CUSTOM_GAME_99") }
        verify(exactly = 1) { history.getAll() }
        coVerify(exactly = 4) { runtimeState.read(setOf(99)) }
    }

    @Test
    fun allPointAdaptersHideAccountMismatchBeforeSourceOrPrivateReads() = runTest {
        val steamDao = mockk<SteamAppDao>(relaxed = true)
        val gogDao = mockk<GOGGameDao>(relaxed = true)
        val epicDao = mockk<EpicGameDao>(relaxed = true)
        val amazonDao = mockk<AmazonGameDao>(relaxed = true)
        val ledger = mockk<OwnedCopyLedgerDao>(relaxed = true)
        val history = mockk<LibraryPlayHistoryDao>(relaxed = true)
        val adapters = listOf(
            SteamOwnedCopyRuntimeAdapter(
                steamDao,
                scopes(GameSource.STEAM, value = otherScope),
                readyLifecycle(GameSource.STEAM),
                mockk(relaxed = true),
                history,
                mockk(relaxed = true),
            ) to key(GameSource.STEAM, "1"),
            GogOwnedCopyRuntimeAdapter(
                gogDao,
                scopes(GameSource.GOG, value = otherScope),
                ledger,
                readyLifecycle(GameSource.GOG),
                mockk(relaxed = true),
                history,
                mockk(relaxed = true),
            ) to key(GameSource.GOG, "1"),
            EpicOwnedCopyRuntimeAdapter(
                epicDao,
                scopes(GameSource.EPIC, value = otherScope),
                ledger,
                readyLifecycle(GameSource.EPIC),
                mockk(relaxed = true),
                history,
                mockk(relaxed = true),
            ) to key(GameSource.EPIC, EpicStableSourceId.encode("ns", "catalog")),
            AmazonOwnedCopyRuntimeAdapter(
                amazonDao,
                scopes(GameSource.AMAZON, value = otherScope),
                ledger,
                readyLifecycle(GameSource.AMAZON),
                mockk(relaxed = true),
                history,
                mockk(relaxed = true),
            ) to key(GameSource.AMAZON, "product"),
            CustomOwnedCopyRuntimeAdapter(
                scopes(GameSource.CUSTOM_GAME, value = otherScope),
                mockk(relaxed = true),
                history,
                mockk(relaxed = true),
            ) to key(GameSource.CUSTOM_GAME, "5"),
        )

        adapters.forEach { (adapter, copyKey) ->
            assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolve(copyKey))
        }

        coVerify(exactly = 0) { steamDao.findOwnedApp(any(), any(), any()) }
        coVerify(exactly = 0) { gogDao.getById(any()) }
        coVerify(exactly = 0) { epicDao.getById(any()) }
        coVerify(exactly = 0) { amazonDao.getByAppId(any()) }
        coVerify(exactly = 0) { ledger.getCompletedSnapshotForLifecycle(any(), any(), any()) }
        coVerify(exactly = 0) { history.get(any()) }
    }

    @Test
    fun notReadyOrStaleLifecycleHidesBeforeOwnershipAndRowsInPointAndBatch() = runTest {
        val key = key(GameSource.GOG, "123")
        val dao = mockk<GOGGameDao>(relaxed = true)
        val ledger = mockk<OwnedCopyLedgerDao>(relaxed = true)
        val history = mockk<LibraryPlayHistoryDao>(relaxed = true)
        val lifecycle = MutableLifecycle(generation = 7L, readyGeneration = 6L)
        val adapter = GogOwnedCopyRuntimeAdapter(
            dao,
            scopes(GameSource.GOG),
            ledger,
            lifecycle,
            mockk(relaxed = true),
            history,
            mockk(relaxed = true),
        )

        assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolve(key))
        assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolveAll(setOf(key)).getValue(key))

        coVerify(exactly = 0) { ledger.getCompletedSnapshotForLifecycle(any(), any(), any()) }
        coVerify(exactly = 0) { ledger.isPresentForLifecycle(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.getById(any()) }
        coVerify(exactly = 0) { dao.getAllAsList() }
        verify(exactly = 0) { history.getAll() }
    }

    @Test
    fun entitlementLossIsHiddenWhileProvenMissingRowsAreTypedUnavailable() = runTest {
        val lostKey = key(GameSource.GOG, "lost")
        val changedKey = key(GameSource.GOG, "changed")
        val dao = mockk<GOGGameDao>()
        val ledger = ledger(GameSource.GOG, mapOf("changed" to null))
        val source = mockk<GogOwnedCopySourceAdapter>()
        every { source.invalidations() } returns emptyFlow()
        coEvery { source.resolve(changedKey) } returns SourceOwnedCopyReference.Gog(changedKey, "changed")
        val history = historyDao("GOG_changed", 1L)
        val state = mockk<GogOwnedCopyRuntimeState>(relaxed = true)
        coEvery { dao.getById("changed") } returns null
        coEvery { dao.getAllAsList() } returns emptyList()
        val adapter = GogOwnedCopyRuntimeAdapter(
            dao,
            scopes(GameSource.GOG),
            ledger,
            readyLifecycle(GameSource.GOG),
            source,
            history,
            state,
        )

        assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolve(lostKey))
        assertUnavailable(adapter.resolve(changedKey), changedKey, CopyUnavailableReason.SOURCE_ROW_CHANGED)
        val batch = adapter.resolveAll(setOf(lostKey, changedKey))
        assertSame(OwnedCopyRuntimeResult.Hidden, batch.getValue(lostKey))
        assertUnavailable(batch.getValue(changedKey), changedKey, CopyUnavailableReason.SOURCE_ROW_CHANGED)

        coVerify(exactly = 0) { source.resolve(lostKey) }
    }

    @Test
    fun epicExcludedContentIsHiddenInPointAndBatchEvenWithCurrentLedgerProof() = runTest {
        val excludedRows = listOf(
            EpicGame(id = 1, namespace = "games", catalogId = "dlc", isDLC = true),
            EpicGame(id = 2, namespace = "ue", catalogId = "marketplace"),
            EpicGame(
                id = 3,
                namespace = "89efe5924d3d467c839449ab6ab52e7f",
                catalogId = "engine",
            ),
        )
        for (game in excludedRows) {
            val stableId = EpicStableSourceId.encode(game.namespace, game.catalogId)
            val key = key(GameSource.EPIC, stableId)
            val dao = mockk<EpicGameDao>()
            val ledger = ledger(GameSource.EPIC, mapOf(stableId to null))
            val source = mockk<EpicOwnedCopySourceAdapter>()
            every { source.invalidations() } returns emptyFlow()
            coEvery { source.resolve(key) } returns null
            coEvery { dao.getByProviderIdentity(game.namespace, game.catalogId) } returns game
            coEvery { dao.getAllForCanonicalProjection() } returns listOf(game)
            val adapter = EpicOwnedCopyRuntimeAdapter(
                dao,
                scopes(GameSource.EPIC),
                ledger,
                readyLifecycle(GameSource.EPIC),
                source,
                historyDao("EPIC_${game.id}", 1L),
                mockk(relaxed = true),
            )

            assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolve(key))
            assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolveAll(setOf(key)).getValue(key))
        }
    }

    @Test
    fun removedCustomRowsAreHiddenInPointAndBatch() = runTest {
        val key = key(GameSource.CUSTOM_GAME, "99")
        val source = sourceAdapter<CustomOwnedCopySourceAdapter>(key, null)
        val state = mockk<CustomOwnedCopyRuntimeState>()
        coEvery { state.read(setOf(99)) } returns emptyMap()
        val adapter = CustomOwnedCopyRuntimeAdapter(
            scopes(GameSource.CUSTOM_GAME),
            source,
            historyDao("CUSTOM_GAME_99", 1L),
            state,
        )

        assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolve(key))
        assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolveAll(setOf(key)).getValue(key))
    }

    @Test
    fun unsupportedGogProviderIdsStayVisibleWithoutBridgeOrCapabilities() = runTest {
        val unsupportedIds = listOf("0", "-7", "+7", " 7", "7 ", "2147483648", "not-decimal")
        val keys = unsupportedIds.map { key(GameSource.GOG, it) }.toSet()
        val games = unsupportedIds.map { id ->
            GOGGame(
                id = id,
                title = "Visible $id",
                developer = "Studio",
                genres = listOf("Action"),
                iconUrl = "https://gog/icon/$id",
                verticalCoverUrl = "https://gog/cover/$id",
            )
        }
        val dao = mockk<GOGGameDao>()
        val ledger = ledger(GameSource.GOG, unsupportedIds.associateWith { null })
        val source = mockk<GogOwnedCopySourceAdapter>()
        every { source.invalidations() } returns emptyFlow()
        keys.forEach { copyKey ->
            coEvery { source.resolve(copyKey) } returns SourceOwnedCopyReference.Gog(copyKey, copyKey.stableSourceId)
            coEvery { dao.getById(copyKey.stableSourceId) } returns games.single { it.id == copyKey.stableSourceId }
        }
        coEvery { dao.getAllAsList() } returns games
        val runtimeState = mockk<GogOwnedCopyRuntimeState>()
        coEvery { runtimeState.read(any()) } answers {
            (invocation.args[0] as List<*>).map { it as GOGGame }.associate { game ->
                game.id to state(isInstalled = false)
            }
        }
        val history = mockk<LibraryPlayHistoryDao>()
        coEvery { history.get(any()) } returns null
        every { history.getAll() } returns flowOf(emptyList())
        val adapter = GogOwnedCopyRuntimeAdapter(
            dao,
            scopes(GameSource.GOG),
            ledger,
            readyLifecycle(GameSource.GOG),
            source,
            history,
            runtimeState,
        )

        val point = available(adapter.resolve(keys.first()))
        val batch = adapter.resolveAll(keys)

        (listOf(point) + batch.values.map(::available)).forEach { runtime ->
            assertNull(runtime.libraryItem)
            assertTrue(runtime.capabilities.isEmpty())
            assertTrue(runtime.nativeTitle.startsWith("Visible"))
            assertEquals(setOf("gog:action"), runtime.genreKeys)
            assertTrue(runtime.iconUrl.startsWith("https://gog/icon/"))
            assertTrue(runtime.capsuleImageUrl.startsWith("https://gog/cover/"))
        }
    }

    @Test
    fun runtimeNeverSwitchesToSiblingRowsWhenExactReferenceChanges() = runTest {
        val key = key(GameSource.AMAZON, "product-a")
        val sibling = AmazonGame(appId = 2, productId = "product-b", title = "Sibling")
        val dao = mockk<AmazonGameDao>()
        val ledger = ledger(GameSource.AMAZON, mapOf("product-a" to "entitlement-a"))
        val reference = SourceOwnedCopyReference.Amazon(key, 1, "product-a", "entitlement-a")
        val source = sourceAdapter<AmazonOwnedCopySourceAdapter>(key, reference)
        coEvery { dao.getByAppId(1) } returns sibling
        val adapter = AmazonOwnedCopyRuntimeAdapter(
            dao,
            scopes(GameSource.AMAZON),
            ledger,
            readyLifecycle(GameSource.AMAZON),
            source,
            historyDao("AMAZON_1", 1L),
            mockk(relaxed = true),
        )

        assertUnavailable(adapter.resolve(key), key, CopyUnavailableReason.SOURCE_ROW_CHANGED)
        coVerify(exactly = 0) { dao.getByProductId("product-b") }
    }

    @Test
    fun postReadLifecycleRaceHidesResultsAfterTheSingleSecondRecheck() = runTest {
        val key = key(GameSource.GOG, "123")
        val game = GOGGame(id = "123", title = "Old account title")
        val scopes = SequencedScopeProvider(listOf(scope, otherScope))
        val dao = mockk<GOGGameDao>()
        val ledger = ledger(GameSource.GOG, mapOf("123" to null))
        val source = sourceAdapter<GogOwnedCopySourceAdapter>(key, SourceOwnedCopyReference.Gog(key, "123"))
        val runtimeState = mockk<GogOwnedCopyRuntimeState>()
        coEvery { dao.getById("123") } returns game
        coEvery { runtimeState.read(listOf(game)) } returns mapOf("123" to state())
        val adapter = GogOwnedCopyRuntimeAdapter(
            dao,
            scopes,
            ledger,
            readyLifecycle(GameSource.GOG),
            source,
            historyDao("GOG_123", 1L),
            runtimeState,
        )

        assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolve(key))
        assertEquals(2, scopes.calls)
    }

    @Test
    fun secondScopeReadFailuresHideAccountBackedAndCustomResults() = runTest {
        val gogKey = key(GameSource.GOG, "123")
        val game = GOGGame(id = "123", title = "Current account title")
        val gogDao = mockk<GOGGameDao>()
        coEvery { gogDao.getById("123") } returns game
        coEvery { gogDao.getAllAsList() } returns listOf(game)
        val gogState = mockk<GogOwnedCopyRuntimeState>()
        coEvery { gogState.read(listOf(game)) } returns mapOf("123" to state())
        val gogSource = sourceAdapter<GogOwnedCopySourceAdapter>(
            gogKey,
            SourceOwnedCopyReference.Gog(gogKey, "123"),
        )
        fun gogAdapter() = GogOwnedCopyRuntimeAdapter(
            gogDao,
            SecondCallThrowingScopeProvider(scope, SensitiveFailure()),
            ledger(GameSource.GOG, mapOf("123" to null)),
            readyLifecycle(GameSource.GOG),
            gogSource,
            historyDao("GOG_123", 1L),
            gogState,
        )

        assertSame(OwnedCopyRuntimeResult.Hidden, gogAdapter().resolve(gogKey))
        assertSame(OwnedCopyRuntimeResult.Hidden, gogAdapter().resolveAll(setOf(gogKey)).getValue(gogKey))

        val customKey = key(GameSource.CUSTOM_GAME, "5")
        val customState = mockk<CustomOwnedCopyRuntimeState>()
        coEvery { customState.read(setOf(5)) } returns mapOf(5 to customRow(5))
        val customSource = sourceAdapter<CustomOwnedCopySourceAdapter>(
            customKey,
            SourceOwnedCopyReference.Custom(customKey, 5),
        )
        fun customAdapter() = CustomOwnedCopyRuntimeAdapter(
            SecondCallThrowingScopeProvider(scope, SensitiveFailure()),
            customSource,
            historyDao("CUSTOM_GAME_5", 1L),
            customState,
        )

        assertSame(OwnedCopyRuntimeResult.Hidden, customAdapter().resolve(customKey))
        assertSame(
            OwnedCopyRuntimeResult.Hidden,
            customAdapter().resolveAll(setOf(customKey)).getValue(customKey),
        )
    }

    @Test
    fun scopeFailuresHideButPostProofReadFailuresAreTypedWithoutPrivateMessages() = runTest {
        val key = key(GameSource.GOG, "123")
        val scopeFailure = GogOwnedCopyRuntimeAdapter(
            mockk(relaxed = true),
            ThrowingScopeProvider(SensitiveFailure()),
            mockk(relaxed = true),
            readyLifecycle(GameSource.GOG),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        assertSame(OwnedCopyRuntimeResult.Hidden, scopeFailure.resolve(key))

        val ledger = ledger(GameSource.GOG, mapOf("123" to null))
        val source = mockk<GogOwnedCopySourceAdapter>()
        every { source.invalidations() } returns emptyFlow()
        coEvery { source.resolve(key) } throws SensitiveFailure()
        val postProof = GogOwnedCopyRuntimeAdapter(
            mockk(relaxed = true),
            scopes(GameSource.GOG),
            ledger,
            readyLifecycle(GameSource.GOG),
            source,
            mockk(relaxed = true),
            mockk(relaxed = true),
        )

        val result = postProof.resolve(key)

        assertUnavailable(result, key, CopyUnavailableReason.SOURCE_READ_FAILED, SensitiveFailure::class)
        assertFalse(result.toString().contains(SENSITIVE_MESSAGE))
    }

    @Test
    fun customReadFailureAfterCurrentRowProofIsTypedWithoutPrivateMessages() = runTest {
        val key = key(GameSource.CUSTOM_GAME, "5")
        val source = sourceAdapter<CustomOwnedCopySourceAdapter>(
            key,
            SourceOwnedCopyReference.Custom(key, 5),
        )
        val runtimeState = mockk<CustomOwnedCopyRuntimeState>()
        coEvery { runtimeState.read(setOf(5)) } returns mapOf(5 to customRow(5))
        val history = mockk<LibraryPlayHistoryDao>()
        coEvery { history.get("CUSTOM_GAME_5") } throws SensitiveFailure()
        val adapter = CustomOwnedCopyRuntimeAdapter(
            scopes(GameSource.CUSTOM_GAME),
            source,
            history,
            runtimeState,
        )

        val result = adapter.resolve(key)

        assertUnavailable(result, key, CopyUnavailableReason.SOURCE_READ_FAILED, SensitiveFailure::class)
        assertFalse(result.toString().contains(SENSITIVE_MESSAGE))
    }

    @Test
    fun cancellationIsPreservedBeforeAndAfterOwnershipProof() = runTest {
        val key = key(GameSource.GOG, "123")
        val beforeProof = GogOwnedCopyRuntimeAdapter(
            mockk(relaxed = true),
            ThrowingScopeProvider(CancellationException("cancel-before")),
            mockk(relaxed = true),
            readyLifecycle(GameSource.GOG),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        assertSuspendThrows(CancellationException::class.java) { beforeProof.resolve(key) }

        val source = mockk<GogOwnedCopySourceAdapter>()
        every { source.invalidations() } returns emptyFlow()
        coEvery { source.resolve(key) } throws CancellationException("cancel-after")
        val afterProof = GogOwnedCopyRuntimeAdapter(
            mockk(relaxed = true),
            scopes(GameSource.GOG),
            ledger(GameSource.GOG, mapOf("123" to null)),
            readyLifecycle(GameSource.GOG),
            source,
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        assertSuspendThrows(CancellationException::class.java) { afterProof.resolve(key) }
    }

    @Test
    fun ledgerBackedBatchesHideEntitlementLossBeforeSourceReads() = runTest {
        val gogKey = key(GameSource.GOG, "1")
        val epicKey = key(GameSource.EPIC, EpicStableSourceId.encode("ns", "catalog"))
        val amazonKey = key(GameSource.AMAZON, "product")
        val gogDao = mockk<GOGGameDao>(relaxed = true)
        val epicDao = mockk<EpicGameDao>(relaxed = true)
        val amazonDao = mockk<AmazonGameDao>(relaxed = true)
        val history = mockk<LibraryPlayHistoryDao>(relaxed = true)
        val adapters = listOf(
            GogOwnedCopyRuntimeAdapter(
                gogDao,
                scopes(GameSource.GOG),
                ledger(GameSource.GOG, emptyMap()),
                readyLifecycle(GameSource.GOG),
                mockk(relaxed = true),
                history,
                mockk(relaxed = true),
            ) to gogKey,
            EpicOwnedCopyRuntimeAdapter(
                epicDao,
                scopes(GameSource.EPIC),
                ledger(GameSource.EPIC, emptyMap()),
                readyLifecycle(GameSource.EPIC),
                mockk(relaxed = true),
                history,
                mockk(relaxed = true),
            ) to epicKey,
            AmazonOwnedCopyRuntimeAdapter(
                amazonDao,
                scopes(GameSource.AMAZON),
                ledger(GameSource.AMAZON, emptyMap()),
                readyLifecycle(GameSource.AMAZON),
                mockk(relaxed = true),
                history,
                mockk(relaxed = true),
            ) to amazonKey,
        )

        adapters.forEach { (adapter, copyKey) ->
            assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolveAll(setOf(copyKey)).getValue(copyKey))
        }

        coVerify(exactly = 0) { gogDao.getAllAsList() }
        coVerify(exactly = 0) { epicDao.getAllForCanonicalProjection() }
        coVerify(exactly = 0) { amazonDao.getAllAsList() }
        verify(exactly = 0) { history.getAll() }
    }

    @Test
    fun everyBatchHidesWhenNoRequestedKeyBelongsToCurrentAccountBeforePrivateReads() = runTest {
        val steamDao = mockk<SteamAppDao>(relaxed = true)
        val gogDao = mockk<GOGGameDao>(relaxed = true)
        val epicDao = mockk<EpicGameDao>(relaxed = true)
        val amazonDao = mockk<AmazonGameDao>(relaxed = true)
        val ledger = mockk<OwnedCopyLedgerDao>(relaxed = true)
        val history = mockk<LibraryPlayHistoryDao>(relaxed = true)
        val customState = mockk<CustomOwnedCopyRuntimeState>(relaxed = true)
        val adapters = listOf(
            SteamOwnedCopyRuntimeAdapter(
                steamDao,
                scopes(GameSource.STEAM, value = otherScope),
                readyLifecycle(GameSource.STEAM),
                mockk(relaxed = true),
                history,
                mockk(relaxed = true),
            ) to key(GameSource.STEAM, "1"),
            GogOwnedCopyRuntimeAdapter(
                gogDao,
                scopes(GameSource.GOG, value = otherScope),
                ledger,
                readyLifecycle(GameSource.GOG),
                mockk(relaxed = true),
                history,
                mockk(relaxed = true),
            ) to key(GameSource.GOG, "1"),
            EpicOwnedCopyRuntimeAdapter(
                epicDao,
                scopes(GameSource.EPIC, value = otherScope),
                ledger,
                readyLifecycle(GameSource.EPIC),
                mockk(relaxed = true),
                history,
                mockk(relaxed = true),
            ) to key(GameSource.EPIC, EpicStableSourceId.encode("ns", "catalog")),
            AmazonOwnedCopyRuntimeAdapter(
                amazonDao,
                scopes(GameSource.AMAZON, value = otherScope),
                ledger,
                readyLifecycle(GameSource.AMAZON),
                mockk(relaxed = true),
                history,
                mockk(relaxed = true),
            ) to key(GameSource.AMAZON, "product"),
            CustomOwnedCopyRuntimeAdapter(
                scopes(GameSource.CUSTOM_GAME, value = otherScope),
                mockk(relaxed = true),
                history,
                customState,
            ) to key(GameSource.CUSTOM_GAME, "5"),
        )

        adapters.forEach { (adapter, copyKey) ->
            assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolveAll(setOf(copyKey)).getValue(copyKey))
        }

        coVerify(exactly = 0) { steamDao._getAllOwnedAppsPaged(any(), any()) }
        coVerify(exactly = 0) { gogDao.getAllAsList() }
        coVerify(exactly = 0) { epicDao.getAllForCanonicalProjection() }
        coVerify(exactly = 0) { amazonDao.getAllAsList() }
        coVerify(exactly = 0) { ledger.getCompletedSnapshotForLifecycle(any(), any(), any()) }
        coVerify(exactly = 0) { customState.read(any()) }
        verify(exactly = 0) { history.getAll() }
    }

    @Test
    fun steamAndEpicPointResolutionTypeProvenRowRacesWithoutSiblingFallback() = runTest {
        val steamKey = key(GameSource.STEAM, "42")
        val steamDao = mockk<SteamAppDao>()
        coEvery { steamDao.findOwnedApp(42, any(), any()) } returns null
        val steam = SteamOwnedCopyRuntimeAdapter(
            steamDao,
            scopes(GameSource.STEAM),
            readyLifecycle(GameSource.STEAM),
            sourceAdapter(steamKey, SourceOwnedCopyReference.Steam(steamKey, 42)),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        assertSame(OwnedCopyRuntimeResult.Hidden, steam.resolve(steamKey))
        coVerify(exactly = 2) { steamDao.findOwnedApp(42, any(), any()) }

        val stableId = EpicStableSourceId.encode("ns", "catalog")
        val epicKey = key(GameSource.EPIC, stableId)
        val epicDao = mockk<EpicGameDao>()
        coEvery { epicDao.getById(7) } returns null
        val epic = EpicOwnedCopyRuntimeAdapter(
            epicDao,
            scopes(GameSource.EPIC),
            ledger(GameSource.EPIC, mapOf(stableId to null)),
            readyLifecycle(GameSource.EPIC),
            sourceAdapter(
                epicKey,
                SourceOwnedCopyReference.Epic(epicKey, 7, "ns", "catalog"),
            ),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        assertUnavailable(
            epic.resolve(epicKey),
            epicKey,
            CopyUnavailableReason.SOURCE_ROW_CHANGED,
        )
    }

    @Test
    fun remainingAccountBackedEntitlementLossesAreHiddenBeforeRuntimeReads() = runTest {
        val steamKey = key(GameSource.STEAM, "42")
        val steamDao = mockk<SteamAppDao>(relaxed = true)
        val steam = SteamOwnedCopyRuntimeAdapter(
            steamDao,
            scopes(GameSource.STEAM),
            readyLifecycle(GameSource.STEAM),
            sourceAdapter(steamKey, null),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        assertSame(OwnedCopyRuntimeResult.Hidden, steam.resolve(steamKey))
        coVerify(exactly = 0) { steamDao.findOwnedApp(any(), any(), any()) }

        val epicStableId = EpicStableSourceId.encode("ns", "catalog")
        val epicKey = key(GameSource.EPIC, epicStableId)
        val epicDao = mockk<EpicGameDao>(relaxed = true)
        val epic = EpicOwnedCopyRuntimeAdapter(
            epicDao,
            scopes(GameSource.EPIC),
            ledger(GameSource.EPIC, emptyMap()),
            readyLifecycle(GameSource.EPIC),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        assertSame(OwnedCopyRuntimeResult.Hidden, epic.resolve(epicKey))
        coVerify(exactly = 0) { epicDao.getById(any()) }

        val amazonKey = key(GameSource.AMAZON, "product")
        val amazonDao = mockk<AmazonGameDao>(relaxed = true)
        val amazon = AmazonOwnedCopyRuntimeAdapter(
            amazonDao,
            scopes(GameSource.AMAZON),
            ledger(GameSource.AMAZON, emptyMap()),
            readyLifecycle(GameSource.AMAZON),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        assertSame(OwnedCopyRuntimeResult.Hidden, amazon.resolve(amazonKey))
        coVerify(exactly = 0) { amazonDao.getByAppId(any()) }
    }

    @Test
    fun batchFailureAfterSteamOwnershipProofIsTypedAndCancellationEscapes() = runTest {
        val key = key(GameSource.STEAM, "42")
        val app = SteamApp(id = 42, name = "Owned")
        val dao = mockk<SteamAppDao>()
        coEvery { dao._getAllOwnedAppsPaged(any(), any()) } returns listOf(app)
        val failedState = mockk<SteamOwnedCopyRuntimeState>()
        coEvery { failedState.readBatch(listOf(app), scope, 0L) } throws SensitiveFailure()
        val failed = SteamOwnedCopyRuntimeAdapter(
            dao,
            scopes(GameSource.STEAM),
            readyLifecycle(GameSource.STEAM),
            mockk(relaxed = true),
            batchHistory(),
            failedState,
        )

        assertUnavailable(
            failed.resolveAll(setOf(key)).getValue(key),
            key,
            CopyUnavailableReason.SOURCE_READ_FAILED,
            SensitiveFailure::class,
        )

        val cancelledState = mockk<SteamOwnedCopyRuntimeState>()
        coEvery { cancelledState.readBatch(listOf(app), scope, 0L) } throws CancellationException("stop")
        val cancelled = SteamOwnedCopyRuntimeAdapter(
            dao,
            scopes(GameSource.STEAM),
            readyLifecycle(GameSource.STEAM),
            mockk(relaxed = true),
            batchHistory(),
            cancelledState,
        )
        assertSuspendThrows(CancellationException::class.java) {
            cancelled.resolveAll(setOf(key))
        }
    }

    @Test
    fun everyBatchUsesOneImmutableSourceReadOwnershipSnapshotHistoryReadAndRecheck() = runTest {
        val steamKeys = setOf(key(GameSource.STEAM, "1"), key(GameSource.STEAM, "2"))
        val steamRows = listOf(SteamApp(id = 1, name = "One"), SteamApp(id = 2, name = "Two"))
        val steamDao = mockk<SteamAppDao>()
        coEvery { steamDao._getAllOwnedAppsPaged(any(), any()) } returns steamRows
        val steamHistory = batchHistory()
        val steamState = mockk<SteamOwnedCopyRuntimeState>()
        coEvery { steamState.readBatch(steamRows, scope, 0L) } returns steamRows.associate { it.id to state() }
        val steamScopes = CountingScopeProvider(scope)
        val steam = SteamOwnedCopyRuntimeAdapter(
            steamDao,
            steamScopes,
            readyLifecycle(GameSource.STEAM),
            mockk(relaxed = true),
            steamHistory,
            steamState,
        )
        assertEquals(steamKeys, steam.resolveAll(steamKeys).keys)
        coVerify(exactly = 2) { steamDao._getAllOwnedAppsPaged(any(), any()) }
        verify(exactly = 1) { steamHistory.getAll() }
        assertEquals(2, steamScopes.calls)

        val gogKeys = setOf(key(GameSource.GOG, "1"), key(GameSource.GOG, "2"))
        val gogRows = listOf(GOGGame(id = "1"), GOGGame(id = "2"))
        val gogDao = mockk<GOGGameDao>()
        coEvery { gogDao.getAllAsList() } returns gogRows
        val gogLedger = ledger(GameSource.GOG, mapOf("1" to null, "2" to null))
        val gogHistory = batchHistory()
        val gogState = mockk<GogOwnedCopyRuntimeState>()
        coEvery { gogState.read(gogRows) } returns gogRows.associate { it.id to state() }
        val gogScopes = CountingScopeProvider(scope)
        val gog = GogOwnedCopyRuntimeAdapter(
            gogDao,
            gogScopes,
            gogLedger,
            readyLifecycle(GameSource.GOG),
            mockk(relaxed = true),
            gogHistory,
            gogState,
        )
        assertEquals(gogKeys, gog.resolveAll(gogKeys).keys)
        coVerify(exactly = 1) { gogDao.getAllAsList() }
        coVerify(exactly = 2) { gogLedger.getCompletedSnapshotForLifecycle(scope.value, GameSource.GOG, 0L) }
        coVerify(exactly = 0) { gogLedger.isPresentForLifecycle(any(), any(), any(), any()) }
        verify(exactly = 1) { gogHistory.getAll() }
        assertEquals(2, gogScopes.calls)

        val epicRows = listOf(
            EpicGame(id = 1, namespace = "ns", catalogId = "1"),
            EpicGame(id = 2, namespace = "ns", catalogId = "2"),
        )
        val epicKeys = epicRows.map { key(GameSource.EPIC, EpicStableSourceId.encode(it.namespace, it.catalogId)) }.toSet()
        val epicDao = mockk<EpicGameDao>()
        coEvery { epicDao.getAllForCanonicalProjection() } returns epicRows
        val epicLedger = ledger(GameSource.EPIC, epicKeys.associate { it.stableSourceId to null })
        val epicHistory = batchHistory()
        val epicState = mockk<EpicOwnedCopyRuntimeState>()
        coEvery { epicState.read(epicRows) } returns epicRows.associate { it.id to state() }
        val epicScopes = CountingScopeProvider(scope)
        val epic = EpicOwnedCopyRuntimeAdapter(
            epicDao,
            epicScopes,
            epicLedger,
            readyLifecycle(GameSource.EPIC),
            mockk(relaxed = true),
            epicHistory,
            epicState,
        )
        assertEquals(epicKeys, epic.resolveAll(epicKeys).keys)
        coVerify(exactly = 1) { epicDao.getAllForCanonicalProjection() }
        coVerify(exactly = 2) { epicLedger.getCompletedSnapshotForLifecycle(scope.value, GameSource.EPIC, 0L) }
        verify(exactly = 1) { epicHistory.getAll() }
        assertEquals(2, epicScopes.calls)

        val amazonRows = listOf(
            AmazonGame(appId = 1, productId = "1"),
            AmazonGame(appId = 2, productId = "2"),
        )
        val amazonKeys = amazonRows.map { key(GameSource.AMAZON, it.productId) }.toSet()
        val amazonDao = mockk<AmazonGameDao>()
        coEvery { amazonDao.getAllAsList() } returns amazonRows
        val amazonLedger = ledger(GameSource.AMAZON, mapOf("1" to "entitlement-1", "2" to "entitlement-2"))
        val amazonHistory = batchHistory()
        val amazonState = mockk<AmazonOwnedCopyRuntimeState>()
        coEvery { amazonState.readBatch(amazonRows, scope, 0L) } returns amazonRows.associate { it.appId to state() }
        val amazonScopes = CountingScopeProvider(scope)
        val amazon = AmazonOwnedCopyRuntimeAdapter(
            amazonDao,
            amazonScopes,
            amazonLedger,
            readyLifecycle(GameSource.AMAZON),
            mockk(relaxed = true),
            amazonHistory,
            amazonState,
        )
        val amazonResults = amazon.resolveAll(amazonKeys)
        assertEquals(amazonKeys, amazonResults.keys)
        amazonResults.forEach { (copyKey, result) ->
            val runtime = available(result)
            assertEquals("entitlement-${copyKey.stableSourceId}", (runtime.reference as SourceOwnedCopyReference.Amazon).entitlementId)
        }
        coVerify(exactly = 1) { amazonDao.getAllAsList() }
        coVerify(exactly = 2) { amazonLedger.getCompletedSnapshotForLifecycle(scope.value, GameSource.AMAZON, 0L) }
        coVerify(exactly = 0) { amazonLedger.getPresenceForLifecycle(any(), any(), any(), any()) }
        verify(exactly = 1) { amazonHistory.getAll() }
        assertEquals(2, amazonScopes.calls)

        val customKeys = setOf(key(GameSource.CUSTOM_GAME, "1"), key(GameSource.CUSTOM_GAME, "2"))
        val customHistory = batchHistory()
        val customState = mockk<CustomOwnedCopyRuntimeState>()
        coEvery { customState.read(setOf(1, 2)) } returns mapOf(
            1 to customRow(1),
            2 to customRow(2),
        )
        val customScopes = CountingScopeProvider(scope)
        val custom = CustomOwnedCopyRuntimeAdapter(
            customScopes,
            mockk(relaxed = true),
            customHistory,
            customState,
        )
        assertEquals(customKeys, custom.resolveAll(customKeys).keys)
        coVerify(exactly = 2) { customState.read(setOf(1, 2)) }
        verify(exactly = 1) { customHistory.getAll() }
        assertEquals(2, customScopes.calls)
    }

    @Test
    fun pointHistoryAndResolvedOwnershipBatchQueriesPreserveExactIds() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.libraryPlayHistoryDao()
            dao.upsert(LibraryPlayHistory("STEAM_1", 11L))
            dao.upsert(LibraryPlayHistory("STEAM_10", 110L))

            assertEquals(11L, dao.get("STEAM_1")?.lastPlayed)
            assertNull(dao.get("STEAM_2"))
            assertEquals(
                mapOf("STEAM_1" to 11L, "STEAM_10" to 110L),
                dao.getAll().first().associate { it.appId to it.lastPlayed },
            )

            val ledgerDao = database.ownedCopyLedgerDao()
            ledgerDao.replaceCompletedSnapshot(
                accountScope = scope.value,
                source = GameSource.AMAZON,
                stableSourceIds = listOf("product"),
                completedAt = 1L,
                lifecycleGeneration = 4L,
                resolvedSourceIds = mapOf("product" to "entitlement"),
            )
            val ledger = ledgerDao.getCompletedSnapshotForLifecycle(
                accountScope = scope.value,
                source = GameSource.AMAZON,
                lifecycleGeneration = 4L,
            )
            assertEquals(listOf("product"), ledger?.stableSourceIds)
            assertEquals(mapOf("product" to "entitlement"), ledger?.resolvedSourceIds)
        } finally {
            database.close()
        }
    }

    @Test
    fun pointFailuresReproveCurrentAccountAndExactOwnershipBeforeUnavailable() = runTest {
        val steamKey = key(GameSource.STEAM, "42")
        val steamApp = SteamApp(id = 42, name = "Owned")
        val steamDao = mockk<SteamAppDao>()
        coEvery { steamDao.findOwnedApp(42, any(), any()) } returns steamApp
        val steamState = mockk<SteamOwnedCopyRuntimeState>()
        coEvery { steamState.readPoint(steamApp, scope, 0L) } throws SensitiveFailure()
        val switchedSteam = SteamOwnedCopyRuntimeAdapter(
            steamDao,
            SequencedScopeProvider(listOf(scope, otherScope)),
            readyLifecycle(GameSource.STEAM),
            sourceAdapter(steamKey, SourceOwnedCopyReference.Steam(steamKey, 42)),
            mockk(relaxed = true),
            steamState,
        )
        assertSame(OwnedCopyRuntimeResult.Hidden, switchedSteam.resolve(steamKey))

        val nestedKey = key(GameSource.GOG, "nested")
        val nestedSource = mockk<GogOwnedCopySourceAdapter>()
        every { nestedSource.invalidations() } returns emptyFlow()
        coEvery { nestedSource.resolve(nestedKey) } throws SensitiveFailure()
        val nestedFailureAfterSwitch = GogOwnedCopyRuntimeAdapter(
            mockk(relaxed = true),
            SequencedScopeProvider(listOf(scope, otherScope)),
            ledger(GameSource.GOG, mapOf("nested" to null)),
            readyLifecycle(GameSource.GOG),
            nestedSource,
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        assertSame(OwnedCopyRuntimeResult.Hidden, nestedFailureAfterSwitch.resolve(nestedKey))

        val gogKey = key(GameSource.GOG, "123")
        val gogDao = mockk<GOGGameDao>()
        coEvery { gogDao.getById("123") } returns null
        val gogLedger = mockk<OwnedCopyLedgerDao>()
        coEvery {
            gogLedger.isPresentForLifecycle(scope.value, GameSource.GOG, "123", 0L)
        } returnsMany listOf(true, false)
        val lostGog = GogOwnedCopyRuntimeAdapter(
            gogDao,
            scopes(GameSource.GOG),
            gogLedger,
            readyLifecycle(GameSource.GOG),
            sourceAdapter(gogKey, SourceOwnedCopyReference.Gog(gogKey, "123")),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        assertSame(OwnedCopyRuntimeResult.Hidden, lostGog.resolve(gogKey))
        coVerify(exactly = 2) {
            gogLedger.isPresentForLifecycle(scope.value, GameSource.GOG, "123", 0L)
        }
    }

    @Test
    fun amazonBlankResolvedEntitlementIsUnavailableOnlyWhilePresenceRemainsCurrent() = runTest {
        val key = key(GameSource.AMAZON, "product")
        val presence = OwnedCopyPresenceEntity(
            accountScope = scope.value,
            source = GameSource.AMAZON,
            stableSourceId = "product",
            resolvedSourceId = null,
        )
        val ledger = mockk<OwnedCopyLedgerDao>()
        coEvery {
            ledger.getPresenceForLifecycle(scope.value, GameSource.AMAZON, "product", 0L)
        } returns presence
        val adapter = AmazonOwnedCopyRuntimeAdapter(
            mockk(relaxed = true),
            scopes(GameSource.AMAZON),
            ledger,
            readyLifecycle(GameSource.AMAZON),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )

        assertUnavailable(
            adapter.resolve(key),
            key,
            CopyUnavailableReason.SOURCE_ROW_CHANGED,
        )
        coVerify(exactly = 2) {
            ledger.getPresenceForLifecycle(scope.value, GameSource.AMAZON, "product", 0L)
        }

        val removedLedger = mockk<OwnedCopyLedgerDao>()
        coEvery {
            removedLedger.getPresenceForLifecycle(scope.value, GameSource.AMAZON, "product", 0L)
        } returnsMany listOf(presence, null)
        val removed = AmazonOwnedCopyRuntimeAdapter(
            mockk(relaxed = true),
            scopes(GameSource.AMAZON),
            removedLedger,
            readyLifecycle(GameSource.AMAZON),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        assertSame(OwnedCopyRuntimeResult.Hidden, removed.resolve(key))

        val batchLedger = ledger(GameSource.AMAZON, mapOf("product" to null))
        val batchDao = mockk<AmazonGameDao>()
        coEvery { batchDao.getAllAsList() } returns emptyList()
        val batchState = mockk<AmazonOwnedCopyRuntimeState>()
        coEvery { batchState.readBatch(emptyList(), scope, 0L) } returns emptyMap()
        val batchAdapter = AmazonOwnedCopyRuntimeAdapter(
            batchDao,
            scopes(GameSource.AMAZON),
            batchLedger,
            readyLifecycle(GameSource.AMAZON),
            mockk(relaxed = true),
            batchHistory(),
            batchState,
        )
        assertUnavailable(
            batchAdapter.resolveAll(setOf(key)).getValue(key),
            key,
            CopyUnavailableReason.SOURCE_ROW_CHANGED,
        )
    }

    @Test
    fun batchFinalProofHidesSameGenerationLossAndAmazonEntitlementRotation() = runTest {
        val gogKey = key(GameSource.GOG, "123")
        val gogGame = GOGGame(id = "123", title = "GOG")
        val gogLedger = mockk<OwnedCopyLedgerDao>()
        coEvery {
            gogLedger.getCompletedSnapshotForLifecycle(scope.value, GameSource.GOG, 0L)
        } returnsMany listOf(
            completedSnapshot(mapOf("123" to null)),
            completedSnapshot(emptyMap()),
        )
        val gogDao = mockk<GOGGameDao>()
        coEvery { gogDao.getAllAsList() } returns listOf(gogGame)
        val gogState = mockk<GogOwnedCopyRuntimeState>()
        coEvery { gogState.read(listOf(gogGame)) } returns mapOf("123" to state())
        val gog = GogOwnedCopyRuntimeAdapter(
            gogDao,
            scopes(GameSource.GOG),
            gogLedger,
            readyLifecycle(GameSource.GOG),
            mockk(relaxed = true),
            batchHistory(),
            gogState,
        )
        assertSame(OwnedCopyRuntimeResult.Hidden, gog.resolveAll(setOf(gogKey)).getValue(gogKey))

        val amazonKey = key(GameSource.AMAZON, "product")
        val amazonGame = AmazonGame(appId = 7, productId = "product", title = "Amazon")
        val amazonLedger = mockk<OwnedCopyLedgerDao>()
        coEvery {
            amazonLedger.getCompletedSnapshotForLifecycle(scope.value, GameSource.AMAZON, 0L)
        } returnsMany listOf(
            completedSnapshot(mapOf("product" to "entitlement-a")),
            completedSnapshot(mapOf("product" to "entitlement-b")),
        )
        val amazonDao = mockk<AmazonGameDao>()
        coEvery { amazonDao.getAllAsList() } returns listOf(amazonGame)
        val amazonState = mockk<AmazonOwnedCopyRuntimeState>()
        coEvery { amazonState.readBatch(listOf(amazonGame), scope, 0L) } returns mapOf(7 to state())
        val amazon = AmazonOwnedCopyRuntimeAdapter(
            amazonDao,
            scopes(GameSource.AMAZON),
            amazonLedger,
            readyLifecycle(GameSource.AMAZON),
            mockk(relaxed = true),
            batchHistory(),
            amazonState,
        )
        assertSame(
            OwnedCopyRuntimeResult.Hidden,
            amazon.resolveAll(setOf(amazonKey)).getValue(amazonKey),
        )
    }

    @Test
    fun customFinalProofHidesPersistedRowRemoval() = runTest {
        val key = key(GameSource.CUSTOM_GAME, "5")
        val runtimeState = mockk<CustomOwnedCopyRuntimeState>()
        coEvery { runtimeState.read(setOf(5)) } returnsMany listOf(
            mapOf(5 to customRow(5)),
            emptyMap(),
        )
        val adapter = CustomOwnedCopyRuntimeAdapter(
            scopes(GameSource.CUSTOM_GAME),
            mockk(relaxed = true),
            batchHistory(),
            runtimeState,
        )

        assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolveAll(setOf(key)).getValue(key))
        coVerify(exactly = 2) { runtimeState.read(setOf(5)) }
    }

    @Test
    fun providerAndLocalHistoryUseNewestPositiveTimestampInPointAndBatch() = runTest {
        val key = key(GameSource.GOG, "123")
        val game = GOGGame(id = "123", title = "GOG", lastPlayed = 50L)
        val dao = mockk<GOGGameDao>()
        coEvery { dao.getById("123") } returns game
        coEvery { dao.getAllAsList() } returns listOf(game)
        val runtimeState = mockk<GogOwnedCopyRuntimeState>()
        coEvery { runtimeState.read(listOf(game)) } returns mapOf("123" to state())
        val adapter = GogOwnedCopyRuntimeAdapter(
            dao,
            scopes(GameSource.GOG),
            ledger(GameSource.GOG, mapOf("123" to null)),
            readyLifecycle(GameSource.GOG),
            sourceAdapter(key, SourceOwnedCopyReference.Gog(key, "123")),
            historyDao("GOG_123", 500L),
            runtimeState,
        )

        assertEquals(500L, available(adapter.resolve(key)).lastPlayedEpochMs)
        assertEquals(500L, available(adapter.resolveAll(setOf(key)).getValue(key)).lastPlayedEpochMs)
    }

    @Test
    fun registryRejectsWrongEmbeddedResultKeysAndReferences() = runTest {
        val requested = key(GameSource.STEAM, "1")
        val wrong = key(GameSource.STEAM, "2")
        val history = mockk<LibraryPlayHistoryDao>()
        every { history.getAll() } returns emptyFlow()

        fun registryFor(adapter: OwnedCopyRuntimeAdapter): OwnedCopyRuntimeRegistry {
            val adapters = GameSource.entries.map { source ->
                if (source == GameSource.STEAM) adapter else fakeAdapter(source)
            }.toSet()
            return OwnedCopyRuntimeRegistry(adapters, history, mockk(relaxed = true))
        }

        val wrongPoint = fakeAdapter(
            source = GameSource.STEAM,
            resolve = { runtimeResult(wrong, wrong) },
        )
        assertSuspendThrows(IllegalStateException::class.java) {
            registryFor(wrongPoint).resolve(requested)
        }

        val wrongCopy = fakeAdapter(GameSource.STEAM) { keys ->
            keys.associateWith { runtimeResult(wrong, wrong) }
        }
        assertSuspendThrows(IllegalStateException::class.java) {
            registryFor(wrongCopy).resolveAll(GameSource.STEAM, setOf(requested))
        }

        val wrongReference = fakeAdapter(GameSource.STEAM) { keys ->
            keys.associateWith { runtimeResult(requested, wrong) }
        }
        assertSuspendThrows(IllegalStateException::class.java) {
            registryFor(wrongReference).resolveAll(GameSource.STEAM, setOf(requested))
        }

        val wrongUnavailable = fakeAdapter(GameSource.STEAM) { keys ->
            keys.associateWith {
                OwnedCopyRuntimeResult.Unavailable(wrong, CopyUnavailableReason.SOURCE_ROW_CHANGED)
            }
        }
        assertSuspendThrows(IllegalStateException::class.java) {
            registryFor(wrongUnavailable).resolveAll(GameSource.STEAM, setOf(requested))
        }
    }

    @Test
    fun supportedUninstalledAndMarkerOnlyPartialStatesExposeRequiredCapabilities() {
        assertTrue(
            OwnedCopyOperation.INSTALL in capabilities(
                GameSource.GOG,
                libraryItemPresent = true,
                state(),
            ),
        )
        val partial = capabilities(
            GameSource.AMAZON,
            libraryItemPresent = true,
            state(hasPartialDownload = true),
        )
        assertTrue(OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD in partial)
        assertTrue(OwnedCopyOperation.CANCEL_DOWNLOAD in partial)
        assertTrue(OwnedCopyOperation.UNINSTALL in partial)
    }

    @Test
    fun fatalErrorsPropagateThroughRuntimeAndInvalidationBoundaries() = runTest {
        val key = key(GameSource.GOG, "123")
        val source = mockk<GogOwnedCopySourceAdapter>()
        every { source.invalidations() } returns emptyFlow()
        coEvery { source.resolve(key) } throws LinkageError("fatal")
        val adapter = GogOwnedCopyRuntimeAdapter(
            mockk(relaxed = true),
            scopes(GameSource.GOG),
            ledger(GameSource.GOG, mapOf("123" to null)),
            readyLifecycle(GameSource.GOG),
            source,
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        assertSuspendThrows(LinkageError::class.java) { adapter.resolve(key) }

        val fatalAdapter = object : OwnedCopyRuntimeAdapter {
            override val source = GameSource.STEAM
            override fun invalidations(): Flow<Unit> = throw OutOfMemoryError("fatal")
            override suspend fun resolve(key: OwnedCopyKey) = OwnedCopyRuntimeResult.Hidden
            override suspend fun resolveAll(keys: Set<OwnedCopyKey>) = keys.hiddenResults()
        }
        val adapters = GameSource.entries.map { sourceType ->
            if (sourceType == GameSource.STEAM) fatalAdapter else fakeAdapter(sourceType)
        }.toSet()
        val history = mockk<LibraryPlayHistoryDao>()
        every { history.getAll() } returns emptyFlow()
        val registry = OwnedCopyRuntimeRegistry(adapters, history, mockk(relaxed = true))
        assertSuspendThrows(OutOfMemoryError::class.java) { registry.invalidations().first() }
        assertThrows(OutOfMemoryError::class.java) {
            GameMetadataManager.parseAppIdReadOnly { throw OutOfMemoryError("fatal") }
        }
    }

    @Test
    fun registryRequiresExactlyOneAdapterPerSource() {
        val history = mockk<LibraryPlayHistoryDao>(relaxed = true)
        val diagnostics = mockk<CanonicalDiagnosticSink>(relaxed = true)
        val complete = GameSource.entries.map(::fakeAdapter)

        OwnedCopyRuntimeRegistry(complete.toSet(), history, diagnostics)

        assertThrows(IllegalStateException::class.java) {
            OwnedCopyRuntimeRegistry((complete + fakeAdapter(GameSource.STEAM)).toSet(), history, diagnostics)
        }
        assertThrows(IllegalStateException::class.java) {
            OwnedCopyRuntimeRegistry(complete.dropLast(1).toSet(), history, diagnostics)
        }
    }

    @Test
    fun registryRejectsMixedSourcesAndAnyReturnedKeySetDifference() = runTest {
        val steamKey = key(GameSource.STEAM, "1")
        val gogKey = key(GameSource.GOG, "2")
        val steam = fakeAdapter(GameSource.STEAM) { keys ->
            if (keys == setOf(steamKey)) emptyMap() else keys.associateWith { OwnedCopyRuntimeResult.Hidden }
        }
        val adapters = GameSource.entries.associateWith { source ->
            if (source == GameSource.STEAM) steam else fakeAdapter(source)
        }.values.toSet()
        val registry = OwnedCopyRuntimeRegistry(
            adapters,
            mockk(relaxed = true),
            mockk(relaxed = true),
        )

        assertSuspendThrows(IllegalArgumentException::class.java) {
            registry.resolveAll(GameSource.STEAM, setOf(steamKey, gogKey))
        }
        assertSuspendThrows(IllegalStateException::class.java) {
            registry.resolveAll(GameSource.STEAM, setOf(steamKey))
        }
    }

    @Test
    fun registryInvalidatesForHistoryManualAndSourceTriggers() = runTest {
        val historyEvents = MutableSharedFlow<List<LibraryPlayHistory>>(extraBufferCapacity = 1)
        val history = mockk<LibraryPlayHistoryDao>()
        every { history.getAll() } returns historyEvents
        val sourceEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val adapters = GameSource.entries.associateWith { source ->
            if (source == GameSource.GOG) {
                fakeAdapter(source, invalidations = sourceEvents)
            } else {
                fakeAdapter(source)
            }
        }.values.toSet()
        val registry = OwnedCopyRuntimeRegistry(adapters, history, mockk(relaxed = true))

        val historyAwait = async(start = CoroutineStart.UNDISPATCHED) { registry.invalidations().first() }
        runCurrent()
        historyEvents.emit(emptyList())
        assertEquals(Unit, historyAwait.await())

        val manualAwait = async(start = CoroutineStart.UNDISPATCHED) { registry.invalidations().first() }
        runCurrent()
        registry.notifyVolatileStateChanged(GameSource.AMAZON)
        assertEquals(Unit, manualAwait.await())

        val sourceAwait = async(start = CoroutineStart.UNDISPATCHED) { registry.invalidations().first() }
        runCurrent()
        sourceEvents.emit(Unit)
        assertEquals(Unit, sourceAwait.await())
    }

    @Test
    fun registryRestartsFailedSourceInvalidationAfterBoundedDelay() = runTest {
        val attempts = AtomicInteger()
        val retrying = fakeAdapter(
            source = GameSource.STEAM,
            invalidations = flow {
                if (attempts.getAndIncrement() == 0) throw SensitiveFailure()
                emit(Unit)
                awaitCancellation()
            },
        )
        val adapters = GameSource.entries.associateWith { source ->
            if (source == GameSource.STEAM) retrying else fakeAdapter(source)
        }.values.toSet()
        val history = mockk<LibraryPlayHistoryDao>()
        every { history.getAll() } returns emptyFlow()
        val diagnostics = mockk<CanonicalDiagnosticSink>(relaxed = true)
        val registry = OwnedCopyRuntimeRegistry(adapters, history, diagnostics)

        val retryAwait = async(start = CoroutineStart.UNDISPATCHED) { registry.invalidations().first() }
        runCurrent()
        assertEquals(1, attempts.get())
        advanceTimeBy(999L)
        runCurrent()
        assertFalse(retryAwait.isCompleted)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(Unit, retryAwait.await())
        assertEquals(2, attempts.get())
        verify(exactly = 1) {
            diagnostics.invalidationFailed(GameSource.STEAM, SensitiveFailure::class)
        }
    }

    @Test
    fun registryRetriesSynchronousSourceAndHistoryFactoriesWhileManualEventsContinue() = runTest {
        val sourceAttempts = AtomicInteger()
        val sourceEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val retryingAdapter = object : OwnedCopyRuntimeAdapter {
            override val source = GameSource.STEAM
            override fun invalidations(): Flow<Unit> {
                if (sourceAttempts.getAndIncrement() == 0) throw SensitiveFailure()
                return sourceEvents
            }
            override suspend fun resolve(key: OwnedCopyKey) = OwnedCopyRuntimeResult.Hidden
            override suspend fun resolveAll(keys: Set<OwnedCopyKey>) = keys.hiddenResults()
        }
        val adapters = GameSource.entries.map { source ->
            if (source == GameSource.STEAM) retryingAdapter else fakeAdapter(source)
        }.toSet()
        val historyAttempts = AtomicInteger()
        val historyEvents = MutableSharedFlow<List<LibraryPlayHistory>>(extraBufferCapacity = 1)
        val history = mockk<LibraryPlayHistoryDao>()
        every { history.getAll() } answers {
            if (historyAttempts.getAndIncrement() == 0) throw SensitiveFailure()
            historyEvents
        }
        val diagnostics = mockk<CanonicalDiagnosticSink>(relaxed = true)
        val registry = OwnedCopyRuntimeRegistry(adapters, history, diagnostics)
        val events = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            registry.invalidations().take(3).toList()
        }
        runCurrent()

        registry.notifyVolatileStateChanged(GameSource.AMAZON)
        runCurrent()
        assertFalse(events.isCompleted)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, sourceAttempts.get())
        assertEquals(2, historyAttempts.get())
        sourceEvents.emit(Unit)
        historyEvents.emit(emptyList())

        assertEquals(3, events.await().size)
        verify(exactly = 1) {
            diagnostics.invalidationFailed(GameSource.STEAM, SensitiveFailure::class)
        }
    }

    @Test
    fun registryInvalidationPreservesCollectorCancellationWithoutRetry() = runTest {
        val attempts = AtomicInteger()
        val cancellable = fakeAdapter(
            source = GameSource.STEAM,
            invalidations = flow {
                attempts.incrementAndGet()
                awaitCancellation()
            },
        )
        val adapters = GameSource.entries.associateWith { source ->
            if (source == GameSource.STEAM) cancellable else fakeAdapter(source)
        }.values.toSet()
        val history = mockk<LibraryPlayHistoryDao>()
        every { history.getAll() } returns emptyFlow()
        val diagnostics = mockk<CanonicalDiagnosticSink>(relaxed = true)
        val registry = OwnedCopyRuntimeRegistry(adapters, history, diagnostics)

        val collector = backgroundScope.launch { registry.invalidations().collect {} }
        runCurrent()
        collector.cancel(CancellationException("stop"))
        collector.join()

        assertTrue(collector.isCancelled)
        assertEquals(1, attempts.get())
        verify(exactly = 0) { diagnostics.invalidationFailed(any(), any()) }
    }

    @Test
    fun steamBatchStateSnapshotsEachProductionFacetOnceWithoutLiveUpdateProbes() = runTest {
        val apps = listOf(
            SteamApp(id = 1, name = "Installed"),
            SteamApp(id = 2, name = "Partial"),
        )
        val installed = AppInfo(id = 1, isDownloaded = true, branch = "beta")
        val gateway = mockk<SteamOwnedCopyRuntimeGateway>()
        coEvery { gateway.activeDownloadIds() } returns setOf(2)
        coEvery { gateway.partialDownloadIds() } returns setOf(2)
        coEvery { gateway.installedApps() } returns mapOf(1 to installed)
        coEvery { gateway.licensedDepotIds(apps) } returns emptyMap()
        coEvery {
            gateway.installPaths(apps, mapOf(1 to installed), setOf(2))
        } returns mapOf(1 to "/installed", 2 to "/partial")
        val runtimeState = SteamOwnedCopyRuntimeState(gateway)

        val states = runtimeState.readBatch(apps, scope, 0L)

        assertTrue(states.getValue(1).isInstalled)
        assertEquals("beta", states.getValue(1).branchOrVersion)
        assertTrue(states.getValue(2).isDownloading)
        assertTrue(states.getValue(2).hasPartialDownload)
        assertFalse(states.getValue(1).updateAvailable)
        coVerify(exactly = 1) { gateway.activeDownloadIds() }
        coVerify(exactly = 1) { gateway.partialDownloadIds() }
        coVerify(exactly = 1) { gateway.installedApps() }
        coVerify(exactly = 1) { gateway.licensedDepotIds(apps) }
        coVerify(exactly = 1) { gateway.installPaths(apps, mapOf(1 to installed), setOf(2)) }
        coVerify(exactly = 0) { gateway.updatePending(any(), any()) }
    }

    @Test
    fun steamPointStateSeedsTheCachedBatchUpdateSnapshot() = runTest {
        val app = SteamApp(id = 1, name = "Installed")
        val installed = AppInfo(id = 1, isDownloaded = true)
        val gateway = mockk<SteamOwnedCopyRuntimeGateway>()
        coEvery { gateway.activeDownloadIds() } returns emptySet()
        coEvery { gateway.partialDownloadIds() } returns emptySet()
        coEvery { gateway.installedApps() } returns mapOf(1 to installed)
        coEvery { gateway.licensedDepotIds(listOf(app)) } returns emptyMap()
        coEvery {
            gateway.installPaths(listOf(app), mapOf(1 to installed), emptySet())
        } returns mapOf(1 to "/installed")
        coEvery { gateway.updatePending(1, "public") } returns true
        val runtimeState = SteamOwnedCopyRuntimeState(gateway)

        assertTrue(runtimeState.readPoint(app, scope, 0L).updateAvailable)
        assertTrue(runtimeState.readBatch(listOf(app), scope, 0L).getValue(1).updateAvailable)
        assertFalse(runtimeState.readBatch(listOf(app), otherScope, 0L).getValue(1).updateAvailable)

        coVerify(exactly = 1) { gateway.updatePending(1, "public") }
    }

    @Test
    fun amazonBatchStateSnapshotsDownloadsOnceWithoutLiveUpdateProbes() = runTest {
        val games = listOf(
            AmazonGame(appId = 1, productId = "installed", isInstalled = true),
            AmazonGame(appId = 2, productId = "partial"),
        )
        val gateway = mockk<AmazonOwnedCopyRuntimeGateway>()
        coEvery { gateway.activeDownloadProductIds() } returns setOf("partial")
        coEvery { gateway.partialDownloadProductIds() } returns setOf("partial")
        val runtimeState = AmazonOwnedCopyRuntimeState(gateway)

        val states = runtimeState.readBatch(games, scope, 0L)

        assertTrue(states.getValue(1).isInstalled)
        assertTrue(states.getValue(2).isDownloading)
        assertTrue(states.getValue(2).hasPartialDownload)
        assertFalse(states.getValue(1).updateAvailable)
        coVerify(exactly = 1) { gateway.activeDownloadProductIds() }
        coVerify(exactly = 1) { gateway.partialDownloadProductIds() }
        coVerify(exactly = 0) { gateway.updatePending(any()) }
    }

    @Test
    fun amazonPointStateSeedsTheCachedBatchUpdateSnapshot() = runTest {
        val game = AmazonGame(appId = 1, productId = "product", isInstalled = true)
        val gateway = mockk<AmazonOwnedCopyRuntimeGateway>()
        coEvery { gateway.activeDownloadProductIds() } returns emptySet()
        coEvery { gateway.partialDownloadProductIds() } returns emptySet()
        coEvery { gateway.updatePending("product") } returns true
        val runtimeState = AmazonOwnedCopyRuntimeState(gateway)

        assertTrue(runtimeState.readPoint(game, scope, 0L).updateAvailable)
        assertTrue(runtimeState.readBatch(listOf(game), scope, 0L).getValue(1).updateAvailable)
        assertFalse(runtimeState.readBatch(listOf(game), otherScope, 0L).getValue(1).updateAvailable)

        coVerify(exactly = 1) { gateway.updatePending("product") }
    }

    @Test
    fun oneMissingBatchStateDoesNotDisableSiblingRuntimeCopies() = runTest {
        val keys = setOf(key(GameSource.STEAM, "1"), key(GameSource.STEAM, "2"))
        val rows = listOf(SteamApp(id = 1, name = "Available"), SteamApp(id = 2, name = "Failed"))
        val dao = mockk<SteamAppDao>()
        coEvery { dao._getAllOwnedAppsPaged(any(), any()) } returns rows
        val runtimeState = mockk<SteamOwnedCopyRuntimeState>()
        coEvery { runtimeState.readBatch(rows, scope, 0L) } returns mapOf(1 to state())
        val adapter = SteamOwnedCopyRuntimeAdapter(
            dao,
            scopes(GameSource.STEAM),
            readyLifecycle(GameSource.STEAM),
            mockk(relaxed = true),
            batchHistory(),
            runtimeState,
        )

        val results = adapter.resolveAll(keys)

        available(results.getValue(key(GameSource.STEAM, "1")))
        assertUnavailable(
            results.getValue(key(GameSource.STEAM, "2")),
            key(GameSource.STEAM, "2"),
            CopyUnavailableReason.SOURCE_ROW_CHANGED,
        )
    }

    @Test
    fun epicAndAmazonPointAndBatchShareDeterministicDuplicateRowPrecedence() = runTest {
        val epicStableId = EpicStableSourceId.encode("namespace", "catalog")
        val epicKey = key(GameSource.EPIC, epicStableId)
        val epicRows = listOf(
            EpicGame(id = 1, namespace = "namespace", catalogId = "catalog", isInstalled = false),
            EpicGame(id = 7, namespace = "namespace", catalogId = "catalog", isInstalled = true),
            EpicGame(id = 5, namespace = "namespace", catalogId = "catalog", isInstalled = true),
        )
        val preferredEpic = epicRows.last()
        val epicDao = mockk<EpicGameDao>()
        coEvery { epicDao.getByProviderIdentity("namespace", "catalog") } returns preferredEpic
        coEvery { epicDao.getById(5) } returns preferredEpic
        coEvery { epicDao.getAllForCanonicalProjection() } returns epicRows
        every { epicDao.getAll() } returns emptyFlow()
        val epicLedger = ledger(GameSource.EPIC, mapOf(epicStableId to null))
        val epicSource = EpicOwnedCopySourceAdapter(
            epicDao,
            scopes(GameSource.EPIC),
            epicLedger,
            readyLifecycle(GameSource.EPIC),
        )
        val epicState = mockk<EpicOwnedCopyRuntimeState>()
        coEvery { epicState.read(listOf(preferredEpic)) } returns mapOf(5 to state())
        val epicAdapter = EpicOwnedCopyRuntimeAdapter(
            epicDao,
            scopes(GameSource.EPIC),
            epicLedger,
            readyLifecycle(GameSource.EPIC),
            epicSource,
            historyDao("EPIC_5", 1L),
            epicState,
        )

        val epicPoint = available(epicAdapter.resolve(epicKey))
        val epicBatch = available(epicAdapter.resolveAll(setOf(epicKey)).getValue(epicKey))
        assertEquals(5, (epicPoint.reference as SourceOwnedCopyReference.Epic).localRowId)
        assertEquals(epicPoint, epicBatch)

        val amazonKey = key(GameSource.AMAZON, "product")
        val amazonRows = listOf(
            AmazonGame(appId = 1, productId = "product", isInstalled = false),
            AmazonGame(appId = 3, productId = "product", isInstalled = true),
            AmazonGame(appId = 2, productId = "product", isInstalled = true),
        )
        val preferredAmazon = amazonRows.last()
        val amazonDao = mockk<AmazonGameDao>()
        coEvery { amazonDao.getByProductId("product") } returns preferredAmazon
        coEvery { amazonDao.getByAppId(2) } returns preferredAmazon
        coEvery { amazonDao.getAllAsList() } returns amazonRows
        every { amazonDao.getAll() } returns emptyFlow()
        val amazonLedger = ledger(GameSource.AMAZON, mapOf("product" to "entitlement"))
        val amazonSource = AmazonOwnedCopySourceAdapter(
            amazonDao,
            scopes(GameSource.AMAZON),
            amazonLedger,
            readyLifecycle(GameSource.AMAZON),
        )
        val amazonState = mockk<AmazonOwnedCopyRuntimeState>()
        coEvery { amazonState.readPoint(preferredAmazon, scope, 0L) } returns state()
        coEvery { amazonState.readBatch(listOf(preferredAmazon), scope, 0L) } returns mapOf(2 to state())
        val amazonAdapter = AmazonOwnedCopyRuntimeAdapter(
            amazonDao,
            scopes(GameSource.AMAZON),
            amazonLedger,
            readyLifecycle(GameSource.AMAZON),
            amazonSource,
            historyDao("AMAZON_2", 1L),
            amazonState,
        )

        val amazonPoint = available(amazonAdapter.resolve(amazonKey))
        val amazonBatch = available(amazonAdapter.resolveAll(setOf(amazonKey)).getValue(amazonKey))
        assertEquals(2, (amazonPoint.reference as SourceOwnedCopyReference.Amazon).localRowId)
        assertEquals(amazonPoint, amazonBatch)
    }

    @Test
    fun customProductionStateUsesOneIoConfinedReadOnlyPersistedIdScan() = runTest {
        val scanner = mockk<CustomOwnedCopyRuntimeScanner>()
        val row = customRow(7)
        var scanThread: String? = null
        every { scanner.scan(setOf(7)) } answers {
            scanThread = Thread.currentThread().name
            mapOf(7 to row)
        }
        val callerThread = Thread.currentThread().name
        val runtimeState = CustomOwnedCopyRuntimeState(scanner)

        assertEquals(mapOf(7 to row), runtimeState.read(setOf(7)))

        verify(exactly = 1) { scanner.scan(setOf(7)) }
        assertFalse(scanThread == callerThread)
    }

    @Test
    fun customProductionScannerFailsClosedOnDuplicateOrMissingPersistedIdsWithoutWrites() = runTest {
        val root = Files.createTempDirectory("custom-runtime").toFile()
        try {
            val unique = File(root, "Unique").apply { mkdirs() }
            val duplicateA = File(root, "Duplicate A").apply { mkdirs() }
            val duplicateB = File(root, "Duplicate B").apply { mkdirs() }
            val missing = File(root, "Missing").apply { mkdirs() }
            File(unique, ".gamenative").writeText("{\"appId\":7}")
            File(duplicateA, ".gamenative").writeText("{\"appId\":8}")
            File(duplicateB, ".gamenative").writeText("{\"appId\":8}")
            File(unique, "steamgriddb_logo.png").writeText("logo")
            File(unique, "coverv.png").writeText("capsule")
            File(unique, "coverh.jpg").writeText("hero")
            File(unique, "nested").apply { mkdirs() }
            File(unique, "nested/payload.bin").writeBytes(ByteArray(32))
            PrefManager.customGameManualFolders = setOf(
                unique.path,
                duplicateA.path,
                duplicateB.path,
                missing.path,
            )
            val before = root.walkTopDown().map { it.relativeTo(root).path }.toSet()
            val metadataBefore = File(unique, ".gamenative").readText()
            val runtimeState = CustomOwnedCopyRuntimeState(CustomOwnedCopyRuntimeScanner())

            val rows = runtimeState.read(setOf(7, 8, 9))

            assertEquals(setOf(7), rows.keys)
            val row = rows.getValue(7)
            assertEquals("Unique", row.nativeTitle)
            assertNull(row.installedSizeBytes)
            assertTrue(row.iconUrl.endsWith("steamgriddb_logo.png"))
            assertTrue(row.capsuleImageUrl.endsWith("coverv.png"))
            assertTrue(row.heroImageUrl.endsWith("coverh.jpg"))
            assertEquals(row.heroImageUrl, row.headerImageUrl)
            assertEquals(before, root.walkTopDown().map { it.relativeTo(root).path }.toSet())
            assertEquals(metadataBefore, File(unique, ".gamenative").readText())
            assertFalse(File(unique, ".extracted.ico").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun customMetadataDeletionHidesBothPointAndBatchWithProductionState() = runTest {
        val root = Files.createTempDirectory("custom-runtime-deletion").toFile()
        try {
            val folder = File(root, "Deleted metadata").apply { mkdirs() }
            PrefManager.customGameManualFolders = setOf(folder.path)
            val runtimeState = CustomOwnedCopyRuntimeState(CustomOwnedCopyRuntimeScanner())
            val key = key(GameSource.CUSTOM_GAME, "7")
            val adapter = CustomOwnedCopyRuntimeAdapter(
                scopes(GameSource.CUSTOM_GAME),
                mockk(relaxed = true),
                batchHistory(),
                runtimeState,
            )

            assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolve(key))
            assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolveAll(setOf(key)).getValue(key))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun steamReadyAdapterForWrongSource(): SteamOwnedCopyRuntimeAdapter =
        SteamOwnedCopyRuntimeAdapter(
            mockk(relaxed = true),
            scopes(GameSource.STEAM),
            readyLifecycle(GameSource.STEAM),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )

    private fun key(source: GameSource, stableSourceId: String): OwnedCopyKey =
        OwnedCopyKey(scope, source, stableSourceId)

    private fun readyLifecycle(source: GameSource): MutableLifecycle = MutableLifecycle().apply {
        readySources += source
    }

    private fun scopes(
        vararg sources: GameSource,
        value: AccountScope = scope,
    ): AccountScopeProvider = object : AccountScopeProvider {
        private val available = sources.toSet()
        override suspend fun current(source: GameSource): AccountScope? = value.takeIf { source in available }
    }

    private fun historyDao(appId: String, lastPlayed: Long): LibraryPlayHistoryDao =
        mockk<LibraryPlayHistoryDao>().also { dao ->
            coEvery { dao.get(appId) } returns LibraryPlayHistory(appId, lastPlayed)
            every { dao.getAll() } returns flowOf(listOf(LibraryPlayHistory(appId, lastPlayed)))
        }

    private fun batchHistory(): LibraryPlayHistoryDao = mockk<LibraryPlayHistoryDao>().also { dao ->
        every { dao.getAll() } returns flowOf(emptyList())
    }

    private fun ledger(
        source: GameSource,
        entries: Map<String, String?>,
    ): OwnedCopyLedgerDao = mockk<OwnedCopyLedgerDao>().also { dao ->
        coEvery {
            dao.getCompletedSnapshotForLifecycle(scope.value, source, any())
        } answers {
            CompletedOwnedCopySnapshot(
                completedAt = 1L,
                lifecycleGeneration = invocation.args[2] as Long,
                stableSourceIds = entries.keys.sorted(),
                resolvedSourceIds = entries.mapNotNull { (stableId, resolvedId) ->
                    resolvedId?.let { stableId to it }
                }.toMap(),
            )
        }
        coEvery { dao.isPresentForLifecycle(scope.value, source, any(), any()) } answers {
            invocation.args[2] as String in entries
        }
        coEvery { dao.getPresenceForLifecycle(scope.value, source, any(), any()) } answers {
            val stableId = invocation.args[2] as String
            if (stableId !in entries) {
                null
            } else {
                OwnedCopyPresenceEntity(
                    accountScope = scope.value,
                    source = source,
                    stableSourceId = stableId,
                    resolvedSourceId = entries[stableId],
                )
            }
        }
        every { dao.observeSourceHeaders(source) } returns emptyFlow()
    }

    private inline fun <reified T : Any> sourceAdapter(
        key: OwnedCopyKey,
        reference: SourceOwnedCopyReference?,
    ): T = mockk<T>().also { adapter ->
        every { adapter.callInvalidations() } returns emptyFlow()
        coEvery { adapter.callResolve(key) } returns reference
    }

    private fun Any.callInvalidations(): Flow<Unit> = when (this) {
        is SteamOwnedCopySourceAdapter -> invalidations()
        is GogOwnedCopySourceAdapter -> invalidations()
        is EpicOwnedCopySourceAdapter -> invalidations()
        is AmazonOwnedCopySourceAdapter -> invalidations()
        is CustomOwnedCopySourceAdapter -> invalidations()
        else -> error("Unsupported source adapter")
    }

    private suspend fun Any.callResolve(key: OwnedCopyKey): SourceOwnedCopyReference? = when (this) {
        is SteamOwnedCopySourceAdapter -> resolve(key)
        is GogOwnedCopySourceAdapter -> resolve(key)
        is EpicOwnedCopySourceAdapter -> resolve(key)
        is AmazonOwnedCopySourceAdapter -> resolve(key)
        is CustomOwnedCopySourceAdapter -> resolve(key)
        else -> error("Unsupported source adapter")
    }

    private fun state(
        installPath: String? = null,
        installedSizeBytes: Long? = null,
        branchOrVersion: String? = null,
        isInstalled: Boolean = false,
        isDownloading: Boolean = false,
        hasPartialDownload: Boolean = false,
        updateAvailable: Boolean = false,
        isShared: Boolean = false,
        playtimeMinutes: Long? = null,
    ): OwnedCopyVolatileState = OwnedCopyVolatileState(
        installPath = installPath,
        installedSizeBytes = installedSizeBytes,
        branchOrVersion = branchOrVersion,
        isInstalled = isInstalled,
        isDownloading = isDownloading,
        hasPartialDownload = hasPartialDownload,
        updateAvailable = updateAvailable,
        isShared = isShared,
        playtimeMinutes = playtimeMinutes,
    )

    private fun customRow(appId: Int): CustomOwnedCopyRuntimeRow = CustomOwnedCopyRuntimeRow(
        appId = appId,
        nativeTitle = "Custom $appId",
        installPath = "/custom/$appId",
        installedSizeBytes = 1L,
        iconUrl = "",
        capsuleImageUrl = "",
        headerImageUrl = "",
        heroImageUrl = "",
    )

    private suspend fun <T : Throwable> assertSuspendThrows(
        expected: Class<T>,
        block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (error: Throwable) {
            if (expected.isInstance(error)) return expected.cast(error)
            throw AssertionError(
                "Expected ${expected.simpleName}, but caught ${error::class.java.simpleName}",
                error,
            )
        }
        throw AssertionError("Expected ${expected.simpleName} to be thrown")
    }

    private fun available(result: OwnedCopyRuntimeResult): OwnedCopyRuntime =
        (result as OwnedCopyRuntimeResult.Available).copy

    private fun assertUnavailable(
        result: OwnedCopyRuntimeResult,
        key: OwnedCopyKey,
        reason: CopyUnavailableReason,
        errorClass: kotlin.reflect.KClass<out Throwable>? = null,
    ) {
        val unavailable = result as OwnedCopyRuntimeResult.Unavailable
        assertEquals(key, unavailable.key)
        assertEquals(reason, unavailable.reason)
        assertEquals(errorClass, unavailable.errorClass)
    }

    private fun assertVolatileState(
        runtime: OwnedCopyRuntime,
        expected: OwnedCopyVolatileState,
        lastPlayed: Long?,
    ) {
        assertEquals(expected.installPath, runtime.installPath)
        assertEquals(expected.installedSizeBytes, runtime.installedSizeBytes)
        assertEquals(expected.branchOrVersion, runtime.branchOrVersion)
        assertEquals(expected.isInstalled, runtime.isInstalled)
        assertEquals(expected.isDownloading, runtime.isDownloading)
        assertEquals(expected.hasPartialDownload, runtime.hasPartialDownload)
        assertEquals(expected.updateAvailable, runtime.updateAvailable)
        assertEquals(expected.isShared, runtime.isShared)
        assertEquals(lastPlayed, runtime.lastPlayedEpochMs)
        assertEquals(expected.playtimeMinutes, runtime.playtimeMinutes)
    }

    private fun completedSnapshot(entries: Map<String, String?>): CompletedOwnedCopySnapshot =
        CompletedOwnedCopySnapshot(
            completedAt = 1L,
            lifecycleGeneration = 0L,
            stableSourceIds = entries.keys.sorted(),
            resolvedSourceIds = entries.mapNotNull { (stableId, resolvedId) ->
                resolvedId?.let { stableId to it }
            }.toMap(),
        )

    private fun runtimeResult(
        copyKey: OwnedCopyKey,
        referenceKey: OwnedCopyKey,
    ): OwnedCopyRuntimeResult.Available = OwnedCopyRuntimeResult.Available(
        OwnedCopyRuntime(
            key = copyKey,
            reference = SourceOwnedCopyReference.Steam(referenceKey, 1),
            libraryItem = null,
            nativeTitle = "Runtime",
            aliases = emptySet(),
            developerKey = "",
            releaseYear = null,
            appType = CanonicalAppType.GAME,
            genreKeys = emptySet(),
            tagIds = emptySet(),
            featureKeys = emptySet(),
            iconUrl = "",
            capsuleImageUrl = "",
            headerImageUrl = "",
            heroImageUrl = "",
            gridHeroImageScale = 1f,
            installPath = null,
            installedSizeBytes = null,
            branchOrVersion = null,
            isInstalled = false,
            isDownloading = false,
            hasPartialDownload = false,
            updateAvailable = false,
            isShared = false,
            lastPlayedEpochMs = null,
            playtimeMinutes = null,
            capabilities = emptySet(),
        ),
    )

    private fun fakeAdapter(
        source: GameSource,
        invalidations: Flow<Unit> = emptyFlow(),
        resolve: (OwnedCopyKey) -> OwnedCopyRuntimeResult = { OwnedCopyRuntimeResult.Hidden },
        resolveAll: (Set<OwnedCopyKey>) -> Map<OwnedCopyKey, OwnedCopyRuntimeResult> = { keys ->
            keys.associateWith { OwnedCopyRuntimeResult.Hidden }
        },
    ): OwnedCopyRuntimeAdapter = object : OwnedCopyRuntimeAdapter {
        override val source: GameSource = source
        override fun invalidations(): Flow<Unit> = invalidations
        override suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult = resolve(key)
        override suspend fun resolveAll(keys: Set<OwnedCopyKey>): Map<OwnedCopyKey, OwnedCopyRuntimeResult> =
            resolveAll(keys)
    }

    private class MutableLifecycle(
        var generation: Long = 0L,
        var readyGeneration: Long? = null,
    ) : AccountLifecycleState {
        val readySources = mutableSetOf<GameSource>()

        override fun generation(source: GameSource): Long = generation
        override fun readyGeneration(source: GameSource): Long? =
            generation.takeIf { source in readySources } ?: readyGeneration

        override fun advanceGeneration(source: GameSource): Long {
            generation += 1
            readySources -= source
            readyGeneration = null
            return generation
        }

        override fun markReady(source: GameSource, expectedGeneration: Long): Boolean {
            if (generation != expectedGeneration) return false
            readySources += source
            readyGeneration = expectedGeneration
            return true
        }
    }

    private class CountingScopeProvider(
        private val value: AccountScope,
    ) : AccountScopeProvider {
        var calls: Int = 0
        override suspend fun current(source: GameSource): AccountScope {
            calls += 1
            return value
        }
    }

    private class SequencedScopeProvider(
        private val values: List<AccountScope?>,
    ) : AccountScopeProvider {
        var calls: Int = 0
        override suspend fun current(source: GameSource): AccountScope? =
            values[calls.coerceAtMost(values.lastIndex)].also { calls += 1 }
    }

    private class SecondCallThrowingScopeProvider(
        private val value: AccountScope,
        private val error: Throwable,
    ) : AccountScopeProvider {
        private var calls = 0

        override suspend fun current(source: GameSource): AccountScope {
            calls += 1
            if (calls >= 2) throw error
            return value
        }
    }

    private class ThrowingScopeProvider(
        private val error: Throwable,
    ) : AccountScopeProvider {
        override suspend fun current(source: GameSource): AccountScope? = throw error
    }

    private class SensitiveFailure : IllegalStateException(SENSITIVE_MESSAGE)

    private companion object {
        const val SENSITIVE_MESSAGE =
            "private title account source-id path URL token username and exception text"
    }
}
