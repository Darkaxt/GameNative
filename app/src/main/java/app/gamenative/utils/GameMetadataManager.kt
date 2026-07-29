package app.gamenative.utils

import java.io.File
import kotlin.reflect.KClass
import timber.log.Timber
import org.json.JSONObject

/**
 * Data class representing the metadata stored in a .gamenative file.
 * This metadata is used for all game sources (Custom Games, Steam, etc.).
 */
data class GameMetadata(
    val appId: Int,
    val steamgriddbFetched: Boolean = false,
    val releaseDate: Long? = null
)

internal sealed interface ReadOnlyAppIdResult {
    data class Present(val appId: Int) : ReadOnlyAppIdResult
    data object MissingOrInvalid : ReadOnlyAppIdResult
    data class ReadFailure(val errorClass: KClass<out Throwable>) : ReadOnlyAppIdResult
}

/**
 * Utility for reading and writing game metadata to/from .gamenative files.
 * Consolidates all game metadata (appId, SteamGridDB fetch status, release date) into a single JSON file.
 * Works for all game sources (Custom Games, Steam, and future sources).
 */
object GameMetadataManager {
    private const val FILE_NAME = ".gamenative"
    private val LEGACY_APP_ID = Regex("[1-9][0-9]*")

    /**
     * Reads metadata from the .gamenative file in the given folder.
     * Returns null if the file doesn't exist or is invalid.
     */
    fun read(folder: File): GameMetadata? {
        val gamenativeFile = File(folder, FILE_NAME)
        if (!gamenativeFile.exists() || !gamenativeFile.isFile) {
            return null
        }

        return try {
            val content = gamenativeFile.readText().trim()
            if (content.isEmpty()) {
                return null
            }
            
            // Try to parse as JSON
            try {
                val json = JSONObject(content)
                
                // Handle legacy format: if it's just a number, treat it as appId only
                if (json.length() == 1 && json.has("appId")) {
                    val appId = json.optInt("appId", -1)
                    if (appId > 0) {
                        return GameMetadata(appId = appId)
                    }
                }
                
                // Parse full metadata
                val appId = json.optInt("appId", -1)
                if (appId <= 0) {
                    return null
                }
                
                val steamgriddbFetched = json.optBoolean("steamgriddbFetched", false)
                val releaseDate = json.optLong("releaseDate", -1).takeIf { it > 0 }
                
                GameMetadata(
                    appId = appId,
                    steamgriddbFetched = steamgriddbFetched,
                    releaseDate = releaseDate
                )
            } catch (e: Exception) {
                // If not JSON, try parsing as plain integer (legacy format)
                val appId = content.toIntOrNull()?.takeIf { it > 0 }
                if (appId != null) {
                    // Migrate legacy format to new format
                    val metadata = GameMetadata(appId = appId)
                    write(folder, metadata)
                    return metadata
                }
                null
            }
        } catch (e: Exception) {
            Timber.tag("GameMetadataManager").w(e, "Failed to read .gamenative file: ${gamenativeFile.absolutePath}")
            null
        }
    }

    /**
     * Writes metadata to the .gamenative file in the given folder.
     * Merges with existing metadata if the file already exists.
     */
    fun write(folder: File, metadata: GameMetadata) {
        val gamenativeFile = File(folder, FILE_NAME)
        try {
            // Read existing metadata to merge
            val existing = read(folder)
            val merged = existing?.let { existingMetadata ->
                GameMetadata(
                    appId = metadata.appId, // appId always takes precedence
                    steamgriddbFetched = metadata.steamgriddbFetched || existingMetadata.steamgriddbFetched,
                    releaseDate = metadata.releaseDate ?: existingMetadata.releaseDate
                )
            } ?: metadata
            
            val json = JSONObject().apply {
                put("appId", merged.appId)
                if (merged.steamgriddbFetched) {
                    put("steamgriddbFetched", true)
                }
                if (merged.releaseDate != null) {
                    put("releaseDate", merged.releaseDate)
                }
            }
            
            gamenativeFile.writeText(json.toString())
            Timber.tag("GameMetadataManager").d("Wrote metadata to ${gamenativeFile.absolutePath}: appId=${merged.appId}, fetched=${merged.steamgriddbFetched}, releaseDate=${merged.releaseDate}")
        } catch (e: Exception) {
            Timber.tag("GameMetadataManager").w(e, "Failed to write .gamenative file: ${gamenativeFile.absolutePath}")
        }
    }

    /**
     * Updates specific fields in the metadata without overwriting others.
     * If appId is not provided and no existing metadata exists, the update will fail.
     * For new metadata, use write() instead.
     */
    fun update(folder: File, appId: Int? = null, steamgriddbFetched: Boolean? = null, releaseDate: Long? = null) {
        val existing = read(folder)
        val finalAppId = appId ?: existing?.appId
        if (finalAppId == null) {
            Timber.tag("GameMetadataManager").w("Cannot update metadata: no appId provided and no existing metadata found for ${folder.absolutePath}")
            return
        }
        
        val updated = GameMetadata(
            appId = finalAppId
        ).let { base ->
            base.copy(
                steamgriddbFetched = steamgriddbFetched ?: existing?.steamgriddbFetched ?: false,
                releaseDate = releaseDate ?: existing?.releaseDate
            )
        }
        write(folder, updated)
    }

    /**
     * Gets the appId from metadata.
     * Returns null if the metadata doesn't exist or doesn't contain a valid appId.
     */
    fun getAppId(folder: File): Int? {
        return read(folder)?.appId
    }

    /** Reads an existing app ID without migrating or writing metadata. */
    internal fun getAppIdReadOnly(folder: File): Int? =
        (readAppIdReadOnly(folder) as? ReadOnlyAppIdResult.Present)?.appId

    internal fun readAppIdReadOnly(folder: File): ReadOnlyAppIdResult = try {
        val metadataFile = File(folder, FILE_NAME)
        if (!metadataFile.exists() || !metadataFile.isFile) {
            ReadOnlyAppIdResult.MissingOrInvalid
        } else {
            readAppIdReadOnly(metadataFile::readText)
        }
    } catch (error: Exception) {
        ReadOnlyAppIdResult.ReadFailure(error::class)
    }

    internal fun readAppIdReadOnly(readText: () -> String): ReadOnlyAppIdResult = try {
        parseAppIdContent(readText())
            ?.let { ReadOnlyAppIdResult.Present(it) }
            ?: ReadOnlyAppIdResult.MissingOrInvalid
    } catch (error: Exception) {
        ReadOnlyAppIdResult.ReadFailure(error::class)
    }

    internal fun parseAppIdReadOnly(readText: () -> String): Int? = try {
        parseAppIdContent(readText())
    } catch (_: Exception) {
        null
    }

    private fun parseAppIdContent(content: String): Int? {
        if (content.isEmpty()) return null
        val jsonAppId = try {
            when (val value = JSONObject(content).opt("appId")) {
                is Int -> value.takeIf { it > 0 }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        return jsonAppId ?: content
            .takeIf { LEGACY_APP_ID.matches(it) }
            ?.toIntOrNull()
            ?.takeIf { it > 0 && it.toString() == content }
    }

    /**
     * Checks if SteamGridDB images have been fetched for this game.
     */
    fun isSteamGridDBFetched(folder: File): Boolean {
        return read(folder)?.steamgriddbFetched ?: false
    }

    /**
     * Gets the release date from metadata.
     */
    fun getReleaseDate(folder: File): Long? {
        return read(folder)?.releaseDate
    }
}

