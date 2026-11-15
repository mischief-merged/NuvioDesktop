package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.UUID

/** A folder downloads are kept in. [id] is stable; the app's own folder has [DownloadLocations.AppId]. */
internal data class DesktopDownloadLocation(val id: String, val path: Path)

/** Which volume a path is on, for telling a drive that is not connected from a folder that is gone. */
internal interface DownloadVolumes {
    /** The mount point of the drive that holds [path], or null for the system volume, which is always there. */
    fun anchorOf(path: Path): Path?

    /** The drives that are mounted now, for finding a drive that came back under another name. */
    fun mountPoints(): List<Path> = emptyList()
}

/** Runs a file-system call that may hang on its own thread and gives up after a time. */
internal object FsGuard {
    private val pool = java.util.concurrent.Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "download-fs").apply { isDaemon = true }
    }

    fun <T> call(timeoutMs: Long, onTimeout: T, block: () -> T): T {
        val future = pool.submit(java.util.concurrent.Callable { block() })
        return try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            onTimeout
        } catch (error: java.util.concurrent.ExecutionException) {
            throw error.cause ?: error
        }
    }
}

internal class OsDownloadVolumes(
    private val osName: String = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT),
    private val systemDrive: String? = System.getenv("SystemDrive"),
) : DownloadVolumes {
    override fun mountPoints(): List<Path> = runCatching {
        when {
            osName.contains("mac") -> Files.list(Paths.get("/Volumes")).use { it.toList() }
            osName.contains("win") -> java.io.File.listRoots().map { it.toPath() }
            else -> listOf("/media", "/run/media", "/mnt").map(Paths::get).filter(Files::isDirectory).flatMap { base ->
                Files.list(base).use { it.toList() }.flatMap { child ->
                    if (base.toString() == "/mnt") listOf(child) else Files.list(child).use { it.toList() }
                }
            }
        }
    }.getOrDefault(emptyList())

    override fun anchorOf(path: Path): Path? {
        val absolute = path.toAbsolutePath().normalize()
        val root = absolute.root ?: return null
        val parts = absolute.map { it.toString() }
        return when {
            osName.contains("mac") ->
                if (parts.size >= 2 && parts[0] == "Volumes") root.resolve("Volumes").resolve(parts[1]) else null
            osName.contains("win") ->
                root.takeUnless { systemDrive != null && it.toString().startsWith(systemDrive, ignoreCase = true) }
            else -> when {
                parts.size >= 3 && parts[0] == "media" -> root.resolve("media").resolve(parts[1]).resolve(parts[2])
                parts.size >= 4 && parts[0] == "run" && parts[1] == "media" ->
                    root.resolve("run").resolve("media").resolve(parts[2]).resolve(parts[3])
                parts.size >= 2 && parts[0] == "mnt" -> root.resolve("mnt").resolve(parts[1])
                else -> null
            }
        }
    }
}

@Serializable
internal data class StoredLocation(val id: String, val path: String)

/** What is saved about locations, on this device only. */
internal class DownloadLocationConfig(private val store: DesktopStorage.Store) {
    private val json = Json { ignoreUnknownKeys = true }

    fun locations(): List<StoredLocation> =
        store.getString(LocationsKey)?.let { runCatching { json.decodeFromString<List<StoredLocation>>(it) }.getOrNull() }.orEmpty()

    fun saveLocations(locations: List<StoredLocation>) {
        store.putString(LocationsKey, json.encodeToString(locations))
    }

    fun defaultId(): String? = store.getString(DefaultKey)

    fun saveDefaultId(id: String) {
        store.putString(DefaultKey, id)
    }

    private companion object {
        const val LocationsKey = "locations"
        const val DefaultKey = "default_location"
    }
}

/**
 * The folders downloads live in. The app's own folder is always there. Others are added when the
 * user chooses them, and are told apart from a path that merely looks the same by a marker file
 * that holds their id.
 */
