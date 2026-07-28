package app.gamenative.diagnostics

import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FeatureDiagnosticsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var originalStore: DiagnosticLogStore? = null

    @Before
    fun resetStore() {
        originalStore = featureDiagnosticsStore()
        setFeatureDiagnosticsStore(null)
    }

    @After
    fun restoreStore() {
        setFeatureDiagnosticsStore(originalStore)
    }

    @Test
    fun `acknowledged record returns false while diagnostics are uninitialized`() {
        assertFalse(recordAcknowledged())
    }

    @Test
    fun `acknowledged record returns true only after append succeeds`() {
        val directory = temporaryFolder.newFolder("diagnostics")
        setFeatureDiagnosticsStore(
            DiagnosticLogStore(
                directory = directory,
                json = Json { ignoreUnknownKeys = true },
            ),
        )

        assertTrue(recordAcknowledged())

        assertTrue(directory.deleteRecursively())
        assertTrue(directory.createNewFile())
        assertFalse(recordAcknowledged())
    }

    private fun recordAcknowledged(): Boolean = FeatureDiagnostics.recordAcknowledged(
        area = DiagnosticArea.DATABASE,
        name = DiagnosticEventName.DATABASE_MIGRATION,
        outcome = DiagnosticOutcome.SUCCEEDED,
        attributes = mapOf(DiagnosticAttribute.MIGRATION to "test"),
    )

    private fun featureDiagnosticsStore(): DiagnosticLogStore? = storeField.get(null) as DiagnosticLogStore?

    private fun setFeatureDiagnosticsStore(store: DiagnosticLogStore?) {
        storeField.set(null, store)
    }

    private companion object {
        val storeField = FeatureDiagnostics::class.java.getDeclaredField("store").apply {
            isAccessible = true
        }
    }
}
