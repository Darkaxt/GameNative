package app.gamenative.di

import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.DefaultAccountScopeProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CanonicalLibraryModule {
    @Binds
    @Singleton
    abstract fun bindAccountScopeProvider(implementation: DefaultAccountScopeProvider): AccountScopeProvider
}