internal class DownloadLocations(
    private val config: DownloadLocationConfig,
    private val appFolder: () -> Path,
    val volumes: DownloadVolumes = OsDownloadVolumes(),
    /** The longest a look at a volume may take before it counts as not answering. */
    private val timeoutMs: Long = 3_000L,
) {
    private val lock = Any()

    // The saved list is read once and again only after this class changes it: it is asked for every
    // row of the list, many times a second.
    private var cachedAll: List<DesktopDownloadLocation>? = null

    fun appLocation(): DesktopDownloadLocation = DesktopDownloadLocation(AppId, appFolder().toAbsolutePath().normalize())

    /** The app's folder first, then the others in the order they were added. */
    fun all(): List<DesktopDownloadLocation> = synchronized(lock) {
        cachedAll ?: (
            listOf(appLocation()) + config.locations()
                .filter { it.id != AppId }
                .mapNotNull { stored -> runCatching { DesktopDownloadLocation(stored.id, Paths.get(stored.path).toAbsolutePath().normalize()) }.getOrNull() }
            ).also { cachedAll = it }
    }

    fun defaultLocation(): DesktopDownloadLocation = synchronized(lock) {
        val id = config.defaultId()
        all().firstOrNull { it.id == id } ?: appLocation()
    }

    fun location(id: String?): DesktopDownloadLocation? = if (id == null) appLocation() else all().firstOrNull { it.id == id }

    /** The location of [path], or a new one. The folder is not created here. */
    fun register(path: Path): DesktopDownloadLocation = synchronized(lock) {
        val wanted = path.toAbsolutePath().normalize()
        all().firstOrNull { samePath(it.path, wanted) }?.let { existing ->
            writeMarker(existing)
            return existing
        }
        val location = DesktopDownloadLocation("loc-" + UUID.randomUUID().toString().take(8), wanted)
        config.saveLocations(config.locations() + StoredLocation(location.id, wanted.toString()))
        cachedAll = null
        writeMarker(location)
        location
    }

    fun setDefault(path: Path): DesktopDownloadLocation = synchronized(lock) {
        val location = register(path)
        config.saveDefaultId(location.id)
        location
    }

    fun setDefaultId(id: String) = synchronized(lock) { config.saveDefaultId(id) }

    /** Forgets locations that are not [keepIds], not the default and not the app's own. */
    fun forgetExcept(keepIds: Set<String>) = synchronized(lock) {
        val keep = keepIds + config.defaultId().orEmpty()
        val remaining = config.locations().filter { it.id in keep }
        if (remaining.size != config.locations().size) {
            config.saveLocations(remaining)
            cachedAll = null
        }
    }

    /**
     * Whether the location can be used. Every file-system call here may hang on a drive that is
     * failing or a network volume that stopped answering, so it runs on a helper thread and is given
     * [timeoutMs]; a location that does not answer counts as not connected, and nothing is touched.
     */
    fun availability(location: DesktopDownloadLocation): FolderAvailability {
        if (location.id == AppId) return FolderAvailability.Available
        return FsGuard.call(timeoutMs, FolderAvailability.NotConnected) {
            val anchor = volumes.anchorOf(location.path)
            when {
                anchor != null && !Files.exists(anchor) -> FolderAvailability.NotConnected
                !Files.isDirectory(location.path) -> FolderAvailability.NotFound
                markerIdOf(location.path) == location.id -> FolderAvailability.Available
                else -> FolderAvailability.NotSetUp
            }
        }
    }

    private fun markerIdOf(folder: Path): String? =
        runCatching { Files.readString(folder.resolve(MarkerName)).trim() }.getOrNull()

    /**
     * Looks for a location whose drive came back under another name, on any other mounted drive, by
     * the id its marker holds and never by a file name. The saved path is updated when it is found.
     */
    fun rediscover(location: DesktopDownloadLocation): DesktopDownloadLocation? {
        if (location.id == AppId) return null
        return FsGuard.call(timeoutMs * 2, null) {
            val anchor = volumes.anchorOf(location.path) ?: return@call null
            val relative = anchor.relativize(location.path.toAbsolutePath().normalize())
            volumes.mountPoints()
                .filter { it != anchor && Files.isDirectory(it) }
                .map { it.resolve(relative.toString()) }
                .firstOrNull { candidate -> Files.isDirectory(candidate) && markerIdOf(candidate) == location.id }
                ?.let { moved -> moveLocation(location.id, moved) }
        }
    }

    private fun moveLocation(id: String, path: Path): DesktopDownloadLocation = synchronized(lock) {
        val normalized = path.toAbsolutePath().normalize()
        config.saveLocations(config.locations().map { if (it.id == id) it.copy(path = normalized.toString()) else it })
        cachedAll = null
        DesktopDownloadLocation(id, normalized)
    }

    /** The location of [path], judged by the names alone. */
    fun locationContaining(path: Path): DesktopDownloadLocation? {
        val absolute = path.toAbsolutePath().normalize()
        return all().filter { absolute.startsWith(it.path) }.maxByOrNull { it.path.nameCount }
    }

    /** The location a record names, or the one its address is in; null when it names one that is not known. */
    fun ownerOf(locationId: String?, localFileUri: String?): DesktopDownloadLocation? {
        if (locationId != null) return location(locationId)
        localFileUri?.let(::fileOfUri)?.let { file -> locationContaining(file.toPath())?.let { return it } }
        return appLocation()
    }

    /** [ownerOf], and the app folder for a record whose location is not known (for counting and labels only). */
    fun locationOfRecord(locationId: String?, localFileUri: String?): DesktopDownloadLocation =
        ownerOf(locationId, localFileUri) ?: localFileUri?.let(::fileOfUri)?.let { locationContaining(it.toPath()) } ?: appLocation()

    /**
     * Looks for the file of a record in the place the record names, and nowhere else. When that place
     * cannot be used the answer is that it is away, and no other place is looked in: a file of the same
     * name somewhere else is another file. A record is pointed somewhere else only by a journalled move.
     */
    fun locate(locationId: String?, localFileUri: String?, fileName: String): FileLookup {
        val owner = ownerOf(locationId, localFileUri) ?: return FileLookup.Unreachable(locationId, FolderAvailability.NotFound)
        val state = availability(owner)
        if (state != FolderAvailability.Available) return FileLookup.Unreachable(owner.id, state)
        val name = fileName.trim()
        val recorded = localFileUri?.let(::fileOfUri)
        return FsGuard.call(timeoutMs, FileLookup.Unreachable(owner.id, FolderAvailability.NotConnected)) {
            // The address on the record counts only inside a location that can be used, marker and all.
            val recordedOwner = recorded?.let { locationContaining(it.toPath()) }
            if (recorded != null && recordedOwner != null && recordedOwner.id != owner.id) {
                val other = availability(recordedOwner)
                if (other != FolderAvailability.Available) return@call FileLookup.Unreachable(recordedOwner.id, other)
            }
            val candidates = listOfNotNull(
                recorded?.takeIf { recordedOwner != null || locationId == null }?.let { (recordedOwner ?: owner) to it },
                name.takeIf { it.isNotEmpty() }?.let { owner to owner.path.resolve(it).toFile() },
            )
            candidates.firstOrNull { (_, file) -> file.isFile }?.let { (location, file) ->
                FileLookup.Found(file.toURI().toString(), location.id)
            } ?: run {
                val anchor = recorded?.let { volumes.anchorOf(it.toPath()) }
                if (anchor != null && !Files.exists(anchor)) {
                    FileLookup.Unreachable(owner.id, FolderAvailability.NotConnected)
                } else {
                    FileLookup.Missing
                }
            }
        }
    }

    private fun writeMarker(location: DesktopDownloadLocation) {
        if (location.id == AppId || !Files.isDirectory(location.path)) return
        runCatching { Files.writeString(location.path.resolve(MarkerName), location.id) }
    }

    companion object {
        const val AppId = "app"
        const val MarkerName = ".nuvio-downloads"

        fun fileOfUri(value: String): File? = runCatching {
            if (value.startsWith("file:")) File(URI(value)) else File(value)
        }.getOrNull()

        fun samePath(a: Path, b: Path): Boolean =
            a.normalize() == b.normalize() ||
                (Files.exists(a) && Files.exists(b) && runCatching { Files.isSameFile(a, b) }.getOrDefault(false))
    }
}

