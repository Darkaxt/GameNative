package app.gamenative.service.gog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.enums.AppType
import app.gamenative.library.canonical.AccountScopedOwnershipLedger
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class GOGManagerTest {
    @Test
    fun parsedDlcIsPersistedWithDlcTypeWhileRemainingExcluded() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = GOGManager(
            mockk<GOGGameDao>(relaxed = true),
            mockk<AccountScopedOwnershipLedger>(relaxed = true),
            context,
        )
        val parsed = ParsedGogGame(
            id = "dlc-id",
            title = "Expansion",
            slug = "expansion",
            imageUrl = "",
            iconUrl = "",
            backgroundUrl = "",
            developer = "Studio",
            publisher = "Publisher",
            genres = listOf("Role-playing"),
            languages = listOf("English"),
            description = "Description",
            releaseDate = "2024-01-01",
            downloadSize = 1L,
            isSecret = false,
            isDlc = true,
        )

        val game = manager.parseGameObject(parsed)

        assertEquals(AppType.dlc, game?.type)
        assertTrue(game?.exclude == true)
    }
}
