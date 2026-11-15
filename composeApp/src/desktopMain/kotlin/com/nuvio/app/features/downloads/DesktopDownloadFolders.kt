package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** What the folders need from the list of downloads. The app wires them to [DownloadsRepository]. */
internal interface DownloadsHooks {
    fun items(): List<DownloadItem>
    fun pause(downloadId: String)
    fun resume(downloadId: String)
    fun relocate(downloadId: String, locationId: String, localFileUri: String?)
    fun resumeHeld()
}

internal object RepositoryDownloadsHooks : DownloadsHooks {
    override fun items(): List<DownloadItem> = DownloadsRepository.uiState.value.items
    override fun pause(downloadId: String) = DownloadsRepository.pauseDownload(downloadId)
    override fun resume(downloadId: String) = DownloadsRepository.resumeDownload(downloadId)
    override fun relocate(downloadId: String, locationId: String, localFileUri: String?) =
        DownloadsRepository.relocateDownload(downloadId, locationId, localFileUri)
    override fun resumeHeld() = DownloadsRepository.resumeHeldByFolder()
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
) : DownloadFolderController {
    override val supported: Boolean = true

    private val _state = MutableStateFlow(DownloadFolderState(isMac = isMac))
    override val state: StateFlow<DownloadFolderState> = _state.asStateFlow()

    private val _progress = MutableStateFlow<MoveProgress?>(null)
    override val progress: StateFlow<MoveProgress?> = _progress.asStateFlow()

    private val movingIds = ConcurrentHashMap.newKeySet<String>()
    private val cancelRequested = AtomicBoolean(false)
    private val moveRunning = AtomicBoolean(false)
    private val lastAvailability = ConcurrentHashMap<String, FolderAvailability>()
    private var monitor: Thread? = null
    @Volatile private var resumeAfterRecovery: List<String> = emptyList()

    override fun pickFolder(): String? = picker()

    override fun check(path: String, requiredBytes: Long): FolderCheck = validator.check(path, requiredBytes)

    override fun freeBytesAt(path: String): Long? =
        runCatching { Paths.get(path) }.getOrNull()?.let(::existingAncestor)?.let(DownloadFolderValidator::freeBytes)

    override fun appFolderPath(): String = locations.appLocation().path.toString()

    override fun locationIdOf(item: DownloadItem): String = locations.locationOfRecord(item.locationId, item.localFileUri).id

    override fun lookup(item: DownloadItem): FileLookup = locations.locate(item.locationId, item.localFileUri, item.fileName)

    override fun isConnected(item: DownloadItem): Boolean =
        locations.availability(locations.locationOfRecord(item.locationId, item.localFileUri)) == FolderAvailability.Available

    override fun locationConnected(locationId: String?): Boolean =
        locations.location(locationId)?.let { locations.availability(it) == FolderAvailability.Available } ?: false

    override fun heldByFolder(item: DownloadItem): Boolean = item.id in movingIds || !isConnected(item)

    override fun placementFor(items: List<DownloadItem>, parentMetaId: String, isEpisode: Boolean): String =
        placementForNewDownload(items, parentMetaId, isEpisode, locations.defaultLocation().id) { locationIdOf(it) }

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
            runCatching { File(dir.toUri()).let { f -> f.usableSpace.takeIf { it > 0L || f.totalSpace > 0L } } }.getOrNull()
        }
    }

    private fun existingAncestor(path: Path): Path? {
        var current: Path? = path.toAbsolutePath().normalize()
        while (current != null && !Files.exists(current)) current = current.parent
        return current
    }

    override fun refresh() {
        val items = hooks.items()
        val byLocation = items.groupBy { locationIdOf(it) }
        val infos = locations.all().map { location ->
            val availability = locations.availability(location)
            val members = byLocation[location.id].orEmpty()
            DownloadLocationInfo(
                id = location.id,
                path = location.path.toString(),
                name = location.path.fileName?.toString() ?: location.path.toString(),
                isDefault = location.id == locations.defaultLocation().id,
                isAppFolder = location.id == DownloadLocations.AppId,
                availability = availability,
                itemCount = members.size,
                totalBytes = members.sumOf { it.moveBytes },
                freeBytes = if (availability == FolderAvailability.Available) DownloadFolderValidator.freeBytes(location.path) else null,
            )
        }
        _state.value = DownloadFolderState(infos, isMac, moving = moveRunning.get())
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

    /** Settles a move that was cut short by a crash or a quit. The downloads it paused are resumed once the monitor starts. */
    fun recoverInterrupted(): RecoveryReport? {
        val report = mover.recover() ?: return null
        resumeAfterRecovery = report.pausedIds
        return report
    }

    private class Planned(val entries: List<MoveEntry>, val unreachable: Int, val items: List<DownloadItem>)

    private fun plan(itemIds: Set<String>, destination: DesktopDownloadLocation): Planned {
        val items = hooks.items().filter { it.id in itemIds }
        val entries = mutableListOf<MoveEntry>()
        var unreachable = 0
        items.forEach { item ->
            val owner = locations.locationOfRecord(item.locationId, item.localFileUri)
            if (item.status == DownloadStatus.Completed) {
                when (val found = locations.locate(item.locationId, item.localFileUri, item.fileName)) {
                    is FileLookup.Found -> if (found.locationId != destination.id) {
                        val file = DownloadLocations.fileOfUri(found.uri)
                        if (file != null) entries += MoveEntry(item.id, item.fileName, file.absolutePath, MoveKind.File, file.length())
                    }
                    is FileLookup.Unreachable -> unreachable++
                    FileLookup.Missing -> Unit
                }
            } else if (owner.id != destination.id) {
                if (locations.availability(owner) != FolderAvailability.Available) {
                    unreachable++
                } else {
                    val partial = owner.path.resolve(item.fileName + ".part")
                    entries += if (Files.isRegularFile(partial)) {
                        MoveEntry(item.id, item.fileName + ".part", partial.toString(), MoveKind.Partial, Files.size(partial))
                    } else {
                        MoveEntry(item.id, item.fileName, "", MoveKind.RecordOnly, 0L)
                    }
                }
            }
        }
        return Planned(entries, unreachable, items)
    }

    override fun bytesToCopy(itemIds: Set<String>, destinationPath: String): Long {
        val destinationDir = runCatching { Paths.get(destinationPath) }.getOrNull()?.let(::existingAncestor) ?: return 0L
        return plan(itemIds, DesktopDownloadLocation("?", Paths.get(destinationPath))).entries
            .filter { it.kind != MoveKind.RecordOnly }
            .filterNot { entry -> runCatching { Files.getFileStore(Paths.get(entry.from)) == Files.getFileStore(destinationDir) }.getOrDefault(false) }
            .sumOf { it.size }
    }

    override fun move(itemIds: Set<String>, destinationPath: String): MoveReport {
        val path = runCatching { Paths.get(destinationPath.trim()) }.getOrNull()
        val name = path?.fileName?.toString() ?: destinationPath
        if (path == null || !Files.isDirectory(path)) return MoveReport(destinationPath, name, 0, problem = FolderProblem.NotAFolder)
        if (!moveRunning.compareAndSet(false, true)) return MoveReport(destinationPath, name, 0, problem = FolderProblem.OverlapsDownloadFolder)
        cancelRequested.set(false)
        try {
            val destination = locations.register(path)
            if (locations.availability(destination) != FolderAvailability.Available) {
                return MoveReport(destinationPath, name, 0, problem = FolderProblem.NotConnected)
            }
            val planned = plan(itemIds, destination)
            movingIds += planned.items.map { it.id }
            val paused = planned.items.filter { it.status == DownloadStatus.Downloading }.map { it.id }
            paused.forEach(hooks::pause)
            _state.value = _state.value.copy(moving = true)
            try {
                val report = mover.run(
                    plan = MovePlan(destination.path, destination.id, planned.entries, paused),
                    isCancelled = { cancelRequested.get() },
                    onProgress = { _progress.value = it },
                    onMoved = { entry, target -> hooks.relocate(entry.itemId, destination.id, target?.toFile()?.toURI()?.toString()) },
                )
                return report.copy(unreachable = planned.unreachable)
            } finally {
                movingIds.clear()
                paused.forEach(hooks::resume)
                hooks.resumeHeld()
            }
        } finally {
            _progress.value = null
            moveRunning.set(false)
            locations.forgetExcept(hooks.items().map { locationIdOf(it) }.toSet())
            refresh()
        }
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
            mover = DownloadMover(DesktopStorage.rootDir.resolve("download-folder-move.json")),
            picker = { NativeFolderPicker.pick(isMac, locations.defaultLocation().path.toFile().takeIf { it.isDirectory }) },
            isMac = isMac,
            onReturned = { runCatching { AutoDownloads.checkNow() } },
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
