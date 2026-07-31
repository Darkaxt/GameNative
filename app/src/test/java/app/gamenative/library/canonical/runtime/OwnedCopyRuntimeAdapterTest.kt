package app.gamenative.library.canonical.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.data.AmazonGame
import app.gamenative.data.AppInfo
import app.gamenative.data.DepotInfo
import app.gamenative.data.EpicGame
import app.gamenative.data.GOGGame
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryAssetsInfo
import app.gamenative.data.LibraryItem
import app.gamenative.data.LibraryCapsuleInfo
import app.gamenative.data.LibraryHeroInfo
import app.gamenative.data.LibraryPlayHistory
import app.gamenative.data.ManifestInfo
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
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import app.gamenative.enums.SteamRealm
import app.gamenative.library.canonical.AccountLifecycleState
import app.gamenative.library.canonical.AccountScopedOwnershipLedger
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.InMemoryAccountLifecycleState
import app.gamenative.library.canonical.MaterializedOwnedCopySnapshot
import app.gamenative.library.canonical.CanonicalDiagnosticSink
import app.gamenative.library.canonical.CanonicalLibraryDiagnosticSink
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.PlayHistoryOrigin
import app.gamenative.library.canonical.source.AmazonOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.CustomOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.EpicOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.GogOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.library.canonical.source.SteamOwnedCopySourceAdapter
import app.gamenative.service.SteamService
import app.gamenative.service.SteamUpdateCheckResult
import app.gamenative.service.amazon.AmazonArtwork
import app.gamenative.utils.GameMetadataManager
import app.gamenative.utils.ReadOnlyAppIdResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.newSingleThreadContext
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
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
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
    fun emptyAdapterKeySetsSubmitCompleteEmptyRuntimeSnapshotsWithoutSourceQueries() = runTest {
        val steamDao = mockk<SteamAppDao>(relaxed = true)
        val steamState = mockk<SteamOwnedCopyRuntimeState>()
        coEvery {
            steamState.readBatch(emptyList(), scope, 0L)
        } returns SteamRuntimeBatchResult(emptyMap(), emptyMap())
        val steamAdapter = SteamOwnedCopyRuntimeAdapter(
            steamDao,
            scopes(GameSource.STEAM),
            readyLifecycle(GameSource.STEAM),
            mockk(relaxed = true),
            mockk(relaxed = true),
            steamState,
        )
        val amazonDao = mockk<AmazonGameDao>(relaxed = true)
        val amazonState = mockk<AmazonOwnedCopyRuntimeState>()
        coEvery {
            amazonState.readBatch(emptyList(), scope, 0L)
        } returns AmazonRuntimeBatchResult(emptyMap(), emptyMap())
        val amazonAdapter = AmazonOwnedCopyRuntimeAdapter(
            amazonDao,
            scopes(GameSource.AMAZON),
            mockk(relaxed = true),
            readyLifecycle(GameSource.AMAZON),
            mockk(relaxed = true),
            mockk(relaxed = true),
            amazonState,
        )

        assertEquals(emptyMap<OwnedCopyKey, OwnedCopyRuntimeResult>(), steamAdapter.resolveAll(emptySet()))
        assertEquals(emptyMap<OwnedCopyKey, OwnedCopyRuntimeResult>(), amazonAdapter.resolveAll(emptySet()))

        coVerify(exactly = 1) { steamState.readBatch(emptyList(), scope, 0L) }
        coVerify(exactly = 1) { amazonState.readBatch(emptyList(), scope, 0L) }
        coVerify(exactly = 0) { steamDao._getAllOwnedAppsPaged(any(), any()) }
        coVerify(exactly = 0) { amazonDao.getAllAsList() }
    }

    @Test
    fun emptyAmazonOwnershipSnapshotSubmitsCompleteEmptyRuntimeSnapshot() = runTest {
        val key = key(GameSource.AMAZON, "removed")
        val runtimeState = mockk<AmazonOwnedCopyRuntimeState>()
        coEvery {
            runtimeState.readBatch(emptyList(), scope, 0L)
        } returns AmazonRuntimeBatchResult(emptyMap(), emptyMap())
        val adapter = AmazonOwnedCopyRuntimeAdapter(
            mockk(relaxed = true),
            scopes(GameSource.AMAZON),
            ledger(GameSource.AMAZON, emptyMap()),
            readyLifecycle(GameSource.AMAZON),
            mockk(relaxed = true),
            mockk(relaxed = true),
            runtimeState,
        )

        val result = adapter.resolveAll(setOf(key))

        assertEquals(OwnedCopyRuntimeResult.Hidden, result.getValue(key))
        coVerify(exactly = 1) { runtimeState.readBatch(emptyList(), scope, 0L) }
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
        coEvery { runtimeState.readBatch(listOf(app), scope, 0L) } returns SteamRuntimeBatchResult(
            states = mapOf(42 to currentState),
            failures = emptyMap(),
        )
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
        val ledgerEntries = mapOf("12345" to null)
        val ledger = ledger(GameSource.GOG, ledgerEntries)
        val lifecycle = lifecycleReadyFromCompletedLedger(GameSource.GOG, ledger, ledgerEntries)
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
            accountLifecycleState = lifecycle,
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
        coVerify(exactly = 2) { ledger.getCompletedSnapshotForLifecycle(scope.value, GameSource.GOG, 1L) }
        coVerify(exactly = 1) { dao.getAllAsList() }
    }

    @Test
    fun gogHistoryFailuresAreCategoricalEnrichmentFailuresForPointAndBatch() = runTest {
        val key = key(GameSource.GOG, "123")
        val game = GOGGame(id = "123", title = "Provider", lastPlayed = 700L)
        val dao = mockk<GOGGameDao>()
        coEvery { dao.getById("123") } returns game
        coEvery { dao.getAllAsList() } returns listOf(game)
        val sourceAdapter = sourceAdapter<GogOwnedCopySourceAdapter>(
            key,
            SourceOwnedCopyReference.Gog(key, "123"),
        )
        val history = mockk<LibraryPlayHistoryDao>()
        coEvery { history.get("GOG_123") } throws SensitiveFailure()
        every { history.getAll() } returns flow { throw SensitiveFailure() }
        val runtimeState = mockk<GogOwnedCopyRuntimeState>()
        coEvery { runtimeState.read(listOf(game)) } returns mapOf("123" to state())
        val diagnostics = mockk<CanonicalDiagnosticSink>(relaxed = true)
        val adapter = GogOwnedCopyRuntimeAdapter(
            dao,
            scopes(GameSource.GOG),
            ledger(GameSource.GOG, mapOf("123" to null)),
            readyLifecycle(GameSource.GOG),
            sourceAdapter,
            history,
            runtimeState,
            diagnostics,
        )

        val point = available(adapter.resolve(key))
        val batch = available(adapter.resolveAll(setOf(key)).getValue(key))

        assertEquals(700L, point.lastPlayedEpochMs)
        assertEquals(700L, batch.lastPlayedEpochMs)
        verify(exactly = 1) {
            diagnostics.playHistoryFailed(GameSource.GOG, PlayHistoryOrigin.POINT, SensitiveFailure::class)
        }
        verify(exactly = 1) {
            diagnostics.playHistoryFailed(GameSource.GOG, PlayHistoryOrigin.BATCH, SensitiveFailure::class)
        }
    }

    @Test
    fun steamHistoryFailuresAreCategoricalEnrichmentFailuresForPointAndBatch() = runTest {
        val key = key(GameSource.STEAM, "7")
        val app = SteamApp(id = 7, name = "Provider")
        val dao = mockk<SteamAppDao>()
        coEvery { dao.findOwnedApp(7) } returns app
        coEvery { dao._getAllOwnedAppsPaged() } returns listOf(app)
        val sourceAdapter = sourceAdapter<SteamOwnedCopySourceAdapter>(
            key,
            SourceOwnedCopyReference.Steam(key, 7),
        )
        val history = mockk<LibraryPlayHistoryDao>()
        coEvery { history.get("STEAM_7") } throws SensitiveFailure()
        every { history.getAll() } returns flow { throw SensitiveFailure() }
        val runtimeState = mockk<SteamOwnedCopyRuntimeState>()
        coEvery { runtimeState.readPoint(app, scope, 0L) } returns state()
        coEvery { runtimeState.readBatch(listOf(app), scope, 0L) } returns
            SteamRuntimeBatchResult(mapOf(7 to state()), emptyMap())
        every { runtimeState.updateInvalidations() } returns emptyFlow()
        val diagnostics = mockk<CanonicalDiagnosticSink>(relaxed = true)
        val adapter = SteamOwnedCopyRuntimeAdapter(
            dao,
            scopes(GameSource.STEAM),
            readyLifecycle(GameSource.STEAM),
            sourceAdapter,
            history,
            runtimeState,
            diagnostics,
        )

        val point = available(adapter.resolve(key))
        val batch = available(adapter.resolveAll(setOf(key)).getValue(key))

        assertEquals(null, point.lastPlayedEpochMs)
        assertEquals(null, batch.lastPlayedEpochMs)
        verify(exactly = 1) {
            diagnostics.playHistoryFailed(GameSource.STEAM, PlayHistoryOrigin.POINT, SensitiveFailure::class)
        }
        verify(exactly = 1) {
            diagnostics.playHistoryFailed(GameSource.STEAM, PlayHistoryOrigin.BATCH, SensitiveFailure::class)
        }
    }

    @Test
    fun customHistoryFailuresAreCategoricalEnrichmentFailuresForPointAndBatch() = runTest {
        val key = key(GameSource.CUSTOM_GAME, "7")
        val row = CustomOwnedCopyRuntimeRow(
            appId = 7,
            nativeTitle = "Provider",
            installPath = "/installed",
            installedSizeBytes = 1L,
            iconUrl = "",
            capsuleImageUrl = "",
            headerImageUrl = "",
            heroImageUrl = "",
        )
        val history = mockk<LibraryPlayHistoryDao>()
        coEvery { history.get("CUSTOM_GAME_7") } throws SensitiveFailure()
        every { history.getAll() } returns flow { throw SensitiveFailure() }
        val runtimeState = mockk<CustomOwnedCopyRuntimeState>()
        coEvery { runtimeState.readTyped(setOf(7)) } returns
            CustomRuntimeScanResult(mapOf(7 to row))
        val diagnostics = mockk<CanonicalDiagnosticSink>(relaxed = true)
        val adapter = CustomOwnedCopyRuntimeAdapter(
            scopes(GameSource.CUSTOM_GAME),
            mockk(relaxed = true),
            history,
            runtimeState,
            diagnostics,
        )

        val point = available(adapter.resolve(key))
        val batch = available(adapter.resolveAll(setOf(key)).getValue(key))

        assertEquals(null, point.lastPlayedEpochMs)
        assertEquals(null, batch.lastPlayedEpochMs)
        verify(exactly = 1) {
            diagnostics.playHistoryFailed(
                GameSource.CUSTOM_GAME,
                PlayHistoryOrigin.POINT,
                SensitiveFailure::class,
            )
        }
        verify(exactly = 1) {
            diagnostics.playHistoryFailed(
                GameSource.CUSTOM_GAME,
                PlayHistoryOrigin.BATCH,
                SensitiveFailure::class,
            )
        }
    }

    @Test
    fun historyEnrichmentPreservesCancellationAndFatalFailures() = runTest {
        val cancelledPoint = mockk<LibraryPlayHistoryDao>()
        coEvery { cancelledPoint.get(any()) } throws CancellationException("cancel")
        assertSuspendThrows(CancellationException::class.java) {
            cancelledPoint.pointLastPlayed("GOG_1", GameSource.GOG, null)
        }

        val cancelledBatch = mockk<LibraryPlayHistoryDao>()
        every { cancelledBatch.getAll() } returns flow { throw CancellationException("cancel") }
        assertSuspendThrows(CancellationException::class.java) {
            cancelledBatch.batchLastPlayed(GameSource.EPIC, null)
        }

        val fatalPoint = mockk<LibraryPlayHistoryDao>()
        coEvery { fatalPoint.get(any()) } throws OutOfMemoryError("fatal")
        assertSuspendThrows(OutOfMemoryError::class.java) {
            fatalPoint.pointLastPlayed("AMAZON_1", GameSource.AMAZON, null)
        }

        val fatalBatch = mockk<LibraryPlayHistoryDao>()
        every { fatalBatch.getAll() } returns flow { throw OutOfMemoryError("fatal") }
        assertSuspendThrows(OutOfMemoryError::class.java) {
            fatalBatch.batchLastPlayed(GameSource.AMAZON, null)
        }
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
        val ledgerEntries = mapOf(stableId to null)
        val ledger = ledger(GameSource.EPIC, ledgerEntries)
        val lifecycle = lifecycleReadyFromCompletedLedger(GameSource.EPIC, ledger, ledgerEntries)
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
            accountLifecycleState = lifecycle,
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
        val ledgerEntries = mapOf("product-id" to "current-entitlement")
        val ledger = ledger(GameSource.AMAZON, ledgerEntries)
        val lifecycle = lifecycleReadyFromCompletedLedger(GameSource.AMAZON, ledger, ledgerEntries)
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
        coEvery { runtimeState.readPoint(game, scope, 1L) } returns currentState
        coEvery { runtimeState.readBatch(listOf(game), scope, 1L) } returns
            AmazonRuntimeBatchResult(mapOf(88 to currentState), emptyMap())
        val adapter = AmazonOwnedCopyRuntimeAdapter(
            amazonGameDao = dao,
            accountScopeProvider = scopes(GameSource.AMAZON),
            ownedCopyLedgerDao = ledger,
            accountLifecycleState = lifecycle,
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
        coEvery { runtimeState.readTyped(setOf(99)) } returns CustomRuntimeScanResult(
            rows = mapOf(99 to row),
        )
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
        coVerify(exactly = 4) { runtimeState.readTyped(setOf(99)) }
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
        coEvery { customState.readTyped(setOf(5)) } returns CustomRuntimeScanResult(
            mapOf(5 to customRow(5)),
        )
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
    fun customHistoryFailureAfterCurrentRowProofDoesNotSuppressCopy() = runTest {
        val key = key(GameSource.CUSTOM_GAME, "5")
        val source = sourceAdapter<CustomOwnedCopySourceAdapter>(
            key,
            SourceOwnedCopyReference.Custom(key, 5),
        )
        val runtimeState = mockk<CustomOwnedCopyRuntimeState>()
        coEvery { runtimeState.readTyped(setOf(5)) } returns CustomRuntimeScanResult(
            mapOf(5 to customRow(5)),
        )
        val history = mockk<LibraryPlayHistoryDao>()
        coEvery { history.get("CUSTOM_GAME_5") } throws SensitiveFailure()
        val diagnostics = mockk<CanonicalDiagnosticSink>(relaxed = true)
        val adapter = CustomOwnedCopyRuntimeAdapter(
            scopes(GameSource.CUSTOM_GAME),
            source,
            history,
            runtimeState,
            diagnostics,
        )

        val result = available(adapter.resolve(key))

        assertEquals(null, result.lastPlayedEpochMs)
        verify(exactly = 1) {
            diagnostics.playHistoryFailed(
                GameSource.CUSTOM_GAME,
                PlayHistoryOrigin.POINT,
                SensitiveFailure::class,
            )
        }
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
    fun everySourceRecordsOneRuntimeFailurePerPointAndBatchGesture() = runTest {
        val diagnostics = mockk<CanonicalLibraryDiagnosticSink>(relaxed = true)

        fun failingScopes(source: GameSource): AccountScopeProvider = mockk<AccountScopeProvider>().also {
            coEvery { it.current(source) } throws SensitiveFailure()
        }

        val adapters = listOf(
            SteamOwnedCopyRuntimeAdapter(
                steamAppDao = mockk(relaxed = true),
                accountScopeProvider = failingScopes(GameSource.STEAM),
                accountLifecycleState = readyLifecycle(GameSource.STEAM),
                sourceAdapter = mockk(relaxed = true),
                playHistoryDao = mockk(relaxed = true),
                runtimeState = mockk(relaxed = true),
                libraryDiagnostics = diagnostics,
            ),
            GogOwnedCopyRuntimeAdapter(
                gogGameDao = mockk(relaxed = true),
                accountScopeProvider = failingScopes(GameSource.GOG),
                ownedCopyLedgerDao = mockk(relaxed = true),
                accountLifecycleState = readyLifecycle(GameSource.GOG),
                sourceAdapter = mockk(relaxed = true),
                playHistoryDao = mockk(relaxed = true),
                runtimeState = mockk(relaxed = true),
                libraryDiagnostics = diagnostics,
            ),
            EpicOwnedCopyRuntimeAdapter(
                epicGameDao = mockk(relaxed = true),
                accountScopeProvider = failingScopes(GameSource.EPIC),
                ownedCopyLedgerDao = mockk(relaxed = true),
                accountLifecycleState = readyLifecycle(GameSource.EPIC),
                sourceAdapter = mockk(relaxed = true),
                playHistoryDao = mockk(relaxed = true),
                runtimeState = mockk(relaxed = true),
                libraryDiagnostics = diagnostics,
            ),
            AmazonOwnedCopyRuntimeAdapter(
                amazonGameDao = mockk(relaxed = true),
                accountScopeProvider = failingScopes(GameSource.AMAZON),
                ownedCopyLedgerDao = mockk(relaxed = true),
                accountLifecycleState = readyLifecycle(GameSource.AMAZON),
                sourceAdapter = mockk(relaxed = true),
                playHistoryDao = mockk(relaxed = true),
                runtimeState = mockk(relaxed = true),
                libraryDiagnostics = diagnostics,
            ),
            CustomOwnedCopyRuntimeAdapter(
                accountScopeProvider = failingScopes(GameSource.CUSTOM_GAME),
                sourceAdapter = mockk(relaxed = true),
                playHistoryDao = mockk(relaxed = true),
                runtimeState = mockk(relaxed = true),
                libraryDiagnostics = diagnostics,
            ),
        )

        adapters.forEach { adapter ->
            val key = key(adapter.source, "77")
            assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolve(key))
            assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolveAll(setOf(key)).getValue(key))
        }

        GameSource.entries.forEach { source ->
            verify(exactly = 2) {
                diagnostics.runtimeReadFailed(source, SensitiveFailure::class)
            }
        }
    }

    @Test
    fun batchFailureAfterSteamOwnershipProofIsTypedOnceAtFifteenHundredCopyScaleAndCancellationEscapes() =
        runTest {
            val keys = (1..1_500).mapTo(linkedSetOf()) { appId ->
                key(GameSource.STEAM, appId.toString())
            }
            val apps = (1..1_500).map { appId -> SteamApp(id = appId, name = "Owned $appId") }
            val dao = mockk<SteamAppDao>()
            coEvery { dao._getAllOwnedAppsPaged(any(), any()) } returns apps
            val failedState = mockk<SteamOwnedCopyRuntimeState>()
            coEvery { failedState.readBatch(apps, scope, 0L) } throws SensitiveFailure()
            val diagnostics = mockk<CanonicalLibraryDiagnosticSink>(relaxed = true)
            val failed = SteamOwnedCopyRuntimeAdapter(
                dao,
                scopes(GameSource.STEAM),
                readyLifecycle(GameSource.STEAM),
                mockk(relaxed = true),
                batchHistory(),
                failedState,
                libraryDiagnostics = diagnostics,
            )

            val failedResults = failed.resolveAll(keys)

            assertEquals(1_500, failedResults.size)
            failedResults.forEach { (key, result) ->
                assertUnavailable(
                    result,
                    key,
                    CopyUnavailableReason.SOURCE_READ_FAILED,
                    SensitiveFailure::class,
                )
            }
            verify(exactly = 1) {
                diagnostics.runtimeReadFailed(GameSource.STEAM, SensitiveFailure::class)
            }

            val cancelledState = mockk<SteamOwnedCopyRuntimeState>()
            coEvery { cancelledState.readBatch(apps, scope, 0L) } throws CancellationException("stop")
            val cancelledDiagnostics = mockk<CanonicalLibraryDiagnosticSink>(relaxed = true)
            val cancelled = SteamOwnedCopyRuntimeAdapter(
                dao,
                scopes(GameSource.STEAM),
                readyLifecycle(GameSource.STEAM),
                mockk(relaxed = true),
                batchHistory(),
                cancelledState,
                libraryDiagnostics = cancelledDiagnostics,
            )
            assertSuspendThrows(CancellationException::class.java) {
                cancelled.resolveAll(keys)
            }
            verify(exactly = 0) { cancelledDiagnostics.runtimeReadFailed(any(), any()) }
        }

    @Test
    fun sourceBatchRecordsOnlyItsFirstRuntimeFailureWhenLaterProjectionAlsoFails() = runTest {
        val keys = setOf(key(GameSource.STEAM, "1"), key(GameSource.STEAM, "2"))
        val healthy = SteamApp(id = 1, name = "Healthy")
        val broken = mockk<SteamApp>(relaxed = true) {
            every { id } returns 2
            every { name } throws SensitiveFailure()
        }
        val rows = listOf(healthy, broken)
        val dao = mockk<SteamAppDao>()
        coEvery { dao._getAllOwnedAppsPaged(any(), any()) } returns rows
        val runtimeState = mockk<SteamOwnedCopyRuntimeState>()
        coEvery { runtimeState.readBatch(rows, scope, 0L) } returns SteamRuntimeBatchResult(
            states = mapOf(2 to state()),
            failures = mapOf(1 to SensitiveFailure::class),
        )
        val diagnostics = mockk<CanonicalLibraryDiagnosticSink>(relaxed = true)
        val adapter = SteamOwnedCopyRuntimeAdapter(
            dao,
            scopes(GameSource.STEAM),
            readyLifecycle(GameSource.STEAM),
            mockk(relaxed = true),
            batchHistory(),
            runtimeState,
            libraryDiagnostics = diagnostics,
        )

        assertEquals(keys, adapter.resolveAll(keys).keys)

        verify(exactly = 1) {
            diagnostics.runtimeReadFailed(GameSource.STEAM, SensitiveFailure::class)
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
        coEvery { steamState.readBatch(steamRows, scope, 0L) } returns SteamRuntimeBatchResult(
            states = steamRows.associate { it.id to state() },
            failures = emptyMap(),
        )
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
        coEvery { amazonState.readBatch(amazonRows, scope, 0L) } returns AmazonRuntimeBatchResult(
            amazonRows.associate { it.appId to state() },
            emptyMap(),
        )
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
        coEvery { customState.readTyped(setOf(1, 2)) } returns CustomRuntimeScanResult(
            mapOf(
                1 to customRow(1),
                2 to customRow(2),
            ),
        )
        val customScopes = CountingScopeProvider(scope)
        val custom = CustomOwnedCopyRuntimeAdapter(
            customScopes,
            mockk(relaxed = true),
            customHistory,
            customState,
        )
        assertEquals(customKeys, custom.resolveAll(customKeys).keys)
        coVerify(exactly = 2) { customState.readTyped(setOf(1, 2)) }
        verify(exactly = 1) { customHistory.getAll() }
        assertEquals(2, customScopes.calls)
    }

    @Test
    fun steamBatchReusesCompletedFinalOwnershipProofAfterPostProofFailure() = runTest {
        val key = key(GameSource.STEAM, "42")
        val app = mockk<SteamApp>()
        every { app.id } returns 42
        every { app.name } throws SensitiveFailure()
        val dao = mockk<SteamAppDao>()
        coEvery { dao._getAllOwnedAppsPaged(any(), any()) } returns listOf(app)
        val runtimeState = mockk<SteamOwnedCopyRuntimeState>()
        coEvery { runtimeState.readBatch(listOf(app), scope, 0L) } returns SteamRuntimeBatchResult(
            states = mapOf(42 to state()),
            failures = emptyMap(),
        )
        val adapter = SteamOwnedCopyRuntimeAdapter(
            dao,
            scopes(GameSource.STEAM),
            readyLifecycle(GameSource.STEAM),
            mockk(relaxed = true),
            batchHistory(),
            runtimeState,
        )

        assertUnavailable(
            adapter.resolveAll(setOf(key)).getValue(key),
            key,
            CopyUnavailableReason.SOURCE_READ_FAILED,
            SensitiveFailure::class,
        )
        coVerify(exactly = 2) { dao._getAllOwnedAppsPaged(any(), any()) }
    }

    @Test
    fun providerBatchesReuseCompletedFinalOwnershipProofAfterPostProofFailure() = runTest {
        fun failingState(): OwnedCopyVolatileState = mockk(relaxed = true) {
            every { installedSizeBytes } throws SensitiveFailure()
        }

        val gogKey = key(GameSource.GOG, "1")
        val gogGame = GOGGame(id = "1", title = "GOG")
        val gogLedger = ledger(GameSource.GOG, mapOf("1" to null))
        val gogState = mockk<GogOwnedCopyRuntimeState>()
        coEvery { gogState.read(listOf(gogGame)) } returns mapOf("1" to failingState())
        val gog = GogOwnedCopyRuntimeAdapter(
            mockk<GOGGameDao> {
                coEvery { getAllAsList() } returns listOf(gogGame)
            },
            scopes(GameSource.GOG),
            gogLedger,
            readyLifecycle(GameSource.GOG),
            mockk(relaxed = true),
            batchHistory(),
            gogState,
        )
        assertUnavailable(
            gog.resolveAll(setOf(gogKey)).getValue(gogKey),
            gogKey,
            CopyUnavailableReason.SOURCE_READ_FAILED,
            SensitiveFailure::class,
        )
        coVerify(exactly = 2) {
            gogLedger.getCompletedSnapshotForLifecycle(scope.value, GameSource.GOG, 0L)
        }

        val epicStableId = EpicStableSourceId.encode("ns", "catalog")
        val epicKey = key(GameSource.EPIC, epicStableId)
        val epicGame = EpicGame(id = 2, namespace = "ns", catalogId = "catalog", title = "Epic")
        val epicLedger = ledger(GameSource.EPIC, mapOf(epicStableId to null))
        val epicState = mockk<EpicOwnedCopyRuntimeState>()
        coEvery { epicState.read(listOf(epicGame)) } returns mapOf(2 to failingState())
        val epic = EpicOwnedCopyRuntimeAdapter(
            mockk<EpicGameDao> {
                coEvery { getAllForCanonicalProjection() } returns listOf(epicGame)
            },
            scopes(GameSource.EPIC),
            epicLedger,
            readyLifecycle(GameSource.EPIC),
            mockk(relaxed = true),
            batchHistory(),
            epicState,
        )
        assertUnavailable(
            epic.resolveAll(setOf(epicKey)).getValue(epicKey),
            epicKey,
            CopyUnavailableReason.SOURCE_READ_FAILED,
            SensitiveFailure::class,
        )
        coVerify(exactly = 2) {
            epicLedger.getCompletedSnapshotForLifecycle(scope.value, GameSource.EPIC, 0L)
        }

        val amazonKey = key(GameSource.AMAZON, "product")
        val amazonGame = AmazonGame(appId = 3, productId = "product", title = "Amazon")
        val amazonLedger = ledger(GameSource.AMAZON, mapOf("product" to "entitlement"))
        val amazonState = mockk<AmazonOwnedCopyRuntimeState>()
        coEvery { amazonState.readBatch(listOf(amazonGame), scope, 0L) } returns
            AmazonRuntimeBatchResult(
                states = mapOf(3 to failingState()),
                failures = emptyMap(),
            )
        val amazon = AmazonOwnedCopyRuntimeAdapter(
            mockk<AmazonGameDao> {
                coEvery { getAllAsList() } returns listOf(amazonGame)
            },
            scopes(GameSource.AMAZON),
            amazonLedger,
            readyLifecycle(GameSource.AMAZON),
            mockk(relaxed = true),
            batchHistory(),
            amazonState,
        )
        assertUnavailable(
            amazon.resolveAll(setOf(amazonKey)).getValue(amazonKey),
            amazonKey,
            CopyUnavailableReason.SOURCE_READ_FAILED,
            SensitiveFailure::class,
        )
        coVerify(exactly = 2) {
            amazonLedger.getCompletedSnapshotForLifecycle(scope.value, GameSource.AMAZON, 0L)
        }

        val customKey = key(GameSource.CUSTOM_GAME, "4")
        val customRow = mockk<CustomOwnedCopyRuntimeRow>(relaxed = true) {
            every { installedSizeBytes } throws SensitiveFailure()
        }
        val customState = mockk<CustomOwnedCopyRuntimeState>()
        coEvery { customState.readTyped(setOf(4)) } returns CustomRuntimeScanResult(
            rows = mapOf(4 to customRow),
        )
        val custom = CustomOwnedCopyRuntimeAdapter(
            scopes(GameSource.CUSTOM_GAME),
            mockk(relaxed = true),
            batchHistory(),
            customState,
        )
        assertUnavailable(
            custom.resolveAll(setOf(customKey)).getValue(customKey),
            customKey,
            CopyUnavailableReason.SOURCE_READ_FAILED,
            SensitiveFailure::class,
        )
        coVerify(exactly = 2) { customState.readTyped(setOf(4)) }
    }

    @Test
    fun pointHistoryAndResolvedOwnershipBatchQueriesPreserveExactIds() = runTest {
        val databaseExecutor = Executors.newSingleThreadExecutor()
        val database = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(databaseExecutor)
            .setTransactionExecutor(databaseExecutor)
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
            databaseExecutor.submit {}.get()
            database.close()
            databaseExecutor.shutdownNow()
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
        val steamDiagnostics = mockk<CanonicalLibraryDiagnosticSink>(relaxed = true)
        val switchedSteam = SteamOwnedCopyRuntimeAdapter(
            steamDao,
            SequencedScopeProvider(listOf(scope, otherScope)),
            readyLifecycle(GameSource.STEAM),
            sourceAdapter(steamKey, SourceOwnedCopyReference.Steam(steamKey, 42)),
            mockk(relaxed = true),
            steamState,
            libraryDiagnostics = steamDiagnostics,
        )
        assertSame(OwnedCopyRuntimeResult.Hidden, switchedSteam.resolve(steamKey))
        verify(exactly = 1) {
            steamDiagnostics.runtimeReadFailed(GameSource.STEAM, SensitiveFailure::class)
        }

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
    fun runtimeDiagnosticSinkFailureDoesNotChangePointReadResult() = runTest {
        val key = key(GameSource.GOG, "123")
        val scopeProvider = mockk<AccountScopeProvider>()
        coEvery { scopeProvider.current(GameSource.GOG) } throws SensitiveFailure()
        val diagnostics = mockk<CanonicalLibraryDiagnosticSink>()
        every { diagnostics.runtimeReadFailed(GameSource.GOG, SensitiveFailure::class) } throws
            IllegalStateException("private diagnostic failure")
        val adapter = GogOwnedCopyRuntimeAdapter(
            gogGameDao = mockk(relaxed = true),
            accountScopeProvider = scopeProvider,
            ownedCopyLedgerDao = mockk(relaxed = true),
            accountLifecycleState = readyLifecycle(GameSource.GOG),
            sourceAdapter = mockk(relaxed = true),
            playHistoryDao = mockk(relaxed = true),
            runtimeState = mockk(relaxed = true),
            libraryDiagnostics = diagnostics,
        )

        assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolve(key))
        verify(exactly = 1) {
            diagnostics.runtimeReadFailed(GameSource.GOG, SensitiveFailure::class)
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
        coEvery { batchState.readBatch(emptyList(), scope, 0L) } returns
            AmazonRuntimeBatchResult(emptyMap(), emptyMap())
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
        coEvery { amazonState.readBatch(listOf(amazonGame), scope, 0L) } returns
            AmazonRuntimeBatchResult(mapOf(7 to state()), emptyMap())
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
        coEvery { runtimeState.readTyped(setOf(5)) } returnsMany listOf(
            CustomRuntimeScanResult(mapOf(5 to customRow(5))),
            CustomRuntimeScanResult(emptyMap()),
        )
        val adapter = CustomOwnedCopyRuntimeAdapter(
            scopes(GameSource.CUSTOM_GAME),
            mockk(relaxed = true),
            batchHistory(),
            runtimeState,
        )

        assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolveAll(setOf(key)).getValue(key))
        coVerify(exactly = 2) { runtimeState.readTyped(setOf(5)) }
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
    fun registryPointRejectsWrongProviderReferencesAndSameSourceExecutableIdsForEverySource() = runTest {
        exactIdentityCases().forEach { identity ->
            val wrongReference = identityRegistry(
                identity.key.source,
                runtimeResult(
                    identity.key,
                    identity.wrongReference,
                    libraryItem(identity.key.source, identity.validLibraryItemId),
                ),
            )

            assertSuspendThrows(IllegalStateException::class.java) {
                wrongReference.registry.resolve(identity.key)
            }
            assertEquals(identity.name, 1, wrongReference.selected.pointCalls)
            assertEquals(identity.name, 0, wrongReference.siblingCalls())

            val wrongLibraryItem = identityRegistry(
                identity.key.source,
                runtimeResult(
                    identity.key,
                    identity.validReference,
                    libraryItem(identity.key.source, identity.wrongLibraryItemId),
                ),
            )

            assertSuspendThrows(IllegalStateException::class.java) {
                wrongLibraryItem.registry.resolve(identity.key)
            }
            assertEquals(identity.name, 1, wrongLibraryItem.selected.pointCalls)
            assertEquals(identity.name, 0, wrongLibraryItem.siblingCalls())
        }
    }

    @Test
    fun registryBatchRejectsWrongProviderReferencesAndSameSourceExecutableIdsForEverySource() = runTest {
        exactIdentityCases().forEach { identity ->
            val wrongReference = identityRegistry(
                identity.key.source,
                runtimeResult(
                    identity.key,
                    identity.wrongReference,
                    libraryItem(identity.key.source, identity.validLibraryItemId),
                ),
            )

            assertSuspendThrows(IllegalStateException::class.java) {
                wrongReference.registry.resolveAll(identity.key.source, setOf(identity.key))
            }
            assertEquals(identity.name, 1, wrongReference.selected.batchCalls)
            assertEquals(identity.name, 0, wrongReference.siblingCalls())

            val wrongLibraryItem = identityRegistry(
                identity.key.source,
                runtimeResult(
                    identity.key,
                    identity.validReference,
                    libraryItem(identity.key.source, identity.wrongLibraryItemId),
                ),
            )

            assertSuspendThrows(IllegalStateException::class.java) {
                wrongLibraryItem.registry.resolveAll(identity.key.source, setOf(identity.key))
            }
            assertEquals(identity.name, 1, wrongLibraryItem.selected.batchCalls)
            assertEquals(identity.name, 0, wrongLibraryItem.siblingCalls())
        }
    }

    @Test
    fun registryAllowsNullExecutableOnlyForExactUnbridgeableGogProviderIds() = runTest {
        listOf("0", "-7", "+7", "007", "2147483648", "not-decimal").forEach { gameId ->
            val gogKey = key(GameSource.GOG, gameId)
            val fixture = identityRegistry(
                GameSource.GOG,
                runtimeResult(
                    gogKey,
                    SourceOwnedCopyReference.Gog(gogKey, gameId),
                    libraryItem = null,
                ),
            )

            assertTrue(fixture.registry.resolve(gogKey) is OwnedCopyRuntimeResult.Available)
        }

        val bridgeableGog = key(GameSource.GOG, "123")
        assertSuspendThrows(IllegalStateException::class.java) {
            identityRegistry(
                GameSource.GOG,
                runtimeResult(
                    bridgeableGog,
                    SourceOwnedCopyReference.Gog(bridgeableGog, "123"),
                    libraryItem = null,
                ),
            ).registry.resolve(bridgeableGog)
        }

        val steam = key(GameSource.STEAM, "42")
        assertSuspendThrows(IllegalStateException::class.java) {
            identityRegistry(
                GameSource.STEAM,
                runtimeResult(
                    steam,
                    SourceOwnedCopyReference.Steam(steam, 42),
                    libraryItem = null,
                ),
            ).registry.resolve(steam)
        }
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
    fun customReadOnlyMetadataAcceptsOnlyExactPositiveCanonicalIntegerIds() {
        assertEquals(1, GameMetadataManager.parseAppIdReadOnly { "{\"appId\":1}" })
        assertEquals(Int.MAX_VALUE, GameMetadataManager.parseAppIdReadOnly { Int.MAX_VALUE.toString() })

        listOf(
            "{\"appId\":1.0}",
            "{\"appId\":1e0}",
            "{\"appId\":2147483648}",
            "{\"appId\":true}",
            "{\"appId\":\"1\"}",
            "+1",
            "01",
            " 1",
            "1 ",
            "2147483648",
        ).forEach { metadata ->
            assertNull(metadata, GameMetadataManager.parseAppIdReadOnly { metadata })
        }
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
        verify(exactly = 1) {
            diagnostics.playHistoryFailed(null, PlayHistoryOrigin.FLOW, SensitiveFailure::class)
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
    fun gogAndEpicRuntimeGatewaysConfineSynchronousServiceSnapshotsToInjectedIo() = runTest {
        val callerThread = Thread.currentThread().name
        val threads = mutableListOf<String>()
        newSingleThreadContext("canonical-runtime-io").use { dispatcher ->
            val gog = GogOwnedCopyRuntimeGateway(
                dispatcher,
                {
                    threads += Thread.currentThread().name
                    RuntimeDownloadSnapshot<String>(emptySet(), emptySet())
                },
                Unit,
            )
            val epic = EpicOwnedCopyRuntimeGateway(
                dispatcher,
                {
                    threads += Thread.currentThread().name
                    RuntimeDownloadSnapshot<Int>(emptySet(), emptySet())
                },
                Unit,
            )

            gog.snapshot()
            epic.snapshot()
        }

        assertEquals(2, threads.size)
        assertTrue(threads.all { it != callerThread && it.contains("canonical-runtime-io") })
    }

    @Test
    fun steamInstallationSnapshotTreatsCompletionMarkersAsAuthorityAndPrefersCompletedDuplicate() = runTest {
        val root = Files.createTempDirectory("steam-runtime-markers").toFile()
        try {
            val partialRoot = File(root, "partial-root").apply { mkdirs() }
            val completedRoot = File(root, "completed-root").apply { mkdirs() }
            val partial = File(partialRoot, "Game").apply { mkdirs() }
            val completed = File(completedRoot, "Game").apply { mkdirs() }
            File(completed, ".download_complete").writeText("")
            val app = SteamApp(id = 1, name = "Game")
            val stale = AppInfo(id = 1, isDownloaded = false)

            val snapshot = SteamInstallationSnapshotReader().read(
                apps = listOf(app),
                installRoots = listOf(partialRoot.path, completedRoot.path),
                appInfos = mapOf(1 to stale),
                activeDownloadIds = emptySet(),
                persistedPartialIds = emptySet(),
                workshopPausedIds = emptySet(),
            ).getValue(1)

            assertTrue(snapshot.isInstalled)
            assertFalse(snapshot.hasPartialDownload)
            assertEquals(completed.path, snapshot.path)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun steamInstallationSnapshotRejectsStaleDownloadedFlagAndRecognizesMarkerOnlyPartialAndWorkshopPause() = runTest {
        val root = Files.createTempDirectory("steam-runtime-partial").toFile()
        try {
            val markerOnly = File(root, "Marker Only").apply { mkdirs() }
            val apps = listOf(
                SteamApp(id = 1, name = "Missing"),
                SteamApp(id = 2, name = "Marker Only"),
                SteamApp(id = 3, name = "Paused"),
            )

            val snapshots = SteamInstallationSnapshotReader().read(
                apps = apps,
                installRoots = listOf(root.path),
                appInfos = mapOf(1 to AppInfo(id = 1, isDownloaded = true)),
                activeDownloadIds = emptySet(),
                persistedPartialIds = emptySet(),
                workshopPausedIds = setOf(3),
            )

            assertFalse(snapshots.getValue(1).isInstalled)
            assertFalse(snapshots.getValue(1).hasPartialDownload)
            assertFalse(snapshots.getValue(2).isInstalled)
            assertTrue(snapshots.getValue(2).hasPartialDownload)
            assertEquals(markerOnly.path, snapshots.getValue(2).path)
            assertTrue(snapshots.getValue(3).hasPartialDownload)
            assertTrue(
                OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD in capabilities(
                    GameSource.STEAM,
                    libraryItemPresent = true,
                    state = OwnedCopyVolatileState(
                        hasPartialDownload = snapshots.getValue(3).hasPartialDownload,
                    ),
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun steamBatchStateSnapshotsEachProductionFacetOnceWithoutLiveUpdateProbes() = runTest {
        val apps = listOf(
            SteamApp(id = 1, name = "Installed"),
            SteamApp(id = 2, name = "Partial"),
        )
        val installedInfo = AppInfo(id = 1, isDownloaded = false, branch = "beta")
        val installations = mapOf(
            1 to SteamInstallationState("/installed", isInstalled = true, hasPartialDownload = false),
            2 to SteamInstallationState("/partial", isInstalled = false, hasPartialDownload = true),
        )
        val gateway = mockk<SteamOwnedCopyRuntimeGateway>()
        coEvery { gateway.activeDownloadIds() } returns setOf(2)
        coEvery { gateway.partialDownloadIds() } returns setOf(2)
        coEvery { gateway.installedApps() } returns mapOf(1 to installedInfo)
        coEvery { gateway.licensedDepotIds(apps) } returns emptyMap()
        coEvery {
            gateway.installationSnapshot(apps, mapOf(1 to installedInfo), setOf(2), setOf(2))
        } returns installations
        val observed = SteamObservedUpdateState(gateway, backgroundScope) { 0L }
        val runtimeState = SteamOwnedCopyRuntimeState(gateway, observed)

        val states = runtimeState.readBatch(apps, scope, 0L).states

        assertTrue(states.getValue(1).isInstalled)
        assertEquals("beta", states.getValue(1).branchOrVersion)
        assertTrue(states.getValue(2).isDownloading)
        assertTrue(states.getValue(2).hasPartialDownload)
        assertFalse(states.getValue(1).updateAvailable)
        coVerify(exactly = 1) { gateway.activeDownloadIds() }
        coVerify(exactly = 1) { gateway.partialDownloadIds() }
        coVerify(exactly = 1) { gateway.installedApps() }
        coVerify(exactly = 1) { gateway.licensedDepotIds(apps) }
        coVerify(exactly = 1) {
            gateway.installationSnapshot(apps, mapOf(1 to installedInfo), setOf(2), setOf(2))
        }
        coVerify(exactly = 0) { gateway.refreshUpdates(any()) }
    }

    @Test
    fun steamColdUpdateIsUnknownThenBatchRefreshInvalidatesAndBecomesTrue() = runTest {
        val app = SteamApp(id = 1, name = "Installed")
        val installed = AppInfo(id = 1, isDownloaded = true)
        val gateway = mockk<SteamOwnedCopyRuntimeGateway>()
        coEvery { gateway.activeDownloadIds() } returns emptySet()
        coEvery { gateway.partialDownloadIds() } returns emptySet()
        coEvery { gateway.installedApps() } returns mapOf(1 to installed)
        coEvery { gateway.licensedDepotIds(listOf(app)) } returns emptyMap()
        coEvery {
            gateway.installationSnapshot(
                listOf(app),
                mapOf(1 to installed),
                emptySet(),
                emptySet(),
            )
        } returns mapOf(
            1 to SteamInstallationState("/installed", isInstalled = true, hasPartialDownload = false),
        )
        coEvery { gateway.refreshUpdates(any()) } answers {
            (invocation.args[0] as List<*>)
                .filterIsInstance<SteamUpdateRefreshRequest>()
                .associate {
                    it.app.id to UpdateRefreshOutcome.Observed(updateAvailable = true)
                }
        }
        val observed = SteamObservedUpdateState(
            gateway = gateway,
            scope = backgroundScope,
            nowMonotonicMs = { 0L },
        )
        val runtimeState = SteamOwnedCopyRuntimeState(gateway, observed)
        val invalidated = async(start = CoroutineStart.UNDISPATCHED) {
            runtimeState.updateInvalidations().first()
        }

        val cold = runtimeState.readBatch(listOf(app), scope, 0L).states.getValue(1)
        assertEquals(UpdateObservation.UNKNOWN, cold.updateObservation)
        assertFalse(cold.updateAvailable)
        runCurrent()

        assertEquals(Unit, invalidated.await())
        val refreshed = runtimeState.readBatch(listOf(app), scope, 0L).states.getValue(1)
        assertEquals(UpdateObservation.UPDATE_AVAILABLE, refreshed.updateObservation)
        assertTrue(refreshed.updateAvailable)
        coVerify(exactly = 1) { gateway.refreshUpdates(any()) }
    }

    @Test
    fun steamRuntimeExpandsInstalledDlcAppIdsToTheirDownloadedDepots() = runTest {
        val parent = SteamApp(
            id = 1,
            name = "Parent",
            depots = mapOf(100 to steamDepot(100, manifestGid = 10L)),
        )
        val dlc = SteamApp(
            id = 2,
            name = "DLC",
            dlcForAppId = parent.id,
            depots = mapOf(
                200 to steamDepot(200, manifestGid = 20L, language = "french"),
            ),
        )
        val remoteParent = parent.copy(
            depots = mapOf(100 to steamDepot(100, manifestGid = 10L)),
        )
        val remoteDlc = dlc.copy(
            depots = mapOf(
                200 to steamDepot(200, manifestGid = 21L, language = "french"),
            ),
        )
        val appInfos = mapOf(
            parent.id to AppInfo(
                id = parent.id,
                isDownloaded = true,
                downloadedDepots = listOf(100),
                dlcDepots = listOf(dlc.id),
            ),
            dlc.id to AppInfo(
                id = dlc.id,
                isDownloaded = true,
                downloadedDepots = listOf(200),
            ),
        )
        val gateway = mockk<SteamOwnedCopyRuntimeGateway>()
        coEvery { gateway.activeDownloadIds() } returns emptySet()
        coEvery { gateway.partialDownloadIds() } returns emptySet()
        coEvery { gateway.installedApps() } returns appInfos
        coEvery { gateway.licensedDepotIds(listOf(parent)) } returns emptyMap()
        coEvery {
            gateway.installationSnapshot(
                listOf(parent),
                appInfos,
                emptySet(),
                emptySet(),
            )
        } returns mapOf(
            parent.id to SteamInstallationState(
                path = "/installed",
                isInstalled = true,
                hasPartialDownload = false,
            ),
        )
        coEvery { gateway.refreshUpdates(any()) } answers {
            val request = (invocation.args[0] as List<*>)
                .filterIsInstance<SteamUpdateRefreshRequest>()
                .single()
            assertEquals(setOf(100, 200), request.installedDepotIds)
            val result = SteamService.getUpdatePendingFromSnapshots(
                localApp = parent,
                branch = "public",
                preferredLanguage = "english",
                ownedDlcApps = listOf(dlc),
                licensedDepotIds = emptyMap(),
                installedDepotIds = request.installedDepotIds,
                remoteApps = mapOf(parent.id to remoteParent, dlc.id to remoteDlc),
            )
            mapOf(
                parent.id to when (result) {
                    is SteamUpdateCheckResult.Failed ->
                        UpdateRefreshOutcome.Failed(result.errorClass)
                    is SteamUpdateCheckResult.Observed ->
                        UpdateRefreshOutcome.Observed(result.updateAvailable)
                },
            )
        }
        val runtimeState = SteamOwnedCopyRuntimeState(
            gateway,
            SteamObservedUpdateState(gateway, backgroundScope) { testScheduler.currentTime },
        )

        assertEquals(
            UpdateObservation.UNKNOWN,
            runtimeState.readBatch(listOf(parent), scope, 0L)
                .states.getValue(parent.id).updateObservation,
        )
        runCurrent()

        val refreshed = runtimeState.readBatch(listOf(parent), scope, 0L)
            .states.getValue(parent.id)
        assertEquals(UpdateObservation.UPDATE_AVAILABLE, refreshed.updateObservation)
        assertTrue(refreshed.updateAvailable)
        coVerify(exactly = 1) { gateway.refreshUpdates(any()) }
    }

    @Test
    fun steamCompleteEmptyRuntimeSnapshotPrunesObservedStateWithoutProviderReads() = runTest {
        val app = SteamApp(id = 1, name = "Installed")
        val appInfo = AppInfo(id = app.id, isDownloaded = true)
        val installations = mapOf(
            app.id to SteamInstallationState(
                path = "/installed",
                isInstalled = true,
                hasPartialDownload = false,
            ),
        )
        val gateway = mockk<SteamOwnedCopyRuntimeGateway>()
        coEvery { gateway.activeDownloadIds() } returns emptySet()
        coEvery { gateway.partialDownloadIds() } returns emptySet()
        coEvery { gateway.installedApps() } returns mapOf(app.id to appInfo)
        coEvery { gateway.licensedDepotIds(listOf(app)) } returns emptyMap()
        coEvery {
            gateway.installationSnapshot(
                listOf(app),
                mapOf(app.id to appInfo),
                emptySet(),
                emptySet(),
            )
        } returns installations
        coEvery { gateway.refreshUpdates(any()) } returns mapOf(
            app.id to UpdateRefreshOutcome.Observed(updateAvailable = false),
        )
        val runtimeState = SteamOwnedCopyRuntimeState(
            gateway,
            SteamObservedUpdateState(gateway, backgroundScope) { testScheduler.currentTime },
        )

        runtimeState.readBatch(listOf(app), scope, 0L)
        runCurrent()
        runtimeState.readBatch(emptyList(), scope, 0L)
        val observedOwnerJob = backgroundScope.coroutineContext[Job]
            ?.children
            ?.single { it.isActive }
        assertEquals(0, observedOwnerJob?.children?.count { it.isActive })
        advanceTimeBy(5 * 60_000L + 1L)
        runCurrent()

        coVerify(exactly = 1) { gateway.activeDownloadIds() }
        coVerify(exactly = 1) { gateway.partialDownloadIds() }
        coVerify(exactly = 1) { gateway.installedApps() }
        coVerify(exactly = 1) { gateway.licensedDepotIds(listOf(app)) }
        coVerify(exactly = 1) { gateway.installationSnapshot(any(), any(), any(), any()) }
        coVerify(exactly = 1) { gateway.refreshUpdates(any()) }
    }

    @Test
    fun steamObservedRefreshReportsTypedProviderFailure() = runTest {
        val app = SteamApp(id = 1, name = "Installed")
        val gateway = mockk<SteamOwnedCopyRuntimeGateway>()
        coEvery { gateway.refreshUpdates(any()) } throws SensitiveFailure()
        val diagnostics = mockk<CanonicalDiagnosticSink>(relaxed = true)
        val observed = SteamObservedUpdateState(
            gateway = gateway,
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            diagnostics = diagnostics,
        )

        observed.snapshot(
            UpdateObservationOwner(scope, 1L),
            listOf(app),
            SteamRuntimeInputs(
                activeDownloadIds = emptySet(),
                appInfos = mapOf(1 to AppInfo(id = 1, isDownloaded = true)),
                licensedDepotIds = emptyMap(),
                installations = mapOf(
                    1 to SteamInstallationState(
                        path = "/installed",
                        isInstalled = true,
                        hasPartialDownload = false,
                    ),
                ),
            ),
        )
        runCurrent()

        verify(exactly = 1) {
            diagnostics.updateObservationFailed(GameSource.STEAM, SensitiveFailure::class)
        }
    }

    @Test
    fun steamMissingProviderObservationRemainsUnknownAndReportsTypedFailure() = runTest {
        val app = SteamApp(id = 1, name = "Installed")
        val gateway = mockk<SteamOwnedCopyRuntimeGateway>()
        coEvery { gateway.refreshUpdates(any()) } returns emptyMap()
        val diagnostics = mockk<CanonicalDiagnosticSink>(relaxed = true)
        val observed = SteamObservedUpdateState(
            gateway = gateway,
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            diagnostics = diagnostics,
        )
        val owner = UpdateObservationOwner(scope, 1L)
        val inputs = SteamRuntimeInputs(
            activeDownloadIds = emptySet(),
            appInfos = mapOf(1 to AppInfo(id = 1, isDownloaded = true)),
            licensedDepotIds = emptyMap(),
            installations = mapOf(
                1 to SteamInstallationState(
                    path = "/installed",
                    isInstalled = true,
                    hasPartialDownload = false,
                ),
            ),
        )

        observed.snapshot(owner, listOf(app), inputs)
        runCurrent()

        assertEquals(UpdateObservation.UNKNOWN, observed.snapshot(owner, listOf(app), inputs)[1])
        verify(exactly = 1) {
            diagnostics.updateObservationFailed(
                GameSource.STEAM,
                MissingUpdateObservationException::class,
            )
        }
    }

    @Test
    fun steamObservedRefreshIncludesOnlyMarkerAuthoritativeInstalledCopies() = runTest {
        val installedApp = SteamApp(id = 1, name = "Installed")
        val partialApp = SteamApp(id = 2, name = "Partial")
        val apps = listOf(installedApp, partialApp)
        val appInfos = mapOf(
            1 to AppInfo(id = 1, isDownloaded = false),
            2 to AppInfo(id = 2, isDownloaded = true),
        )
        val installations = mapOf(
            1 to SteamInstallationState("/installed", isInstalled = true, hasPartialDownload = false),
            2 to SteamInstallationState("/partial", isInstalled = false, hasPartialDownload = true),
        )
        val gateway = mockk<SteamOwnedCopyRuntimeGateway>()
        coEvery { gateway.activeDownloadIds() } returns emptySet()
        coEvery { gateway.partialDownloadIds() } returns setOf(2)
        coEvery { gateway.installedApps() } returns appInfos
        coEvery { gateway.licensedDepotIds(apps) } returns emptyMap()
        coEvery {
            gateway.installationSnapshot(apps, appInfos, emptySet(), setOf(2))
        } returns installations
        coEvery { gateway.refreshUpdates(any()) } answers {
            (invocation.args[0] as List<*>)
                .filterIsInstance<SteamUpdateRefreshRequest>()
                .associate {
                    it.app.id to UpdateRefreshOutcome.Observed(updateAvailable = true)
                }
        }
        val runtimeState = SteamOwnedCopyRuntimeState(
            gateway,
            SteamObservedUpdateState(gateway, backgroundScope) { 0L },
        )

        val cold = runtimeState.readBatch(apps, scope, 0L).states

        assertEquals(UpdateObservation.UNKNOWN, cold.getValue(1).updateObservation)
        assertEquals(UpdateObservation.CURRENT, cold.getValue(2).updateObservation)
        runCurrent()
        assertEquals(
            UpdateObservation.CURRENT,
            runtimeState.readBatch(apps, scope, 0L).states.getValue(2).updateObservation,
        )
        coVerify(exactly = 1) {
            gateway.refreshUpdates(match { requests -> requests.map { it.app.id } == listOf(1) })
        }
    }

    @Test
    fun steamPointAndBatchShareObservedStateWhileScopeSwitchReturnsUnknown() = runTest {
        val app = SteamApp(id = 1, name = "Installed")
        val installed = AppInfo(id = 1, isDownloaded = true)
        val gateway = mockk<SteamOwnedCopyRuntimeGateway>()
        coEvery { gateway.activeDownloadIds() } returns emptySet()
        coEvery { gateway.partialDownloadIds() } returns emptySet()
        coEvery { gateway.installedApps() } returns mapOf(1 to installed)
        coEvery { gateway.licensedDepotIds(listOf(app)) } returns emptyMap()
        coEvery {
            gateway.installationSnapshot(
                listOf(app),
                mapOf(1 to installed),
                emptySet(),
                emptySet(),
            )
        } returns mapOf(
            1 to SteamInstallationState("/installed", isInstalled = true, hasPartialDownload = false),
        )
        coEvery { gateway.refreshUpdates(any()) } returns mapOf(
            1 to UpdateRefreshOutcome.Observed(updateAvailable = true),
        )
        val observed = SteamObservedUpdateState(gateway, backgroundScope) { 0L }
        val runtimeState = SteamOwnedCopyRuntimeState(gateway, observed)

        assertEquals(UpdateObservation.UNKNOWN, runtimeState.readPoint(app, scope, 0L).updateObservation)
        runCurrent()
        assertEquals(
            UpdateObservation.UPDATE_AVAILABLE,
            runtimeState.readBatch(listOf(app), scope, 0L).states.getValue(1).updateObservation,
        )
        assertEquals(
            UpdateObservation.UNKNOWN,
            runtimeState.readBatch(listOf(app), otherScope, 0L).states.getValue(1).updateObservation,
        )

        coVerify(exactly = 1) { gateway.refreshUpdates(any()) }
    }

    @Test
    fun steamBranchFingerprintChangesConvergeUpdateStateInBothDirections() = runTest {
        val app = SteamApp(id = 1, name = "Installed")
        var appInfo = AppInfo(id = 1, isDownloaded = false, branch = "beta-a")
        val refreshedBranches = mutableListOf<String>()
        val refreshResults = ArrayDeque(listOf(true, false))
        val gateway = mockk<SteamOwnedCopyRuntimeGateway>()
        coEvery { gateway.activeDownloadIds() } returns emptySet()
        coEvery { gateway.partialDownloadIds() } returns emptySet()
        coEvery { gateway.installedApps() } answers { mapOf(1 to appInfo) }
        coEvery { gateway.licensedDepotIds(listOf(app)) } returns emptyMap()
        coEvery {
            gateway.installationSnapshot(listOf(app), any(), emptySet(), emptySet())
        } returns mapOf(
            1 to SteamInstallationState("/installed", isInstalled = true, hasPartialDownload = false),
        )
        coEvery { gateway.refreshUpdates(any()) } answers {
            val request = (invocation.args[0] as List<*>)
                .filterIsInstance<SteamUpdateRefreshRequest>()
                .single()
            refreshedBranches += request.branch
            mapOf(
                1 to UpdateRefreshOutcome.Observed(refreshResults.removeFirst()),
            )
        }
        val runtimeState = SteamOwnedCopyRuntimeState(
            gateway,
            SteamObservedUpdateState(gateway, backgroundScope) { 0L },
        )

        assertEquals(UpdateObservation.UNKNOWN, runtimeState.readPoint(app, scope, 0L).updateObservation)
        runCurrent()
        assertEquals(
            UpdateObservation.UPDATE_AVAILABLE,
            runtimeState.readBatch(listOf(app), scope, 0L).states.getValue(1).updateObservation,
        )

        appInfo = appInfo.copy(branch = "beta-b")
        assertEquals(UpdateObservation.UNKNOWN, runtimeState.readPoint(app, scope, 0L).updateObservation)
        runCurrent()
        assertEquals(UpdateObservation.CURRENT, runtimeState.readPoint(app, scope, 0L).updateObservation)
        assertEquals(listOf("beta-a", "beta-b"), refreshedBranches)
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
        val observed = AmazonObservedUpdateState(gateway, backgroundScope) { 0L }
        val runtimeState = AmazonOwnedCopyRuntimeState(gateway, observed)

        val states = runtimeState.readBatch(games, scope, 0L)

        assertTrue(states.getValue(1).isInstalled)
        assertTrue(states.getValue(2).isDownloading)
        assertTrue(states.getValue(2).hasPartialDownload)
        assertFalse(states.getValue(1).updateAvailable)
        coVerify(exactly = 1) { gateway.activeDownloadProductIds() }
        coVerify(exactly = 1) { gateway.partialDownloadProductIds() }
        coVerify(exactly = 0) { gateway.refreshUpdates(any(), any()) }
    }

    @Test
    fun amazonCompleteEmptyRuntimeSnapshotPrunesObservedStateWithoutProviderReads() = runTest {
        val game = AmazonGame(
            appId = 1,
            productId = "installed",
            versionId = "v1",
            isInstalled = true,
        )
        val gateway = mockk<AmazonOwnedCopyRuntimeGateway>()
        coEvery { gateway.activeDownloadProductIds() } returns emptySet()
        coEvery { gateway.partialDownloadProductIds() } returns emptySet()
        coEvery { gateway.refreshUpdates(any(), any()) } returns mapOf(
            game.productId to UpdateRefreshOutcome.Observed(updateAvailable = false),
        )
        val runtimeState = AmazonOwnedCopyRuntimeState(
            gateway,
            AmazonObservedUpdateState(gateway, backgroundScope) { testScheduler.currentTime },
        )

        runtimeState.readBatch(listOf(game), scope, 0L)
        runCurrent()
        runtimeState.readBatch(emptyList(), scope, 0L)
        val observedOwnerJob = backgroundScope.coroutineContext[Job]
            ?.children
            ?.single { it.isActive }
        assertEquals(0, observedOwnerJob?.children?.count { it.isActive })
        advanceTimeBy(5 * 60_000L + 1L)
        runCurrent()

        coVerify(exactly = 1) { gateway.activeDownloadProductIds() }
        coVerify(exactly = 1) { gateway.partialDownloadProductIds() }
        coVerify(exactly = 1) { gateway.refreshUpdates(any(), any()) }
    }

    @Test
    fun amazonObservedUpdateRechecksAccountAuthorityBeforeBatchProviderIo() = runTest {
        val gateway = mockk<AmazonOwnedCopyRuntimeGateway>(relaxed = true)
        val owner = UpdateObservationOwner(scope, 1L)
        var authoritative = true
        val observed = AmazonObservedUpdateState(
            gateway = gateway,
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            isOwnerCurrent = { authoritative },
        )
        val game = AmazonGame(
            appId = 1,
            productId = "old-account-product",
            versionId = "v1",
            isInstalled = true,
        )

        assertEquals(UpdateObservation.UNKNOWN, observed.snapshot(owner, listOf(game))[game.productId])
        authoritative = false
        runCurrent()

        coVerify(exactly = 0) { gateway.refreshUpdates(any(), any()) }
    }

    @Test
    fun amazonLifecycleRetirementCancelsActiveObservedProviderIo() = runTest {
        val gateway = mockk<AmazonOwnedCopyRuntimeGateway>()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { gateway.refreshUpdates(any(), any()) } coAnswers {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val retirements = MutableSharedFlow<UpdateObservationLifecycle>(extraBufferCapacity = 1)
        val observed = AmazonObservedUpdateState(
            gateway = gateway,
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            isOwnerCurrent = { true },
            retirements = retirements,
        )
        val owner = UpdateObservationOwner(scope, 1L)
        val game = AmazonGame(
            appId = 1,
            productId = "product",
            versionId = "version",
            isInstalled = true,
        )

        observed.snapshot(owner, listOf(game))
        runCurrent()
        assertEquals(Unit, started.await())
        retirements.emit(UpdateObservationLifecycle(otherScope, 2L))
        runCurrent()

        assertEquals(Unit, cancelled.await())
    }

    @Test
    fun amazonObservedRefreshUsesOneBatchGatewayCallAndPreservesPerKeyFailure() = runTest {
        val games = listOf(
            AmazonGame(appId = 1, productId = "product-a", versionId = "v1", isInstalled = true),
            AmazonGame(appId = 2, productId = "product-b", versionId = "v2", isInstalled = true),
        )
        val owner = UpdateObservationOwner(scope, 1L)
        val gateway = mockk<AmazonOwnedCopyRuntimeGateway>()
        coEvery { gateway.refreshUpdates(owner, any()) } returns mapOf(
            "product-a" to UpdateRefreshOutcome.Observed(updateAvailable = true),
            "product-b" to UpdateRefreshOutcome.Failed(SensitiveFailure::class),
        )
        val observed = AmazonObservedUpdateState(gateway, backgroundScope) { 0L }

        observed.snapshot(owner, games)
        runCurrent()

        val state = observed.snapshot(owner, games)
        assertEquals(UpdateObservation.UPDATE_AVAILABLE, state["product-a"])
        assertEquals(UpdateObservation.UNKNOWN, state["product-b"])
        coVerify(exactly = 1) { gateway.refreshUpdates(owner, any()) }
    }

    @Test
    fun amazonObservedRefreshReportsTypedProviderFailure() = runTest {
        val game = AmazonGame(
            appId = 1,
            productId = "product",
            versionId = "version",
            isInstalled = true,
        )
        val gateway = mockk<AmazonOwnedCopyRuntimeGateway>()
        coEvery { gateway.refreshUpdates(any(), any()) } returns mapOf(
            game.productId to UpdateRefreshOutcome.Failed(SensitiveFailure::class),
        )
        val diagnostics = mockk<CanonicalDiagnosticSink>(relaxed = true)
        val observed = AmazonObservedUpdateState(
            gateway = gateway,
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            diagnostics = diagnostics,
        )

        observed.snapshot(UpdateObservationOwner(scope, 1L), listOf(game))
        runCurrent()

        verify(exactly = 1) {
            diagnostics.updateObservationFailed(GameSource.AMAZON, SensitiveFailure::class)
        }
    }

    @Test
    fun amazonColdUpdateRefreshRunsOutsideAssemblyThenInvalidatesTrue() = runTest {
        val game = AmazonGame(
            appId = 1,
            productId = "product",
            versionId = "version-a",
            isInstalled = true,
        )
        val gateway = mockk<AmazonOwnedCopyRuntimeGateway>()
        coEvery { gateway.activeDownloadProductIds() } returns emptySet()
        coEvery { gateway.partialDownloadProductIds() } returns emptySet()
        coEvery { gateway.refreshUpdates(any(), any()) } answers {
            (invocation.args[1] as List<*>)
                .filterIsInstance<UpdateObservationRequest<String, String>>()
                .associate { request ->
                    request.key to UpdateRefreshOutcome.Observed(updateAvailable = true)
                }
        }
        val observed = AmazonObservedUpdateState(
            gateway = gateway,
            scope = backgroundScope,
            nowMonotonicMs = { 0L },
        )
        val runtimeState = AmazonOwnedCopyRuntimeState(gateway, observed)
        val invalidated = async(start = CoroutineStart.UNDISPATCHED) {
            runtimeState.updateInvalidations().first()
        }

        val cold = runtimeState.readBatch(listOf(game), scope, 0L).getValue(1)
        assertEquals(UpdateObservation.UNKNOWN, cold.updateObservation)
        coVerify(exactly = 0) { gateway.refreshUpdates(any(), any()) }
        runCurrent()

        assertEquals(Unit, invalidated.await())
        val refreshed = runtimeState.readBatch(listOf(game), scope, 0L).getValue(1)
        assertEquals(UpdateObservation.UPDATE_AVAILABLE, refreshed.updateObservation)
        coVerify(exactly = 1) { gateway.refreshUpdates(any(), any()) }
    }

    @Test
    fun amazonObservedStorageRetainsFifteenHundredInstalledCopies() = runTest {
        val games = (1..1_500).map { id ->
            AmazonGame(
                appId = id,
                productId = "product-$id",
                versionId = "v1",
                isInstalled = true,
            )
        }
        val queried = mutableSetOf<String>()
        val gateway = mockk<AmazonOwnedCopyRuntimeGateway>()
        coEvery { gateway.refreshUpdates(any(), any()) } answers {
            (invocation.args[1] as List<*>)
                .filterIsInstance<UpdateObservationRequest<String, String>>()
                .associate { request ->
                    queried += request.key
                    request.key to UpdateRefreshOutcome.Observed(updateAvailable = false)
                }
        }
        val observed = AmazonObservedUpdateState(gateway, backgroundScope) { 0L }

        observed.snapshot(UpdateObservationOwner(scope, 1L), games)
        runCurrent()

        assertEquals(1_500, queried.size)
    }

    @Test
    fun amazonObservedRefreshUsesSequentialBoundedBatchWaves() = runTest {
        val games = (1..40).map { id ->
            AmazonGame(
                appId = id,
                productId = "product-$id",
                versionId = "version",
                isInstalled = true,
            )
        }
        val gate = CompletableDeferred<Unit>()
        val waveSizes = mutableListOf<Int>()
        val gateway = mockk<AmazonOwnedCopyRuntimeGateway>()
        coEvery { gateway.refreshUpdates(any(), any()) } coAnswers {
            val requests = (invocation.args[1] as List<*>)
                .filterIsInstance<UpdateObservationRequest<String, String>>()
            waveSizes += requests.size
            if (waveSizes.size == 1) gate.await()
            requests.associate { request ->
                request.key to UpdateRefreshOutcome.Observed(updateAvailable = false)
            }
        }
        val observed = AmazonObservedUpdateState(gateway, backgroundScope) { 0L }
        val owner = UpdateObservationOwner(scope, 0L)

        observed.snapshot(owner, games)
        observed.snapshot(owner, games)
        runCurrent()
        assertEquals(listOf(32), waveSizes)

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(32, 8), waveSizes)
        coVerify(exactly = 2) { gateway.refreshUpdates(any(), any()) }
    }

    @Test
    fun amazonPointAndBatchShareObservedStateAndVersionFingerprintConvergesBothDirections() = runTest {
        val versionA = AmazonGame(
            appId = 1,
            productId = "product",
            versionId = "version-a",
            isInstalled = true,
        )
        val versionB = versionA.copy(versionId = "version-b")
        val versionC = versionA.copy(versionId = "version-c")
        val results = ArrayDeque(listOf(true, false, true))
        val gateway = mockk<AmazonOwnedCopyRuntimeGateway>()
        coEvery { gateway.activeDownloadProductIds() } returns emptySet()
        coEvery { gateway.partialDownloadProductIds() } returns emptySet()
        coEvery { gateway.refreshUpdates(any(), any()) } answers {
            (invocation.args[1] as List<*>)
                .filterIsInstance<UpdateObservationRequest<String, String>>()
                .associate { request ->
                    request.key to UpdateRefreshOutcome.Observed(results.removeFirst())
                }
        }
        val observed = AmazonObservedUpdateState(gateway, backgroundScope) { 0L }
        val runtimeState = AmazonOwnedCopyRuntimeState(gateway, observed)

        assertEquals(UpdateObservation.UNKNOWN, runtimeState.readPoint(versionA, scope, 0L).updateObservation)
        runCurrent()
        assertEquals(
            UpdateObservation.UPDATE_AVAILABLE,
            runtimeState.readBatch(listOf(versionA), scope, 0L).getValue(1).updateObservation,
        )
        assertEquals(
            UpdateObservation.UNKNOWN,
            runtimeState.readBatch(listOf(versionB), scope, 0L).getValue(1).updateObservation,
        )
        runCurrent()
        assertEquals(
            UpdateObservation.CURRENT,
            runtimeState.readBatch(listOf(versionB), scope, 0L).getValue(1).updateObservation,
        )
        assertEquals(
            UpdateObservation.UNKNOWN,
            runtimeState.readBatch(listOf(versionC), scope, 0L).getValue(1).updateObservation,
        )
        runCurrent()
        assertEquals(
            UpdateObservation.UPDATE_AVAILABLE,
            runtimeState.readBatch(listOf(versionC), scope, 0L).getValue(1).updateObservation,
        )

        coVerify(exactly = 3) { gateway.refreshUpdates(any(), any()) }
    }

    @Test
    fun steamTypedBatchMapperFailureDoesNotDisableSuccessfulSibling() = runTest {
        val keys = setOf(key(GameSource.STEAM, "1"), key(GameSource.STEAM, "2"))
        val rows = listOf(SteamApp(id = 1, name = "Available"), SteamApp(id = 2, name = "Failed"))
        val dao = mockk<SteamAppDao>()
        coEvery { dao._getAllOwnedAppsPaged(any(), any()) } returns rows
        val runtimeState = mockk<SteamOwnedCopyRuntimeState>()
        coEvery { runtimeState.readBatch(rows, scope, 0L) } returns SteamRuntimeBatchResult(
            states = mapOf(1 to state()),
            failures = mapOf(2 to SensitiveFailure::class),
        )
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
            CopyUnavailableReason.SOURCE_READ_FAILED,
            SensitiveFailure::class,
        )
    }

    @Test
    fun steamProductionBatchMapperTypesPerCopyFailureWithoutDroppingSibling() = runTest {
        val apps = listOf(
            SteamApp(id = 1, name = "Available"),
            SteamApp(id = 2, name = "Failed"),
        )
        val gateway = mockk<SteamOwnedCopyRuntimeGateway>()
        coEvery { gateway.activeDownloadIds() } returns emptySet()
        coEvery { gateway.partialDownloadIds() } returns emptySet()
        coEvery { gateway.installedApps() } returns emptyMap()
        coEvery { gateway.licensedDepotIds(apps) } returns emptyMap()
        coEvery {
            gateway.installationSnapshot(apps, emptyMap(), emptySet(), emptySet())
        } returns mapOf(
            1 to SteamInstallationState(null, isInstalled = false, hasPartialDownload = false),
        )
        coEvery { gateway.refreshUpdates(any()) } returns emptyMap()
        val runtimeState = SteamOwnedCopyRuntimeState(
            gateway,
            SteamObservedUpdateState(gateway, backgroundScope) { 0L },
        )

        val result = runtimeState.readBatch(apps, scope, 0L)

        assertEquals(setOf(1), result.states.keys)
        assertEquals(NoSuchElementException::class, result.failures.getValue(2))
    }

    @Test
    fun amazonProductionBatchMapperTypesPerCopyFailureWithoutDroppingSibling() = runTest {
        val games = listOf(
            AmazonGame(appId = 1, productId = "healthy", title = "Available"),
            AmazonGame(appId = 2, productId = "malformed", title = "Failed"),
        )
        val activeIds = object : AbstractSet<String>() {
            override val size: Int = 0
            override fun iterator(): Iterator<String> = emptySet<String>().iterator()
            override fun contains(element: String): Boolean {
                if (element == "malformed") throw SensitiveFailure()
                return false
            }
        }
        val gateway = mockk<AmazonOwnedCopyRuntimeGateway>()
        coEvery { gateway.activeDownloadProductIds() } returns activeIds
        coEvery { gateway.partialDownloadProductIds() } returns emptySet()
        val runtimeState = AmazonOwnedCopyRuntimeState(
            gateway,
            AmazonObservedUpdateState(gateway, backgroundScope) { 0L },
        )

        val result = runtimeState.readBatch(games, scope, 0L)

        assertEquals(setOf(1), result.states.keys)
        assertEquals(SensitiveFailure::class, result.failures.getValue(2))
    }

    @Test
    fun amazonTypedBatchMapperFailureIsSourceReadFailedForFreshKey() = runTest {
        val healthyKey = key(GameSource.AMAZON, "healthy")
        val failedKey = key(GameSource.AMAZON, "malformed")
        val rows = listOf(
            AmazonGame(appId = 1, productId = "healthy", title = "Available"),
            AmazonGame(appId = 2, productId = "malformed", title = "Failed"),
        )
        val dao = mockk<AmazonGameDao>()
        coEvery { dao.getAllAsList() } returns rows
        val ledger = ledger(
            GameSource.AMAZON,
            mapOf("healthy" to "entitlement-a", "malformed" to "entitlement-b"),
        )
        val runtimeState = mockk<AmazonOwnedCopyRuntimeState>()
        coEvery { runtimeState.readBatch(rows, scope, 0L) } returns AmazonRuntimeBatchResult(
            states = mapOf(1 to state()),
            failures = mapOf(2 to SensitiveFailure::class),
        )
        val adapter = AmazonOwnedCopyRuntimeAdapter(
            dao,
            scopes(GameSource.AMAZON),
            ledger,
            readyLifecycle(GameSource.AMAZON),
            mockk(relaxed = true),
            batchHistory(),
            runtimeState,
        )

        val results = adapter.resolveAll(setOf(healthyKey, failedKey))

        available(results.getValue(healthyKey))
        assertUnavailable(
            results.getValue(failedKey),
            failedKey,
            CopyUnavailableReason.SOURCE_READ_FAILED,
            SensitiveFailure::class,
        )
    }

    @Test
    fun steamMissingBatchStateWithoutTypedFailureRemainsSourceRowChanged() = runTest {
        val key = key(GameSource.STEAM, "2")
        val row = SteamApp(id = 2, name = "Changed")
        val dao = mockk<SteamAppDao>()
        coEvery { dao._getAllOwnedAppsPaged(any(), any()) } returns listOf(row)
        val runtimeState = mockk<SteamOwnedCopyRuntimeState>()
        coEvery { runtimeState.readBatch(listOf(row), scope, 0L) } returns SteamRuntimeBatchResult(
            states = emptyMap(),
            failures = emptyMap(),
        )
        val adapter = SteamOwnedCopyRuntimeAdapter(
            dao,
            scopes(GameSource.STEAM),
            readyLifecycle(GameSource.STEAM),
            mockk(relaxed = true),
            batchHistory(),
            runtimeState,
        )

        assertUnavailable(
            adapter.resolveAll(setOf(key)).getValue(key),
            key,
            CopyUnavailableReason.SOURCE_ROW_CHANGED,
        )
    }

    @Test
    fun steamFatalBatchMapperErrorPropagates() = runTest {
        val key = key(GameSource.STEAM, "2")
        val row = SteamApp(id = 2, name = "Fatal")
        val dao = mockk<SteamAppDao>()
        coEvery { dao._getAllOwnedAppsPaged(any(), any()) } returns listOf(row)
        val runtimeState = mockk<SteamOwnedCopyRuntimeState>()
        coEvery { runtimeState.readBatch(listOf(row), scope, 0L) } throws OutOfMemoryError("fatal")
        val adapter = SteamOwnedCopyRuntimeAdapter(
            dao,
            scopes(GameSource.STEAM),
            readyLifecycle(GameSource.STEAM),
            mockk(relaxed = true),
            batchHistory(),
            runtimeState,
        )

        assertSuspendThrows(OutOfMemoryError::class.java) { adapter.resolveAll(setOf(key)) }
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
        coEvery { amazonState.readBatch(listOf(preferredAmazon), scope, 0L) } returns
            AmazonRuntimeBatchResult(mapOf(2 to state()), emptyMap())
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
        every { scanner.scanTyped(setOf(7)) } answers {
            scanThread = Thread.currentThread().name
            CustomRuntimeScanResult(mapOf(7 to row))
        }
        val callerThread = Thread.currentThread().name
        val runtimeState = CustomOwnedCopyRuntimeState(scanner)

        assertEquals(mapOf(7 to row), runtimeState.read(setOf(7)))

        verify(exactly = 1) { scanner.scanTyped(setOf(7)) }
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
            setCustomFolders(
                setOf(
                    unique.path,
                    duplicateA.path,
                    duplicateB.path,
                    missing.path,
                ),
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
    fun customRuntimeArtworkPreservesExistingExtractedExecutableAndImmediateSubfolderPrecedence() = runTest {
        val root = Files.createTempDirectory("custom-runtime-artwork").toFile()
        try {
            val extractedFolder = File(root, "Extracted").apply { mkdirs() }
            File(extractedFolder, ".gamenative").writeText("{\"appId\":7}")
            val extractedBin = File(extractedFolder, "bin").apply { mkdirs() }
            File(extractedBin, "Game.exe").writeText("exe")
            val extracted = File(extractedBin, "Game.extracted.ico").apply { writeText("icon") }
            File(extractedFolder, "other.png").writeText("other")

            val executableFolder = File(root, "Executable").apply { mkdirs() }
            File(executableFolder, ".gamenative").writeText("{\"appId\":8}")
            File(executableFolder, "Runner.exe").writeText("exe")
            val executableIcon = File(executableFolder, "Runner.png").apply { writeText("icon") }
            File(executableFolder, "generic-icon.png").writeText("other")

            val soleFolder = File(root, "Sole").apply { mkdirs() }
            File(soleFolder, ".gamenative").writeText("{\"appId\":9}")
            val soleSubfolder = File(soleFolder, "assets").apply { mkdirs() }
            val soleIcon = File(soleSubfolder, "art.ico").apply { writeText("icon") }
            File(soleFolder, "unrelated.jpg").writeText("artwork")
            File(soleSubfolder, "unrelated.webp").writeText("artwork")

            setCustomFolders(
                linkedSetOf(
                    extractedFolder.path,
                    executableFolder.path,
                    soleFolder.path,
                ),
            )
            assertEquals(
                setOf(extractedFolder.path, executableFolder.path, soleFolder.path),
                PrefManager.customGameManualFolders,
            )
            val before = root.walkTopDown().map { it.relativeTo(root).path }.toSet()
            assertEquals(7, GameMetadataManager.getAppIdReadOnly(extractedFolder))
            assertEquals(8, GameMetadataManager.getAppIdReadOnly(executableFolder))
            assertEquals(9, GameMetadataManager.getAppIdReadOnly(soleFolder))

            val rows = CustomOwnedCopyRuntimeScanner().scan(setOf(7, 8, 9))

            assertEquals(setOf(7, 8, 9), rows.keys)
            assertTrue(rows.getValue(7).iconUrl.endsWith(extracted.relativeTo(root).path))
            assertTrue(rows.getValue(8).iconUrl.endsWith(executableIcon.relativeTo(root).path))
            assertTrue(rows.getValue(9).iconUrl.endsWith(soleIcon.relativeTo(root).path))
            assertEquals(before, root.walkTopDown().map { it.relativeTo(root).path }.toSet())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun customRootSteamGridLogoPrefixUsesLegacyFormatsBeforeNearbyIcons() = runTest {
        val root = Files.createTempDirectory("custom-runtime-logo-prefix").toFile()
        try {
            val folder = File(root, "Prefix").apply { mkdirs() }
            File(folder, ".gamenative").writeText("{\"appId\":10}")
            val rootLogo = File(folder, "SteamGridDB_Logo_alt.JPG").apply { writeText("logo") }
            val nested = File(folder, "bin").apply { mkdirs() }
            File(nested, "Game.exe").writeText("exe")
            File(nested, "Game.extracted.ico").writeText("icon")
            setCustomFolders(setOf(folder.path))

            val row = CustomOwnedCopyRuntimeScanner().scan(setOf(10)).getValue(10)

            assertTrue(row.iconUrl.endsWith(rootLogo.relativeTo(root).path))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun customTypedMetadataReadDistinguishesRemovalAssociatedFailureAndAmbiguousBatchFailure() {
        assertSame(
            ReadOnlyAppIdResult.MissingOrInvalid,
            GameMetadataManager.readAppIdReadOnly { "{\"appId\":1.5}" },
        )
        val readFailure = GameMetadataManager.readAppIdReadOnly { throw SensitiveFailure() }
        assertEquals(SensitiveFailure::class, (readFailure as ReadOnlyAppIdResult.ReadFailure).errorClass)

        val root = Files.createTempDirectory("custom-runtime-typed-metadata").toFile()
        try {
            val folder = File(root, "Associated").apply { mkdirs() }
            setCustomFolders(setOf(folder.path))
            var result: ReadOnlyAppIdResult = ReadOnlyAppIdResult.Present(7)
            val scanner = CustomOwnedCopyRuntimeScanner({ result }, Unit)

            assertEquals(setOf(7), scanner.scanTyped(setOf(7)).rows.keys)
            result = ReadOnlyAppIdResult.ReadFailure(SensitiveFailure::class)
            val associatedFailure = scanner.scanTyped(setOf(7))
            assertEquals(SensitiveFailure::class, associatedFailure.failures.getValue(7))
            assertNull(associatedFailure.batchFailure)

            val unknownFailure = CustomOwnedCopyRuntimeScanner(
                readAppId = { ReadOnlyAppIdResult.ReadFailure(SensitiveFailure::class) },
                marker = Unit,
            ).scanTyped(setOf(7))
            assertEquals(emptyMap<Int, CustomOwnedCopyRuntimeRow>(), unknownFailure.rows)
            assertEquals(SensitiveFailure::class, unknownFailure.batchFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun customAssociatedFailureOutsideRequestedIdsDoesNotPoisonBatch() {
        val root = Files.createTempDirectory("custom-runtime-unrequested-failure").toFile()
        try {
            val requestedFolder = File(root, "Requested").apply { mkdirs() }
            val otherFolder = File(root, "Other").apply { mkdirs() }
            setCustomFolders(linkedSetOf(requestedFolder.path, otherFolder.path))
            var failOther = false
            val scanner = CustomOwnedCopyRuntimeScanner(
                readAppId = { folder ->
                    when {
                        folder == requestedFolder -> ReadOnlyAppIdResult.Present(7)
                        failOther -> ReadOnlyAppIdResult.ReadFailure(SensitiveFailure::class)
                        else -> ReadOnlyAppIdResult.Present(8)
                    }
                },
                marker = Unit,
            )
            assertEquals(setOf(7, 8), scanner.scanTyped(setOf(7, 8)).rows.keys)

            failOther = true
            val result = scanner.scanTyped(setOf(7))

            assertEquals(setOf(7), result.rows.keys)
            assertTrue(result.failures.isEmpty())
            assertNull(result.batchFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun customAssociatedMetadataReadFailureReturnsTypedUnavailableInPointAndBatch() = runTest {
        val root = Files.createTempDirectory("custom-runtime-associated-failure").toFile()
        try {
            val folder = File(root, "Associated").apply { mkdirs() }
            setCustomFolders(setOf(folder.path))
            var result: ReadOnlyAppIdResult = ReadOnlyAppIdResult.Present(7)
            val scanner = CustomOwnedCopyRuntimeScanner({ result }, Unit)
            val runtimeState = CustomOwnedCopyRuntimeState(scanner)
            assertEquals(setOf(7), runtimeState.readTyped(setOf(7)).rows.keys)
            result = ReadOnlyAppIdResult.ReadFailure(SensitiveFailure::class)
            val key = key(GameSource.CUSTOM_GAME, "7")
            val adapter = CustomOwnedCopyRuntimeAdapter(
                scopes(GameSource.CUSTOM_GAME),
                mockk(relaxed = true),
                batchHistory(),
                runtimeState,
            )

            val point = adapter.resolve(key)
            val batch = adapter.resolveAll(setOf(key)).getValue(key)

            listOf(point, batch).forEach { resolved ->
                assertUnavailable(
                    resolved,
                    key,
                    CopyUnavailableReason.SOURCE_READ_FAILED,
                    SensitiveFailure::class,
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun customUnknownMetadataReadFailureFailsClosedAndEmitsOneCategoricalBatchDiagnostic() = runTest {
        val root = Files.createTempDirectory("custom-runtime-unknown-failure").toFile()
        try {
            val folder = File(root, "Unknown").apply { mkdirs() }
            setCustomFolders(setOf(folder.path))
            val scanner = CustomOwnedCopyRuntimeScanner(
                readAppId = { ReadOnlyAppIdResult.ReadFailure(SensitiveFailure::class) },
                marker = Unit,
            )
            val diagnostics = mockk<CanonicalDiagnosticSink>(relaxed = true)
            val key = key(GameSource.CUSTOM_GAME, "7")
            val adapter = CustomOwnedCopyRuntimeAdapter(
                scopes(GameSource.CUSTOM_GAME),
                mockk(relaxed = true),
                batchHistory(),
                CustomOwnedCopyRuntimeState(scanner),
                diagnostics,
            )

            assertSame(OwnedCopyRuntimeResult.Hidden, adapter.resolveAll(setOf(key)).getValue(key))
            verify(exactly = 1) {
                diagnostics.sourceSnapshot(
                    GameSource.CUSTOM_GAME,
                    any(),
                    0,
                    any(),
                    SensitiveFailure::class,
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun customMetadataDeletionHidesBothPointAndBatchWithProductionState() = runTest {
        val root = Files.createTempDirectory("custom-runtime-deletion").toFile()
        try {
            val folder = File(root, "Deleted metadata").apply { mkdirs() }
            setCustomFolders(setOf(folder.path))
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

    @Test
    fun steamObservedUpdateRechecksAccountAuthorityBeforeProviderIo() = runTest {
        val gateway = mockk<SteamOwnedCopyRuntimeGateway>(relaxed = true)
        val owner = UpdateObservationOwner(scope, 1L)
        var authoritative = true
        val observed = SteamObservedUpdateState(
            gateway = gateway,
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            isOwnerCurrent = { authoritative },
        )
        val app = SteamApp(id = 7, name = "Installed")

        assertEquals(
            UpdateObservation.UNKNOWN,
            observed.snapshot(owner, listOf(app), SteamRuntimeInputs(emptySet(), emptyMap(), emptyMap(), emptyMap()))[7],
        )
        authoritative = false
        runCurrent()

        coVerify(exactly = 0) { gateway.refreshUpdates(any()) }
    }

    @Test
    fun steamLifecycleRetirementCancelsActiveObservedProviderIo() = runTest {
        val gateway = mockk<SteamOwnedCopyRuntimeGateway>()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { gateway.refreshUpdates(any()) } coAnswers {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val retirements = MutableSharedFlow<UpdateObservationLifecycle>(extraBufferCapacity = 1)
        val observed = SteamObservedUpdateState(
            gateway = gateway,
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            isOwnerCurrent = { true },
            retirements = retirements,
        )
        val owner = UpdateObservationOwner(scope, 1L)
        val app = SteamApp(id = 7, name = "Installed")

        observed.snapshot(owner, listOf(app), SteamRuntimeInputs(emptySet(), emptyMap(), emptyMap(), emptyMap()))
        runCurrent()
        assertEquals(Unit, started.await())
        retirements.emit(UpdateObservationLifecycle(otherScope, 2L))
        runCurrent()

        assertEquals(Unit, cancelled.await())
    }

    @Test
    fun steamSameGenerationReadinessInvalidationPreservesObservedProviderWork() = runTest {
        val gateway = mockk<SteamOwnedCopyRuntimeGateway>()
        val started = CompletableDeferred<Unit>()
        val completion = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { gateway.refreshUpdates(any()) } coAnswers {
            started.complete(Unit)
            try {
                completion.await()
            } finally {
                if (!completion.isCompleted) cancelled.complete(Unit)
            }
            mapOf(7 to UpdateRefreshOutcome.Observed(updateAvailable = false))
        }
        val retirements = MutableSharedFlow<UpdateObservationLifecycle>(extraBufferCapacity = 1)
        val observed = SteamObservedUpdateState(
            gateway = gateway,
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            isOwnerCurrent = { true },
            retirements = retirements,
        )
        val owner = UpdateObservationOwner(scope, 2L)
        val app = SteamApp(id = 7, name = "Installed")
        val inputs = SteamRuntimeInputs(emptySet(), emptyMap(), emptyMap(), emptyMap())

        observed.snapshot(owner, listOf(app), inputs)
        runCurrent()
        assertEquals(Unit, started.await())
        retirements.emit(UpdateObservationLifecycle(scope, 2L))
        runCurrent()

        assertFalse(cancelled.isCompleted)
        completion.complete(Unit)
        runCurrent()
        assertEquals(UpdateObservation.CURRENT, observed.snapshot(owner, listOf(app), inputs)[7])
    }

    @Test
    fun lifecycleTransitionCancelsOlderOwnerAndAllowsCurrentGenerationImmediately() = runTest {
        val oldCancelled = CompletableDeferred<Unit>()
        val providerCalls = mutableListOf<Int>()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 1,
            refresh = { requests ->
                val key = requests.single().key
                providerCalls += key
                if (key == 1) {
                    try {
                        awaitCancellation()
                    } finally {
                        oldCancelled.complete(Unit)
                    }
                }
                mapOf(key to UpdateRefreshOutcome.Observed(updateAvailable = false))
            },
        )
        val oldOwner = UpdateObservationOwner(scope, 1L)
        val currentOwner = UpdateObservationOwner(otherScope, 2L)

        store.snapshot(oldOwner, mapOf(1 to "old"), UpdateSnapshotCoverage.POINT)
        runCurrent()
        store.transitionLifecycle(currentOwner.accountScope, currentOwner.generation)
        runCurrent()
        assertEquals(Unit, oldCancelled.await())

        store.snapshot(currentOwner, mapOf(2 to "new"), UpdateSnapshotCoverage.POINT)
        runCurrent()

        assertEquals(listOf(1, 2), providerCalls)
        assertEquals(
            UpdateObservation.CURRENT,
            store.snapshot(currentOwner, mapOf(2 to "new"), UpdateSnapshotCoverage.POINT)[2],
        )
        assertEquals(
            UpdateObservation.UNKNOWN,
            store.snapshot(oldOwner, mapOf(1 to "old"), UpdateSnapshotCoverage.POINT)[1],
        )
    }

    @Test
    fun sameGenerationLifecycleTransitionPreservesCurrentOwnerWork() = runTest {
        val completion = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 1,
            refresh = { requests ->
                try {
                    completion.await()
                } finally {
                    if (!completion.isCompleted) cancelled.complete(Unit)
                }
                requests.associate {
                    it.key to UpdateRefreshOutcome.Observed(updateAvailable = false)
                }
            },
        )
        val owner = UpdateObservationOwner(scope, 2L)

        store.snapshot(owner, mapOf(1 to "v"), UpdateSnapshotCoverage.POINT)
        runCurrent()
        store.transitionLifecycle(owner.accountScope, owner.generation)
        runCurrent()

        assertFalse(cancelled.isCompleted)
        completion.complete(Unit)
        runCurrent()
        assertEquals(
            UpdateObservation.CURRENT,
            store.snapshot(owner, mapOf(1 to "v"), UpdateSnapshotCoverage.POINT)[1],
        )
    }

    @Test
    fun sameGenerationDifferentScopeTransitionReplacesTheOldOwner() = runTest {
        var calls = 0
        val cancelled = CompletableDeferred<Unit>()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 1,
            refresh = { requests ->
                calls += 1
                if (calls == 1) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                requests.associate {
                    it.key to UpdateRefreshOutcome.Observed(updateAvailable = false)
                }
            },
        )
        val oldOwner = UpdateObservationOwner(scope, 2L)
        val newOwner = UpdateObservationOwner(otherScope, 2L)

        store.snapshot(oldOwner, mapOf(1 to "old"), UpdateSnapshotCoverage.POINT)
        runCurrent()
        store.transitionLifecycle(newOwner.accountScope, newOwner.generation)
        runCurrent()

        assertTrue(cancelled.isCompleted)
        store.snapshot(newOwner, mapOf(2 to "new"), UpdateSnapshotCoverage.POINT)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(
            UpdateObservation.CURRENT,
            store.snapshot(newOwner, mapOf(2 to "new"), UpdateSnapshotCoverage.POINT)[2],
        )
    }

    @Test
    fun logoutTransitionCancelsCurrentOwnerAndFencesItsSnapshots() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 1,
            refresh = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        )
        val owner = UpdateObservationOwner(scope, 1L)

        store.snapshot(owner, mapOf(1 to "v"), UpdateSnapshotCoverage.POINT)
        runCurrent()
        store.transitionLifecycle(accountScope = null, generation = 1L)
        runCurrent()

        assertEquals(Unit, cancelled.await())
        assertEquals(
            UpdateObservation.UNKNOWN,
            store.snapshot(owner, mapOf(1 to "v"), UpdateSnapshotCoverage.POINT)[1],
        )
    }

    @Test
    fun pointSnapshotsDoNotPruneUnrelatedCompleteObservationsOrRetries() = runTest {
        val attempts = mutableMapOf<Int, Int>()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 1,
            maxRefreshBatch = 2,
            refresh = { requests ->
                requests.associate { request ->
                    val attempt = attempts.getOrDefault(request.key, 0) + 1
                    attempts[request.key] = attempt
                    request.key to if (request.key == 2 && attempt == 1) {
                        UpdateRefreshOutcome.Failed(SensitiveFailure::class)
                    } else {
                        UpdateRefreshOutcome.Observed(updateAvailable = false)
                    }
                }
            },
        )
        val owner = UpdateObservationOwner(scope, 1L)

        store.snapshot(
            owner,
            linkedMapOf(1 to "v", 2 to "v"),
            UpdateSnapshotCoverage.COMPLETE,
        )
        runCurrent()
        store.snapshot(owner, mapOf(1 to "v"), UpdateSnapshotCoverage.POINT)
        advanceTimeBy(10L)
        runCurrent()

        assertEquals(1, attempts[1])
        assertEquals(2, attempts[2])
        assertEquals(
            UpdateObservation.CURRENT,
            store.snapshot(owner, mapOf(2 to "v"), UpdateSnapshotCoverage.POINT)[2],
        )
    }

    @Test
    fun completeSnapshotConvergesAllActiveKeysAboveLegacyEntryLimitInBoundedWaves() = runTest {
        val waves = mutableListOf<List<Int>>()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 2,
            maxRefreshBatch = 2,
            refresh = { requests ->
                waves += requests.map(UpdateObservationRequest<Int, String>::key)
                requests.associate {
                    it.key to UpdateRefreshOutcome.Observed(updateAvailable = false)
                }
            },
        )
        val owner = UpdateObservationOwner(scope, 1L)
        val active = (1..5).associateWith { "v" }

        store.snapshot(owner, active, UpdateSnapshotCoverage.COMPLETE)
        runCurrent()

        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), waves)
        assertTrue(waves.all { it.size <= 2 })
        assertTrue(
            store.snapshot(owner, active, UpdateSnapshotCoverage.COMPLETE)
                .values
                .all { it == UpdateObservation.CURRENT },
        )
    }

    @Test
    fun completeSnapshotPrunesAbsentRetriesWhilePointSnapshotDoesNot() = runTest {
        val attempts = mutableListOf<Int>()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 1,
            maxRefreshBatch = 2,
            refresh = { requests ->
                attempts += requests.map(UpdateObservationRequest<Int, String>::key)
                requests.associate {
                    it.key to UpdateRefreshOutcome.Failed(SensitiveFailure::class)
                }
            },
        )
        val owner = UpdateObservationOwner(scope, 1L)

        store.snapshot(owner, linkedMapOf(1 to "v", 2 to "v"), UpdateSnapshotCoverage.COMPLETE)
        runCurrent()
        store.snapshot(owner, mapOf(1 to "v"), UpdateSnapshotCoverage.COMPLETE)
        advanceTimeBy(10L)
        runCurrent()

        assertEquals(listOf(1, 2, 1), attempts)
    }

    @Test
    fun timedOutRefreshWaveFailsCategoricallyThenContinuesAndRetries() = runTest {
        val attempts = mutableMapOf<Int, Int>()
        val failures = mutableListOf<kotlin.reflect.KClass<out Throwable>>()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            ttlMs = 100L,
            retryDelayMs = 10L,
            refreshTimeoutMs = 10L,
            maxEntries = 1,
            maxRefreshBatch = 1,
            onRefreshFailure = failures::add,
            refresh = { requests ->
                val key = requests.single().key
                val attempt = attempts.getOrDefault(key, 0) + 1
                attempts[key] = attempt
                if (key == 1 && attempt == 1) awaitCancellation()
                mapOf(key to UpdateRefreshOutcome.Observed(updateAvailable = false))
            },
        )
        val owner = UpdateObservationOwner(scope, 1L)

        store.snapshot(
            owner,
            linkedMapOf(1 to "v", 2 to "v"),
            UpdateSnapshotCoverage.COMPLETE,
        )
        runCurrent()
        advanceTimeBy(10L)
        runCurrent()

        assertEquals(1, attempts[1])
        assertEquals(1, attempts[2])
        assertEquals(listOf(TimeoutCancellationException::class), failures)
        assertEquals(
            UpdateObservation.CURRENT,
            store.snapshot(owner, mapOf(2 to "v"), UpdateSnapshotCoverage.POINT)[2],
        )

        advanceTimeBy(10L)
        runCurrent()
        assertEquals(2, attempts[1])
    }

    @Test
    fun staleRetirementCannotClearNewerObservedOwnerState() = runTest {
        val refreshes = AtomicInteger()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 8,
            refresh = { requests ->
                refreshes.incrementAndGet()
                requests.associate { request ->
                    request.key to UpdateRefreshOutcome.Observed(updateAvailable = false)
                }
            },
        )
        val newerOwner = UpdateObservationOwner(scope, 2L)

        store.snapshot(newerOwner, mapOf(7 to "v1"))
        runCurrent()
        assertEquals(UpdateObservation.CURRENT, store.snapshot(newerOwner, mapOf(7 to "v1"))[7])

        store.transitionLifecycle(accountScope = null, generation = 1L)

        assertEquals(UpdateObservation.CURRENT, store.snapshot(newerOwner, mapOf(7 to "v1"))[7])
        assertEquals(1, refreshes.get())
    }

    @Test
    fun observedRetirementCancelsAutonomousRetryTimer() = runTest {
        val attempts = AtomicInteger()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 8,
            refresh = { requests ->
                attempts.incrementAndGet()
                requests.associate { request ->
                    request.key to UpdateRefreshOutcome.Failed(SensitiveFailure::class)
                }
            },
        )
        val owner = UpdateObservationOwner(scope, 1L)

        store.snapshot(owner, mapOf(7 to "v1"))
        runCurrent()
        assertEquals(1, attempts.get())

        store.transitionLifecycle(accountScope = null, generation = owner.generation)
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(1, attempts.get())
    }

    @Test
    fun observedUpdateRejectsCompletionAfterOwnerLosesAuthority() = runTest {
        var authoritative = true
        val completion = CompletableDeferred<Unit>()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 8,
            isOwnerCurrent = { authoritative },
            refresh = { requests ->
                completion.await()
                requests.associate { request ->
                    request.key to UpdateRefreshOutcome.Observed(updateAvailable = false)
                }
            },
        )
        val owner = UpdateObservationOwner(scope, 1L)

        store.snapshot(owner, mapOf(7 to "v1"))
        runCurrent()
        authoritative = false
        completion.complete(Unit)
        runCurrent()

        assertEquals(UpdateObservation.UNKNOWN, store.snapshot(owner, mapOf(7 to "v1"))[7])
    }

    @Test
    fun observedUpdateFailureRetriesAutonomouslyWithoutAnotherSnapshot() = runTest {
        val attempts = AtomicInteger()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 8,
            refresh = { requests ->
                val outcome = if (attempts.getAndIncrement() == 0) {
                    UpdateRefreshOutcome.Failed(SensitiveFailure::class)
                } else {
                    UpdateRefreshOutcome.Observed(updateAvailable = true)
                }
                requests.associate { it.key to outcome }
            },
        )
        val owner = UpdateObservationOwner(scope, 1L)

        assertEquals(UpdateObservation.UNKNOWN, store.snapshot(owner, mapOf(7 to "v1"))[7])
        runCurrent()
        assertEquals(1, attempts.get())

        advanceTimeBy(10L)
        runCurrent()

        assertEquals(2, attempts.get())
        assertEquals(UpdateObservation.UPDATE_AVAILABLE, store.snapshot(owner, mapOf(7 to "v1"))[7])
    }

    @Test
    fun observedUpdateExpiryRefreshesAutonomouslyWithoutAnotherSnapshot() = runTest {
        val attempts = AtomicInteger()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 8,
            refresh = { requests ->
                val updateAvailable = attempts.getAndIncrement() == 0
                requests.associate { request ->
                    request.key to UpdateRefreshOutcome.Observed(updateAvailable)
                }
            },
        )
        val owner = UpdateObservationOwner(scope, 1L)

        store.snapshot(owner, mapOf(7 to "v1"))
        runCurrent()
        assertEquals(1, attempts.get())
        assertEquals(UpdateObservation.UPDATE_AVAILABLE, store.snapshot(owner, mapOf(7 to "v1"))[7])

        advanceTimeBy(100L)
        runCurrent()

        assertEquals(2, attempts.get())
        assertEquals(UpdateObservation.CURRENT, store.snapshot(owner, mapOf(7 to "v1"))[7])
    }

    @Test
    fun observedUpdateCompleteSetConvergesAboveLegacyCapacityWithoutRotatingLiveKeys() = runTest {
        val providerCalls = mutableListOf<Int>()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            ttlMs = 100L,
            retryDelayMs = 1_000L,
            maxEntries = 2,
            maxRefreshBatch = 2,
            refresh = { requests ->
                providerCalls += requests.map(UpdateObservationRequest<Int, String>::key)
                requests.associate { request ->
                    request.key to if (request.key == 3) {
                        UpdateRefreshOutcome.Failed(SensitiveFailure::class)
                    } else {
                        UpdateRefreshOutcome.Observed(updateAvailable = false)
                    }
                }
            },
        )
        val owner = UpdateObservationOwner(scope, 1L)
        val requested = linkedMapOf(1 to "v", 2 to "v", 3 to "v")

        store.snapshot(owner, requested, UpdateSnapshotCoverage.COMPLETE)
        runCurrent()
        store.snapshot(owner, requested, UpdateSnapshotCoverage.COMPLETE)
        runCurrent()

        assertEquals(listOf(1, 2, 3), providerCalls)
        assertEquals(
            UpdateObservation.CURRENT,
            store.snapshot(owner, requested, UpdateSnapshotCoverage.COMPLETE)[1],
        )
        assertEquals(
            UpdateObservation.CURRENT,
            store.snapshot(owner, requested, UpdateSnapshotCoverage.COMPLETE)[2],
        )
        assertEquals(
            UpdateObservation.UNKNOWN,
            store.snapshot(owner, requested, UpdateSnapshotCoverage.COMPLETE)[3],
        )
    }

    @Test
    fun observedUpdateWavesAreGloballySequentialAndBoundQueuedWork() = runTest {
        val firstWave = CompletableDeferred<Unit>()
        val startedWaves = mutableListOf<List<Int>>()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 8,
            maxRefreshBatch = 1,
            refresh = { requests ->
                startedWaves += requests.map(UpdateObservationRequest<Int, String>::key)
                if (requests.single().key == 1) firstWave.await()
                requests.associate {
                    it.key to UpdateRefreshOutcome.Observed(updateAvailable = false)
                }
            },
        )
        val owner = UpdateObservationOwner(scope, 1L)

        store.snapshot(owner, mapOf(1 to "v"))
        runCurrent()
        store.snapshot(owner, linkedMapOf(1 to "v", 2 to "v"))
        runCurrent()
        assertEquals(listOf(listOf(1)), startedWaves)

        firstWave.complete(Unit)
        runCurrent()
        assertEquals(listOf(listOf(1), listOf(2)), startedWaves)
    }

    @Test
    fun retiredObservedOwnerCannotReclaimStateOrStartQueuedProviderIo() = runTest {
        val oldOwner = UpdateObservationOwner(scope, 1L)
        val newOwner = UpdateObservationOwner(otherScope, 2L)
        var authoritativeOwner = oldOwner
        val providerCalls = mutableListOf<Int>()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { testScheduler.currentTime },
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 8,
            isOwnerCurrent = { owner -> owner == authoritativeOwner },
            refresh = { requests ->
                providerCalls += requests.map(UpdateObservationRequest<Int, String>::key)
                requests.associate { request ->
                    request.key to UpdateRefreshOutcome.Observed(updateAvailable = false)
                }
            },
        )

        assertEquals(UpdateObservation.UNKNOWN, store.snapshot(oldOwner, mapOf(1 to "old"))[1])
        authoritativeOwner = newOwner
        assertEquals(UpdateObservation.UNKNOWN, store.snapshot(newOwner, mapOf(2 to "new"))[2])
        runCurrent()

        assertEquals(listOf(2), providerCalls)
        assertEquals(UpdateObservation.UNKNOWN, store.snapshot(oldOwner, mapOf(1 to "old"))[1])
        runCurrent()
        assertEquals(listOf(2), providerCalls)
    }

    @Test
    fun coldObservedUpdateRefreshPublishesAuthoritativeTrueAndInvalidates() = runTest {
        val clock = MutableRuntimeClock()
        val refreshes = AtomicInteger()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = clock::now,
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 8,
            refresh = { requests ->
                refreshes.incrementAndGet()
                requests.associate {
                    it.key to UpdateRefreshOutcome.Observed(updateAvailable = true)
                }
            },
        )
        val owner = UpdateObservationOwner(scope, 1L)
        val invalidated = async(start = CoroutineStart.UNDISPATCHED) {
            store.invalidations().first()
        }

        assertEquals(
            UpdateObservation.UNKNOWN,
            store.snapshot(owner, mapOf(7 to "branch-a")).getValue(7),
        )
        runCurrent()

        assertEquals(Unit, invalidated.await())
        assertEquals(1, refreshes.get())
        assertEquals(
            UpdateObservation.UPDATE_AVAILABLE,
            store.snapshot(owner, mapOf(7 to "branch-a")).getValue(7),
        )
    }

    @Test
    fun observedUpdateExpiryAndFingerprintChangesConvergeBothDirections() = runTest {
        val clock = MutableRuntimeClock()
        val results = ArrayDeque(listOf(true, false, true))
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = clock::now,
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 8,
            refresh = { requests ->
                requests.associate {
                    it.key to UpdateRefreshOutcome.Observed(results.removeFirst())
                }
            },
        )
        val owner = UpdateObservationOwner(scope, 1L)

        assertEquals(UpdateObservation.UNKNOWN, store.snapshot(owner, mapOf(7 to "v1"))[7])
        runCurrent()
        assertEquals(UpdateObservation.UPDATE_AVAILABLE, store.snapshot(owner, mapOf(7 to "v1"))[7])

        clock.advanceBy(101L)
        assertEquals(UpdateObservation.UNKNOWN, store.snapshot(owner, mapOf(7 to "v1"))[7])
        runCurrent()
        assertEquals(UpdateObservation.CURRENT, store.snapshot(owner, mapOf(7 to "v1"))[7])

        assertEquals(UpdateObservation.UNKNOWN, store.snapshot(owner, mapOf(7 to "v2"))[7])
        runCurrent()
        assertEquals(UpdateObservation.UPDATE_AVAILABLE, store.snapshot(owner, mapOf(7 to "v2"))[7])
    }

    @Test
    fun observedUpdateFailuresRetryAfterDelayAndGenerationSwitchEvictsOldState() = runTest {
        val clock = MutableRuntimeClock()
        val attempts = AtomicInteger()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = clock::now,
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 8,
            refresh = { requests ->
                if (attempts.getAndIncrement() == 0) throw SensitiveFailure()
                requests.associate {
                    it.key to UpdateRefreshOutcome.Observed(updateAvailable = true)
                }
            },
        )
        val firstGeneration = UpdateObservationOwner(scope, 1L)

        assertEquals(UpdateObservation.UNKNOWN, store.snapshot(firstGeneration, mapOf(7 to "v1"))[7])
        runCurrent()
        assertEquals(1, attempts.get())
        assertEquals(UpdateObservation.UNKNOWN, store.snapshot(firstGeneration, mapOf(7 to "v1"))[7])
        runCurrent()
        assertEquals(1, attempts.get())

        clock.advanceBy(10L)
        store.snapshot(firstGeneration, mapOf(7 to "v1"))
        runCurrent()
        assertEquals(UpdateObservation.UPDATE_AVAILABLE, store.snapshot(firstGeneration, mapOf(7 to "v1"))[7])

        val nextGeneration = UpdateObservationOwner(scope, 2L)
        assertEquals(UpdateObservation.UNKNOWN, store.snapshot(nextGeneration, mapOf(7 to "v1"))[7])
        assertEquals(UpdateObservation.UNKNOWN, store.snapshot(firstGeneration, mapOf(7 to "v1"))[7])
    }

    @Test
    fun completeSnapshotsPruneFailureStateForAbsentKeys() = runTest {
        val attempts = mutableListOf<Int>()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { 0L },
            ttlMs = 100L,
            retryDelayMs = 1_000L,
            maxEntries = 1,
            refresh = { requests ->
                attempts += requests.single().key
                throw SensitiveFailure()
            },
        )
        val owner = UpdateObservationOwner(scope, 1L)

        store.snapshot(owner, mapOf(1 to "v"), UpdateSnapshotCoverage.COMPLETE)
        runCurrent()
        store.snapshot(owner, mapOf(2 to "v"), UpdateSnapshotCoverage.COMPLETE)
        runCurrent()
        store.snapshot(owner, mapOf(1 to "v"), UpdateSnapshotCoverage.COMPLETE)
        runCurrent()

        assertEquals(listOf(1, 2, 1), attempts)
    }

    @Test
    fun observedUpdateIgnoresStaleCompletionAfterOwnerGenerationSwitch() = runTest {
        val firstCompletion = CompletableDeferred<Map<Int, UpdateRefreshOutcome>>()
        val refreshCount = AtomicInteger()
        val store = ObservedUpdateStateStore<Int, String>(
            scope = backgroundScope,
            nowMonotonicMs = { 0L },
            ttlMs = 100L,
            retryDelayMs = 10L,
            maxEntries = 8,
            refresh = { requests ->
                if (refreshCount.getAndIncrement() == 0) {
                    firstCompletion.await()
                } else {
                    requests.associate {
                        it.key to UpdateRefreshOutcome.Observed(updateAvailable = false)
                    }
                }
            },
        )
        val oldOwner = UpdateObservationOwner(scope, 1L)
        val newOwner = UpdateObservationOwner(scope, 2L)

        assertEquals(UpdateObservation.UNKNOWN, store.snapshot(oldOwner, mapOf(7 to "v1"))[7])
        runCurrent()
        assertEquals(UpdateObservation.UNKNOWN, store.snapshot(newOwner, mapOf(7 to "v2"))[7])
        runCurrent()
        assertEquals(UpdateObservation.CURRENT, store.snapshot(newOwner, mapOf(7 to "v2"))[7])

        firstCompletion.complete(
            mapOf(7 to UpdateRefreshOutcome.Observed(updateAvailable = true)),
        )
        runCurrent()

        assertEquals(UpdateObservation.CURRENT, store.snapshot(newOwner, mapOf(7 to "v2"))[7])
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

    private fun setCustomFolders(value: Set<String>) {
        PrefManager.customGameManualFolders = value
        val deadline = System.nanoTime() + 5_000_000_000L
        while (PrefManager.customGameManualFolders != value && System.nanoTime() < deadline) {
            Thread.sleep(10L)
        }
        assertEquals(value, PrefManager.customGameManualFolders)
    }

    private fun key(source: GameSource, stableSourceId: String): OwnedCopyKey =
        OwnedCopyKey(scope, source, stableSourceId)

    private suspend fun lifecycleReadyFromCompletedLedger(
        source: GameSource,
        ledgerDao: OwnedCopyLedgerDao,
        entries: Map<String, String?>,
    ): InMemoryAccountLifecycleState {
        val lifecycle = InMemoryAccountLifecycleState()
        val generation = lifecycle.advanceGeneration(source)
        AccountScopedOwnershipLedger(scopes(source), ledgerDao, lifecycle)
            .runCompleteSnapshot(source) {
                MaterializedOwnedCopySnapshot(
                    value = Unit,
                    stableSourceIds = entries.keys,
                    resolvedSourceIds = entries.mapNotNull { (stableId, resolvedId) ->
                        resolvedId?.let { stableId to it }
                    }.toMap(),
                )
            }.getOrThrow()
        assertEquals(generation, lifecycle.readyGeneration(source))
        return lifecycle
    }

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
            dao.replaceCompletedSnapshot(
                accountScope = scope.value,
                source = source,
                stableSourceIds = any(),
                completedAt = any(),
                lifecycleGeneration = any(),
                resolvedSourceIds = any(),
            )
        } returns true
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

    private fun steamDepot(
        depotId: Int,
        manifestGid: Long,
        language: String = "",
    ): DepotInfo = DepotInfo(
        depotId = depotId,
        dlcAppId = SteamService.INVALID_APP_ID,
        depotFromApp = SteamService.INVALID_APP_ID,
        sharedInstall = false,
        osList = EnumSet.of(OS.windows),
        osArch = OSArch.Unknown,
        manifests = mapOf(
            "public" to ManifestInfo(
                name = "",
                gid = manifestGid,
                size = 1L,
                download = 1L,
            ),
        ),
        encryptedManifests = emptyMap(),
        language = language,
        realm = SteamRealm.Unknown,
    )

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

    private fun exactIdentityCases(): List<ExactIdentityCase> {
        val steam = key(GameSource.STEAM, "42")
        val gog = key(GameSource.GOG, "123")
        val epic = key(
            GameSource.EPIC,
            EpicStableSourceId.encode("namespace", "catalog"),
        )
        val amazon = key(GameSource.AMAZON, "product")
        val custom = key(GameSource.CUSTOM_GAME, "5")
        return listOf(
            ExactIdentityCase(
                name = "Steam",
                key = steam,
                validReference = SourceOwnedCopyReference.Steam(steam, 42),
                wrongReference = SourceOwnedCopyReference.Steam(steam, 43),
                validLibraryItemId = "STEAM_42",
                wrongLibraryItemId = "STEAM_43",
            ),
            ExactIdentityCase(
                name = "GOG",
                key = gog,
                validReference = SourceOwnedCopyReference.Gog(gog, "123"),
                wrongReference = SourceOwnedCopyReference.Gog(gog, "0123"),
                validLibraryItemId = "GOG_123",
                wrongLibraryItemId = "GOG_124",
            ),
            ExactIdentityCase(
                name = "Epic",
                key = epic,
                validReference = SourceOwnedCopyReference.Epic(
                    epic,
                    localRowId = 7,
                    namespace = "namespace",
                    catalogId = "catalog",
                ),
                wrongReference = SourceOwnedCopyReference.Epic(
                    epic,
                    localRowId = 7,
                    namespace = "other-namespace",
                    catalogId = "catalog",
                ),
                validLibraryItemId = "EPIC_7",
                wrongLibraryItemId = "EPIC_8",
            ),
            ExactIdentityCase(
                name = "Amazon",
                key = amazon,
                validReference = SourceOwnedCopyReference.Amazon(
                    amazon,
                    localRowId = 8,
                    productId = "product",
                    entitlementId = "entitlement",
                ),
                wrongReference = SourceOwnedCopyReference.Amazon(
                    amazon,
                    localRowId = 8,
                    productId = "other-product",
                    entitlementId = "entitlement",
                ),
                validLibraryItemId = "AMAZON_8",
                wrongLibraryItemId = "AMAZON_9",
            ),
            ExactIdentityCase(
                name = "Custom",
                key = custom,
                validReference = SourceOwnedCopyReference.Custom(custom, 5),
                wrongReference = SourceOwnedCopyReference.Custom(custom, 6),
                validLibraryItemId = "CUSTOM_GAME_5",
                wrongLibraryItemId = "CUSTOM_GAME_6",
            ),
        )
    }

    private fun identityRegistry(
        source: GameSource,
        result: OwnedCopyRuntimeResult,
    ): IdentityRegistryFixture {
        val adapters = GameSource.entries.associateWith { adapterSource ->
            IdentityRecordingAdapter(
                source = adapterSource,
                result = result.takeIf { adapterSource == source }
                    ?: OwnedCopyRuntimeResult.Hidden,
            )
        }
        val history = mockk<LibraryPlayHistoryDao>()
        every { history.getAll() } returns emptyFlow()
        return IdentityRegistryFixture(
            registry = OwnedCopyRuntimeRegistry(
                adapters.values.toSet(),
                history,
                mockk(relaxed = true),
            ),
            selected = adapters.getValue(source),
            adapters = adapters,
        )
    }

    private fun libraryItem(source: GameSource, appId: String): LibraryItem = LibraryItem(
        appId = appId,
        name = "Runtime",
        gameSource = source,
    )

    private fun runtimeResult(
        copyKey: OwnedCopyKey,
        referenceKey: OwnedCopyKey,
    ): OwnedCopyRuntimeResult.Available = runtimeResult(
        key = copyKey,
        reference = SourceOwnedCopyReference.Steam(referenceKey, 1),
        libraryItem = null,
    )

    private fun runtimeResult(
        key: OwnedCopyKey,
        reference: SourceOwnedCopyReference,
        libraryItem: LibraryItem?,
    ): OwnedCopyRuntimeResult.Available = OwnedCopyRuntimeResult.Available(
        OwnedCopyRuntime(
            key = key,
            reference = reference,
            libraryItem = libraryItem,
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

    private data class ExactIdentityCase(
        val name: String,
        val key: OwnedCopyKey,
        val validReference: SourceOwnedCopyReference,
        val wrongReference: SourceOwnedCopyReference,
        val validLibraryItemId: String,
        val wrongLibraryItemId: String,
    )

    private data class IdentityRegistryFixture(
        val registry: OwnedCopyRuntimeRegistry,
        val selected: IdentityRecordingAdapter,
        val adapters: Map<GameSource, IdentityRecordingAdapter>,
    ) {
        fun siblingCalls(): Int = adapters.values
            .filterNot { it === selected }
            .sumOf { it.pointCalls + it.batchCalls }
    }

    private class IdentityRecordingAdapter(
        override val source: GameSource,
        private val result: OwnedCopyRuntimeResult,
    ) : OwnedCopyRuntimeAdapter {
        var pointCalls = 0
        var batchCalls = 0

        override fun invalidations(): Flow<Unit> = emptyFlow()

        override suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult {
            pointCalls += 1
            return result
        }

        override suspend fun resolveAll(
            keys: Set<OwnedCopyKey>,
        ): Map<OwnedCopyKey, OwnedCopyRuntimeResult> {
            batchCalls += 1
            return keys.associateWith { result }
        }
    }

    private class MutableRuntimeClock(
        private var value: Long = 0L,
    ) {
        fun now(): Long = value
        fun advanceBy(durationMs: Long) {
            value += durationMs
        }
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
