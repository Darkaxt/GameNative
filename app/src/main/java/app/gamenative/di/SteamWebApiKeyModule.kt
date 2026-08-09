package app.gamenative.di

import app.gamenative.service.steam.AndroidSteamWebApiKeyCipher
import app.gamenative.service.steam.DefaultSteamWebApiKeyRepository
import app.gamenative.service.steam.PrefManagerSteamWebApiKeyPersistence
import app.gamenative.service.steam.SteamWebApiKeyCipher
import app.gamenative.service.steam.SteamWebApiKeyPersistence
import app.gamenative.service.steam.SteamWebApiKeyRepository
import app.gamenative.service.steam.SteamWebApiKeySource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SteamWebApiKeyModule {
    @Binds
    @Singleton
    abstract fun bindSteamWebApiKeyRepository(
        implementation: DefaultSteamWebApiKeyRepository,
    ): SteamWebApiKeyRepository

    @Binds
    @Singleton
    abstract fun bindSteamWebApiKeySource(
        implementation: DefaultSteamWebApiKeyRepository,
    ): SteamWebApiKeySource

    @Binds
    @Singleton
    abstract fun bindSteamWebApiKeyPersistence(
        implementation: PrefManagerSteamWebApiKeyPersistence,
    ): SteamWebApiKeyPersistence

    @Binds
    @Singleton
    abstract fun bindSteamWebApiKeyCipher(
        implementation: AndroidSteamWebApiKeyCipher,
    ): SteamWebApiKeyCipher
}
