package com.winlator.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class WineInfoProfileTest {
    @Test
    fun installedWineProfilesKeepTheirIdentityAcrossVersionCodeWidths() {
        assertInstalledProfile("wine", ContentProfile.ContentType.CONTENT_TYPE_WINE, "10.0-arm64ec")
    }

    @Test
    fun installedProtonProfilesKeepTheirIdentityAcrossVersionCodeWidths() {
        assertInstalledProfile("Proton", ContentProfile.ContentType.CONTENT_TYPE_PROTON, "9.0-x86_64")
    }

    @Test
    fun invalidIdentifiersRetainTheBundledFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = mockk<ContentsManager>()
        every { manager.getProfileByEntryName(any()) } returns null
        val actual = WineInfo.fromIdentifier(context, manager, "not-a-wine-profile")
        assertEquals(WineInfo.MAIN_WINE_VERSION.identifier(), actual.identifier())
        assertNull(actual.path)
    }

    private fun assertInstalledProfile(type: String, contentType: ContentProfile.ContentType, versionName: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = mockk<ContentsManager>()
        for (code in listOf(1, 10, 123)) {
            val profile = ContentProfile().apply {
                this.type = contentType
                verName = versionName
                verCode = code
            }
            val entryName = "$type-$versionName-$code"
            every { manager.getProfileByEntryName(entryName) } returns profile
            val actual = WineInfo.fromIdentifier(context, manager, entryName)
            assertEquals(type.lowercase(), actual.type)
            assertEquals(versionName.substringBefore('-'), actual.version)
            assertEquals(versionName.substringAfter('-'), actual.arch)
            assertEquals(ContentsManager.getInstallDir(context, profile).path, actual.path)
        }
    }
}
