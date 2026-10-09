package app.gamenative.library.canonical

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.CanonicalGamePreferenceEntity
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.PluviaDatabase
import app.gamenative.library.canonical.action.ActionFailureReason
import app.gamenative.library.canonical.action.ActionSelectionPolicy
import app.gamenative.library.canonical.action.OwnedCopyActionRouter
import app.gamenative.library.canonical.action.OwnedCopyRouteResult
import app.gamenative.library.canonical.artwork.ArtworkFingerprint
import app.gamenative.library.canonical.artwork.ArtworkFingerprintSource
import app.gamenative.library.canonical.artwork.ArtworkFixtures
import app.gamenative.library.canonical.catalog.ResolutionWorkerTestApplication
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.library.metadata.SystemMetadataLocaleProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class CanonicalFamilyActionsTest {
    @get:Rule val timeout = TimeoutRule.seconds(30)
    private lateinit var db: PluviaDatabase
    private lateinit var preferences: PreferredCopyRepository
    private lateinit var router: OwnedCopyActionRouter
    private val registry = mockk<OwnedCopyRuntimeRegistry>()
    private val runtimes = linkedMapOf<OwnedCopyKey, OwnedCopyRuntimeResult>()
    private val firstKey = key(1)
    private val secondKey = key(2)
    private val originals = object : ArtworkFingerprintSource {
        override suspend fun original(source: GameSource, raw: String, steamAppId: Int?) = fingerprint()
        override suspend fun candidate(steamAppId: Int, raw: String): ArtworkFingerprint? = throw AssertionError("No family candidate images")
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PluviaDatabase::class.java)
            .allowMainThreadQueries().build()
        preferences = PreferredCopyRepository(db)
        every { registry.invalidations() } returns emptyFlow()
        coEvery { registry.resolve(any()) } answers { runtimes[firstArg()] ?: OwnedCopyRuntimeResult.Hidden }
        coEvery { registry.resolveAll(any(), any()) } answers {
            secondArg<Set<OwnedCopyKey>>().associateWith { runtimes[it] ?: OwnedCopyRuntimeResult.Hidden }
        }
        val gate = mockk<CanonicalPublicLibraryGate>()
        every { gate.isEnabled() } returns true
        val clock = mockk<CanonicalProjectionClock>()
        every { clock.nowEpochMs() } returns 500L
        router = OwnedCopyActionRouter(registry, preferences, gate, clock, NoOpCanonicalLibraryDiagnosticSink)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun multipleInstalledEditionsRequireRunChooserEvenWithUniqueRecentPlay() = runActionTest {
        seed()
        val family = family()
        assertEquals(OwnedCopyRouteResult.NeedsChooser(listOf(firstKey, secondKey)), router.route(family, OwnedCopyOperation.PLAY))
    }

    @Test
    fun uninstalledPreferenceCannotHideTheChooserForTwoInstalledEditions() = runActionTest {
        seed()
        seedMember(3, "Fixture Game Ultimate")
        installed(firstKey, false)
        db.canonicalPreferenceDao().upsert(preference(1, firstKey))
        assertEquals(OwnedCopyRouteResult.NeedsChooser(listOf(secondKey, key(3))), router.route(family(), OwnedCopyOperation.PLAY))
    }

    @Test
    fun soleInstalledEditionWinsWithoutUsingAnUninstalledRememberedCopy() = runActionTest {
        seed()
        installed(secondKey, false)
        db.canonicalPreferenceDao().upsert(preference(2, secondKey))
        val result = router.route(family(), OwnedCopyOperation.PLAY) as OwnedCopyRouteResult.Ready
        assertEquals(firstKey, result.guard.key)
        assertEquals(ActionSelectionPolicy.SOLE_COPY, result.policy)
    }

    @Test
    fun explicitEditionRemembersItsOwnMemberCanonicalNotTheDisplayAnchor() = runActionTest {
        seed()
        val result = router.route(family(), OwnedCopyOperation.PLAY, secondKey, rememberChoice = true) as OwnedCopyRouteResult.Ready
        assertNull(result.warning)
        assertEquals(secondKey, preferenceKey(2))
        assertNull(preferenceKey(1))
        assertEquals(secondKey, result.guard.key)
        assertEquals("GOG_2", result.guard.initialLibraryItem.appId)
    }

    @Test
    fun explicitFamilyPreferenceAtomicallyClearsConflictingMemberPreferences() = runActionTest {
        seed()
        db.canonicalPreferenceDao().upsert(preference(1, firstKey))
        db.canonicalPreferenceDao().upsert(preference(2, secondKey))
        val captured = family()
        assertNull(captured.preferredCopy)
        val result = router.route(captured, OwnedCopyOperation.PLAY, firstKey, rememberChoice = true) as OwnedCopyRouteResult.Ready
        assertNull(result.warning)
        assertEquals(firstKey, preferenceKey(1))
        assertNull(preferenceKey(2))
        assertEquals(firstKey, family().preferredCopy)
    }

    @Test
    fun familyPreferencePreservesEveryMembersTitleAndArtworkOverrides() = runActionTest {
        seed()
        db.canonicalPreferenceDao().upsert(preference(1, null))
        db.canonicalPreferenceDao().upsert(preference(2, null))
        val result = router.route(family(), OwnedCopyOperation.PLAY, secondKey, rememberChoice = true) as OwnedCopyRouteResult.Ready
        assertNull(result.warning)
        assertEquals(secondKey, preferenceKey(2))
        for (index in 1..2) {
            val preference = db.canonicalPreferenceDao().get(id(index).value)!!
            assertEquals(title(index), preference.titleOverride)
            assertEquals("synthetic-artwork-$index", preference.artworkOverrideJson)
        }
    }

    @Test
    fun changedSiblingMatchRevisionRejectsRememberingWithoutWritingAnyPreference() = runActionTest {
        seed()
        val captured = family()
        db.storeMatchDao().upsert(match(2).copy(matchedAt = 200))
        val result = router.route(captured, OwnedCopyOperation.PLAY, firstKey, rememberChoice = true) as OwnedCopyRouteResult.Ready
        assertEquals(ActionFailureReason.PREFERENCE_WRITE_FAILED, result.warning)
        assertNull(preferenceKey(1))
        assertNull(preferenceKey(2))
    }

    @Test
    fun changedSiblingPreferenceRevisionCannotBeOverwrittenByAnOldFamilyChoice() = runActionTest {
        seed()
        val captured = family()
        db.canonicalPreferenceDao().upsert(preference(2, secondKey).copy(updatedAt = 300))
        val result = router.route(captured, OwnedCopyOperation.PLAY, firstKey, rememberChoice = true) as OwnedCopyRouteResult.Ready
        assertEquals(ActionFailureReason.PREFERENCE_WRITE_FAILED, result.warning)
        assertNull(preferenceKey(1))
        assertEquals(secondKey, preferenceKey(2))
    }

    @Test
    fun clearingFamilyPreferenceClearsExactMembersAndPreservesOverrides() = runActionTest {
        seed()
        db.canonicalPreferenceDao().upsert(preference(1, firstKey))
        db.canonicalPreferenceDao().upsert(preference(2, null))
        invokePreference("clearFamilyPreferredCopy", family(), 500L)
        assertNull(preferenceKey(1))
        assertNull(preferenceKey(2))
        assertEquals("synthetic-artwork-2", db.canonicalPreferenceDao().get(id(2).value)!!.artworkOverrideJson)
    }

    @Test
    fun durableFamilyOnlySeparationPreservesCatalogAndExactCopyDecisions() = runActionTest {
        seed()
        val before = db.canonicalLibraryDao().observePresentGames().first().map { it.game to it.matches }
        invokePreference("setFamilyGroupingSuppressed", family(), secondKey, true, 500L)
        val raw = repository().observeCards().first()
        assertEquals(2, raw.size)
        assertTrue(suppressed(raw.single { it.canonicalId == id(2) }))
        assertEquals(before, db.canonicalLibraryDao().observePresentGames().first().map { it.game to it.matches })
        assertEquals(43, db.canonicalGameDao().get(id(2).value)!!.steamAppId)
        assertEquals(MatchConfidence.HIGH, db.storeMatchDao().getPresent(secondKey.accountScope.value, secondKey.source, secondKey.stableSourceId)!!.confidence)
    }

    @Test
    fun resettingFamilyOnlySeparationRestoresGroupingAfterRepositoryRecreation() = runActionTest {
        seed()
        invokePreference("setFamilyGroupingSuppressed", family(), secondKey, true, 500L)
        val separated = repository().observeCards().first().single { it.canonicalId == id(2) }
        invokePreference("setFamilyGroupingSuppressed", separated, secondKey, false, 600L)
        assertFalse(suppressed(family()))
        assertEquals(setOf(firstKey, secondKey), family().copies.map { it.key }.toSet())
    }

    @Test
    fun staleFamilySeparationFailsClosedWithoutRejectingTheSteamCatalogMatch() = runActionTest {
        seed()
        val captured = family()
        val method = preferenceMethod("setFamilyGroupingSuppressed", 5)
        db.storeMatchDao().upsert(match(2).copy(matchedAt = 200))
        val failure = runCatching { invokePreference(method, captured, secondKey, true, 500L) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(MatchConfidence.HIGH, db.storeMatchDao().getPresent(secondKey.accountScope.value, secondKey.source, secondKey.stableSourceId)!!.confidence)
        assertNull(db.canonicalPreferenceDao().get(id(2).value))
    }

    @Test
    fun newFamilySuppressionStorageDefaultsFalseInsteadOfChangingTrustedIdentity() = runActionTest {
        seed()
        val columns = mutableMapOf<String, String?>()
        db.openHelper.writableDatabase.query("PRAGMA table_info(canonical_game_preference)").use { cursor ->
            while (cursor.moveToNext()) columns[cursor.getString(cursor.getColumnIndexOrThrow("name"))] =
                cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
        }
        assertTrue("Missing additive durable family-only preference", "family_grouping_suppressed" in columns)
        assertEquals("0", columns["family_grouping_suppressed"])
    }

    @Test
    fun rememberedEligibleInstalledEditionStillRoutesExactlyAndNeverSelectsItsSibling() = runActionTest {
        seed()
        db.canonicalPreferenceDao().upsert(preference(2, secondKey))
        val result = router.route(family(), OwnedCopyOperation.PLAY) as OwnedCopyRouteResult.Ready
        assertEquals(ActionSelectionPolicy.PREFERRED, result.policy)
        assertEquals(secondKey, result.guard.key)
        assertEquals("GOG_2", result.guard.initialLibraryItem.appId)
    }

    private fun runActionTest(body: suspend () -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(10_000) { body() } }
    }

    private fun repository() = CanonicalLibraryRepository(db.canonicalLibraryDao(), registry,
        NoOpCanonicalLibraryDiagnosticSink, mockk(relaxed = true), SystemMetadataLocaleProvider(),
        CanonicalFamilyArtworkCorroborator(originals, registry::resolve))
    private suspend fun family() = repository().observeCards().first { it.size == 1 }.single()
    private suspend fun preferenceKey(index: Int) = db.canonicalPreferenceDao().get(id(index).value)?.preferredCopyKeyOrNull()
    private fun id(index: Int) = CanonicalGameId.parse(UUID(0, index.toLong()).toString())
    private fun key(index: Int) = OwnedCopyKey(AccountScope("2".repeat(64)), GameSource.GOG, "$index")
    private fun title(index: Int) = when (index) { 1 -> "Fixture Game"; 2 -> "Fixture Game Deluxe"; else -> "Fixture Game Ultimate" }

    private suspend fun seed() { seedMember(1, title(1)); seedMember(2, title(2)) }
    private suspend fun seedMember(index: Int, name: String) {
        db.canonicalGameDao().insert(CanonicalGameEntity(id(index).value, 41 + index, name, name.lowercase(),
            GameSource.GOG, CanonicalAppType.GAME, null, "", ClassificationState.UNCLASSIFIED, null, 1, 1))
        db.storeMatchDao().upsert(match(index))
        val key = key(index)
        runtimes[key] = OwnedCopyRuntimeResult.Available(OwnedCopyRuntime(
            key = key, reference = SourceOwnedCopyReference.Gog(key, "$index"),
            libraryItem = LibraryItem(appId = "GOG_$index", name = name, gameSource = GameSource.GOG),
            nativeTitle = name, aliases = emptySet(), developerKey = "", releaseYear = null, appType = CanonicalAppType.GAME,
            genreKeys = emptySet(), tagIds = emptySet(), featureKeys = emptySet(), iconUrl = "", capsuleImageUrl = "", headerImageUrl = "",
            heroImageUrl = "", gridHeroImageScale = 1f, installPath = "synthetic-install-$index", installedSizeBytes = index.toLong(),
            branchOrVersion = "synthetic-$index", isInstalled = true, isDownloading = false, hasPartialDownload = false,
            updateAvailable = false, isShared = false, lastPlayedEpochMs = index * 100L, playtimeMinutes = null,
            capabilities = setOf(OwnedCopyOperation.PLAY), originalArtworkUrl = "https://images.gog.com/fixture-$index.jpg",
        ))
    }

    private fun match(index: Int) = StoreMatchEntity(
        accountScope = key(index).accountScope.value, source = GameSource.GOG, stableSourceId = "$index", canonicalId = id(index).value,
        candidateSteamAppId = 41 + index, matchMethod = MatchMethod.STEAM_CATALOG, confidence = MatchConfidence.HIGH,
        decisionSource = MatchDecisionSource.AUTOMATIC, resolverVersion = CURRENT_RESOLVER_VERSION, matchedAt = 100, isPresent = true,
        evidenceDisplayName = title(index), evidenceTitleKey = title(index).lowercase(), evidenceDeveloperKey = "",
        evidenceReleaseYear = null, evidenceAppType = CanonicalAppType.GAME,
    )

    private fun preference(index: Int, preferred: OwnedCopyKey?) = CanonicalGamePreferenceEntity(
        canonicalId = id(index).value, preferredAccountScope = preferred?.accountScope?.value,
        preferredSource = preferred?.source, preferredStableSourceId = preferred?.stableSourceId,
        titleOverride = title(index), artworkOverrideJson = "synthetic-artwork-$index", updatedAt = 100,
    )

    private fun installed(key: OwnedCopyKey, installed: Boolean) {
        val copy = (runtimes.getValue(key) as OwnedCopyRuntimeResult.Available).copy
        runtimes[key] = OwnedCopyRuntimeResult.Available(copy.copy(isInstalled = installed))
    }

    private fun suppressed(card: CanonicalLibraryCard): Boolean {
        val method = card.javaClass.methods.singleOrNull { it.name == "getFamilyGroupingSuppressed" && it.parameterCount == 0 }
        assertTrue("Raw member cards must expose durable family-only separation", method != null)
        return method!!.invoke(card) as Boolean
    }

    private fun preferenceMethod(name: String, count: Int) = preferences.javaClass.methods.singleOrNull { it.name == name && it.parameterCount == count }
        .also { assertTrue("Missing guarded exact-member family preference boundary: $name", it != null) }!!
    private suspend fun invokePreference(name: String, vararg args: Any?) = invokePreference(preferenceMethod(name, args.size + 1), *args)
    private suspend fun invokePreference(method: java.lang.reflect.Method, vararg args: Any?): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
        try { method.invoke(preferences, *args, continuation) } catch (failure: InvocationTargetException) { throw failure.targetException }
    }
    private fun fingerprint() = requireNotNull(ArtworkFingerprint.fromArgb(192, 288, ArtworkFixtures.cover(192, 288)))
}
