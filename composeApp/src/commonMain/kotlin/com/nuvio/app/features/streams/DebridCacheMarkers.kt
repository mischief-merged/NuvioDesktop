package com.nuvio.app.features.streams

/*
 * Whether an add-on says in its own label for a source that the debrid service does not have the file
 * yet. Such a source plays the service's "not cached yet" clip instead of the video. Only the markers
 * add-ons put in the source's name are read, never its description, where the release title is: a film
 * called "The Download" or a release with an arrow in its title must not count.
 *
 *  - A service tag with a download arrow in square brackets, as Comet writes it: "[TB⬇️] Comet 1080p".
 *    Its cached sources carry a lightning bolt instead: "[TB⚡]".
 *  - A service tag followed by "download" in square brackets, as Torrentio writes it: "[RD download]".
 *    Its cached sources read "[RD+]".
 *  - The whole word "uncached" or the words "not cached", unless negated ("uncached: false").
 */

/** A service tag of two to four letters next to a download arrow (with or without the emoji selector), in brackets. */
private val ARROW_TAG = Regex("\\[\\s*(?:[A-Za-z]{2,4}\\s*⬇️?|⬇️?\\s*[A-Za-z]{2,4})\\s*]")

/** A service tag and the word "download", in brackets. */
private val DOWNLOAD_TAG = Regex("\\[\\s*[A-Za-z]{2,4}\\s+download\\s*]", RegexOption.IGNORE_CASE)

/**
 * "uncached" or "not cached" as whole words, unless the text negates them: "not uncached", or a status
 * that follows with false, no, 0 or off ("uncached: false", "uncached=no").
 */
private val NOT_CACHED_WORDS = Regex(
    "(?<![\\p{L}\\p{N}])(?<!not\\s)(?:uncached|not\\s+cached)(?![\\p{L}\\p{N}])" +
        "(?!\\s*[:=]?\\s*(?:false|no|0|off)(?![\\p{L}\\p{N}]))",
    RegexOption.IGNORE_CASE,
)

/** Whether a source's name (its label in the list) says the debrid service does not have the file yet. */
internal fun sourceLabelSaysNotCached(name: String?): Boolean {
    val label = name?.takeIf { it.isNotBlank() } ?: return false
    return ARROW_TAG.containsMatchIn(label) || DOWNLOAD_TAG.containsMatchIn(label) || NOT_CACHED_WORDS.containsMatchIn(label)
}

/**
 * Whether the debrid service is known not to have this source's file yet: the add-on's label says so,
 * or the app's own cache check found it missing. Such a source is tried after the cached ones and is a
 * suspect of playing a placeholder clip.
 */
internal val StreamItem.saysNotCached: Boolean
    get() = debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED || sourceLabelSaysNotCached(name)

/** [streams] with the ones known not to be cached moved behind the others; each part keeps its order. */
internal fun <T> List<T>.cachedFirst(saysNotCached: (T) -> Boolean): List<T> {
    val (notCached, rest) = partition(saysNotCached)
    return if (notCached.isEmpty() || rest.isEmpty()) this else rest + notCached
}
