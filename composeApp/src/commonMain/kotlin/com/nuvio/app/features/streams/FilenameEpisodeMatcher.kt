package com.nuvio.app.features.streams

/**
 * Builds a target filename string by replacing episode/season numbers in the
 * current filename with the next episode's numbers. Used for precise stream
 * matching when binge group alone is ambiguous.
 */
object FilenameEpisodeMatcher {

    fun buildNextFilename(
        filename: String,
        currentSeason: Int?,
        currentEpisode: Int,
        nextSeason: Int?,
        nextEpisode: Int
    ): String? {
        if (filename.isBlank()) return null
        for (pattern in patterns) {
            val result = pattern.replace(filename, currentSeason, currentEpisode, nextSeason, nextEpisode)
            if (result != null) return result
        }
        return null
    }

    fun matches(candidateFilename: String?, expectedFilename: String?): Boolean {
        if (candidateFilename.isNullOrBlank() || expectedFilename.isNullOrBlank()) return false
        return candidateFilename.equals(expectedFilename, ignoreCase = true)
    }

    fun extractSeasonEpisode(filename: String): Pair<Int?, Int>? {
        if (filename.isBlank()) return null
        Regex("""(?i)S\s*(\d{1,3})\s*E\s*(\d{1,4})""").find(filename)?.let { match ->
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull() ?: return null
            return season to episode
        }
        Regex("""(?<!\d)(\d{1,2})[xX](\d{1,4})""").find(filename)?.let { match ->
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull() ?: return null
            return season to episode
        }
        Regex("""(?i)\bE[Pp]?[.\s]?(\d{1,4})\b""").find(filename)?.let { match ->
            val episode = match.groupValues[1].toIntOrNull() ?: return null
            return null to episode
        }
        return null
    }

    /** The season and the episodes one marker in a release name stands for. */
    data class EpisodeClaim(val season: Int, val episodes: Set<Int>)

    private val seasonEpisodeMarker = Regex("""(?i)(?<![a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,3})(?!\d)""")
    private val crossMarker = Regex("""(?<![0-9a-zA-Z])(\d{1,2})[xX](\d{2,3})(?![0-9])""")
    private val episodeTail = Regex("""^[ ._]?(-|&|[eE])[ ._]?[eE]?(\d{1,3})(?!\d)""")
    private val standaloneYear = Regex("""(?<![0-9a-zA-Z])((?:19|20)\d{2})(?![0-9a-zA-Z])""")

    /**
     * Every season and episode marker in [name]: `S02E06`, `2x06`, and the chains and ranges that
     * follow an `S02E06` (`S02E06E07`, `S02E01-E13`, `S02E01-13`, `S02E01&E02`). Empty when the name
     * states none.
     */
    fun episodeClaims(name: String): List<EpisodeClaim> {
        val claims = mutableListOf<EpisodeClaim>()
        for (match in seasonEpisodeMarker.findAll(name)) {
            val season = match.groupValues[1].toInt()
            var last = match.groupValues[2].toInt()
            val episodes = mutableSetOf(last)
            var rest = name.substring(match.range.last + 1)
            while (true) {
                val tail = episodeTail.find(rest) ?: break
                val next = tail.groupValues[2].toInt()
                if (tail.groupValues[1] == "-" && next > last) episodes.addAll(last..next) else episodes.add(next)
                last = next
                rest = rest.substring(tail.range.last + 1)
            }
            claims += EpisodeClaim(season, episodes)
        }
        for (match in crossMarker.findAll(name)) {
            claims += EpisodeClaim(match.groupValues[1].toInt(), setOf(match.groupValues[2].toInt()))
        }
        return claims
    }

    /**
     * True when [name] states season and episode markers and none of them is the wanted one. A name
     * that states none, or a request that does not know both numbers, proves nothing.
     */
    fun namesOtherEpisode(name: String?, wantedSeason: Int?, wantedEpisode: Int?): Boolean {
        if (name.isNullOrBlank() || wantedSeason == null || wantedEpisode == null) return false
        val claims = episodeClaims(name)
        return claims.isNotEmpty() && claims.none { it.season == wantedSeason && wantedEpisode in it.episodes }
    }

