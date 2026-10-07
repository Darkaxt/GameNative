package app.gamenative.db.migration

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.gamenative.db.PluviaDatabase
import app.gamenative.diagnostics.DiagnosticArea
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEventName
import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.diagnostics.FeatureDiagnostics
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class DestructiveRecoveryMigrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        mockkObject(FeatureDiagnostics)
        every { FeatureDiagnostics.record(any(), any(), any(), any(), any()) } just Runs
        every { FeatureDiagnostics.recordAcknowledged(any(), any(), any(), any(), any()) } returns true
    }

    @After
    fun tearDown() = unmockkObject(FeatureDiagnostics)

    @Test
    fun unsupportedRecoveryReportsActualTarget31AndCleansAcknowledgedMarker() {
        openFixture(
            prepare = { it.version = 7 },
            check = { database ->
                assertEquals(31, database.version)
                assertFalse(database.hasDiagnosticMarker())
                verify(exactly = 1) {
                    FeatureDiagnostics.record(
                        DiagnosticArea.DATABASE,
                        DiagnosticEventName.DATABASE_MIGRATION,
                        DiagnosticOutcome.STARTED,
                        any(),
                        match { it[DiagnosticAttribute.MIGRATION] == "7_to_16_to_31" && it[DiagnosticAttribute.DB_VERSION] == "31" },
                    )
                }
                verifyAcknowledged("7_to_16_to_31")
            },
        )
    }

    @Test
    fun previouslyPending27RecoveryIsAcknowledgedWithItsOriginalIdentity() {
        openFixture(
            prepare = { database ->
                database.execSQL("CREATE TABLE pluvia_migration_diagnostics (marker TEXT NOT NULL PRIMARY KEY)")
                database.execSQL("INSERT INTO pluvia_migration_diagnostics VALUES ('destructive_recovery_7_to_16_to_27')")
            },
            check = { database ->
                assertFalse(database.hasDiagnosticMarker())
                verifyAcknowledged("7_to_16_to_27")
            },
        )
    }

    @Test
    fun previouslyPending29RecoveryIsAcknowledgedWithItsOriginalIdentity() {
        openFixture(
            prepare = { database ->
                database.execSQL("CREATE TABLE pluvia_migration_diagnostics (marker TEXT NOT NULL PRIMARY KEY)")
                database.execSQL("INSERT INTO pluvia_migration_diagnostics VALUES ('destructive_recovery_7_to_16_to_29')")
            },
            check = { database ->
                assertFalse(database.hasDiagnosticMarker())
                verifyAcknowledged("7_to_16_to_29")
            },
        )
    }

    @Test
    fun unacknowledgedCurrentRecoveryMarkerSurvivesUntilLaterOpen() {
        every { FeatureDiagnostics.recordAcknowledged(any(), any(), any(), any(), any()) } returns false
        openFixture(
            prepare = { it.version = 7 },
            check = { database ->
                assertTrue(database.hasDiagnosticMarker())
                database.query("SELECT marker FROM pluvia_migration_diagnostics").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("destructive_recovery_7_to_16_to_31", cursor.getString(0))
                }
            },
        )
    }

    @Test
    fun acknowledgingLegacyMarkerDoesNotDiscardUnacknowledgedCurrentMarker() {
        every { FeatureDiagnostics.recordAcknowledged(any(), any(), any(), any(), any()) } answers {
            val attributes = arg<Map<DiagnosticAttribute, String>>(4)
            attributes[DiagnosticAttribute.MIGRATION] in setOf("7_to_16_to_27", "7_to_16_to_29", "7_to_16_to_30")
        }
        openFixture(
            prepare = { database ->
                database.execSQL("CREATE TABLE pluvia_migration_diagnostics (marker TEXT NOT NULL PRIMARY KEY)")
                database.execSQL("INSERT INTO pluvia_migration_diagnostics VALUES ('destructive_recovery_7_to_16_to_27')")
                database.execSQL("INSERT INTO pluvia_migration_diagnostics VALUES ('destructive_recovery_7_to_16_to_29')")
                database.execSQL("INSERT INTO pluvia_migration_diagnostics VALUES ('destructive_recovery_7_to_16_to_30')")
                database.execSQL("INSERT INTO pluvia_migration_diagnostics VALUES ('destructive_recovery_7_to_16_to_31')")
            },
            check = { database ->
                assertTrue(database.hasDiagnosticMarker())
                database.query("SELECT marker FROM pluvia_migration_diagnostics").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("destructive_recovery_7_to_16_to_31", cursor.getString(0))
                    assertFalse(cursor.moveToNext())
                }
                verifyAcknowledged("7_to_16_to_27")
                verifyAcknowledged("7_to_16_to_29")
                verifyAcknowledged("7_to_16_to_30")
            },
        )
    }

    private fun verifyAcknowledged(migration: String) {
        verify(exactly = 1) {
            FeatureDiagnostics.recordAcknowledged(
                DiagnosticArea.DATABASE,
                DiagnosticEventName.DATABASE_MIGRATION,
                DiagnosticOutcome.SUCCEEDED,
                any(),
                match { it[DiagnosticAttribute.MIGRATION] == migration && it[DiagnosticAttribute.REASON] == "destructive_recovery" },
            )
        }
    }

    private fun openFixture(prepare: (SupportSQLiteDatabase) -> Unit, check: (SupportSQLiteDatabase) -> Unit) {
        val directory = temporaryFolder.newFolder("databases")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getDatabasePath(name: String) = File(directory, name)
        }
        val name = "recovery.db"
        Room.databaseBuilder(context, PluviaDatabase::class.java, name).allowMainThreadQueries().build().let { original ->
            try {
                prepare(original.openHelper.writableDatabase)
            } finally {
                original.close()
            }
        }
        val upgraded = Room.databaseBuilder(context, PluviaDatabase::class.java, name)
            .configurePluviaDatabaseMigrations().allowMainThreadQueries().build()
        try {
            check(upgraded.openHelper.writableDatabase)
        } finally {
            upgraded.close()
        }
    }

    private fun SupportSQLiteDatabase.hasDiagnosticMarker(): Boolean =
        query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'pluvia_migration_diagnostics'").use { it.moveToFirst() }
}
