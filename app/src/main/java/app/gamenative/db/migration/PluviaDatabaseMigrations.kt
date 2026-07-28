package app.gamenative.db.migration

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.SupportSQLiteConnection
import androidx.sqlite.execSQL
import app.gamenative.db.PluviaDatabase
import app.gamenative.diagnostics.DiagnosticArea
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEventName
import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.diagnostics.FeatureDiagnostics
import timber.log.Timber

private const val TARGET_DATABASE_VERSION = "26"
private const val V25_TO_V26_MIGRATION = "25_to_26"
private const val DESTRUCTIVE_RECOVERY_MIGRATION = "7_to_16_to_26"
private const val DESTRUCTIVE_RECOVERY_REASON = "destructive_recovery"
private const val V25_TO_V26_PENDING_SUCCESS_ID = -26
private const val V25_TO_V26_PENDING_SUCCESS_HASH = "pluvia_pending_25_to_26"
private const val MIGRATION_DIAGNOSTICS_MARKER_TABLE = "pluvia_migration_diagnostics"
private const val DESTRUCTIVE_RECOVERY_PENDING_SUCCESS = "destructive_recovery_7_to_16_to_26"

internal val PLUVIA_EXPLICIT_MIGRATIONS: List<Migration> = listOf(
    ROOM_MIGRATION_V23_to_V24,
    ROOM_MIGRATION_V24_to_V25,
    ROOM_MIGRATION_V25_to_V26,
)

internal val UNSUPPORTED_PRESERVATION_VERSIONS = intArrayOf(7, 8, 9, 10, 11, 12, 13, 14, 15, 16)

internal fun RoomDatabase.Builder<PluviaDatabase>.configurePluviaDatabaseMigrations(): RoomDatabase.Builder<PluviaDatabase> =
    addMigrations(*PLUVIA_EXPLICIT_MIGRATIONS.toTypedArray())
        .fallbackToDestructiveMigrationFrom(
            true,
            *UNSUPPORTED_PRESERVATION_VERSIONS,
        )
        .addCallback(PLUVIA_MIGRATION_DIAGNOSTICS_CALLBACK)

private val PLUVIA_MIGRATION_DIAGNOSTICS_CALLBACK = object : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        completePendingMigrationSuccesses(SupportSQLiteConnection(db))
    }

    override fun onOpen(connection: SQLiteConnection) {
        completePendingMigrationSuccesses(connection)
    }

    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
        DestructiveRecoveryMigrationDiagnostics.recordStartedAndMarkPending(
            SupportSQLiteConnection(db),
        )
    }

    override fun onDestructiveMigration(connection: SQLiteConnection) {
        DestructiveRecoveryMigrationDiagnostics.recordStartedAndMarkPending(connection)
    }
}

private fun completePendingMigrationSuccesses(connection: SQLiteConnection) {
    V25ToV26MigrationDiagnostics.completePendingSuccess(connection)
    DestructiveRecoveryMigrationDiagnostics.completePendingSuccess(connection)
}

internal fun acknowledgeAndCleanupPendingMigrationSuccess(
    acknowledge: () -> Boolean,
    cleanup: () -> Unit,
    logCleanupFailure: (String) -> Unit = { errorType ->
        Timber.tag("MigrationDiagnostics").w(
            "Unable to clean up acknowledged migration diagnostic marker; errorType=%s",
            errorType,
        )
    },
) {
    if (!acknowledge()) return
    try {
        cleanup()
    } catch (error: Exception) {
        logCleanupFailure(error.javaClass.simpleName.ifEmpty { "Exception" })
    }
}

