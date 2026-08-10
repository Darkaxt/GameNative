package app.gamenative.library.metadata

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class SteamMediaRedirectInterceptor(
    private val urlPolicy: MediaUrlPolicy = SteamUrlPolicy(),
    private val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
) : Interceptor {
    init {
        require(maxRedirects >= 0) { "Redirect bound must not be negative" }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val visited = mutableSetOf(request.url)
        var redirectCount = 0

        while (true) {
            if (!urlPolicy.isAllowedMediaUrl(request.url)) throw SteamMediaException()
            val response = chain.proceed(request)
            if (response.code !in REDIRECT_CODES) return response

            val nextUrl = response.header("Location")?.toHttpUrlOrNull()
            response.close()
            if (
                nextUrl == null ||
                !urlPolicy.isAllowedMediaUrl(nextUrl) ||
                redirectCount >= maxRedirects ||
                !visited.add(nextUrl)
            ) {
                throw SteamMediaException()
            }
            redirectCount += 1
            request = request.newBuilder().url(nextUrl).get().build()
        }
    }

    private companion object {
        const val DEFAULT_MAX_REDIRECTS = 3
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
