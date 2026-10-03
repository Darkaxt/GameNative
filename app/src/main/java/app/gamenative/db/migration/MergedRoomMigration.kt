package app.gamenative.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

// Official and fork versions 26/27 have different published shapes. Inspect columns rather
// than treating a version number as proof that either history's additions are present.
internal class MergedRoomMigration(startVersion: Int) : Migration(startVersion, 29) {
    private val diagnostics = PendingRoomMigrationDiagnostics(
        migration = "${startVersion}_to_29",
        pendingSuccessId = -2900 - startVersion,
        pendingSuccessHash = "pluvia_pending_${startVersion}_to_29",
    )

    override fun migrate(connection: SQLiteConnection) {
        diagnostics.recordStarted()
        try {
            connection.addMissingColumn("steam_app", "genre_ids", "TEXT NOT NULL DEFAULT '[]'")
            connection.addMissingColumn("steam_app", "category_ids", "TEXT NOT NULL DEFAULT '[]'")
            connection.addMissingColumn("steam_app", "store_tag_ids", "TEXT NOT NULL DEFAULT '[]'")
            connection.addMissingColumn("steam_app", "primary_genre_id", "INTEGER NOT NULL DEFAULT 0")
            connection.addMissingColumn("steam_app", "pics_parse_version", "INTEGER NOT NULL DEFAULT 0")
            connection.addMissingColumn("steam_app", "is_vr_only", "INTEGER NOT NULL DEFAULT 0")
            connection.addMissingColumn("steam_app", "is_vr_supported", "INTEGER NOT NULL DEFAULT 0")
            connection.addMissingColumn("gog_games", "hidden", "INTEGER NOT NULL DEFAULT 0")
            createCanonicalCoreStorageV26(connection)
            createCanonicalFacetStorageV26(connection)
            createOwnedCopyLedgerStorageV26(connection)
            // Old completeness timestamps cannot authorize a new account lifecycle.
            connection.addMissingColumn("owned_copy_sync", "lifecycle_generation", "INTEGER NOT NULL DEFAULT -1")
            if (!connection.hasColumn("mod_placement_recipe", "target_file_name")) {
                addModTargetFileName(connection)
            }
            diagnostics.markPendingSuccess(connection)
        } catch (error: Exception) {
            diagnostics.recordBodyFailed(error.javaClass.simpleName)
            throw error
        }
    }

    fun completePendingSuccess(connection: SQLiteConnection) = diagnostics.completePendingSuccess(connection)
}

internal val MERGED_ROOM_MIGRATIONS = listOf(26, 27, 28).map(::MergedRoomMigration)

private fun SQLiteConnection.hasColumn(table: String, column: String): Boolean =
    prepare("PRAGMA table_info(`$table`)").use { statement ->
        while (statement.step()) {
            if (statement.getText(1) == column) return@use true
        }
        false
    }

private fun SQLiteConnection.addMissingColumn(table: String, column: String, definition: String) {
    if (!hasColumn(table, column)) execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
}

private fun addModTargetFileName(connection: SQLiteConnection) {
    val sequence = connection.prepare("SELECT seq FROM sqlite_sequence WHERE name = 'mod_placement_recipe'").use { statement ->
        if (statement.step()) statement.getLong(0) else 0L
    }
    // Rebuild this leaf table so its new non-null column has the entity's exact no-default shape.
    connection.execSQL(
        """
        CREATE TABLE `mod_placement_recipe_v29` (
            `recipe_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `install_id` TEXT NOT NULL,
            `source_subpath` TEXT NOT NULL,
            `target_root` TEXT NOT NULL,
            `target_relative_path` TEXT NOT NULL,
            `target_file_name` TEXT NOT NULL,
            `mode` TEXT NOT NULL,
            `strip_prefix_segments` INTEGER NOT NULL,
            `include_source_directory` INTEGER NOT NULL,
            `enabled` INTEGER NOT NULL,
            FOREIGN KEY(`install_id`) REFERENCES `mod_install`(`install_id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO `mod_placement_recipe_v29` (
            `recipe_id`, `install_id`, `source_subpath`, `target_root`, `target_relative_path`,
            `target_file_name`, `mode`, `strip_prefix_segments`, `include_source_directory`, `enabled`
        )
        SELECT `recipe_id`, `install_id`, `source_subpath`, `target_root`, `target_relative_path`,
            '', `mode`, `strip_prefix_segments`, `include_source_directory`, `enabled`
        FROM `mod_placement_recipe`
        """.trimIndent(),
    )
    connection.execSQL("DROP TABLE `mod_placement_recipe`")
    connection.execSQL("ALTER TABLE `mod_placement_recipe_v29` RENAME TO `mod_placement_recipe`")
    connection.execSQL("UPDATE sqlite_sequence SET seq = MAX(seq, $sequence) WHERE name = 'mod_placement_recipe'")
    connection.execSQL("CREATE INDEX `index_mod_placement_recipe_install_id` ON `mod_placement_recipe` (`install_id`)")
}
