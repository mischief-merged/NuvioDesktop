package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** What the folders need from the list of downloads. The app wires them to [DownloadsRepository]. */
internal interface DownloadsHooks {
    fun items(): List<DownloadItem>

    /** Pauses a running download and waits until it has stopped writing. False when it did not stop. */
    fun pauseAndWait(downloadId: String): Boolean
    fun resume(downloadId: String)

    /** Points the record at its new place and saves it so that it survives a power cut. */
    fun relocate(downloadId: String, locationId: String, localFileUri: String?)
    fun resumeHeld()

    /** The profile whose list this is. A move belongs to the one that started it. */
    fun profileId(): Int = 0

    /** The location and address of every record in the stored list of every profile, the active one included. */
    fun recordsOfAllProfiles(): List<Pair<String?, String?>> = items().map { it.locationId to it.localFileUri }
}

internal object RepositoryDownloadsHooks : DownloadsHooks {
    private val lenient = Json { ignoreUnknownKeys = true }

    override fun items(): List<DownloadItem> {
        DownloadsRepository.ensureLoaded()
        return DownloadsRepository.uiState.value.items
    }

    override fun pauseAndWait(downloadId: String): Boolean = DownloadsRepository.pauseAndWait(downloadId)
    override fun resume(downloadId: String) = DownloadsRepository.resumeDownload(downloadId)

    override fun relocate(downloadId: String, locationId: String, localFileUri: String?) {
        DownloadsRepository.relocateDownload(downloadId, locationId, localFileUri)
        DesktopStorage.store("nuvio_downloads").sync()
    }

    override fun resumeHeld() = DownloadsRepository.resumeHeldByFolder()
    override fun profileId(): Int = ProfileRepository.activeProfileId

    override fun recordsOfAllProfiles(): List<Pair<String?, String?>> {
        val active = items().map { it.locationId to it.localFileUri }
        val store = DesktopStorage.store("nuvio_downloads")
        val activeKey = ProfileScopedKey.of("downloads")
        val others = store.keys().filter { it.startsWith("downloads_") && it != activeKey && it.removePrefix("downloads_").all(Char::isDigit) }
            .flatMap { key ->
                val payload = store.getString(key) ?: return@flatMap emptyList()
                val array = (runCatching { lenient.parseToJsonElement(payload) }.getOrNull() as? JsonObject)?.get("items") as? JsonArray
                array.orEmpty().mapNotNull { element ->
                    val record = element as? JsonObject ?: return@mapNotNull null
                    (record["locationId"] as? JsonPrimitive)?.contentOrNull to (record["localFileUri"] as? JsonPrimitive)?.contentOrNull
                }
            }
        return active + others
    }
}

