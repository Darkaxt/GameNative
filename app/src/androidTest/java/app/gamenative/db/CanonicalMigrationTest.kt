package app.gamenative.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.db.migration.PLUVIA_EXPLICIT_MIGRATIONS
import app.gamenative.db.migration.ROOM_MIGRATION_V25_to_V26
import app.gamenative.db.migration.UNSUPPORTED_PRESERVATION_VERSIONS
import app.gamenative.db.migration.configurePluviaDatabaseMigrations
import app.gamenative.diagnostics.DiagnosticArea
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEventName
import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.diagnostics.FeatureDiagnostics
import app.gamenative.enums.AppType
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val V25_TO_V26_PENDING_SUCCESS_ID = -26

@RunWith(AndroidJUnit4::class)
class CanonicalMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @get:Rule
    val migrationHelper = MigrationTestHelper(instrumentation, PluviaDatabase::class.java)

    @Test
    fun testBuilderSharesTheProductionExplicitMigrationConfiguration() {
        assertEquals(
            listOf(23 to 24, 24 to 25, 25 to 26),
            PLUVIA_EXPLICIT_MIGRATIONS.map { it.startVersion to it.endVersion },
        )
        assertArrayEquals((7..16).toList().toIntArray(), UNSUPPORTED_PRESERVATION_VERSIONS)
    }

    @Test
    fun emptySchemasFromEverySupportedVersionReach26() {
        (17..25).forEach { startVersion ->
            val name = "canonical-supported-$startVersion"
            migrationHelper.createDatabase(name, startVersion).close()

            val database = openAtCurrentVersion(name)
            try {
                assertEquals(26, database.openHelper.writableDatabase.version)
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun populatedOldestSupportedV17RowSurvivesToV26() {
        val name = "canonical-v17-preservation"
        migrationHelper.createDatabase(name, 17).use { database ->
            database.execSQL(
                "INSERT INTO `app_change_numbers` (`appId`, `changeNumber`) VALUES (1717, 2600)",
            )
        }

        val roomDatabase = openAtCurrentVersion(name)
        try {
            roomDatabase.openHelper.writableDatabase
                .query("SELECT `appId`, `changeNumber` FROM `app_change_numbers`")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1717, cursor.getInt(0))
                    assertEquals(2600, cursor.getInt(1))
                    assertFalse(cursor.moveToNext())
                }
        } finally {
            roomDatabase.close()
        }
    }

    @Test
    fun v25GogRowSurvivesAndCanonicalTablesStartEmpty() {
        val name = "canonical-v25-preservation"
        migrationHelper.createDatabase(name, 25).use { database ->
            database.execSQL(
                """
                INSERT INTO `gog_games` (
                    `id`, `title`, `slug`, `download_size`, `install_size`, `is_installed`,
                    `install_path`, `image_url`, `icon_url`, `background_url`, `vertical_cover_url`,
                    `description`, `release_date`, `developer`, `publisher`, `genres`, `languages`,
                    `last_played`, `play_time`, `type`, `exclude`
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "gog-preserved-id",
                    "Preserved GOG Game",
                    "",
                    0L,
                    0L,
                    0,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "[]",
                    "[]",
                    0L,
                    0L,
                    AppType.game.code,
                    0,
                ),
            )
        }

        val roomDatabase = openAtCurrentVersion(name)
        try {
            val database = roomDatabase.openHelper.writableDatabase
            database.query("SELECT `id`, `title` FROM `gog_games`").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("gog-preserved-id", cursor.getString(0))
                assertEquals("Preserved GOG Game", cursor.getString(1))
                assertFalse(cursor.moveToNext())
            }
            CANONICAL_TABLES.forEach { table ->
                assertEquals("$table should start empty", 0, database.rowCount(table))
            }
        } finally {
            roomDatabase.close()
        }
    }

    @Test
    fun v25SteamAppCanarySurvivesAndReadsThroughRoomWithV26Defaults() {
        val name = "canonical-v25-steam-preservation"
        migrationHelper.createDatabase(name, 25).use { database ->
            database.insertLegacySteamAppCanary()
        }

        val roomDatabase = openAtCurrentVersion(name)
        try {
            val app = runBlocking { roomDatabase.steamAppDao().findApp(620) }
            assertTrue(app != null)
            app!!
            assertEquals(620, app.id)
            assertEquals(77, app.packageId)
            assertEquals(listOf(1234, 5678), app.ownerAccountId)
            assertTrue(app.receivedPICS)
            assertEquals(9876, app.lastChangeNumber)
            assertEquals(4, app.ufsParseVersion)
            assertEquals("Legacy Steam Canary", app.name)
            assertEquals("Canary Developer", app.developer)
            assertTrue(app.primaryGenre)
            assertEquals(emptyList<Int>(), app.genreIds)
            assertEquals(emptyList<Int>(), app.categoryIds)
            assertEquals(emptyList<Int>(), app.storeTagIds)
            assertEquals(0, app.primaryGenreId)
            assertEquals(0, app.picsParseVersion)
        } finally {
            roomDatabase.close()
        }
    }

    @Test
    fun v25ToV26CreatesCanonicalSchemaAndSteamRevisionColumns() {
        val database = migrateV25("canonical-v26-schema")
        database.use {
            assertEquals(CANONICAL_TABLES, database.userTableNames())
            assertTrue(database.indexNames().containsAll(CANONICAL_INDEXES))

            val columns = database.tableColumns("steam_app")
            assertEquals("'[]'", columns.getValue("genre_ids"))
            assertEquals("'[]'", columns.getValue("category_ids"))
            assertEquals("'[]'", columns.getValue("store_tag_ids"))
            assertEquals("0", columns.getValue("primary_genre_id"))
            assertEquals("0", columns.getValue("pics_parse_version"))
            assertTrue(columns.containsKey("primary_genre"))
        }
    }

    @Test
    fun multipleNullSteamAppIdsAreAllowed() {
        migrateV25("canonical-null-steam-ids").use { database ->
            database.insertCanonical("canonical-one", null)
            database.insertCanonical("canonical-two", null)

            assertEquals(2, database.rowCount("canonical_game"))
        }
    }

    @Test
    fun duplicateNonNullSteamAppIdFails() {
        migrateV25("canonical-unique-steam-id").use { database ->
            database.insertCanonical("canonical-one", 620)
            assertFailsWith<SQLiteConstraintException> {
                database.insertCanonical("canonical-two", 620)
            }
        }
    }

    @Test
    fun canonicalGameCanOwnMultipleCopyKeys() {
        migrateV25("canonical-multiple-copy-keys").use { database ->
            database.insertCanonical("canonical-one", null)
            database.insertStoreMatch("canonical-one", stableSourceId = "copy-1")
            database.insertStoreMatch("canonical-one", stableSourceId = "copy-2")

            assertEquals(2, database.rowCount("store_match"))
        }
    }

    @Test
    fun sameSourceIdIsAllowedAcrossAccountScopes() {
        migrateV25("canonical-account-scoped-copy-keys").use { database ->
            database.insertCanonical("canonical-one", null)
            database.insertStoreMatch(
                canonicalId = "canonical-one",
                accountScope = "a".repeat(64),
                stableSourceId = "shared-copy",
            )
            database.insertStoreMatch(
                canonicalId = "canonical-one",
                accountScope = "b".repeat(64),
                stableSourceId = "shared-copy",
            )

            assertEquals(2, database.rowCount("store_match"))
        }
    }

    @Test
    fun duplicateOwnedCopyCompositeKeyFails() {
        migrateV25("canonical-owned-copy-key").use { database ->
            database.insertCanonical("canonical-one", null)
            database.insertStoreMatch("canonical-one")
            assertFailsWith<SQLiteConstraintException> {
                database.insertStoreMatch("canonical-one")
            }
        }
    }

    @Test
    fun deletingCanonicalGameCascadesToAllOwnedRows() {
        migrateV25("canonical-cascade").use { database ->
            database.setForeignKeyConstraintsEnabled(true)
            database.insertCanonical("canonical-one", 620)
            database.insertStoreMatch("canonical-one")
            database.execSQL(
                "INSERT INTO `canonical_game_preference` (`canonical_id`, `updated_at`) VALUES ('canonical-one', 1)",
            )
            database.execSQL(
                "INSERT INTO `canonical_game_genre` (`canonical_id`, `genre_key`) VALUES ('canonical-one', 'steam:1')",
            )
            database.execSQL(
                "INSERT INTO `canonical_game_tag` (`canonical_id`, `tag_id`) VALUES ('canonical-one', 19)",
            )
            database.execSQL(
                "INSERT INTO `canonical_game_feature` (`canonical_id`, `feature_key`) VALUES ('canonical-one', 'steam:2')",
            )
            database.execSQL(
                """
                INSERT INTO `game_detail_snapshot` (
                    `canonical_id`, `locale`, `country`, `payload_json`, `provenance_json`,
                    `fetched_at`, `source_revision`
                ) VALUES ('canonical-one', 'en', 'US', '{}', '{}', 1, 'test')
                """.trimIndent(),
            )

            database.execSQL("DELETE FROM `canonical_game` WHERE `canonical_id` = 'canonical-one'")

            CASCADE_TABLES.forEach { table ->
                assertEquals("$table should be deleted by cascade", 0, database.rowCount(table))
            }
        }
    }

    @Test
    fun v16UsesOnlyTheExplicitSevenThroughSixteenDestructiveRecoveryScope() {
        assertArrayEquals((7..16).toList().toIntArray(), UNSUPPORTED_PRESERVATION_VERSIONS)

        val name = "canonical-v16-recovery"
        migrationHelper.createDatabase(name, 16).use { database ->
            database.execSQL(
                "INSERT INTO `app_change_numbers` (`appId`, `changeNumber`) VALUES (4242, 99)",
            )
        }
        resetDiagnostics()

        val roomDatabase = openAtCurrentVersion(name)
        try {
            val database = roomDatabase.openHelper.writableDatabase
            assertEquals(26, database.version)
            assertEquals(0, database.rowCount("app_change_numbers"))
            CANONICAL_TABLES.forEach { table -> assertTrue(database.hasTable(table)) }
            assertFalse(database.hasTable(DESTRUCTIVE_DIAGNOSTICS_MARKER_TABLE))
        } finally {
            roomDatabase.close()
        }

        val events = databaseMigrationEvents()
        assertEquals(
            listOf(DiagnosticOutcome.STARTED, DiagnosticOutcome.SUCCEEDED),
            events.map { it.outcome },
        )
        events.forEach { event ->
            assertEquals(
                mapOf(
                    DiagnosticAttribute.MIGRATION.wireName to "7_to_16_to_26",
                    DiagnosticAttribute.DB_VERSION.wireName to "26",
                    DiagnosticAttribute.REASON.wireName to "destructive_recovery",
                ),
                event.attributes,
            )
        }
    }

    @Test
    fun destructiveRecoveryFailureBeforeRecreationNeverRecordsSuccess() {
        val name = "canonical-v16-recovery-failure"
        migrationHelper.createDatabase(name, 16).close()
        resetDiagnostics()

        val failAfterDiagnostics = object : androidx.room.RoomDatabase.Callback() {
            override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                throw IllegalStateException("forced destructive recreation failure")
            }

            override fun onDestructiveMigration(connection: SQLiteConnection) {
                throw IllegalStateException("forced destructive recreation failure")
            }
        }
        val database = Room.databaseBuilder(context, PluviaDatabase::class.java, name)
            .configurePluviaDatabaseMigrations()
            .addCallback(failAfterDiagnostics)
            .build()
        try {
            assertFailsWith<Exception> { database.openHelper.writableDatabase }
        } finally {
            database.close()
        }

        val outcomes = databaseMigrationEvents().map { it.outcome }
        assertTrue(outcomes.isNotEmpty())
        assertTrue(outcomes.all { it == DiagnosticOutcome.STARTED })

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { rolledBackDatabase ->
            assertEquals(16, rolledBackDatabase.version)
            assertFalse(rolledBackDatabase.hasTable(DESTRUCTIVE_DIAGNOSTICS_MARKER_TABLE))
        }
    }

    @Test
    fun destructiveRecoverySuccessMarkerRetriesUntilAppendIsAcknowledged() {
        val name = "canonical-v16-recovery-retry"
        migrationHelper.createDatabase(name, 16).close()
        resetDiagnostics()
        val appendBlocker = blockDiagnosticAppends()

        val firstOpen = openAtCurrentVersion(name)
        try {
            assertTrue(
                firstOpen.openHelper.writableDatabase.hasTable(DESTRUCTIVE_DIAGNOSTICS_MARKER_TABLE),
            )
        } finally {
            firstOpen.close()
            assertTrue(appendBlocker.delete())
        }
        assertTrue(databaseMigrationEvents().isEmpty())

        val retryOpen = openAtCurrentVersion(name)
        try {
            assertFalse(
                retryOpen.openHelper.writableDatabase.hasTable(DESTRUCTIVE_DIAGNOSTICS_MARKER_TABLE),
            )
        } finally {
            retryOpen.close()
        }
        assertEquals(listOf(DiagnosticOutcome.SUCCEEDED), databaseMigrationEvents().map { it.outcome })
    }

    @Test
    fun v25SuccessMarkerRetriesUntilAppendIsAcknowledged() {
        val name = "canonical-v25-diagnostics-retry"
        migrationHelper.createDatabase(name, 25).close()
        resetDiagnostics()
        val appendBlocker = blockDiagnosticAppends()

        val firstOpen = openAtCurrentVersion(name)
        try {
            assertTrue(firstOpen.openHelper.writableDatabase.hasV25ToV26PendingSuccess())
        } finally {
            firstOpen.close()
            assertTrue(appendBlocker.delete())
        }
        assertTrue(databaseMigrationEvents().isEmpty())

        val retryOpen = openAtCurrentVersion(name)
        try {
            assertFalse(retryOpen.openHelper.writableDatabase.hasV25ToV26PendingSuccess())
        } finally {
            retryOpen.close()
        }
        assertEquals(listOf(DiagnosticOutcome.SUCCEEDED), databaseMigrationEvents().map { it.outcome })
    }

    @Test
    fun successfulMigrationDiagnosticsContainOnlyVersionMetadata() {
        val name = "canonical-diagnostics-success"
        migrationHelper.createDatabase(name, 25).close()
        resetDiagnostics()

        openAtCurrentVersion(name).close()

        val events = databaseMigrationEvents()
        assertEquals(listOf(DiagnosticOutcome.STARTED, DiagnosticOutcome.SUCCEEDED), events.map { it.outcome })
        events.forEach { event ->
            assertEquals(
                mapOf(
                    DiagnosticAttribute.MIGRATION.wireName to "25_to_26",
                    DiagnosticAttribute.DB_VERSION.wireName to "26",
                ),
                event.attributes,
            )
        }
    }

    @Test
    fun postSqlSchemaValidationFailureNeverRecordsMigrationSuccess() {
        val name = "canonical-diagnostics-validation-failure"
        migrationHelper.createDatabase(name, 25).close()
        resetDiagnostics()

        val invalidAfterSqlMigration = object : Migration(25, 26) {
            override fun migrate(connection: SQLiteConnection) {
                ROOM_MIGRATION_V25_to_V26.migrate(connection)
                connection.execSQL("DROP INDEX `index_canonical_game_match_title_key`")
            }
        }
        val database = Room.databaseBuilder(context, PluviaDatabase::class.java, name)
            .configurePluviaDatabaseMigrations()
            .addMigrations(invalidAfterSqlMigration)
            .build()
        try {
            assertFailsWith<Exception> { database.openHelper.writableDatabase }
        } finally {
            database.close()
        }

        val outcomes = databaseMigrationEvents().map { it.outcome }
        assertTrue(outcomes.isNotEmpty())
        assertTrue(outcomes.all { it == DiagnosticOutcome.STARTED })
    }

    @Test
    fun failedMigrationRecordsOnlyExceptionClassAndRethrows() {
        val name = "canonical-diagnostics-failure"
        migrationHelper.createDatabase(name, 25).use { database ->
            database.execSQL(
                "ALTER TABLE `steam_app` ADD COLUMN `genre_ids` TEXT NOT NULL DEFAULT '[]'",
            )
        }
        resetDiagnostics()

        val thrown = assertFailsWith<Exception> {
            openAtCurrentVersion(name)
        }

        val events = databaseMigrationEvents()
        assertTrue(events.isNotEmpty())
        assertEquals(0, events.size % 2)
        events.chunked(2).forEach { attempt ->
            assertEquals(listOf(DiagnosticOutcome.STARTED, DiagnosticOutcome.FAILED), attempt.map { it.outcome })
        }
        events.filter { it.outcome == DiagnosticOutcome.STARTED }.forEach { event ->
            assertEquals(
                mapOf(
                    DiagnosticAttribute.MIGRATION.wireName to "25_to_26",
                    DiagnosticAttribute.DB_VERSION.wireName to "26",
                ),
                event.attributes,
            )
        }
        events.filter { it.outcome == DiagnosticOutcome.FAILED }.forEach { event ->
            assertEquals(
                mapOf(
                    DiagnosticAttribute.MIGRATION.wireName to "25_to_26",
                    DiagnosticAttribute.DB_VERSION.wireName to "26",
                    DiagnosticAttribute.ERROR_TYPE.wireName to thrown.causeChainMigrationErrorType(),
                ),
                event.attributes,
            )
        }
    }

    private fun databaseMigrationEvents() = FeatureDiagnostics.recent().filter {
        it.area == DiagnosticArea.DATABASE && it.name == DiagnosticEventName.DATABASE_MIGRATION
    }

    private fun blockDiagnosticAppends(): File {
        assertTrue(FeatureDiagnostics.clear())
        val blocker = File(context.filesDir, "diagnostics/feature-events.0.jsonl")
        assertFalse(blocker.exists())
        assertTrue(blocker.mkdir())
        return blocker
    }

    private fun migrateV25(name: String): SupportSQLiteDatabase {
        migrationHelper.createDatabase(name, 25).close()
        return migrationHelper.runMigrationsAndValidate(
            name,
            26,
            true,
            *PLUVIA_EXPLICIT_MIGRATIONS.toTypedArray(),
        )
    }

    private fun openAtCurrentVersion(name: String): PluviaDatabase {
        val database = Room.databaseBuilder(context, PluviaDatabase::class.java, name)
            .configurePluviaDatabaseMigrations()
            .build()
        return try {
            database.also { it.openHelper.writableDatabase }
        } catch (error: Exception) {
            database.close()
            throw error
        }
    }

    private fun resetDiagnostics() {
        FeatureDiagnostics.initialize(context)
        assertTrue(FeatureDiagnostics.clear())
    }

    private fun Throwable.causeChainMigrationErrorType(): String =
        generateSequence(this) { it.cause }
            .map { it.javaClass.simpleName }
            .first { it == "SQLiteException" || it == "SQLiteConstraintException" }

    companion object {
        private const val DESTRUCTIVE_DIAGNOSTICS_MARKER_TABLE = "pluvia_migration_diagnostics"

        val CANONICAL_TABLES = setOf(
            "canonical_game",
            "store_match",
            "canonical_game_preference",
            "canonical_game_genre",
            "canonical_game_tag",
            "canonical_game_feature",
            "steam_tag_dictionary",
            "game_detail_snapshot",
        )

        val CANONICAL_INDEXES = setOf(
            "index_canonical_game_steam_app_id",
            "index_canonical_game_match_title_key",
            "index_store_match_canonical_id",
            "index_store_match_candidate_steam_app_id",
            "index_store_match_source_stable_source_id",
            "index_canonical_game_genre_genre_key_canonical_id",
            "index_canonical_game_tag_tag_id_canonical_id",
            "index_canonical_game_feature_feature_key_canonical_id",
            "index_game_detail_snapshot_canonical_id",
        )

        val CASCADE_TABLES = setOf(
            "store_match",
            "canonical_game_preference",
            "canonical_game_genre",
            "canonical_game_tag",
            "canonical_game_feature",
            "game_detail_snapshot",
        )
    }
}

private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) return error
        throw AssertionError("Expected ${T::class.java.simpleName}, but got ${error.javaClass.simpleName}", error)
    }
    throw AssertionError("Expected ${T::class.java.simpleName} to be thrown")
}

private fun SupportSQLiteDatabase.userTableNames(): Set<String> {
    val canonicalTables = CanonicalMigrationTest.CANONICAL_TABLES
    return query("SELECT `name` FROM `sqlite_master` WHERE `type` = 'table'").use { cursor ->
        buildSet {
            while (cursor.moveToNext()) {
                val tableName = cursor.getString(0)
                if (tableName in canonicalTables) add(tableName)
            }
        }
    }
}

private fun SupportSQLiteDatabase.indexNames(): Set<String> =
    query("SELECT `name` FROM `sqlite_master` WHERE `type` = 'index'").use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

private fun SupportSQLiteDatabase.tableColumns(table: String): Map<String, String?> =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
        buildMap {
            while (cursor.moveToNext()) {
                put(cursor.getString(nameIndex), if (cursor.isNull(defaultIndex)) null else cursor.getString(defaultIndex))
            }
        }
    }

private fun SupportSQLiteDatabase.rowCount(table: String): Int =
    query("SELECT COUNT(*) FROM `$table`").use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getInt(0)
    }

private fun SupportSQLiteDatabase.hasTable(table: String): Boolean =
    query("SELECT 1 FROM `sqlite_master` WHERE `type` = 'table' AND `name` = ?", arrayOf(table)).use { cursor ->
        cursor.moveToFirst()
    }

private fun SupportSQLiteDatabase.hasV25ToV26PendingSuccess(): Boolean =
    query(
        "SELECT 1 FROM `room_master_table` WHERE `id` = ? LIMIT 1",
        arrayOf(V25_TO_V26_PENDING_SUCCESS_ID),
    ).use { cursor ->
        cursor.moveToFirst()
    }

private fun SQLiteDatabase.hasTable(table: String): Boolean =
    rawQuery(
        "SELECT 1 FROM `sqlite_master` WHERE `type` = 'table' AND `name` = ?",
        arrayOf(table),
    ).use { cursor ->
        cursor.moveToFirst()
    }

private fun SupportSQLiteDatabase.insertLegacySteamAppCanary() {
    val values = ContentValues().apply {
        put("id", 620)
        put("package_id", 77)
        put("owner_account_id", "[1234,5678]")
        put("license_flags", 0)
        put("received_pics", 1)
        put("last_change_number", 9876)
        put("ufs_parse_version", 4)
        put("depots", "{}")
        put("branches", "{}")
        put("name", "Legacy Steam Canary")
        put("type", AppType.game.code)
        put("os_list", 1)
        put("release_state", 1)
        put("release_date", 1_700_000_000L)
        put("metacritic_score", 87)
        put("metacritic_full_url", "")
        put("logo_hash", "legacy-logo")
        put("logo_small_hash", "legacy-small-logo")
        put("icon_hash", "legacy-icon")
        put("client_icon_hash", "legacy-client-icon")
        put("client_tga_hash", "legacy-client-tga")
        put("small_capsule", "{}")
        put("header_image", "{}")
        put("library_assets", "{}")
        put("primary_genre", 1)
        put("review_score", 8)
        put("review_percentage", 91)
        put("controller_support", 2)
        put("demo_of_app_id", 0)
        put("developer", "Canary Developer")
        put("publisher", "Canary Publisher")
        put("homepage_url", "")
        put("game_manual_url", "")
        put("load_all_before_launch", 0)
        put("dlc_app_ids", "[621]")
        put("is_free_app", 0)
        put("dlc_for_app_id", 0)
        put("must_own_app_to_purchase", 0)
        put("dlc_available_on_store", 0)
        put("optional_dlc", 0)
        put("game_dir", "legacy-game-dir")
        put("install_script", "")
        put("no_servers", 0)
        put("`order`", 0)
        put("primary_cache", 0)
        put("valid_os_list", 1)
        put("third_party_cd_key", 0)
        put("visible_only_when_installed", 0)
        put("visible_only_when_subscribed", 0)
        put("launch_eula_url", "")
        put("require_default_install_folder", 0)
        put("content_type", 0)
        put("install_dir", "legacy-install-dir")
        put("use_launch_cmd_line", 0)
        put("launch_without_workshop_updates", 0)
        put("use_mms", 0)
        put("install_script_signature", "")
        put("install_script_override", 0)
        put("config", "{}")
        put("ufs", "{}")
        put("workshop_mods", 1)
        put("enabled_workshop_item_ids", "10,20")
        put("workshop_download_pending", 1)
    }
    assertEquals(620L, insert("steam_app", SQLiteDatabase.CONFLICT_NONE, values))
}

private fun SupportSQLiteDatabase.insertCanonical(canonicalId: String, steamAppId: Int?) {
    execSQL(
        """
        INSERT INTO `canonical_game` (
            `canonical_id`, `steam_app_id`, `display_name`, `match_title_key`,
            `primary_metadata_source`, `app_type`, `release_year`, `developer_key`,
            `classification_state`, `steam_review_count`, `created_at`, `updated_at`
        ) VALUES (?, ?, 'Test', 'test', 'STEAM', 'GAME', 2024, 'developer', 'UNCLASSIFIED', 1, 1, 1)
        """.trimIndent(),
        arrayOf<Any?>(canonicalId, steamAppId),
    )
}

private fun SupportSQLiteDatabase.insertStoreMatch(
    canonicalId: String,
    accountScope: String = "a".repeat(64),
    source: String = "GOG",
    stableSourceId: String = "copy-1",
) {
    execSQL(
        """
        INSERT INTO `store_match` (
            `account_scope`, `source`, `stable_source_id`, `canonical_id`,
            `candidate_steam_app_id`, `match_method`, `confidence`, `decision_source`,
            `resolver_version`, `matched_at`, `is_present`, `evidence_display_name`,
            `evidence_title_key`, `evidence_developer_key`, `evidence_release_year`,
            `evidence_app_type`
        ) VALUES (
            ?, ?, ?, ?, NULL, 'UNMATCHED', 'UNMATCHED', 'AUTOMATIC',
            1, 1, 1, 'Test', 'test', 'developer', 2024, 'GAME'
        )
        """.trimIndent(),
        arrayOf(accountScope, source, stableSourceId, canonicalId),
    )
}
