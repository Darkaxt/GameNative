package com.winlator.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.xenvironment.ImageFs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RuntimePathsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun privateStorageAndGamepadPathsFollowRuntimeDataDirectory() {
        assertEquals(File(context.dataDir, "storage"), RuntimePaths.storageDir(context))
        assertEquals(File(context.filesDir, "gamepad_shm"), RuntimePaths.gamepadSharedMemoryDir(context))
        assertTrue(RuntimePaths.defaultDrives(context).contains("E:${RuntimePaths.storageDir(context).absolutePath}"))
    }

    @Test
    fun importedDriveMappingsAreRebasedWithoutRetainingAnotherSandbox() {
        val imported = "D:/storage/emulated/0/DownloadE:/data/user/0/another.package/storage"

        assertEquals(
            "D:/storage/emulated/0/DownloadE:${context.dataDir}/storage",
            RuntimePaths.rebasePrivateStorageDrive(context, imported),
        )
    }

    @Test
    fun blankImportedDrivesResolveToRuntimeDefaults() {
        assertEquals(RuntimePaths.defaultDrives(context), RuntimePaths.resolveDrives(context, ""))
    }

    @Test
    fun importedPrivatePathsAreRebasedToTheReceivingSandbox() {
        assertEquals(
            "${context.dataDir}/files/imagefs/home/xuser/tool.exe",
            RuntimePaths.rebasePrivatePath(
                context,
                "/data/data/another.package/files/imagefs/home/xuser/tool.exe",
            ),
        )
    }

    @Test
    fun mediaAndDxvkPathsStayUnderResolvedImageFs() {
        val root = File(context.filesDir, "custom-imagefs")
        val paths = RuntimePaths.mediaConversionEnvVars(root)

        assertTrue(paths.all { it.substringAfter('=').startsWith(root.absolutePath) })
        assertEquals(
            File(root, ImageFs.CACHE_PATH.removePrefix("/")).absolutePath,
            RuntimePaths.dxvkCachePath(root),
        )
    }
}
