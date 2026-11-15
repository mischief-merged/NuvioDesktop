package com.nuvio.app.features.details

/** Where an episode sits in a show, used by the episode page for its trail and neighbours. */
internal data class EpisodeTrail(
    val showName: String,
    val seasonNumber: Int,
    val episodeNumber: Int?,
    val episodeTitle: String,
)

/** Episodes in the order the season selector shows them: seasons ascending, specials last. */
internal fun MetaDetails.episodesInDisplayOrder(): List<MetaVideo> =
    groupedEpisodesForDisplay().values.flatten()

/** Finds an episode by its video id, or by season and episode number when no id matches. */
internal fun MetaDetails.findEpisode(
    videoId: String?,
    season: Int? = null,
    episode: Int? = null,
): MetaVideo? {
    val episodes = episodesInDisplayOrder()
    if (!videoId.isNullOrBlank()) {
        episodes.firstOrNull { it.id == videoId }?.let { return it }
    }
    if (episode == null) return null
    val wantedSeason = normalizeSeasonNumber(season)
    return episodes.firstOrNull {
        normalizeSeasonNumber(it.season) == wantedSeason && it.episode == episode
    }
}

/** The episode shown before [video], crossing season boundaries. Null at the start or when unknown. */
internal fun MetaDetails.previousEpisode(video: MetaVideo): MetaVideo? {
    val episodes = episodesInDisplayOrder()
    val index = episodes.indexOfFirst { it.id == video.id }
    return if (index > 0) episodes[index - 1] else null
}

/** The episode shown after [video], crossing season boundaries. Null at the end or when unknown. */
internal fun MetaDetails.nextEpisode(video: MetaVideo): MetaVideo? {
    val episodes = episodesInDisplayOrder()
    val index = episodes.indexOfFirst { it.id == video.id }
    return if (index >= 0 && index < episodes.lastIndex) episodes[index + 1] else null
}

/** All episodes of the season [video] belongs to, in display order. */
internal fun MetaDetails.episodesInSeasonOf(video: MetaVideo): List<MetaVideo> =
    groupedEpisodesForDisplay()[normalizeSeasonNumber(video.season)].orEmpty()

internal fun MetaDetails.trailFor(video: MetaVideo): EpisodeTrail =
    EpisodeTrail(
        showName = name,
        seasonNumber = normalizeSeasonNumber(video.season),
        episodeNumber = video.episode,
        episodeTitle = video.title,
    )
