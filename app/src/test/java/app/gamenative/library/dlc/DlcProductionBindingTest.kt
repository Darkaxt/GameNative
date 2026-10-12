package app.gamenative.library.dlc

import android.content.Context
import app.gamenative.data.EpicCredentials
import app.gamenative.data.GOGCredentials
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.service.SteamService
import app.gamenative.service.epic.EpicAuthManager
import app.gamenative.service.gog.GOGAuthManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DlcProductionBindingTest {
    private val context = mockk<Context>()
    private val requests = mutableListOf<Pair<HttpUrl, String?>>()
    private var response: (HttpUrl) -> String = { "{}" }

    @Before
    fun setUp() {
        mockkConstructor(DlcHttpTransport::class)
        mockkObject(GOGAuthManager, EpicAuthManager, SteamService.Companion)
        coEvery { anyConstructed<DlcHttpTransport>().get(any(), any()) } coAnswers {
            val url = firstArg<HttpUrl>()
            requests += url to secondArg<String?>()
            Json.parseToJsonElement(response(url))
        }
        coEvery { GOGAuthManager.getStoredCredentials(context) } returns Result.success(GOGCredentials("synthetic-gog", "synthetic-refresh", "7", "Fixture"))
        coEvery { EpicAuthManager.getStoredCredentials(context) } returns Result.success(EpicCredentials("synthetic-epic", "synthetic-refresh", "a".repeat(32), "Fixture"))
        every { SteamService.currentDlcLicenseSnapshot() } returns listOf(SteamDlcLicense(10, 123L, 7))
        coEvery { SteamService.readLicensedDlcPackages(any()) } returns mapOf(10 to setOf("43"))
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun injectedGogSourceObtainsExistingNativeCredentialsInsideTheApplication() = runTest {
        response = { """{"owned":[42,43]}""" }
        val reference = SourceOwnedCopyReference.Gog(key(GameSource.GOG, "42"), "42")
        val result = NativeDlcSource(context).entitlements(reference)
        assertTrue(result.complete)
        assertEquals(setOf("42", "43"), result.ownedIds)
        assertEquals("synthetic-gog", requests.single().second)
        coVerify(exactly = 1) { GOGAuthManager.getStoredCredentials(context) }
        coVerify(exactly = 0) { EpicAuthManager.getStoredCredentials(any()) }
        verify(exactly = 0) { SteamService.currentDlcLicenseSnapshot() }
    }

    @Test
    fun injectedEpicSourceUsesExactNamespaceAndAccountBoundNativeEntitlements() = runTest {
        response = { """[{"id":"grant1","namespace":"ns","catalogItemId":"expansion","active":true,"consumable":false,"status":"ACTIVE"}]""" }
        val reference = SourceOwnedCopyReference.Epic(key(GameSource.EPIC, EpicStableSourceId.encode("ns", "base")), 1, "ns", "base")
        val result = NativeDlcSource(context).entitlements(reference)
        assertTrue(result.complete)
        assertEquals(setOf(EpicStableSourceId.encode("ns", "expansion")), result.ownedIds)
        assertEquals("synthetic-epic", requests.single().second)
        coVerify(exactly = 1) { EpicAuthManager.getStoredCredentials(context) }
        coVerify(exactly = 0) { GOGAuthManager.getStoredCredentials(any()) }
    }

    @Test
    fun injectedSteamSourceUsesTheReadOnlyLicensedPackageFacade() = runTest {
        val result = NativeDlcSource(context).entitlements(SourceOwnedCopyReference.Steam(key(GameSource.STEAM, "42"), 42))
        assertTrue(result.complete)
        assertEquals(setOf("43"), result.ownedIds)
        assertTrue(requests.isEmpty())
        verify(exactly = 2) { SteamService.currentDlcLicenseSnapshot() }
        coVerify(exactly = 1) { SteamService.readLicensedDlcPackages(listOf(SteamDlcLicense(10, 123L, 7))) }
    }

    private fun key(source: GameSource, id: String) = OwnedCopyKey(AccountScope.parse("a".repeat(64)), source, id)
}
