package app.gamenative.ui.data

import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.library.canonical.CanonicalCardKey
import app.gamenative.library.canonical.stableComposeKey

sealed interface LibraryCardIdentity {
    data class SourceCopy(val item: LibraryItem) : LibraryCardIdentity
    data class Canonical(val key: CanonicalCardKey) : LibraryCardIdentity
    data class Promotion(val id: String) : LibraryCardIdentity
}

data class LibraryCard(
    val identity: LibraryCardIdentity,
    val index: Int,
    val name: String,
    val iconUrl: String,
    val capsuleImageUrl: String,
    val headerImageUrl: String,
    val heroImageUrl: String,
    val gridHeroImageScale: Float,
    val ownedSources: Set<GameSource>,
    val compatibilityStatus: GameCompatibilityStatus?,
    val gameStats: GameCardStats?,
    val sizeBytes: Long,
    val isInstalled: Boolean,
    val isShared: Boolean,
    val isRecommended: Boolean,
    val recommendedGameId: String,
    val recRating: Int?,
    val recDiscount: String?,
    val recPrice: String?,
    val recBasePrice: String?,
    val recSeedCount: Int,
    val recSeedIconUrl: String?,
    val recStoreCard: Boolean,
    val recSource: String,
    val isFeatured: Boolean,
) {
    val composeKey: String
        get() = when (val value = identity) {
            is LibraryCardIdentity.SourceCopy -> "source:${value.item.appId}"
            is LibraryCardIdentity.Canonical -> "canonical:${value.key.stableComposeKey()}"
            is LibraryCardIdentity.Promotion -> "promotion:${value.id}"
        }

    val orderedSources: List<GameSource>
        get() = OWNED_SOURCE_ORDER.filter(ownedSources::contains)

    fun sourceItemOrNull(): LibraryItem? =
        (identity as? LibraryCardIdentity.SourceCopy)?.item

    companion object {
        val OWNED_SOURCE_ORDER = listOf(
            GameSource.STEAM,
            GameSource.GOG,
            GameSource.EPIC,
            GameSource.AMAZON,
            GameSource.CUSTOM_GAME,
        )

        fun fromSource(
            item: LibraryItem,
            compatibilityStatus: GameCompatibilityStatus? = item.compatibilityStatus,
            gameStats: GameCardStats? = null,
        ): LibraryCard = fromLibraryItem(
            identity = LibraryCardIdentity.SourceCopy(item),
            item = item,
            iconUrl = item.clientIconUrl,
            compatibilityStatus = compatibilityStatus,
            gameStats = gameStats,
        )

        fun fromPromotion(
            item: LibraryItem,
            compatibilityStatus: GameCompatibilityStatus? = item.compatibilityStatus,
            gameStats: GameCardStats? = null,
        ): LibraryCard = fromLibraryItem(
            identity = LibraryCardIdentity.Promotion(item.appId),
            item = item,
            iconUrl = item.iconHash,
            compatibilityStatus = compatibilityStatus,
            gameStats = gameStats,
        )

        private fun fromLibraryItem(
            identity: LibraryCardIdentity,
            item: LibraryItem,
            iconUrl: String,
            compatibilityStatus: GameCompatibilityStatus?,
            gameStats: GameCardStats?,
        ): LibraryCard = LibraryCard(
            identity = identity,
            index = item.index,
            name = item.name,
            iconUrl = iconUrl,
            capsuleImageUrl = item.capsuleImageUrl,
            headerImageUrl = item.headerImageUrl,
            heroImageUrl = item.heroImageUrl,
            gridHeroImageScale = item.gridHeroImageScale,
            ownedSources = setOf(item.gameSource),
            compatibilityStatus = compatibilityStatus,
            gameStats = gameStats,
            sizeBytes = item.sizeBytes,
            isInstalled = item.isInstalled,
            isShared = item.isShared,
            isRecommended = item.isRecommended,
            recommendedGameId = item.recommendedGameId,
            recRating = item.recRating,
            recDiscount = item.recDiscount,
            recPrice = item.recPrice,
            recBasePrice = item.recBasePrice,
            recSeedCount = item.recSeedCount,
            recSeedIconUrl = item.recSeedIconUrl,
            recStoreCard = item.recStoreCard,
            recSource = item.recSource,
            isFeatured = item.isFeatured,
        )

        fun canonical(
            key: CanonicalCardKey,
            index: Int,
            name: String,
            iconUrl: String = "",
            capsuleImageUrl: String = "",
            headerImageUrl: String = "",
            heroImageUrl: String = "",
            gridHeroImageScale: Float = 1f,
            ownedSources: Set<GameSource>,
            compatibilityStatus: GameCompatibilityStatus? = null,
            gameStats: GameCardStats? = null,
            sizeBytes: Long = 0,
            isInstalled: Boolean = false,
            isShared: Boolean = false,
        ): LibraryCard = LibraryCard(
            identity = LibraryCardIdentity.Canonical(key),
            index = index,
            name = name,
            iconUrl = iconUrl,
            capsuleImageUrl = capsuleImageUrl,
            headerImageUrl = headerImageUrl,
            heroImageUrl = heroImageUrl,
            gridHeroImageScale = gridHeroImageScale,
            ownedSources = ownedSources,
            compatibilityStatus = compatibilityStatus,
            gameStats = gameStats,
            sizeBytes = sizeBytes,
            isInstalled = isInstalled,
            isShared = isShared,
            isRecommended = false,
            recommendedGameId = "",
            recRating = null,
            recDiscount = null,
            recPrice = null,
            recBasePrice = null,
            recSeedCount = 0,
            recSeedIconUrl = null,
            recStoreCard = false,
            recSource = "",
            isFeatured = false,
        )
    }
}
