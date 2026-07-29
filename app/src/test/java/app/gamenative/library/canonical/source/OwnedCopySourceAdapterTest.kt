package app.gamenative.library.canonical.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.data.AmazonGame
import app.gamenative.data.EpicGame
import app.gamenative.data.GOGGame
import app.gamenative.data.GameSource
import app.gamenative.data.SteamApp
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.OwnedCopyPresenceEntity
import app.gamenative.data.canonical.OwnedCopySyncEntity
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.CompletedOwnedCopySnapshot
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.OwnedCopyLedgerDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.enums.AppType
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.InMemoryAccountLifecycleState
import app.gamenative.utils.CustomGameScanner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class OwnedCopySourceAdapterTest {
    private lateinit var context: Context
    private lateinit var testRoot: File
    private val scope = AccountScope.parse("a".repeat(64))
    private val otherScope = AccountScope.parse("b".repeat(64))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefManager.init(context)
        testRoot = File(context.cacheDir, "owned-copy-source-adapter-test").apply {
            deleteRecursively()
            mkdirs()
        }
        setCustomFolders(emptySet())
        CustomGameScanner.invalidateCache()
    }

    @After
    fun tearDown() {
        setCustomFolders(emptySet())
        CustomGameScanner.invalidateCache()
        testRoot.deleteRecursively()
    }

    @Test
    fun steamUsesDecimalAppIdsAndMapsNumericFacets() = runTest {
        val dao = mockk<SteamAppDao>()
        val apps = listOf(
            SteamApp(id = 20, name = "Later"),
            SteamApp(
                id = 3,
                name = "Three",
                developer = "Developer",
                releaseDate = 1_704_067_200L,
                type = AppType.game,
                genreIds = listOf(4, 2, 4),
                categoryIds = listOf(22, 2),
                storeTagIds = listOf(492, 19, 492),
            ),
        )
        coEvery { dao._getAllOwnedAppsPaged(any(), any()) } returns apps
        coEvery { dao.findOwnedApp(3, any(), any()) } returns apps[1]
        every { dao._observeOwnedAppCount(any(), any()) } returns flowOf(2, 3)
        val adapter = SteamOwnedCopySourceAdapter(
            dao,
            scopes(GameSource.STEAM),
            steamReadyLifecycle(),
        )

        val batch = adapter.snapshot()

        assertEquals(SnapshotCompleteness.COMPLETE, batch.completeness)
        assertEquals(listOf("20", "3"), batch.copies.map { it.key.stableSourceId })
        val projected = batch.copies.single { it.key.stableSourceId == "3" }
        assertEquals(3, projected.directSteamAppId)
        assertEquals(2024, projected.releaseYear)
        assertEquals(CanonicalAppType.GAME, projected.appType)
        assertEquals(setOf("steam:2", "steam:4"), projected.genreKeys)
        assertEquals(setOf("steam:2", "steam:22"), projected.featureKeys)
        assertEquals(setOf(19, 492), projected.tagIds)
        assertEquals(
            SourceOwnedCopyReference.Steam(projected.key, 3),
            adapter.resolve(projected.key),
        )
        assertEquals(2, adapter.invalidations().take(2).toList().size)
    }

    @Test
    fun steamResolutionRejectsRowsWithoutACurrentOwnedLicense() = runTest {
        val dao = mockk<SteamAppDao>()
        coEvery { dao.findOwnedApp(3, any(), any()) } returns null
        val adapter = SteamOwnedCopySourceAdapter(
            dao,
            scopes(GameSource.STEAM),
            steamReadyLifecycle(),
        )

        val resolved = adapter.resolve(OwnedCopyKey(scope, GameSource.STEAM, "3"))

        assertNull(resolved)
        coVerify(exactly = 1) { dao.findOwnedApp(3, any(), any()) }
        coVerify(exactly = 0) { dao.findApp(any()) }
    }

    @Test
    fun steamRejectsPriorLicenseRowsUntilCurrentGenerationIsReady() = runTest {
        val dao = mockk<SteamAppDao>()
        val lifecycleState = InMemoryAccountLifecycleState().apply {
            markReady(GameSource.STEAM, 0L)
            advanceGeneration(GameSource.STEAM)
        }
        val adapter = SteamOwnedCopySourceAdapter(
            dao,
            scopes(GameSource.STEAM),
            lifecycleState,
        )

        val batch = adapter.snapshot()
        val resolved = adapter.resolve(OwnedCopyKey(scope, GameSource.STEAM, "3"))

        assertEquals(SnapshotCompleteness.UNAVAILABLE, batch.completeness)
        assertEquals(SnapshotReason.PRESENCE_LEDGER_NOT_READY, batch.reason)
        assertTrue(batch.copies.isEmpty())
        assertNull(resolved)
        coVerify(exactly = 0) { dao._getAllOwnedAppsPaged(any(), any()) }
        coVerify(exactly = 0) { dao.findOwnedApp(any(), any(), any()) }
    }

    @Test
    fun gogKeepsProviderIdsAndQualifiesGenres() = runTest {
        val dao = mockk<GOGGameDao>()
        val games = listOf(
            GOGGame(id = "z-id", title = "Zulu"),
            GOGGame(
                id = "gog-id",
                title = "Game",
                developer = "Studio",
                releaseDate = "2023-04-01",
                genres = listOf("Role-Playing", "Action", "action"),
                type = AppType.application,
            ),
        )
        coEvery { dao.getAllAsList() } returns games
        coEvery { dao.getById("gog-id") } returns games[1]
        every { dao.getAll() } returns flowOf(games, games)
        val adapter = GogOwnedCopySourceAdapter(
            dao,
            scopes(GameSource.GOG),
            completedLedger(GameSource.GOG, "gog-id", "z-id"),
        )

        val batch = adapter.snapshot()

        assertEquals(listOf("gog-id", "z-id"), batch.copies.map { it.key.stableSourceId })
        val projected = batch.copies.first()
        assertEquals(setOf("gog:action", "gog:role playing"), projected.genreKeys)
        assertEquals(CanonicalAppType.APPLICATION, projected.appType)
        assertEquals(
            SourceOwnedCopyReference.Gog(projected.key, "gog-id"),
            adapter.resolve(projected.key),
        )
        assertEquals(2, adapter.invalidations().take(2).toList().size)
    }

    @Test
    fun epicUsesProviderIdentityAndNeverGeneratedRowId() = runTest {
        val dao = mockk<EpicGameDao>()
        val namespace = "ns:one"
        val catalogId = "商品/id"
        val games = listOf(
            EpicGame(id = 41, namespace = "z", catalogId = "z", title = "Zulu"),
            EpicGame(
                id = 77,
                namespace = namespace,
                catalogId = catalogId,
                title = "Epic Game",
                developer = "Epic Studio",
                releaseDate = "2022-10-01T00:00:00Z",
                genres = listOf("Action RPG", "action-rpg"),
                tags = listOf("Not a Steam tag ID"),
                type = AppType.game,
            ),
        )
        coEvery { dao.getAllForCanonicalProjection() } returns games
        coEvery { dao.getByProviderIdentity(namespace, catalogId) } returns games[1]
        every { dao.getAll() } returns flowOf(games, games)
        val adapter = EpicOwnedCopySourceAdapter(
            dao,
            scopes(GameSource.EPIC),
            completedLedger(
                GameSource.EPIC,
                EpicStableSourceId.encode("z", "z"),
                EpicStableSourceId.encode(namespace, catalogId),
            ),
        )

        val batch = adapter.snapshot()

        val stableId = EpicStableSourceId.encode(namespace, catalogId)
        assertEquals(
            listOf(EpicStableSourceId.encode("z", "z"), stableId).sorted(),
            batch.copies.map { it.key.stableSourceId },
        )
        val projected = batch.copies.single { it.key.stableSourceId == stableId }
        assertEquals(setOf("epic:action rpg"), projected.genreKeys)
        assertTrue(projected.tagIds.isEmpty())
        assertTrue(projected.featureKeys.isEmpty())
        assertFalse(projected.key.stableSourceId.contains("77"))
        assertEquals(
            SourceOwnedCopyReference.Epic(
                key = projected.key,
                localRowId = 77,
                namespace = namespace,
                catalogId = catalogId,
            ),
            adapter.resolve(projected.key),
        )
        assertEquals(2, adapter.invalidations().take(2).toList().size)
    }

    @Test
    fun `epic snapshot and resolution omit rows hidden from All Library`() = runTest {
        val dao = mockk<EpicGameDao>()
        val normal = EpicGame(
            id = 1,
            namespace = "games",
            catalogId = "normal",
            title = "Normal Game",
        )
        val excluded = listOf(
            EpicGame(
                id = 2,
                namespace = "games",
                catalogId = "dlc",
                title = "DLC",
                isDLC = true,
            ),
            EpicGame(
                id = 3,
                namespace = "ue",
                catalogId = "marketplace",
                title = "Unreal Marketplace",
            ),
            EpicGame(
                id = 4,
                namespace = "89efe5924d3d467c839449ab6ab52e7f",
                catalogId = "engine",
                title = "Unreal Engine",
            ),
        )
        val games = listOf(normal) + excluded
        val stableIds = games.associateWith { game ->
            EpicStableSourceId.encode(game.namespace, game.catalogId)
        }
        coEvery { dao.getAllForCanonicalProjection() } returns games
        coEvery { dao.getByProviderIdentity(any(), any()) } answers {
            val namespace = invocation.args[0] as String
            val catalogId = invocation.args[1] as String
            games.singleOrNull { game ->
                game.namespace == namespace && game.catalogId == catalogId
            }
        }
        val adapter = EpicOwnedCopySourceAdapter(
            dao,
            scopes(GameSource.EPIC),
            completedLedger(GameSource.EPIC, *stableIds.values.toTypedArray()),
        )

        val batch = adapter.snapshot()

        val normalKey = OwnedCopyKey(scope, GameSource.EPIC, stableIds.getValue(normal))
        assertEquals(SnapshotCompleteness.COMPLETE, batch.completeness)
        assertEquals(listOf(normalKey), batch.copies.map { it.key })
        assertNull(batch.reason)
        excluded.forEach { game ->
            val excludedKey = OwnedCopyKey(scope, GameSource.EPIC, stableIds.getValue(game))
            assertNull(adapter.resolve(excludedKey))
        }
    }

    @Test
    fun `epic snapshot remains partial for a truly missing materialized row`() = runTest {
        val dao = mockk<EpicGameDao>()
        val normal = EpicGame(
            id = 1,
            namespace = "games",
            catalogId = "normal",
            title = "Normal Game",
        )
        val normalStableId = EpicStableSourceId.encode(normal.namespace, normal.catalogId)
        val missingStableId = EpicStableSourceId.encode("games", "missing")
        coEvery { dao.getAllForCanonicalProjection() } returns listOf(normal)
        val adapter = EpicOwnedCopySourceAdapter(
            dao,
            scopes(GameSource.EPIC),
            completedLedger(GameSource.EPIC, normalStableId, missingStableId),
        )

        val batch = adapter.snapshot()

        assertEquals(SnapshotCompleteness.PARTIAL, batch.completeness)
        assertEquals(SnapshotReason.MISSING_MATERIALIZED_ROW, batch.reason)
        assertEquals(
            listOf(OwnedCopyKey(scope, GameSource.EPIC, normalStableId)),
            batch.copies.map { it.key },
        )
    }

    @Test
    fun amazonUsesProductIdAndKeepsEntitlementOnlyInReference() = runTest {
        val dao = mockk<AmazonGameDao>()
        val games = listOf(
            AmazonGame(appId = 8, productId = "z-product", title = "Zulu"),
            AmazonGame(
                appId = 9,
                productId = "product-id",
                entitlementId = "stale-account-entitlement",
                title = "Amazon Game",
                developer = "Studio",
                releaseDate = "2021-06-01",
            ),
        )
        coEvery { dao.getAllAsList() } returns games
        coEvery { dao.getByProductId("product-id") } returns games[1]
        every { dao.getAll() } returns flowOf(games, games)
        val adapter = AmazonOwnedCopySourceAdapter(
            dao,
            scopes(GameSource.AMAZON),
            completedLedger(GameSource.AMAZON, "product-id", "z-product"),
        )

        val batch = adapter.snapshot()

        assertEquals(listOf("product-id", "z-product"), batch.copies.map { it.key.stableSourceId })
        val projected = batch.copies.first()
        assertEquals(CanonicalAppType.GAME, projected.appType)
        assertEquals("product-id", projected.key.stableSourceId)
        assertEquals(
            SourceOwnedCopyReference.Amazon(
                key = projected.key,
                localRowId = 9,
                productId = "product-id",
                entitlementId = "entitlement-id",
            ),
            adapter.resolve(projected.key),
        )
        assertEquals(2, adapter.invalidations().take(2).toList().size)
    }

    @Test
    fun customSnapshotIsPartialAndDoesNotCreateOrRewriteMetadata() = runTest {
        val valid = File(testRoot, "Valid Game").apply { mkdirs() }
        val metadata = File(valid, ".gamenative")
        val originalBytes = JSONObject().put("appId", 42).toString().toByteArray()
        metadata.writeBytes(originalBytes)
        val missingId = File(testRoot, "Missing ID").apply { mkdirs() }
        val missingFolder = File(testRoot, "Missing Folder")
        setCustomFolders(linkedSetOf(valid.path, missingId.path, missingFolder.path))
        val adapter = CustomOwnedCopySourceAdapter(scopes(GameSource.CUSTOM_GAME))

        val batch = adapter.snapshot()

        assertEquals(SnapshotCompleteness.PARTIAL, batch.completeness)
        assertEquals(SnapshotReason.MISSING_STABLE_ID, batch.reason)
        assertEquals(listOf("42"), batch.copies.map { it.key.stableSourceId })
        assertEquals("Valid Game", batch.copies.single().displayName)
        assertEquals(CanonicalAppType.GAME, batch.copies.single().appType)
        assertTrue(metadata.readBytes().contentEquals(originalBytes))
        assertFalse(File(missingId, ".gamenative").exists())
        assertFalse(missingFolder.exists())
        assertEquals(
            SourceOwnedCopyReference.Custom(batch.copies.single().key, 42),
            adapter.resolve(batch.copies.single().key),
        )
    }

    @Test
    fun customSnapshotDoesNotMigrateLegacyMetadataAsASideEffect() = runTest {
        val folder = File(testRoot, "Legacy Game").apply { mkdirs() }
        val metadata = File(folder, ".gamenative").apply { writeText("123") }
        setCustomFolders(setOf(folder.path))
        val adapter = CustomOwnedCopySourceAdapter(scopes(GameSource.CUSTOM_GAME))

        val batch = adapter.snapshot()
        val resolved = adapter.resolve(batch.copies.single().key)

        assertEquals("123", metadata.readText())
        assertEquals("123", batch.copies.single().key.stableSourceId)
        assertEquals(SourceOwnedCopyReference.Custom(batch.copies.single().key, 123), resolved)
    }

    @Test
    fun missingAccountScopeIsUnavailableEvenWhenStaleRowsExist() = runTest {
        val steamDao = mockk<SteamAppDao>(relaxed = true)
        val gogDao = mockk<GOGGameDao>(relaxed = true)
        val epicDao = mockk<EpicGameDao>(relaxed = true)
        val amazonDao = mockk<AmazonGameDao>(relaxed = true)
        val folder = File(testRoot, "Stale Custom").apply { mkdirs() }
        File(folder, ".gamenative").writeText(JSONObject().put("appId", 44).toString())
        setCustomFolders(setOf(folder.path))
        val noScopes = scopes()
        val batches = listOf(
            SteamOwnedCopySourceAdapter(steamDao, noScopes).snapshot(),
            GogOwnedCopySourceAdapter(gogDao, noScopes, emptyLedger()).snapshot(),
            EpicOwnedCopySourceAdapter(epicDao, noScopes, emptyLedger()).snapshot(),
            AmazonOwnedCopySourceAdapter(amazonDao, noScopes, emptyLedger()).snapshot(),
            CustomOwnedCopySourceAdapter(noScopes).snapshot(),
        )

        batches.forEach { batch ->
            assertEquals(SnapshotCompleteness.UNAVAILABLE, batch.completeness)
            assertEquals(SnapshotReason.MISSING_ACCOUNT_SCOPE, batch.reason)
            assertNull(batch.accountScope)
            assertTrue(batch.copies.isEmpty())
            assertNull(batch.errorClass)
        }
        coVerify(exactly = 0) { steamDao._getAllOwnedAppsPaged(any(), any()) }
        coVerify(exactly = 0) { gogDao.getAllAsList() }
        coVerify(exactly = 0) { epicDao.getAllForCanonicalProjection() }
        coVerify(exactly = 0) { amazonDao.getAllAsList() }
    }

    @Test
    fun daoFailuresUseOnlyFixedReasonAndExceptionClass() = runTest {
        val steamDao = mockk<SteamAppDao>()
        val gogDao = mockk<GOGGameDao>()
        val epicDao = mockk<EpicGameDao>()
        val amazonDao = mockk<AmazonGameDao>()
        coEvery { steamDao._getAllOwnedAppsPaged(any(), any()) } throws SensitiveDaoException()
        coEvery { gogDao.getAllAsList() } throws SensitiveDaoException()
        coEvery { epicDao.getAllForCanonicalProjection() } throws SensitiveDaoException()
        coEvery { amazonDao.getAllAsList() } throws SensitiveDaoException()
        val batches = listOf(
            SteamOwnedCopySourceAdapter(
                steamDao,
                scopes(GameSource.STEAM),
                steamReadyLifecycle(),
            ).snapshot(),
            GogOwnedCopySourceAdapter(
                gogDao,
                scopes(GameSource.GOG),
                completedLedger(GameSource.GOG, "owned"),
            ).snapshot(),
            EpicOwnedCopySourceAdapter(
                epicDao,
                scopes(GameSource.EPIC),
                completedLedger(GameSource.EPIC, EpicStableSourceId.encode("ns", "owned")),
            ).snapshot(),
            AmazonOwnedCopySourceAdapter(
                amazonDao,
                scopes(GameSource.AMAZON),
                completedLedger(GameSource.AMAZON, "owned"),
            ).snapshot(),
        )

        batches.forEach { batch ->
            assertEquals(SnapshotCompleteness.UNAVAILABLE, batch.completeness)
            assertEquals(SnapshotReason.SOURCE_READ_FAILED, batch.reason)
            assertEquals(SensitiveDaoException::class, batch.errorClass)
            assertTrue(batch.copies.isEmpty())
            assertFalse(batch.toString().contains(SENSITIVE_EXCEPTION_MESSAGE))
        }
    }

    @Test
    fun everyAdapterRejectsKeysFromAnotherAccountOrSourceBeforeLookup() = runTest {
        val steamDao = mockk<SteamAppDao>(relaxed = true)
        val gogDao = mockk<GOGGameDao>(relaxed = true)
        val epicDao = mockk<EpicGameDao>(relaxed = true)
        val amazonDao = mockk<AmazonGameDao>(relaxed = true)
        val folder = File(testRoot, "Custom").apply { mkdirs() }
        File(folder, ".gamenative").writeText(JSONObject().put("appId", 5).toString())
        setCustomFolders(setOf(folder.path))

        assertNull(
            SteamOwnedCopySourceAdapter(steamDao, scopes(GameSource.STEAM)).resolve(
                OwnedCopyKey(otherScope, GameSource.STEAM, "3"),
            ),
        )
        assertNull(
            GogOwnedCopySourceAdapter(gogDao, scopes(GameSource.GOG), emptyLedger()).resolve(
                OwnedCopyKey(otherScope, GameSource.GOG, "gog-id"),
            ),
        )
        assertNull(
            EpicOwnedCopySourceAdapter(epicDao, scopes(GameSource.EPIC), emptyLedger()).resolve(
                OwnedCopyKey(otherScope, GameSource.EPIC, EpicStableSourceId.encode("ns", "catalog")),
            ),
        )
        assertNull(
            AmazonOwnedCopySourceAdapter(amazonDao, scopes(GameSource.AMAZON), emptyLedger()).resolve(
                OwnedCopyKey(otherScope, GameSource.AMAZON, "product"),
            ),
        )
        assertNull(
            CustomOwnedCopySourceAdapter(scopes(GameSource.CUSTOM_GAME)).resolve(
                OwnedCopyKey(otherScope, GameSource.CUSTOM_GAME, "5"),
            ),
        )
        assertNull(
            SteamOwnedCopySourceAdapter(steamDao, scopes(GameSource.STEAM)).resolve(
                OwnedCopyKey(scope, GameSource.GOG, "3"),
            ),
        )
        assertNull(
            GogOwnedCopySourceAdapter(gogDao, scopes(GameSource.GOG), emptyLedger()).resolve(
                OwnedCopyKey(scope, GameSource.EPIC, "gog-id"),
            ),
        )
        assertNull(
            EpicOwnedCopySourceAdapter(epicDao, scopes(GameSource.EPIC), emptyLedger()).resolve(
                OwnedCopyKey(scope, GameSource.AMAZON, EpicStableSourceId.encode("ns", "catalog")),
            ),
        )
        assertNull(
            AmazonOwnedCopySourceAdapter(amazonDao, scopes(GameSource.AMAZON), emptyLedger()).resolve(
                OwnedCopyKey(scope, GameSource.STEAM, "product"),
            ),
        )
        assertNull(
            CustomOwnedCopySourceAdapter(scopes(GameSource.CUSTOM_GAME)).resolve(
                OwnedCopyKey(scope, GameSource.GOG, "5"),
            ),
        )

        coVerify(exactly = 0) { steamDao.findOwnedApp(any(), any(), any()) }
        coVerify(exactly = 0) { gogDao.getById(any()) }
        coVerify(exactly = 0) { epicDao.getByProviderIdentity(any(), any()) }
        coVerify(exactly = 0) { amazonDao.getByProductId(any()) }
    }

    @Test
    fun malformedStructuredIdsFailResolutionWithoutSourceLookup() = runTest {
        val steamDao = mockk<SteamAppDao>(relaxed = true)
        val epicDao = mockk<EpicGameDao>(relaxed = true)
        val steam = SteamOwnedCopySourceAdapter(steamDao, scopes(GameSource.STEAM))
        val epic = EpicOwnedCopySourceAdapter(epicDao, scopes(GameSource.EPIC), emptyLedger())
        val custom = CustomOwnedCopySourceAdapter(scopes(GameSource.CUSTOM_GAME))

        assertNull(steam.resolve(OwnedCopyKey(scope, GameSource.STEAM, "003")))
        assertNull(steam.resolve(OwnedCopyKey(scope, GameSource.STEAM, "-3")))
        assertNull(epic.resolve(OwnedCopyKey(scope, GameSource.EPIC, "not-an-epic-id")))
        assertNull(custom.resolve(OwnedCopyKey(scope, GameSource.CUSTOM_GAME, "custom_3")))

        coVerify(exactly = 0) { steamDao.findOwnedApp(any(), any(), any()) }
        coVerify(exactly = 0) { epicDao.getByProviderIdentity(any(), any()) }
    }

    @Test
    fun malformedOrMissingSourceRowIdsMakeSnapshotsPartial() = runTest {
        val steamDao = mockk<SteamAppDao>()
        val gogDao = mockk<GOGGameDao>()
        val epicDao = mockk<EpicGameDao>()
        val amazonDao = mockk<AmazonGameDao>()
        coEvery { steamDao._getAllOwnedAppsPaged(any(), any()) } returns listOf(SteamApp(id = 0, name = "Invalid"))
        coEvery { gogDao.getAllAsList() } returns listOf(GOGGame(id = "", title = "Invalid"))
        coEvery { epicDao.getAllForCanonicalProjection() } returns listOf(EpicGame(namespace = "", catalogId = "catalog", title = "Invalid"))
        coEvery { amazonDao.getAllAsList() } returns listOf(AmazonGame(productId = "", title = "Invalid"))

        val batches = listOf(
            SteamOwnedCopySourceAdapter(
                steamDao,
                scopes(GameSource.STEAM),
                steamReadyLifecycle(),
            ).snapshot(),
            GogOwnedCopySourceAdapter(
                gogDao,
                scopes(GameSource.GOG),
                completedLedger(GameSource.GOG, "owned"),
            ).snapshot(),
            EpicOwnedCopySourceAdapter(
                epicDao,
                scopes(GameSource.EPIC),
                completedLedger(GameSource.EPIC, EpicStableSourceId.encode("ns", "owned")),
            ).snapshot(),
            AmazonOwnedCopySourceAdapter(
                amazonDao,
                scopes(GameSource.AMAZON),
                completedLedger(GameSource.AMAZON, "owned"),
            ).snapshot(),
        )

        assertEquals(SnapshotReason.MALFORMED_SOURCE_ID, batches[0].reason)
        batches.drop(1).forEach { assertEquals(SnapshotReason.MISSING_MATERIALIZED_ROW, it.reason) }
        batches.forEach {
            assertEquals(SnapshotCompleteness.PARTIAL, it.completeness)
            assertTrue(it.copies.isEmpty())
        }
    }

    @Test
    fun providerAdaptersRequireCompletedCurrentScopeLedger() = runTest {
        val gogDao = mockk<GOGGameDao>(relaxed = true)
        val epicDao = mockk<EpicGameDao>(relaxed = true)
        val amazonDao = mockk<AmazonGameDao>(relaxed = true)
        val notReady = emptyLedger()

        val batches = listOf(
            GogOwnedCopySourceAdapter(gogDao, scopes(GameSource.GOG), notReady).snapshot(),
            EpicOwnedCopySourceAdapter(epicDao, scopes(GameSource.EPIC), notReady).snapshot(),
            AmazonOwnedCopySourceAdapter(amazonDao, scopes(GameSource.AMAZON), notReady).snapshot(),
        )

        batches.forEach { batch ->
            assertEquals(SnapshotCompleteness.UNAVAILABLE, batch.completeness)
            assertEquals(SnapshotReason.PRESENCE_LEDGER_NOT_READY, batch.reason)
            assertTrue(batch.copies.isEmpty())
        }
        coVerify(exactly = 0) { gogDao.getAllAsList() }
        coVerify(exactly = 0) { epicDao.getAllForCanonicalProjection() }
        coVerify(exactly = 0) { amazonDao.getAllAsList() }
    }

    @Test
    fun providerAdaptersRejectPriorLifecycleLedgerBeforeReadingSourceRows() = runTest {
        val gogDao = mockk<GOGGameDao>(relaxed = true)
        val epicDao = mockk<EpicGameDao>(relaxed = true)
        val amazonDao = mockk<AmazonGameDao>(relaxed = true)
        val staleLedger = mockk<OwnedCopyLedgerDao>(relaxed = true)
        val lifecycleState = InMemoryAccountLifecycleState().apply {
            advanceGeneration(GameSource.GOG)
            advanceGeneration(GameSource.EPIC)
            advanceGeneration(GameSource.AMAZON)
        }
        coEvery { staleLedger.getCompletedSnapshot(any(), any()) } returns CompletedOwnedCopySnapshot(
            completedAt = 1L,
            lifecycleGeneration = 0L,
            stableSourceIds = listOf("old-account"),
        )
        coEvery { staleLedger.getCompletedSnapshotForLifecycle(any(), any(), any()) } returns null
        every { staleLedger.observeSourceHeaders(any()) } returns emptyFlow()

        val batches = listOf(
            GogOwnedCopySourceAdapter(
                gogDao,
                scopes(GameSource.GOG),
                staleLedger,
                lifecycleState,
            ).snapshot(),
            EpicOwnedCopySourceAdapter(
                epicDao,
                scopes(GameSource.EPIC),
                staleLedger,
                lifecycleState,
            ).snapshot(),
            AmazonOwnedCopySourceAdapter(
                amazonDao,
                scopes(GameSource.AMAZON),
                staleLedger,
                lifecycleState,
            ).snapshot(),
        )

        batches.forEach { batch ->
            assertEquals(SnapshotCompleteness.UNAVAILABLE, batch.completeness)
            assertEquals(SnapshotReason.PRESENCE_LEDGER_NOT_READY, batch.reason)
            assertTrue(batch.copies.isEmpty())
        }
        coVerify(exactly = 0) { gogDao.getAllAsList() }
        coVerify(exactly = 0) { epicDao.getAllForCanonicalProjection() }
        coVerify(exactly = 0) { amazonDao.getAllAsList() }
    }

    @Test
    fun completedEmptyLedgerIsCompleteAndExcludesStaleRows() = runTest {
        val dao = mockk<GOGGameDao>()
        coEvery { dao.getAllAsList() } returns listOf(GOGGame(id = "stale-a", title = "Stale"))
        val adapter = GogOwnedCopySourceAdapter(
            dao,
            scopes(GameSource.GOG),
            completedLedger(GameSource.GOG),
        )

        val batch = adapter.snapshot()

        assertEquals(SnapshotCompleteness.COMPLETE, batch.completeness)
        assertTrue(batch.copies.isEmpty())
        assertNull(batch.reason)
    }

    @Test
    fun ownedLedgerMissingRowIsPartialAndNeverIncludesAnotherAccountsRows() = runTest {
        val dao = mockk<GOGGameDao>()
        coEvery { dao.getAllAsList() } returns listOf(
            GOGGame(id = "owned-b", title = "Owned"),
            GOGGame(id = "stale-a", title = "Stale"),
        )
        val adapter = GogOwnedCopySourceAdapter(
            dao,
            scopes(GameSource.GOG),
            completedLedger(GameSource.GOG, "owned-b", "missing-b"),
        )

        val batch = adapter.snapshot()

        assertEquals(SnapshotCompleteness.PARTIAL, batch.completeness)
        assertEquals(SnapshotReason.MISSING_MATERIALIZED_ROW, batch.reason)
        assertEquals(listOf("owned-b"), batch.copies.map { it.key.stableSourceId })
    }

    @Test
    fun accountSwitchDuringSnapshotFailsClosedWithoutReturningOldAccountRows() = runTest {
        val dao = mockk<GOGGameDao>()
        coEvery { dao.getAllAsList() } returns listOf(GOGGame(id = "owned-a", title = "Old account"))
        val scopeProvider = object : AccountScopeProvider {
            private var calls = 0
            override suspend fun current(source: GameSource): AccountScope = if (calls++ == 0) scope else otherScope
        }
        val adapter = GogOwnedCopySourceAdapter(
            dao,
            scopeProvider,
            completedLedger(GameSource.GOG, "owned-a"),
        )

        val batch = adapter.snapshot()

        assertEquals(SnapshotCompleteness.UNAVAILABLE, batch.completeness)
        assertEquals(SnapshotReason.ACCOUNT_SCOPE_CHANGED, batch.reason)
        assertTrue(batch.copies.isEmpty())
    }

    @Test
    fun customInvalidationSignalIsEmittedWithoutScanning() = runTest {
        val awaiting = async(start = CoroutineStart.UNDISPATCHED) {
            CustomGameScanner.canonicalInvalidations().first()
        }

        CustomGameScanner.invalidateCache()

        assertEquals(Unit, awaiting.await())
    }

    private fun steamReadyLifecycle(): InMemoryAccountLifecycleState =
        InMemoryAccountLifecycleState().apply {
            markReady(GameSource.STEAM, 0L)
        }

    private fun completedLedger(source: GameSource, vararg stableSourceIds: String): OwnedCopyLedgerDao {
        val dao = mockk<OwnedCopyLedgerDao>()
        val ids = stableSourceIds.toList().sorted()
        coEvery {
            dao.getCompletedSnapshotForLifecycle(scope.value, source, any())
        } answers {
            CompletedOwnedCopySnapshot(
                completedAt = 1L,
                lifecycleGeneration = invocation.args[2] as Long,
                stableSourceIds = ids,
            )
        }
        coEvery { dao.isPresentForLifecycle(scope.value, source, any(), any()) } answers {
            invocation.args[2] as String in ids
        }
        coEvery { dao.getPresenceForLifecycle(scope.value, source, any(), any()) } answers {
            val stableSourceId = invocation.args[2] as String
            stableSourceId.takeIf(ids::contains)?.let {
                OwnedCopyPresenceEntity(
                    accountScope = scope.value,
                    source = source,
                    stableSourceId = it,
                    resolvedSourceId = if (it == "product-id") "entitlement-id" else null,
                )
            }
        }
        every { dao.observeSourceHeaders(source) } returns emptyFlow()
        return dao
    }

    private fun emptyLedger(): OwnedCopyLedgerDao {
        val dao = mockk<OwnedCopyLedgerDao>(relaxed = true)
        coEvery { dao.getCompletedSnapshotForLifecycle(any(), any(), any()) } returns null
        every { dao.observeSourceHeaders(any()) } returns emptyFlow()
        return dao
    }

    private fun scopes(vararg sources: GameSource): AccountScopeProvider = object : AccountScopeProvider {
        private val available = sources.toSet()

        override suspend fun current(source: GameSource): AccountScope? =
            scope.takeIf { source in available }
    }

    private fun setCustomFolders(value: Set<String>) {
        PrefManager.customGameManualFolders = value
        val deadline = System.nanoTime() + 5_000_000_000L
        while (PrefManager.customGameManualFolders != value && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(value, PrefManager.customGameManualFolders)
    }

    private class SensitiveDaoException : IllegalStateException(SENSITIVE_EXCEPTION_MESSAGE)

    private companion object {
        const val SENSITIVE_EXCEPTION_MESSAGE =
            "private title, account, source id, path, URL, token, and user text"
    }
}
