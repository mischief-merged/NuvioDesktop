package com.nuvio.app.features.downloads

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The poster and the episode thumbnail of a download, saved beside the video so that the pages that
 * show them work offline. Two small files in the sibling folder `<video file name>.subtitles/`
 * (the rule in [SkipTimesSidecar]), named by role: `poster.<ext>` and `thumbnail.<ext>`, where the
 * extension (jpg, png, webp or gif) is taken from the bytes. Backgrounds and logos are not saved,
 * and an image larger than [SavedArtwork.MaxBytes] is not saved. When an image cannot be saved
 * (too large, not an image) a marker `<role>.none` holding the time of the look is written
 * instead, so that it is not fetched again before [SavedArtwork.RecheckAgeMs].
 */
internal enum class ArtworkRole(val fileStem: String) { Poster("poster"), Thumbnail("thumbnail") }

internal object SavedArtwork {
    /** Larger images are not saved. */
    const val MaxBytes = 1_500_000

    /** An image that could not be saved is looked at again after this long. */
    const val RecheckAgeMs = 30L * 24 * 60 * 60 * 1000

    internal val extensions = listOf("jpg", "png", "webp", "gif")

    fun urlFor(item: DownloadItem, role: ArtworkRole): String? = when (role) {
        ArtworkRole.Poster -> item.poster
        ArtworkRole.Thumbnail -> if (item.isEpisode) item.episodeThumbnail else null
    }?.trim()?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }

    /** The saved file of a role as a file address, or null. */
    fun fileUri(localVideoUri: String, role: ArtworkRole): String? {
        if (!localVideoUri.startsWith("file:")) return null
        val storage = runCatching { DownloadSubtitleStorage(localVideoUri) }.getOrNull() ?: return null
        return extensions.firstNotNullOfOrNull { storage.localFileUri("${role.fileStem}.$it") }
    }

    /** A video and role whose image address is [url]. Memory only: no file is touched. */
    internal fun candidatesFor(url: String, items: List<DownloadItem>): List<Pair<String, ArtworkRole>> {
        val wanted = url.trim()
        return items.mapNotNull { item ->
            val video = item.localFileUri?.takeIf { item.status == DownloadStatus.Completed } ?: return@mapNotNull null
            ArtworkRole.values().firstOrNull { urlFor(item, it) == wanted }?.let { video to it }
        }
    }

    /**
     * The saved file for an image address, among [items]: the address is that of the poster or
     * thumbnail of a finished download and its file is there. Null for any other address. Looks at
     * the disk, so it is called off the main thread.
     */
    fun savedFileFor(url: String, items: List<DownloadItem>): String? =
        candidatesFor(url, items).firstNotNullOfOrNull { (video, role) -> fileUri(video, role) }

    /** The extension for image bytes, or null when they are not an image this build shows. */
    fun extensionOf(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "png"
        bytes.size >= 12 && bytes.decodeToString(0, 4) == "RIFF" && bytes.decodeToString(8, 12) == "WEBP" -> "webp"
        bytes.size >= 6 && bytes.decodeToString(0, 3) == "GIF" -> "gif"
        else -> null
    }

    /**
     * How a request for [url] is served: from the saved file at once while offline, from the network
     * with the saved file as the fallback otherwise, and never from a saved file when none is saved.
     */
    fun plan(offline: Boolean, savedFile: String?): ArtworkPlan = when {
        savedFile == null -> ArtworkPlan.Network
        offline -> ArtworkPlan.SavedFirst
        else -> ArtworkPlan.NetworkThenSaved
    }
}

internal enum class ArtworkPlan { Network, SavedFirst, NetworkThenSaved }

