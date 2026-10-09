package app.gamenative.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

class FamilyPreferenceMigration : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(SQL)
    }

    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(SQL)
    }

    private companion object {
        const val SQL = "ALTER TABLE `canonical_game_preference` ADD COLUMN `family_grouping_suppressed` INTEGER NOT NULL DEFAULT 0"
    }
}
