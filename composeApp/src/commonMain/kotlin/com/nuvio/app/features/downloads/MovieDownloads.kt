package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.parseRuntimeMinutes

/** The season number under which a batch of movies runs. Shows use it for specials, with other ids. */
internal const val MovieBatchSeasonNumber = 0

/** True for a request that downloads one movie, which is reported differently from a season. */
internal val SeasonBatchRequest.isSingleMovie: Boolean
    get() = episodes.size == 1 && episodes.single().movie != null

/**
 * A request that downloads this movie with [choices]: a batch of one item, so it is looked up,
 * queued, retried with the next matching source and limited exactly like an episode of a season.
 * The bitrate limit uses the movie's runtime.
 */
internal fun MetaDetails.movieBatchRequest(choices: SeasonDownloadDefaults): SeasonBatchRequest =
    SeasonBatchRequest(
        contentType = type,
        parentMetaId = id,
        parentMetaType = type,
        title = name,
        logo = logo,
        poster = poster,
        background = background,
        seasonNumber = MovieBatchSeasonNumber,
        episodes = listOf(
            SeasonBatchEpisode(
                videoId = id,
                seasonNumber = MovieBatchSeasonNumber,
                episodeNumber = 1,
                title = null,
                thumbnail = null,
                runtimeMinutes = parseRuntimeMinutes(runtime)?.takeIf { it > 0 },
                movie = SeasonBatchMovie(
                    metaId = id,
                    metaType = type,
                    title = name,
                    logo = logo,
                    poster = poster,
                    background = background,
                ),
            ),
        ),
        quality = choices.quality,
        maxBitrateMbps = choices.maxBitrateMbps,
        audioLanguage = choices.audioLanguage,
    )