private object DestructiveRecoveryMigrationDiagnostics {
    fun recordStartedAndMarkPending(connection: SQLiteConnection) {
        recordDatabaseMigration(
            outcome = DiagnosticOutcome.STARTED,
            migration = DESTRUCTIVE_RECOVERY_MIGRATION,
            reason = DESTRUCTIVE_RECOVERY_REASON,
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `$MIGRATION_DIAGNOSTICS_MARKER_TABLE` (
                `marker` TEXT NOT NULL,
                PRIMARY KEY(`marker`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT OR REPLACE INTO `$MIGRATION_DIAGNOSTICS_MARKER_TABLE` (`marker`)
            VALUES ('$DESTRUCTIVE_RECOVERY_PENDING_SUCCESS')
            """.trimIndent(),
        )
    }

    fun completePendingSuccess(connection: SQLiteConnection) {
        val hasMarkerTable = connection.hasResult(
            """
            SELECT 1 FROM `sqlite_master`
            WHERE `type` = 'table' AND `name` = '$MIGRATION_DIAGNOSTICS_MARKER_TABLE'
            LIMIT 1
            """.trimIndent(),
        )
        if (!hasMarkerTable) return

        val isPending = connection.hasResult(
            """
            SELECT 1 FROM `$MIGRATION_DIAGNOSTICS_MARKER_TABLE`
            WHERE `marker` = '$DESTRUCTIVE_RECOVERY_PENDING_SUCCESS'
            LIMIT 1
            """.trimIndent(),
        )
        if (!isPending) return

        acknowledgeAndCleanupPendingMigrationSuccess(
            acknowledge = {
                acknowledgeDatabaseMigrationSuccess(
                    migration = DESTRUCTIVE_RECOVERY_MIGRATION,
                    reason = DESTRUCTIVE_RECOVERY_REASON,
                )
            },
            cleanup = {
                connection.execSQL("DROP TABLE `$MIGRATION_DIAGNOSTICS_MARKER_TABLE`")
            },
        )
    }
}

internal object V25ToV26MigrationDiagnostics {
    fun recordStarted() {
        recordDatabaseMigration(DiagnosticOutcome.STARTED, migration = V25_TO_V26_MIGRATION)
    }

    fun recordBodyFailed(errorType: String) {
        recordDatabaseMigration(
            outcome = DiagnosticOutcome.FAILED,
            migration = V25_TO_V26_MIGRATION,
            errorType = errorType,
        )
    }

    fun markPendingSuccess(connection: SQLiteConnection) {
        connection.execSQL(
            """
            INSERT OR REPLACE INTO `room_master_table` (`id`, `identity_hash`)
            VALUES ($V25_TO_V26_PENDING_SUCCESS_ID, '$V25_TO_V26_PENDING_SUCCESS_HASH')
            """.trimIndent(),
        )
    }

    fun completePendingSuccess(connection: SQLiteConnection) {
        val isPending = connection.hasResult(
            """
            SELECT 1 FROM `room_master_table`
            WHERE `id` = $V25_TO_V26_PENDING_SUCCESS_ID
                AND `identity_hash` = '$V25_TO_V26_PENDING_SUCCESS_HASH'
            LIMIT 1
            """.trimIndent(),
        )
        if (!isPending) return

        acknowledgeAndCleanupPendingMigrationSuccess(
            acknowledge = {
                acknowledgeDatabaseMigrationSuccess(migration = V25_TO_V26_MIGRATION)
            },
            cleanup = {
                connection.execSQL(
                    "DELETE FROM `room_master_table` WHERE `id` = $V25_TO_V26_PENDING_SUCCESS_ID",
                )
            },
        )
    }
}

private fun recordDatabaseMigration(
    outcome: DiagnosticOutcome,
    migration: String,
    reason: String? = null,
    errorType: String? = null,
) {
    FeatureDiagnostics.record(
        area = DiagnosticArea.DATABASE,
        name = DiagnosticEventName.DATABASE_MIGRATION,
        outcome = outcome,
        attributes = databaseMigrationAttributes(migration, reason, errorType),
    )
}

private fun acknowledgeDatabaseMigrationSuccess(
    migration: String,
    reason: String? = null,
): Boolean = FeatureDiagnostics.recordAcknowledged(
    area = DiagnosticArea.DATABASE,
    name = DiagnosticEventName.DATABASE_MIGRATION,
    outcome = DiagnosticOutcome.SUCCEEDED,
    attributes = databaseMigrationAttributes(migration, reason),
)

private fun databaseMigrationAttributes(
    migration: String,
    reason: String? = null,
    errorType: String? = null,
): Map<DiagnosticAttribute, String> = buildMap {
    put(DiagnosticAttribute.MIGRATION, migration)
    put(DiagnosticAttribute.DB_VERSION, TARGET_DATABASE_VERSION)
    reason?.let { put(DiagnosticAttribute.REASON, it) }
    errorType?.let { put(DiagnosticAttribute.ERROR_TYPE, it) }
}

private fun SQLiteConnection.hasResult(sql: String): Boolean =
    prepare(sql).use { statement -> statement.step() }
