package app.gamenative.library.metadata

import okhttp3.HttpUrl

internal fun interface MediaUrlPolicy {
    fun isAllowedMediaUrl(url: HttpUrl): Boolean
}

internal fun MetadataProvider.mediaUrlPolicy(): MediaUrlPolicy = when (this) {
    MetadataProvider.STEAM_APPDETAILS -> SteamUrlPolicy()
    MetadataProvider.EPIC_CMS -> EpicUrlPolicy()
}
