package com.nuvio.app.features.downloads

import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The folder beside a video, `<video file name>.subtitles/`, where everything saved for it lives. The app
 * only ever writes into, replaces in or removes from a folder it made itself, which it marks when it makes
 * it ([SidecarOwnership.MarkerName], created with create-new). A folder of that name that is already there
 * without the mark is someone else's: nothing is written there, unless its contents are exactly what this
 * app saves (a one-time adoption for folders made before folders were marked).
 */
internal actual class DownloadSubtitleStorage actual constructor(localVideoUri: String) {
    private val video = File(URI(localVideoUri))
    private val directory = File(video.path + ".subtitles")

    actual fun read(fileName: String): String? =
        runCatching { file(fileName).takeIf(File::isFile)?.readText(Charsets.UTF_8) }.getOrNull()

    actual fun write(fileName: String, text: String) = writeBytes(fileName, text.toByteArray(Charsets.UTF_8))

    /**
     * Writes to a temporary file of its own (created with create-new, never through a link) and renames it
     * over the target, so that a crash leaves the old file or the new one and never half of one. Only in a
     * folder of the app's own; anywhere else this refuses and writes nothing.
     */
    actual fun writeBytes(fileName: String, bytes: ByteArray) {
        val target = file(fileName)
        if (!SidecarOwnership.ensureOwned(directory.toPath(), video.name)) throw IOException("The folder ${directory.name} is not the app's")
        SidecarOwnership.removeOwnTemporaries(directory.toPath())
        val temporary = directory.toPath().resolve("${SidecarOwnership.TemporaryPrefix}${DownloadMover.randomHex(8)}${SidecarOwnership.TemporarySuffix}")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            // The target is a file of the app's own, in a folder of the app's own.
            try {
                Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    actual fun localFileUri(fileName: String): String? =
        file(fileName).takeIf(File::isFile)?.toURI()?.toString()

    /** Removes the folder only when it is the app's own. */
    actual fun remove() {
        if (SidecarOwnership.isOwned(directory.toPath(), video.name)) directory.deleteRecursively()
    }

    actual fun refused(): Boolean = SidecarOwnership.refuses(directory.toPath(), video.name)

    private fun file(fileName: String): File {
        require(
            fileName.isNotBlank() && File(fileName).name == fileName && fileName != "." && fileName != ".." &&
                !fileName.endsWith(SidecarOwnership.TemporarySuffix) && fileName != SidecarOwnership.MarkerName,
        )
        return File(directory, fileName)
    }
}

/** Which sidecar folders are the app's own, and the one-time adoption of folders made before they were marked. */
internal object SidecarOwnership {
    const val MarkerName = ".nuvio-sidecar"
    const val TemporaryPrefix = ".nuvio-tmp-"
    const val TemporarySuffix = ".tmp"
    private const val MarkerHeader = "nuvio-sidecar 1"

    /** Names the app saves besides the subtitle files a manifest lists. */
    private val knownNames: Set<String> = setOf("manifest.json", SkipTimesSidecar.FILE_NAME, EpisodeListSidecar.FILE_NAME)
    private val artworkStems: Set<String> = ArtworkRole.entries.map { it.fileStem }.toSet()

    private fun markerText(videoName: String) = "$MarkerHeader\nvideo=$videoName\n"

    /** The folder is there, is a plain folder, and carries the app's mark for [videoName]. */
    fun isOwned(folder: Path, videoName: String): Boolean {
        if (Files.isSymbolicLink(folder) || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return false
        val marker = folder.resolve(MarkerName)
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return false
        return runCatching { Files.size(marker) < 4096 && String(Files.readAllBytes(marker), Charsets.UTF_8) == markerText(videoName) }.getOrDefault(false)
    }

    /** A folder of that name is there and is not the app's (and cannot be adopted): nothing may be saved in it. */
    fun refuses(folder: Path, videoName: String): Boolean =
        Files.exists(folder, LinkOption.NOFOLLOW_LINKS) && !isOwned(folder, videoName) && !adoptable(folder)

    /**
     * Makes sure the folder is the app's own: creates and marks it when it is not there; adopts it, once, when
     * its contents are exactly what the app saves; otherwise false, and nothing is touched.
     */
    fun ensureOwned(folder: Path, videoName: String): Boolean {
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(folder)
            } catch (_: FileAlreadyExistsException) {
                return ensureOwned(folder, videoName)
            }
            return createMarker(folder, videoName)
        }
        if (isOwned(folder, videoName)) return true
        if (Files.exists(folder.resolve(MarkerName), LinkOption.NOFOLLOW_LINKS)) return false
        return adoptable(folder) && createMarker(folder, videoName)
    }

    /**
     * A folder made by this app before folders were marked: a plain folder with only plain files, a
     * `manifest.json` that reads as this app's manifest and lists exactly the subtitle files present (when
     * there are any), and nothing else but the names the app saves (skip times, episode list, artwork, and
     * the leftovers of its own interrupted writes).
     */
    fun adoptable(folder: Path): Boolean = runCatching {
        if (Files.isSymbolicLink(folder) || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return false
        val entries = Files.list(folder).use { it.toList() }
        if (entries.any { Files.isSymbolicLink(it) || !Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }) return false
        val names = entries.map { it.fileName.toString() }.filterNot { it.endsWith(TemporarySuffix) && isOursTemporary(it) }
        val manifest = folder.resolve("manifest.json")
        val listed = if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            subtitleManifestFileNames(String(Files.readAllBytes(manifest), Charsets.UTF_8)) ?: return false
        } else {
            emptyList()
        }
        val others = names.filterNot { it in knownNames || isArtwork(it) }
        others.toSet() == listed.toSet()
    }.getOrDefault(false)

    private fun isArtwork(name: String) = name.substringBeforeLast('.', "") in artworkStems
    private fun isOursTemporary(name: String) = name.startsWith(TemporaryPrefix) || name.removeSuffix(TemporarySuffix).let { it in knownNames || isArtwork(it) || it.endsWith(".srt") || it.endsWith(".vtt") }

    /** Removes temporary files of the app's own writes. Called only for a folder already proven to be the app's. */
    fun removeOwnTemporaries(folder: Path) {
        runCatching {
            Files.list(folder).use { it.toList() }
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) && it.fileName.toString().endsWith(TemporarySuffix) && isOursTemporary(it.fileName.toString()) }
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun createMarker(folder: Path, videoName: String): Boolean = runCatching {
        FileChannel.open(folder.resolve(MarkerName), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val bytes = ByteBuffer.wrap(markerText(videoName).toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        true
    }.getOrDefault(false)
}
