package com.nuvio.app.features.downloads

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** A file that travels with a video: where it is, and its name relative to the folder the video is in. */
internal data class CompanionFile(val path: Path, val relativeName: String)

internal class Companions(val files: List<CompanionFile>, val skippedLinks: List<Path>)

/**
 * The one rule for which files belong to a download, so that every part of the app that moves, trashes
 * or lists a download agrees.
 *
 * A file is a companion of the video `base.ext` when it is in the same folder, is not the video, and
 * its name starts with `base.`: `base.mp4.subtitles`, `base.skiptimes.json`, `base.en.srt`. If that
 * is a folder, every regular file inside it, at any depth, travels with it. Files that are being
 * written (`.part`, `.moving`, `.tmp`, and this app's `.nuvio-` names) never are. Links are never
 * companions: they are skipped and reported, and never followed or moved.
 */
internal object DownloadCompanions {
    /** The name of a video without its last extension. */
    fun baseName(video: Path): String {
        val name = video.fileName.toString()
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    fun isCompanionName(videoName: String, candidate: String): Boolean {
        val dot = videoName.lastIndexOf('.')
        val base = if (dot > 0) videoName.substring(0, dot) else videoName
        return candidate != videoName && candidate.startsWith("$base.") && !isTransient(candidate)
    }

    private fun isTransient(name: String): Boolean =
        name.startsWith(".nuvio-") || name.endsWith(".part") || name.endsWith(".moving") || name.endsWith(".tmp")

    fun of(video: Path): Companions {
        val folder = video.parent ?: return Companions(emptyList(), emptyList())
        val files = mutableListOf<CompanionFile>()
        val links = mutableListOf<Path>()
        val entries = runCatching { Files.list(folder).use { it.toList() } }.getOrDefault(emptyList())
        entries.sortedBy { it.fileName.toString() }.forEach { entry ->
            val name = entry.fileName.toString()
            if (!isCompanionName(video.fileName.toString(), name)) return@forEach
            when {
                Files.isSymbolicLink(entry) -> links.add(entry)
                Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) -> files += CompanionFile(entry, name)
                Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) -> collect(entry, name, files, links)
            }
        }
        return Companions(files, links)
    }

    private fun collect(directory: Path, relative: String, files: MutableList<CompanionFile>, links: MutableList<Path>) {
        val children = runCatching { Files.list(directory).use { it.toList() } }.getOrDefault(emptyList())
        children.sortedBy { it.fileName.toString() }.forEach { child ->
            val childName = "$relative/${child.fileName}"
            when {
                Files.isSymbolicLink(child) -> links.add(child)
                Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> files += CompanionFile(child, childName)
                Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> collect(child, childName, files, links)
            }
        }
    }
}
