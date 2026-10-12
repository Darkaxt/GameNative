package app.gamenative.library.dlc

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@OptIn(ExperimentalCoroutinesApi::class)
internal class DlcTransportException : IOException("DLC provider unavailable"), CopyableThrowable<DlcTransportException> {
    override fun createCopy(): DlcTransportException? = null
}

internal class DlcHttpTransport(client: OkHttpClient) : DlcJsonTransport {
    private val publicClient = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val accountClient = publicClient.newBuilder().cache(null).build()

    override suspend fun get(url: HttpUrl, bearer: String?): JsonElement {
        if (!allowedDlcRequest(url, bearer != null) ||
            bearer?.let { it.isBlank() || it.length > 16_384 || it.any { char -> char !in '!'..'~' } } == true
        ) throw DlcTransportException()
        val request = Request.Builder().url(url).apply {
            if (bearer != null) {
                header("Authorization", "Bearer $bearer")
                header("Cache-Control", "no-store")
            }
        }.get().build()
        val call = (if (bearer == null) publicClient else accountClient).newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(DlcTransportException()))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    val result = try {
                        response.use {
                            if (!it.isSuccessful || it.request.url != url) throw DlcTransportException()
                            val body = it.body
                            if (body.contentLength() > MAX_RESPONSE_BYTES) throw DlcTransportException()
                            val source = body.source()
                            if (source.request(MAX_RESPONSE_BYTES + 1)) throw DlcTransportException()
                            Result.success(Json.parseToJsonElement(source.readUtf8()))
                        }
                    } catch (_: Exception) {
                        Result.failure(DlcTransportException())
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
    }
}

internal fun allowedDlcRequest(url: HttpUrl, authenticated: Boolean): Boolean {
    if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() ||
        url.password.isNotEmpty() || url.fragment != null
    ) return false
    val keys = url.queryParameterNames
    if (keys.any { url.queryParameterValues(it).size != 1 }) return false
    return when (url.host) {
        "api.gog.com" -> !authenticated && url.encodedPath.matches(Regex("/products/[1-9][0-9]{0,19}")) &&
            keys == setOf("expand") && url.queryParameter("expand") == "expanded_dlcs"
        "embed.gog.com" -> authenticated && url.encodedPath == "/user/data/games" && keys.isEmpty()
        "store.steampowered.com" -> !authenticated && url.encodedPath == "/api/appdetails" &&
            keys == setOf("appids", "l", "cc") &&
            url.queryParameter("appids")?.toIntOrNull()?.let { it > 0 && it.toString() == url.queryParameter("appids") } == true &&
            url.queryParameter("l") == "english" && url.queryParameter("cc") == "US"
        "catalog-public-service-prod06.ol.epicgames.com" -> authenticated &&
            url.encodedPath.matches(Regex("/catalog/api/shared/namespace/[A-Za-z0-9_-]{1,128}/bulk/items")) &&
            keys == setOf("id", "includeDLCDetails", "includeMainGameDetails", "country", "locale") &&
            url.queryParameter("id")?.isEpicCatalogId() == true &&
            url.queryParameter("includeDLCDetails") == "true" && url.queryParameter("includeMainGameDetails") == "true" &&
            url.queryParameter("country") == "US" && url.queryParameter("locale") == "en-US"
        "entitlement-public-service-prod08.ol.epicgames.com" -> authenticated &&
            url.encodedPath.matches(Regex("/entitlement/api/account/[a-fA-F0-9]{32}/entitlements")) &&
            keys == setOf("start", "count") && url.queryParameter("count") == "1000" &&
            url.queryParameter("start")?.let { raw -> raw.toIntOrNull()?.let { it in 0..50_000 && it.toString() == raw } } == true
        else -> false
    }
}

internal fun String.isEpicCatalogId(): Boolean = matches(Regex("[A-Za-z0-9_-]{1,128}"))
