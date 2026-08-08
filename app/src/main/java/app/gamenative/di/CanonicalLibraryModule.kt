package app.gamenative.di

import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.CanonicalIdGenerator
import app.gamenative.library.canonical.AccountLifecycleState
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.CanonicalDiagnosticSink
import app.gamenative.library.canonical.CanonicalDiagnostics
import app.gamenative.library.canonical.CanonicalEventRecorder
import app.gamenative.library.canonical.CanonicalGameResolver
import app.gamenative.library.canonical.CanonicalLibraryDiagnosticSink
import app.gamenative.library.canonical.CanonicalMutationRepository
import app.gamenative.library.canonical.CanonicalProjectionClock
import app.gamenative.library.canonical.CanonicalProjectionEngine
import app.gamenative.library.canonical.CanonicalProjectionGate
import app.gamenative.library.canonical.CanonicalProjectionRunner
import app.gamenative.library.canonical.CanonicalPublicLibraryGate
import app.gamenative.library.canonical.CanonicalResolver
import app.gamenative.library.canonical.DefaultAccountScopeProvider
import app.gamenative.library.canonical.FeatureCanonicalEventRecorder
import app.gamenative.library.canonical.FeatureCanonicalLibraryDiagnostics
import app.gamenative.library.canonical.PrefManagerCanonicalProjectionGate
import app.gamenative.library.canonical.PrefManagerCanonicalPublicLibraryGate
import app.gamenative.library.canonical.RoomCanonicalMutationRepository
import app.gamenative.library.canonical.SharedPreferencesAccountLifecycleState
import app.gamenative.library.canonical.SteamCatalogDecisionWriter
import app.gamenative.library.canonical.SystemCanonicalProjectionClock
import app.gamenative.library.canonical.TrustedSteamMappingProvider
import app.gamenative.library.canonical.catalog.FeatureSteamCatalogResolutionDiagnostics
import app.gamenative.library.canonical.catalog.SteamAcceptedIdentityEnricher
import app.gamenative.library.canonical.catalog.SteamAcceptedIdentityEnrichmentSink
import app.gamenative.library.canonical.catalog.SteamCatalogResolutionDiagnosticSink
import app.gamenative.library.canonical.catalog.SteamCatalogSearchProvider
import app.gamenative.library.canonical.catalog.SteamCatalogSearchSource
import app.gamenative.library.canonical.catalog.SteamPublicPicsFacetSource
import app.gamenative.library.canonical.catalog.SteamSessionPublicPicsFacetSource
import app.gamenative.library.canonical.runtime.AmazonOwnedCopyRuntimeAdapter
import app.gamenative.library.canonical.runtime.CanonicalIoDispatcher
import app.gamenative.library.canonical.runtime.CustomOwnedCopyRuntimeAdapter
import app.gamenative.library.canonical.runtime.EpicOwnedCopyRuntimeAdapter
import app.gamenative.library.canonical.runtime.GogOwnedCopyRuntimeAdapter
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeAdapter
import app.gamenative.library.canonical.runtime.SteamOwnedCopyRuntimeAdapter
import app.gamenative.library.canonical.source.AmazonOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.CustomOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.EpicOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.GogOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.OwnedCopySourceAdapter
import app.gamenative.library.canonical.source.SteamOwnedCopySourceAdapter
import app.gamenative.library.discovery.GameFacetRepository
import app.gamenative.library.discovery.RoomGameFacetRepository
import app.gamenative.library.discovery.SteamReviewSummaryProvider
import app.gamenative.library.discovery.SteamReviewSummarySource
import app.gamenative.library.metadata.GameMetadataRepository
import app.gamenative.library.metadata.MetadataClock
import app.gamenative.library.metadata.MetadataLocaleProvider
import app.gamenative.library.metadata.RoomGameMetadataRepository
import app.gamenative.library.metadata.SteamCatalogDataSource
import app.gamenative.library.metadata.SteamCatalogProvider
import app.gamenative.library.metadata.SteamCatalogRecordSource
import app.gamenative.library.metadata.SystemMetadataClock
import app.gamenative.library.metadata.SystemMetadataLocaleProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
abstract class CanonicalLibraryModule {
    @Binds
    @Singleton
    abstract fun bindAccountScopeProvider(implementation: DefaultAccountScopeProvider): AccountScopeProvider

    @Binds
    @Singleton
    abstract fun bindAccountLifecycleState(
        implementation: SharedPreferencesAccountLifecycleState,
    ): AccountLifecycleState

    @Binds
    @Singleton
    abstract fun bindCanonicalResolver(implementation: CanonicalGameResolver): CanonicalResolver

    @Binds
    @Singleton
    abstract fun bindProjectionRunner(implementation: CanonicalProjectionEngine): CanonicalProjectionRunner

    @Binds
    @Singleton
    abstract fun bindMutationRepository(
        implementation: RoomCanonicalMutationRepository,
    ): CanonicalMutationRepository

    @Binds
    @Singleton
    abstract fun bindCanonicalDiagnostics(implementation: CanonicalDiagnostics): CanonicalDiagnosticSink

    @Binds
    @Singleton
    abstract fun bindCanonicalLibraryDiagnostics(
        implementation: FeatureCanonicalLibraryDiagnostics,
    ): CanonicalLibraryDiagnosticSink

