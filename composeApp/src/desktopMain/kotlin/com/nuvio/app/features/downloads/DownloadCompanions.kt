package com.nuvio.app.features.downloads

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** A file that travels with a video: where it is, and its name relative to the folder the video is in. */
internal data class CompanionFile(val path: Path, val relativeName: String)

internal class Companions(val files: List<CompanionFile>, val skippedLinks: List<Path>)

/**
 * The one rule for which files belong to a download besides its video (and its own `.part` while it is
 * unfinished): the contents of its dedicated sidecar folder, `<video file name>.subtitles/`, at any depth.
 * That is the single place the offline feature keeps subtitles, skip times, episode lists and artwork.
 * Files that merely share a base name with the video are never companions: they may be another
 * download's. Links are never companions: they are skipped and reported, never followed or moved.
 */
internal object DownloadCompanions {
    const val SidecarSuffix = ".subtitles"

    /** The name of the sidecar folder of a video. */
    fun sidecarFolderName(videoFileName: String): String = videoFileName + SidecarSuffix

    fun of(video: Path): Companions {
        val folder = video.parent ?: return Companions(emptyList(), emptyList())
        val sidecar = folder.resolve(sidecarFolderName(video.fileName.toString()))
        val files = mutableListOf<CompanionFile>()
        val links = mutableListOf<Path>()
        if (Files.isSymbolicLink(sidecar)) {
            links.add(sidecar)
        } else if (Files.isDirectory(sidecar, LinkOption.NOFOLLOW_LINKS)) {
            collect(sidecar, sidecar.fileName.toString(), files, links)
        }
        return Companions(files, links)
    }

    private fun collect(directory: Path, relative: String, files: MutableList<CompanionFile>, links: MutableList<Path>) {
        val children = runCatching { Files.list(directory).use { it.toList() } }.getOrDefault(emptyList())
        children.sortedBy { it.fileName.toString() }.forEach { child ->
            val childName = "$relative/${child.fileName}"
            when {
                Files.isSymbolicLink(child) -> links.add(child)
                Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> files.add(CompanionFile(child, childName))
                Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> collect(child, childName, files, links)
            }
        }
    }
}
