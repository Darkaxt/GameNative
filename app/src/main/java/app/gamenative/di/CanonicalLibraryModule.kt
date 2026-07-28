package app.gamenative.di

import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.CanonicalIdGenerator
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.CanonicalGameResolver
import app.gamenative.library.canonical.CanonicalResolver
import app.gamenative.library.canonical.DefaultAccountScopeProvider
import app.gamenative.library.canonical.TrustedSteamMappingProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CanonicalLibraryModule {
    @Binds
    @Singleton
    abstract fun bindAccountScopeProvider(implementation: DefaultAccountScopeProvider): AccountScopeProvider

    @Binds
    @Singleton
    abstract fun bindCanonicalResolver(implementation: CanonicalGameResolver): CanonicalResolver

    @Multibinds
    abstract fun trustedSteamMappingProviders(): Set<TrustedSteamMappingProvider>

    companion object {
        @Provides
        @Singleton
        fun provideCanonicalIdGenerator(): CanonicalIdGenerator = CanonicalIdGenerator {
            CanonicalGameId.random()
        }
    }
}
