package app.gamenative.library.dlc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val MAX_DLC_ITEMS = 256

internal fun parseSteamDlcCatalog(data: JsonObject, expectedParentId: String): DlcCatalog? {
    if (data["steam_appid"].nativeNumericId() != expectedParentId || data["type"].text() != "game") return null
    val values = data["dlc"] as? JsonArray ?: return null
    if (values.size > MAX_DLC_ITEMS) return null
    val ids = values.map { value ->
        value.nativeNumericId()?.takeIf { it.toIntOrNull() != null } ?: return null
    }
    if (ids.distinct().size != ids.size || expectedParentId in ids) return null
    return DlcCatalog(ids.map { DlcCatalogItem(it, null) }, complete = ids.isEmpty())
}

internal fun parseGogDlcCatalog(data: JsonObject, expectedParentId: String): DlcCatalog? {
    if (data["id"].nativeNumericId() != expectedParentId || data["game_type"].text() != "game") return null
    val references = (data["dlcs"] as? JsonObject)?.get("products") as? JsonArray ?: return null
    val expanded = data["expanded_dlcs"] as? JsonArray ?: return null
    if (references.size > MAX_DLC_ITEMS || expanded.size > MAX_DLC_ITEMS) return null
    val ids = references.map { (it as? JsonObject)?.get("id").nativeNumericId() ?: return null }
    if (ids.distinct().size != ids.size || expectedParentId in ids) return null
    val names = mutableMapOf<String, String?>()
    for (entry in expanded) {
        val child = entry as? JsonObject ?: return null
        val id = child["id"].nativeNumericId() ?: return null
        if (id !in ids || id in names || child["game_type"].text() != "dlc") return null
        names[id] = child["title"].text()?.safeDlcTitle()
    }
    return DlcCatalog(
        items = ids.map { DlcCatalogItem(it, names[it]) },
        complete = ids.all { names[it] != null },
    )
}

internal fun kotlinx.serialization.json.JsonElement?.text(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun kotlinx.serialization.json.JsonElement?.nativeNumericId(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.takeIf {
        it.matches(Regex("[1-9][0-9]{0,19}")) && it.toLongOrNull() != null
    }

internal fun String.safeDlcTitle(): String? = take(1024)
    .replace(Regex("<[^>]*>"), " ")
    .filterNot { it.isISOControl() }
    .trim().take(240).takeIf(String::isNotBlank)