/** The desktop side of download locations: where files live, what is reachable, and moving. */
internal class DesktopDownloadFolders(
    val locations: DownloadLocations,
    private val validator: DownloadFolderValidator,
    private val mover: DownloadMover,
    private val hooks: DownloadsHooks = RepositoryDownloadsHooks,
    private val picker: () -> String? = { null },
    private val reveal: (Path) -> Boolean = ::openInFileManager,
    private val isMac: Boolean = false,
    private val onReturned: () -> Unit = {},
    /** Whether the player has this download open. A playing download is not moved. */
    private val isPlaying: (DownloadItem) -> Boolean = { false },
    internal val usage: DownloadStorageUsage = DownloadStorageUsage(locations),
    /** How long a move waits for a writer that was stopped and is still closing its file. */
    private val writerWaitMs: Long = 30_000L,
) : DownloadFolderController {
    override val supported: Boolean = true

    private val _state = MutableStateFlow(DownloadFolderState(isMac = isMac))
    override val state: StateFlow<DownloadFolderState> = _state.asStateFlow()

    private val _progress = MutableStateFlow<MoveProgress?>(null)
    override val progress: StateFlow<MoveProgress?> = _progress.asStateFlow()

    @Volatile private var movingNow: Set<String> = emptySet()
    private val cancelRequested = AtomicBoolean(false)
    private val moveRunning = AtomicBoolean(false)
    private val lastAvailability = java.util.concurrent.ConcurrentHashMap<String, FolderAvailability>()
    private var monitor: Thread? = null
    @Volatile private var resumeAfterRecovery: List<String> = emptyList()

    override fun pickFolder(): String? = picker()

    override fun check(path: String, requiredBytes: Long): FolderCheck = validator.check(path, requiredBytes)

    override fun freeBytesAt(path: String): Long? =
        runCatching { Paths.get(path) }.getOrNull()?.let(::existingAncestor)
            ?.let { dir -> FsGuard.call(3_000L, null) { DownloadFolderValidator.freeBytes(dir) } }

    override fun appFolderPath(): String = locations.appLocation().path.toString()

    override fun locationIdOf(item: DownloadItem): String = locations.locationOfRecord(item.locationId, item.localFileUri).id

    override fun lookup(item: DownloadItem): FileLookup = locations.locate(item.locationId, item.localFileUri, item.fileName)

    override fun isConnected(item: DownloadItem): Boolean {
        val owner = locations.ownerOf(item.locationId, item.localFileUri) ?: return false
        return locations.availability(owner) == FolderAvailability.Available
    }

    override fun locationConnected(locationId: String?): Boolean =
        locations.location(locationId)?.let { locations.availability(it) == FolderAvailability.Available } ?: false

    override fun heldByFolder(item: DownloadItem): Boolean =
        ItemLocks.isHeld(item.id) || WriterTokens.kindOf(item.id) == WriterTokens.Kind.Mover || !isConnected(item)

    override fun placementFor(items: List<DownloadItem>, parentMetaId: String, isEpisode: Boolean): String =
        placementForNewDownload(items, parentMetaId, isEpisode, locations.defaultLocation().id) { locationIdOf(it) }

    override fun removePartialOf(item: DownloadItem): Boolean {
        val owner = locations.ownerOf(item.locationId, item.localFileUri) ?: return false
        if (locations.availability(owner) != FolderAvailability.Available) return false
        val partial = owner.path.resolve(item.fileName + ".part")
        // Only the partial file of this download in its own location. A link is never followed.
        if (!Files.exists(partial, LinkOption.NOFOLLOW_LINKS)) return true
        if (Files.isSymbolicLink(partial)) return false
        return runCatching { Files.deleteIfExists(partial) }.isSuccess
    }

    override fun setDefault(path: String): Boolean {
        val target = runCatching { Paths.get(path.trim()) }.getOrNull() ?: return false
        if (!Files.isDirectory(target)) return false
        locations.setDefault(target)
        refresh()
        return true
    }

    /** The room for new downloads of [item]: the free space of the volume of its location. */
    fun freeBytesOf(item: DownloadItem): Long? {
        val location = locations.locationOfRecord(item.locationId, item.localFileUri)
        return existingAncestor(location.path)?.let { dir ->
            FsGuard.call(3_000L, null) {
                File(dir.toUri()).let { f -> f.usableSpace.takeIf { it > 0L || f.totalSpace > 0L } }
            }
        }
    }

    private fun existingAncestor(path: Path): Path? {
        var current: Path? = path.toAbsolutePath().normalize()
        while (current != null && !Files.exists(current)) current = current.parent
        return current
    }

    override fun refresh() {
        // A drive that came back under another name is found by the id in its marker.
        locations.all().filter { it.id != DownloadLocations.AppId }.forEach { location ->
            if (locations.availability(location) != FolderAvailability.Available) locations.rediscover(location)
        }
        val items = hooks.items()
        val byLocation = items.groupBy { locationIdOf(it) }
        val defaultId = locations.defaultLocation().id
        val states = locations.all().associate { it.id to locations.availability(it) }
        val measured = usage.current(items, states)
        val infos = locations.all().map { location ->
            val availability = states.getValue(location.id)
            val members = byLocation[location.id].orEmpty()
            val used = measured.perLocation[location.id]
            DownloadLocationInfo(
                id = location.id,
                path = location.path.toString(),
                name = location.path.fileName?.toString() ?: location.path.toString(),
                isDefault = location.id == defaultId,
                isAppFolder = location.id == DownloadLocations.AppId,
                availability = availability,
                itemCount = members.size,
                totalBytes = members.sumOf { it.moveBytes },
                usedBytes = used?.usedBytes ?: members.sumOf { it.moveBytes },
                usedIsLastKnown = used?.lastKnown ?: false,
                freeBytes = if (availability == FolderAvailability.Available) {
                    FsGuard.call(3_000L, null) { DownloadFolderValidator.freeBytes(location.path) }
                } else {
                    null
                },
            )
        }
        // Each volume counted once, so two folders on one drive do not count its free space twice.
        val freeTotal = infos.filter { it.availability == FolderAvailability.Available && it.freeBytes != null }
            .distinctBy { info -> runCatching { Files.getFileStore(Paths.get(info.path)).toString() }.getOrDefault(info.id) }
            .sumOf { it.freeBytes ?: 0L }
            .takeIf { infos.any { it.availability == FolderAvailability.Available && it.freeBytes != null } }
        _state.value = DownloadFolderState(
            infos, isMac, moving = moveRunning.get(), movingIds = movingNow,
            autoDownloadUsedBytes = measured.autoDownloadBytes, totalFreeBytes = freeTotal, usageMeasured = true,
            leftovers = runCatching { mover.leftovers() }.getOrDefault(emptyList()),
        )
        var returned = false
        infos.forEach { info ->
            val before = lastAvailability.put(info.id, info.availability)
            if (before != null && before != FolderAvailability.Available && info.availability == FolderAvailability.Available) returned = true
        }
        if (returned) {
            hooks.resumeHeld()
            onReturned()
        }
    }

    /** Checks every few seconds, off the interface thread, which locations can be reached. */
    fun start() {
        if (monitor != null) return
        monitor = Thread({
            runCatching { resumeAfterRecovery.forEach(hooks::resume) }
            while (true) {
                // An interrupted move is settled as soon as its locations are there, and again when they come back.
                if (mover.hasJournals() && !moveRunning.get()) runCatching { settleInterrupted(withRecords = true) }
                runCatching { refresh() }
                try {
                    Thread.sleep(CheckIntervalMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "download-locations").apply {
            isDaemon = true
            start()
        }
    }

    /** At start: removes temporary files an interrupted move left, which needs no list. The rest waits for the list. */
    fun recoverInterrupted(): RecoveryReport? = if (mover.hasJournals()) settleInterrupted(withRecords = false) else null

    /**
     * One pass of recovery. With the list loaded it may also finish a record update the journal proves.
     * It never deletes an original or a file at a final name.
     */
    fun settleInterrupted(withRecords: Boolean): RecoveryReport =
        mover.recover(
            if (!withRecords) {
                null
            } else {
                RecoveryContext(
                    profileId = hooks.profileId(),
                    recordMatches = ::recordMatches,
                    relocate = { item, path -> hooks.relocate(item.itemId, mover.destinationOf(item), path?.toFile()?.toURI()?.toString()) },
                )
            },
        )

    private class Planned(val items: List<MoveItem>, val unreachable: Int, val failures: List<MoveFailure>)

    private fun rel(root: Path, path: Path): String = root.relativize(path).toString()

    private fun plan(ready: List<DownloadItem>, destination: DesktopDownloadLocation, all: List<DownloadItem>): Planned {
        val moveItems = mutableListOf<MoveItem>()
        val failures = mutableListOf<MoveFailure>()
        val profile = hooks.profileId()
        var unreachable = 0
        ready.forEach { item ->
            val owner = locations.locationOfRecord(item.locationId, item.localFileUri)
            if (item.status == DownloadStatus.Completed) {
                when (val found = locations.locate(item.locationId, item.localFileUri, item.fileName)) {
                    is FileLookup.Found -> if (found.locationId != destination.id) {
                        val video = DownloadLocations.fileOfUri(found.uri)?.toPath() ?: return@forEach
                        val root = locations.location(found.locationId)?.path ?: return@forEach
                        if (Files.isSymbolicLink(video)) {
                            failures += MoveFailure(item.fileName, MoveFailureReason.Symlink)
                            return@forEach
                        }
                        val sidecar = DownloadCompanions.of(video).files
                        val ours = (listOf(video) + sidecar.map { it.path }).map { it.toAbsolutePath().normalize() }.toSet()
                        // A path that belongs to another record stops this item.
                        val owned = all.any { other ->
                            other.id != item.id && (
                                (other.fileName == item.fileName && locationIdOf(other) == locationIdOf(item)) ||
                                    other.localFileUri?.let(DownloadLocations::fileOfUri)?.toPath()?.toAbsolutePath()?.normalize() in ours
                                )
                        }
                        if (owned) {
                            failures += MoveFailure(item.fileName, MoveFailureReason.OwnedByAnother)
                            return@forEach
                        }
                        val entries = mutableListOf(
                            MoveEntry(found.locationId.orEmpty(), rel(root, video), destination.id, video.fileName.toString(), MoveKind.File, Files.size(video)),
                        )
                        sidecar.forEach { companion ->
                            entries += MoveEntry(found.locationId.orEmpty(), rel(root, companion.path), destination.id, companion.relativeName, MoveKind.Companion, Files.size(companion.path))
                        }
                        moveItems += MoveItem(profile, item.id, item.locationId, rel(root, video), entries)
                    }
                    is FileLookup.Unreachable -> unreachable++
                    FileLookup.Missing -> Unit
                }
            } else if (owner.id != destination.id) {
                if (locations.availability(owner) != FolderAvailability.Available) {
                    unreachable++
                } else {
                    val partial = owner.path.resolve(item.fileName + ".part")
                    when {
                        Files.isSymbolicLink(partial) -> failures += MoveFailure(item.fileName, MoveFailureReason.Symlink)
                        Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS) -> moveItems += MoveItem(
                            profile, item.id, item.locationId, item.fileName + ".part",
                            listOf(MoveEntry(owner.id, item.fileName + ".part", destination.id, item.fileName + ".part", MoveKind.Partial, Files.size(partial))),
                        )
                        else -> moveItems += MoveItem(profile, item.id, item.locationId, item.fileName, emptyList())
                    }
                }
            }
        }
        return Planned(moveItems, unreachable, failures)
    }

    override fun bytesToCopy(itemIds: Set<String>, destinationPath: String): Long {
        val destinationDir = runCatching { Paths.get(destinationPath) }.getOrNull()?.let(::existingAncestor) ?: return 0L
        val all = hooks.items()
        val items = all.filter { it.id in itemIds }
        return plan(items, DesktopDownloadLocation("?", Paths.get(destinationPath)), all).items
            .flatMap { it.entries }
            .filterNot { entry ->
                val source = locations.location(entry.srcLocationId)?.path?.resolve(entry.srcRel) ?: return@filterNot true
                runCatching { Files.getFileStore(source) == Files.getFileStore(destinationDir) }.getOrDefault(false)
            }
            .sumOf { it.size }
    }

    private fun acquireWriterToken(id: String): Boolean {
        var waited = 0L
        while (!WriterTokens.tryAcquire(id, WriterTokens.Kind.Mover)) {
            if (waited >= writerWaitMs) return false
            Thread.sleep(10)
            waited += 10
        }
        return true
    }

    override fun move(itemIds: Set<String>, destinationPath: String): MoveReport {
        val path = runCatching { Paths.get(destinationPath.trim()) }.getOrNull()
        val name = path?.fileName?.toString() ?: destinationPath
        if (path == null) return MoveReport(destinationPath, name, 0, problem = FolderProblem.NotAFolder)
        // The same checks as before the question was asked: a folder inside a download folder, the Trash,
        // a drive that is not there, no room.
        when (val check = validator.check(destinationPath, bytesToCopy(itemIds, destinationPath))) {
            is FolderCheck.Problem -> return MoveReport(destinationPath, name, 0, problem = check.kind)
            FolderCheck.Ok -> Unit
        }
        if (!moveRunning.compareAndSet(false, true)) return MoveReport(destinationPath, name, 0, problem = FolderProblem.MoveInProgress)
        cancelRequested.set(false)
        val startedProfile = hooks.profileId()
        val locked = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        val paused = mutableListOf<String>()
        val prefailed = mutableListOf<MoveFailure>()
        try {
            val destination = locations.register(path)
            if (locations.availability(destination) != FolderAvailability.Available) {
                return MoveReport(destinationPath, name, 0, problem = FolderProblem.NotConnected)
            }
            val everything = hooks.items()
            val ready = mutableListOf<DownloadItem>()
            everything.filter { it.id in itemIds }.forEach { item ->
                when {
                    isPlaying(item) -> prefailed += MoveFailure(item.fileName, MoveFailureReason.Playing)
                    !ItemLocks.tryAcquire(item.id) -> prefailed += MoveFailure(item.fileName, MoveFailureReason.Busy)
                    else -> {
                        locked += item.id
                        // Exactly one writer: a running download is asked to stop and waited for, and the
                        // item's writer token is taken. A writer that is still closing its file is waited for.
                        val wasRunning = item.status == DownloadStatus.Downloading
                        val stopped = !wasRunning || hooks.pauseAndWait(item.id)
                        if (stopped && acquireWriterToken(item.id)) {
                            tokens += item.id
                            if (wasRunning) paused += item.id
                            ready += item
                        } else {
                            prefailed += MoveFailure(item.fileName, MoveFailureReason.StillWriting)
                            ItemLocks.release(item.id)
                            locked.remove(item.id)
                            if (wasRunning) paused += item.id
                        }
                    }
                }
            }
            movingNow = ready.map { it.id }.toSet()
            _state.value = _state.value.copy(moving = true, movingIds = movingNow)
            val planned = plan(hooks.items().filter { it.id in movingNow }, destination, everything)
            val report = mover.run(
                plan = MovePlan(destination.id, name, planned.items),
                isCancelled = { cancelRequested.get() || hooks.profileId() != startedProfile },
                onProgress = { _progress.value = it },
                canCommit = { moveItem -> hooks.profileId() == moveItem.profileId && recordMatches(moveItem) },
                onMoved = { moveItem, target -> hooks.relocate(moveItem.itemId, destination.id, target?.toFile()?.toURI()?.toString()) },
            )
            // A location that holds nothing in any profile's list is forgotten, after a move that ended normally.
            if (report.problem == null) {
                val used = hooks.recordsOfAllProfiles().map { (id, uri) -> locations.locationOfRecord(id, uri).id }.toSet()
                locations.forgetExcept(used)
            }
            return report.copy(
                unreachable = planned.unreachable,
                failures = prefailed + planned.failures + report.failures,
            )
        } finally {
            movingNow = emptySet()
            usage.invalidate()
            tokens.forEach(WriterTokens::release)
            locked.forEach(ItemLocks::release)
            paused.distinct().forEach(hooks::resume)
            hooks.resumeHeld()
            _progress.value = null
            moveRunning.set(false)
            refresh()
        }
    }

    /** The record is the one the move planned for: it exists and still points at the journalled source. */
    private fun recordMatches(moveItem: MoveItem): Boolean {
        val record = hooks.items().firstOrNull { it.id == moveItem.itemId } ?: return false
        if (record.locationId != moveItem.recordLocationId) return false
        val main = moveItem.main ?: return true
        val source = locations.location(main.srcLocationId)?.path?.resolve(main.srcRel)?.normalize() ?: return false
        return currentPathOf(record) == source
    }

    /** Where the record's file is, by the record and nowhere else. */
    private fun currentPathOf(record: DownloadItem): Path? =
        if (record.status == DownloadStatus.Completed) {
            (locations.locate(record.locationId, record.localFileUri, record.fileName) as? FileLookup.Found)
                ?.let { DownloadLocations.fileOfUri(it.uri)?.toPath()?.normalize() }
        } else {
            locations.ownerOf(record.locationId, record.localFileUri)?.path?.resolve(record.fileName + ".part")?.normalize()
        }

    private fun recordSide(moveItem: MoveItem): RecordSide {
        val record = hooks.items().firstOrNull { it.id == moveItem.itemId } ?: return RecordSide.Missing
        val main = moveItem.main ?: return RecordSide.Missing
        val current = currentPathOf(record) ?: return RecordSide.Missing
        val source = locations.location(main.srcLocationId)?.path?.resolve(main.srcRel)?.normalize()
        val target = locations.location(main.dstLocationId)?.path?.resolve(main.dstRel)?.normalize()
        return when (current) {
            source -> RecordSide.AtSource
            target -> RecordSide.AtDestination
            else -> RecordSide.Missing
        }
    }

    override fun removeExtraCopy(leftoverId: String): LeftoverOutcome =
        mover.removeExtraCopy(leftoverId, hooks.profileId(), ::recordSide).also { refresh() }

    override fun keepBoth(leftoverId: String) {
        mover.keepBoth(leftoverId)
        refresh()
    }

    override fun cancelMove() {
        cancelRequested.set(true)
        _progress.value = _progress.value?.copy(cancelling = true)
    }

    override fun showInFileManager(locationId: String): Boolean {
        val location = locations.location(locationId) ?: return false
        if (locations.availability(location) != FolderAvailability.Available) return false
        if (locationId == DownloadLocations.AppId) runCatching { Files.createDirectories(location.path) }
        return reveal(location.path)
    }

    override fun onLocationReturned() = onReturned()

    companion object {
        private const val CheckIntervalMs = 3_000L

        fun openInFileManager(path: Path): Boolean {
            val desktop = runCatching { Desktop.getDesktop() }.getOrNull()
            if (desktop != null && Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.OPEN)) {
                if (runCatching { desktop.open(path.toFile()) }.isSuccess) return true
            }
            val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            val command = when {
                os.contains("mac") -> listOf("open", path.toString())
                os.contains("win") -> listOf("explorer", path.toString())
                else -> listOf("xdg-open", path.toString())
            }
            return runCatching { ProcessBuilder(command).start() }.isSuccess
        }
    }
}

/** The folders of the running app. Replaced in tests. */
internal object DesktopDownloads {
    @Volatile
    private var override: DesktopDownloadFolders? = null

    private val real: DesktopDownloadFolders by lazy {
        val appFolder = DesktopStorage.rootDir.resolve("downloads")
        val locations = DownloadLocations(DownloadLocationConfig(DesktopStorage.store("nuvio_download_folders")), { appFolder })
        val isMac = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("mac")
        DesktopDownloadFolders(
            locations = locations,
            validator = DownloadFolderValidator(locations, DesktopStorage.rootDir, DesktopStorage.cacheDir),
            mover = DownloadMover(
                journalDir = DesktopStorage.rootDir.resolve("moves"),
                resolve = { id -> locations.location(id)?.takeIf { locations.availability(it) == FolderAvailability.Available }?.path },
            ),
            picker = { NativeFolderPicker.pick(isMac, locations.defaultLocation().path.toFile().takeIf { it.isDirectory }) },
            isMac = isMac,
            onReturned = { runCatching { AutoDownloads.checkNow() } },
            isPlaying = { item -> AutoDownloads.isPlaying(item) },
        )
    }

    val folders: DesktopDownloadFolders get() = override ?: real

    /** For tests: use [folders], or the real ones again with null. */
    fun replaceForTest(folders: DesktopDownloadFolders?) {
        override = folders
    }

    /** Called once when the app starts: settles an interrupted move, then watches the locations. */
    fun install() {
        val folders = real
        folders.recoverInterrupted()
        DownloadFolders.controller = folders
        folders.start()
    }
}
