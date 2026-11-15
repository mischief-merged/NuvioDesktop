package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.AddonSubtitle
import com.nuvio.app.features.player.loadAddonSubtitles
import com.nuvio.app.features.player.SubtitleAddonRequest
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** What one call to [DownloadSubtitles.prepare] did. */
internal data class SubtitleSaveResult(
    /** Subtitle files saved for the video now, old and new. */
    val saved: Int,
    /** Files saved by this call. */
    val newlySaved: Int,
    /** Subtitles that were tried and could not be fetched, as opposed to ones that were not subtitles. */
    val unreachable: Int,
)

internal object DownloadSubtitles {
    private val json = Json { ignoreUnknownKeys = true }

    /** Fetches the text of a subtitle. Replaced in tests. */
    internal var fetchText: suspend (url: String, headers: Map<String, String>) -> String =
        { url, headers -> httpGetTextWithHeaders(url, headers) }

    /** Asks the subtitle add-ons. Replaced in tests. */
    internal var loadAddon: suspend (List<SubtitleAddonRequest>) -> List<AddonSubtitle> =
        { requests -> loadAddonSubtitles(requests) }

    /** A look that found no subtitle at all is repeated only after this long. */
    const val RECHECK_EMPTY_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** Whether the item names anywhere to get subtitles from. */
    fun hasSources(item: DownloadItem): Boolean = item.subtitleRequests.isNotEmpty() || item.sourceSubtitles.isNotEmpty()

    /** How many subtitle files are saved for the video. */
    fun savedCount(localVideoUri: String): Int {
        if (!localVideoUri.startsWith("file:")) return 0
        val storage = runCatching { DownloadSubtitleStorage(localVideoUri) }.getOrNull() ?: return 0
        return readManifest(storage).tracks.count { storage.localFileUri(it.fileName) != null }
    }

    /**
     * Whether a look for subtitles is worth making for this video now: never made, not finished,
     * a saved file gone, or nothing found long enough ago. A video whose item names no source never is.
     */
    fun isDue(item: DownloadItem, localVideoUri: String, nowEpochMs: Long): Boolean {
        if (!hasSources(item) || !localVideoUri.startsWith("file:")) return false
        val storage = runCatching { DownloadSubtitleStorage(localVideoUri) }.getOrNull() ?: return false
        val manifest = readManifest(storage)
        val saved = manifest.tracks.count { storage.localFileUri(it.fileName) != null }
        return when {
            !manifest.complete -> true
            saved < manifest.tracks.size -> true
            saved > 0 -> false
            manifest.checkedAtEpochMs <= 0L -> true
            else -> nowEpochMs - manifest.checkedAtEpochMs !in 0 until RECHECK_EMPTY_AGE_MS
        }
    }

    /**
     * Saves the subtitles of [item] beside the video. Never removes one that is saved. With
     * [recheck] a manifest that says "complete" is still looked at again, so that what could not be
     * fetched at the start is tried when the video is done.
     */
    suspend fun prepare(item: DownloadItem, localVideoUri: String, recheck: Boolean = false): SubtitleSaveResult = withContext(Dispatchers.Default) {
        val storage = DownloadSubtitleStorage(localVideoUri)
        val manifest = readManifest(storage)
        val saved = manifest.tracks.filter { storage.localFileUri(it.fileName) != null }.toMutableList()
        if (!recheck && manifest.complete && saved.size == manifest.tracks.size) {
            return@withContext SubtitleSaveResult(saved.size, 0, 0)
        }
        val before = saved.size
        var unreachable = 0
        var nextFileIndex = (saved.maxOfOrNull { it.fileName.substringBefore('.').toIntOrNull() ?: -1 } ?: -1) + 1
        val addonSubtitles = loadAddon(item.subtitleRequests).map {
            StreamSubtitle(url = it.url, language = it.language, name = it.display)
        }
        val pending = (item.sourceSubtitles + addonSubtitles)
            .distinctBy { it.url }
            .filter { subtitle ->
                (subtitle.url.startsWith("https://", true) || subtitle.url.startsWith("http://", true)) &&
                    saved.none { it.sourceUrl == subtitle.url }
            }
        for (batch in pending.chunked(4)) {
            val fetched = coroutineScope {
                batch.map { subtitle ->
                    async {
                        var failed = false
                        val body = try {
                            val text = withTimeoutOrNull(15_000L) {
                                fetchText(subtitle.url, subtitle.headers.orEmpty())
                            }
                            if (text == null) failed = true
                            text?.takeIf { it.isNotBlank() && PlayerSubtitleCueParser.parse(it, subtitle.url).isNotEmpty() }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            // An error status or unreadable text means the server answered; anything else did not.
                            if (error !is IllegalStateException && error !is IllegalArgumentException) failed = true
                            null
                        }
                        Triple(subtitle, body, failed)
                    }
                }.awaitAll()
            }
            for ((subtitle, body, failed) in fetched) {
                currentCoroutineContext().ensureActive()
                if (body == null) {
                    if (failed) unreachable++
                    continue
                }
                val extension = PlayerSubtitleCueParser.fileExtension(body, subtitle.url)
                val fileName = "${nextFileIndex++}.$extension"
                try {
                    storage.write(fileName, body)
                    saved += DownloadedSubtitle(subtitle.url, fileName, subtitle.language, subtitle.name)
                    storage.write("manifest.json", json.encodeToString(SubtitleManifest(tracks = saved.toList())))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                }
            }
        }
        currentCoroutineContext().ensureActive()
        runCatching {
            // A look that met an unreachable server is not stamped, so that it is made again soon.
            val checkedAt = if (unreachable == 0) DownloadsClock.nowEpochMs() else 0L
            storage.write("manifest.json", json.encodeToString(SubtitleManifest(complete = true, tracks = saved, checkedAtEpochMs = checkedAt)))
        }
        SubtitleSaveResult(saved = saved.size, newlySaved = saved.size - before, unreachable = unreachable)
    }

    fun localSubtitles(localVideoUri: String): List<StreamSubtitle> {
        if (!localVideoUri.startsWith("file:")) return emptyList()
        val storage = DownloadSubtitleStorage(localVideoUri)
        return readManifest(storage).tracks.mapNotNull { track ->
            val uri = storage.localFileUri(track.fileName) ?: return@mapNotNull null
            StreamSubtitle(url = uri, language = track.language, name = track.name)
        }
    }

    private fun readManifest(storage: DownloadSubtitleStorage): SubtitleManifest =
        runCatching {
            storage.read("manifest.json")?.let { json.decodeFromString<SubtitleManifest>(it) }
        }.getOrNull() ?: SubtitleManifest()
}

@Serializable
private data class SubtitleManifest(
    val complete: Boolean = false,
    val tracks: List<DownloadedSubtitle> = emptyList(),
    /** When a complete look was made, in milliseconds since 1970. Zero when unknown. */
    val checkedAtEpochMs: Long = 0L,
)

@Serializable
private data class DownloadedSubtitle(
    val sourceUrl: String,
    val fileName: String,
    val language: String,
    val name: String? = null,
)

internal expect class DownloadSubtitleStorage(localVideoUri: String) {
    fun read(fileName: String): String?
    fun write(fileName: String, text: String)
    fun localFileUri(fileName: String): String?
    fun remove()
}