    @Binds
    @Singleton
    abstract fun bindCanonicalEventRecorder(
        implementation: FeatureCanonicalEventRecorder,
    ): CanonicalEventRecorder

    @Binds
    @Singleton
    abstract fun bindProjectionGate(
        implementation: PrefManagerCanonicalProjectionGate,
    ): CanonicalProjectionGate

    @Binds
    @Singleton
    abstract fun bindPublicLibraryGate(
        implementation: PrefManagerCanonicalPublicLibraryGate,
    ): CanonicalPublicLibraryGate

    @Binds
    @Singleton
    abstract fun bindSteamCatalogDataSource(
        implementation: SteamCatalogProvider,
    ): SteamCatalogDataSource

    @Binds
    @Singleton
    abstract fun bindSteamCatalogSearchSource(
        implementation: SteamCatalogSearchProvider,
    ): SteamCatalogSearchSource

    @Binds
    @Singleton
    abstract fun bindSteamCatalogRecordSource(
        implementation: SteamCatalogProvider,
    ): SteamCatalogRecordSource

    @Binds
    @Singleton
    abstract fun bindSteamAcceptedIdentityEnrichment(
        implementation: SteamAcceptedIdentityEnricher,
    ): SteamAcceptedIdentityEnrichmentSink

    @Binds
    @Singleton
    abstract fun bindSteamPublicPicsFacetSource(
        implementation: SteamSessionPublicPicsFacetSource,
    ): SteamPublicPicsFacetSource

    @Binds
    @Singleton
    abstract fun bindSteamCatalogResolutionDiagnostics(
        implementation: FeatureSteamCatalogResolutionDiagnostics,
    ): SteamCatalogResolutionDiagnosticSink

    @Binds
    @Singleton
    abstract fun bindSteamReviewSummarySource(
        implementation: SteamReviewSummaryProvider,
    ): SteamReviewSummarySource

    @Binds
    @Singleton
    abstract fun bindGameFacetRepository(
        implementation: RoomGameFacetRepository,
    ): GameFacetRepository

    @Binds
    @Singleton
    abstract fun bindGameMetadataRepository(
        implementation: RoomGameMetadataRepository,
    ): GameMetadataRepository

    @Binds
    @Singleton
    abstract fun bindMetadataLocaleProvider(
        implementation: SystemMetadataLocaleProvider,
    ): MetadataLocaleProvider

    @Binds
    @Singleton
    abstract fun bindMetadataClock(implementation: SystemMetadataClock): MetadataClock

    @Binds
    @Singleton
    abstract fun bindProjectionClock(
        implementation: SystemCanonicalProjectionClock,
    ): CanonicalProjectionClock

    @Binds
    @IntoSet
    abstract fun bindSteamAdapter(implementation: SteamOwnedCopySourceAdapter): OwnedCopySourceAdapter

    @Binds
    @IntoSet
    abstract fun bindGogAdapter(implementation: GogOwnedCopySourceAdapter): OwnedCopySourceAdapter

    @Binds
    @IntoSet
    abstract fun bindEpicAdapter(implementation: EpicOwnedCopySourceAdapter): OwnedCopySourceAdapter

    @Binds
    @IntoSet
    abstract fun bindAmazonAdapter(implementation: AmazonOwnedCopySourceAdapter): OwnedCopySourceAdapter

    @Binds
    @IntoSet
    abstract fun bindCustomAdapter(implementation: CustomOwnedCopySourceAdapter): OwnedCopySourceAdapter

    @Binds
    @IntoSet
    abstract fun bindSteamRuntimeAdapter(
        implementation: SteamOwnedCopyRuntimeAdapter,
    ): OwnedCopyRuntimeAdapter

    @Binds
    @IntoSet
    abstract fun bindGogRuntimeAdapter(
        implementation: GogOwnedCopyRuntimeAdapter,
    ): OwnedCopyRuntimeAdapter

    @Binds
    @IntoSet
    abstract fun bindEpicRuntimeAdapter(
        implementation: EpicOwnedCopyRuntimeAdapter,
    ): OwnedCopyRuntimeAdapter

    @Binds
    @IntoSet
    abstract fun bindAmazonRuntimeAdapter(
        implementation: AmazonOwnedCopyRuntimeAdapter,
    ): OwnedCopyRuntimeAdapter

    @Binds
    @IntoSet
    abstract fun bindCustomRuntimeAdapter(
        implementation: CustomOwnedCopyRuntimeAdapter,
    ): OwnedCopyRuntimeAdapter

    @Multibinds
    abstract fun trustedSteamMappingProviders(): Set<TrustedSteamMappingProvider>

    companion object {
        @Provides
        @Singleton
        fun provideSteamCatalogDecisionWriter(
            repository: CanonicalMutationRepository,
        ): SteamCatalogDecisionWriter = repository

        @Provides
        @Singleton
        @CanonicalIoDispatcher
        fun provideCanonicalIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

        @Provides
        @Singleton
        fun provideCanonicalIdGenerator(): CanonicalIdGenerator = CanonicalIdGenerator {
            CanonicalGameId.random()
        }
    }
}
