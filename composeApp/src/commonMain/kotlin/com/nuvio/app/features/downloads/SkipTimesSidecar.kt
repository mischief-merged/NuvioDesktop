package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.skip.SkipInterval
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Skip-intro times saved beside a downloaded video, so that Skip Intro, Skip Recap and Skip Credits
 * work when the title is played without a connection.
 *
 * Where it lives: inside the sibling folder that holds the saved subtitles of the same file, as
 * `<video file name>.subtitles/skip-times.json`. For `Pantheon.S01E01.mkv` that is
 * `Pantheon.S01E01.mkv.subtitles/skip-times.json`. There is no folder of its own on purpose: the
 * code that deletes, trashes or moves a download already carries `<video file name>.subtitles` with
 * the video, so whatever moves that folder moves this file, and removing it removes this file. Code
 * that moves a video and its siblings can rely on this rule: the sibling folder named after the
 * video file, plus `.subtitles`, belongs to that video and holds everything saved for it. The folder
 * is not a video and never appears in the list of downloads.
 *
 * The file is small JSON: [SkipTimesFile]. Its `version` is checked first. A file with another
 * version, or one that cannot be read, is ignored and never trusted; a file of a newer version is
 * also never overwritten, so an older build cannot destroy what a newer one saved.
 */
internal object SkipTimesSidecar {
    const val FILE_NAME = "skip-times.json"

    /** The format this build reads and writes. */
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(localVideoUri: String): SavedSkipTimesRead {
        if (!localVideoUri.startsWith("file:")) return SavedSkipTimesRead.Missing
        val text = runCatching { DownloadSubtitleStorage(localVideoUri).read(FILE_NAME) }.getOrNull()
            ?: return SavedSkipTimesRead.Missing
        return decode(text)
    }

    /**
     * Writes [times], replacing the file in one step (a temporary file beside it, then a rename), so
     * that a crash leaves the old file or the new one, never half of either. False when nothing was
     * written: the file could not be written, or a newer build owns it.
     */
    fun write(localVideoUri: String, times: SavedSkipTimes): Boolean {
        if (!localVideoUri.startsWith("file:")) return false
        if (read(localVideoUri) is SavedSkipTimesRead.NewerVersion) return false
        val body = json.encodeToString(SkipTimesFile.serializer(), times.toFile())
        return runCatching { DownloadSubtitleStorage(localVideoUri).write(FILE_NAME, body) }.isSuccess
    }

    internal fun decode(text: String): SavedSkipTimesRead = try {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return SavedSkipTimesRead.Unusable
        val version = root["version"]?.jsonPrimitive?.int ?: return SavedSkipTimesRead.Unusable
        when {
            version > VERSION -> SavedSkipTimesRead.NewerVersion
            version < 1 -> SavedSkipTimesRead.Unusable
            else -> {
                val file = json.decodeFromJsonElement<SkipTimesFile>(root)
                if (file.fetchedAtEpochMs <= 0L) SavedSkipTimesRead.Unusable
                else SavedSkipTimesRead.Saved(file.toTimes())
            }
        }
    } catch (error: Exception) {
        SavedSkipTimesRead.Unusable
    }
}

/** The stored form. */
@Serializable
internal data class SkipTimesFile(
    val version: Int = SkipTimesSidecar.VERSION,
    /** When the services were asked, in milliseconds since 1970. */
    val fetchedAtEpochMs: Long,
    /** The services the intervals came from, such as "introdb" or "aniskip". Empty when none were found. */
    val sources: List<String> = emptyList(),
    /** What was found. Empty means the services were asked and had nothing. */
    val intervals: List<SavedSkipInterval> = emptyList(),
    /** False when a service did not answer while this was fetched, so that more may be missing. Absent in older files, meaning true. */
    val complete: Boolean = true,
)

@Serializable
internal data class SavedSkipInterval(
    val startTime: Double,
    val endTime: Double,
    /** As the service named it, for example "intro", "recap", "outro", "op", "ed", "movie-credits". */
    val type: String,
    val provider: String,
)

internal data class SavedSkipTimes(
    val fetchedAtEpochMs: Long,
    val intervals: List<SkipInterval>,
    /** False when only some of the services answered. Such a result is looked up again soon. */
    val complete: Boolean = true,
) {
    /** The services were asked and had nothing. */
    val noneFound: Boolean get() = intervals.isEmpty()

    fun toFile() = SkipTimesFile(
        fetchedAtEpochMs = fetchedAtEpochMs,
        complete = complete,
        sources = intervals.map { it.provider }.distinct(),
        intervals = intervals.map { SavedSkipInterval(it.startTime, it.endTime, it.type, it.provider) },
    )
}

internal sealed interface SavedSkipTimesRead {
    /** No file. */
    data object Missing : SavedSkipTimesRead

    /** A file that cannot be read. It is ignored, and may be replaced. */
    data object Unusable : SavedSkipTimesRead

    /** A file written by a newer build. It is ignored, and is not replaced. */
    data object NewerVersion : SavedSkipTimesRead

    data class Saved(val times: SavedSkipTimes) : SavedSkipTimesRead
}

private fun SkipTimesFile.toTimes() = SavedSkipTimes(
    fetchedAtEpochMs = fetchedAtEpochMs,
    complete = complete,
    // An interval that makes no sense is dropped; the end of an ending may be unbounded, so only the order is checked.
    intervals = intervals.filter { it.startTime.isFinite() && it.startTime >= 0.0 && it.endTime > it.startTime && !it.endTime.isNaN() }
        .map { SkipInterval(it.startTime, it.endTime, it.type, it.provider) },
)
