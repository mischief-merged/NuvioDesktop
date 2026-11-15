package com.nuvio.app.features.streams

/**
 * Compares a source's release name with the titles the wanted item is known by. It is only a hint
 * for ordering: it never drops a source, and the caller acts on [TitleVerdict.Different] only when
 * some source in the same list is clearly [TitleVerdict.Same], so a title the metadata gives in
 * another language than the releases changes nothing on its own.
 */
internal enum class TitleVerdict { Same, Different, Unclear }

internal object ReleaseTitleMatch {
    private const val SameShare = 0.75
    private const val DifferentShare = 0.25

    /**
     * One verdict per source in list order. A source is [TitleVerdict.Same] when its name matches any
     * of [titles], [TitleVerdict.Different] only when it differs from every one of them, and
     * [TitleVerdict.Unclear] otherwise. A title that is a single very short word is not judged.
     */
    fun verdicts(streams: List<StreamItem>, titles: List<String>): List<TitleVerdict> {
        val usable = titles.map { titleWords(it, cutAtTechnicalTags = false) }
            .filter { it.isNotEmpty() && !(it.size == 1 && it.first().length < 4) }
            .distinct()
        if (usable.isEmpty()) return streams.map { TitleVerdict.Unclear }
        return streams.map { stream ->
            val name = StreamReleaseName.of(stream)
            val each = usable.map { verdict(name, it) }
            when {
                TitleVerdict.Same in each -> TitleVerdict.Same
                each.all { it == TitleVerdict.Different } -> TitleVerdict.Different
                else -> TitleVerdict.Unclear
            }
        }
    }

    private fun verdict(releaseName: String?, wanted: Set<String>): TitleVerdict {
        val words = titleWords(releaseName.orEmpty().substringAfterLast('/').substringAfterLast('\\'), cutAtTechnicalTags = true)
        if (words.isEmpty()) return TitleVerdict.Unclear
        // "SpiderMan" in a release against "Spider-Man" in the metadata: the words run together.
        val joinedWanted = wanted.joinToString("")
        if (joinedWanted.length >= 4 && words.joinToString("").contains(joinedWanted)) return TitleVerdict.Same
        val share = wanted.count { it in words }.toDouble() / wanted.size
        return when {
            share >= SameShare -> TitleVerdict.Same
            share <= DifferentShare -> TitleVerdict.Different
            else -> TitleVerdict.Unclear
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
