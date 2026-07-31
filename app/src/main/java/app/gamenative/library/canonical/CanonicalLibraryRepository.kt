package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.dao.CanonicalLibraryAggregate
import app.gamenative.db.dao.CanonicalLibraryDao
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart

@Singleton
class CanonicalLibraryRepository @Inject constructor(
    private val dao: CanonicalLibraryDao,
    private val runtimeRegistry: OwnedCopyRuntimeRegistry,
) {
    fun observeCards(): Flow<List<CanonicalLibraryCard>> = combine(
        dao.observePresentGames().map(::freezeAggregates),
        runtimeRegistry.invalidations().onStart { emit(Unit) },
    ) { aggregates, _ -> aggregates }
        .mapLatest(::assemble)
        .distinctUntilChanged()

    private suspend fun assemble(
        aggregates: List<CanonicalLibraryAggregate>,
    ): List<CanonicalLibraryCard> {
        val relationships = aggregates.asSequence()
            .sortedBy { it.game.canonicalId }
            .flatMap { aggregate ->
                aggregate.matches.asSequence()
                    .filter(StoreMatchEntity::isPresent)
                    .mapNotNull { match ->
                        match.ownedCopyKeyOrNull()?.let { key ->
                            Relationship(aggregate, match, key)
                        }
                    }
                    .sortedWith(RELATIONSHIP_COMPARATOR)
            }
            .distinctBy(Relationship::key)
            .toList()
        if (relationships.isEmpty()) return emptyList()

        val results = resolveAll(relationships)
        val visible = relationships.mapNotNull { relationship ->
            when (val result = results.getValue(relationship.key)) {
                OwnedCopyRuntimeResult.Hidden -> null
                is OwnedCopyRuntimeResult.Available -> VisibleRelationship(
                    relationship = relationship,
                    runtime = result.copy,
                    unavailable = null,
                )
                is OwnedCopyRuntimeResult.Unavailable -> VisibleRelationship(
                    relationship = relationship,
                    runtime = null,
                    unavailable = result,
                )
            }
        }
        if (visible.isEmpty()) return emptyList()

        return immutableList(
            visible.groupByTo(linkedMapOf()) { entry -> entry.relationship.cardKey() }
                .map { (key, entries) -> buildCard(key, entries) }
                .sortedWith(CARD_COMPARATOR),
        )
    }

    private suspend fun resolveAll(
        relationships: List<Relationship>,
    ): Map<OwnedCopyKey, OwnedCopyRuntimeResult> = coroutineScope {
        val keysBySource = relationships.groupBy(
            keySelector = { it.key.source },
            valueTransform = Relationship::key,
        )
        SOURCE_ORDER.mapNotNull { source ->
            val keys = keysBySource[source]
                ?.let(::immutableSet)
                ?.takeIf(Set<OwnedCopyKey>::isNotEmpty)
                ?: return@mapNotNull null
            async {
                source to LinkedHashMap(runtimeRegistry.resolveAll(source, keys))
            }
        }.awaitAll().flatMap { (_, results) -> results.entries }
            .associateTo(linkedMapOf()) { it.key to it.value }
    }

    private fun buildCard(
        key: CanonicalCardKey,
        unsortedEntries: List<VisibleRelationship>,
    ): CanonicalLibraryCard {
        val entries = unsortedEntries.sortedWith(VISIBLE_RELATIONSHIP_COMPARATOR)
        val aggregate = entries.first().relationship.aggregate
        val game = aggregate.game
        val displayName = when (key) {
            is CanonicalCardKey.Grouped -> aggregate.preferenceOrNull()
                ?.titleOverride
                ?.takeIf(String::isNotBlank)
                ?: game.displayName
            is CanonicalCardKey.Independent -> entries.single().runtime
                ?.nativeTitle
                ?.takeIf(String::isNotBlank)
                ?: entries.single().relationship.match.evidenceDisplayName
        }
        val copies = immutableList(entries.map(::summary))
        val emittedKeys = copies.mapTo(hashSetOf(), OwnedCopySummary::key)
        val preferredCopy = aggregate.preferenceOrNull()
            ?.preferredCopyKeyOrNull()
            ?.takeIf(emittedKeys::contains)
        val artwork = artwork(entries, game.primaryMetadataSource)
        val aliases = linkedSetOf<String>().apply {
            addName(displayName)
            if (key is CanonicalCardKey.Grouped) addName(game.displayName)
            entries.forEach { entry ->
                addName(entry.relationship.match.evidenceDisplayName)
                entry.runtime?.let { runtime ->
                    addName(runtime.nativeTitle)
                    runtime.aliases.forEach { alias -> addName(alias) }
                }
            }
        }
        val ownedSources = copies.map(OwnedCopySummary::source).toCollection(linkedSetOf())
        val steamCollectionAppIds = copies.asSequence()
            .filter { it.source == GameSource.STEAM }
            .mapNotNull { it.key.stableSourceId.positiveExactDecimalIntOrNull() }
            .toCollection(linkedSetOf())

        return CanonicalLibraryCard(
            key = key,
            canonicalId = CanonicalGameId.parse(entries.first().relationship.match.canonicalId),
            displayName = displayName,
            appType = when (key) {
                is CanonicalCardKey.Grouped -> game.appType
                is CanonicalCardKey.Independent ->
                    entries.single().relationship.match.evidenceAppType
            },
            iconUrl = artwork.iconUrl,
            capsuleImageUrl = artwork.capsuleImageUrl,
            headerImageUrl = artwork.headerImageUrl,
            heroImageUrl = artwork.heroImageUrl,
            gridHeroImageScale = artwork.gridHeroImageScale,
            aliases = immutableSet(aliases),
            ownedSources = immutableSet(ownedSources),
            copies = copies,
            preferredCopy = preferredCopy,
            steamCollectionAppIds = immutableSet(steamCollectionAppIds),
            isShared = copies.any(OwnedCopySummary::isShared),
        )
    }

    private fun summary(entry: VisibleRelationship): OwnedCopySummary {
        val relationship = entry.relationship
        val match = relationship.match
        val key = relationship.key
        val runtime = entry.runtime
        return if (runtime != null) {
            val unsupportedBridge = runtime.libraryItem == null
            OwnedCopySummary(
                key = key,
                source = key.source,
                nativeTitle = runtime.nativeTitle,
                installPath = runtime.installPath,
                installedSizeBytes = runtime.installedSizeBytes,
                branchOrVersion = runtime.branchOrVersion,
                isInstalled = runtime.isInstalled,
                isDownloading = runtime.isDownloading,
                hasPartialDownload = runtime.hasPartialDownload,
                updateAvailable = runtime.updateAvailable,
                isShared = runtime.isShared,
                lastPlayedEpochMs = runtime.lastPlayedEpochMs,
                playtimeMinutes = runtime.playtimeMinutes,
                capabilities = if (unsupportedBridge) {
                    emptySet()
                } else {
                    immutableSet(runtime.capabilities)
                },
                unavailableReason = if (unsupportedBridge) {
                    CopyUnavailableReason.LEGACY_BRIDGE_UNSUPPORTED
                } else {
                    null
                },
                canSeparateMatch = true,
                matchMethod = match.matchMethod,
                confidence = match.confidence,
                decisionSource = match.decisionSource,
                decisionRevision = match.matchedAt,
            )
        } else {
            val unavailable = checkNotNull(entry.unavailable)
            OwnedCopySummary(
                key = key,
                source = key.source,
                nativeTitle = match.evidenceDisplayName,
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
                unavailableReason = unavailable.reason,
                canSeparateMatch = false,
                matchMethod = match.matchMethod,
                confidence = match.confidence,
                decisionSource = match.decisionSource,
                decisionRevision = match.matchedAt,
            )
        }
    }

    private fun artwork(
        entries: List<VisibleRelationship>,
        primarySource: GameSource,
    ): CardArtwork {
        val runtimes = entries.mapNotNull(VisibleRelationship::runtime)
            .sortedWith(
                compareBy<OwnedCopyRuntime> { runtime ->
                    when (runtime.key.source) {
                        GameSource.STEAM -> 0
                        primarySource -> 1
                        else -> 2 + sourceIndex(runtime.key.source)
                    }
                }.thenBy { it.key.accountScope.value }
                    .thenBy { it.key.stableSourceId },
            )
        val heroRuntime = runtimes.firstOrNull { it.heroImageUrl.isNotBlank() }
        return CardArtwork(
            iconUrl = runtimes.firstNonblank(OwnedCopyRuntime::iconUrl),
            capsuleImageUrl = runtimes.firstNonblank(OwnedCopyRuntime::capsuleImageUrl),
            headerImageUrl = runtimes.firstNonblank(OwnedCopyRuntime::headerImageUrl),
            heroImageUrl = heroRuntime?.heroImageUrl.orEmpty(),
            gridHeroImageScale = heroRuntime?.gridHeroImageScale ?: 1f,
        )
    }

    private data class Relationship(
        val aggregate: CanonicalLibraryAggregate,
        val match: StoreMatchEntity,
        val key: OwnedCopyKey,
    ) {
        fun cardKey(): CanonicalCardKey =
            if (match.confidence == MatchConfidence.VERIFIED ||
                match.confidence == MatchConfidence.HIGH
            ) {
                CanonicalCardKey.Grouped(CanonicalGameId.parse(match.canonicalId))
            } else {
                CanonicalCardKey.Independent(key)
            }
    }

    private data class VisibleRelationship(
        val relationship: Relationship,
        val runtime: OwnedCopyRuntime?,
        val unavailable: OwnedCopyRuntimeResult.Unavailable?,
    )

    private data class CardArtwork(
        val iconUrl: String,
        val capsuleImageUrl: String,
        val headerImageUrl: String,
        val heroImageUrl: String,
        val gridHeroImageScale: Float,
    )

    private fun freezeAggregates(
        aggregates: List<CanonicalLibraryAggregate>,
    ): List<CanonicalLibraryAggregate> = immutableList(
        aggregates.map { aggregate ->
            aggregate.copy(
                matches = immutableList(aggregate.matches),
                preferences = immutableList(aggregate.preferences),
            )
        },
    )

    private companion object {
        val SOURCE_ORDER = listOf(
            GameSource.STEAM,
            GameSource.GOG,
            GameSource.EPIC,
            GameSource.AMAZON,
            GameSource.CUSTOM_GAME,
        )

        val RELATIONSHIP_COMPARATOR = compareBy<Relationship>(
            { sourceIndex(it.key.source) },
            { it.key.accountScope.value },
            { it.key.stableSourceId },
            { it.match.confidence.ordinal },
            { it.match.matchMethod.ordinal },
            { it.match.decisionSource.ordinal },
        )
        val VISIBLE_RELATIONSHIP_COMPARATOR = Comparator<VisibleRelationship> { left, right ->
            RELATIONSHIP_COMPARATOR.compare(left.relationship, right.relationship)
        }
        val CARD_COMPARATOR = compareBy<CanonicalLibraryCard>(
            { it.canonicalId.value },
            { if (it.key is CanonicalCardKey.Grouped) 0 else 1 },
            {
                when (val key = it.key) {
                    is CanonicalCardKey.Grouped -> -1
                    is CanonicalCardKey.Independent -> sourceIndex(key.copyKey.source)
                }
            },
            {
                when (val key = it.key) {
                    is CanonicalCardKey.Grouped -> ""
                    is CanonicalCardKey.Independent -> key.copyKey.accountScope.value
                }
            },
            {
                when (val key = it.key) {
                    is CanonicalCardKey.Grouped -> ""
                    is CanonicalCardKey.Independent -> key.copyKey.stableSourceId
                }
            },
        )

        fun sourceIndex(source: GameSource): Int = when (source) {
            GameSource.STEAM -> 0
            GameSource.GOG -> 1
            GameSource.EPIC -> 2
            GameSource.AMAZON -> 3
            GameSource.CUSTOM_GAME -> 4
        }

        fun StoreMatchEntity.ownedCopyKeyOrNull(): OwnedCopyKey? = try {
            OwnedCopyKey(
                accountScope = AccountScope.parse(accountScope),
                source = source,
                stableSourceId = stableSourceId,
            )
        } catch (_: IllegalArgumentException) {
            null
        }

        fun String.positiveExactDecimalIntOrNull(): Int? =
            takeIf(POSITIVE_EXACT_DECIMAL::matches)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }

        fun MutableSet<String>.addName(name: String) {
            if (name.isNotBlank()) add(name)
        }

        fun List<OwnedCopyRuntime>.firstNonblank(
            selector: (OwnedCopyRuntime) -> String,
        ): String = firstNotNullOfOrNull { runtime ->
            selector(runtime).takeIf(String::isNotBlank)
        }.orEmpty()

        fun <T> immutableList(values: Collection<T>): List<T> =
            if (values.isEmpty()) emptyList()
            else Collections.unmodifiableList(ArrayList(values))

        fun <T> immutableSet(values: Collection<T>): Set<T> =
            if (values.isEmpty()) emptySet()
            else Collections.unmodifiableSet(LinkedHashSet(values))

        val POSITIVE_EXACT_DECIMAL = Regex("[1-9][0-9]*")
    }
}
