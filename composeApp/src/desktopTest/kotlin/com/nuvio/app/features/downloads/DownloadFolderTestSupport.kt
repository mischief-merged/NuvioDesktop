package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.random.Random

/** Treats the folders under a temporary "Volumes" folder as separate drives. Moving one aside unplugs it. */
internal class FakeVolumes(private val volumesDir: Path) : DownloadVolumes {
    override fun anchorOf(path: Path): Path? {
        val absolute = path.toAbsolutePath().normalize()
        return if (absolute.startsWith(volumesDir) && absolute.nameCount > volumesDir.nameCount) {
            volumesDir.resolve(absolute.getName(volumesDir.nameCount).toString())
        } else {
            null
        }
    }
}

/** A temporary place with an app folder, a few drives and locations over them. Nothing outside it is touched. */
internal class FolderWorld(val root: Path = Files.createTempDirectory("nuvio-folders").toRealPath()) {
    val volumes: Path = root.resolve("Volumes").createDirectories()
    val appFolder: Path = root.resolve("app-data/downloads")
    val appData: Path = root.resolve("app-data")
    val config = DownloadLocationConfig(DesktopStorage.Store(root.resolve("folders.properties")))
    val locations = DownloadLocations(config, { appFolder.also { it.createDirectories() } }, FakeVolumes(volumes))
    val validator = DownloadFolderValidator(
        locations = locations,
        appDataDir = appData,
        cacheDir = root.resolve("cache"),
        programDirs = { listOf(root.resolve("program")) },
    )

    /** A drive that is plugged in, and a folder on it registered as a location. */
    fun driveLocation(drive: String, folder: String = "Movies"): DesktopDownloadLocation {
        val directory = volumes.resolve(drive).resolve(folder).createDirectories()
        return locations.register(directory)
    }

    fun unplug(drive: String) {
        Files.move(volumes.resolve(drive), root.resolve("$drive.unplugged"))
    }

    fun replug(drive: String) {
        Files.move(root.resolve("$drive.unplugged"), volumes.resolve(drive))
    }

    fun close() {
        root.toFile().deleteRecursively()
    }
}

/** A small, valid video file with [mediaBytes] of media that differ by [seed]. Big enough for the file's hash when above 128 KB. */
internal fun videoFile(seed: Int, mediaBytes: Int = 200_000): ByteArray = mp4File(1) + Random(seed).nextBytes(mediaBytes)

internal fun Path.writeVideo(seed: Int, mediaBytes: Int = 200_000): Path {
    parent?.createDirectories()
    Files.write(this, videoFile(seed, mediaBytes))
    return this
}

internal fun episodeItem(
    show: String,
    episode: Int,
    status: DownloadStatus = DownloadStatus.Completed,
    locationId: String? = null,
    file: Path? = null,
    bytes: Long = 0L,
    ruleKey: String? = null,
    updatedAt: Long = 2L,
    season: Int = 1,
) = DownloadItem(
    id = "$show-$season-$episode",
    contentType = "series",
    parentMetaId = show,
    parentMetaType = "series",
    videoId = "$show:$season:$episode",
    title = "Show $show",
    seasonNumber = season,
    episodeNumber = episode,
    streamTitle = "1080p",
    providerName = "Addon",
    sourceUrl = "https://example.com/$show/$episode.mp4",
    localFileUri = file?.toFile()?.toURI()?.toString(),
    fileName = "$show-s${season}e$episode.mp4",
    status = status,
    downloadedBytes = bytes,
    totalBytes = bytes.takeIf { it > 0L },
    createdAtEpochMs = 1L,
    updatedAtEpochMs = updatedAt,
    autoDownloadRuleKey = ruleKey,
    locationId = locationId,
)

internal fun movieItem(id: String, locationId: String? = null, file: Path? = null, bytes: Long = 0L) = DownloadItem(
    id = id,
    contentType = "movie",
    parentMetaId = "tt$id",
    parentMetaType = "movie",
    videoId = "tt$id",
    title = "Film $id",
    streamTitle = "1080p",
    providerName = "Addon",
    sourceUrl = "https://example.com/$id.mp4",
    localFileUri = file?.toFile()?.toURI()?.toString(),
    fileName = "$id.mp4",
    status = DownloadStatus.Completed,
    downloadedBytes = bytes,
    totalBytes = bytes.takeIf { it > 0L },
    createdAtEpochMs = 1L,
    updatedAtEpochMs = 2L,
    locationId = locationId,
)

/** A backend over real temporary locations, for the list of downloads. Nothing is fetched. */
internal class LocationsBackend(private val world: FolderWorld) : DownloadsBackend {
    var payload: String? = null
    val trashed = mutableListOf<String>()
    val removedOutright = mutableListOf<String?>()
    var trashSucceeds = true
    val started = mutableListOf<String>()

    override fun loadPayload(): String? = payload
    override fun savePayload(payload: String) {
        this.payload = payload
    }

    override fun restoreItem(item: DownloadItem): DownloadItem = item

    override fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        started += request.item.id
        return object : DownloadsTaskHandle {
            override fun cancel() = Unit
        }
    }

    override fun removeFile(localFileUri: String?): Boolean {
        removedOutright += localFileUri
        return true
    }

    override fun removePartialFile(destinationFileName: String): Boolean = true

    override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
        (world.locations.locate(null, localFileUri, destinationFileName) as? FileLookup.Found)?.uri

    override fun inspectFile(localFileUri: String): DownloadedFileFacts? = inspectDownloadedFile(localFileUri)

    override fun trashFile(localFileUri: String): Boolean {
        if (trashSucceeds) trashed += localFileUri
        return trashSucceeds
    }

    override fun locate(item: DownloadItem): FileLookup = world.locations.locate(item.locationId, item.localFileUri, item.fileName)

    override fun isConnected(item: DownloadItem): Boolean =
        world.locations.availability(world.locations.locationOfRecord(item.locationId, item.localFileUri)) == FolderAvailability.Available

    override fun locationConnected(locationId: String?): Boolean =
        world.locations.location(locationId)?.let { world.locations.availability(it) == FolderAvailability.Available } ?: false

    override fun heldByFolder(item: DownloadItem): Boolean = !isConnected(item)

    override fun placementFor(items: List<DownloadItem>, parentMetaId: String, isEpisode: Boolean): String =
        placementForNewDownload(items, parentMetaId, isEpisode, world.locations.defaultLocation().id) {
            world.locations.locationOfRecord(it.locationId, it.localFileUri).id
        }
}

internal fun storedPayload(items: List<DownloadItem>): String {
    val json = kotlinx.serialization.json.Json { encodeDefaults = true }
    return """{"items":[${items.joinToString(",") { json.encodeToString(DownloadItem.serializer(), it) }}]}"""
}
