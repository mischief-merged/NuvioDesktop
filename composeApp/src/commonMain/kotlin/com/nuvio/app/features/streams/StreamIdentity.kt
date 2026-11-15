package com.nuvio.app.features.streams

/**
 * What was asked for, as far as it is known, so a source whose release name states something else
 * can be told apart. A null field is not known and never counts against a source. A request with a
 * [season] or an [episode] is an episode request; [year] is used for films only.
 */
internal data class WantedContent(
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
)

/** Why a source is clearly not what was asked for. */
internal enum class IdentityMismatch { Title, Episode, Year }

internal object StreamIdentity {
    /**
     * One entry per source in list order: why that source is clearly not [wanted], or null when it
     * may be. The episode and the year are judged per source from its release name, the title
     * against the whole list (see [ReleaseTitleMatch]). Nothing is removed. The year of a series
     * is never compared: it is the year the show first aired and a release carries its own.
     */
    fun mismatches(streams: List<StreamItem>, wanted: WantedContent?): List<IdentityMismatch?> {
        if (wanted == null) return streams.map { null }
        val isEpisode = wanted.season != null || wanted.episode != null
        val otherTitle = ReleaseTitleMatch.differentTitle(streams, wanted.title)
        return streams.mapIndexed { index, stream ->
            val name = StreamReleaseName.of(stream)
            when {
                FilenameEpisodeMatcher.namesOtherEpisode(name, wanted.season, wanted.episode) -> IdentityMismatch.Episode
                !isEpisode && FilenameEpisodeMatcher.namesOtherYear(name, wanted.year) -> IdentityMismatch.Year
                otherTitle[index] -> IdentityMismatch.Title
                else -> null
            }
        }
    }
}
