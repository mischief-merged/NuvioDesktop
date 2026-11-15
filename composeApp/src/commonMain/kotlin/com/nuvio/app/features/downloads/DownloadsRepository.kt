package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.SubtitleRequestExtras
import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.streams.StreamItem
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object DownloadsRepository {
    private const val MaxDownloadAttempts = 3

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private val _hasUnseenCompleted = MutableStateFlow(false)
    val hasUnseenCompleted: StateFlow<Boolean> = _hasUnseenCompleted.asStateFlow()

    // The UI, downloader callbacks and background workers reach this object from different threads.
    // Every read-modify-write of the item list, and every use of activeTransfers, holds this lock.
    private val stateLock = SynchronizedObject()

    // The transfer that currently owns each download. A transfer that was paused, replaced or removed
    // is no longer in this map, so whatever it reports afterwards is ignored.
    private val activeTransfers = mutableMapOf<String, ActiveTransfer>()
    private var hasLoaded = false

    // Where items are persisted and transfers and files are handled. Always the platform in the app.
    internal var backend: DownloadsBackend = PlatformDownloadsBackend

    private var nextDownloadOrdinal = 0L

    // What the stored payload held that this build does not use: fields of an item it does not
    // know, by download id, and items it could not read at all. Both are written back unchanged,
    // so a payload written by another build of the app loses nothing by being loaded here.
    private var loadedPayload: DownloadsCodec.Decoded? = null

    // True when a stored payload could neither be read nor be put aside. Nothing is saved then, so
    // that payload is still there for a later launch.
    private var savingIsUnsafe = false

    // The downloads that were started again when the list was loaded, until someone asks for them.
    private var downloadsResumedAtLoad: Set<String> = emptySet()

    fun ensureLoaded(): Unit = synchronized(stateLock) {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun markCompletedSeen() {
        _hasUnseenCompleted.value = false
    }

    fun clearLocalState(): Unit = synchronized(stateLock) {
        val transfers = activeTransfers.values.toList()
        activeTransfers.clear()
        transfers.forEach(ActiveTransfer::cancel)
        hasLoaded = false
        _hasUnseenCompleted.value = false
        _uiState.value = DownloadsUiState()
        notifyLiveStatusPlatform()
    }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? {
        ensureLoaded()
        val normalizedVideoId = videoId?.trim().orEmpty()
        if (normalizedVideoId.isBlank()) return null
        return _uiState.value.items.firstOrNull { item ->
            item.videoId == normalizedVideoId && item.hasPlayableLocalFile()
        }
    }

    fun findPlayableDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? {
        ensureLoaded()
        val items = _uiState.value.items
        val normalizedParentMetaId = parentMetaId.trim()

        findPlayableDownloadByVideoId(videoId)?.let { return it }

        return if (seasonNumber != null && episodeNumber != null) {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == seasonNumber &&
                    item.episodeNumber == episodeNumber &&
                    item.hasPlayableLocalFile()
            }
        } else {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == null &&
                    item.episodeNumber == null &&
                    item.hasPlayableLocalFile()
            }
        }
    }

    /** The finished download whose file is [localFileUri], in either spelling of a file address. */
    internal fun findCompletedDownloadByLocalFile(localFileUri: String): DownloadItem? {
        ensureLoaded()
        val wanted = localFileUri.localFilePath().takeIf { it.isNotBlank() } ?: return null
        return _uiState.value.items.firstOrNull { item ->
            item.status == DownloadStatus.Completed && (
                item.localFileUri?.localFilePath() == wanted ||
                    backend.resolveLocalFileUri(item.localFileUri, item.fileName)?.localFilePath() == wanted
                )
        }
    }

    fun playableLocalFileUri(item: DownloadItem): String? {
        ensureLoaded()
        if (item.status != DownloadStatus.Completed) return null
        val resolvedUri = backend.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return null

        if (resolvedUri != item.localFileUri) {
            mutateItem(item.id) { current ->
                if (current.fileName == item.fileName) {
                    current.copy(
                        localFileUri = resolvedUri,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                } else {
                    current
                }
            }
        }

        return resolvedUri
    }

    fun enqueueFromStream(
        contentType: String,
        videoId: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        episodeThumbnail: String?,
        stream: StreamItem,
        autoDownloadRuleKey: String? = null,
    ): DownloadEnqueueResult = synchronized(stateLock) {
        ensureLoaded()

        val sourceUrl = stream.playableDirectUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return DownloadEnqueueResult.MissingUrl

        if (!sourceUrl.isSupportedDownloadUrl()) {
            return DownloadEnqueueResult.UnsupportedFormat
        }
        // Nobody looks at the address of a download that starts by itself, so it may only go to a
        // public host.
        if (autoDownloadRuleKey != null && !sourceUrl.hasPublicDownloadHost()) {
            return DownloadEnqueueResult.UnsupportedFormat
        }

        val now = DownloadsClock.nowEpochMs()
        val logicalKey = buildLogicalKey(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )

        var replacedExisting = false
        val currentItems = _uiState.value.items.toMutableList()
        val existing = currentItems.firstOrNull { it.logicalContentKey == logicalKey }
        if (existing != null) {
            replacedExisting = true
            activeTransfers.remove(existing.id)?.cancel()
            backend.removeFile(playableLocalFileUri(existing) ?: existing.localFileUri)
            backend.removePartialFile(existing.fileName)
            currentItems.removeAll { it.id == existing.id }
        }

        val downloadId = nextDownloadId(now)
        val fileName = buildFileName(
            title = title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            fallbackTitle = stream.streamLabel,
            sourceUrl = sourceUrl,
            downloadId = downloadId,
        )

        val item = DownloadItem(
            id = downloadId,
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = videoId,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizeResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            subtitleRequests = addonSubtitleRequests(
                type = contentType,
                videoId = videoId,
                extras = SubtitleRequestExtras.from(stream.behaviorHints, sourceUrl),
            ),
            sourceSubtitles = stream.externalSubtitles,
            localFileUri = null,
            fileName = fileName,
            status = DownloadStatus.Downloading,
            downloadedBytes = 0L,
            totalBytes = null,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            expectedVideoHash = stream.behaviorHints.videoHash.asVideoHash(),
            expectedSizeBytes = stream.behaviorHints.videoSize?.takeIf { it > 0L },
            autoDownloadRuleKey = autoDownloadRuleKey,
        )

        currentItems.add(0, item)
        publish(currentItems)
        persist()
        startDownload(item)

        return if (replacedExisting) {
            DownloadEnqueueResult.Replaced
        } else {
            DownloadEnqueueResult.Started
        }
    }

    fun pauseDownload(downloadId: String): Unit = synchronized(stateLock) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Downloading) return

        activeTransfers.remove(downloadId)?.cancel()
        mutateItem(downloadId) { current ->
            current.copy(
                status = DownloadStatus.Paused,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                errorMessage = null,
            )
        }
    }

    fun pauseActiveDownloads() {
        ensureLoaded()
        _uiState.value.items
            .filter { it.status == DownloadStatus.Downloading }
            .map { it.id }
            .forEach(::pauseDownload)
    }

    fun resumeDownload(downloadId: String): Unit = synchronized(stateLock) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return

        val reset = item.copy(
            status = DownloadStatus.Downloading,
            errorMessage = null,
            localFileUri = null,
            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
        )

        replaceItem(reset)
        persist()
        startDownload(reset)
    }

    fun retryDownload(downloadId: String) {
        resumeDownload(downloadId)
    }

    internal fun reattachBackgroundDownload(downloadId: String): Unit = synchronized(stateLock) {
        if (!hasLoaded) return
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        activeTransfers.remove(downloadId)?.cancel()
        val restored = backend.restoreItem(item)
        replaceItem(restored)
        persist()
        if (restored.status == DownloadStatus.Downloading) startDownload(restored)
    }

    fun cancelDownload(downloadId: String): Unit = synchronized(stateLock) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return

        activeTransfers.remove(downloadId)?.cancel()
        backend.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
        backend.removePartialFile(item.fileName)

        publish(_uiState.value.items.filterNot { it.id == downloadId })
        persist()
    }

    private fun loadFromDisk(): Unit = synchronized(stateLock) {
        _hasUnseenCompleted.value = false
        hasLoaded = true
        loadedPayload = null
        savingIsUnsafe = false
        val payload = backend.loadPayload().orEmpty().trim()
        var decoded = if (payload.isEmpty()) null else DownloadsCodec.decode(payload)
        if (decoded == null && payload.isNotEmpty()) {
            // A payload that cannot be read is put aside before anything can be saved in its place,
            // and the one from before the last write is read instead when there is one. That one
            // is fetched first: putting the payload aside is a write, after which the copy from
            // before the last write is no longer this one.
            val previous = runCatching { backend.loadPreviousPayload() }.getOrNull()
            savingIsUnsafe = runCatching { backend.saveUnreadablePayload(payload, DownloadsClock.nowEpochMs()) }.isFailure
            decoded = previous
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(DownloadsCodec::decode)
        }
        if (decoded == null) {
            _uiState.value = DownloadsUiState()
            notifyLiveStatusPlatform()
            return
        }
        loadedPayload = decoded

        var shouldPersistNormalized = false
        val normalized = decoded.items
            .map { item ->
                val statusNormalized = backend.restoreItem(item)

                val localUriNormalized = normalizeCompletedLocalFileUri(statusNormalized).withoutLostFile()
                if (localUriNormalized != item) {
                    shouldPersistNormalized = true
                }
                localUriNormalized
            }

        _uiState.value = DownloadsUiState(normalized)
        notifyLiveStatusPlatform()
        if (shouldPersistNormalized) {
            persist()
        }
        val resumed = normalized.filter { it.status == DownloadStatus.Downloading && it.id !in activeTransfers }
        downloadsResumedAtLoad = downloadsResumedAtLoad + resumed.map { it.id }
        resumed.forEach(::startDownload)
    }

    private fun startDownload(item: DownloadItem, attempt: Int = 1): Unit = synchronized(stateLock) {
        val transfer = ActiveTransfer()
        // Registered before the downloader is asked to start, so a report made before start returns
        // already finds this transfer.
        activeTransfers[item.id] = transfer

        val handle = backend.start(
            DownloadPlatformRequest(item, onDigestVerified = { transfer.digestVerified = true }),
            { downloadedBytes, totalBytes ->
                ifCurrent(item.id, transfer) {
                    mutateItem(item.id) { current ->
                        if (current.status != DownloadStatus.Downloading) {
                            current
                        } else {
                            current.copy(
                                downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                                totalBytes = totalBytes?.takeIf { it > 0L },
                                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                                errorMessage = null,
                            )
                        }
                    }
                }
            },
            { localFileUri, totalBytes ->
                ifCurrent(item.id, transfer) {
                    activeTransfers.remove(item.id)
                    // A download is finished only when its file is there, has the size the server
                    // announced and is a video. Anything else is a failed download without a file.
                    val facts = backend.inspectFile(localFileUri)
                    val problem = facts?.problem(announcedBytes = totalBytes, expectedVideoHash = item.expectedVideoHash)
                    if (problem != null) backend.removeFile(localFileUri)
                    mutateItem(item.id) { current ->
                        if (current.status != DownloadStatus.Downloading) return@mutateItem current
                        if (problem != null) return@mutateItem current.failedBecauseOf(problem)
                        current.copy(
                            verifiedBy = facts?.verification(
                                // Only a size the server announced while the transfer ran counts.
                                announcedBytes = current.totalBytes,
                                expectedVideoHash = item.expectedVideoHash,
                                expectedSizeBytes = item.expectedSizeBytes,
                                digestVerified = transfer.digestVerified,
                            ) ?: if (transfer.digestVerified) DownloadVerification.Digest else null,
                            status = DownloadStatus.Completed,
                            localFileUri = localFileUri,
                            downloadedBytes = if (totalBytes != null && totalBytes > 0L) {
                                totalBytes
                            } else {
                                current.downloadedBytes
                            },
                            totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                            errorMessage = null,
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        )
                    }
                }
            },
            { message ->
                ifCurrent(item.id, transfer) {
                    activeTransfers.remove(item.id)
                    val current = _uiState.value.items.firstOrNull { it.id == item.id }
                    if (current?.status == DownloadStatus.Downloading && attempt < MaxDownloadAttempts) {
                        startDownload(current, attempt + 1)
                    } else {
                        mutateItem(item.id) { failed ->
                            if (failed.status != DownloadStatus.Downloading) {
                                failed
                            } else {
                                failed.copy(
                                    status = DownloadStatus.Failed,
                                    errorMessage = message.ifBlank { runBlocking { getString(Res.string.download_failed) } },
                                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                                )
                            }
                        }
                    }
                }
            },
            {
                ifCurrent(item.id, transfer) {
                    activeTransfers.remove(item.id)
                    mutateItem(item.id) { current ->
                        if (current.status != DownloadStatus.Downloading) return@mutateItem current
                        current.copy(status = DownloadStatus.Paused, errorMessage = null)
                    }
                }
            },
        )
        transfer.attach(handle)
    }

    /** The downloads that went on by themselves when the list was loaded. Each is handed out once. */
    internal fun takeDownloadsResumedAtLoad(): Set<String> = synchronized(stateLock) {
        downloadsResumedAtLoad.also { downloadsResumedAtLoad = emptySet() }
    }

    /** Notes the audio language playback of a download starts on. */
    internal fun setPreferredAudioLanguage(downloadId: String, language: String) {
        ensureLoaded()
        mutateItem(downloadId) { it.copy(preferredAudioLanguage = language) }
    }

    /** The audio language that was noted for the finished download with this file, if any. */
    fun preferredAudioLanguageOfFile(localFileUri: String): String? =
        _uiState.value.items
            .firstOrNull { it.status == DownloadStatus.Completed && it.localFileUri == localFileUri }
            ?.preferredAudioLanguage

    /** A look at the file of a finished download. Null when it has none or the platform cannot look. */
    internal fun inspectCompletedDownload(item: DownloadItem): DownloadedFileFacts? {
        if (item.status != DownloadStatus.Completed) return null
        val fileUri = backend.resolveLocalFileUri(item.localFileUri, item.fileName) ?: item.localFileUri ?: return null
        return backend.inspectFile(fileUri)
    }

    /**
     * Whether a finished download still has its file. One whose file is gone, or smaller than the
     * download was, becomes a failed download, which can be tried again, and is not counted as
     * downloaded. True for a platform that cannot look at the file.
     */
    internal fun verifyCompletedDownload(downloadId: String): Boolean = synchronized(stateLock) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return false
        if (item.status != DownloadStatus.Completed) return false
        val checked = item.withoutLostFile()
        if (checked.status == DownloadStatus.Completed) return true
        mutateItem(downloadId) { checked }
        false
    }

    /** This finished download, or the failed download it has become because its file is lost. */
    private fun DownloadItem.withoutLostFile(): DownloadItem {
        if (status != DownloadStatus.Completed) return this
        val fileUri = backend.resolveLocalFileUri(localFileUri, fileName) ?: localFileUri ?: return this
        val facts = backend.inspectFile(fileUri) ?: return this
        val problem = when {
            !facts.exists -> DownloadedFileProblem.Missing
            totalBytes != null && facts.sizeBytes < totalBytes -> DownloadedFileProblem.Incomplete
            else -> return this
        }
        return failedBecauseOf(problem)
    }

    private fun DownloadItem.failedBecauseOf(problem: DownloadedFileProblem): DownloadItem = copy(
        status = DownloadStatus.Failed,
        localFileUri = null,
        errorMessage = runBlocking {
            getString(
                when (problem) {
                    DownloadedFileProblem.Missing -> Res.string.downloads_error_file_missing
                    DownloadedFileProblem.Incomplete -> Res.string.downloads_error_file_incomplete
                    DownloadedFileProblem.NotVideo -> Res.string.downloads_error_file_not_video
                    DownloadedFileProblem.ChecksumMismatch -> Res.string.downloads_error_file_checksum
                },
            )
        },
        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
    )

    /** Runs [block] under the state lock, and only while [transfer] still owns the download. */
    private inline fun ifCurrent(downloadId: String, transfer: ActiveTransfer, block: () -> Unit) {
        synchronized(stateLock) {
            if (activeTransfers[downloadId] === transfer) block()
        }
    }

    private fun mutateItem(downloadId: String, transform: (DownloadItem) -> DownloadItem): Unit = synchronized(stateLock) {
        var changed = false
        val updated = _uiState.value.items.map { item ->
            if (item.id == downloadId) {
                changed = true
                transform(item)
            } else {
                item
            }
        }

        if (changed) {
            publish(updated)
            persist()
        }
    }

    private fun replaceItem(item: DownloadItem): Unit = synchronized(stateLock) {
        val updated = _uiState.value.items.map { existing ->
            if (existing.id == item.id) item else existing
        }
        publish(updated)
    }

    private fun publish(items: List<DownloadItem>) {
        val previousStatuses = _uiState.value.items.associate { it.id to it.status }
        if (items.any { it.status == DownloadStatus.Completed && previousStatuses[it.id].let { status -> status != null && status != DownloadStatus.Completed } }) {
            _hasUnseenCompleted.value = true
        }
        _uiState.value = DownloadsUiState(
            items = items,
        )
        notifyLiveStatusPlatform()
    }

    private fun notifyLiveStatusPlatform() {
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items)
        }
    }

    private fun persist() {
        if (savingIsUnsafe) return
        backend.savePayload(DownloadsCodec.encode(_uiState.value.items, loadedPayload))
    }

    internal fun nextDownloadId(nowEpochMs: Long): String {
        val ordinal = synchronized(stateLock) {
            nextDownloadOrdinal += 1L
            nextDownloadOrdinal
        }
        return buildString {
            append(nowEpochMs.toString(36))
            append('_')
            append(ordinal.toString(36))
        }
    }

    private fun normalizeCompletedLocalFileUri(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.Completed) return item
        val resolvedUri = backend.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return item
        return if (resolvedUri != item.localFileUri) {
            item.copy(localFileUri = resolvedUri)
        } else {
            item
        }
    }

    private fun DownloadItem.hasPlayableLocalFile(): Boolean =
        status == DownloadStatus.Completed &&
            backend.resolveLocalFileUri(
                localFileUri = localFileUri,
                destinationFileName = fileName,
            ) != null
}

