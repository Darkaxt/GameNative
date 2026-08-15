package app.gamenative.library.community

import app.gamenative.diagnostics.DiagnosticArea
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEventName
import app.gamenative.diagnostics.DiagnosticLogStore
import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.diagnostics.FeatureDiagnostics
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamCommunityDiagnosticsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var originalStore: DiagnosticLogStore? = null

    @Before
    fun installStore() {
        originalStore = storeField.get(null) as DiagnosticLogStore?
        storeField.set(
            null,
            DiagnosticLogStore(
                directory = temporaryFolder.newFolder("diagnostics"),
                json = Json { ignoreUnknownKeys = true },
            ),
        )
    }

    @After
    fun restoreStore() {
        storeField.set(null, originalStore)
    }

    @Test
    fun `feature sink exports only typed bounded page aggregates`() {
        FeatureSteamCommunityDiagnostics().record(
            SteamCommunityPageDiagnostic(
                operation = SteamCommunityPageOperation.DISCUSSION_THREAD,
                outcome = DiagnosticOutcome.SUCCEEDED,
                durationMs = 25,
                httpStatus = 200,
                attemptCount = 1,
                itemCount = 15,
                skippedItemCount = 2,
                blankItemCount = 1,
                duplicateItemCount = 0,
            ),
        )

        val event = FeatureDiagnostics.recent(1).single()
        assertEquals(DiagnosticArea.DISCUSSIONS, event.area)
        assertEquals(DiagnosticEventName.DISCUSSION_PAGE, event.name)
        assertEquals(25L, event.durationMs)
        assertEquals(
            mapOf(
                DiagnosticAttribute.OPERATION.wireName to "DISCUSSION_THREAD",
                DiagnosticAttribute.ATTEMPT_COUNT.wireName to "1",
                DiagnosticAttribute.ITEM_COUNT.wireName to "15",
                DiagnosticAttribute.SKIPPED_ITEM_COUNT.wireName to "2",
                DiagnosticAttribute.BLANK_ITEM_COUNT.wireName to "1",
                DiagnosticAttribute.DUPLICATE_ITEM_COUNT.wireName to "0",
                DiagnosticAttribute.HTTP_STATUS.wireName to "200",
            ),
            event.attributes,
        )
    }

    private companion object {
        val storeField = FeatureDiagnostics::class.java.getDeclaredField("store").apply {
            isAccessible = true
        }
    }
}
