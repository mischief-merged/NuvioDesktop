package com.nuvio.app.features.details

import com.nuvio.app.features.streams.WantedContent

/**
 * The length in minutes of the video [videoId] of this title, when the addon says so: the runtime of
 * the matching episode, or the runtime of the title itself for a movie. Null when it is not known.
 */
internal fun MetaDetails.runtimeMinutesFor(videoId: String, season: Int?, episode: Int?): Int? {
    val video = videos.firstOrNull { it.id == videoId }
        ?: if (season != null && episode != null) {
            videos.firstOrNull { it.season == season && it.episode == episode }
        } else {
            null
        }
    video?.runtime?.takeIf { it > 0 }?.let { return it }
    if (videoId != id || videos.any { it.season != null }) return null
    return parseRuntimeMinutes(runtime)?.takeIf { it > 0 }
}

/**
 * What a source has to be for the video of this title at [season] and [episode]: the title, and for
 * an episode its season and episode. For a film the release year, taken from the first year in
 * [MetaDetails.releaseInfo]. The year of a series is left out: it is the year the show first aired,
 * which a release name of a later episode does not carry.
 */
internal fun MetaDetails.wantedContent(season: Int?, episode: Int?): WantedContent {
    val isEpisode = season != null && episode != null
    val year = if (!isEpisode && type.equals("movie", ignoreCase = true)) {
        Regex("""(?<!\d)((?:19|20)\d{2})(?!\d)""").find(releaseInfo.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    } else {
        null
    }
    val count = videos.count { it.season == season }.takeIf { isEpisode && it > 0 }
    return WantedContent(
        title = name.takeIf { it.isNotBlank() },
        season = season.takeIf { isEpisode },
        episode = episode.takeIf { isEpisode },
        year = year,
        alternativeTitles = alternativeTitles,
        seasonEpisodeCount = count,
        numberingUnreliable = isAnime(),
    )
}

private val animeIdPrefixes = listOf("kitsu", "mal", "anilist", "anidb")

/** The title is known to be anime: it comes from an anime id source or carries the genre. Its numbering follows other rules than release names. */
internal fun MetaDetails.isAnime(): Boolean =
    animeIdPrefixes.any { id.startsWith("$it:", ignoreCase = true) } ||
        type.equals("anime", ignoreCase = true) ||
        genres.any { it.equals("anime", ignoreCase = true) }
