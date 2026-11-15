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

    private val seasonEpisodeMarker = Regex("""(?i)(?<![a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,3})(?![0-9])""")
    private val crossMarker = Regex("""(?<![0-9a-zA-Z])(\d{1,2})[xX](\d{2,3})(?![0-9])""")

    // What may follow a first episode number to name more episodes: E07, -E13, -13, &E02 for SxxEyy,
    // and -06, -2x06, x06, &06 for NxNN. The number must end the token, so 720p and 10bit are not episodes.
    // The number may be followed by another E (S02E06E07) but not by other letters (720p, 10bit) or by
    // a dot and one digit (5.1; but not .1080p).
    private val seasonEpisodeTail = Regex("""^[ ._]?(-|&|[eE])[ ._]?[eE]?(\d{1,3})(?![0-9a-df-zA-DF-Z]|\.[0-9](?![0-9]))""")
    private val crossTail = Regex("""^(-|&|[xX])[ ._]?(?:(\d{1,2})[xX])?(\d{2,3})(?![0-9a-df-zA-DF-Z]|\.[0-9](?![0-9]))""")
    private const val MaxExpandedRange = 300

    private val nameSeparator = Regex("[^A-Za-z0-9]+")
    private val leadingArticles = setOf("the", "a", "an")

    /** A dash names a range only upwards; a smaller number after it is something else (`-5.1`). */
    private fun MutableSet<Int>.addRange(from: Int, to: Int): Boolean {
        if (to <= from) return false
        if (to - from <= MaxExpandedRange) addAll(from..to) else add(to)
        return true
    }

    /**
     * Every season and episode marker in [name]: `S02E06` and `2x06`, with the chains and ranges
     * that follow them (`S02E06E07`, `S02E01-E13`, `S02E01&E02`, `2x05-06`, `2x05-2x06`, `2x05x06`).
     * Empty when the name states none.
     */
    fun episodeClaims(name: String): List<EpisodeClaim> {
        val claims = mutableListOf<EpisodeClaim>()
        var consumed = 0
        for (match in seasonEpisodeMarker.findAll(name)) {
            if (match.range.first < consumed) continue
            val season = match.groupValues[1].toInt()
            var last = match.groupValues[2].toInt()
            val episodes = mutableSetOf(last)
            var rest = name.substring(match.range.last + 1)
            while (true) {
                val tail = seasonEpisodeTail.find(rest) ?: break
                val next = tail.groupValues[2].toInt()
                if (tail.groupValues[1] == "-") {
                    if (!episodes.addRange(last, next)) break
                } else {
                    episodes.add(next)
                }
                last = next
                rest = rest.substring(tail.range.last + 1)
            }
            consumed = name.length - rest.length
            claims += EpisodeClaim(season, episodes)
        }
        consumed = 0
        for (match in crossMarker.findAll(name)) {
            if (match.range.first < consumed) continue
            val season = match.groupValues[1].toInt()
            var last = match.groupValues[2].toInt()
            val episodes = mutableSetOf(last)
            var rest = name.substring(match.range.last + 1)
            while (true) {
                val tail = crossTail.find(rest) ?: break
                val otherSeason = tail.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
                if (otherSeason != null && otherSeason != season) break
                val next = tail.groupValues[3].toInt()
                if (tail.groupValues[1] == "-") {
                    if (!episodes.addRange(last, next)) break
                } else {
                    episodes.add(next)
                }
                last = next
                rest = rest.substring(tail.range.last + 1)
            }
            consumed = name.length - rest.length
            claims += EpisodeClaim(season, episodes)
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

    private fun words(text: String) = text.lowercase().split(nameSeparator).filter { it.isNotEmpty() }

    /** [tokens] without the wanted title at their start, when one of [titles] is there. */
    private fun withoutLeadingTitle(tokens: List<String>, titles: List<String>): List<String> {
        var dropped = 0
        for (title in titles) {
            val full = words(title)
            for (candidate in listOf(full, full.dropWhile { it in leadingArticles }.takeIf { full.firstOrNull() in leadingArticles })) {
                if (candidate.isNullOrEmpty() || candidate.size > tokens.size) continue
                if (tokens.subList(0, candidate.size) == candidate) dropped = maxOf(dropped, candidate.size)
            }
        }
        return tokens.drop(dropped)
    }

    /**
     * The year [name] states, or null when it states none or the numbers are ambiguous. Only a
     * whole token counts, so `2160p`, `x265` and the `1920` of `1920x1080` do not. The wanted
     * [titles] are removed from the front first, because a title can be a number (`1917`,
     * `2001 A Space Odyssey`, `Blade Runner 2049`, `2012`), and a year-like number that opens the
     * name without a known title is left alone as likely part of the title. When several different
     * year-like numbers remain the name is ambiguous and states no year.
     */
    fun releaseYear(name: String, titles: List<String> = emptyList()): Int? {
        val all = words(name)
        val tokens = withoutLeadingTitle(all, titles).let { rest ->
            if (rest.size == all.size && rest.firstOrNull()?.isYear() == true) rest.drop(1) else rest
        }
        val years = tokens.filter { it.isYear() }.map { it.toInt() }.distinct()
        return years.singleOrNull()
    }

    private fun String.isYear() = length == 4 && all(Char::isDigit) && toInt() in 1900..2099

    /** True when [name] states a year that is more than [tolerance] years from [wantedYear]. */
    fun namesOtherYear(name: String?, wantedYear: Int?, titles: List<String> = emptyList(), tolerance: Int = 1): Boolean {
        if (name.isNullOrBlank() || wantedYear == null) return false
        val year = releaseYear(name, titles) ?: return false
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