/** Saves the poster and thumbnail of a download. */
internal class ArtworkCompanion(
    private val fetchImage: suspend (url: String) -> ByteArray? = { url -> fetchImageBytes(url) },
    private val nowEpochMs: () -> Long = { DownloadsClock.nowEpochMs() },
) : DownloadCompanion {
    override val name: String get() = "artwork"

    override fun isDue(item: DownloadItem, localVideoUri: String): Boolean =
        ArtworkRole.values().any { role -> SavedArtwork.urlFor(item, role) != null && needs(localVideoUri, role) }

    private fun needs(localVideoUri: String, role: ArtworkRole): Boolean {
        if (SavedArtwork.fileUri(localVideoUri, role) != null) return false
        val marked = runCatching { DownloadSubtitleStorage(localVideoUri).read("${role.fileStem}.none") }.getOrNull()?.trim()?.toLongOrNull()
        if (marked == null) return true
        val age = nowEpochMs() - marked
        return age < 0 || age >= SavedArtwork.RecheckAgeMs
    }

    override suspend fun run(item: DownloadItem, localVideoUri: String): CompanionOutcome {
        var saved = false
        var failed = false
        for (role in ArtworkRole.values()) {
            val url = SavedArtwork.urlFor(item, role) ?: continue
            if (!needs(localVideoUri, role)) continue
            val bytes = try {
                fetchImage(url)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }
            if (bytes == null) { failed = true; continue }
            val extension = bytes.takeIf { it.isNotEmpty() && it.size < SavedArtwork.MaxBytes }?.let(SavedArtwork::extensionOf)
            val outcome = SidecarLocks.withLock(localVideoUri) {
                if (!DownloadCompanionGuard.stillWanted(localVideoUri)) return@withLock null
                val storage = DownloadSubtitleStorage(localVideoUri)
                runCatching {
                    if (extension != null) {
                        storage.writeBytes("${role.fileStem}.$extension", bytes)
                        true
                    } else {
                        // Too large, or not an image: remember that it was looked at.
                        storage.write("${role.fileStem}.none", nowEpochMs().toString())
                        false
                    }
                }.getOrNull()
            }
            when (outcome) {
                true -> saved = true
                null -> failed = true
                false -> Unit
            }
        }
        return when {
            saved -> CompanionOutcome.Saved
            failed -> CompanionOutcome.Failed
            else -> CompanionOutcome.NothingFound
        }
    }
}

/** Fetches an image of at most [SavedArtwork.MaxBytes], or null when the server did not answer. A larger body is returned as empty. */
private suspend fun fetchImageBytes(url: String): ByteArray? {
    val response = withTimeoutOrNull(20_000L) {
        httpRequestRaw(method = "GET", url = url, headers = emptyMap(), body = "", maxResponseBodyBytes = SavedArtwork.MaxBytes + 1)
    } ?: return null
    return when {
        response.status in 500..599 -> null
        response.status !in 200..299 -> ByteArray(0)
        response.bodyBytes.size > SavedArtwork.MaxBytes -> ByteArray(0)
        else -> response.bodyBytes
    }
}

/**
 * Coil interceptor that serves the saved poster or thumbnail of a download. Offline, the saved
 * file is used straight away. Online, the network (and Coil's own cache) comes first, and the saved
 * file is the fallback when it fails. Any other image is untouched.
 */
class SavedArtworkInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        // Memory only: an image that is not a download's poster or thumbnail goes straight on, with no disk access.
        val candidates = SavedArtwork.candidatesFor(url, DownloadsRepository.uiState.value.items)
        if (candidates.isEmpty()) return chain.proceed()
        // The disk is consulted off the main thread, and given a short time; a slow drive just means no saved file.
        suspend fun savedFile(): String? = SidecarIo.read(timeoutMs = 750L) {
            candidates.firstNotNullOfOrNull { (video, role) -> SavedArtwork.fileUri(video, role) }
        }
        suspend fun fromSaved(file: String): ImageResult = chain.withRequest(chain.request.newBuilder().data(file).build()).proceed()
        if (!NetworkStatusRepository.uiState.value.isOfflineLike) {
            // Online the network (and Coil's cache) goes first and is never held up; the disk is looked at only after a failure.
            val result = chain.proceed()
            if (result !is ErrorResult) return result
            return savedFile()?.let { fromSaved(it) } ?: result
        }
        val file = savedFile() ?: return chain.proceed()
        val fromFile = fromSaved(file)
        return if (fromFile is ErrorResult) chain.proceed() else fromFile
    }
}
