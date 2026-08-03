package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseChannelPolicyTest {
    @Test
    fun sideBySideDisablesOfficialNetworkChannels() {
        val policy = ReleaseChannelPolicy(
            applicationId = "app.gamenative.darkaxt",
            releaseChannel = "darkaxt-side-by-side",
            officialUpdaterEnabled = false,
            officialAnalyticsEnabled = false,
        )

        assertFalse(policy.mayCheckOfficialUpdates)
        assertFalse(policy.mayInitializeOfficialAnalytics)
        assertEquals("app.gamenative.darkaxt.LAUNCH_GAME", policy.launchAction)
    }

    @Test
    fun compatibilityKeepsOfficialIntegrationsEnabled() {
        val policy = ReleaseChannelPolicy(
            applicationId = "app.gamenative",
            releaseChannel = "compatibility",
            officialUpdaterEnabled = true,
            officialAnalyticsEnabled = true,
        )

        assertTrue(policy.mayCheckOfficialUpdates)
        assertTrue(policy.mayInitializeOfficialAnalytics)
        assertEquals("app.gamenative.LAUNCH_GAME", policy.launchAction)
    }

    @Test
    fun flagsCannotEnableOfficialIntegrationsOnAnotherChannel() {
        val policy = ReleaseChannelPolicy(
            applicationId = "app.gamenative.darkaxt",
            releaseChannel = "darkaxt-side-by-side",
            officialUpdaterEnabled = true,
            officialAnalyticsEnabled = true,
        )

        assertFalse(policy.mayCheckOfficialUpdates)
        assertFalse(policy.mayInitializeOfficialAnalytics)
    }
}
