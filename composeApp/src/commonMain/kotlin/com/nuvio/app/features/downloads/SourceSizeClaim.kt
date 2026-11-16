package com.nuvio.app.features.downloads

/*
 * The size a source says its file has, read from the text it shows when it does not state one in a
 * structured field. Such text is written for people ("[💾 9.53 GB] Title.2026.1080p.mkv"), so every
 * reading here is careful: a number is taken only with a size unit, a bitrate or a count is never
 * taken, and when the text names more than one size without saying which is the file's, nothing is
 * claimed. A claim that is not there is better than a wrong one: a wrong one makes a good file look
 * like a placeholder.
 */

private const val Kilo = 1000.0
private const val Kibi = 1024.0

/** A size smaller than this is not the size of a video file. */
private const val SmallestClaimBytes = 1_000_000L

/** A size larger than this is not the size of one video file. */
private const val LargestClaimBytes = 1_000_000_000_000L

// A number, then a size unit that is not the start of a longer word and not a rate ("10 Mb/s", "5 Mbps").
private val SizeToken = Regex(
    """(?<![\p{L}\p{N}.,])(\d{1,4}(?:[.,]\d{1,3})?)\s?(TiB|TB|GiB|GB|MiB|MB)(?![\p{L}\p{N}]|\s?/\s?s|\s?ps\b)""",
    RegexOption.IGNORE_CASE,
)

private val SizeWordBefore = Regex("""size\s*[:=\-]?\s*$""", RegexOption.IGNORE_CASE)

private const val DiskSymbol = "💾"

// Text that says the file is one of many: a season pack, a complete series or a collection. The size
// such text states is the size of the whole pack, not of the one file that was fetched from it.
private val PackMarker = Regex(
    """(?<![\p{L}\p{N}])(complete|pack|batch|collection|box\s?set|trilogy|saga|anthology)(?![\p{L}\p{N}])""" +
        """|(?<![\p{L}\p{N}])seasons?[\s._-]*\d{1,2}\s*[-–]\s*\d{1,2}""" +
        """|(?<![\p{L}\p{N}])S\d{1,2}\s*[-–]\s*S?\d{1,2}(?![\p{L}\p{N}])""" +
        """|(?<![\p{L}\p{N}])S\d{1,2}(?![\p{L}\p{N}])(?![\s._-]*E\d)""",
    RegexOption.IGNORE_CASE,
)

/** One size found in a text: [bytes], and whether the text marks it as the size of the file. */
private class SizeMention(val bytes: Long, val marked: Boolean)

private fun unitBytes(unit: String): Double = when (unit.lowercase()) {
    "mb" -> Kilo * Kilo
    "gb" -> Kilo * Kilo * Kilo
    "tb" -> Kilo * Kilo * Kilo * Kilo
    "mib" -> Kibi * Kibi
    "gib" -> Kibi * Kibi * Kibi
    "tib" -> Kibi * Kibi * Kibi * Kibi
    else -> 0.0
}

private fun mentionsIn(text: String): List<SizeMention> = SizeToken.findAll(text).mapNotNull { match ->
    val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
    val bytes = (number * unitBytes(match.groupValues[2])).toLong()
    if (bytes !in SmallestClaimBytes..LargestClaimBytes) return@mapNotNull null
    val before = text.substring((match.range.first - 12).coerceAtLeast(0), match.range.first)
        .trimEnd('[', '(', ' ', '\t', '\uFE0F')
    val marked = before.endsWith(DiskSymbol) || SizeWordBefore.containsMatchIn(before)
    SizeMention(bytes, marked)
}.toList()

/**
 * The size in bytes that [text] states for a file, or null. A size marked with a disk symbol or the
 * word "size" wins over unmarked ones; with no marked size, a single size (however often it is
 * written) is the claim. Several different sizes with no mark to tell them apart claim nothing.
 * Decimal units (GB, MB) count 1000, binary ones (GiB, MiB) 1024, as the source wrote them.
 */
internal fun parseClaimedSizeBytes(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    val mentions = mentionsIn(text)
    val marked = mentions.filter { it.marked }.map { it.bytes }.distinct()
    if (marked.size == 1) return marked.single()
    if (marked.size > 1) return null
    return mentions.map { it.bytes }.distinct().singleOrNull()
}

/** Whether [text] describes a pack of files, whose stated size is not the size of one file. */
internal fun looksLikePack(text: String?): Boolean = !text.isNullOrBlank() && PackMarker.containsMatchIn(text)

/**
 * The size a source claims in its title and description. Null when they state none, are not clear
 * about which size is the file's, or describe a pack, where the size is that of the whole pack.
 */
internal fun claimedSizeFromSourceText(title: String?, subtitle: String?): Long? {
    val text = listOfNotNull(title, subtitle).joinToString("\n")
    if (looksLikePack(text)) return null
    return parseClaimedSizeBytes(text)
}

/**
 * The size the source claims for this download's file: the size it stated, or else the one its text
 * names. Both are kept in [DownloadItem.expectedSizeBytes] where known, and the text is read again
 * for an item that was stored before the text was read.
 */
internal fun DownloadItem.claimedSizeBytes(): Long? =
    expectedSizeBytes?.takeIf { it > 0L } ?: claimedSizeFromSourceText(streamTitle, streamSubtitle)

/** The claim of a source as a size for a sentence: "9.5 GB", in the units the source wrote its size in. */
internal fun formatClaimedSize(bytes: Long): String = formatDecimalSize(bytes)

/** A size in decimal units with one decimal: "8.5 MB", "9.5 GB". */
internal fun formatDecimalSize(bytes: Long): String {
    val value = bytes.toDouble()
    fun one(amount: Double) = "${(amount * 10.0).toLong() / 10.0}"
    return when {
        value >= Kilo * Kilo * Kilo -> "${one(value / (Kilo * Kilo * Kilo))} GB"
        value >= Kilo * Kilo -> "${one(value / (Kilo * Kilo))} MB"
        value >= Kilo -> "${one(value / Kilo)} KB"
        else -> "$bytes B"
    }
}

/** A size as the Downloads list shows it, in units of 1024 under the names KB, MB and GB: "8.1 MB". */
internal fun formatListedSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val value = bytes.toDouble()
    fun one(amount: Double) = "${(amount * 10.0).toInt() / 10.0}"
    return when {
        value >= Kibi * Kibi * Kibi -> "${one(value / (Kibi * Kibi * Kibi))} GB"
        value >= Kibi * Kibi -> "${one(value / (Kibi * Kibi))} MB"
        value >= Kibi -> "${one(value / Kibi)} KB"
        else -> "$bytes B"
    }
}
