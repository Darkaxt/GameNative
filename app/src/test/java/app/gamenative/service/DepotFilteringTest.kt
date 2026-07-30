package app.gamenative.service

import app.gamenative.data.DepotInfo
import app.gamenative.data.ManifestInfo
import app.gamenative.data.SteamApp
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import org.junit.Assert.*
import org.junit.Test
import java.util.EnumSet

class DepotFilteringTest {

    private fun depot(
        depotId: Int = 1,
        manifests: Map<String, ManifestInfo> = emptyMap(),
        encryptedManifests: Map<String, ManifestInfo> = emptyMap(),
        sharedInstall: Boolean = false,
        osList: EnumSet<OS> = EnumSet.of(OS.windows),
        osArch: OSArch = OSArch.Arch64,
        dlcAppId: Int = SteamService.INVALID_APP_ID,
        language: String = "",
        systemDefined: Boolean = false,
        steamDeck: Boolean = false,
    ) = DepotInfo(
        depotId = depotId,
        dlcAppId = dlcAppId,
        depotFromApp = 0,
        sharedInstall = sharedInstall,
        osList = osList,
        osArch = osArch,
        manifests = manifests,
        encryptedManifests = encryptedManifests,
        language = language,
        systemDefined = systemDefined,
        steamDeck = steamDeck,
    )

    private fun manifest(
        size: Long = 1000L,
        download: Long = 800L,
        gid: Long = 123L,
    ) = ManifestInfo(
        name = "public",
        gid = gid,
        size = size,
        download = download,
    )

    // -- filterForDownloadableDepots: 0-byte manifest filtering --