/** Checks a folder before it is accepted as a place for downloads. */
internal class DownloadFolderValidator(
    private val locations: DownloadLocations,
    private val appDataDir: Path,
    private val cacheDir: Path,
    private val programDirs: () -> List<Path> = ::defaultProgramDirs,
) {
    fun check(raw: String, requiredBytes: Long): FolderCheck {
        val path = runCatching { Paths.get(raw.trim()) }.getOrNull()
            ?.takeIf { raw.isNotBlank() && it.isAbsolute }?.normalize()
            ?: return FolderCheck.Problem(FolderProblem.NotAFolder)
        if (Files.exists(path) && !Files.isDirectory(path)) return FolderCheck.Problem(FolderProblem.NotAFolder)
        val anchor = locations.volumes.anchorOf(path)
        if (anchor != null && !Files.exists(anchor)) return FolderCheck.Problem(FolderProblem.NotConnected)
        if (isTrash(path)) return FolderCheck.Problem(FolderProblem.IsTrash)

        val resolved = resolve(path)
        val appFolder = resolve(locations.appLocation().path)
        val isAppFolder = resolved == appFolder
        if (!isAppFolder && (inside(resolved, resolve(appDataDir)) || inside(resolved, resolve(cacheDir)))) {
            return FolderCheck.Problem(FolderProblem.InsideAppData)
        }
        if (programDirs().any { inside(resolved, resolve(it)) }) return FolderCheck.Problem(FolderProblem.InsideProgram)
        locations.all().forEach { known ->
            val other = resolve(known.path)
            if (other != resolved && (inside(resolved, other) || inside(other, resolved)) && known.id != DownloadLocations.AppId) {
                return FolderCheck.Problem(FolderProblem.OverlapsDownloadFolder)
            }
        }
        if (!Files.isDirectory(path)) {
            runCatching { Files.createDirectories(path) }.getOrElse { return FolderCheck.Problem(FolderProblem.CannotCreate) }
            if (!Files.isDirectory(path)) return FolderCheck.Problem(FolderProblem.CannotCreate)
        }
        if (!canWrite(path)) return FolderCheck.Problem(FolderProblem.NotWritable)
        if (requiredBytes > 0L) {
            val free = freeBytes(path)
            if (free != null && requiredBytes > free) {
                return FolderCheck.Problem(FolderProblem.NotEnoughSpace, neededBytes = requiredBytes, freeBytes = free)
            }
        }
        return FolderCheck.Ok
    }

    private fun canWrite(directory: Path): Boolean {
        // A probe with a random name, created without replacing anything. If the name is taken it is left alone.
        repeat(5) {
            val probe = directory.resolve(".nuvio-write-test-${DownloadMover.randomHex(8)}")
            val created = try {
                Files.newByteChannel(probe, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use {
                    it.write(java.nio.ByteBuffer.wrap(ByteArray(16) { b -> b.toByte() }))
                }
                true
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                return@repeat
            } catch (_: Exception) {
                return false
            }
            if (created) {
                runCatching { Files.deleteIfExists(probe) }
                return true
            }
        }
        return false
    }

    private fun inside(path: Path, parent: Path): Boolean = path.startsWith(parent)

    /** [path] with the links of its existing part resolved, so a link cannot hide where it leads. */
    private fun resolve(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var existing: Path? = absolute
        val rest = ArrayDeque<Path>()
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing.fileName?.let { rest.addFirst(it) }
            existing = existing.parent
        }
        val base = existing?.let { runCatching { it.toRealPath() }.getOrDefault(it) } ?: absolute
        return rest.fold(base) { acc, part -> acc.resolve(part) }
    }

    companion object {
        fun freeBytes(path: Path): Long? = runCatching { Files.getFileStore(path).usableSpace }.getOrNull()

        fun isTrash(path: Path): Boolean {
            val parts = path.map { it.toString().lowercase(Locale.ROOT) }
            if (parts.any { it == ".trash" || it == ".trashes" || it == "\$recycle.bin" || it == "recycler" }) return true
            return path.toString().replace('\\', '/').lowercase(Locale.ROOT).contains("/.local/share/trash")
        }

        fun defaultProgramDirs(): List<Path> {
            val dirs = mutableListOf<Path>()
            System.getProperty("java.home")?.takeIf { it.isNotBlank() }?.let { dirs.add(Paths.get(it)) }
            System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }?.let { dirs.add(Paths.get(it)) }
            runCatching {
                val source = Paths.get(DownloadFolderValidator::class.java.protectionDomain.codeSource.location.toURI())
                val bundle = generateSequence(source) { it.parent }.firstOrNull { it.fileName?.toString()?.endsWith(".app") == true }
                dirs.add(bundle ?: source.parent ?: source)
            }
            return dirs
        }
    }
}
