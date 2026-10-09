package app.gamenative.db.migration

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.gamenative.db.PluviaDatabase
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.junit.rules.TimeoutRule

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class FamilyPreferenceMigrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    @get:Rule val timeout = TimeoutRule.seconds(30)
    private val canonicalId = "00000000-0000-0000-0000-000000000001"

    @Test
    fun additiveFamilyPreferenceMigrationIsRegisteredForPublishedSchema31() {
        val migration = migration()
        assertEquals(31, migration.startVersion)
        assertEquals(32, migration.endVersion)
        val paths = androidx.room.RoomDatabase.MigrationContainer().apply {
            addMigrations(*PLUVIA_EXPLICIT_MIGRATIONS.toTypedArray())
        }
        assertNotNull("Published schema31 needs a registered preserving upgrade", paths.findMigrationPath(31, 32))
    }

    @Test
    fun migrationChangesOnlyOneDefaultFalsePreferenceColumnAndPreservesEveryExistingValue() {
        val migration = migration()
        val fixture = fixture()
        val helper = oldDatabase(fixture.first, fixture.second)
        try {
            val database = helper.writableDatabase
            val before = tableDefinitions(database)
            val row = preferenceRow(database)
            migration.migrate(database)
            val after = tableDefinitions(database)
            assertEquals(before.keys, after.keys)
            before.filterKeys { it != "canonical_game_preference" }.forEach { (name, definition) ->
                assertEquals("Unrelated historical table/index changed: $name", definition, after[name])
            }
            assertEquals(row, preferenceRow(database).filterKeys { it != "family_grouping_suppressed" })
            assertEquals("0", preferenceRow(database)["family_grouping_suppressed"])
            database.query("PRAGMA table_info(canonical_game_preference)").use { cursor ->
                val added = linkedMapOf<String, Pair<String, String?>>()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    if (name !in row) added[name] = cursor.getString(cursor.getColumnIndexOrThrow("type")) to
                        cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                }
                assertEquals(mapOf("family_grouping_suppressed" to ("INTEGER" to "0")), added)
            }
        } finally { helper.close() }
    }

    @Test
    fun actualRegisteredRoomUpgradeReopensSchema32WithoutClearingPreferencesOrCatalog() {
        migration()
        val fixture = fixture()
        val helper = oldDatabase(fixture.first, fixture.second)
        helper.writableDatabase
        helper.close()
        val upgraded = Room.databaseBuilder(fixture.first, PluviaDatabase::class.java, fixture.second)
            .configurePluviaDatabaseMigrations().allowMainThreadQueries().build()
        try {
            val database = upgraded.openHelper.writableDatabase
            assertEquals(32, database.version)
            assertEquals("Preserved title", preferenceRow(database)["title_override"])
            assertEquals("synthetic artwork", preferenceRow(database)["artwork_override_json"])
            assertEquals("42", preferenceRow(database)["preferred_stable_source_id"])
            assertEquals("0", preferenceRow(database)["family_grouping_suppressed"])
            database.query("SELECT steam_app_id FROM canonical_game WHERE canonical_id = ?", arrayOf(canonicalId)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(42, cursor.getInt(0))
            }
            database.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(false, cursor.moveToFirst()) }
        } finally { upgraded.close() }
    }

    private fun migration(): Migration {
        val type = runCatching { Class.forName("app.gamenative.db.migration.FamilyPreferenceMigration") }.getOrNull()
        assertTrue("Missing additive schema31-to32 family preference migration", type != null)
        return type!!.getConstructor().newInstance() as Migration
    }

    private fun fixture(): Pair<Context, String> {
        val directory = temporaryFolder.newFolder("databases")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getDatabasePath(name: String): File = File(directory, name)
        }
        return context to "family-preference.db"
    }

    private fun oldDatabase(context: Context, name: String): SupportSQLiteOpenHelper {
        val schema = JSONObject(requireNotNull(javaClass.classLoader!!.getResourceAsStream("db/family-preference-31.json"))
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        assertEquals(31, schema.getInt("version"))
        return FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(31) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (index in 0 until entities.length()) {
                        val entity = entities.getJSONObject(index)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices")
                        if (indices != null) for (i in 0 until indices.length()) {
                            db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                        }
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
                    db.execSQL("INSERT INTO canonical_game VALUES (?,42,'Fixture Game','fixture game','GOG','GAME',NULL,'','UNCLASSIFIED',NULL,1,1)", arrayOf(canonicalId))
                    db.execSQL("INSERT INTO canonical_game_preference VALUES (?,?,'GOG','42','Preserved title','synthetic artwork',99)",
                        arrayOf(canonicalId, "2".repeat(64)))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    error("Historical fixture creation must not migrate")
            }).build())
    }

    private fun preferenceRow(database: SupportSQLiteDatabase): Map<String, String?> =
        database.query("SELECT * FROM canonical_game_preference").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.columnNames.associateWith { name -> cursor.getString(cursor.getColumnIndexOrThrow(name)) }
        }

    private fun tableDefinitions(database: SupportSQLiteDatabase): Map<String, String?> =
        database.query("SELECT name,sql FROM sqlite_master WHERE type IN ('table','index') ORDER BY name").use { cursor ->
            val result = linkedMapOf<String, String?>()
            while (cursor.moveToNext()) result[cursor.getString(0)] = cursor.getString(1)
            result
        }
}
