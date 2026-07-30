package app.gamenative.library.canonical.source

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.utils.CustomGameProjectionScan
import app.gamenative.utils.CustomGameScanner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

@Singleton
class CustomOwnedCopySourceAdapter private constructor(
    private val accountScopeProvider: AccountScopeProvider,
    private val scanProjection: () -> CustomGameProjectionScan,
) : OwnedCopySourceAdapter {
    @Inject
    constructor(
        accountScopeProvider: AccountScopeProvider,
    ) : this(accountScopeProvider, CustomGameScanner::scanForCanonicalProjection)

    internal constructor(
        accountScopeProvider: AccountScopeProvider,
        scanProjection: () -> CustomGameProjectionScan,
        @Suppress("UNUSED_PARAMETER") marker: Unit = Unit,
    ) : this(accountScopeProvider, scanProjection)

    override val source: GameSource = GameSource.CUSTOM_GAME

    override fun invalidations(): Flow<Unit> = CustomGameScanner.canonicalInvalidations()

    override suspend fun snapshot(): SourceProjectionBatch {
        val accountScope = try {
            accountScopeProvider.current(source)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return sourceReadFailed(source, null, error)
        } ?: return missingAccountScope(source)

        return try {
            val scan = scanProjection()
            val copies = scan.entries.map { entry ->
                OwnedCopyProjection(
                    key = OwnedCopyKey(accountScope, source, entry.appId.toString()),
                    displayName = entry.displayName,
                    developer = "",
                    releaseYear = null,
                    appType = CanonicalAppType.GAME,
                )
            }.sortedBy { it.key.stableSourceId }
            if (copies.isEmpty() && scan.readFailureClass != null) {
                sourceReadFailed(source, accountScope, scan.readFailureClass)
            } else {
                sourceBatch(
                    source = source,
                    accountScope = accountScope,
                    copies = copies,
                    partialReason = when {
                        scan.readFailureClass != null -> SnapshotReason.SOURCE_READ_FAILED
                        scan.missingStableId -> SnapshotReason.MISSING_STABLE_ID
                        else -> null
                    },
                    errorClass = scan.readFailureClass,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            sourceReadFailed(source, accountScope, error)
        }
    }

    override suspend fun resolve(key: OwnedCopyKey): SourceOwnedCopyReference? {
        if (key.source != source) return null
        val currentScope = accountScopeProvider.current(source) ?: return null
        if (key.accountScope != currentScope) return null
        val appId = key.stableSourceId.toIntOrNull()
            ?.takeIf { it > 0 && it.toString() == key.stableSourceId }
            ?: return null
        if (!CustomGameScanner.hasPersistedCanonicalAppId(appId)) return null
        return SourceOwnedCopyReference.Custom(key, appId)
    }
}