private class ActiveTransfer {
    private var handle: DownloadsTaskHandle? = null
    private var cancelled = false

    /** Set by the platform downloader when the body matched a digest the server sent. */
    @Volatile
    var digestVerified = false

    /** A transfer cancelled before its handle is known is cancelled as soon as the handle arrives. */
    fun attach(handle: DownloadsTaskHandle) {
        this.handle = handle
        if (cancelled) handle.cancel()
    }

    fun cancel() {
        cancelled = true
        handle?.cancel()
    }
}

internal interface DownloadsBackend {
    fun loadPayload(): String?
    fun savePayload(payload: String)

    /** The payload from before the last one that was saved. Null where no such copy is kept. */
    fun loadPreviousPayload(): String? = null

    /** Keeps a payload that could not be read, apart from the one [savePayload] writes. */
    fun saveUnreadablePayload(payload: String, atEpochMs: Long) = Unit
    fun restoreItem(item: DownloadItem): DownloadItem
    fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle
    fun removeFile(localFileUri: String?): Boolean
    fun removePartialFile(destinationFileName: String): Boolean
    fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String?

    /** Looks at the file of a download. Null where that cannot be done. */
    fun inspectFile(localFileUri: String): DownloadedFileFacts? = null
}

private object PlatformDownloadsBackend : DownloadsBackend {
    override fun loadPayload(): String? = DownloadsStorage.loadPayload()

