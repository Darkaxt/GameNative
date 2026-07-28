package app.gamenative.di

import android.content.Context
import androidx.room.Room
import app.gamenative.db.DATABASE_NAME
import app.gamenative.db.PluviaDatabase
import app.gamenative.db.dao.AppInfoDao
import app.gamenative.db.dao.CachedLicenseDao
import app.gamenative.db.dao.DownloadingAppInfoDao
import app.gamenative.db.dao.EncryptedAppTicketDao
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.db.dao.ModDao
import app.gamenative.db.dao.SteamUnlockedBranchDao
import app.gamenative.db.migration.configurePluviaDatabaseMigrations
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
class DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PluviaDatabase {
        // Versions 17–25 have preservation migrations. Versions 7–16 use an explicitly
        // limited destructive recovery because the historical 16→17 schema gap cannot be
        // validated safely; that recovery resets every row in the database.
        return Room.databaseBuilder(context, PluviaDatabase::class.java, DATABASE_NAME)
            .configurePluviaDatabaseMigrations()
            .build()
    }

    @Provides
    @Singleton
    fun provideSteamLicenseDao(db: PluviaDatabase) = db.steamLicenseDao()

    @Provides
    @Singleton
    fun provideSteamAppDao(db: PluviaDatabase) = db.steamAppDao()

    @Provides
    @Singleton
    fun provideSteamFileHashCacheDao(db: PluviaDatabase) = db.steamFileHashCacheDao()

    @Provides
    @Singleton
    fun provideAppChangeNumbersDao(db: PluviaDatabase) = db.appChangeNumbersDao()

    @Provides
    @Singleton
    fun provideAppFileChangeListsDao(db: PluviaDatabase) = db.appFileChangeListsDao()

    @Provides
    @Singleton
    fun provideLibraryPlayHistoryDao(db: PluviaDatabase): LibraryPlayHistoryDao = db.libraryPlayHistoryDao()

    @Provides
    @Singleton
    fun provideAppInfoDao(db: PluviaDatabase): AppInfoDao = db.appInfoDao()

    @Provides
    @Singleton
    fun provideCachedLicenseDao(db: PluviaDatabase): CachedLicenseDao = db.cachedLicenseDao()

    @Provides
    @Singleton
    fun provideEncryptedAppTicketDao(db: PluviaDatabase): EncryptedAppTicketDao = db.encryptedAppTicketDao()

    @Provides
    @Singleton
    fun provideGOGGameDao(db: PluviaDatabase) = db.gogGameDao()

    @Provides
    @Singleton
    fun provideEpicGameDao(db: PluviaDatabase) = db.epicGameDao()

    @Provides
    @Singleton
    fun provideAmazonGameDao(db: PluviaDatabase) = db.amazonGameDao()

    @Provides
    @Singleton
    fun provideDownloadingAppInfoDao(db: PluviaDatabase): DownloadingAppInfoDao = db.downloadingAppInfoDao()

    @Provides
    @Singleton
    fun provideSteamUnlockedBranchDao(db: PluviaDatabase): SteamUnlockedBranchDao = db.steamUnlockedBranchDao()

    @Provides
    @Singleton
    fun provideModDao(db: PluviaDatabase): ModDao = db.modDao()

    @Provides
    @Singleton
    fun provideCanonicalGameDao(db: PluviaDatabase) = db.canonicalGameDao()

    @Provides
    @Singleton
    fun provideStoreMatchDao(db: PluviaDatabase) = db.storeMatchDao()

    @Provides
    @Singleton
    fun provideCanonicalPreferenceDao(db: PluviaDatabase) = db.canonicalPreferenceDao()

    @Provides
    @Singleton
    fun provideCanonicalFacetDao(db: PluviaDatabase) = db.canonicalFacetDao()

    @Provides
    @Singleton
    fun provideGameDetailSnapshotDao(db: PluviaDatabase) = db.gameDetailSnapshotDao()

    @Provides
    @Singleton
    fun provideOwnedCopyLedgerDao(db: PluviaDatabase) = db.ownedCopyLedgerDao()
}
