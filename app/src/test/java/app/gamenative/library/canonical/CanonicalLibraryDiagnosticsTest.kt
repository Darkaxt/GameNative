package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticLogStore
import app.gamenative.diagnostics.DiagnosticReportBuilder
import app.gamenative.diagnostics.DiagnosticReportHeader
import app.gamenative.diagnostics.FeatureDiagnostics
import app.gamenative.library.canonical.action.ActionFailureReason
import app.gamenative.library.canonical.action.ActionSelectionPolicy
import java.io.File
import kotlin.reflect.KClass
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CanonicalLibraryDiagnosticsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var originalStore: DiagnosticLogStore? = null

    @Before
    fun installStore() {
        originalStore = featureDiagnosticsStore()
        setFeatureDiagnosticsStore(
            DiagnosticLogStore(
                directory = temporaryFolder.newFolder("diagnostics"),
                json = Json { ignoreUnknownKeys = true },
            ),
        )
    }

    @After
    fun restoreStore() {
        setFeatureDiagnosticsStore(originalStore)
    }

    @Test
    fun `facade exposes only privacy safe typed parameters`() {
        val methods = CanonicalLibraryDiagnosticSink::class.java.declaredMethods
            .associate { method ->
                method.name to method.parameterTypes.toList()
            }

        assertEquals(
            setOf(
                "cardsProjected",
                "runtimeReadFailed",
                "legacyFallback",
                "routeSelected",
                "chooserRequired",
                "routeFailed",
                "revalidationFailed",
                "routeSucceeded",
            ),
            methods.keys,
        )
        val allowedParameterTypes = setOf(
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            GameSource::class.java,
            OwnedCopyOperation::class.java,
            ActionSelectionPolicy::class.java,
            CanonicalPublicFailure::class.java,
            ActionFailureReason::class.java,
            KClass::class.java,
        )
        methods.values.flatten().forEach { parameterType ->
            assertTrue("Unexpected diagnostic parameter type: $parameterType", parameterType in allowedParameterTypes)
        }
        assertFalse(methods.values.flatten().contains(String::class.java))
        assertFalse(methods.values.flatten().contains(OwnedCopyKey::class.java))
        assertFalse(methods.values.flatten().contains(AccountScope::class.java))
        assertFalse(methods.values.flatten().contains(CanonicalGameId::class.java))
    }

    @Test
    fun `manual export contains bounded typed attributes and no private fixture values`() {
        val forbidden = ForbiddenFixture()
        val diagnostics = FeatureCanonicalLibraryDiagnostics()

        diagnostics.cardsProjected(
            resultCount = -9,
            canonicalCount = Int.MAX_VALUE,
            copyCount = 1_500,
            elapsedMs = Long.MAX_VALUE,
        )
        diagnostics.runtimeReadFailed(GameSource.STEAM, forbidden.failure::class)
        diagnostics.legacyFallback(CanonicalPublicFailure.ASSEMBLY_FAILED, forbidden.failure::class)
        diagnostics.routeSelected(
            source = GameSource.GOG,
            operation = OwnedCopyOperation.INSTALL,
            policy = ActionSelectionPolicy.EXPLICIT,
            capableCount = Int.MAX_VALUE,
        )
        diagnostics.chooserRequired(OwnedCopyOperation.PLAY, capableCount = -4)
        diagnostics.routeFailed(
            source = GameSource.EPIC,
            operation = OwnedCopyOperation.UPDATE,
            reason = ActionFailureReason.PREFERENCE_WRITE_FAILED,
            errorClass = forbidden.failure::class,
        )
        diagnostics.revalidationFailed(
            source = GameSource.AMAZON,
            operation = OwnedCopyOperation.PLAY,
            reason = ActionFailureReason.TARGET_CHANGED,
        )
        diagnostics.routeSucceeded(GameSource.CUSTOM_GAME, OwnedCopyOperation.PLAY)
        diagnostics.runtimeReadFailed(
            GameSource.GOG,
            object : IllegalStateException(forbidden.exceptionMessage) {}::class,
        )

        val events = FeatureDiagnostics.recent()
        val report = DiagnosticReportBuilder.build(
            header = DiagnosticReportHeader(
                appVersion = "test-version",
                buildFlavor = "test-flavor",
                device = "test-device",
                androidVersion = "test-android",
            ),
            events = events,
        )

        assertEquals(9, events.size)
        assertTrue(report.contains("Upload: manual export only"))
        forbidden.allPrivateValues.forEach { privateValue ->
            assertFalse("Private value reached manual export: $privateValue", report.contains(privateValue))
        }

        val exactAllowedAttributes = setOf(
            "source",
            "operation",
            "capability",
            "selection_policy",
            "reason",
            "result_count",
            "canonical_count",
            "copy_count",
            "error_type",
        )
        events.forEach { event ->
            assertTrue(event.attributes.keys.all(exactAllowedAttributes::contains))
            assertFalse(event.attributes.containsKey("duration_ms"))
        }

        val projected = events.first()
        assertEquals("0", projected.attributes.getValue("result_count"))
        assertEquals("1000000", projected.attributes.getValue("canonical_count"))
        assertEquals("1500", projected.attributes.getValue("copy_count"))
        assertEquals(86_400_000L, projected.durationMs)
        assertEquals("0", events[4].attributes.getValue("result_count"))
        assertEquals("UNKNOWN_EXCEPTION", events.last().attributes.getValue("error_type"))
        assertNull(events[1].durationMs)
        assertTrue(DiagnosticAttribute.entries.any { it.wireName == "selection_policy" })
    }

    @Test
    fun `persistence failure is contained by production facade`() {
        val diagnosticsDirectory = temporaryFolder.newFolder("failing-diagnostics")
        setFeatureDiagnosticsStore(DiagnosticLogStore(diagnosticsDirectory))
        assertTrue(diagnosticsDirectory.deleteRecursively())
        assertTrue(diagnosticsDirectory.createNewFile())

        FeatureCanonicalLibraryDiagnostics().routeSucceeded(
            GameSource.STEAM,
            OwnedCopyOperation.PLAY,
        )
    }

    private fun featureDiagnosticsStore(): DiagnosticLogStore? = storeField.get(null) as DiagnosticLogStore?

    private fun setFeatureDiagnosticsStore(store: DiagnosticLogStore?) {
        storeField.set(null, store)
    }

    private class ForbiddenFailure(message: String) : IllegalStateException(message)

    private data class ForbiddenFixture(
        val canonicalId: String = "11111111-2222-3333-4444-555555555555",
        val accountScope: String = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        val sourceId: String = "private-source-id-987654",
        val title: String = "Private Library Title QZX",
        val candidateTitle: String = "Private Candidate Title YWV",
        val searchText: String = "private search phrase jkl",
        val installPath: String = "C:\\PrivateGames\\SecretTitle",
        val url: String = "https://private.example.invalid/library/item",
        val token: String = "token=private-token-value-123456",
        val username: String = "private-user-name-abc",
        val reviewBody: String = "private review body rst",
        val discussionBody: String = "private discussion body uvw",
        val exceptionMessage: String = "private exception message mno",
    ) {
        val key = OwnedCopyKey(AccountScope.parse(accountScope), GameSource.STEAM, sourceId)
        val parsedCanonicalId = CanonicalGameId.parse(canonicalId)
        val failure = ForbiddenFailure(exceptionMessage)
        val allPrivateValues = listOf(
            canonicalId,
            accountScope,
            sourceId,
            title,
            candidateTitle,
            searchText,
            installPath,
            url,
            token,
            username,
            reviewBody,
            discussionBody,
            exceptionMessage,
            key.stableSourceId,
            parsedCanonicalId.value,
        ).distinct()
    }

    private companion object {
        val storeField = FeatureDiagnostics::class.java.getDeclaredField("store").apply {
            isAccessible = true
        }
    }
}
