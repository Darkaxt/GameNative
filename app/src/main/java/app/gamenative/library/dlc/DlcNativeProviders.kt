package app.gamenative.library.dlc

import app.gamenative.data.canonical.EpicStableSourceId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

internal interface DlcJsonTransport {
    suspend fun get(url: HttpUrl, bearer: String? = null): JsonElement
}

internal class GogDlcProvider(
    private val http: DlcJsonTransport,
    private val bearer: suspend () -> String,
) {
    suspend fun catalog(parentId: String): DlcCatalog? {
        requireNativeProductId(parentId)
        val url = "https://api.gog.com/products/$parentId".toHttpUrl().newBuilder()
            .addQueryParameter("expand", "expanded_dlcs").build()
        return (http.get(url) as? JsonObject)?.let { parseGogDlcCatalog(it, parentId) }
    }

    suspend fun entitlements(parentId: String): DlcEntitlements = try {
        requireNativeProductId(parentId)
        val data = http.get("https://embed.gog.com/user/data/games".toHttpUrl(), bearer()) as? JsonObject
        val values = data?.get("owned") as? JsonArray
        if (values == null || values.size > 50_000) unknownEntitlements() else {
            val ids = values.map { it.nativeNumericId() ?: throw DlcTransportException() }.toSet()
            if (parentId !in ids) unknownEntitlements() else DlcEntitlements(ids, complete = true)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        unknownEntitlements()
    }
}

internal data class EpicDlcSession(val accountId: String, val bearer: String)
internal class EpicDlcProvider(
    private val http: DlcJsonTransport,
    private val session: suspend () -> EpicDlcSession,
) {
    suspend fun catalog(namespace: String, catalogId: String): DlcCatalog? {
        if (!namespace.isEpicCatalogId() || !catalogId.isEpicCatalogId()) return null
        val url = "https://catalog-public-service-prod06.ol.epicgames.com/catalog/api/shared/namespace"
            .toHttpUrl().newBuilder().addPathSegment(namespace).addPathSegments("bulk/items")
            .addQueryParameter("id", catalogId).addQueryParameter("includeDLCDetails", "true")
            .addQueryParameter("includeMainGameDetails", "true")
            .addQueryParameter("country", "US").addQueryParameter("locale", "en-US").build()
        val root = http.get(url, session().bearer) as? JsonObject ?: return null
        val parent = root[catalogId] as? JsonObject ?: return null
        if (root.size != 1 || parent["id"].text() != catalogId || parent["namespace"].text() != namespace) return null
        val raw = parent["dlcItemList"] ?: return null
        val children = if (raw == JsonNull) JsonArray(emptyList()) else raw as? JsonArray ?: return null
        if (children.size > MAX_DLC_ITEMS) return null
        val items = children.map { child ->
            val entry = child as? JsonObject ?: return null
            val id = entry["id"].text()?.takeIf(String::isEpicCatalogId) ?: return null
            val childNamespace = entry["namespace"].text()?.takeIf(String::isEpicCatalogId) ?: return null
            if (id == catalogId && childNamespace == namespace) return null
            DlcCatalogItem(EpicStableSourceId.encode(childNamespace, id), entry["title"].text()?.safeDlcTitle())
        }
        if (items.map { it.id }.distinct().size != items.size) return null
        return DlcCatalog(items, complete = items.all { it.title != null })
    }

    suspend fun entitlements(): DlcEntitlements {
        val owned = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        var complete = true
        try {
            val credentials = session()
            if (!credentials.accountId.matches(Regex("[a-fA-F0-9]{32}"))) return unknownEntitlements()
            repeat(50) { page ->
                val url = "https://entitlement-public-service-prod08.ol.epicgames.com/entitlement/api/account"
                    .toHttpUrl().newBuilder().addPathSegment(credentials.accountId).addPathSegment("entitlements")
                    .addQueryParameter("start", (page * 1000).toString()).addQueryParameter("count", "1000").build()
                val entries = http.get(url, credentials.bearer) as? JsonArray ?: throw DlcTransportException()
                if (entries.size > 1000) throw DlcTransportException()
                for (raw in entries) {
                    val entry = raw as? JsonObject ?: throw DlcTransportException()
                    val id = entry["id"].text()?.takeIf(String::isEpicCatalogId) ?: throw DlcTransportException()
                    if (!seen.add(id)) return DlcEntitlements(owned.toSet(), complete = false)
                    if (entry["accountId"] != null && entry["accountId"].text() != credentials.accountId) {
                        return unknownEntitlements()
                    }
                    val namespace = entry["namespace"].text()?.takeIf(String::isEpicCatalogId) ?: throw DlcTransportException()
                    val catalogId = entry["catalogItemId"].text()?.takeIf(String::isEpicCatalogId) ?: throw DlcTransportException()
                    val active = (entry["active"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw DlcTransportException()
                    val consumable = (entry["consumable"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw DlcTransportException()
                    val status = entry["status"].text() ?: throw DlcTransportException()
                    if (status !in setOf("ACTIVE", "INACTIVE", "REVOKED", "DELETED") || (active && consumable)) complete = false
                    val rawCached = entry["readFromCache"]
                    val cached = if (rawCached == null) false else {
                        (rawCached as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw DlcTransportException()
                    }
                    if (cached) complete = false
                    if (!cached && active && !consumable && status == "ACTIVE") owned += EpicStableSourceId.encode(namespace, catalogId)
                }
                if (entries.size < 1000) return DlcEntitlements(owned.toSet(), complete)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return DlcEntitlements(owned.toSet(), complete = false)
        }
        return DlcEntitlements(owned.toSet(), complete = false)
    }
}

internal data class SteamDlcLicense(val packageId: Int, val accessToken: Long, val revision: Int, val admission: Any? = null)
internal class SteamDlcEntitlementProvider(
    private val snapshot: suspend () -> List<SteamDlcLicense>?,
    private val packages: suspend (List<SteamDlcLicense>) -> Map<Int, Set<String>>,
) {
    suspend fun load(): DlcEntitlements {
        val owned = mutableSetOf<String>()
        try {
            val licenses = snapshot()?.sortedBy { it.packageId } ?: return unknownEntitlements()
            if (licenses.size > 5000 || licenses.any { it.packageId <= 0 } || licenses.map { it.packageId }.distinct().size != licenses.size) {
                return unknownEntitlements()
            }
            var complete = true
            for (chunk in licenses.chunked(100)) {
                val contents = try {
                    packages(chunk)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    complete = false
                    break
                }
                if (contents.keys != chunk.map { it.packageId }.toSet()) complete = false
                chunk.forEach { license ->
                    val ids = contents[license.packageId]
                    if (ids == null || ids.any { id -> id.toIntOrNull()?.let { it > 0 && it.toString() == id } != true }) {
                        complete = false
                    } else {
                        owned += ids
                    }
                }
            }
            if (snapshot()?.sortedBy { it.packageId } != licenses) return unknownEntitlements()
            return DlcEntitlements(owned.toSet(), complete)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return unknownEntitlements()
        }
    }
}

internal fun unknownEntitlements() = DlcEntitlements(emptySet(), complete = false)

private fun requireNativeProductId(id: String) {
    if (!id.matches(Regex("[1-9][0-9]{0,19}")) || id.toLongOrNull() == null) throw DlcTransportException()
}
