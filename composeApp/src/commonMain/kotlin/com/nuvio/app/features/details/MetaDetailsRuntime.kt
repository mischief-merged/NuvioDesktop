package com.nuvio.app.features.details

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
