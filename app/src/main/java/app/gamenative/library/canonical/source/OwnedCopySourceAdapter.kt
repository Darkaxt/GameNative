package app.gamenative.library.canonical.source

import app.gamenative.data.AmazonGame
import app.gamenative.data.EpicGame
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.AccountLifecycleState
import app.gamenative.library.canonical.AccountScopeInvalidations
import app.gamenative.library.canonical.AccountScopeProvider
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

enum class SnapshotCompleteness {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE,
}

enum class SnapshotReason {
    MISSING_ACCOUNT_SCOPE,
    SOURCE_READ_FAILED,
    MISSING_STABLE_ID,
    MALFORMED_SOURCE_ID,
    FEATURE_DISABLED,
    PRESENCE_LEDGER_NOT_READY,
    MISSING_MATERIALIZED_ROW,
    ACCOUNT_SCOPE_CHANGED,
}

data class OwnedCopyProjection(
    val key: OwnedCopyKey,
    val displayName: String,
    val developer: String,
    val releaseYear: Int?,
    val appType: CanonicalAppType,
    val directSteamAppId: Int? = null,
    val genreKeys: Set<String> = emptySet(),
    val tagIds: Set<Int> = emptySet(),
    val featureKeys: Set<String> = emptySet(),
)

data class SourceProjectionBatch(
    val source: GameSource,
    val accountScope: AccountScope?,
    val lifecycleGeneration: Long? = null,
    val completeness: SnapshotCompleteness,
    val copies: List<OwnedCopyProjection>,
    val reason: SnapshotReason? = null,
    val errorClass: KClass<out Throwable>? = null,
)

sealed interface SourceOwnedCopyReference {
    val key: OwnedCopyKey

    data class Steam(
        override val key: OwnedCopyKey,
        val appId: Int,
    ) : SourceOwnedCopyReference

    data class Gog(
        override val key: OwnedCopyKey,
        val gameId: String,
    ) : SourceOwnedCopyReference

    data class Epic(
        override val key: OwnedCopyKey,
        val localRowId: Int,
        val namespace: String,
        val catalogId: String,
    ) : SourceOwnedCopyReference

    data class Amazon(
        override val key: OwnedCopyKey,
        val localRowId: Int,
        val productId: String,
        val entitlementId: String,
    ) : SourceOwnedCopyReference

    data class Custom(
        override val key: OwnedCopyKey,
        val appId: Int,
    ) : SourceOwnedCopyReference
}

interface OwnedCopySourceAdapter {
    val source: GameSource

    fun invalidations(): Flow<Unit>

    suspend fun snapshot(): SourceProjectionBatch

    suspend fun resolve(key: OwnedCopyKey): SourceOwnedCopyReference?
}

internal suspend fun AccountScopeProvider.isAccountScopeUnchanged(
    source: GameSource,
    accountScope: AccountScope,
    generation: Long,
    lifecycleState: AccountLifecycleState = AccountScopeInvalidations,
): Boolean = current(source) == accountScope &&
    lifecycleState.generation(source) == generation

internal fun missingAccountScope(
    source: GameSource,
    lifecycleGeneration: Long? = null,
): SourceProjectionBatch = unavailableBatch(
    source = source,
    lifecycleGeneration = lifecycleGeneration,
    reason = SnapshotReason.MISSING_ACCOUNT_SCOPE,
)

internal fun presenceLedgerNotReady(
    source: GameSource,
    accountScope: AccountScope,
    lifecycleGeneration: Long,
): SourceProjectionBatch = unavailableBatch(
    source = source,
    accountScope = accountScope,
    lifecycleGeneration = lifecycleGeneration,
    reason = SnapshotReason.PRESENCE_LEDGER_NOT_READY,
)

internal fun accountScopeChanged(
    source: GameSource,
    lifecycleGeneration: Long? = null,
): SourceProjectionBatch = unavailableBatch(
    source = source,
    lifecycleGeneration = lifecycleGeneration,
    reason = SnapshotReason.ACCOUNT_SCOPE_CHANGED,
)

internal fun sourceReadFailed(
    source: GameSource,
    accountScope: AccountScope?,
    error: Exception,
    lifecycleGeneration: Long? = null,
): SourceProjectionBatch = unavailableBatch(
    source = source,
    accountScope = accountScope,
    lifecycleGeneration = lifecycleGeneration,
    reason = SnapshotReason.SOURCE_READ_FAILED,
    errorClass = error::class,
)

internal fun sourceReadFailed(
    source: GameSource,
    accountScope: AccountScope?,
    errorClass: KClass<out Throwable>,
    lifecycleGeneration: Long? = null,
): SourceProjectionBatch = unavailableBatch(
    source = source,
    accountScope = accountScope,
    lifecycleGeneration = lifecycleGeneration,
    reason = SnapshotReason.SOURCE_READ_FAILED,
    errorClass = errorClass,
)

internal fun sourceBatch(
    source: GameSource,
    accountScope: AccountScope,
    copies: List<OwnedCopyProjection>,
    partialReason: SnapshotReason?,
    lifecycleGeneration: Long? = null,
    errorClass: KClass<out Throwable>? = null,
): SourceProjectionBatch = SourceProjectionBatch(
    source = source,
    accountScope = accountScope,
    lifecycleGeneration = lifecycleGeneration,
    completeness = if (partialReason == null) {
        SnapshotCompleteness.COMPLETE
    } else {
        SnapshotCompleteness.PARTIAL
    },
    copies = copies,
    reason = partialReason,
    errorClass = errorClass,
)

internal fun sourceQualifiedKeys(provider: String, values: List<String>): Set<String> = values
    .asSequence()
    .map(CanonicalNormalization::titleKey)
    .filter(String::isNotEmpty)
    .map { "$provider:$it" }
    .toSortedSet()

internal fun preferredEpicRows(games: Iterable<EpicGame>): Map<String, EpicGame> {
    val rows = linkedMapOf<String, EpicGame>()
    games.sortedWith(compareByDescending<EpicGame>(EpicGame::isInstalled).thenBy(EpicGame::id))
        .forEach { game ->
            val stableId = try {
                EpicStableSourceId.encode(game.namespace, game.catalogId)
            } catch (_: IllegalArgumentException) {
                return@forEach
            }
            rows.putIfAbsent(stableId, game)
        }
    return rows
}

internal fun preferredAmazonRows(games: Iterable<AmazonGame>): Map<String, AmazonGame> {
    val rows = linkedMapOf<String, AmazonGame>()
    games.asSequence()
        .filter { it.productId.isNotBlank() }
        .sortedWith(compareByDescending<AmazonGame>(AmazonGame::isInstalled).thenBy(AmazonGame::appId))
        .forEach { game -> rows.putIfAbsent(game.productId, game) }
    return rows
}

private fun unavailableBatch(
    source: GameSource,
    reason: SnapshotReason,
    accountScope: AccountScope? = null,
    lifecycleGeneration: Long? = null,
    errorClass: KClass<out Throwable>? = null,
): SourceProjectionBatch = SourceProjectionBatch(
    source = source,
    accountScope = accountScope,
    lifecycleGeneration = lifecycleGeneration,
    completeness = SnapshotCompleteness.UNAVAILABLE,
    copies = emptyList(),
    reason = reason,
    errorClass = errorClass,
)
