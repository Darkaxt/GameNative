package app.gamenative.db.converters

import androidx.room.TypeConverter
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod

class CanonicalConverter {
    @TypeConverter
    fun fromGameSource(value: GameSource): String = value.name

    @TypeConverter
    fun toGameSource(value: String): GameSource = GameSource.valueOf(value)

    @TypeConverter
    fun fromCanonicalAppType(value: CanonicalAppType): String = value.name

    @TypeConverter
    fun toCanonicalAppType(value: String): CanonicalAppType = CanonicalAppType.valueOf(value)

    @TypeConverter
    fun fromClassificationState(value: ClassificationState): String = value.name

    @TypeConverter
    fun toClassificationState(value: String): ClassificationState = ClassificationState.valueOf(value)

    @TypeConverter
    fun fromMatchMethod(value: MatchMethod): String = value.name

    @TypeConverter
    fun toMatchMethod(value: String): MatchMethod = MatchMethod.valueOf(value)

    @TypeConverter
    fun fromMatchConfidence(value: MatchConfidence): String = value.name

    @TypeConverter
    fun toMatchConfidence(value: String): MatchConfidence = MatchConfidence.valueOf(value)

    @TypeConverter
    fun fromMatchDecisionSource(value: MatchDecisionSource): String = value.name

    @TypeConverter
    fun toMatchDecisionSource(value: String): MatchDecisionSource = MatchDecisionSource.valueOf(value)
}
