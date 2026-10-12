package app.gamenative.library.dlc

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.AccountLifecycleState
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import `in`.dragonbra.javasteam.enums.ELicenseFlags
import `in`.dragonbra.javasteam.enums.ELicenseType
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.types.KeyValue
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

internal class NativeDlcCopyAuthority(
    private val scopes: AccountScopeProvider,
    private val lifecycle: AccountLifecycleState,
    private val resolve: suspend (OwnedCopyKey) -> SourceOwnedCopyReference?,
) : DlcCopyAuthority {
    @Inject constructor(
        scopes: AccountScopeProvider,
        lifecycle: AccountLifecycleState,
        runtimes: OwnedCopyRuntimeRegistry,
    ) : this(scopes, lifecycle, { key ->
        (runtimes.resolve(key) as? OwnedCopyRuntimeResult.Available)?.copy?.reference
    })

    override suspend fun current(key: OwnedCopyKey): DlcCopyProof? = try {
        val generation = lifecycle.generation(key.source)
        if (scopes.current(key.source) != key.accountScope) null else {
            val reference = resolve(key)
            if (reference == null || !reference.matchesNativeKey(key) ||
                scopes.current(key.source) != key.accountScope || lifecycle.generation(key.source) != generation
            ) null else DlcCopyProof(reference, generation)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

private fun SourceOwnedCopyReference.matchesNativeKey(expected: OwnedCopyKey): Boolean {
    if (key != expected) return false
    return when (this) {
        is SourceOwnedCopyReference.Gog -> expected.source == GameSource.GOG && gameId == expected.stableSourceId &&
            gameId.toLongOrNull()?.let { it > 0 && it.toString() == gameId } == true
        is SourceOwnedCopyReference.Steam -> expected.source == GameSource.STEAM && appId > 0 && appId.toString() == expected.stableSourceId
        is SourceOwnedCopyReference.Epic -> expected.source == GameSource.EPIC && namespace.isEpicCatalogId() &&
            catalogId.isEpicCatalogId() && EpicStableSourceId.encode(namespace, catalogId) == expected.stableSourceId
        is SourceOwnedCopyReference.Amazon -> expected.source == GameSource.AMAZON && productId == expected.stableSourceId && entitlementId.isNotBlank()
        is SourceOwnedCopyReference.Custom -> expected.source == GameSource.CUSTOM_GAME && appId > 0 && appId.toString() == expected.stableSourceId
    }
}

internal fun projectSteamDlcLicenses(licenses: List<License>, currentAccountId: Int): List<SteamDlcLicense>? {
    if (currentAccountId <= 0 || licenses.size > 50_000) return null
    val grants = mutableMapOf<Int, SteamDlcLicense>()
    for (license in licenses) {
        if (license.ownerAccountID == 0) return null
        if (license.ownerAccountID != currentAccountId || ELicenseFlags.Borrowed in license.licenseFlags) continue
        if (license.licenseFlags.any { it in ENDED_LICENSE_FLAGS }) continue
        if (license.licenseType != ELicenseType.SinglePurchase || license.licenseFlags.any { it !in ACTIVE_LICENSE_FLAGS } || license.packageID <= 0) return null
        val grant = SteamDlcLicense(license.packageID, license.accessToken, license.lastChangeNumber)
        val previous = grants.put(license.packageID, grant)
        if (previous != null && previous != grant) return null
    }
    return grants.values.sortedBy { it.packageId }
}

internal fun readSteamDlcPackageApps(root: KeyValue): Set<String>? {
    val membership = root.children.filter { it.name == "appids" }.singleOrNull() ?: return null
    if (membership.value != null || membership.children.size > 50_000) return null
    val ids = membership.children.map { child ->
        val raw = child.value ?: return null
        raw.toIntOrNull()?.takeIf { it > 0 && it.toString() == raw }?.toString() ?: return null
    }
    if (ids.distinct().size != ids.size) return null
    return ids.toSet()
}

private val ENDED_LICENSE_FLAGS = setOf(ELicenseFlags.Expired)
private val ACTIVE_LICENSE_FLAGS = setOf(
    ELicenseFlags.None, ELicenseFlags.LowViolenceContent, ELicenseFlags.ImportedFromSteam2,
    ELicenseFlags.ForceRunRestriction, ELicenseFlags.RegionRestrictionExpired, ELicenseFlags.PreferredOwner,
)
