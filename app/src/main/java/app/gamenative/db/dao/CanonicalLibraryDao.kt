package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalGamePreferenceEntity
import app.gamenative.data.canonical.StoreMatchEntity
import kotlinx.coroutines.flow.Flow

data class CanonicalLibraryAggregate(
    @Embedded val game: CanonicalGameEntity,
    @Relation(
        parentColumn = "canonical_id",
        entityColumn = "canonical_id",
    )
    val matches: List<StoreMatchEntity>,
    @Relation(
        parentColumn = "canonical_id",
        entityColumn = "canonical_id",
    )
    val preferences: List<CanonicalGamePreferenceEntity>,
) {
    fun preferenceOrNull(): CanonicalGamePreferenceEntity? = preferences.singleOrNull()
}

@Dao
interface CanonicalLibraryDao {
    @Transaction
    @Query(
        """
        SELECT * FROM canonical_game
        WHERE EXISTS (
            SELECT 1 FROM store_match
            WHERE store_match.canonical_id = canonical_game.canonical_id
              AND store_match.is_present = 1
        )
        ORDER BY canonical_game.canonical_id
        """,
    )
    fun observePresentGames(): Flow<List<CanonicalLibraryAggregate>>
}
