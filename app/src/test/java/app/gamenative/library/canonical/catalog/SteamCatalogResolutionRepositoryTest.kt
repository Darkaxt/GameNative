package app.gamenative.library.canonical.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.PluviaDatabase
import app.gamenative.library.canonical.CURRENT_RESOLVER_VERSION
import app.gamenative.library.canonical.CanonicalGuardedMutationResult
import app.gamenative.library.canonical.ExpectedMatchState
import app.gamenative.library.canonical.SteamCatalogDecisionWriter
import app.gamenative.library.metadata.CanonicalGameMetadata
import app.gamenative.library.metadata.GamePlatform
import app.gamenative.library.metadata.MetadataClock
import app.gamenative.library.metadata.MetadataLocale
import app.gamenative.library.metadata.MetadataLocaleProvider
import app.gamenative.library.metadata.SteamCatalogRecord
import app.gamenative.library.metadata.SteamCatalogRecordSource
import app.gamenative.service.steam.SteamWebApiKeyRepository
import app.gamenative.service.steam.SteamWebApiKeySaveResult
import app.gamenative.service.steam.SteamWebApiKeyStatus
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SteamCatalogResolutionRepositoryTest {
    private lateinit var db: PluviaDatabase
    private lateinit var writer: FakeDecisionWriter
    private lateinit var diagnostics: FakeDiagnostics
    private lateinit var enrichment: FakeAcceptedIdentityEnrichment
    private val scope = AccountScope("2".repeat(64))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        writer = FakeDecisionWriter()
        diagnostics = FakeDiagnostics()
        enrichment = FakeAcceptedIdentityEnrichment()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `automatic scan waits for a configured Steam Web API key`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(match(key(GameSource.GOG, "blocked"), canonical.canonicalId, title = "Blocked"))
        val calls = AtomicInteger(0)
        val repository = repository(
            search = SteamCatalogSearchSource { _, _ ->
                calls.incrementAndGet()
                emptyList()
            },
            keyStatus = SteamWebApiKeyStatus.NOT_CONFIGURED,
        )

        val progress = repository.scanAutomatically()

        assertEquals(SteamResolutionProgress(), progress)
        assertEquals(0, calls.get())
        assertTrue(repository.keyRequired.value)
        assertFalse(repository.isScanning.value)
    }

    @Test
    fun `automatic scan searches once per eligible canonical using strongest present evidence`() = runTest {
        val eligible = canonical(1, steamAppId = null)
        val sticky = canonical(2, steamAppId = null)
        val resolved = canonical(3, steamAppId = 99)
        db.canonicalGameDao().insert(eligible)
        db.canonicalGameDao().insert(sticky)
        db.canonicalGameDao().insert(resolved)
        seedMatch(
            match(
                key(GameSource.AMAZON, "weak"),
                eligible.canonicalId,
                title = "Weak Marker",
                developer = "",
                year = null,
                appType = CanonicalAppType.UNKNOWN,
            ),
        )
        seedMatch(
            match(
                key(GameSource.GOG, "strong"),
                eligible.canonicalId,
                title = "Strong Marker",
                developer = "studio",
                year = 2020,
                appType = CanonicalAppType.GAME,
            ),
        )
        seedMatch(
            match(key(GameSource.EPIC, "sticky"), sticky.canonicalId, title = "Sticky Marker")
                .copy(
                    decisionSource = MatchDecisionSource.USER,
                    confidence = MatchConfidence.REJECTED,
                    matchMethod = MatchMethod.MANUAL,
                    candidateSteamAppId = 77,
                ),
        )
        seedMatch(
            match(key(GameSource.GOG, "resolved"), resolved.canonicalId, title = "Resolved Marker"),
        )
        val queries = mutableListOf<String>()
        val repository = repository(
            search = SteamCatalogSearchSource { query, _ ->
                queries += query
                emptyList()
            },
        )

        val progress = repository.scanAutomatically()

        assertEquals(listOf("Strong Marker"), queries)
        assertEquals(1, progress.total)
        assertEquals(1, progress.completed)
        assertEquals(1, progress.unmatched)
        assertEquals(1, writer.operations.filterIsInstance<DecisionOperation.Unmatched>().size)
    }

    @Test
    fun `automatic scan serializes and paces provider work while isolating item failures`() = runTest {
        repeat(4) { index ->
            val canonical = canonical(index + 1L, steamAppId = null)
            db.canonicalGameDao().insert(canonical)
            seedMatch(
                match(
                    key(GameSource.GOG, "copy-$index"),
                    canonical.canonicalId,
                    title = if (index == 1) "Failure Marker" else "Game $index",
                ),
            )
        }
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val calls = AtomicInteger(0)
        val startTimes = mutableListOf<Long>()
        val repository = repository(
            search = SteamCatalogSearchSource { query, _ ->
                startTimes += testScheduler.currentTime
                calls.incrementAndGet()
                val current = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, current) }
                try {
                    delay(100)
                    if (query == "Failure Marker") error("private provider detail")
                    emptyList()
                } finally {
                    active.decrementAndGet()
                }
            },
        )

        val progress = repository.scanAutomatically()

        assertEquals(4, calls.get())
        assertEquals(1, maxActive.get())
        assertTrue(startTimes.zipWithNext().all { (left, right) -> right - left >= 350L })
        assertEquals(4, progress.completed)
        assertEquals(4, progress.total)
        assertEquals(1, progress.failed)
        assertEquals(3, progress.unmatched)
    }

    @Test
    fun `automatic scan validates at most five hits and accepts one corroborated exact candidate`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        val selected = match(
            key(GameSource.EPIC, "bounded"),
            canonical.canonicalId,
            title = "Exact Marker",
            developer = "studio",
            year = 2020,
        )
        db.canonicalGameDao().insert(canonical)
        seedMatch(selected)
        val fetched = mutableListOf<Int>()
        val repository = repository(
            search = SteamCatalogSearchSource { _, _ ->
                (1..10).map { id ->
                    SteamStoreSearchHit(
                        steamAppId = id,
                        title = if (id == 1) "Exact Marker" else "Other $id",
                        headerImageUrl = null,
                    )
                }
            },
            records = SteamCatalogRecordSource { steamAppId, _ ->
                fetched += steamAppId
                record(
                    steamAppId = steamAppId,
                    title = if (steamAppId == 1) "Exact Marker" else "Other $steamAppId",
                    developer = "Studio Ltd",
                    year = 2020,
                )
            },
        )

        val progress = repository.scanAutomatically()

        assertEquals(listOf(1, 2, 3, 4, 5), fetched)
        assertEquals(1, progress.autoAccepted)
        val accepted = writer.operations.single() as DecisionOperation.Accepted
        assertEquals(1, accepted.steamAppId)
        assertEquals(CanonicalAppType.GAME, accepted.appType)
        assertEquals(listOf(EnrichmentCall(1, MetadataLocale("en-US", "US"))), enrichment.calls)
    }

    @Test
    fun `automatic scan defers to review when one exact candidate cannot be validated`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(
            match(
                key(GameSource.GOG, "partial-details"),
                canonical.canonicalId,
                title = "Exact Marker",
            ),
        )
        val repository = repository(
            search = SteamCatalogSearchSource { _, _ ->
                listOf(
                    SteamStoreSearchHit(1, "Exact Marker", null),
                    SteamStoreSearchHit(2, "Exact Marker", null),
                )
            },
            records = SteamCatalogRecordSource { steamAppId, _ ->
                if (steamAppId == 1) {
                    record(steamAppId, "Exact Marker", "Studio", 2020)
                } else {
                    error("candidate details unavailable")
                }
            },
        )

        val progress = repository.scanAutomatically()

        assertEquals(0, progress.failed)
        assertEquals(1, progress.needsReview)
        assertEquals(0, progress.autoAccepted)
        val recorded = writer.operations.single() as DecisionOperation.Review
        assertEquals(1, recorded.steamAppId)
    }

    @Test
    fun `manual search ignores completed automatic session cooldown`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        val selected = match(key(GameSource.GOG, "manual"), canonical.canonicalId, title = "Manual Marker")
        db.canonicalGameDao().insert(canonical)
        seedMatch(selected)
        val calls = AtomicInteger(0)
        val repository = repository(
            search = SteamCatalogSearchSource { _, _ ->
                calls.incrementAndGet()
                emptyList()
            },
        )
        val expected = expected(selected)

        repository.scanAutomatically()
        repository.scanAutomatically()
        repository.searchManually(expected, "Manual Marker")
        repository.searchManually(expected, "Manual Marker")

        assertEquals(3, calls.get())
    }

    @Test
    fun `explicit retries reset local catalog refresh backoff while direct AppID does not`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        val selected = match(key(GameSource.GOG, "refresh"), canonical.canonicalId, title = "Refresh Marker")
        db.canonicalGameDao().insert(canonical)
        seedMatch(selected)
        val retryRequests = AtomicInteger(0)
        val search = object : SteamCatalogSearchSource {
            override suspend fun search(
                query: String,
                locale: MetadataLocale,
            ): List<SteamStoreSearchHit> = emptyList()

            override fun requestImmediateRetry() {
                retryRequests.incrementAndGet()
            }
        }
        val repository = repository(search = search)

        repository.retryAutomatically()
        repository.searchManually(expected(selected), "Refresh Marker")
        repository.searchManually(expected(selected), "42")

        assertEquals(2, retryRequests.get())
    }

    @Test
    fun `automatic retry clears the process session gate and reruns failed work`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(match(key(GameSource.GOG, "retry"), canonical.canonicalId, title = "Retry Marker"))
        val calls = AtomicInteger(0)
        val repository = repository(
            search = SteamCatalogSearchSource { _, _ ->
                if (calls.incrementAndGet() == 1) error("provider unavailable")
                emptyList()
            },
        )

        val failed = repository.scanAutomatically()
        val retried = repository.retryAutomatically()

        assertEquals(1, failed.failed)
        assertEquals(2, calls.get())
        assertEquals(0, retried.failed)
        assertEquals(1, retried.unmatched)
        assertEquals(1, writer.operations.filterIsInstance<DecisionOperation.Unmatched>().size)
    }

    @Test
    fun `automatic retry revalidates a current automatic review decision`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        val selected = match(
            key(GameSource.EPIC, "current-review"),
            canonical.canonicalId,
            title = "Exact Marker",
            developer = "studio",
            year = 2020,
        ).copy(
            candidateSteamAppId = 42,
            matchMethod = MatchMethod.STEAM_CATALOG,
            confidence = MatchConfidence.REVIEW_REQUIRED,
        )
        db.canonicalGameDao().insert(canonical)
        seedMatch(selected)
        val calls = AtomicInteger(0)
        val repository = repository(
            search = SteamCatalogSearchSource { query, _ ->
                calls.incrementAndGet()
                listOf(SteamStoreSearchHit(42, query, null))
            },
            records = SteamCatalogRecordSource { steamAppId, _ ->
                record(steamAppId, "Exact Marker", "Studio", 2020)
            },
        )

        val automatic = repository.scanAutomatically()
        val retried = repository.retryAutomatically()

        assertEquals(0, automatic.total)
        assertEquals(1, retried.total)
        assertEquals(1, retried.autoAccepted)
        assertEquals(1, calls.get())
        assertTrue(writer.operations.single() is DecisionOperation.Accepted)
    }

    @Test
    fun `automatic scan revalidates review decisions persisted by the previous resolver`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        val selected = match(
            key(GameSource.EPIC, "previous-review"),
            canonical.canonicalId,
            title = "Exact Marker",
            developer = "studio",
            year = 2020,
        ).copy(
            candidateSteamAppId = 42,
            matchMethod = MatchMethod.STEAM_CATALOG,
            confidence = MatchConfidence.REVIEW_REQUIRED,
            resolverVersion = 2,
        )
        db.canonicalGameDao().insert(canonical)
        seedMatch(selected)
        val repository = repository(
            search = SteamCatalogSearchSource { query, _ ->
                listOf(SteamStoreSearchHit(42, query, null))
            },
            records = SteamCatalogRecordSource { steamAppId, _ ->
                record(steamAppId, "Exact Marker", "Studio", 2020)
            },
        )

        val progress = repository.scanAutomatically()

        assertTrue(CURRENT_RESOLVER_VERSION > selected.resolverVersion)
        assertEquals(1, progress.total)
        assertEquals(1, progress.autoAccepted)
        assertTrue(writer.operations.single() is DecisionOperation.Accepted)
    }

    @Test
    fun `manual confirmation enriches the validated selected identity`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        val selected = match(key(GameSource.GOG, "manual-confirm"), canonical.canonicalId, title = "Manual Marker")
        db.canonicalGameDao().insert(canonical)
        seedMatch(selected)
        val repository = repository(
            search = SteamCatalogSearchSource { _, _ ->
                listOf(SteamStoreSearchHit(42, "Manual Marker", null))
            },
            records = SteamCatalogRecordSource { steamAppId, _ ->
                record(steamAppId, "Manual Marker", "Studio", 2020)
            },
        )

        repository.searchManually(expected(selected), "Manual Marker")
        val result = repository.confirmCandidate(expected(selected), 42)

        assertEquals(CanonicalGuardedMutationResult.APPLIED, result)
        assertEquals(listOf(EnrichmentCall(42, MetadataLocale("en-US", "US"))), enrichment.calls)
        assertTrue(writer.operations.single() is DecisionOperation.Confirmed)
    }

    @Test
    fun `scan emits fixed outcome categories without private catalog text`() = runTest {
        val specs = listOf(
            Triple(1L, "Auto Private Marker", 424242),
            Triple(2L, "Review Private Marker", 424243),
            Triple(3L, "None Private Marker", 424244),
            Triple(4L, "Fail Private Marker", 424245),
        )
        specs.forEach { (index, title, _) ->
            val canonical = canonical(index, steamAppId = null)
            db.canonicalGameDao().insert(canonical)
            seedMatch(match(key(GameSource.GOG, "private-$index"), canonical.canonicalId, title = title))
        }
        val repository = repository(
            search = SteamCatalogSearchSource { query, _ ->
                when {
                    query.startsWith("Auto") -> listOf(SteamStoreSearchHit(424242, query, null))
                    query.startsWith("Review") -> listOf(SteamStoreSearchHit(424243, query, null))
                    query.startsWith("None") -> emptyList()
                    else -> error("response URL private marker")
                }
            },
            records = SteamCatalogRecordSource { steamAppId, _ ->
                when (steamAppId) {
                    424242 -> record(steamAppId, "Auto Private Marker", "Studio", 2020)
                    424243 -> record(steamAppId, "Review Private Marker", null, null)
                    else -> null
                }
            },
        )

        val progress = repository.scanAutomatically()

        assertEquals(1, progress.autoAccepted)
        assertEquals(1, progress.needsReview)
        assertEquals(1, progress.unmatched)
        assertEquals(1, progress.failed)
        val diagnosticText = diagnostics.events.joinToString("\n")
        specs.forEach { (_, title, appId) ->
            assertFalse(diagnosticText.contains(title))
            assertFalse(diagnosticText.contains(appId.toString()))
        }
        assertFalse(diagnosticText.contains("response URL"))
        assertEquals(
            listOf("UNEXPECTED_FAILURE"),
            diagnostics.events.mapNotNull(SteamResolutionDiagnosticEvent::errorType),
        )
        assertTrue(diagnostics.events.map(SteamResolutionDiagnosticEvent::result).containsAll(
            listOf(
                SteamResolutionItemResult.AutoAccepted,
                SteamResolutionItemResult.ReviewRequired,
                SteamResolutionItemResult.Unmatched,
                SteamResolutionItemResult.ProviderUnavailable,
            ),
        ))
    }

    @Test
    fun `provider diagnostics distinguish catalog details and partial failures`() = runTest {
        listOf("Index Marker", "Details Marker", "Partial Marker").forEachIndexed { index, title ->
            val canonical = canonical(index + 1L, steamAppId = null)
            db.canonicalGameDao().insert(canonical)
            seedMatch(
                match(
                    key(GameSource.GOG, "failure-${index + 1}"),
                    canonical.canonicalId,
                    title = title,
                ),
            )
        }
        val repository = repository(
            search = SteamCatalogSearchSource { query, _ ->
                when (query) {
                    "Index Marker" -> throw SteamCatalogSearchException()
                    "Details Marker" -> listOf(SteamStoreSearchHit(1, query, null))
                    else -> listOf(
                        SteamStoreSearchHit(2, query, null),
                        SteamStoreSearchHit(3, query, null),
                    )
                }
            },
            records = SteamCatalogRecordSource { steamAppId, _ ->
                when (steamAppId) {
                    2 -> record(steamAppId, "Partial Marker", "Studio", 2020)
                    else -> error("private app details failure")
                }
            },
        )

        repository.scanAutomatically()

        assertEquals(
            listOf(
                "APP_LIST_UNAVAILABLE",
                "APP_DETAILS_UNAVAILABLE",
                "CANDIDATE_DETAILS_INCOMPLETE",
            ),
            diagnostics.events.map(SteamResolutionDiagnosticEvent::errorType),
        )
        assertEquals(
            listOf(
                SteamResolutionItemResult.ProviderUnavailable,
                SteamResolutionItemResult.ProviderUnavailable,
                SteamResolutionItemResult.ReviewRequired,
            ),
            diagnostics.events.map(SteamResolutionDiagnosticEvent::result),
        )
    }

    @Test
    fun `cancellation propagates and clears scanning state`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(match(key(GameSource.GOG, "cancel"), canonical.canonicalId, title = "Cancel Marker"))
        val started = CompletableDeferred<Unit>()
        val repository = repository(
            search = SteamCatalogSearchSource { _, _ ->
                started.complete(Unit)
                awaitCancellation()
            },
        )
        val job = launch { repository.scanAutomatically() }
        started.await()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertFalse(repository.isScanning.value)
    }

    private fun repository(
        search: SteamCatalogSearchSource,
        records: SteamCatalogRecordSource = SteamCatalogRecordSource { _, _ -> null },
        keyStatus: SteamWebApiKeyStatus = SteamWebApiKeyStatus.CONFIGURED,
    ) = SteamCatalogResolutionRepository(
        storeMatchDao = db.storeMatchDao(),
        searchSource = search,
        recordSource = records,
        candidatePolicy = SteamCatalogCandidatePolicy(),
        decisionWriter = writer,
        localeProvider = MetadataLocaleProvider { MetadataLocale("en-US", "US") },
        diagnostics = diagnostics,
        acceptedIdentityEnrichment = enrichment,
        clock = MetadataClock { 1_000L },
        steamWebApiKeyRepository = object : SteamWebApiKeyRepository {
            override val changes: SharedFlow<SteamWebApiKeyStatus> = MutableSharedFlow()

            override suspend fun status(): SteamWebApiKeyStatus = keyStatus

            override suspend fun save(key: String): SteamWebApiKeySaveResult =
                SteamWebApiKeySaveResult.SAVED

            override suspend fun delete() = Unit
        },
    )

    private suspend fun seedMatch(match: StoreMatchEntity) {
        db.storeMatchDao().upsert(match)
    }

    private fun canonical(index: Long, steamAppId: Int?) = CanonicalGameEntity(
        canonicalId = UUID(0, index).toString(),
        steamAppId = steamAppId,
        displayName = "Game $index",
        matchTitleKey = "game $index",
        primaryMetadataSource = GameSource.GOG,
        appType = CanonicalAppType.GAME,
        releaseYear = 2020,
        developerKey = "studio",
        classificationState = ClassificationState.UNCLASSIFIED,
        steamReviewCount = null,
        createdAt = index,
        updatedAt = index,
    )

    private fun match(
        key: OwnedCopyKey,
        canonicalId: String,
        title: String,
        developer: String = "studio",
        year: Int? = 2020,
        appType: CanonicalAppType = CanonicalAppType.GAME,
    ) = StoreMatchEntity(
        accountScope = key.accountScope.value,
        source = key.source,
        stableSourceId = key.stableSourceId,
        canonicalId = canonicalId,
        candidateSteamAppId = null,
        matchMethod = MatchMethod.UNMATCHED,
        confidence = MatchConfidence.UNMATCHED,
        decisionSource = MatchDecisionSource.AUTOMATIC,
        resolverVersion = CURRENT_RESOLVER_VERSION,
        matchedAt = 100,
        isPresent = true,
        evidenceDisplayName = title,
        evidenceTitleKey = title.lowercase(),
        evidenceDeveloperKey = developer,
        evidenceReleaseYear = year,
        evidenceAppType = appType,
    )

    private fun expected(match: StoreMatchEntity) = ExpectedMatchState(
        key = OwnedCopyKey(
            accountScope = AccountScope.parse(match.accountScope),
            source = match.source,
            stableSourceId = match.stableSourceId,
        ),
        canonicalId = match.canonicalId,
        matchMethod = match.matchMethod,
        confidence = match.confidence,
        decisionSource = match.decisionSource,
        candidateSteamAppId = match.candidateSteamAppId,
        resolverVersion = match.resolverVersion,
        decisionRevision = match.matchedAt,
    )

    private fun key(source: GameSource, stableSourceId: String) = OwnedCopyKey(
        accountScope = scope,
        source = source,
        stableSourceId = stableSourceId,
    )

    private fun record(
        steamAppId: Int,
        title: String,
        developer: String?,
        year: Int?,
    ) = SteamCatalogRecord(
        steamAppId = steamAppId,
        appType = CanonicalAppType.GAME,
        releaseYear = year,
        metadata = CanonicalGameMetadata(
            title = title,
            shortDescription = null,
            about = null,
            headerImageUrl = null,
            screenshots = emptyList(),
            movies = emptyList(),
            developers = listOfNotNull(developer),
            publishers = emptyList(),
            releaseDate = year?.toString(),
            platforms = setOf(GamePlatform.WINDOWS),
            languages = emptyList(),
            requirements = null,
            features = emptyList(),
            achievementCount = null,
            dlcCount = null,
            fetchedAtEpochMs = 1_000,
        ),
    )

    private class FakeDecisionWriter : SteamCatalogDecisionWriter {
        val operations = mutableListOf<DecisionOperation>()

        override suspend fun recordCandidate(
            expected: ExpectedMatchState,
            steamAppId: Int,
            resolverVersion: Int,
            nowEpochMs: Long,
        ) = applied(DecisionOperation.Review(expected.key, steamAppId))

        override suspend fun acceptAutomatic(
            expected: ExpectedMatchState,
            steamAppId: Int,
            candidateAppType: CanonicalAppType,
            resolverVersion: Int,
            nowEpochMs: Long,
        ) = applied(DecisionOperation.Accepted(expected.key, steamAppId, candidateAppType))

        override suspend fun confirm(
            expected: ExpectedMatchState,
            steamAppId: Int,
            candidateAppType: CanonicalAppType,
            nowEpochMs: Long,
        ) = applied(DecisionOperation.Confirmed(expected.key, steamAppId, candidateAppType))

        override suspend fun reject(
            expected: ExpectedMatchState,
            steamAppId: Int,
            nowEpochMs: Long,
        ) = applied(DecisionOperation.Rejected(expected.key, steamAppId))

        override suspend fun reset(
            expected: ExpectedMatchState,
            nowEpochMs: Long,
        ) = applied(DecisionOperation.Reset(expected.key))

        override suspend fun recordUnmatched(
            expected: ExpectedMatchState,
            resolverVersion: Int,
            nowEpochMs: Long,
        ) = applied(DecisionOperation.Unmatched(expected.key))

        private fun applied(operation: DecisionOperation): CanonicalGuardedMutationResult {
            operations += operation
            return CanonicalGuardedMutationResult.APPLIED
        }
    }

    private class FakeAcceptedIdentityEnrichment : SteamAcceptedIdentityEnrichmentSink {
        val calls = mutableListOf<EnrichmentCall>()

        override suspend fun enrich(
            trustedSteamAppId: Int,
            locale: MetadataLocale,
            record: SteamCatalogRecord,
        ): SteamAcceptedIdentityEnrichmentResult {
            calls += EnrichmentCall(trustedSteamAppId, locale)
            return SteamAcceptedIdentityEnrichmentResult.Enriched
        }
    }

    private class FakeDiagnostics : SteamCatalogResolutionDiagnosticSink {
        val events = mutableListOf<SteamResolutionDiagnosticEvent>()

        override fun record(event: SteamResolutionDiagnosticEvent) {
            events += event
        }
    }

    private data class EnrichmentCall(
        val steamAppId: Int,
        val locale: MetadataLocale,
    )

    private sealed interface DecisionOperation {
        val key: OwnedCopyKey

        data class Accepted(
            override val key: OwnedCopyKey,
            val steamAppId: Int,
            val appType: CanonicalAppType,
        ) : DecisionOperation

        data class Review(override val key: OwnedCopyKey, val steamAppId: Int) : DecisionOperation
        data class Unmatched(override val key: OwnedCopyKey) : DecisionOperation
        data class Confirmed(
            override val key: OwnedCopyKey,
            val steamAppId: Int,
            val appType: CanonicalAppType,
        ) : DecisionOperation
        data class Rejected(override val key: OwnedCopyKey, val steamAppId: Int) : DecisionOperation
        data class Reset(override val key: OwnedCopyKey) : DecisionOperation
    }
}
