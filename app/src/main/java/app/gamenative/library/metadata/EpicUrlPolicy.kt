package app.gamenative.library.metadata

import okhttp3.HttpUrl

class EpicUrlPolicy internal constructor(
    private val cmsHosts: Set<String> = setOf(EPIC_CMS_HOST),
    private val mediaRoots: Set<String> = EPIC_MEDIA_ROOTS,
    private val requireHttps: Boolean = true,
    private val allowedPorts: Set<Int> = setOf(443),
) {
    fun isAllowedCmsRequest(url: HttpUrl): Boolean {
        if (
            !hasSafeAuthority(url) ||
            url.host !in cmsHosts ||
            url.querySize != 0 ||
            url.fragment != null
        ) {
            return false
        }

        val segments = url.pathSegments
        return segments.size == 5 &&
            segments[0] == "api" &&
            LOCALE.matches(segments[1]) &&
            segments[2] == "content" &&
            segments[3] == "products" &&
            PRODUCT_SLUG.matches(segments[4])
    }

    fun isAllowedMediaUrl(url: HttpUrl): Boolean {
        if (!hasSafeAuthority(url) || url.fragment != null || !url.encodedPath.startsWith('/')) {
            return false
        }
        return mediaRoots.any { root -> url.host == root || url.host.endsWith(".$root") }
    }

    private fun hasSafeAuthority(url: HttpUrl): Boolean =
        (!requireHttps || url.isHttps) &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.port in allowedPorts

    companion object {
        const val EPIC_CMS_HOST = "store-content.ak.epicgames.com"
        val EPIC_MEDIA_ROOTS = setOf("epicgames.com", "unrealengine.com")
        internal val PRODUCT_SLUG = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        private val LOCALE = Regex("[a-z]{2}(?:-[A-Z]{2})?")
    }
}
