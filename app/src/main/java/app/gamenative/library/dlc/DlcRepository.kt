package app.gamenative.library.dlc

import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class DlcCatalogItem(val id: String, val title: String?, val associationVerified: Boolean = true)
data class DlcCatalog(val items: List<DlcCatalogItem>, val complete: Boolean)
data class DlcEntitlements(val ownedIds: Set<String>, val complete: Boolean)
enum class DlcOwnership { OWNED, NOT_OWNED, UNKNOWN }
data class DlcItem(val id: String, val title: String?, val ownership: DlcOwnership)

sealed interface DlcState {
    data object Idle : DlcState
    data object Loading : DlcState
    data class Content(
        val key: OwnedCopyKey,
        val items: List<DlcItem>,
        val catalogComplete: Boolean,
        val entitlementsComplete: Boolean,
    ) : DlcState
    data object Unavailable : DlcState
}

internal data class DlcCopyProof(val reference: SourceOwnedCopyReference, val generation: Long)
internal fun interface DlcCopyAuthority {
    suspend fun current(key: OwnedCopyKey): DlcCopyProof?
}
internal interface DlcSource {
    suspend fun catalog(reference: SourceOwnedCopyReference): DlcCatalog
    suspend fun entitlements(reference: SourceOwnedCopyReference): DlcEntitlements
}

internal class DlcRepository(
    private val authority: DlcCopyAuthority,
    private val source: DlcSource,
) {
    suspend fun load(key: OwnedCopyKey): DlcState = try {
        val captured = authority.current(key)
        if (captured == null || captured.reference.key != key) {
            DlcState.Unavailable
        } else {
            val catalog = source.catalog(captured.reference)
            if (authority.current(key) != captured) {
                DlcState.Unavailable
            } else {
                val entitlements = try {
                    withTimeoutOrNull(25_000) { source.entitlements(captured.reference) }
                        ?: DlcEntitlements(emptySet(), complete = false)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    DlcEntitlements(emptySet(), complete = false)
                }
                if (authority.current(key) != captured) {
                    DlcState.Unavailable
                } else {
                    DlcState.Content(
                        key = key,
                        items = catalog.items.map { item ->
                            DlcItem(item.id, item.title, when {
                                !item.associationVerified -> DlcOwnership.UNKNOWN
                                item.id in entitlements.ownedIds -> DlcOwnership.OWNED
                                entitlements.complete -> DlcOwnership.NOT_OWNED
                                else -> DlcOwnership.UNKNOWN
                            })
                        },
                        catalogComplete = catalog.complete,
                        entitlementsComplete = entitlements.complete,
                    )
                }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        DlcState.Unavailable
    }
}