    override fun savePayload(payload: String) = DownloadsStorage.savePayload(payload)

    override fun loadPreviousPayload(): String? = DownloadsStorage.loadPreviousPayload()

    override fun saveUnreadablePayload(payload: String, atEpochMs: Long) =
        DownloadsStorage.saveUnreadablePayload(payload, atEpochMs)

    override fun restoreItem(item: DownloadItem): DownloadItem = DownloadsPlatformDownloader.restoreItem(item)

    override fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle = DownloadsPlatformDownloader.start(request, onProgress, onSuccess, onFailure, onPaused)

    override fun removeFile(localFileUri: String?): Boolean = DownloadsPlatformDownloader.removeFile(localFileUri)

    override fun removePartialFile(destinationFileName: String): Boolean =
        DownloadsPlatformDownloader.removePartialFile(destinationFileName)

    override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
        DownloadsPlatformDownloader.resolveLocalFileUri(localFileUri, destinationFileName)

    override fun inspectFile(localFileUri: String): DownloadedFileFacts? = inspectDownloadedFile(localFileUri)
}

/**
 * Reads and writes the stored list of downloads so that no build of the app destroys what another
 * build wrote.
 *
 * Reading: every item is read on its own. Fields this build does not know are ignored, a value it
 * does not know for a field that has a default becomes that default, and an item that still cannot
 * be read is set aside without affecting the others. Writing: the fields an item was stored with
 * that this build does not know are written back with it, and the items it could not read are
 * written back as they were.
 */
