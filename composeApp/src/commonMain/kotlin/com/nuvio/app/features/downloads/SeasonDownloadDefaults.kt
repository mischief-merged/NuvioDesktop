package com.nuvio.app.features.downloads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.app.features.debrid.DebridStreamLanguage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** What the user last chose for a season download. A null bitrate or language means no preference. */
@Serializable
internal data class SeasonDownloadDefaults(
    val quality: BatchQuality = BatchQuality.BestAvailable,
    val maxBitrateMbps: Int? = null,
    val audioLanguage: DebridStreamLanguage? = null,
) {
    fun normalized(): SeasonDownloadDefaults = copy(
        maxBitrateMbps = maxBitrateMbps?.takeIf { it in maxBitrateOptions },
        audioLanguage = audioLanguage?.takeIf { it in audioLanguageOptions },
    )

    companion object {
        val maxBitrateOptions: List<Int?> = listOf(null, 4, 8, 10, 15, 25, 40)

        // Only languages a source can be tagged with are offered. MULTI and UNKNOWN describe a
        // source, not a preference.
        val audioLanguageOptions: List<DebridStreamLanguage?> = listOf<DebridStreamLanguage?>(null) +
            DebridStreamLanguage.entries.filter { it != DebridStreamLanguage.MULTI && it != DebridStreamLanguage.UNKNOWN }
    }
}

internal object SeasonDownloadDefaultsRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(profileId: Int): SeasonDownloadDefaults {
        val payload = SeasonDownloadDefaultsStorage.loadPayload(profileId)?.takeIf { it.isNotBlank() }
            ?: return SeasonDownloadDefaults()
        return runCatching { json.decodeFromString<SeasonDownloadDefaults>(payload).normalized() }
            .getOrDefault(SeasonDownloadDefaults())
    }

    fun save(profileId: Int, defaults: SeasonDownloadDefaults) {
        SeasonDownloadDefaultsStorage.savePayload(profileId, json.encodeToString(defaults.normalized()))
    }
}

/**
 * The choices shown in the season download dialog, starting from what [profileId] last confirmed.
 * They are saved only when the download is confirmed, and always for that same profile.
 */
internal class SeasonDownloadChoices(
    val profileId: Int,
    saved: SeasonDownloadDefaults = SeasonDownloadDefaultsRepository.load(profileId),
) {
    var quality by mutableStateOf(saved.quality)
    var maxBitrateMbps by mutableStateOf(saved.maxBitrateMbps)
    var audioLanguage by mutableStateOf(saved.audioLanguage)

    fun confirm(): SeasonDownloadDefaults =
        SeasonDownloadDefaults(quality, maxBitrateMbps, audioLanguage)
            .normalized()
            .also { SeasonDownloadDefaultsRepository.save(profileId, it) }
}
