package com.nuvio.app.features.updater

internal enum class ReleaseNoteSectionKind(val title: String) {
    NEW("New"),
    IMPROVED("Improved"),
    FIXED("Fixed"),
    KNOWN_ISSUES("Known issues"),
    OTHER("Notes"),
}

internal data class ReleaseNoteSection(
    val kind: ReleaseNoteSectionKind,
    val title: String,
    val items: List<String>,
)

internal data class ParsedReleaseNotes(
    val sections: List<ReleaseNoteSection>,
    val headline: String?,
)

data class ReleaseNoteEntry(
    val version: NuvioRoidVersion,
    val versionDisplay: String,
    val tag: String,
    val title: String,
    val notes: String,
    val releaseUrl: String? = null,
)

internal object ReleaseNotesParser {
    private val htmlCommentPattern = Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL))
    private val markdownHeadingPattern = Regex("""^#{1,6}\s+(.+?)\s*#*$""")
    private val boldHeadingPattern = Regex("""^\*\*(.+?)\*\*\s*:?\s*$""")
    private val plainSectionHeadingPattern = Regex(
        """^(New|What'?s\s+new|Added|Features|Improved|Improvements|Changed|Performance|Fixed|Fixes|Bug\s+fixes|Known\s+issues?)\s*:?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val bulletPattern = Regex("""^(?:[-*•]|\d+[.)])\s+(.+)$""")
    private val inlineBoldDoubleStarPattern = Regex("""\*\*([^*]+)\*\*""")
    private val inlineBoldUnderscorePattern = Regex("""__([^_]+)__""")
    private val inlineCodePattern = Regex("""`([^`]+)`""")

    fun parse(markdown: String): ParsedReleaseNotes {
        val withoutComments = htmlCommentPattern.replace(markdown.replace("\r\n", "\n"), "")
        if (withoutComments.isBlank()) {
            return ParsedReleaseNotes(sections = emptyList(), headline = null)
        }

        val orderedBuckets = linkedMapOf<Pair<ReleaseNoteSectionKind, String>, MutableList<String>>()
        var currentKey: Pair<ReleaseNoteSectionKind, String>? = null
        var seenAnySectionOrItem = false

        for (rawLine in withoutComments.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            val headingText = extractHeadingText(trimmed)
            if (headingText != null) {
                val mappedKind = mapSectionKind(headingText)
                if (!seenAnySectionOrItem && mappedKind == null && looksLikeVersionHeader(headingText)) {
                    continue
                }
                val kind = mappedKind ?: ReleaseNoteSectionKind.OTHER
                val title = if (mappedKind != null) mappedKind.title else cleanInlineMarkdown(headingText)
                currentKey = kind to title
                orderedBuckets.getOrPut(currentKey) { mutableListOf() }
                seenAnySectionOrItem = true
                continue
            }

            val targetKey = currentKey ?: (ReleaseNoteSectionKind.OTHER to ReleaseNoteSectionKind.OTHER.title).also {
                currentKey = it
            }
            val bucket = orderedBuckets.getOrPut(targetKey) { mutableListOf() }
            seenAnySectionOrItem = true

            val bulletMatch = bulletPattern.matchEntire(trimmed)
            if (bulletMatch != null) {
                val item = cleanInlineMarkdown(bulletMatch.groupValues[1])
                if (item.isNotEmpty()) {
                    bucket += item
                }
            } else {
                val cleaned = cleanInlineMarkdown(trimmed)
                if (cleaned.isNotEmpty()) {
                    val isContinuation = rawLine.startsWith("  ") || rawLine.startsWith("\t")
                    if (isContinuation && bucket.isNotEmpty()) {
                        bucket[bucket.lastIndex] = "${bucket.last()} $cleaned"
                    } else {
                        bucket += cleaned
                    }
                }
            }
        }

        val sections = orderedBuckets
            .mapNotNull { (key, items) ->
                if (items.isEmpty()) null
                else ReleaseNoteSection(kind = key.first, title = key.second, items = items.toList())
            }

        val headline = listOf(
            ReleaseNoteSectionKind.NEW,
            ReleaseNoteSectionKind.IMPROVED,
            ReleaseNoteSectionKind.FIXED,
            ReleaseNoteSectionKind.OTHER,
            ReleaseNoteSectionKind.KNOWN_ISSUES,
        ).firstNotNullOfOrNull { preferredKind ->
            sections.firstOrNull { it.kind == preferredKind }?.items?.firstOrNull()
        }

        return ParsedReleaseNotes(sections = sections, headline = headline)
    }

    fun selectVersionRange(
        releases: List<ReleaseNoteEntry>,
        installedVersion: NuvioRoidVersion,
        targetVersion: NuvioRoidVersion,
    ): List<ReleaseNoteEntry> {
        if (targetVersion <= installedVersion) return emptyList()
        val byVersion = linkedMapOf<NuvioRoidVersion, ReleaseNoteEntry>()
        for (entry in releases) {
            if (entry.version > installedVersion && entry.version <= targetVersion) {
                val existing = byVersion[entry.version]
                if (existing == null || (existing.notes.isBlank() && entry.notes.isNotBlank())) {
                    byVersion[entry.version] = entry
                }
            }
        }
        return byVersion.values.sortedByDescending { it.version }
    }

    fun formatBannerSummary(entries: List<ReleaseNoteEntry>): String? {
        if (entries.isEmpty()) return null
        if (entries.size == 1) {
            return parse(entries.first().notes).headline
        }
        return entries.joinToString(separator = "\n") { entry ->
            val headline = parse(entry.notes).headline
            if (headline != null) {
                "${entry.versionDisplay}: $headline"
            } else {
                entry.versionDisplay
            }
        }
    }

    private fun extractHeadingText(line: String): String? {
        markdownHeadingPattern.matchEntire(line)?.let { return it.groupValues[1].trim() }
        boldHeadingPattern.matchEntire(line)?.let { return it.groupValues[1].trim() }
        plainSectionHeadingPattern.matchEntire(line)?.let { return it.groupValues[1].trim() }
        return null
    }

    private fun mapSectionKind(heading: String): ReleaseNoteSectionKind? {
        val normalized = heading.trim().trimEnd(':').lowercase().replace(Regex("""\s+"""), " ")
        return when (normalized) {
            "new", "what's new", "whats new", "added", "features" -> ReleaseNoteSectionKind.NEW
            "improved", "improvements", "changed", "performance" -> ReleaseNoteSectionKind.IMPROVED
            "fixed", "fixes", "bug fixes", "bugs" -> ReleaseNoteSectionKind.FIXED
            "known issues", "known issue" -> ReleaseNoteSectionKind.KNOWN_ISSUES
            else -> null
        }
    }

    private fun looksLikeVersionHeader(heading: String): Boolean {
        val lower = heading.lowercase()
        return lower.contains("nuvioroid") || NuvioRoidVersion.parse(heading) != null
    }

    private fun cleanInlineMarkdown(text: String): String {
        var result = text.trim()
        result = inlineBoldDoubleStarPattern.replace(result, "$1")
        result = inlineBoldUnderscorePattern.replace(result, "$1")
        result = inlineCodePattern.replace(result, "$1")
        return result.trim()
    }
}
