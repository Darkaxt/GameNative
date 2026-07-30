package app.gamenative.library.canonical

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.CanonicalGamePreferenceEntity
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.PluviaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class PreferredCopyRepositoryTest {
    private lateinit var db: PluviaDatabase
    private lateinit var repository: PreferredCopyRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PreferredCopyRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `present canonical member is saved as the complete key`() = runBlocking {
        val canonical = canonical(CANONICAL_A)
        val key = key(SCOPE_A, GameSource.GOG, "gog-copy")
        seed(canonical, match(key, canonical.canonicalId))

        repository.setPreferredCopy(CanonicalGameId.parse(canonical.canonicalId), key, 200L)

        assertEquals(
            preference(canonical.canonicalId, key, updatedAt = 200L),
            db.canonicalPreferenceDao().get(canonical.canonicalId),
        )
    }

    @Test
    fun `setting a preference preserves overrides and updates timestamp`() = runBlocking {
        val canonical = canonical(CANONICAL_A)
        val previousKey = key(SCOPE_A, GameSource.STEAM, "10")
        val selectedKey = key(SCOPE_A, GameSource.GOG, "gog-copy")
        seed(canonical, match(selectedKey, canonical.canonicalId))
        db.canonicalPreferenceDao().upsert(
            preference(
                canonicalId = canonical.canonicalId,
                key = previousKey,
                titleOverride = "Custom title",
                artworkOverrideJson = "{\"cover\":\"custom\"}",
                updatedAt = 100L,
            ),
        )

        repository.setPreferredCopy(
            CanonicalGameId.parse(canonical.canonicalId),
            selectedKey,
            250L,
        )

        assertEquals(
            preference(
                canonicalId = canonical.canonicalId,
                key = selectedKey,
                titleOverride = "Custom title",
                artworkOverrideJson = "{\"cover\":\"custom\"}",
                updatedAt = 250L,
            ),
            db.canonicalPreferenceDao().get(canonical.canonicalId),
        )
    }

    @Test
    fun `copy belonging to another canonical is rejected without changing preference`() = runBlocking {
        val requestedCanonical = canonical(CANONICAL_A)
        val otherCanonical = canonical(CANONICAL_B)
        val existingKey = key(SCOPE_A, GameSource.STEAM, "10")
        val otherKey = key(SCOPE_A, GameSource.GOG, "other-copy")
        db.canonicalGameDao().insert(requestedCanonical)
        db.canonicalGameDao().insert(otherCanonical)
        db.storeMatchDao().upsert(match(otherKey, otherCanonical.canonicalId))
        val existing = preference(
            requestedCanonical.canonicalId,
            existingKey,
            titleOverride = "Keep title",
            artworkOverrideJson = "keep-art",
            updatedAt = 100L,
        )
        db.canonicalPreferenceDao().upsert(existing)

        val failure = runCatching {
            repository.setPreferredCopy(
                CanonicalGameId.parse(requestedCanonical.canonicalId),
                otherKey,
                200L,
            )
        }.exceptionOrNull()

        assertValidationFailure(failure)
        assertEquals(existing, db.canonicalPreferenceDao().get(requestedCanonical.canonicalId))
    }

    @Test
    fun `absent copy is rejected without falling back to a present sibling`() = runBlocking {
        val canonical = canonical(CANONICAL_A)
        val absentKey = key(SCOPE_A, GameSource.GOG, "absent-copy")
        val siblingKey = key(SCOPE_A, GameSource.STEAM, "10")
        db.canonicalGameDao().insert(canonical)
        db.storeMatchDao().upsert(match(absentKey, canonical.canonicalId, isPresent = false))
        db.storeMatchDao().upsert(match(siblingKey, canonical.canonicalId))
        val existing = preference(canonical.canonicalId, siblingKey, updatedAt = 100L)
        db.canonicalPreferenceDao().upsert(existing)

        val failure = runCatching {
            repository.setPreferredCopy(
                CanonicalGameId.parse(canonical.canonicalId),
                absentKey,
                200L,
            )
        }.exceptionOrNull()

        assertValidationFailure(failure)
        assertEquals(existing, db.canonicalPreferenceDao().get(canonical.canonicalId))
    }

    @Test
    fun `account A cannot select account B relationship with the same source id`() = runBlocking {
        val canonical = canonical(CANONICAL_A)
        val accountAKey = key(SCOPE_A, GameSource.GOG, "shared-id")
        val accountBKey = key(SCOPE_B, GameSource.GOG, "shared-id")
        seed(canonical, match(accountBKey, canonical.canonicalId))

        val failure = runCatching {
            repository.setPreferredCopy(
                CanonicalGameId.parse(canonical.canonicalId),
                accountAKey,
                200L,
            )
        }.exceptionOrNull()

        assertValidationFailure(failure)
        assertNull(db.canonicalPreferenceDao().get(canonical.canonicalId))
    }

    @Test
    fun `incomplete existing preference is replaced by a complete validated key`() = runBlocking {
        val canonical = canonical(CANONICAL_A)
        val selectedKey = key(SCOPE_B, GameSource.EPIC, "epic-copy")
        seed(canonical, match(selectedKey, canonical.canonicalId))
        db.canonicalPreferenceDao().upsert(
            CanonicalGamePreferenceEntity(
                canonicalId = canonical.canonicalId,
                preferredAccountScope = SCOPE_A,
                preferredSource = null,
                preferredStableSourceId = "old-copy",
                titleOverride = "Keep title",
                artworkOverrideJson = "keep-art",
                updatedAt = 100L,
            ),
        )

        repository.setPreferredCopy(
            CanonicalGameId.parse(canonical.canonicalId),
            selectedKey,
            200L,
        )

        assertEquals(
            preference(
                canonicalId = canonical.canonicalId,
                key = selectedKey,
                titleOverride = "Keep title",
                artworkOverrideJson = "keep-art",
                updatedAt = 200L,
            ),
            db.canonicalPreferenceDao().get(canonical.canonicalId),
        )
    }

    @Test
    fun `malformed existing preference is replaced by a complete validated key`() = runBlocking {
        val canonical = canonical(CANONICAL_A)
        val selectedKey = key(SCOPE_A, GameSource.AMAZON, "amazon-copy")
        seed(canonical, match(selectedKey, canonical.canonicalId))
        db.canonicalPreferenceDao().upsert(
            CanonicalGamePreferenceEntity(
                canonicalId = canonical.canonicalId,
                preferredAccountScope = "malformed-scope",
                preferredSource = GameSource.GOG,
                preferredStableSourceId = "old-copy",
                titleOverride = "Keep title",
                artworkOverrideJson = "keep-art",
                updatedAt = 100L,
            ),
        )

        repository.setPreferredCopy(
            CanonicalGameId.parse(canonical.canonicalId),
            selectedKey,
            200L,
        )

        assertEquals(
            preference(
                canonicalId = canonical.canonicalId,
                key = selectedKey,
                titleOverride = "Keep title",
                artworkOverrideJson = "keep-art",
                updatedAt = 200L,
            ),
            db.canonicalPreferenceDao().get(canonical.canonicalId),
        )
    }

    @Test
    fun `present relationship remains selectable without runtime availability state`() = runBlocking {
        val canonical = canonical(CANONICAL_A)
        val selectedKey = key(SCOPE_A, GameSource.GOG, "temporarily-unavailable")
        seed(canonical, match(selectedKey, canonical.canonicalId))
        db.canonicalPreferenceDao().upsert(
            preference(canonical.canonicalId, selectedKey, updatedAt = 100L),
        )

        repository.setPreferredCopy(
            CanonicalGameId.parse(canonical.canonicalId),
            selectedKey,
            200L,
        )

        assertEquals(
            preference(canonical.canonicalId, selectedKey, updatedAt = 200L),
            db.canonicalPreferenceDao().get(canonical.canonicalId),
        )
    }

    @Test
    fun `clearing nulls only the complete preferred key and updates timestamp`() = runBlocking {
        val canonical = canonical(CANONICAL_A)
        val selectedKey = key(SCOPE_A, GameSource.GOG, "gog-copy")
        db.canonicalGameDao().insert(canonical)
        val existing = preference(
            canonicalId = canonical.canonicalId,
            key = selectedKey,
            titleOverride = "Custom title",
            artworkOverrideJson = "{\"cover\":\"custom\"}",
            updatedAt = 100L,
        )
        db.canonicalPreferenceDao().upsert(existing)

        repository.clearPreferredCopy(CanonicalGameId.parse(canonical.canonicalId), 300L)

        assertEquals(
            existing.copy(
                preferredAccountScope = null,
                preferredSource = null,
                preferredStableSourceId = null,
                updatedAt = 300L,
            ),
            db.canonicalPreferenceDao().get(canonical.canonicalId),
        )
    }

    @Test
    fun `clearing with no preference row is a no-op`() = runBlocking {
        val canonical = canonical(CANONICAL_A)
        db.canonicalGameDao().insert(canonical)

        repository.clearPreferredCopy(CanonicalGameId.parse(canonical.canonicalId), 300L)

        assertNull(db.canonicalPreferenceDao().get(canonical.canonicalId))
    }

    @Test
    fun `preference write failure preserves the complete previous row`() = runBlocking {
        val canonical = canonical(CANONICAL_A)
        val previousKey = key(SCOPE_A, GameSource.STEAM, "10")
        val selectedKey = key(SCOPE_A, GameSource.GOG, "gog-copy")
        seed(canonical, match(selectedKey, canonical.canonicalId))
        val existing = preference(
            canonicalId = canonical.canonicalId,
            key = previousKey,
            titleOverride = "Keep title",
            artworkOverrideJson = "keep-art",
            updatedAt = 100L,
        )
        db.canonicalPreferenceDao().upsert(existing)
        installPreferenceWriteFailureTrigger(canonical.canonicalId)

        val failure = runCatching {
            repository.setPreferredCopy(
                CanonicalGameId.parse(canonical.canonicalId),
                selectedKey,
                200L,
            )
        }.exceptionOrNull()

        assertInjectedFailure(failure)
        assertEquals(existing, db.canonicalPreferenceDao().get(canonical.canonicalId))
    }

    @Test
    fun `preference clear failure preserves the complete previous row`() = runBlocking {
        val canonical = canonical(CANONICAL_A)
        val selectedKey = key(SCOPE_A, GameSource.GOG, "gog-copy")
        db.canonicalGameDao().insert(canonical)
        val existing = preference(
            canonicalId = canonical.canonicalId,
            key = selectedKey,
            titleOverride = "Keep title",
            artworkOverrideJson = "keep-art",
            updatedAt = 100L,
        )
        db.canonicalPreferenceDao().upsert(existing)
        installPreferenceWriteFailureTrigger(canonical.canonicalId)

        val failure = runCatching {
            repository.clearPreferredCopy(CanonicalGameId.parse(canonical.canonicalId), 200L)
        }.exceptionOrNull()

        assertInjectedFailure(failure)
        assertEquals(existing, db.canonicalPreferenceDao().get(canonical.canonicalId))
    }

    private suspend fun seed(
        canonical: CanonicalGameEntity,
        match: StoreMatchEntity,
    ) {
        db.canonicalGameDao().insert(canonical)
        db.storeMatchDao().upsert(match)
    }

    private fun canonical(canonicalId: String) = CanonicalGameEntity(
        canonicalId = canonicalId,
        steamAppId = null,
        displayName = "Canonical game",
        matchTitleKey = "canonical-game",
        primaryMetadataSource = GameSource.STEAM,
        appType = CanonicalAppType.GAME,
        releaseYear = 2026,
        developerKey = "developer",
        classificationState = ClassificationState.CLASSIFIED,
        steamReviewCount = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun key(
        accountScope: String,
        source: GameSource,
        stableSourceId: String,
    ) = OwnedCopyKey(AccountScope.parse(accountScope), source, stableSourceId)

    private fun match(
        key: OwnedCopyKey,
        canonicalId: String,
        isPresent: Boolean = true,
    ) = StoreMatchEntity(
        accountScope = key.accountScope.value,
        source = key.source,
        stableSourceId = key.stableSourceId,
        canonicalId = canonicalId,
        candidateSteamAppId = null,
        matchMethod = MatchMethod.EXACT_METADATA,
        confidence = MatchConfidence.HIGH,
        decisionSource = MatchDecisionSource.AUTOMATIC,
        resolverVersion = 1,
        matchedAt = 1L,
        isPresent = isPresent,
        evidenceDisplayName = "Owned copy",
        evidenceTitleKey = "owned-copy",
        evidenceDeveloperKey = "developer",
        evidenceReleaseYear = 2026,
        evidenceAppType = CanonicalAppType.GAME,
    )

    private fun preference(
        canonicalId: String,
        key: OwnedCopyKey,
        titleOverride: String? = null,
        artworkOverrideJson: String? = null,
        updatedAt: Long,
    ) = CanonicalGamePreferenceEntity(
        canonicalId = canonicalId,
        preferredAccountScope = key.accountScope.value,
        preferredSource = key.source,
        preferredStableSourceId = key.stableSourceId,
        titleOverride = titleOverride,
        artworkOverrideJson = artworkOverrideJson,
        updatedAt = updatedAt,
    )

    private fun installPreferenceWriteFailureTrigger(canonicalId: String) {
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_preference_write
            BEFORE UPDATE ON canonical_game_preference
            WHEN OLD.canonical_id = '$canonicalId'
            BEGIN
                SELECT RAISE(ABORT, 'injected preference failure');
            END
            """.trimIndent(),
        )
    }

    private fun assertValidationFailure(failure: Throwable?) {
        assertTrue(failure is IllegalArgumentException)
        assertEquals(
            "Preferred copy is not a present member of the canonical game",
            failure?.message,
        )
    }

    private fun assertInjectedFailure(failure: Throwable?) {
        assertNotNull(failure)
        assertTrue(
            generateSequence(failure) { throwable -> throwable.cause }
                .any { throwable ->
                    throwable.message?.contains("injected preference failure") == true
                },
        )
    }

    private companion object {
        const val CANONICAL_A = "11111111-1111-1111-1111-111111111111"
        const val CANONICAL_B = "22222222-2222-2222-2222-222222222222"
        val SCOPE_A = "a".repeat(64)
        val SCOPE_B = "b".repeat(64)
    }
}
