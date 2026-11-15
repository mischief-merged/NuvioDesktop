package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.player.AudioLanguageOption
import com.nuvio.app.features.player.normalizeLanguageCode

/**
 * The download audio language that goes with the playback audio language, or null when there is no
 * match: "default" and "original" name no language, and a language that no source is tagged with has
 * no download language. The device language stands in for "device".
 */
internal fun downloadLanguageFromPlayback(
    playbackLanguage: String,
    deviceLanguages: List<String>,
): DebridStreamLanguage? {
    // "Original" means the language of the title, which is not known here, so it names no language
    // even though playback falls back to the device language for it.
    val targets = when (normalizeLanguageCode(playbackLanguage)) {
        null, AudioLanguageOption.DEFAULT, AudioLanguageOption.ORIGINAL -> emptyList()
        AudioLanguageOption.DEVICE -> deviceLanguages
        else -> listOf(playbackLanguage)
    }
    return targets.firstNotNullOfOrNull { target ->
        val code = normalizeLanguageCode(target)?.substringBefore('-') ?: return@firstNotNullOfOrNull null
        SeasonDownloadDefaults.audioLanguageOptions.firstOrNull { it != null && it.code == code }
    }
}

/** What an editor of the download choices starts from, and whether its language came from playback. */
internal data class DownloadChoicesStart(
    val defaults: SeasonDownloadDefaults,
    val languageFromPlayback: Boolean,
)

/**
 * The choices to start from for [profileId]. A profile that has saved choices gets exactly those,
 * whatever they are: a saved "any language" is a choice and is never replaced. A profile that has
 * never saved any starts with the language that goes with its playback audio language, if there is
 * one. Nothing is saved here.
 */
internal fun downloadChoicesStart(
    profileId: Int,
    playbackLanguage: String = AudioLanguageOption.DEFAULT,
    deviceLanguages: List<String> = emptyList(),
): DownloadChoicesStart {
    if (SeasonDownloadDefaultsRepository.hasSaved(profileId)) {
        return DownloadChoicesStart(SeasonDownloadDefaultsRepository.load(profileId), languageFromPlayback = false)
    }
    val language = downloadLanguageFromPlayback(playbackLanguage, deviceLanguages)
    return DownloadChoicesStart(SeasonDownloadDefaults(audioLanguage = language), languageFromPlayback = language != null)
}
