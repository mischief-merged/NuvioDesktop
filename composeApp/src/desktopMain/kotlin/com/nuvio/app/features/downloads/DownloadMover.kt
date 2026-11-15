package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

@Serializable
internal enum class MoveKind {
    /** The finished file of a download. */
    File,

    /** The partial file of a download that is not finished. */
    Partial,

    /** Nothing to carry: the record only changes location. */
    RecordOnly,
}

@Serializable
internal data class MoveEntry(
    val itemId: String,
    /** The file name in the destination. */
    val name: String,
    /** Where the file is now. Empty for [MoveKind.RecordOnly]. */
    val from: String,
    val kind: MoveKind,
    val size: Long,
    /** The OpenSubtitles hash of the original, when it could be worked out. */
    val hash: String? = null,
)

@Serializable
internal data class MoveJournal(
    val version: Int = 1,
    val destination: String,
    val destinationId: String,
    val pausedIds: List<String> = emptyList(),
    val entries: List<MoveEntry>,
)

internal class MovePlan(
    val destination: Path,
    val destinationId: String,
    val entries: List<MoveEntry>,
    val pausedIds: List<String> = emptyList(),
)

internal data class RecoveryReport(
    val finished: Int,
    val rolledBack: Int,
    val conflicts: Int,
    val pausedIds: List<String>,
)

/** Thrown inside a copy when the user cancelled. */
internal class MoveCancelled : Exception()

/**
 * Moves files to another folder, one at a time, so that every stopping point is consistent.
 *
 * Nothing is deleted until its copy has been checked, and a copy is never deleted unless the
 * original is still there with the same size. A journal in [journalFile] names what a move was
 * doing, so [recover] can settle an interrupted move by looking at the disk.
 */
