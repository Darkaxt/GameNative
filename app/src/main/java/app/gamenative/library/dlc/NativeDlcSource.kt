package app.gamenative.library.dlc

import android.content.Context
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.service.SteamService
import app.gamenative.service.epic.EpicAuthManager
import app.gamenative.service.gog.GOGAuthManager
import app.gamenative.utils.Net
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class NativeDlcSource(
    private val http: DlcJsonTransport,
    private val gogBearer: suspend () -> String,
    private val epicSession: suspend () -> EpicDlcSession,
    private val steamSnapshot: suspend () -> List<SteamDlcLicense>?,
    private val steamPackages: suspend (List<SteamDlcLicense>) -> Map<Int, Set<String>>,
) : DlcSource {
    @Inject constructor(@ApplicationContext context: Context) : this(
        DlcHttpTransport(Net.http),
        {
            val result = GOGAuthManager.getStoredCredentials(context)
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            result.getOrNull()?.accessToken ?: throw DlcTransportException()
        },
        {
            val result = EpicAuthManager.getStoredCredentials(context)
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val credentials = result.getOrNull() ?: throw DlcTransportException()
            EpicDlcSession(credentials.accountId, credentials.accessToken)
        },
        { SteamService.currentDlcLicenseSnapshot() },
        { SteamService.readLicensedDlcPackages(it) },
    )

    override suspend fun catalog(reference: SourceOwnedCopyReference): DlcCatalog = when (reference) {
        is SourceOwnedCopyReference.Gog -> GogDlcProvider(http, gogBearer).catalog(reference.gameId) ?: throw DlcTransportException()
        is SourceOwnedCopyReference.Epic -> EpicDlcProvider(http, epicSession).catalog(reference.namespace, reference.catalogId) ?: throw DlcTransportException()
        is SourceOwnedCopyReference.Steam -> steamCatalog(reference.appId)
        else -> throw DlcTransportException()
    }

    override suspend fun entitlements(reference: SourceOwnedCopyReference): DlcEntitlements = when (reference) {
        is SourceOwnedCopyReference.Gog -> GogDlcProvider(http, gogBearer).entitlements(reference.gameId)
        is SourceOwnedCopyReference.Epic -> EpicDlcProvider(http, epicSession).entitlements()
        is SourceOwnedCopyReference.Steam -> SteamDlcEntitlementProvider(steamSnapshot, steamPackages).load()
        else -> unknownEntitlements()
    }

    private suspend fun steamCatalog(parentId: Int): DlcCatalog {
        if (parentId <= 0) throw DlcTransportException()
        val parent = steamDetails(parentId.toString()) ?: throw DlcTransportException()
        val advertised = parseSteamDlcCatalog(parent, parentId.toString()) ?: throw DlcTransportException()
        val items = advertised.items.toMutableList()
        withTimeoutOrNull(12_000) {
            for (index in items.indices.take(20)) {
                val item = items[index]
                try {
                    val child = steamDetails(item.id) ?: continue
                    if (child["type"].text() != "dlc") continue
                    val fullGame = (child["fullgame"] as? JsonObject)?.get("appid").nativeNumericId()
                    if (fullGame != null && fullGame != parentId.toString()) {
                        items[index] = item.copy(associationVerified = false)
                    } else if (fullGame == parentId.toString()) {
                        items[index] = item.copy(title = child["name"].text()?.safeDlcTitle())
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The parent's advertised native ID survives unavailable optional title details.
                }
            }
        }
        return DlcCatalog(items.toList(), complete = items.all { it.title != null && it.associationVerified })
    }

    private suspend fun steamDetails(appId: String): JsonObject? {
        val url = "https://store.steampowered.com/api/appdetails".toHttpUrl().newBuilder()
            .addQueryParameter("appids", appId).addQueryParameter("l", "english").addQueryParameter("cc", "US").build()
        val root = http.get(url) as? JsonObject ?: return null
        val envelope = root[appId] as? JsonObject ?: return null
        val success = (envelope["success"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        val data = envelope["data"] as? JsonObject ?: return null
        return data.takeIf { root.size == 1 && success == true && it["steam_appid"].nativeNumericId() == appId }
    }
}
