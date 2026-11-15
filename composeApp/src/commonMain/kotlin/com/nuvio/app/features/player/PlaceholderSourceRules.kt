package com.nuvio.app.features.player

/*
 * Rules that tell a provider's placeholder clip ("not ready", "failed", "rate limited") from the video,
 * without a frame. They add to the duration rule in SourceFailoverPlanner.kt. Each one must be something a
 * real release is very unlikely to match, because a match ends a source the user may want: so a word counts
 * only when it is the whole file name or a whole folder, never part of a title, and never in the host.
 */

/** Which rule marked a source as a placeholder. For the log only. */
internal enum class PlaceholderSignal {
    /** The link says in its query or path that it is a status or error video. */
    SelfDescribingLink,

    /** The link carries a rate limit notice. */
    RateLimitNotice,

    /** The link points at one of a provider's known static status clips. */
    StaticStatusClip,

    /** The file is named exactly like one of those clips. */
    StatusClipFileName,
}

/** An http(s) link split into what the rules look at. The host is dropped on purpose. */
private class LinkParts(val folders: List<String>, val file: String, val query: List<Pair<String, String>>)

private fun splitLink(url: String): LinkParts? {
    val lower = url.trim().lowercase()
    val afterScheme = when {
        lower.startsWith("https://") -> lower.substring(8)
        lower.startsWith("http://") -> lower.substring(7)
        else -> return null
    }
    val noFragment = afterScheme.substringBefore('#')
    val pathPart = noFragment.substringBefore('?').substringAfter('/', "")
    val segments = pathPart.split('/').filter { it.isNotEmpty() }
    val query = noFragment.substringAfter('?', "")
        .split('&')
        .filter { it.isNotEmpty() }
        .map { pair -> pair.substringBefore('=').let(::decodeQueryText) to pair.substringAfter('=', "").let(::decodeQueryText) }
    return LinkParts(segments.dropLast(1), segments.lastOrNull().orEmpty(), query)
}

private fun decodeQueryText(text: String): String = text.replace('+', ' ').replace("%20", " ")

/** Clips whose name alone is enough: a release is never called this. */
private val STATUS_CLIP_NAMES = setOf(
    "downloading.mp4",
    "download_failed.mp4",
    "no_matching_file.mp4",
    "payment_required.mp4",
    "store_limit_exceeded.mp4",
    "content_proxy_limit_reached.mp4",
    "unavailable_for_legal_reasons.mp4",
)

/** HTTP status codes used as clip names. They say something only inside a `static` folder of a link. */
private val STATUS_CODE_CLIP_NAMES = setOf("200.mp4", "401.mp4", "403.mp4", "429.mp4", "500.mp4")

private val DIAGNOSTIC_FILE = Regex("(error|status|failed)\\.[a-z0-9]{2,5}")

private fun isRateLimitText(text: String): Boolean =
    text.trim() == "too many requests" || "rate-limiting" in text

/** The file name as a bare lower case name, without any folder. */
private fun bareName(fileName: String?): String =
    fileName.orEmpty().trim().lowercase().substringAfterLast('/').substringAfterLast('\\')

/** Whether [fileName] is exactly one of the provider status clips. A title that contains the word does not match. */
internal fun isStatusClipFileName(fileName: String?): Boolean = bareName(fileName) in STATUS_CLIP_NAMES

/**
 * The rule that marks a source as a placeholder by its resolved [url] and the file names known for it
 * (any may be null), or null when none applies. It needs no frame and no network.
 */
internal fun placeholderSignalFor(url: String, fileNames: List<String?>): PlaceholderSignal? {
    val link = splitLink(url)
    if (link != null) {
        if (link.query.any { (key, value) -> (key == "title" || key == "body" || key == "message") && isRateLimitText(value) }) {
            return PlaceholderSignal.RateLimitNotice
        }
        val keys = link.query.map { it.first }
        val describesItself = ("title" in keys && ("body" in keys || "message" in keys)) || "error" in keys ||
            link.folders.any { it == "error" || it == "status" } || DIAGNOSTIC_FILE.matches(link.file)
        if (describesItself) return PlaceholderSignal.SelfDescribingLink
        val inStaticFolder = link.folders.lastOrNull() == "static"
        if (inStaticFolder && (link.file in STATUS_CLIP_NAMES || link.file in STATUS_CODE_CLIP_NAMES)) {
            return PlaceholderSignal.StaticStatusClip
        }
    }
    return if (fileNames.any(::isStatusClipFileName)) PlaceholderSignal.StatusClipFileName else null
}

/** A file this small can be a placeholder... */
internal const val PLACEHOLDER_SIZE_CEILING_BYTES = 64L * 1024L * 1024L

/** ...when it is also this many times smaller than the size the release declared. */
internal const val PLACEHOLDER_SIZE_FACTOR = 10L

/**
 * Whether the file really served is too small to be what the release declared. It needs both sizes: with
 * either unknown it claims nothing. A wrong declared size on a file of normal size never matches, because
 * the served file must itself be under [PLACEHOLDER_SIZE_CEILING_BYTES].
 */
internal fun servedFileTooSmallForDeclaredSize(servedBytes: Long?, declaredBytes: Long?): Boolean {
    val served = servedBytes?.takeIf { it > 0L } ?: return false
    val declared = declaredBytes?.takeIf { it > 0L } ?: return false
    return served < PLACEHOLDER_SIZE_CEILING_BYTES && served * PLACEHOLDER_SIZE_FACTOR < declared
}

private const val WAIT_CLIP_MS = 120_000L
private const val WAIT_CLIP_SLACK_MS = 1_500L

/**
 * The two minute "being prepared" clip: a file of about 120 seconds when playback was to resume later than its
 * end. Such a resume point cannot belong to that file, which keeps the rule narrow.
 */
internal fun isWaitClipForResume(durationMs: Long, resumeMs: Long): Boolean =
    kotlin.math.abs(durationMs - WAIT_CLIP_MS) <= WAIT_CLIP_SLACK_MS && resumeMs > durationMs + 1_000L
