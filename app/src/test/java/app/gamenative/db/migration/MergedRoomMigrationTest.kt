package app.gamenative.db.migration

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.gamenative.db.PluviaDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class MergedRoomMigrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun shared17PreservesEveryExistingTableAndRowThroughRegisteredChain() = migrateFixture("common-17")

    @Test
    fun shared18PreservesEveryExistingTableAndRowThroughRegisteredChain() = migrateFixture("common-18")

    @Test
    fun shared19PreservesEveryExistingTableAndRowThroughRegisteredChain() = migrateFixture("common-19")

    @Test
    fun shared20PreservesEveryExistingTableAndRowThroughRegisteredChain() = migrateFixture("common-20")

    @Test
    fun shared21PreservesEveryExistingTableAndRowThroughRegisteredChain() = migrateFixture("common-21")

    @Test
    fun shared22PreservesEveryExistingTableAndRowThroughRegisteredChain() = migrateFixture("common-22")

    @Test
    fun shared23PreservesEveryExistingTableAndRowThroughRegisteredChain() = migrateFixture("common-23")

    @Test
    fun shared24PreservesEveryExistingTableAndRowThroughRegisteredChain() = migrateFixture("common-24")

    @Test
    fun shared25PreservesEveryExistingTableAndRowThroughRegisteredChain() = migrateFixture("common-25")

    @Test
    fun publishedFork26PreservesEveryExistingTableAndRow() = migrateFixture("fork-26")

    @Test
    fun publishedFork27PreservesEveryExistingTableAndRow() = migrateFixture("fork-27")

    @Test
    fun official26PreservesEveryExistingTableAndRow() = migrateFixture("official-26")

    @Test
    fun official27PreservesEveryExistingTableAndRow() = migrateFixture("official-27")

    @Test
    fun official28PreservesEveryExistingTableAndRow() = migrateFixture("official-28")

    @Test
    fun laterFork26LedgerPreservesRowsThroughDirectCombinedUpgrade() = migrateFixture("fork-26", laterV26Ledger = true)

    @Test
    fun emptyRecipeTableRetainsDeletedIdHighWatermark() = migrateFixture("fork-27", emptyRecipeTable = true)

    @Test
    fun emptyV24ModTablesRetainDeletedIdHighWatermarks() =
        migrateFixture("common-24", emptyRecipeTable = true, emptyOverwriteManifest = true)

    private fun migrateFixture(
        name: String,
        emptyRecipeTable: Boolean = false,
        laterV26Ledger: Boolean = false,
        emptyOverwriteManifest: Boolean = false,
    ) {
        val resource = "db/upstream-merge-2026-10-03/$name.json"
        val fixture = JSONObject(requireNotNull(javaClass.classLoader!!.getResourceAsStream(resource)) {
            "Missing historical schema fixture: $resource"
        }.bufferedReader().use { it.readText() }).getJSONObject("database")
        val version = fixture.getInt("version")
        // Keep real database fixtures outside long Robolectric worktree paths on Windows.
        val databaseDirectory = temporaryFolder.newFolder("databases")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getDatabasePath(name: String): File = File(databaseDirectory, name)
        }
        val databaseName = "merge-regression-$name.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = fixture.getJSONArray("entities")
                        for (index in 0 until entities.length()) {
                            val entity = entities.getJSONObject(index)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.optJSONArray("indices")
                            if (indices != null) {
                                for (indexIndex in 0 until indices.length()) {
                                    db.execSQL(indices.getJSONObject(indexIndex).getString("createSql").replace("\${TABLE_NAME}", table))
                                }
                            }
                        }
                        val setupQueries = fixture.getJSONArray("setupQueries")
                        for (index in 0 until setupQueries.length()) db.execSQL(setupQueries.getString(index))
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        error("Fixture creation must not upgrade a database")
                    }
                })
                .build(),
        )
        try {
            val oldDatabase = helper.writableDatabase
            val entities = fixture.getJSONArray("entities")
            val before = linkedMapOf<String, Map<String, String?>>()
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                val fields = entity.getJSONArray("fields")
                val values = ContentValues()
                for (fieldIndex in 0 until fields.length()) {
                    val field = fields.getJSONObject(fieldIndex)
                    val column = field.getString("columnName")
                    when (field.getString("affinity")) {
                        "INTEGER" -> values.put(column, 7L)
                        "REAL" -> values.put(column, 7.0)
                        "BLOB" -> values.put(column, byteArrayOf(7))
                        else -> values.put(column, "retained")
                    }
                }
                val columns = values.keySet().sorted()
                oldDatabase.execSQL(
                    "INSERT INTO `$table` (${columns.joinToString(",") { "`$it`" }}) " +
                        "VALUES (${columns.joinToString(",") { "?" }})",
                    columns.map { values.get(it) }.toTypedArray(),
                )
                before[table] = oldDatabase.singleRow(table)
            }
            if (laterV26Ledger) {
                oldDatabase.execSQL(
                    "CREATE TABLE owned_copy_sync (account_scope TEXT NOT NULL, source TEXT NOT NULL, " +
                        "completed_at INTEGER NOT NULL, PRIMARY KEY(account_scope, source))",
                )
                oldDatabase.execSQL(
                    "CREATE TABLE owned_copy_presence (account_scope TEXT NOT NULL, source TEXT NOT NULL, " +
                        "stable_source_id TEXT NOT NULL, resolved_source_id TEXT, " +
                        "PRIMARY KEY(account_scope, source, stable_source_id), " +
                        "FOREIGN KEY(account_scope, source) REFERENCES owned_copy_sync(account_scope, source) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                oldDatabase.execSQL("CREATE INDEX index_owned_copy_presence_account_scope_source ON owned_copy_presence(account_scope, source)")
                val account = "a".repeat(64)
                oldDatabase.execSQL("INSERT INTO owned_copy_sync VALUES (?, 'GOG', 17)", arrayOf(account))
                oldDatabase.execSQL("INSERT INTO owned_copy_presence VALUES (?, 'GOG', 'owned', 'resolved')", arrayOf(account))
                before["owned_copy_sync"] = oldDatabase.singleRow("owned_copy_sync")
                before["owned_copy_presence"] = oldDatabase.singleRow("owned_copy_presence")
            }
            val hadRecipeTable = oldDatabase.hasTable("mod_placement_recipe")
            if (hadRecipeTable) {
                oldDatabase.execSQL("UPDATE sqlite_sequence SET seq = 99 WHERE name = 'mod_placement_recipe'")
                oldDatabase.execSQL("UPDATE sqlite_sequence SET seq = 199 WHERE name = 'mod_overwrite_manifest'")
            }
            if (emptyOverwriteManifest) {
                oldDatabase.execSQL("DELETE FROM mod_overwrite_manifest")
                before.remove("mod_overwrite_manifest")
            }
            if (emptyRecipeTable) {
                oldDatabase.execSQL("DELETE FROM mod_placement_recipe")
                before.remove("mod_placement_recipe")
            }
            helper.close()

            val upgraded = Room.databaseBuilder(context, PluviaDatabase::class.java, databaseName)
                .configurePluviaDatabaseMigrations()
                .allowMainThreadQueries()
                .build()
            try {
                val migrations = androidx.room.RoomDatabase.MigrationContainer().apply {
                    addMigrations(*PLUVIA_EXPLICIT_MIGRATIONS.toTypedArray())
                    addMigrations(*upgraded.createAutoMigrations(emptyMap()).toTypedArray())
                }
                assertNotNull(
                    "A registered preservation path from $version to 29 is required",
                    migrations.findMigrationPath(version, 29),
                )
                // Opening invokes the registered migration path and Room's full target-schema validation.
                val database = upgraded.openHelper.writableDatabase
                assertEquals(29, database.version)
                before.forEach { (table, expected) ->
                    val actual = database.singleRow(table)
                    expected.forEach { (column, value) ->
                        assertEquals("$name: $table.$column must be preserved", value, actual[column])
                    }
                }
                assertEquals("0", database.columnDefault("gog_games", "hidden"))
                assertEquals("0", database.columnDefault("steam_app", "is_vr_only"))
                assertEquals("0", database.columnDefault("steam_app", "is_vr_supported"))
                assertEquals("-1", database.columnDefault("owned_copy_sync", "lifecycle_generation"))
                if (laterV26Ledger) {
                    assertEquals("-1", database.singleRow("owned_copy_sync")["lifecycle_generation"])
                }
                assertTrue(database.hasTable("canonical_game"))
                assertTrue(database.hasTable("owned_copy_presence"))
                if (hadRecipeTable) {
                    database.query("SELECT seq FROM sqlite_sequence WHERE name = 'mod_overwrite_manifest'").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("Deleted manifest IDs must not be reused after upgrade", 199L, cursor.getLong(0))
                    }
                    database.query("SELECT seq FROM sqlite_sequence WHERE name = 'mod_placement_recipe'").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("Deleted recipe IDs must not be reused after upgrade", 99L, cursor.getLong(0))
                    }
                } else {
                    assertTrue(database.hasTable("mod_placement_recipe"))
                }
                if (emptyRecipeTable) database.query("SELECT COUNT(*) FROM mod_placement_recipe").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                if (emptyOverwriteManifest) database.query("SELECT COUNT(*) FROM mod_overwrite_manifest").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                database.query("PRAGMA foreign_key_check").use { cursor ->
                    assertEquals("All preserved rows must retain valid foreign keys", false, cursor.moveToFirst())
                }
            } finally {
                upgraded.close()
            }
        } finally {
            helper.close()
        }
    }

    private fun SupportSQLiteDatabase.singleRow(table: String): Map<String, String?> =
        query("SELECT * FROM `$table`").use { cursor ->
            assertTrue("$table must retain its row", cursor.moveToFirst())
            val row = cursor.columnNames.mapIndexed { index, name ->
                name to when {
                    cursor.isNull(index) -> null
                    cursor.getType(index) == android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).joinToString(",")
                    else -> cursor.getString(index)
                }
            }.toMap()
            assertEquals("$table must retain exactly one row", false, cursor.moveToNext())
            row
        }

    private fun SupportSQLiteDatabase.columnDefault(table: String, column: String): String? =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == column) {
                    return@use cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                }
            }
            null
        }

    private fun SupportSQLiteDatabase.hasTable(table: String): Boolean =
        query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { it.moveToFirst() }
}
