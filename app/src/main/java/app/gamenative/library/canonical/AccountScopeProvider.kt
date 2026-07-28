package app.gamenative.library.canonical

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.service.amazon.AmazonAuthManager
import app.gamenative.service.epic.EpicAuthManager
import app.gamenative.service.gog.GOGAuthManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface AccountScopeProvider {
    suspend fun current(source: GameSource): AccountScope?
}

@Singleton
class DefaultAccountScopeProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : AccountScopeProvider {
    override suspend fun current(source: GameSource): AccountScope? = withContext(Dispatchers.IO) {
        val accountKey = when (source) {
            GameSource.STEAM -> PrefManager.steamUserSteamId64
                .takeIf { it != 0L }
                ?.toString()
            GameSource.GOG -> GOGAuthManager.getStoredUserId(context)
            GameSource.EPIC -> EpicAuthManager.getStoredAccountId(context)
            GameSource.AMAZON -> AmazonAuthManager.getOrCreateProfileScopeId(context)
            GameSource.CUSTOM_GAME -> CUSTOM_ACCOUNT_NAMESPACE
        } ?: return@withContext null

        val domainSeparatedKey = "$SCOPE_DOMAIN$SCOPE_SEPARATOR${source.name}$SCOPE_SEPARATOR$accountKey"
        AccountScope.parse(sha256(domainSeparatedKey))
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private companion object {
        const val SCOPE_DOMAIN = "gamenative-owned-copy-scope-v1"
        const val SCOPE_SEPARATOR = Char.MIN_VALUE
        const val CUSTOM_ACCOUNT_NAMESPACE = "local-custom-games-v1"
    }
}
