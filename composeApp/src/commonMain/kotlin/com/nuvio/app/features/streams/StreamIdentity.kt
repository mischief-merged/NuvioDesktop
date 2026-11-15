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
    /** Other names the item goes by (the original title, the addon's own name). */
    val alternativeTitles: List<String> = emptyList(),
    /** How many episodes the wanted season has, when the metadata lists them. */
    val seasonEpisodeCount: Int? = null,
    /** True when season and episode numbers cannot be trusted against release names, as for anime. */
    val numberingUnreliable: Boolean = false,
) {
    /** Every title the item is known by. */
    val titles: List<String>
        get() = (listOfNotNull(title) + alternativeTitles).filter { it.isNotBlank() }.distinct()
}

/** Why a source is clearly not what was asked for. */
internal enum class IdentityMismatch { Title, Episode, Year }

/**
 * Says which sources are clearly not the wanted item. When the evidence is ambiguous it abstains:
 * a source is only flagged on facts that are present on both sides and disagree.
 */
internal object StreamIdentity {
    private val dateNamed = Regex("(?<![0-9])(19|20)\\d{2}[ ._-](0[1-9]|1[0-2])[ ._-](0[1-9]|[12][0-9]|3[01])(?![0-9])")
    private const val ThreeDigitEpisode = 100

    // An episode written with three digits (S01E033, 1x105) is an absolute number, not a seasonal one.
    private val threeDigitMarker = Regex("(?i)(?<![a-z0-9])s\\d{1,2}[ ._-]?e\\d{3}(?![0-9])|(?<![0-9a-z])\\d{1,2}x\\d{3}(?![0-9])")

    /**
     * One entry per source in list order: why that source is clearly not [wanted], or null when it
     * may be. Nothing is removed.
     *  - Episode: the name states season and episode markers and none is the wanted one. Not
     *    judged for a special (season 0), a show with unreliable numbering (anime), a name dated
     *    like a daily show, or a name with an episode number of three digits or beyond the number
     *    of episodes the season has (absolute numbering).
     *  - Year: for a film only, when the name states one year and it is more than a year off.
     *  - Title: the name differs from every title the item is known by, some other source clearly
     *    matches, and the name agrees with the wanted item on nothing else that is present (the
     *    same year for a film, the wanted season and episode for a series).
     */
    fun mismatches(streams: List<StreamItem>, wanted: WantedContent?): List<IdentityMismatch?> {
        if (wanted == null) return streams.map { null }
        val isEpisode = wanted.season != null || wanted.episode != null
        val titles = wanted.titles
        val verdicts = ReleaseTitleMatch.verdicts(streams, titles)
        val titleJudged = TitleVerdict.Same in verdicts
        return streams.mapIndexed { index, stream ->
            val name = StreamReleaseName.of(stream)
            val episodeJudged = name != null && judgesEpisode(name, wanted)
            when {
                episodeJudged && FilenameEpisodeMatcher.namesOtherEpisode(name, wanted.season, wanted.episode) -> IdentityMismatch.Episode
                !isEpisode && FilenameEpisodeMatcher.namesOtherYear(name, wanted.year, titles) -> IdentityMismatch.Year
                titleJudged && verdicts[index] == TitleVerdict.Different &&
                    !agreesElsewhere(name, wanted, episodeJudged) -> IdentityMismatch.Title
                else -> null
            }
        }
    }

    private fun judgesEpisode(name: String, wanted: WantedContent): Boolean {
        val season = wanted.season ?: return false
        if (wanted.episode == null || season == 0 || wanted.numberingUnreliable) return false
        if (dateNamed.containsMatchIn(name) || threeDigitMarker.containsMatchIn(name)) return false
        val limit = wanted.seasonEpisodeCount
        return FilenameEpisodeMatcher.episodeClaims(name).none { claim ->
            claim.episodes.any { it >= ThreeDigitEpisode || (limit != null && it > limit) }
        }
    }

    /** The name states the wanted year (film) or the wanted season and episode (series). */
    private fun agreesElsewhere(name: String?, wanted: WantedContent, episodeJudged: Boolean): Boolean {
        if (name == null) return false
        if (wanted.season != null || wanted.episode != null) {
            val season = wanted.season
            val episode = wanted.episode
            return episodeJudged && season != null && episode != null &&
                FilenameEpisodeMatcher.episodeClaims(name).any { it.season == season && episode in it.episodes }
        }
        val year = wanted.year ?: return false
        return FilenameEpisodeMatcher.releaseYear(name, wanted.titles) == year
    }
}
