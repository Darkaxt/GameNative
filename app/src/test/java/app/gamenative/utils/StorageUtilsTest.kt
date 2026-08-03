package app.gamenative.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageUtilsTest {
    @Test
    fun sideBySideRootUsesChannelName() {
        val appFiles = File("/storage/1234/Android/data/app.gamenative.darkaxt/files")

        assertEquals(
            File("/storage/1234/GameNative-Darkaxt"),
            StorageUtils.publicInstallRoot(appFiles, "GameNative-Darkaxt"),
        )
    }

    @Test
    fun compatibilityRootRemainsUnchanged() {
        val appFiles = File("/storage/1234/Android/data/app.gamenative/files")

        assertEquals(
            File("/storage/1234/GameNative"),
            StorageUtils.publicInstallRoot(appFiles, "GameNative"),
        )
    }

    @Test
    fun officialRootIsClassifiedAsSharedForSideBySideChannel() {
        assertTrue(StorageUtils.isSharedCompatibilityRoot(File("/storage/1234/GameNative")))
        assertFalse(StorageUtils.isSharedCompatibilityRoot(File("/storage/1234/GameNative-Darkaxt")))
    }
}
