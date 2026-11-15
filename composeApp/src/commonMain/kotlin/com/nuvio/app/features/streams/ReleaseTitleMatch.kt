package com.nuvio.app.features.streams

/**
 * Tells whether a source's release name is clearly another title than the wanted one. It is only a
 * hint for ordering: it never drops a source, and it says nothing unless some source in the same
 * list clearly is the wanted title, so a title the metadata gives in another language than the
 * releases leaves the order alone.
 */
internal object ReleaseTitleMatch {
    private const val SameShare = 0.75
    private const val DifferentShare = 0.25

    private enum class Verdict { Same, Different, Unclear }

    /** One flag per source in list order: true when it is clearly another title. */
    fun differentTitle(streams: List<StreamItem>, wantedTitle: String?): List<Boolean> {
        val wanted = titleWords(wantedTitle.orEmpty(), cutAtTechnicalTags = false)
        if (wanted.isEmpty() || (wanted.size == 1 && wanted.first().length < 4)) return streams.map { false }
        val verdicts = streams.map { verdict(StreamReleaseName.of(it), wanted) }
        if (Verdict.Same !in verdicts) return streams.map { false }
        return verdicts.map { it == Verdict.Different }
    }

    private fun verdict(releaseName: String?, wanted: Set<String>): Verdict {
        val words = titleWords(releaseName.orEmpty().substringAfterLast('/').substringAfterLast('\\'), cutAtTechnicalTags = true)
        if (words.isEmpty()) return Verdict.Unclear
        // "SpiderMan" in a release against "Spider-Man" in the metadata: the words run together.
        if (words.joinToString("").contains(wanted.joinToString("")) && wanted.joinToString("").length >= 4) return Verdict.Same
        val share = wanted.count { it in words }.toDouble() / wanted.size
        return when {
            share >= SameShare -> Verdict.Same
            share <= DifferentShare -> Verdict.Different
            else -> Verdict.Unclear
        }
    }

    private val technicalTag = Regex(
        "(?i)(?<![a-z0-9])(s\\d{1,3}(e\\d{1,3})?|\\d{1,2}x\\d{2,3}|season [0-9]+|(19|20)\\d{2}|(480|576|720|1080|2160)p|4k)(?![a-z0-9])",
    )
    private val fillerWords = setOf("the", "a", "an", "of", "and", "in", "on", "to")
    private val foldedLetters = mapOf(
        'à' to 'a', 'á' to 'a', 'â' to 'a', 'ã' to 'a', 'ä' to 'a', 'å' to 'a',
        'è' to 'e', 'é' to 'e', 'ê' to 'e', 'ë' to 'e',
        'ì' to 'i', 'í' to 'i', 'î' to 'i', 'ï' to 'i',
        'ò' to 'o', 'ó' to 'o', 'ô' to 'o', 'õ' to 'o', 'ö' to 'o',
        'ù' to 'u', 'ú' to 'u', 'û' to 'u', 'ü' to 'u',
        'ç' to 'c', 'ñ' to 'n',
    )
    private val wordSeparator = Regex("[^\\p{L}\\p{N}]+")

    /**
     * The words of a title or of the start of a release name. A release name is cut where the
     * technical tags begin (season marker, year, resolution), because what follows is not the title.
     */
    private fun titleWords(text: String, cutAtTechnicalTags: Boolean): Set<String> {
        val spaced = text.lowercase().map { foldedLetters[it] ?: it }.joinToString("").replace('.', ' ').replace('_', ' ')
        val head = if (cutAtTechnicalTags) technicalTag.find(spaced)?.let { spaced.substring(0, it.range.first) } ?: spaced else spaced
        return head.split(wordSeparator)
            .filter { it.isNotEmpty() && it !in fillerWords && it.any(Char::isLetter) }
            .toSet()
    }
}

/** The name a source gives its release, from the most to the least specific place. */
internal object StreamReleaseName {
    private const val FolderMark = "📁"

    fun of(stream: StreamItem): String? {
        val raw = stream.clientResolve?.stream?.raw
        return listOf(
            raw?.filename,
            stream.clientResolve?.filename,
            stream.behaviorHints.filename,
            raw?.torrentName,
            stream.clientResolve?.torrentName,
            raw?.parsed?.parsedTitle,
            stream.title?.lineSequence()?.firstOrNull { it.isNotBlank() },
            stream.description?.lineSequence()?.firstOrNull { it.trimStart().startsWith(FolderMark) }?.trimStart()?.removePrefix(FolderMark),
        ).firstOrNull { !it.isNullOrBlank() }
    }
}