    @Test
    fun `valid depot with normal manifest passes filter`() {
        val d = depot(manifests = mapOf("public" to manifest()))
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    @Test
    fun `depot with 0-byte manifest is accepted`() {
        val d = depot(manifests = mapOf("public" to manifest(size = 0L, download = 0L)))
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    @Test
    fun `depot with nonzero size but 0-byte download passes (old game without download metadata)`() {
        val d = depot(manifests = mapOf("public" to manifest(size = 1000L, download = 0L)))
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    @Test
    fun `depot with mix of 0 and nonzero manifests passes`() {
        val d = depot(manifests = mapOf(
            "public" to manifest(size = 1000L, download = 800L),
            "beta" to manifest(size = 0L, download = 0L),
        ))
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    @Test
    fun `encrypted-only depot is rejected`() {
        val d = depot(
            manifests = emptyMap(),
            encryptedManifests = mapOf("public" to manifest()),
        )
        assertFalse(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    @Test
    fun `depot with both regular and encrypted manifests passes`() {
        val d = depot(
            manifests = mapOf("public" to manifest()),
            encryptedManifests = mapOf("beta" to manifest()),
        )
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    @Test
    fun `depot with empty manifests and no shared install is rejected`() {
        val d = depot(manifests = emptyMap(), sharedInstall = false)
        assertFalse(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    @Test
    fun `depot with empty manifests but shared install passes`() {
        val d = depot(manifests = emptyMap(), sharedInstall = true)
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    // -- licensedDepotIds filtering --

    @Test
    fun `depot in licensed set passes`() {
        val d = depot(depotId = 100, manifests = mapOf("public" to manifest()))
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null, setOf(100, 200)))
    }

    @Test
    fun `depot not in licensed set is rejected`() {
        val d = depot(depotId = 100, manifests = mapOf("public" to manifest()))
        assertFalse(SteamService.filterForDownloadableDepots(d, true, false, "english", null, setOf(200, 300)))
    }

    @Test
    fun `null licensedDepotIds skips license check`() {
        val d = depot(depotId = 100, manifests = mapOf("public" to manifest()))
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null, null))
    }

    @Test
    fun `systemDefined depot bypasses license check`() {
        val d = depot(depotId = 551, manifests = mapOf("public" to manifest()), systemDefined = true)
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null, setOf(552, 553)))
    }

    @Test
    fun `non-systemDefined depot still rejected when unlicensed`() {
        val d = depot(depotId = 100, manifests = mapOf("public" to manifest()), systemDefined = false)
        assertFalse(SteamService.filterForDownloadableDepots(d, true, false, "english", null, setOf(200, 300)))
    }

    // -- Steam Deck depot filtering --

    @Test
    fun `deck depot rejected when non-deck windows depot exists`() {
        val d = depot(manifests = mapOf("public" to manifest()), steamDeck = true)
        assertFalse(SteamService.filterForDownloadableDepots(d, true, true, "english", null))
    }

    @Test
    fun `deck depot passes when no non-deck windows depot exists`() {
        val d = depot(manifests = mapOf("public" to manifest()), steamDeck = true)
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    @Test
    fun `batch update detects changed manifest owned only by indirect DLC`() {
        val mainDepot = depot(
            depotId = 100,
            manifests = mapOf("public" to manifest(gid = 1L)),
        )
        val localDlcDepot = depot(
            depotId = 200,
            manifests = mapOf("public" to manifest(gid = 10L)),
        )
        val remoteDlcDepot = localDlcDepot.copy(
            manifests = mapOf("public" to manifest(gid = 11L)),
        )
        val localMain = SteamApp(id = 1, name = "Main", depots = mapOf(100 to mainDepot))
        val remoteMain = localMain.copy()
        val localDlc = SteamApp(id = 2, name = "DLC", depots = mapOf(200 to localDlcDepot))
        val remoteDlc = localDlc.copy(depots = mapOf(200 to remoteDlcDepot))

        assertEquals(
            SteamUpdateCheckResult.Observed(updateAvailable = true),
            SteamService.getUpdatePendingFromSnapshots(
                localApp = localMain,
                branch = "public",
                preferredLanguage = "english",
                ownedDlcApps = listOf(localDlc),
                licensedDepotIds = mapOf(1 to setOf(100), 2 to setOf(200)),
                installedDepotIds = setOf(100, 200),
                remoteApps = mapOf(1 to remoteMain, 2 to remoteDlc),
            ),
        )
    }

    @Test
    fun `batch update detects changed direct DLC depot owned by DLC app`() {
        val localDlcDepot = depot(
            depotId = 200,
            dlcAppId = 2,
            manifests = mapOf("public" to manifest(gid = 10L)),
        )
        val remoteDlcDepot = localDlcDepot.copy(
            manifests = mapOf("public" to manifest(gid = 11L)),
        )
        val localMain = SteamApp(id = 1, name = "Main", depots = mapOf(200 to localDlcDepot))
        val remoteMain = localMain.copy(depots = mapOf(200 to remoteDlcDepot))
        val localDlc = SteamApp(id = 2, name = "DLC")

        assertEquals(
            SteamUpdateCheckResult.Observed(updateAvailable = true),
            SteamService.getUpdatePendingFromSnapshots(
                localApp = localMain,
                branch = "public",
                preferredLanguage = "english",
                ownedDlcApps = listOf(localDlc),
                licensedDepotIds = mapOf(1 to setOf(200)),
                installedDepotIds = setOf(200),
                remoteApps = mapOf(1 to remoteMain, 2 to localDlc),
            ),
        )
    }

    @Test
    fun `batch update retains installed depot after language selection changes`() {
        val englishDepot = depot(
            depotId = 100,
            language = "english",
            manifests = mapOf("public" to manifest(gid = 1L)),
        )
        val localFrenchDepot = depot(
            depotId = 200,
            language = "french",
            manifests = mapOf("public" to manifest(gid = 10L)),
        )
        val remoteFrenchDepot = localFrenchDepot.copy(
            manifests = mapOf("public" to manifest(gid = 11L)),
        )
        val localMain = SteamApp(
            id = 1,
            name = "Main",
            depots = mapOf(100 to englishDepot, 200 to localFrenchDepot),
        )
        val remoteMain = localMain.copy(
            depots = mapOf(100 to englishDepot, 200 to remoteFrenchDepot),
        )

        assertEquals(
            SteamUpdateCheckResult.Observed(updateAvailable = true),
            SteamService.getUpdatePendingFromSnapshots(
                localApp = localMain,
                branch = "public",
                preferredLanguage = "english",
                ownedDlcApps = emptyList(),
                licensedDepotIds = mapOf(1 to setOf(100, 200)),
                installedDepotIds = setOf(100, 200),
                remoteApps = mapOf(1 to remoteMain),
            ),
        )
    }

    @Test
    fun `parent 64 bit preference excludes indirect 32 bit DLC from update comparison`() {
        val parentDepot = depot(
            depotId = 100,
            osArch = OSArch.Arch64,
            manifests = mapOf("public" to manifest(gid = 1L)),
        )
        val localDlcDepot = depot(
            depotId = 200,
            osArch = OSArch.Arch32,
            manifests = mapOf("public" to manifest(gid = 10L)),
        )
        val remoteDlcDepot = localDlcDepot.copy(
            manifests = mapOf("public" to manifest(gid = 11L)),
        )
        val localMain = SteamApp(id = 1, name = "Main", depots = mapOf(100 to parentDepot))
        val localDlc = SteamApp(id = 2, name = "DLC", depots = mapOf(200 to localDlcDepot))

        assertEquals(
            SteamUpdateCheckResult.Observed(updateAvailable = false),
            SteamService.getUpdatePendingFromSnapshots(
                localApp = localMain,
                branch = "public",
                preferredLanguage = "english",
                ownedDlcApps = listOf(localDlc),
                licensedDepotIds = mapOf(1 to setOf(100), 2 to setOf(200)),
                installedDepotIds = setOf(100),
                remoteApps = mapOf(
                    1 to localMain,
                    2 to localDlc.copy(depots = mapOf(200 to remoteDlcDepot)),
                ),
            ),
        )
    }

    @Test
    fun `missing remote branch manifest remains unknown`() {
        val localDepot = depot(
            depotId = 100,
            manifests = mapOf("public" to manifest(gid = 1L)),
        )
        val local = SteamApp(id = 1, name = "Main", depots = mapOf(100 to localDepot))
        val remote = local.copy(depots = mapOf(100 to localDepot.copy(manifests = emptyMap())))

        assertEquals(
            SteamUpdateCheckResult.Failed(SteamManifestUnavailableException::class),
            SteamService.getUpdatePendingFromSnapshots(
                localApp = local,
                branch = "public",
                preferredLanguage = "english",
                ownedDlcApps = emptyList(),
                licensedDepotIds = mapOf(1 to setOf(100)),
                installedDepotIds = emptySet(),
                remoteApps = mapOf(1 to remote),
            ),
        )
    }

    @Test
    fun `zero comparable required depots remains unknown`() {
        val local = SteamApp(id = 1, name = "Main", depots = emptyMap())

        assertEquals(
            SteamUpdateCheckResult.Failed(SteamNoComparableDepotsException::class),
            SteamService.getUpdatePendingFromSnapshots(
                localApp = local,
                branch = "public",
                preferredLanguage = "english",
                ownedDlcApps = emptyList(),
                licensedDepotIds = emptyMap(),
                installedDepotIds = emptySet(),
                remoteApps = mapOf(1 to local),
            ),
        )
    }

    @Test
    fun `malformed remote sibling does not prevent healthy comparison`() {
        val firstDepot = depot(depotId = 100, manifests = mapOf("public" to manifest(gid = 1L)))
        val secondDepot = depot(depotId = 200, manifests = mapOf("public" to manifest(gid = 2L)))
        val first = SteamApp(id = 1, name = "Healthy", depots = mapOf(100 to firstDepot))
        val second = SteamApp(id = 2, name = "Malformed", depots = mapOf(200 to secondDepot))

        val results = SteamService.getUpdatePendingBatchFromSnapshots(
            localApps = listOf(first, second),
            branches = mapOf(1 to "public", 2 to "public"),
            preferredLanguage = "english",
            ownedDlcApps = emptyList(),
            licensedDepotIds = mapOf(1 to setOf(100), 2 to setOf(200)),
            installedDepotIds = emptyMap(),
            unlockedBranchAppIds = emptySet(),
            remoteApps = mapOf(1 to first),
            remoteGenerationFailures = mapOf(2 to MalformedSteamMetadataException::class),
        )

        assertEquals(SteamUpdateCheckResult.Observed(false), results[1])
        assertEquals(
            SteamUpdateCheckResult.Failed(MalformedSteamMetadataException::class),
            results[2],
        )
    }

    @Test
    fun `non-deck depot passes regardless of preferNonDeckWindows`() {
        val d = depot(manifests = mapOf("public" to manifest()), steamDeck = false)
        assertTrue(SteamService.filterForDownloadableDepots(d, true, true, "english", null))
    }

    private class MalformedSteamMetadataException : Exception()
}
