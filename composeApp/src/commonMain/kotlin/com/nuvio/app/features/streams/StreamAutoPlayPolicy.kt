package com.nuvio.app.features.streams

import com.nuvio.app.features.downloads.SeasonDownloadDefaults
import com.nuvio.app.features.downloads.SeasonDownloadDefaultsRepository
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.profiles.ProfileRepository

object StreamAutoPlayPolicy {
    fun isEffectivelyEnabled(settings: PlayerSettingsUiState): Boolean {
        if (settings.streamReuseLastLinkEnabled) return true
        if (settings.streamAutoPlayReuseBingeGroup && settings.streamAutoPlayPreferBingeGroup) return true

        return when (settings.streamAutoPlayMode) {
            StreamAutoPlayMode.MANUAL -> false
            StreamAutoPlayMode.FIRST_STREAM -> true
            StreamAutoPlayMode.REGEX_MATCH -> isRegexSelectionConfigured(settings.streamAutoPlayRegex)
            StreamAutoPlayMode.PREFERRED -> true
        }
    }

    /**
     * The choices [StreamAutoPlayMode.PREFERRED] ranks by: what the active profile saved for
     * downloads. Read from storage only in that mode.
     */
    internal fun preferencesFor(mode: StreamAutoPlayMode): SeasonDownloadDefaults =
        if (mode == StreamAutoPlayMode.PREFERRED) {
            SeasonDownloadDefaultsRepository.load(ProfileRepository.activeProfileId)
        } else {
            SeasonDownloadDefaults()
        }

    fun isRegexSelectionConfigured(regexPattern: String): Boolean {
        val pattern = regexPattern.trim()
        if (pattern.isEmpty() || !pattern.any { it.isLetterOrDigit() }) return false
        return runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.isSuccess
    }
}
