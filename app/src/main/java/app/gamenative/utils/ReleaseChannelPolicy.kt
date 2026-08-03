package app.gamenative.utils

import app.gamenative.BuildConfig

data class ReleaseChannelPolicy(
    val applicationId: String,
    val releaseChannel: String,
    val officialUpdaterEnabled: Boolean,
    val officialAnalyticsEnabled: Boolean,
) {
    val launchAction: String = "$applicationId.LAUNCH_GAME"
    val mayCheckOfficialUpdates: Boolean = officialUpdaterEnabled && releaseChannel == "compatibility"
    val mayInitializeOfficialAnalytics: Boolean = officialAnalyticsEnabled && releaseChannel == "compatibility"

    companion object {
        fun current() = ReleaseChannelPolicy(
            applicationId = BuildConfig.APPLICATION_ID,
            releaseChannel = BuildConfig.RELEASE_CHANNEL,
            officialUpdaterEnabled = BuildConfig.OFFICIAL_UPDATER_ENABLED,
            officialAnalyticsEnabled = BuildConfig.OFFICIAL_ANALYTICS_ENABLED,
        )
    }
}