internal class DownloadMover(
    private val journalFile: Path,
    /** Called at every step that matters. Tests make it throw to simulate a crash there. */
    private val checkpoint: (String) -> Unit = {},
    private val copy: (from: Path, to: Path, onBytes: (Long) -> Unit, cancelled: () -> Boolean) -> Unit = ::copyFile,
    private val hashOf: (Path) -> String? = { path -> inspectDownloadedFile(path.toFile().toURI().toString())?.videoHash },
    /** Whether a file and a folder are on one volume, where a rename is atomic and carries no bytes. */
    private val sameStore: (Path, Path) -> Boolean = ::onSameFileStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun hasJournal(): Boolean = Files.isRegularFile(journalFile)

    fun run(
        plan: MovePlan,
        isCancelled: () -> Boolean,
        onProgress: (MoveProgress) -> Unit,
        /** The file of [MoveEntry] is now at [Path] (null for a record-only entry). The record must follow. */
        onMoved: (MoveEntry, Path?) -> Unit,
    ): MoveReport {
        val destination = plan.destination
        val destinationName = destination.fileName?.toString() ?: destination.toString()
        val entries = plan.entries.map { entry ->
            if (entry.kind == MoveKind.File && entry.hash == null) entry.copy(hash = runCatching { hashOf(Paths.get(entry.from)) }.getOrNull()) else entry
        }
        writeJournal(MoveJournal(destination = destination.toString(), destinationId = plan.destinationId, pausedIds = plan.pausedIds, entries = entries))
        checkpoint("journal-written")

        val totalBytes = entries.sumOf { it.size }
        var doneBytes = 0L
        var doneFiles = 0
        var moved = 0
        var notReached = 0
        var cancelled = false
        val failures = mutableListOf<MoveFailure>()
        fun progress(current: MoveEntry?, currentBytes: Long = 0L) = onProgress(
            MoveProgress(
                destinationName = destinationName,
                totalFiles = entries.size,
                doneFiles = doneFiles,
                totalBytes = totalBytes,
                doneBytes = doneBytes + currentBytes,
                currentName = current?.name,
                currentBytes = currentBytes,
                currentTotalBytes = current?.size ?: 0L,
                cancelling = isCancelled(),
            ),
        )
        progress(null)
        for ((index, entry) in entries.withIndex()) {
            if (isCancelled()) {
                cancelled = true
                notReached = entries.size - index
                break
            }
            progress(entry)
            try {
                val failure = moveEntry(entry, destination, isCancelled, onBytes = { progress(entry, it) }, onMoved)
                if (failure == null) moved++ else failures += MoveFailure(entry.name.removeSuffix(".part"), failure)
            } catch (_: MoveCancelled) {
                cancelled = true
                notReached = entries.size - index
                break
            }
            doneBytes += entry.size
            doneFiles++
            progress(null)
        }
        Files.deleteIfExists(journalFile)
        return MoveReport(
            destinationPath = destination.toString(),
            destinationName = destinationName,
            moved = moved,
            failures = failures,
            cancelled = cancelled,
            notReached = notReached,
        )
    }

    private fun moveEntry(
        entry: MoveEntry,
        destination: Path,
        isCancelled: () -> Boolean,
        onBytes: (Long) -> Unit,
        onMoved: (MoveEntry, Path?) -> Unit,
    ): MoveFailureReason? {
        if (entry.kind == MoveKind.RecordOnly) {
            onMoved(entry, null)
            return null
        }
        val source = Paths.get(entry.from)
        val target = destination.resolve(entry.name)
        val temporary = destination.resolve(entry.name + MovingSuffix)
        if (!Files.isRegularFile(source)) return MoveFailureReason.CouldNotRead
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return MoveFailureReason.NameTaken
        try {
            Files.deleteIfExists(temporary)
            if (sameStore(source, destination)) {
                val renamed = try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                    true
                } catch (_: AtomicMoveNotSupportedException) {
                    false
                } catch (_: FileSystemException) {
                    false
                }
                if (renamed) {
                    checkpoint("renamed")
                    moveCompanions(source, target)
                    onMoved(entry, target)
                    checkpoint("record-updated")
                    onBytes(entry.size)
                    return null
                }
            }
            val sizeBefore = Files.size(source)
            copy(source, temporary, onBytes, isCancelled)
            checkpoint("copied")
            if (Files.size(source) != sizeBefore) {
                Files.deleteIfExists(temporary)
                return MoveFailureReason.ChangedWhileCopying
            }
            if (Files.size(temporary) != sizeBefore) {
                Files.deleteIfExists(temporary)
                return MoveFailureReason.CopyDidNotMatch
            }
            if (entry.kind == MoveKind.File && entry.hash != null && hashOf(temporary) != entry.hash) {
                Files.deleteIfExists(temporary)
                return MoveFailureReason.CopyDidNotMatch
            }
            checkpoint("verified")
            moveWithinFolder(temporary, target)
            checkpoint("renamed")
            moveCompanions(source, target)
            onMoved(entry, target)
            checkpoint("record-updated")
            Files.deleteIfExists(source)
            deleteCompanionsIfCopied(source, target)
            checkpoint("original-removed")
            return null
        } catch (cancel: MoveCancelled) {
            runCatching { Files.deleteIfExists(temporary) }
            throw cancel
        } catch (error: NoSuchFileException) {
            runCatching { Files.deleteIfExists(temporary) }
            return if (error.file == entry.from) MoveFailureReason.CouldNotRead else MoveFailureReason.CouldNotWrite
        } catch (error: IOException) {
            runCatching { Files.deleteIfExists(temporary) }
            return if (error.message.orEmpty().contains("space", ignoreCase = true)) MoveFailureReason.NoSpace else MoveFailureReason.CouldNotWrite
        } catch (error: SecurityException) {
            runCatching { Files.deleteIfExists(temporary) }
            return MoveFailureReason.CouldNotWrite
        }
    }

    /**
     * Settles a move that was cut short, by what is on the disk. A copy that is whole and checked is
     * finished by removing the original. Anything else is rolled back by removing what this move
     * made. An original is removed only beside a checked copy; a copy is removed only beside an
     * intact original.
     */
    fun recover(): RecoveryReport? {
        if (!Files.isRegularFile(journalFile)) return null
        val journal = runCatching { json.decodeFromString<MoveJournal>(Files.readString(journalFile)) }.getOrNull()
        if (journal == null) {
            runCatching { Files.move(journalFile, journalFile.resolveSibling(journalFile.fileName.toString() + ".unreadable"), StandardCopyOption.REPLACE_EXISTING) }
            return null
        }
        val destination = Paths.get(journal.destination)
        var finished = 0
        var rolledBack = 0
        var conflicts = 0
        journal.entries.filter { it.kind != MoveKind.RecordOnly }.forEach { entry ->
            val source = Paths.get(entry.from)
            val target = destination.resolve(entry.name)
            val temporary = destination.resolve(entry.name + MovingSuffix)
            val sourceThere = Files.isRegularFile(source)
            val targetThere = Files.isRegularFile(target)
            if (sourceThere || targetThere) runCatching { Files.deleteIfExists(temporary) }
            when {
                sourceThere && targetThere -> when {
                    isWhole(target, entry) -> {
                        Files.deleteIfExists(source)
                        deleteCompanionsIfCopied(source, target)
                        finished++
                    }
                    runCatching { Files.size(source) == entry.size }.getOrDefault(false) -> {
                        Files.deleteIfExists(target)
                        rolledBack++
                    }
                    else -> conflicts++
                }
                sourceThere -> rolledBack++
                targetThere -> finished++
            }
        }
        Files.deleteIfExists(journalFile)
        return RecoveryReport(finished, rolledBack, conflicts, journal.pausedIds)
    }

    private fun isWhole(target: Path, entry: MoveEntry): Boolean =
        runCatching { Files.size(target) == entry.size }.getOrDefault(false) &&
            (entry.kind != MoveKind.File || entry.hash == null || hashOf(target) == entry.hash)

    private fun moveWithinFolder(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to)
        }
    }

    private fun companionOf(video: Path): Path = video.resolveSibling(video.fileName.toString() + SubtitlesSuffix)

    /** The subtitles folder beside the video follows it. Its failure never fails the move: they stay where they were. */
    private fun moveCompanions(source: Path, target: Path) {
        val from = companionOf(source)
        val to = companionOf(target)
        if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS) || Files.exists(to)) return
        runCatching {
            if (sameStore(from, to.parent)) {
                try {
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                    return
                } catch (_: Exception) {
                    // Copied below.
                }
            }
            Files.createDirectories(to)
            Files.list(from).use { stream ->
                stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { file ->
                    Files.copy(file, to.resolve(file.fileName.toString()), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    /** Removes the old subtitles folder only when every file in it is also in the new one. */
    private fun deleteCompanionsIfCopied(source: Path, target: Path) {
        val from = companionOf(source)
        val to = companionOf(target)
        if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(to)) return
        runCatching {
            val files = Files.list(from).use { stream -> stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList() }
            val allThere = files.all { file ->
                val copy = to.resolve(file.fileName.toString())
                Files.isRegularFile(copy) && Files.size(copy) == Files.size(file)
            }
            if (allThere) {
                files.forEach { Files.deleteIfExists(it) }
                Files.deleteIfExists(from)
            }
        }
    }

    private fun writeJournal(journal: MoveJournal) {
        journalFile.parent?.let { Files.createDirectories(it) }
        val temporary = journalFile.resolveSibling(journalFile.fileName.toString() + ".tmp")
        FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            channel.write(java.nio.ByteBuffer.wrap(json.encodeToString(journal).toByteArray(Charsets.UTF_8)))
            channel.force(true)
        }
        try {
            Files.move(temporary, journalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, journalFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val MovingSuffix = ".moving"
        const val SubtitlesSuffix = ".subtitles"

        fun copyFile(from: Path, to: Path, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
            var written = 0L
            Files.newInputStream(from).use { input ->
                FileChannel.open(to, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        if (cancelled()) throw MoveCancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(java.nio.ByteBuffer.wrap(buffer, 0, read))
                        written += read
                        onBytes(written)
                    }
                    output.force(true)
                }
            }
        }
    }
}

internal fun onSameFileStore(source: Path, destination: Path): Boolean =
    runCatching { Files.getFileStore(source) == Files.getFileStore(destination) }.getOrDefault(false)
