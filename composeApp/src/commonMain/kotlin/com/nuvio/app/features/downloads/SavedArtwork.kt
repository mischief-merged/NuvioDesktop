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
 * (too large, not an image, refused, unreachable) a marker `<role>.none` saying why is written
 * instead; see [ArtworkMarker] for when it is looked at again.
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
    private val fetchImage: suspend (url: String) -> ImageFetch = { url -> fetchImageFromServer(url) },
    private val nowEpochMs: () -> Long = { DownloadsClock.nowEpochMs() },
) : DownloadCompanion {
    override val name: String get() = "artwork"

    override fun isDue(item: DownloadItem, localVideoUri: String): Boolean =
        ArtworkRole.values().any { role -> SavedArtwork.urlFor(item, role) != null && needs(localVideoUri, role) }

    private fun needs(localVideoUri: String, role: ArtworkRole): Boolean {
        if (SavedArtwork.fileUri(localVideoUri, role) != null) return false
        val marker = ArtworkMarker.read(localVideoUri, role) ?: return true
        val age = nowEpochMs() - marker.atEpochMs
        if (age < 0) return true
        return age >= if (marker.answered) SavedArtwork.RecheckAgeMs else DownloadSubtitles.cooldownAfter(marker.attempts)
    }

    override suspend fun run(item: DownloadItem, localVideoUri: String): CompanionOutcome {
        var saved = false
        var failed = false
        for (role in ArtworkRole.values()) {
            val url = SavedArtwork.urlFor(item, role) ?: continue
            if (!needs(localVideoUri, role)) continue
            val fetched = try {
                fetchImage(url)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ImageFetch.Unreachable
            }
            // What to keep: an image, or a marker saying why there is none.
            val bytes = (fetched as? ImageFetch.Bytes)?.bytes
            val extension = bytes?.takeIf { it.isNotEmpty() && it.size <= SavedArtwork.MaxBytes }?.let(SavedArtwork::extensionOf)
            val reason = when {
                fetched is ImageFetch.Unreachable -> ArtworkMarker.Reason.Unreachable
                fetched is ImageFetch.Rejected -> ArtworkMarker.Reason.Refused
                bytes != null && bytes.size > SavedArtwork.MaxBytes -> ArtworkMarker.Reason.TooLarge
                extension == null -> ArtworkMarker.Reason.NotAnImage
                else -> null
            }
            val outcome = SidecarLocks.withLock(localVideoUri) {
                if (!DownloadCompanionGuard.stillWanted(localVideoUri)) return@withLock null
                val storage = DownloadSubtitleStorage(localVideoUri)
                // Read again under the lock: another run may have saved this role since the look before the fetch.
                // A saved image is never replaced.
                if (SavedArtwork.fileUri(localVideoUri, role) != null) return@withLock true
                runCatching {
                    if (reason == null) {
                        storage.writeBytes("${role.fileStem}.$extension", bytes!!)
                        true
                    } else {
                        ArtworkMarker.write(localVideoUri, role, reason, nowEpochMs())
                        // A server that did not answer is a failure to retry soon; one that answered is a settled "no".
                        if (reason == ArtworkMarker.Reason.Unreachable) null else false
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

/** What asking the server for an image gave. */
internal sealed interface ImageFetch {
    /** A successful response. Its bytes are exactly the body, and may still not be an image or be too large. */
    class Bytes(val bytes: ByteArray) : ImageFetch

    /** The server answered and has no image to give (not found, forbidden...). A settled answer. */
    data object Rejected : ImageFetch

    /** No answer: no connection, a timeout, a server error, a rate limit. Says nothing about the image. */
    data object Unreachable : ImageFetch
}

/**
 * Why an image was not saved, kept in `<role>.none` as small JSON: `{"v":2,"reason":...,"attempts":n,"at":ms}`.
 * Markers of an earlier format (just a number) are ignored, which gives the image one fresh try.
 * A reason that is a settled answer (refused, not an image, too large) is looked at again after
 * [SavedArtwork.RecheckAgeMs]; "unreachable" is tried again soon, after a cooldown that grows.
 */
internal data class ArtworkMarker(val reason: Reason, val attempts: Int, val atEpochMs: Long) {
    enum class Reason(val code: String) {
        Refused("refused"), NotAnImage("not-an-image"), TooLarge("too-large"), Unreachable("unreachable"),
    }

    val answered: Boolean get() = reason != Reason.Unreachable

    companion object {
        const val Version = 2
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun read(localVideoUri: String, role: ArtworkRole): ArtworkMarker? {
            val text = runCatching { DownloadSubtitleStorage(localVideoUri).read("${role.fileStem}.none") }.getOrNull() ?: return null
            return decode(text)
        }

        internal fun decode(text: String): ArtworkMarker? = try {
            val obj = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
            val version = obj?.get("v")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
            if (obj == null || version != Version) null else {
                val code = (obj["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val reason = Reason.values().firstOrNull { it.code == code }
                val at = (obj["at"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                val attempts = (obj["attempts"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 1
                if (reason == null || at == null) null else ArtworkMarker(reason, attempts.coerceAtLeast(1), at)
            }
        } catch (error: Exception) {
            null
        }

        fun write(localVideoUri: String, role: ArtworkRole, reason: Reason, nowEpochMs: Long) {
            val previous = read(localVideoUri, role)
            val attempts = if (reason == Reason.Unreachable && previous?.reason == Reason.Unreachable) previous.attempts + 1 else 1
            val body = "{\"v\":$Version,\"reason\":\"${reason.code}\",\"attempts\":$attempts,\"at\":$nowEpochMs}"
            DownloadSubtitleStorage(localVideoUri).write("${role.fileStem}.none", body)
        }
    }
}

private const val ImageAccept = "image/jpeg,image/png,image/webp,image/gif;q=0.9,*/*;q=0.5"

/**
 * Asks the server for an image, with a body of at most [SavedArtwork.MaxBytes] (one more byte is read
 * to tell "too large"). Redirects are followed. The bytes come back exactly as sent: the response
 * carries them apart from its decoded text. A request that cannot be made is [ImageFetch.Unreachable].
 */
internal suspend fun fetchImageFromServer(url: String): ImageFetch {
    val response = try {
        withTimeoutOrNull(20_000L) {
            httpRequestRaw(
                method = "GET", url = url, headers = mapOf("Accept" to ImageAccept), body = "",
                maxResponseBodyBytes = SavedArtwork.MaxBytes + 1,
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    } ?: return ImageFetch.Unreachable
    return when {
        response.status in 200..299 -> ImageFetch.Bytes(response.bodyBytes)
        response.status == 408 || response.status == 429 || response.status in 500..599 -> ImageFetch.Unreachable
        response.status in 400..499 -> ImageFetch.Rejected
        else -> ImageFetch.Unreachable
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
