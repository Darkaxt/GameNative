package app.gamenative.library.canonical.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
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
import app.gamenative.db.PluviaDatabase
import app.gamenative.library.canonical.CURRENT_RESOLVER_VERSION
import app.gamenative.library.canonical.CanonicalGuardedMutationResult
import app.gamenative.library.canonical.EpicCatalogFallbackWriter
import app.gamenative.library.canonical.ExpectedMatchState
import app.gamenative.library.canonical.SteamCatalogDecisionWriter
import app.gamenative.library.metadata.CanonicalGameMetadata
import app.gamenative.library.metadata.EpicCmsCatalogRecord
import app.gamenative.library.metadata.EpicCmsCatalogRequest
import app.gamenative.library.metadata.EpicCmsCatalogSource
import app.gamenative.library.metadata.GamePlatform
import app.gamenative.library.metadata.MetadataClock
import app.gamenative.library.metadata.MetadataLocale
import app.gamenative.library.metadata.MetadataLocaleProvider
import app.gamenative.library.metadata.PcGamingWikiCurrentAvailabilityEvidence
import app.gamenative.library.metadata.PcGamingWikiCurrentAvailabilityRequest
import app.gamenative.library.metadata.PcGamingWikiCurrentAvailabilityResult
import app.gamenative.library.metadata.PcGamingWikiCurrentAvailabilitySource
import app.gamenative.library.metadata.SteamCatalogRecord
import app.gamenative.library.metadata.SteamCatalogRecordSource
import app.gamenative.library.metadata.SteamRateLimitExhaustedException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private lateinit var epicFallbackWriter: FakeEpicFallbackWriter
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
        epicFallbackWriter = FakeEpicFallbackWriter()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `automatic scan uses keyless Store search without a configured Web API key`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(match(key(GameSource.GOG, "keyless"), canonical.canonicalId, title = "Keyless"))
        val calls = AtomicInteger(0)
        val repository = repository(
            search = SteamCatalogSearchSource { _, _ ->
                calls.incrementAndGet()
                emptyList()
            },
        )

        val progress = repository.scanAutomatically()

        assertEquals(1, progress.total)
        assertEquals(1, progress.completed)
        assertEquals(1, progress.unmatched)
        assertEquals(1, calls.get())
        assertFalse(repository.keyRequired.value)
        assertFalse(repository.isScanning.value)
    }

    @Test
    fun `complete Steam miss checks PCGW before Epic CMS and carries confirmed label`() = runTest {
        val namespace = "c4763f236d08423eb47b4c3008779c84"
        val catalogId = "93f2a8c3547846eda966cb3c152a026e"
        val stableSourceId = EpicStableSourceId.encode(namespace, catalogId)
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(
            match(
                key(GameSource.EPIC, stableSourceId),
                canonical.canonicalId,
                title = "Alan Wake 2",
                developer = "remedy entertainment",
                year = 2023,
            ),
        )
        val order = mutableListOf<String>()
        val availabilityRequests = mutableListOf<PcGamingWikiCurrentAvailabilityRequest>()
        val evidence = PcGamingWikiCurrentAvailabilityEvidence(sourceRevision = 1_783_673L)
        val repository = repository(
            search = SteamCatalogSearchSource { _, _ ->
                order += "steam-search"
                listOf(SteamStoreSearchHit(3_274_290, "Alan Wake 2", null))
            },
            records = SteamCatalogRecordSource { steamAppId, _ ->
                order += "steam-details"
                record(
                    steamAppId = steamAppId,
                    title = "Alan Wake 2",
                    developer = "Remedy Entertainment",
                    year = 2023,
                    appType = CanonicalAppType.APPLICATION,
                )
            },
            pcGamingWikiSource = PcGamingWikiCurrentAvailabilitySource { request ->
                order += "pcgw"
                availabilityRequests += request
                PcGamingWikiCurrentAvailabilityResult.Confirmed(evidence)
            },
            epicCatalogSource = EpicCmsCatalogSource { request ->
                order += "epic-cms"
                epicRecord(request.stableSourceId, namespace, catalogId, request.sourceTitle)
            },
        )
        epicFallbackWriter.onRecord = { order += "fallback-writer" }

        val progress = repository.scanAutomatically()

        assertEquals(1, progress.unmatched)
        assertEquals(
            listOf("steam-search", "steam-details", "pcgw", "epic-cms", "fallback-writer"),
            order,
        )
        assertEquals(
            listOf(
                PcGamingWikiCurrentAvailabilityRequest(
                    sourceTitle = "Alan Wake 2",
                    sourceReleaseYear = 2023,
                    sourceDeveloper = "remedy entertainment",
                    sourcePublisher = null,
                ),
            ),
            availabilityRequests,
        )
        assertEquals(evidence, epicFallbackWriter.calls.single().decisionEvidence)
        assertEquals(
            "PCGW_CURRENT_EGS_ACCOUNT_REQUIRED",
            epicFallbackWriter.calls.single().decisionEvidence?.label?.name,
        )
        assertEquals(stableSourceId, epicFallbackWriter.calls.single().key.stableSourceId)
        assertEquals(
            SteamResolutionItemResult.CompleteNoPlausibleSteamMatch(
                epicPresentation = EpicPresentationOutcome.EPIC_CMS_PERSISTED,
                pcGamingWikiEvidence = evidence,
            ),
            diagnostics.events.single().result,
        )
        assertTrue(writer.operations.isEmpty())
    }

    @Test
    fun `PCGW not confirmed remains non blocking and still invokes Epic CMS`() = runTest {
        val namespace = "namespace"
        val catalogId = "catalog"
        val stableSourceId = EpicStableSourceId.encode(namespace, catalogId)
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(match(key(GameSource.EPIC, stableSourceId), canonical.canonicalId, "Epic Native"))
        val calls = mutableListOf<String>()
        val repository = repository(
            search = SteamCatalogSearchSource { _, _ -> emptyList() },
            pcGamingWikiSource = PcGamingWikiCurrentAvailabilitySource {
                calls += "pcgw-not-confirmed"
                PcGamingWikiCurrentAvailabilityResult.NotConfirmed
            },
            epicCatalogSource = EpicCmsCatalogSource { request ->
                calls += "epic-cms"
                epicRecord(request.stableSourceId, namespace, catalogId, request.sourceTitle)
            },
        )

        val progress = repository.scanAutomatically()

        assertEquals(1, progress.unmatched)
        assertEquals(listOf("pcgw-not-confirmed", "epic-cms"), calls)
        assertNull(epicFallbackWriter.calls.single().decisionEvidence)
    }

    @Test
    fun `PCGW unavailable remains non blocking and still invokes Epic CMS`() = runTest {
        val namespace = "namespace"
        val catalogId = "catalog"
        val stableSourceId = EpicStableSourceId.encode(namespace, catalogId)
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(match(key(GameSource.EPIC, stableSourceId), canonical.canonicalId, "Epic Native"))
        val calls = mutableListOf<String>()
        val repository = repository(
            search = SteamCatalogSearchSource { _, _ -> emptyList() },
            pcGamingWikiSource = PcGamingWikiCurrentAvailabilitySource {
                calls += "pcgw-unavailable"
                PcGamingWikiCurrentAvailabilityResult.Unavailable
            },
            epicCatalogSource = EpicCmsCatalogSource { request ->
                calls += "epic-cms"
                epicRecord(request.stableSourceId, namespace, catalogId, request.sourceTitle)
            },
        )

        val progress = repository.scanAutomatically()

        assertEquals(1, progress.unmatched)
        assertEquals(listOf("pcgw-unavailable", "epic-cms"), calls)
        assertNull(epicFallbackWriter.calls.single().decisionEvidence)
    }

    @Test
    fun `partial Steam result never invokes PCGW or Epic CMS`() = runTest {
        val stableSourceId = EpicStableSourceId.encode("namespace", "catalog")
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(match(key(GameSource.EPIC, stableSourceId), canonical.canonicalId, "Partial Epic"))
        val search = object : SteamCatalogSearchSource {
            override suspend fun search(query: String, locale: MetadataLocale) = emptyList<SteamStoreSearchHit>()

            override suspend fun searchResult(query: String, locale: MetadataLocale) =
                SteamCatalogSearchResult(emptyList(), complete = false)
        }
        val repository = repository(
            search = search,
            pcGamingWikiSource = PcGamingWikiCurrentAvailabilitySource {
                throw AssertionError("PCGW must not run after partial Steam evidence")
            },
            epicCatalogSource = EpicCmsCatalogSource {
                throw AssertionError("Epic CMS must not run after partial Steam evidence")
            },
        )

        val progress = repository.scanAutomatically()

        assertEquals(1, progress.failed)
        assertEquals(0, progress.unmatched)
        assertTrue(epicFallbackWriter.calls.isEmpty())
    }

    @Test
    fun `complete Epic game unmatched persists CMS fallback atomically`() = runTest {
        val namespace = "c4763f236d08423eb47b4c3008779c84"
        val catalogId = "93f2a8c3547846eda966cb3c152a026e"
        val stableSourceId = EpicStableSourceId.encode(namespace, catalogId)
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(match(key(GameSource.EPIC, stableSourceId), canonical.canonicalId, title = "Alan Wake 2"))
        val requests = mutableListOf<EpicCmsCatalogRequest>()
        val repository = repository(
            search = SteamCatalogSearchSource { _, _ -> emptyList() },
            epicCatalogSource = EpicCmsCatalogSource { request ->
                requests += request
                epicRecord(request.stableSourceId, namespace, catalogId, request.sourceTitle)
            },
        )

        val progress = repository.scanAutomatically()

        assertEquals(1, progress.unmatched)
        assertEquals(0, progress.failed)
        assertTrue(writer.operations.isEmpty())
        assertEquals(listOf("Alan Wake 2"), requests.map(EpicCmsCatalogRequest::sourceTitle))
        assertEquals(1, epicFallbackWriter.calls.size)
        assertEquals(stableSourceId, epicFallbackWriter.calls.single().record.stableSourceId)
    }

    @Test
    fun `complete Epic result with only non game Steam hit uses CMS fallback`() = runTest {
        val namespace = "c4763f236d08423eb47b4c3008779c84"
        val catalogId = "93f2a8c3547846eda966cb3c152a026e"
        val stableSourceId = EpicStableSourceId.encode(namespace, catalogId)
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(match(key(GameSource.EPIC, stableSourceId), canonical.canonicalId, title = "Alan Wake 2"))
        val requests = mutableListOf<EpicCmsCatalogRequest>()
        val repository = repository(
            search = SteamCatalogSearchSource { _, _ ->
                listOf(SteamStoreSearchHit(3_274_290, "Alan Wake 2", null))
            },
            records = SteamCatalogRecordSource { steamAppId, _ ->
                record(
                    steamAppId = steamAppId,
                    title = "Alan Wake 2",
                    developer = null,
                    year = null,
                    appType = CanonicalAppType.APPLICATION,
                )
            },
            epicCatalogSource = EpicCmsCatalogSource { request ->
                requests += request
                epicRecord(request.stableSourceId, namespace, catalogId, request.sourceTitle)
            },
        )

        val progress = repository.scanAutomatically()

        assertEquals(1, progress.unmatched)
        assertEquals(0, progress.needsReview)
        assertEquals(0, progress.failed)
        assertEquals(listOf("Alan Wake 2"), requests.map(EpicCmsCatalogRequest::sourceTitle))
        assertEquals(1, epicFallbackWriter.calls.size)
    }

    @Test
    fun `complete Epic miss records Steam outcome when CMS is unavailable`() = runTest {
        val namespace = "c4763f236d08423eb47b4c3008779c84"
        val catalogId = "93f2a8c3547846eda966cb3c152a026e"
        val stableSourceId = EpicStableSourceId.encode(namespace, catalogId)
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(match(key(GameSource.EPIC, stableSourceId), canonical.canonicalId, title = "Alan Wake 2"))
        val repository = repository(
            search = SteamCatalogSearchSource { _, _ -> emptyList() },
            epicCatalogSource = EpicCmsCatalogSource { null },
        )

        val progress = repository.scanAutomatically()

        assertEquals(0, progress.failed)
        assertEquals(1, progress.unmatched)
        assertEquals(1, writer.operations.filterIsInstance<DecisionOperation.Unmatched>().size)
        assertTrue(epicFallbackWriter.calls.isEmpty())
        val event = diagnostics.events.single()
        assertEquals("EPIC_CMS_UNAVAILABLE", event.errorType)
        assertEquals(
            SteamResolutionItemResult.CompleteNoPlausibleSteamMatch(
                EpicPresentationOutcome.EPIC_CMS_UNAVAILABLE,
            ),
            event.result,
        )
    }

    @Test
    fun `Epic CMS rate exhaustion preserves complete Steam miss`() = runTest {
        val stableSourceId = EpicStableSourceId.encode("namespace", "catalog")
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(match(key(GameSource.EPIC, stableSourceId), canonical.canonicalId, title = "Epic Exclusive"))
        val repository = repository(
            search = SteamCatalogSearchSource { _, _ -> emptyList() },
            epicCatalogSource = EpicCmsCatalogSource { throw SteamRateLimitExhaustedException() },
        )

        val progress = repository.scanAutomatically()

        assertEquals(0, progress.failed)
        assertEquals(1, progress.unmatched)
        assertEquals(1, writer.operations.filterIsInstance<DecisionOperation.Unmatched>().size)
        assertTrue(epicFallbackWriter.calls.isEmpty())
        val event = diagnostics.events.single()
        assertEquals("RATE_LIMIT_EXHAUSTED", event.errorType)
        assertEquals(
            SteamResolutionItemResult.CompleteNoPlausibleSteamMatch(
                EpicPresentationOutcome.EPIC_CMS_UNAVAILABLE,
            ),
            event.result,
        )
    }

    @Test
    fun `Epic fallback never runs for partial review non Epic or non game results`() = runTest {
        val matches = listOf(
            Triple(GameSource.EPIC, "Partial Epic", CanonicalAppType.GAME),
            Triple(GameSource.EPIC, "Review Epic", CanonicalAppType.GAME),
            Triple(GameSource.GOG, "GOG Unmatched", CanonicalAppType.GAME),
            Triple(GameSource.EPIC, "Epic Utility", CanonicalAppType.APPLICATION),
        )
        matches.forEachIndexed { index, (source, title, appType) ->
            val canonical = canonical((index + 1).toLong(), steamAppId = null)
            db.canonicalGameDao().insert(canonical)
            val stableId = if (source == GameSource.EPIC) {
                EpicStableSourceId.encode("namespace-$index", "catalog-$index")
            } else {
                "gog-$index"
            }
            seedMatch(match(key(source, stableId), canonical.canonicalId, title, appType = appType))
        }
        val search = object : SteamCatalogSearchSource {
            override suspend fun search(query: String, locale: MetadataLocale) =
                searchResult(query, locale).hits

            override suspend fun searchResult(query: String, locale: MetadataLocale) = when (query) {
                "Partial Epic" -> SteamCatalogSearchResult(emptyList(), complete = false)
                "Review Epic" -> SteamCatalogSearchResult(
                    listOf(SteamStoreSearchHit(42, "Review Epic", null)),
                    complete = true,
                )
                else -> SteamCatalogSearchResult(emptyList(), complete = true)
            }
        }
        val epicRequests = mutableListOf<EpicCmsCatalogRequest>()
        val repository = repository(
            search = search,
            records = SteamCatalogRecordSource { steamAppId, _ ->
                record(steamAppId, "Review Epic", developer = null, year = null)
            },
            epicCatalogSource = EpicCmsCatalogSource { request ->
                epicRequests += request
                throw AssertionError("ineligible Epic fallback")
            },
        )

        repository.scanAutomatically()

        assertTrue(epicRequests.isEmpty())
        assertTrue(epicFallbackWriter.calls.isEmpty())
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
    fun `automatic scan ignores malformed persisted identity and keeps valid sibling eligible`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(
            match(
                key(GameSource.GOG, "malformed"),
                canonical.canonicalId,
                title = "Malformed Marker",
                developer = "stronger studio",
                year = 2020,
            ).copy(stableSourceId = "01"),
        )
        seedMatch(
            match(
                key(GameSource.GOG, "valid"),
                canonical.canonicalId,
                title = "Valid Marker",
                developer = "",
                year = null,
            ),
        )
        val queries = mutableListOf<String>()
        val repository = repository(
            search = SteamCatalogSearchSource { query, _ ->
                queries += query
                emptyList()
            },
        )

        val progress = repository.scanAutomatically()

        assertEquals(listOf("Valid Marker"), queries)
        assertEquals(1, progress.total)
        assertEquals(1, progress.completed)
        assertEquals(1, progress.unmatched)
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
    fun `automatic scan validates at most fifteen aggregated hits and accepts one corroborated exact candidate`() = runTest {
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
                (1..20).map { id ->
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

        assertEquals((1..15).toList(), fetched)
        assertEquals(5, repository.candidatesFor(expected(selected).key).size)
        assertEquals(1, progress.autoAccepted)
        val accepted = writer.operations.single() as DecisionOperation.Accepted
        assertEquals(1, accepted.steamAppId)
        assertEquals(CanonicalAppType.GAME, accepted.appType)
        assertEquals(listOf(EnrichmentCall(1, MetadataLocale("en-US", "US"))), enrichment.calls)
    }

    @Test
    fun `automatic scan fans out safe aliases and aggregates candidates before validation`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(
            match(
                key(GameSource.EPIC, "safe-alias"),
                canonical.canonicalId,
                title = "Playdead's INSIDE",
                developer = "playdead",
                year = 2016,
            ),
        )
        val queries = mutableListOf<String>()
        val fetched = mutableListOf<Int>()
        val repository = repository(
            search = SteamCatalogSearchSource { query, _ ->
                queries += query
                when (query) {
                    "Playdead's INSIDE" -> (1..10).map { SteamStoreSearchHit(it, "Other $it", null) }
                    "INSIDE" -> listOf(
                        SteamStoreSearchHit(304430, "INSIDE", null),
                        SteamStoreSearchHit(1, "Duplicate", null),
                        *(11..20).map { SteamStoreSearchHit(it, "Other $it", null) }.toTypedArray(),
                    )
                    else -> listOf(SteamStoreSearchHit(304430, "INSIDE", null))
                }
            },
            records = SteamCatalogRecordSource { steamAppId, _ ->
                fetched += steamAppId
                record(
                    steamAppId,
                    if (steamAppId == 304430) "INSIDE" else "Other $steamAppId",
                    if (steamAppId == 304430) "Playdead" else "Other",
                    2016,
                )
            },
        )

        val progress = repository.scanAutomatically()

        assertEquals(
            listOf("Playdead's INSIDE", "INSIDE", "playdead s inside"),
            queries,
        )
        assertEquals(15, fetched.size)
        assertEquals(15, fetched.distinct().size)
        assertTrue(304430 in fetched)
        assertEquals(1, progress.autoAccepted)
        assertEquals(
            304430,
            (writer.operations.single() as DecisionOperation.Accepted).steamAppId,
        )
    }

    @Test
    fun `failed alias query keeps aggregated verified candidates as ranked review only`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(
            match(
                key(GameSource.GOG, "partial-alias"),
                canonical.canonicalId,
                title = "Example!",
                developer = "",
                year = null,
            ),
        )
        val search = object : SteamCatalogSearchSource {
            override suspend fun search(query: String, locale: MetadataLocale) =
                searchResult(query, locale).hits

            override suspend fun searchResult(
                query: String,
                locale: MetadataLocale,
            ): SteamCatalogSearchResult {
                if (query == "example") throw SteamCatalogSearchException()
                return SteamCatalogSearchResult(
                    hits = listOf(
                        SteamStoreSearchHit(42, "Unrelated", null),
                        SteamStoreSearchHit(84, "Example", null),
                    ),
                    complete = true,
                )
            }
        }
        val repository = repository(
            search = search,
            records = SteamCatalogRecordSource { steamAppId, _ ->
                record(
                    steamAppId,
                    if (steamAppId == 84) "Example" else "Unrelated",
                    null,
                    null,
                )
            },
        )

        val progress = repository.scanAutomatically()

        assertEquals(1, progress.needsReview)
        assertEquals(0, progress.failed)
        assertEquals(84, (writer.operations.single() as DecisionOperation.Review).steamAppId)
        assertEquals("SEARCH_INCOMPLETE", diagnostics.events.single().errorType)
    }

    @Test
    fun `recorded 30 title corpus resolves without expected AppID candidate selection`() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val corpusRoot = json.parseToJsonElement(
            requireNotNull(javaClass.classLoader?.getResource("steam-resolver/real-30.json"))
                .readText(),
        ) as JsonObject
        val catalogRoot = json.parseToJsonElement(
            requireNotNull(
                javaClass.classLoader?.getResource("steam-resolver/steam-catalog-30.json"),
            ).readText(),
        ) as JsonObject
        val cases = (corpusRoot.getValue("cases") as JsonArray).map { it as JsonObject }
        val catalog = (catalogRoot.getValue("apps") as JsonArray).map { element ->
            val app = element as JsonObject
            SteamCatalogCandidate(
                steamAppId = (app.getValue("steamAppId") as JsonPrimitive).content.toInt(),
                title = (app.getValue("title") as JsonPrimitive).content,
                developer = (app["developer"] as? JsonPrimitive)?.content,
                publisher = (app["publisher"] as? JsonPrimitive)?.content,
                releaseYear = (app["releaseYear"] as? JsonPrimitive)?.content?.toIntOrNull(),
                appType = CanonicalAppType.valueOf(
                    (app.getValue("appType") as JsonPrimitive).content,
                ),
                headerImageUrl = null,
            )
        }
        cases.forEachIndexed { index, case ->
            val input = case.getValue("input") as JsonObject
            val canonical = canonical(index + 1L, steamAppId = null)
            db.canonicalGameDao().insert(canonical)
            seedMatch(
                match(
                    key(
                        GameSource.valueOf((input.getValue("source") as JsonPrimitive).content),
                        (input.getValue("stableSourceId") as JsonPrimitive).content,
                    ),
                    canonical.canonicalId,
                    title = (input.getValue("displayName") as JsonPrimitive).content,
                    developer = (input["developer"] as? JsonPrimitive)?.content.orEmpty(),
                    year = (input["releaseYear"] as? JsonPrimitive)?.content?.toIntOrNull(),
                    appType = CanonicalAppType.valueOf(
                        (input.getValue("appType") as JsonPrimitive).content,
                    ),
                ),
            )
        }
        val recordsById = catalog.associateBy(SteamCatalogCandidate::steamAppId)
        val repository = repository(
            search = SteamCatalogSearchSource { query, _ ->
                val queryKey = SteamCatalogNormalization.titleKey(query)
                catalog.filter { candidate ->
                    SteamCatalogNormalization.titleKey(candidate.title) == queryKey
                }.map { candidate ->
                    SteamStoreSearchHit(candidate.steamAppId, candidate.title, null)
                }
            },
            records = SteamCatalogRecordSource { steamAppId, _ ->
                recordsById[steamAppId]?.let { candidate ->
                    record(
                        steamAppId = candidate.steamAppId,
                        title = candidate.title,
                        developer = candidate.developer,
                        year = candidate.releaseYear,
                        appType = candidate.appType,
                    )
                }
            },
        )

        val progress = repository.scanAutomatically()
        val actualByStableSourceId = writer.operations
            .filterIsInstance<DecisionOperation.Accepted>()
            .associate { operation -> operation.key.stableSourceId to operation.steamAppId }

        assertEquals(30, progress.autoAccepted)
        cases.forEach { case ->
            val input = case.getValue("input") as JsonObject
            val stableSourceId = (input.getValue("stableSourceId") as JsonPrimitive).content
            val expectedSteamAppId =
                (case.getValue("expectedSteamAppId") as JsonPrimitive).content.toInt()
            assertEquals(
                (case.getValue("caseId") as JsonPrimitive).content,
                expectedSteamAppId,
                actualByStableSourceId[stableSourceId],
            )
        }
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
    fun `partial search with a verified candidate records review only`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(match(key(GameSource.GOG, "partial-search"), canonical.canonicalId, "Exact Marker"))
        val search = object : SteamCatalogSearchSource {
            override suspend fun search(query: String, locale: MetadataLocale) = emptyList<SteamStoreSearchHit>()

            override suspend fun searchResult(
                query: String,
                locale: MetadataLocale,
            ) = SteamCatalogSearchResult(
                hits = listOf(SteamStoreSearchHit(42, "Exact Marker", null)),
                complete = false,
            )
        }
        val repository = repository(
            search = search,
            records = SteamCatalogRecordSource { steamAppId, _ ->
                record(steamAppId, "Exact Marker", "Studio", 2020)
            },
        )

        val progress = repository.scanAutomatically()

        assertEquals(1, progress.needsReview)
        assertEquals(0, progress.unmatched)
        assertEquals(0, progress.failed)
        assertTrue(writer.operations.single() is DecisionOperation.Review)
        assertEquals("SEARCH_INCOMPLETE", diagnostics.events.single().errorType)
    }

    @Test
    fun `partial search without a verified candidate is unavailable and never unmatched`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(match(key(GameSource.GOG, "empty-partial"), canonical.canonicalId, "Exact Marker"))
        val search = object : SteamCatalogSearchSource {
            override suspend fun search(query: String, locale: MetadataLocale) = emptyList<SteamStoreSearchHit>()

            override suspend fun searchResult(
                query: String,
                locale: MetadataLocale,
            ) = SteamCatalogSearchResult(hits = emptyList(), complete = false)
        }
        val repository = repository(search = search)

        val progress = repository.scanAutomatically()

        assertEquals(1, progress.failed)
        assertEquals(0, progress.unmatched)
        assertTrue(writer.operations.isEmpty())
        assertEquals("SEARCH_INCOMPLETE", diagnostics.events.single().errorType)
    }

    @Test
    fun `AppDetails rate exhaustion aborts candidate traversal without partial result`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(match(key(GameSource.GOG, "details-limit"), canonical.canonicalId, "Exact Marker"))
        val fetched = mutableListOf<Int>()
        val repository = repository(
            search = SteamCatalogSearchSource { _, _ ->
                listOf(
                    SteamStoreSearchHit(1, "Exact Marker", null),
                    SteamStoreSearchHit(2, "Exact Marker", null),
                )
            },
            records = SteamCatalogRecordSource { steamAppId, _ ->
                fetched += steamAppId
                throw SteamRateLimitExhaustedException()
            },
        )

        val progress = repository.scanAutomatically()

        assertEquals(listOf(1), fetched)
        assertEquals(1, progress.failed)
        assertEquals(0, progress.needsReview)
        assertEquals(0, progress.unmatched)
        assertTrue(writer.operations.isEmpty())
        assertEquals("RATE_LIMIT_EXHAUSTED", diagnostics.events.single().errorType)
    }

    @Test
    fun `search rate exhaustion returns no catalog result`() = runTest {
        val canonical = canonical(1, steamAppId = null)
        db.canonicalGameDao().insert(canonical)
        seedMatch(match(key(GameSource.GOG, "search-limit"), canonical.canonicalId, "Exact Marker"))
        val repository = repository(
            search = SteamCatalogSearchSource { _, _ -> throw SteamRateLimitExhaustedException() },
        )

        val progress = repository.scanAutomatically()

        assertEquals(1, progress.failed)
        assertEquals(0, progress.unmatched)
        assertTrue(writer.operations.isEmpty())
        assertEquals("RATE_LIMIT_EXHAUSTED", diagnostics.events.single().errorType)
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
                SteamResolutionItemResult.CompleteNoPlausibleSteamMatch(),
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
                "STORE_SEARCH_UNAVAILABLE",
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
        pcGamingWikiSource: PcGamingWikiCurrentAvailabilitySource =
            PcGamingWikiCurrentAvailabilitySource {
                PcGamingWikiCurrentAvailabilityResult.NotConfirmed
            },
        epicCatalogSource: EpicCmsCatalogSource = EpicCmsCatalogSource {
            throw AssertionError("Epic CMS fallback must not run")
        },
    ) = SteamCatalogResolutionRepository(
        storeMatchDao = db.storeMatchDao(),
        searchSource = search,
        recordSource = records,
        candidatePolicy = SteamCatalogCandidatePolicy(),
        decisionWriter = writer,
        pcGamingWikiSource = pcGamingWikiSource,
        epicCatalogSource = epicCatalogSource,
        epicFallbackWriter = epicFallbackWriter,
        localeProvider = MetadataLocaleProvider { MetadataLocale("en-US", "US") },
        diagnostics = diagnostics,
        acceptedIdentityEnrichment = enrichment,
        clock = MetadataClock { 1_000L },
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

    private fun key(source: GameSource, stableSourceId: String): OwnedCopyKey {
        val canonicalStableSourceId = when (source) {
            GameSource.GOG -> stableSourceId.takeIf { value ->
                value.toLongOrNull()?.let { it > 0L && it.toString() == value } == true
            } ?: (stableSourceId.hashCode().toUInt().toLong() + 1L).toString()
            GameSource.AMAZON -> stableSourceId.takeIf { it.startsWith("amzn1.adg.product.") }
                ?: "amzn1.adg.product.${UUID.nameUUIDFromBytes(stableSourceId.toByteArray())}"
            else -> stableSourceId
        }
        return OwnedCopyKey(
            accountScope = scope,
            source = source,
            stableSourceId = canonicalStableSourceId,
        )
    }

    private fun epicRecord(
        stableSourceId: String,
        namespace: String,
        catalogId: String,
        title: String,
    ) = EpicCmsCatalogRecord(
        stableSourceId = stableSourceId,
        namespace = namespace,
        catalogId = catalogId,
        slug = "alan-wake-2",
        offerId = "a7364ebfa54147f1b90f78a81c8093f7",
        storeUrl = "https://store.epicgames.com/en-US/p/alan-wake-2",
        metadata = record(1, title, "Remedy Entertainment", 2023).metadata,
    )

    private fun record(
        steamAppId: Int,
        title: String,
        developer: String?,
        year: Int?,
        appType: CanonicalAppType = CanonicalAppType.GAME,
    ) = SteamCatalogRecord(
        steamAppId = steamAppId,
        appType = appType,
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

    private class FakeEpicFallbackWriter : EpicCatalogFallbackWriter {
        val calls = mutableListOf<EpicFallbackCall>()
        var onRecord: () -> Unit = {}

        override suspend fun recordEpicFallback(
            expected: ExpectedMatchState,
            resolverVersion: Int,
            nowEpochMs: Long,
            locale: MetadataLocale,
            record: EpicCmsCatalogRecord,
            decisionEvidence: PcGamingWikiCurrentAvailabilityEvidence?,
        ): CanonicalGuardedMutationResult {
            onRecord()
            calls += EpicFallbackCall(expected.key, locale, record, decisionEvidence)
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

    private data class EpicFallbackCall(
        val key: OwnedCopyKey,
        val locale: MetadataLocale,
        val record: EpicCmsCatalogRecord,
        val decisionEvidence: PcGamingWikiCurrentAvailabilityEvidence?,
    )

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
