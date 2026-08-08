package app.gamenative.library.canonical.catalog

import app.gamenative.service.SteamService
import `in`.dragonbra.javasteam.types.KeyValue
import javax.inject.Inject
import javax.inject.Singleton

data class SteamPublicPicsFacets(
    val genreIds: Set<Int>,
    val categoryIds: Set<Int>,
    val storeTagIds: Set<Int>,
) {
    init {
        require(genreIds.all { it > 0 }) { "Steam genre IDs must be positive" }
        require(categoryIds.all { it > 0 }) { "Steam category IDs must be positive" }
        require(storeTagIds.all { it > 0 }) { "Steam store tag IDs must be positive" }
    }
}

fun interface SteamPublicPicsFacetSource {
    suspend fun fetch(trustedSteamAppId: Int): SteamPublicPicsFacets?
}

@Singleton
class SteamSessionPublicPicsFacetSource @Inject constructor() : SteamPublicPicsFacetSource {
    override suspend fun fetch(trustedSteamAppId: Int): SteamPublicPicsFacets? =
        SteamService.fetchPublicPicsFacets(trustedSteamAppId)
}

internal fun parseSteamPublicPicsFacets(keyValues: KeyValue): SteamPublicPicsFacets {
    val common = keyValues["common"]
    return SteamPublicPicsFacets(
        genreIds = common["genres"].positiveNumericChildValues(),
        categoryIds = common["category"].children.mapNotNullTo(sortedSetOf()) { child ->
            child.name
                ?.takeIf { it.startsWith(CATEGORY_PREFIX) }
                ?.removePrefix(CATEGORY_PREFIX)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
        },
        storeTagIds = common["store_tags"].positiveNumericChildValues(),
    )
}

private fun KeyValue.positiveNumericChildValues(): Set<Int> = children.mapNotNullTo(sortedSetOf()) { child ->
    child.value?.toIntOrNull()?.takeIf { it > 0 }
}

private const val CATEGORY_PREFIX = "category_"