    /**
     * The first year in [name] that stands alone between separators. `2160p`, `x265` and the
     * `1920` of `1920x1080` do not count.
     */
    fun releaseYear(name: String): Int? = standaloneYear.find(name)?.groupValues?.get(1)?.toIntOrNull()

    /** True when [name] states a year that is more than [tolerance] years from [wantedYear]. */
    fun namesOtherYear(name: String?, wantedYear: Int?, tolerance: Int = 1): Boolean {
        if (name.isNullOrBlank() || wantedYear == null) return false
        val year = releaseYear(name) ?: return false
        return kotlin.math.abs(year - wantedYear) > tolerance
    }

    private interface EpisodePattern {
        fun replace(filename: String, currentSeason: Int?, currentEpisode: Int, nextSeason: Int?, nextEpisode: Int): String?
    }

    private val sXeY = object : EpisodePattern {
        private val regex = Regex("""(?i)(S)\s*(\d{1,3})\s*(E)\s*(\d{1,4})""")
        override fun replace(filename: String, currentSeason: Int?, currentEpisode: Int, nextSeason: Int?, nextEpisode: Int): String? {
            if (currentSeason == null) return null
            val match = regex.find(filename) ?: return null
            if (match.groupValues[2].toIntOrNull() != currentSeason || match.groupValues[4].toIntOrNull() != currentEpisode) return null
            val seasonWidth = match.groupValues[2].length
            val episodeWidth = match.groupValues[4].length
            val replacement = "${match.groupValues[1]}${(nextSeason ?: currentSeason).toString().padStart(seasonWidth, '0')}${match.groupValues[3]}${nextEpisode.toString().padStart(episodeWidth, '0')}"
            return filename.replaceRange(match.range, replacement)
        }
    }

    // 01x03, 1x03 (negative lookbehind for digit avoids matching 1920x1080)
    private val seasonXepisode = object : EpisodePattern {
        private val regex = Regex("""(?<!\d)(\d{1,2})[xX](\d{1,4})""")
        override fun replace(filename: String, currentSeason: Int?, currentEpisode: Int, nextSeason: Int?, nextEpisode: Int): String? {
            if (currentSeason == null) return null
            val match = regex.find(filename) ?: return null
            if (match.groupValues[1].toIntOrNull() != currentSeason || match.groupValues[2].toIntOrNull() != currentEpisode) return null
            val seasonWidth = match.groupValues[1].length
            val episodeWidth = match.groupValues[2].length
            val sep = filename[match.range.first + match.groupValues[1].length]
            val replacement = "${(nextSeason ?: currentSeason).toString().padStart(seasonWidth, '0')}${sep}${nextEpisode.toString().padStart(episodeWidth, '0')}"
            return filename.replaceRange(match.range, replacement)
        }
    }

    private val episodeOnly = object : EpisodePattern {
        private val regex = Regex("""(?i)\b(E[Pp]?)[.\s]?(\d{1,4})\b""")
        override fun replace(filename: String, currentSeason: Int?, currentEpisode: Int, nextSeason: Int?, nextEpisode: Int): String? {
            val match = regex.find(filename) ?: return null
            if (match.groupValues[2].toIntOrNull() != currentEpisode) return null
            val episodeWidth = match.groupValues[2].length
            val prefix = match.groupValues[1]
            val separator = filename.substring(
                match.range.first + prefix.length,
                match.range.first + prefix.length + (match.value.length - prefix.length - match.groupValues[2].length)
            )
            val replacement = "$prefix$separator${nextEpisode.toString().padStart(episodeWidth, '0')}"
            return filename.replaceRange(match.range, replacement)
        }
    }

    private val patterns = listOf(sXeY, seasonXepisode, episodeOnly)
}