private object DownloadsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    /** One readable record: as it was stored, and as this build writes what it read from it. */
    class StoredRecord(val stored: JsonObject, val readAs: JsonObject)

    class Decoded(
        val items: List<DownloadItem>,
        /** Everything in the payload beside the list itself. */
        val root: JsonObject,
        /** Every readable record, by download id. */
        val records: Map<String, StoredRecord>,
        val unreadableItems: List<JsonElement>,
    )

    /**
     * Null when the payload as a whole is not a list of downloads this app could have written: not
     * an object, or an object with content but without a list of items.
     */
    fun decode(payload: String): Decoded? {
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
        val stored = when (val items = root["items"]) {
            null -> if (root.isEmpty()) emptyList() else return null
            is JsonArray -> items
            else -> return null
        }
        val items = mutableListOf<DownloadItem>()
        val records = mutableMapOf<String, StoredRecord>()
        val unreadable = mutableListOf<JsonElement>()
        stored.forEach { element ->
            val item = runCatching { json.decodeFromJsonElement<DownloadItem>(element.withCurrentAutoDownloadFields()) }.getOrNull()
            if (item == null || item.id in records) {
                unreadable += element
            } else {
                items += item
                records[item.id] = StoredRecord(element as JsonObject, json.encodeToJsonElement(item) as JsonObject)
            }
        }
        return Decoded(items, root, records, unreadable)
    }

    /**
     * A record of an automatic download as an earlier build wrote it: the rule in `ruleKey` beside
     * `origin` AUTO. It is read as the same download with its rule in the field used now. The
     * earlier fields stay in the stored record, so that build still reads what this one writes.
     */
    private fun JsonElement.withCurrentAutoDownloadFields(): JsonElement {
        val record = this as? JsonObject ?: return this
        if ("autoDownloadRuleKey" in record) return this
        val origin = (record["origin"] as? JsonPrimitive)?.contentOrNull
        val ruleKey = (record["ruleKey"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (origin != "AUTO" || ruleKey == null) return this
        return JsonObject(record + ("autoDownloadRuleKey" to JsonPrimitive(ruleKey)))
    }

    /**
     * [items] as a payload. A field of a record is written only when this build changed it since
     * the record was read; otherwise the stored value stays, also one this build read as a default
     * because it did not know it. A record that could not be read because its id was taken is
     * dropped once the download with that id is gone, so it does not come back in its place.
     */
    fun encode(items: Collection<DownloadItem>, loaded: Decoded?): String {
        val ids = items.mapTo(mutableSetOf()) { it.id }
        val written = items.map { item ->
            val fields = json.encodeToJsonElement(item) as JsonObject
            val record = loaded?.records?.get(item.id) ?: return@map fields
            JsonObject(record.stored + fields.filter { (name, value) -> name !in record.stored || record.readAs[name] != value })
        }
        val unreadable = loaded?.unreadableItems.orEmpty().filterNot { element ->
            val id = ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
            id != null && id in loaded?.records.orEmpty() && id !in ids
        }
        return JsonObject(loaded?.root.orEmpty() + ("items" to JsonArray(written + unreadable))).toString()
    }
}

private fun sanitizeRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun sanitizeResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun buildLogicalKey(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = if (seasonNumber != null && episodeNumber != null) {
    "${parentMetaId.trim()}|$seasonNumber|$episodeNumber"
} else {
    "${parentMetaId.trim()}|movie"
}

private fun buildFileName(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    fallbackTitle: String,
    sourceUrl: String,
    downloadId: String,
): String {
    val baseTitle = if (seasonNumber != null && episodeNumber != null) {
        buildString {
            append(title)
            append(" S")
            append(seasonNumber.toString().padStart(2, '0'))
            append('E')
            append(episodeNumber.toString().padStart(2, '0'))
            if (!episodeTitle.isNullOrBlank()) {
                append(' ')
                append(episodeTitle)
            }
        }
    } else {
        title.ifBlank { fallbackTitle }
    }

    val extension = sourceUrl.fileExtensionFromUrl()
    return buildString {
        append(baseTitle.sanitizeFileName().ifBlank { "download" }.take(92))
        append('_')
        append(downloadId)
        append('.')
        append(extension)
    }
}

private fun String.sanitizeFileName(): String =
    trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")

private fun String.fileExtensionFromUrl(): String {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val suffix = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .trim()

    return if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
        suffix
    } else {
        "mp4"
    }
}

/** A file address without its scheme and leading slashes, so file:/a and file:///a compare equal. */
private fun String.localFilePath(): String = trim().removePrefix("file:").trimStart('/')

internal fun String.isSupportedDownloadUrl(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.startsWith("magnet:")) return false
    if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) return false
    if (normalized.endsWith(".mpd") || normalized.contains(".mpd?")) return false
    if (normalized.endsWith(".torrent") || normalized.contains(".torrent?")) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}
