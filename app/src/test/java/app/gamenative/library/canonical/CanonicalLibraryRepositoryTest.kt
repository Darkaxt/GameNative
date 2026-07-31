package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.CanonicalGamePreferenceEntity
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
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeAdapter
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import io.mockk.every
import io.mockk.mockk
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CanonicalLibraryRepositoryTest {
    @Test
    fun verifiedSteamAndHighGogShareOneGroupedCard() = runTest {
        val game = game(ID_A, displayName = "Canonical Hero")
        val steam = match(
            game,
            GameSource.STEAM,
            "10",
            MatchConfidence.VERIFIED,
            evidenceName = "Steam Evidence",
        ).copy(
            candidateSteamAppId = 10,
            resolverVersion = 2,
            matchedAt = 111L,
        )
        val gog = match(
            game,
            GameSource.GOG,
            "20",
            MatchConfidence.HIGH,
            evidenceName = "GOG Evidence",
        ).copy(
            candidateSteamAppId = 20,
            resolverVersion = 3,
            matchedAt = 222L,
        )
        val harness = harness(
            listOf(aggregate(game, listOf(gog, steam))),
            mapOf(
                steam.key() to available(steam.key(), nativeTitle = "Steam Native"),
                gog.key() to available(gog.key(), nativeTitle = "GOG Native"),
            ),
        )

        val cards = harness.repository.observeCards().first()

        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(CanonicalCardKey.Grouped(CanonicalGameId.parse(ID_A)), card.key)
        assertEquals("Canonical Hero", card.displayName)
        assertEquals(listOf(GameSource.STEAM, GameSource.GOG), card.copies.map { it.source })
        assertEquals(listOf(10, 20), card.copies.map { it.decisionCandidateSteamAppId })
        assertEquals(listOf(2, 3), card.copies.map { it.decisionResolverVersion })
        assertEquals(listOf(111L, 222L), card.copies.map { it.decisionRevision })
        assertEquals(listOf(GameSource.STEAM, GameSource.GOG), card.ownedSources.toList())
        assertEquals(
            setOf("Canonical Hero", "Steam Evidence", "GOG Evidence", "Steam Native", "GOG Native"),
            card.aliases,
        )
        assertEquals(setOf(10), card.steamCollectionAppIds)
    }

    @Test
    fun reviewRejectedAndUnmatchedRelationshipsRemainIndependentWithOwnTitles() = runTest {
        val game = game(ID_A, displayName = "Canonical Sibling", appType = CanonicalAppType.APPLICATION)
        val grouped = match(game, GameSource.AMAZON, "product", MatchConfidence.VERIFIED)
        val review = match(
            game,
            GameSource.STEAM,
            "11",
            MatchConfidence.REVIEW_REQUIRED,
            evidenceName = "Review Evidence",
            evidenceAppType = CanonicalAppType.DEMO,
        )
        val rejected = match(
            game,
            GameSource.GOG,
            "22",
            MatchConfidence.REJECTED,
            evidenceName = "Rejected Evidence",
        )
        val unmatched = match(
            game,
            GameSource.EPIC,
            EpicStableSourceId.encode("namespace", "catalog"),
            MatchConfidence.UNMATCHED,
            evidenceName = "Unmatched Evidence",
        )
        val harness = harness(
            listOf(aggregate(game, listOf(unmatched, grouped, rejected, review))),
            listOf(grouped, review, rejected, unmatched).associate { relationship ->
                relationship.key() to available(
                    relationship.key(),
                    nativeTitle = when (relationship) {
                        review -> "Independent Runtime Title"
                        rejected -> "Rejected Runtime Title"
                        unmatched -> "Unmatched Runtime Title"
                        else -> "Grouped Runtime Title"
                    },
                )
            },
        )

        val cards = harness.repository.observeCards().first()

        assertEquals(4, cards.size)
        assertTrue(cards.any { it.key == CanonicalCardKey.Grouped(CanonicalGameId.parse(ID_A)) })
        val reviewCard = cards.single { it.key == CanonicalCardKey.Independent(review.key()) }
        assertEquals("Independent Runtime Title", reviewCard.displayName)
        assertEquals(CanonicalAppType.DEMO, reviewCard.appType)
        assertFalse(reviewCard.displayName.contains("Canonical Sibling"))
        assertFalse("Canonical Sibling" in reviewCard.aliases)
        assertEquals(
            setOf(
                CanonicalCardKey.Independent(review.key()),
                CanonicalCardKey.Independent(rejected.key()),
                CanonicalCardKey.Independent(unmatched.key()),
            ),
            cards.map { it.key }.filterIsInstance<CanonicalCardKey.Independent>().toSet(),
        )
    }

    @Test
    fun independentAliasesExcludeCanonicalTitleAndHiddenTrustedSiblingEvidence() = runTest {
        val game = game(ID_A, displayName = "Hidden Trusted Canonical")
        val hiddenTrusted = match(
            game,
            GameSource.STEAM,
            "12",
            MatchConfidence.VERIFIED,
            evidenceName = "Hidden Trusted Evidence",
        )
        val independent = match(
            game,
            GameSource.GOG,
            "13",
            MatchConfidence.REVIEW_REQUIRED,
            evidenceName = "Independent Evidence",
        )
        val harness = harness(
            listOf(aggregate(game, listOf(hiddenTrusted, independent))),
            mapOf(
                hiddenTrusted.key() to OwnedCopyRuntimeResult.Hidden,
                independent.key() to available(
                    independent.key(),
                    nativeTitle = "Independent Native",
                    aliases = setOf("Independent Runtime Alias"),
                ),
            ),
        )

        val card = harness.repository.observeCards().first().single()

        assertEquals(CanonicalCardKey.Independent(independent.key()), card.key)
        assertEquals(
            setOf("Independent Native", "Independent Evidence", "Independent Runtime Alias"),
            card.aliases,
        )
        assertFalse("Hidden Trusted Canonical" in card.aliases)
        assertFalse("Hidden Trusted Evidence" in card.aliases)
    }

    @Test
    fun hiddenCopiesAndHiddenSiblingsContributeNoCardOrPresentationEvidence() = runTest {
        val visibleGame = game(ID_A, displayName = "Visible Canonical")
        val visible = match(
            visibleGame,
            GameSource.STEAM,
            "30",
            MatchConfidence.VERIFIED,
            evidenceName = "Visible Evidence",
        )
        val hiddenSibling = match(
            visibleGame,
            GameSource.GOG,
            "31",
            MatchConfidence.HIGH,
            evidenceName = "Hidden Sibling Evidence",
        )
        val hiddenOnlyGame = game(ID_B, displayName = "Hidden Only Canonical")
        val hiddenOnly = match(
            hiddenOnlyGame,
            GameSource.EPIC,
            EpicStableSourceId.encode("hidden", "only"),
            MatchConfidence.HIGH,
            evidenceName = "Hidden Only Evidence",
        )
        val harness = harness(
            listOf(
                aggregate(visibleGame, listOf(hiddenSibling, visible)),
                aggregate(hiddenOnlyGame, listOf(hiddenOnly)),
            ),
            mapOf(
                visible.key() to available(visible.key(), nativeTitle = "Visible Native"),
                hiddenSibling.key() to OwnedCopyRuntimeResult.Hidden,
                hiddenOnly.key() to OwnedCopyRuntimeResult.Hidden,
            ),
        )

        val cards = harness.repository.observeCards().first()

        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(listOf(GameSource.STEAM), card.copies.map { it.source })
        assertEquals(setOf(GameSource.STEAM), card.ownedSources)
        assertFalse("Hidden Sibling Evidence" in card.aliases)
        assertFalse("Hidden Only Evidence" in card.aliases)
        assertFalse(cards.any { it.canonicalId == CanonicalGameId.parse(ID_B) })
    }

    @Test
    fun unavailableSoleCurrentAccountCopyUsesOnlyDisabledPersistedEvidence() = runTest {
        val game = game(ID_A)
        val relationship = match(
            game,
            GameSource.AMAZON,
            "product-current",
            MatchConfidence.HIGH,
            evidenceName = "Persisted Evidence Title",
        )
        val harness = harness(
            listOf(aggregate(game, listOf(relationship))),
            mapOf(
                relationship.key() to OwnedCopyRuntimeResult.Unavailable(
                    relationship.key(),
                    CopyUnavailableReason.SOURCE_READ_FAILED,
                    IllegalStateException::class,
                ),
            ),
        )

        val card = harness.repository.observeCards().first().single()
        val copy = card.copies.single()

        assertEquals("Persisted Evidence Title", copy.nativeTitle)
        assertNull(copy.installPath)
        assertNull(copy.installedSizeBytes)
        assertNull(copy.branchOrVersion)
        assertFalse(copy.isInstalled)
        assertFalse(copy.isDownloading)
        assertFalse(copy.hasPartialDownload)
        assertFalse(copy.updateAvailable)
        assertFalse(copy.isShared)
        assertNull(copy.lastPlayedEpochMs)
        assertNull(copy.playtimeMinutes)
        assertEquals(emptySet<OwnedCopyOperation>(), copy.capabilities)
        assertEquals(CopyUnavailableReason.SOURCE_READ_FAILED, copy.unavailableReason)
        assertFalse(copy.canSeparateMatch)
        assertEquals(setOf(GameSource.AMAZON), card.ownedSources)
    }

    @Test
    fun unsupportedNonblankGogBridgeRemainsVisibleWithArtworkAndNoCapability() = runTest {
        val game = game(ID_A, primarySource = GameSource.GOG)
        val relationship = match(
            game,
            GameSource.GOG,
            "+123",
            MatchConfidence.HIGH,
            evidenceName = "GOG Evidence",
        )
        val result = available(
            relationship.key(),
            nativeTitle = "Current GOG Title",
            libraryItemPresent = false,
            capabilities = setOf(OwnedCopyOperation.PLAY),
            iconUrl = "gog-icon",
            capsuleImageUrl = "gog-capsule",
            headerImageUrl = "gog-header",
            heroImageUrl = "gog-hero",
            gridHeroImageScale = 1.25f,
        )
        val harness = harness(
            listOf(aggregate(game, listOf(relationship))),
            mapOf(relationship.key() to result),
        )

        val card = harness.repository.observeCards().first().single()
        val copy = card.copies.single()

        assertEquals("Current GOG Title", copy.nativeTitle)
        assertEquals(CopyUnavailableReason.LEGACY_BRIDGE_UNSUPPORTED, copy.unavailableReason)
        assertEquals(emptySet<OwnedCopyOperation>(), copy.capabilities)
        assertTrue(copy.canSeparateMatch)
        assertEquals("gog-icon", card.iconUrl)
        assertEquals("gog-capsule", card.capsuleImageUrl)
        assertEquals("gog-header", card.headerImageUrl)
        assertEquals("gog-hero", card.heroImageUrl)
        assertEquals(1.25f, card.gridHeroImageScale)
    }

    @Test
    fun preferredCopyRequiresOneCompleteParseableTripleAndIsNeverWritten() = runTest {
        val validGame = game(ID_A)
        val partialGame = game(ID_B)
        val malformedScopeGame = game(ID_C)
        val blankIdGame = game(ID_D)
        val games = listOf(validGame, partialGame, malformedScopeGame, blankIdGame)
        val relationships = games.mapIndexed { index, entity ->
            match(entity, GameSource.STEAM, "${100 + index}", MatchConfidence.HIGH)
        }
        val validPreference = preference(validGame, relationships[0].key())
        val partialPreference = preference(partialGame, relationships[1].key()).copy(
            preferredSource = null,
        )
        val malformedScopePreference = preference(malformedScopeGame, relationships[2].key()).copy(
            preferredAccountScope = "not-a-scope",
        )
        val blankIdPreference = preference(blankIdGame, relationships[3].key()).copy(
            preferredStableSourceId = " ",
        )
        val preferences = listOf(
            validPreference,
            partialPreference,
            malformedScopePreference,
            blankIdPreference,
        )
        val harness = harness(
            games.indices.map { index ->
                aggregate(games[index], listOf(relationships[index]), listOf(preferences[index]))
            },
            relationships.associate { it.key() to available(it.key()) },
        )

        val cards = harness.repository.observeCards().first().associateBy { it.canonicalId.value }

        assertEquals(relationships[0].key(), cards.getValue(ID_A).preferredCopy)
        assertNull(cards.getValue(ID_B).preferredCopy)
        assertNull(cards.getValue(ID_C).preferredCopy)
        assertNull(cards.getValue(ID_D).preferredCopy)
        assertEquals(preferences, listOf(validPreference, partialPreference, malformedScopePreference, blankIdPreference))
    }

    @Test
    fun artworkUsesSteamThenPrimaryThenFixedSourceThenEmptyWithoutReadingOverrideJson() = runTest {
        val steamFirstGame = game(ID_A, primarySource = GameSource.GOG)
        val steamFirstGog = match(steamFirstGame, GameSource.GOG, "201", MatchConfidence.HIGH)
        val steamFirstSteam = match(steamFirstGame, GameSource.STEAM, "202", MatchConfidence.HIGH)

        val primaryFirstGame = game(ID_B, primarySource = GameSource.EPIC)
        val primaryGog = match(primaryFirstGame, GameSource.GOG, "203", MatchConfidence.HIGH)
        val primaryEpic = match(
            primaryFirstGame,
            GameSource.EPIC,
            EpicStableSourceId.encode("primary", "epic"),
            MatchConfidence.HIGH,
        )

        val fixedOrderGame = game(ID_C, primarySource = GameSource.STEAM)
        val fixedAmazon = match(fixedOrderGame, GameSource.AMAZON, "product-204", MatchConfidence.HIGH)
        val fixedGog = match(fixedOrderGame, GameSource.GOG, "205", MatchConfidence.HIGH)

        val emptyGame = game(ID_D, primarySource = GameSource.CUSTOM_GAME)
        val emptyCopy = match(emptyGame, GameSource.CUSTOM_GAME, "206", MatchConfidence.HIGH)

        fun art(relationship: StoreMatchEntity, label: String, scale: Float) =
            relationship.key() to available(
                relationship.key(),
                iconUrl = "$label-icon",
                capsuleImageUrl = "$label-capsule",
                headerImageUrl = "$label-header",
                heroImageUrl = "$label-hero",
                gridHeroImageScale = scale,
            )

        val results = mapOf(
            art(steamFirstGog, "gog-a", 1.1f),
            art(steamFirstSteam, "steam-a", 1.2f),
            art(primaryGog, "gog-b", 1.3f),
            art(primaryEpic, "epic-b", 1.4f),
            art(fixedAmazon, "amazon-c", 1.5f),
            art(fixedGog, "gog-c", 1.6f),
            emptyCopy.key() to OwnedCopyRuntimeResult.Unavailable(
                emptyCopy.key(),
                CopyUnavailableReason.SOURCE_ROW_CHANGED,
            ),
        )
        val override = preference(steamFirstGame, steamFirstSteam.key()).copy(
            artworkOverrideJson = "{\"unversioned\":\"must-not-be-read\"}",
        )
        val harness = harness(
            listOf(
                aggregate(steamFirstGame, listOf(steamFirstGog, steamFirstSteam), listOf(override)),
                aggregate(primaryFirstGame, listOf(primaryGog, primaryEpic)),
                aggregate(fixedOrderGame, listOf(fixedAmazon, fixedGog)),
                aggregate(emptyGame, listOf(emptyCopy)),
            ),
            results,
        )

        val cards = harness.repository.observeCards().first().associateBy { it.canonicalId.value }

        assertArtwork(cards.getValue(ID_A), "steam-a", 1.2f)
        assertArtwork(cards.getValue(ID_B), "epic-b", 1.4f)
        assertArtwork(cards.getValue(ID_C), "gog-c", 1.6f)
        assertArtwork(cards.getValue(ID_D), "", 1f)
        assertEquals("{\"unversioned\":\"must-not-be-read\"}", override.artworkOverrideJson)
    }

    @Test
    fun identicalInputsEmitOnceAndCardsOwnImmutableCopiesOfEveryCollection() = runTest {
        val game = game(ID_A)
        val relationship = match(game, GameSource.STEAM, "300", MatchConfidence.HIGH)
        val runtimeAliases = linkedSetOf("Runtime Alias")
        val runtimeCapabilities = linkedSetOf(OwnedCopyOperation.PLAY)
        val results = mutableMapOf<OwnedCopyKey, OwnedCopyRuntimeResult>(
            relationship.key() to available(
                relationship.key(),
                aliases = runtimeAliases,
                capabilities = runtimeCapabilities,
            ),
        )
        val harness = harness(listOf(aggregate(game, listOf(relationship))), results)
        val emissions = mutableListOf<List<CanonicalLibraryCard>>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            harness.repository.observeCards().collect(emissions::add)
        }
        runCurrent()
        assertEquals(1, emissions.size)
        val first = emissions.single()
        val equalSnapshot = first.map { card ->
            card.copy(
                aliases = card.aliases.toSet(),
                ownedSources = card.ownedSources.toSet(),
                copies = card.copies.map { copy -> copy.copy(capabilities = copy.capabilities.toSet()) },
                steamCollectionAppIds = card.steamCollectionAppIds.toSet(),
            )
        }

        harness.adapters.getValue(GameSource.STEAM).invalidations.emit(Unit)
        runCurrent()
        assertEquals(1, emissions.size)

        runtimeAliases += "Late Runtime Alias"
        runtimeCapabilities += OwnedCopyOperation.UNINSTALL

        assertEquals(1, emissions.size)
        assertEquals(equalSnapshot, first)
        val card = first.single()
        assertFalse("Late Runtime Alias" in card.aliases)
        assertFalse(OwnedCopyOperation.UNINSTALL in card.copies.single().capabilities)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (card.copies as MutableList<OwnedCopySummary>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (card.aliases as MutableSet<String>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (card.copies.single().capabilities as MutableSet<OwnedCopyOperation>).clear()
        }
        collector.cancel()
    }

    @Test
    fun relationshipsCopiesSourcesAndCardsHaveStableExplicitOrder() = runTest {
        val laterGame = game(ID_B)
        val earlierGame = game(ID_A)
        val sourceMatches = SOURCE_ORDER.reversed().mapIndexed { index, source ->
            match(
                earlierGame,
                source,
                stableId(source, 400 + index),
                MatchConfidence.HIGH,
            )
        }
        val laterMatch = match(laterGame, GameSource.GOG, "499", MatchConfidence.HIGH)
        val allMatches = sourceMatches + laterMatch
        val harness = harness(
            listOf(
                aggregate(laterGame, listOf(laterMatch)),
                aggregate(earlierGame, sourceMatches),
            ),
            allMatches.associate { it.key() to available(it.key()) },
        )

        val first = harness.repository.observeCards().first()
        harness.adapters.getValue(GameSource.GOG).invalidations.emit(Unit)
        val second = harness.repository.observeCards().first()

        assertEquals(listOf(ID_A, ID_B), first.map { it.canonicalId.value })
        assertEquals(SOURCE_ORDER, first.first().copies.map { it.source })
        assertEquals(SOURCE_ORDER, first.first().ownedSources.toList())
        assertEquals(first, second)
    }

    @Test
    fun sameTitlesNeverGroupAcrossCanonicalIds() = runTest {
        val firstGame = game(ID_A, displayName = "Same Title")
        val secondGame = game(ID_B, displayName = "Same Title")
        val firstMatch = match(firstGame, GameSource.STEAM, "501", MatchConfidence.HIGH)
        val secondMatch = match(secondGame, GameSource.GOG, "502", MatchConfidence.HIGH)
        val harness = harness(
            listOf(
                aggregate(secondGame, listOf(secondMatch)),
                aggregate(firstGame, listOf(firstMatch)),
            ),
            mapOf(
                firstMatch.key() to available(firstMatch.key(), nativeTitle = "Same Title"),
                secondMatch.key() to available(secondMatch.key(), nativeTitle = "Same Title"),
            ),
        )

        val cards = harness.repository.observeCards().first()

        assertEquals(2, cards.size)
        assertEquals(setOf(ID_A, ID_B), cards.map { it.canonicalId.value }.toSet())
    }

    @Test
    fun malformedScopesAndBlankSourceIdsAreOmittedBeforeRuntimeResolution() = runTest {
        val game = game(ID_A)
        val valid = match(game, GameSource.STEAM, "601", MatchConfidence.HIGH)
        val malformedScope = match(game, GameSource.GOG, "602", MatchConfidence.HIGH).copy(
            accountScope = "account-id-must-not-be-exposed",
            evidenceDisplayName = "Malformed Scope Evidence",
        )
        val blankSourceId = match(game, GameSource.EPIC, " ", MatchConfidence.HIGH).copy(
            evidenceDisplayName = "Blank Source Evidence",
        )
        val harness = harness(
            listOf(aggregate(game, listOf(blankSourceId, malformedScope, valid))),
            mapOf(valid.key() to available(valid.key())),
        )

        val card = harness.repository.observeCards().first().single()

        assertEquals(listOf(valid.key()), card.copies.map { it.key })
        assertFalse("Malformed Scope Evidence" in card.aliases)
        assertFalse("Blank Source Evidence" in card.aliases)
        assertEquals(1, harness.adapters.getValue(GameSource.STEAM).batches.single().size)
        assertTrue(harness.adapters.getValue(GameSource.GOG).batches.isEmpty())
        assertTrue(harness.adapters.getValue(GameSource.EPIC).batches.isEmpty())
    }

    @Test
    fun duplicatePresentAndAbsentRelationshipsResolveAndEmitOnePresentCopy() = runTest {
        val game = game(ID_A)
        val present = match(game, GameSource.STEAM, "701", MatchConfidence.HIGH)
        val duplicate = present.copy()
        val absent = match(game, GameSource.GOG, "702", MatchConfidence.HIGH).copy(isPresent = false)
        val harness = harness(
            listOf(aggregate(game, listOf(absent, duplicate, present))),
            mapOf(present.key() to available(present.key())),
        )

        val card = harness.repository.observeCards().first().single()

        assertEquals(listOf(present.key()), card.copies.map { it.key })
        assertEquals(listOf(setOf(present.key())), harness.adapters.getValue(GameSource.STEAM).batches)
        assertTrue(harness.adapters.getValue(GameSource.GOG).batches.isEmpty())
    }

    @Test
    fun registryBatchKeyMismatchFailsWholeAssembly() = runTest {
        val game = game(ID_A)
        val relationship = match(game, GameSource.STEAM, "801", MatchConfidence.HIGH)
        val adapters = completeAdapters().toMutableMap()
        adapters[GameSource.STEAM] = RecordingRuntimeAdapter(GameSource.STEAM) { emptyMap() }
        val repository = repository(flowOf(listOf(aggregate(game, listOf(relationship)))), adapters.values)

        assertSuspendThrows(IllegalStateException::class.java) {
            repository.observeCards().first()
        }
    }

    @Test
    fun sourceBatchExceptionFailsAssemblyAndCancelsConcurrentSiblingBatch() = runTest {
        val game = game(ID_A)
        val steam = match(game, GameSource.STEAM, "901", MatchConfidence.HIGH)
        val gog = match(game, GameSource.GOG, "902", MatchConfidence.HIGH)
        val steamStarted = CompletableDeferred<Unit>()
        val steamCancelled = CompletableDeferred<Unit>()
        val adapters = completeAdapters().toMutableMap()
        adapters[GameSource.STEAM] = RecordingRuntimeAdapter(GameSource.STEAM) {
            steamStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                steamCancelled.complete(Unit)
            }
        }
        adapters[GameSource.GOG] = RecordingRuntimeAdapter(GameSource.GOG) {
            steamStarted.await()
            throw SourceBatchFailure()
        }
        val repository = repository(
            flowOf(listOf(aggregate(game, listOf(steam, gog)))),
            adapters.values,
        )

        assertSuspendThrows(SourceBatchFailure::class.java) {
            withTimeout(1_000L) { repository.observeCards().first() }
        }
        assertTrue(steamCancelled.isCompleted)
    }

    @Test
    fun collectorCancellationCancelsAnInFlightSourceBatchWithoutConversion() = runTest {
        val game = game(ID_A)
        val relationship = match(game, GameSource.AMAZON, "product-cancel", MatchConfidence.HIGH)
        val batchStarted = CompletableDeferred<Unit>()
        val batchCancelled = CompletableDeferred<Unit>()
        val adapters = completeAdapters().toMutableMap()
        adapters[GameSource.AMAZON] = RecordingRuntimeAdapter(GameSource.AMAZON) {
            batchStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                batchCancelled.complete(Unit)
            }
        }
        val repository = repository(
            flowOf(listOf(aggregate(game, listOf(relationship)))),
            adapters.values,
        )
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.observeCards().collect {}
        }
        runCurrent()
        batchStarted.await()

        collector.cancel(CancellationException("collector cancelled"))
        collector.join()

        assertTrue(collector.isCancelled)
        assertTrue(batchCancelled.isCompleted)
    }

    @Test
    fun roomRelationshipCollectionsAreFrozenBeforeRuntimeSuspension() = runTest {
        val game = game(ID_A)
        val original = match(game, GameSource.STEAM, "1001", MatchConfidence.HIGH)
        val late = match(game, GameSource.GOG, "1002", MatchConfidence.HIGH)
        val mutableMatches = mutableListOf(original)
        val aggregateEvents = MutableSharedFlow<List<CanonicalLibraryAggregate>>(replay = 1).apply {
            tryEmit(listOf(aggregate(game, mutableMatches)))
        }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adapters = completeAdapters(
            mapOf(original.key() to available(original.key())),
        ).toMutableMap()
        adapters[GameSource.STEAM] = RecordingRuntimeAdapter(GameSource.STEAM) { keys ->
            started.complete(Unit)
            release.await()
            keys.associateWith { available(it) }
        }
        val repository = repository(aggregateEvents, adapters.values)
        val result = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            repository.observeCards().first()
        }
        runCurrent()
        started.await()

        mutableMatches += late
        release.complete(Unit)
        runCurrent()

        assertEquals(listOf(original.key()), result.await().single().copies.map { it.key })
        assertTrue(adapters.getValue(GameSource.GOG).batches.isEmpty())
    }

    @Test
    fun runtimeInvalidationCancelsStaleAssemblyThroughMapLatest() = runTest {
        val game = game(ID_A)
        val relationship = match(game, GameSource.STEAM, "1101", MatchConfidence.HIGH)
        val calls = AtomicInteger()
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val steamAdapter = RecordingRuntimeAdapter(GameSource.STEAM) { keys ->
            if (calls.getAndIncrement() == 0) {
                firstStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    firstCancelled.complete(Unit)
                }
            }
            keys.associateWith { available(it, nativeTitle = "Fresh Runtime") }
        }
        val adapters = completeAdapters().toMutableMap().apply {
            put(GameSource.STEAM, steamAdapter)
        }
        val repository = repository(
            flowOf(listOf(aggregate(game, listOf(relationship)))),
            adapters.values,
        )
        val result = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            repository.observeCards().first()
        }
        runCurrent()
        firstStarted.await()

        steamAdapter.invalidations.emit(Unit)
        runCurrent()

        assertEquals("Fresh Runtime", result.await().single().copies.single().nativeTitle)
        assertTrue(firstCancelled.isCompleted)
        assertEquals(2, calls.get())
    }

    @Test
    fun fifteenHundredCopiesResolveInFiveConcurrentSourceBatchesWithoutPerGameReads() = runTest {
        val game = game(ID_A)
        val relationships = SOURCE_ORDER.flatMapIndexed { sourceIndex, source ->
            (1..300).map { copyIndex ->
                match(
                    game,
                    source,
                    stableId(source, sourceIndex * 300 + copyIndex),
                    MatchConfidence.HIGH,
                    evidenceName = "Evidence $sourceIndex $copyIndex",
                )
            }
        }.shuffled(java.util.Random(7L))
        val allStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val startedSources = Collections.synchronizedSet(linkedSetOf<GameSource>())
        val adapters = SOURCE_ORDER.associateWith { source ->
            RecordingRuntimeAdapter(source) { keys ->
                startedSources += source
                if (startedSources.size == SOURCE_ORDER.size) allStarted.complete(Unit)
                release.await()
                keys.associateWith { key ->
                    OwnedCopyRuntimeResult.Unavailable(
                        key,
                        CopyUnavailableReason.SOURCE_READ_FAILED,
                    )
                }
            }
        }
        val dao = RecordingDao(flowOf(listOf(aggregate(game, relationships))))
        val repository = repository(dao, adapters.values)
        val result = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            repository.observeCards().first()
        }
        runCurrent()
        val allWereStartedBeforeAnyCompleted = allStarted.isCompleted

        release.complete(Unit)
        runCurrent()
        val card = result.await().single()

        assertTrue(allWereStartedBeforeAnyCompleted)
        assertEquals(SOURCE_ORDER.toSet(), startedSources.toSet())
        assertEquals(1, dao.observeCalls.get())
        assertEquals(1_500, card.copies.size)
        SOURCE_ORDER.forEach { source ->
            val batches = adapters.getValue(source).batches
            assertEquals(1, batches.size)
            assertEquals(300, batches.single().size)
        }
    }

    private fun harness(
        aggregates: List<CanonicalLibraryAggregate>,
        results: Map<OwnedCopyKey, OwnedCopyRuntimeResult>,
    ): Harness {
        val adapters = completeAdapters(results)
        return Harness(
            repository = repository(flowOf(aggregates), adapters.values),
            adapters = adapters,
        )
    }

    private fun repository(
        aggregateFlow: Flow<List<CanonicalLibraryAggregate>>,
        adapters: Collection<OwnedCopyRuntimeAdapter>,
    ): CanonicalLibraryRepository = repository(RecordingDao(aggregateFlow), adapters)

    private fun repository(
        dao: CanonicalLibraryDao,
        adapters: Collection<OwnedCopyRuntimeAdapter>,
    ): CanonicalLibraryRepository {
        val history = mockk<LibraryPlayHistoryDao>()
        every { history.getAll() } returns emptyFlow()
        val registry = OwnedCopyRuntimeRegistry(
            adapters.toSet(),
            history,
            mockk(relaxed = true),
        )
        return CanonicalLibraryRepository(dao, registry)
    }

    private fun completeAdapters(
        results: Map<OwnedCopyKey, OwnedCopyRuntimeResult> = emptyMap(),
    ): Map<GameSource, RecordingRuntimeAdapter> = SOURCE_ORDER.associateWith { source ->
        RecordingRuntimeAdapter(source) { keys ->
            keys.associateWith { key -> results[key] ?: OwnedCopyRuntimeResult.Hidden }
        }
    }

    private fun game(
        canonicalId: String,
        displayName: String = "Canonical $canonicalId",
        primarySource: GameSource = GameSource.STEAM,
        appType: CanonicalAppType = CanonicalAppType.GAME,
    ) = CanonicalGameEntity(
        canonicalId = canonicalId,
        steamAppId = null,
        displayName = displayName,
        matchTitleKey = displayName.lowercase(),
        primaryMetadataSource = primarySource,
        appType = appType,
        releaseYear = 2026,
        developerKey = "developer",
        classificationState = ClassificationState.CLASSIFIED,
        steamReviewCount = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun match(
        game: CanonicalGameEntity,
        source: GameSource,
        stableSourceId: String,
        confidence: MatchConfidence,
        evidenceName: String = "Evidence $stableSourceId",
        evidenceAppType: CanonicalAppType = CanonicalAppType.GAME,
    ) = StoreMatchEntity(
        accountScope = SCOPE.value,
        source = source,
        stableSourceId = stableSourceId,
        canonicalId = game.canonicalId,
        candidateSteamAppId = null,
        matchMethod = when (confidence) {
            MatchConfidence.VERIFIED -> MatchMethod.DIRECT_STEAM
            MatchConfidence.HIGH -> MatchMethod.EXACT_METADATA
            MatchConfidence.REVIEW_REQUIRED -> MatchMethod.FUZZY_CANDIDATE
            MatchConfidence.REJECTED -> MatchMethod.MANUAL
            MatchConfidence.UNMATCHED -> MatchMethod.UNMATCHED
        },
        confidence = confidence,
        decisionSource = if (confidence == MatchConfidence.REJECTED) {
            MatchDecisionSource.USER
        } else {
            MatchDecisionSource.AUTOMATIC
        },
        resolverVersion = 1,
        matchedAt = 1L,
        isPresent = true,
        evidenceDisplayName = evidenceName,
        evidenceTitleKey = evidenceName.lowercase(),
        evidenceDeveloperKey = "developer",
        evidenceReleaseYear = 2026,
        evidenceAppType = evidenceAppType,
    )

    private fun preference(
        game: CanonicalGameEntity,
        key: OwnedCopyKey,
    ) = CanonicalGamePreferenceEntity(
        canonicalId = game.canonicalId,
        preferredAccountScope = key.accountScope.value,
        preferredSource = key.source,
        preferredStableSourceId = key.stableSourceId,
        titleOverride = null,
        artworkOverrideJson = null,
        updatedAt = 1L,
    )

    private fun aggregate(
        game: CanonicalGameEntity,
        matches: List<StoreMatchEntity>,
        preferences: List<CanonicalGamePreferenceEntity> = emptyList(),
    ) = CanonicalLibraryAggregate(
        game = game,
        matches = matches,
        preferences = preferences,
    )

    private fun StoreMatchEntity.key(): OwnedCopyKey = OwnedCopyKey(
        AccountScope.parse(accountScope),
        source,
        stableSourceId,
    )

    private fun available(
        key: OwnedCopyKey,
        nativeTitle: String = "Runtime ${key.source} ${key.stableSourceId}",
        aliases: Set<String> = emptySet(),
        libraryItemPresent: Boolean = true,
        capabilities: Set<OwnedCopyOperation> = setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS),
        iconUrl: String = "",
        capsuleImageUrl: String = "",
        headerImageUrl: String = "",
        heroImageUrl: String = "",
        gridHeroImageScale: Float = 1f,
    ): OwnedCopyRuntimeResult.Available = OwnedCopyRuntimeResult.Available(
        OwnedCopyRuntime(
            key = key,
            reference = reference(key),
            libraryItem = if (libraryItemPresent) {
                LibraryItem(
                    appId = "${key.source.name}_${key.stableSourceId}",
                    name = nativeTitle,
                    gameSource = key.source,
                )
            } else {
                null
            },
            nativeTitle = nativeTitle,
            aliases = aliases,
            developerKey = "developer",
            releaseYear = 2026,
            appType = CanonicalAppType.GAME,
            genreKeys = setOf("genre"),
            tagIds = setOf(1),
            featureKeys = setOf("feature"),
            iconUrl = iconUrl,
            capsuleImageUrl = capsuleImageUrl,
            headerImageUrl = headerImageUrl,
            heroImageUrl = heroImageUrl,
            gridHeroImageScale = gridHeroImageScale,
            installPath = "/installed/${key.source.name}",
            installedSizeBytes = 100L,
            branchOrVersion = "current",
            isInstalled = true,
            isDownloading = false,
            hasPartialDownload = false,
            updateAvailable = false,
            isShared = key.source == GameSource.STEAM,
            lastPlayedEpochMs = 200L,
            playtimeMinutes = 20L,
            capabilities = capabilities,
        ),
    )

    private fun reference(key: OwnedCopyKey): SourceOwnedCopyReference = when (key.source) {
        GameSource.STEAM -> SourceOwnedCopyReference.Steam(
            key,
            key.stableSourceId.toIntOrNull()?.takeIf { it > 0 } ?: 1,
        )
        GameSource.GOG -> SourceOwnedCopyReference.Gog(key, key.stableSourceId)
        GameSource.EPIC -> SourceOwnedCopyReference.Epic(key, 1, "namespace", "catalog")
        GameSource.AMAZON -> SourceOwnedCopyReference.Amazon(
            key,
            1,
            key.stableSourceId,
            "entitlement-${key.stableSourceId}",
        )
        GameSource.CUSTOM_GAME -> SourceOwnedCopyReference.Custom(
            key,
            key.stableSourceId.toIntOrNull()?.takeIf { it > 0 } ?: 1,
        )
    }

    private fun stableId(source: GameSource, value: Int): String = when (source) {
        GameSource.STEAM -> value.toString()
        GameSource.GOG -> (10_000 + value).toString()
        GameSource.EPIC -> EpicStableSourceId.encode("namespace-$value", "catalog-$value")
        GameSource.AMAZON -> "product-$value"
        GameSource.CUSTOM_GAME -> value.toString()
    }

    private fun assertArtwork(card: CanonicalLibraryCard, label: String, scale: Float) {
        assertEquals(if (label.isEmpty()) "" else "$label-icon", card.iconUrl)
        assertEquals(if (label.isEmpty()) "" else "$label-capsule", card.capsuleImageUrl)
        assertEquals(if (label.isEmpty()) "" else "$label-header", card.headerImageUrl)
        assertEquals(if (label.isEmpty()) "" else "$label-hero", card.heroImageUrl)
        assertEquals(scale, card.gridHeroImageScale)
    }

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

    private data class Harness(
        val repository: CanonicalLibraryRepository,
        val adapters: Map<GameSource, RecordingRuntimeAdapter>,
    )

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
        private val resolver: suspend (Set<OwnedCopyKey>) -> Map<OwnedCopyKey, OwnedCopyRuntimeResult>,
    ) : OwnedCopyRuntimeAdapter {
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val batches = Collections.synchronizedList(mutableListOf<Set<OwnedCopyKey>>())

        override fun invalidations(): Flow<Unit> = invalidations

        override suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult =
            resolver(setOf(key)).getValue(key)

        override suspend fun resolveAll(
            keys: Set<OwnedCopyKey>,
        ): Map<OwnedCopyKey, OwnedCopyRuntimeResult> {
            batches += keys.toSet()
            return resolver(keys)
        }
    }

    private class SourceBatchFailure : IllegalStateException("source batch failed")

    private companion object {
        val SCOPE = AccountScope.parse("a".repeat(64))
        const val ID_A = "11111111-1111-1111-1111-111111111111"
        const val ID_B = "22222222-2222-2222-2222-222222222222"
        const val ID_C = "33333333-3333-3333-3333-333333333333"
        const val ID_D = "44444444-4444-4444-4444-444444444444"
        val SOURCE_ORDER = listOf(
            GameSource.STEAM,
            GameSource.GOG,
            GameSource.EPIC,
            GameSource.AMAZON,
            GameSource.CUSTOM_GAME,
        )
    }
}
