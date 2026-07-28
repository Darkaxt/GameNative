package app.gamenative.db.migration

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.SupportSQLiteConnection
import androidx.test.core.app.ApplicationProvider
import app.gamenative.db.PluviaDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class RoomMigrationTest {
    private lateinit var roomDatabase: PluviaDatabase
    private lateinit var database: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        roomDatabase = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database = roomDatabase.openHelper.writableDatabase
        database.execSQL("DROP TABLE `owned_copy_presence`")
        database.execSQL("DROP TABLE `owned_copy_sync`")
    }

    @After
    fun tearDown() {
        roomDatabase.close()
    }

    @Test
    fun originalPublishedV26WithoutLedgerMigratesToV27() {
        assertFalse(database.hasTable("owned_copy_sync"))
        assertFalse(database.hasTable("owned_copy_presence"))

        ROOM_MIGRATION_V26_to_V27.migrate(SupportSQLiteConnection(database))

        assertTrue(database.hasTable("owned_copy_sync"))
        assertTrue(database.hasTable("owned_copy_presence"))
        assertEquals("-1", database.columnDefault("owned_copy_sync", "lifecycle_generation"))
    }

    @Test
    fun laterV26LedgerRowsMigrateFailClosedWithPresenceRetained() {
        database.createV26OwnedCopyLedger()
        val accountScope = "a".repeat(64)
        database.execSQL(
            "INSERT INTO `owned_copy_sync` (`account_scope`, `source`, `completed_at`) " +
                "VALUES (?, 'GOG', 1)",
            arrayOf(accountScope),
        )
        database.execSQL(
            "INSERT INTO `owned_copy_presence` " +
                "(`account_scope`, `source`, `stable_source_id`) VALUES (?, 'GOG', 'owned')",
            arrayOf(accountScope),
        )

        ROOM_MIGRATION_V26_to_V27.migrate(SupportSQLiteConnection(database))

        database.query(
            "SELECT `lifecycle_generation` FROM `owned_copy_sync` " +
                "WHERE `account_scope` = ? AND `source` = 'GOG'",
            arrayOf(accountScope),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(-1L, cursor.getLong(0))
            assertFalse(cursor.moveToNext())
        }
        assertEquals(1, database.rowCount("owned_copy_presence"))
    }
}

private fun SupportSQLiteDatabase.createV26OwnedCopyLedger() {
    execSQL(
        """
        CREATE TABLE `owned_copy_sync` (
            `account_scope` TEXT NOT NULL,
            `source` TEXT NOT NULL,
            `completed_at` INTEGER NOT NULL,
            PRIMARY KEY(`account_scope`, `source`)
        )
        """.trimIndent(),
    )
    execSQL(
        """
        CREATE TABLE `owned_copy_presence` (
            `account_scope` TEXT NOT NULL,
            `source` TEXT NOT NULL,
            `stable_source_id` TEXT NOT NULL,
            `resolved_source_id` TEXT,
            PRIMARY KEY(`account_scope`, `source`, `stable_source_id`),
            FOREIGN KEY(`account_scope`, `source`) REFERENCES `owned_copy_sync`(`account_scope`, `source`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    execSQL(
        "CREATE INDEX `index_owned_copy_presence_account_scope_source` " +
            "ON `owned_copy_presence` (`account_scope`, `source`)",
    )
}

private fun SupportSQLiteDatabase.hasTable(table: String): Boolean =
    query(
        "SELECT 1 FROM `sqlite_master` WHERE `type` = 'table' AND `name` = ?",
        arrayOf(table),
    ).use { cursor ->
        cursor.moveToFirst()
    }

private fun SupportSQLiteDatabase.columnDefault(table: String, column: String): String? =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) {
                return@use if (cursor.isNull(defaultIndex)) null else cursor.getString(defaultIndex)
            }
        }
        null
    }

private fun SupportSQLiteDatabase.rowCount(table: String): Int =
    query("SELECT COUNT(*) FROM `$table`").use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getInt(0)
    }
