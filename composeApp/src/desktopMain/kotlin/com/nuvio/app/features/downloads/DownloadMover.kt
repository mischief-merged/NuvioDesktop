package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

@Serializable
internal enum class MoveKind {
    /** The finished video of a download. */
    File,

    /** The partial file of a download that is not finished. */
    Partial,

    /** A file that travels with a video (see [DownloadCompanions]). */
    Companion,

    /** Nothing to carry: the record only changes location. */
    RecordOnly,
}

/** How far one file got. Each step is written to the journal, and flushed, before the next one starts. */
@Serializable
internal enum class EntryState {
    Planned,

    /** The copy to its own temporary name has started. That name is this move's, and only this move's. */
    Copying,

    /** The copy is complete and its hash of the whole file is in the journal. Not yet at its final name. */
    Copied,

    /** At its final name, and read back whole once: the hash matched. Only now is it this move's file. */
    Published,

    /** The original is gone. */
    Removed,
}

@Serializable
internal enum class RecordState {
    Pending,

    /** Written before the record is changed, so a crash after it cannot tell whether the record was. */
    Updating,
    Updated,
}

@Serializable
internal data class MoveEntry(
    /** The name in the destination, relative to its folder. */
    val name: String,
    /** Where the file is now. Empty for [MoveKind.RecordOnly]. */
    val from: String,
    val kind: MoveKind,
    val size: Long = 0L,
    /** SHA-256 of the whole file, worked out while it was copied. */
    val hash: String? = null,
    /** The temporary name in the destination, unique to this move. Empty when the file is linked. */
    val temp: String = "",
    /** Moved by a hard link on one volume: no bytes are copied and the file is the same one. */
    val linked: Boolean = false,
    val state: EntryState = EntryState.Planned,
)

@Serializable
internal data class MoveItem(
    val itemId: String,
    val entries: List<MoveEntry>,
    val record: RecordState = RecordState.Pending,
    /** Stopped before the record changed, and cleaned up. */
    val failed: Boolean = false,
) {
    val displayName: String
        get() = entries.firstOrNull { it.kind != MoveKind.Companion }?.name?.removeSuffix(".part") ?: itemId

    val mainName: String? get() = entries.firstOrNull { it.kind == MoveKind.File || it.kind == MoveKind.Partial }?.name
}

@Serializable
internal data class MoveJournal(
    val version: Int = 2,
    val txId: String,
    val destination: String,
    val destinationId: String,
    val pausedIds: List<String> = emptyList(),
    val items: List<MoveItem>,
)

internal class MovePlan(
    val destination: Path,
    val destinationId: String,
    val items: List<MoveItem>,
    val pausedIds: List<String> = emptyList(),
)

internal enum class RecordSide { AtDestination, AtSource, Missing }

internal data class RecoveryReport(
    val finished: Int,
    val rolledBack: Int,
    /** Items left exactly as they were because nothing proved what this move did to them. */
    val left: Int,
    val pausedIds: List<String>,
    /** Items whose record could not be told apart yet. They are settled when the list is loaded. */
    val unresolved: List<String> = emptyList(),
    /** Originals that could not be removed again. The journal keeps them for the next start. */
    val originalsKept: Int = 0,
)

/** Thrown inside a copy when the user cancelled. */
internal class MoveCancelled : Exception()

/** A step that ends the whole move. */
private class StopMove(val reason: MoveFailureReason) : Exception()

/**
 * Moves downloads to another folder. The rule it keeps: it deletes or replaces only a file it can
 * prove it made in this transaction, and deletes an original only after a copy that was hashed whole
 * has been published, read back, and the record has been saved.
 *
 * One download goes through these steps, each written to the journal (file and folder flushed)
 * before the next:
 *
 * 1. planned. Names in the destination are reserved; a name that exists, in any letter case, fails the item.
 * 2. copying to a temporary name made of this move's id. Same volume: a hard link instead, no bytes.
 * 3. copied: the hash of the whole file, taken while it was read, is in the journal.
 * 4. published: a hard link to the final name, which cannot replace a file (or a rename that does
 *    not replace, where links are not supported), then the file is read back once and its hash compared.
 * 5. record updating, the record changed and saved, record updated.
 * 6. the original is removed and its folder flushed. If it cannot be, or folders cannot be flushed
 *    here, it stays, the item counts as moved, and the journal keeps it for the next start.
 *
 * [recover] acts only on what the journal proves; anything else is left as it is.
 */
internal class DownloadMover(
    private val journalFile: Path,
    /** Called at every step that matters. Tests make it throw to simulate a crash there. */
    private val checkpoint: (String) -> Unit = {},
    private val copy: (from: Path, to: Path, onBytes: (Long) -> Unit, cancelled: () -> Boolean) -> String = ::copyFile,
    private val hashOf: (Path) -> String = ::sha256Of,
    /** Whether a file and a folder are on one volume. */
    private val sameStore: (Path, Path) -> Boolean = ::onSameFileStore,
    /** Flushes a folder to disk. False where the platform cannot. */
    private val syncDirectory: (Path) -> Boolean = ::flushDirectory,
    private val deleteFile: (Path) -> Unit = { Files.delete(it) },
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun hasJournal(): Boolean = Files.isRegularFile(journalFile)

    fun run(
        plan: MovePlan,
        isCancelled: () -> Boolean,
        onProgress: (MoveProgress) -> Unit,
        /** Asked just before the record changes: does it still exist and still point where the move found it? */
        canCommit: (MoveItem) -> Boolean = { true },
        /** The files of the item are now in the destination; [Path] is the main file (null for a record-only item). */
        onMoved: (MoveItem, Path?) -> Unit,
    ): MoveReport = Transaction(plan, isCancelled, onProgress, canCommit, onMoved).run()

    private inner class Transaction(
        val plan: MovePlan,
        val isCancelled: () -> Boolean,
        val onProgress: (MoveProgress) -> Unit,
        val canCommit: (MoveItem) -> Boolean,
        val onMoved: (MoveItem, Path?) -> Unit,
    ) {
        val destination: Path = plan.destination
        val destinationName: String = destination.fileName?.toString() ?: destination.toString()
        val txId: String = UUID.randomUUID().toString().take(12)
        lateinit var journal: MoveJournal
        var totalFiles = 0
        var totalBytes = 0L
        var doneFiles = 0
        var doneBytes = 0L

        fun save() = writeJournal(journal)

        fun updateItem(index: Int, change: (MoveItem) -> MoveItem) {
            journal = journal.copy(items = journal.items.mapIndexed { i, item -> if (i == index) change(item) else item })
            save()
        }

        fun updateEntry(index: Int, entry: Int, change: (MoveEntry) -> MoveEntry) =
            updateItem(index) { item -> item.copy(entries = item.entries.mapIndexed { i, e -> if (i == entry) change(e) else e }) }

        fun progress(current: String?, currentBytes: Long = 0L, currentTotal: Long = 0L) = onProgress(
            MoveProgress(destinationName, totalFiles, doneFiles, totalBytes, doneBytes + currentBytes, current, currentBytes, currentTotal, isCancelled()),
        )

        fun run(): MoveReport {
            val failures = mutableListOf<MoveFailure>()
            val reserved = mutableSetOf<String>()
            var counter = 0
            val accepted = mutableListOf<MoveItem>()
            plan.items.forEach { item ->
                // Two entries must never aim at one name, whatever the case, within this move.
                val clash = item.entries.filter { it.kind != MoveKind.RecordOnly }.any { !reserved.add(it.name.lowercase()) }
                if (clash) {
                    failures += MoveFailure(item.displayName, MoveFailureReason.NameTaken)
                } else {
                    accepted += item.copy(
                        entries = item.entries.map { e ->
                            if (e.kind == MoveKind.RecordOnly) e else e.copy(temp = ".nuvio-$txId-${counter++}.tmp")
                        },
                    )
                }
            }
            journal = MoveJournal(txId = txId, destination = destination.toString(), destinationId = plan.destinationId, pausedIds = plan.pausedIds, items = accepted)
            totalFiles = accepted.size
            totalBytes = accepted.sumOf { item -> item.entries.sumOf { it.size } }
            save()
            checkpoint("journal-written")
            var moved = 0
            var notReached = 0
            var cancelled = false
            val kept = mutableListOf<MoveFailure>()
            progress(null)
            for (index in accepted.indices) {
                if (isCancelled()) {
                    cancelled = true
                    notReached = accepted.size - index
                    break
                }
                val item = journal.items[index]
                progress(item.displayName, 0L, item.entries.sumOf { it.size })
                try {
                    val result = processItem(index)
                    if (result == null) {
                        moved++
                        kept += keptOriginals(index)
                    } else {
                        failures += MoveFailure(item.displayName, result)
                    }
                } catch (_: MoveCancelled) {
                    cleanTemps(index)
                    updateItem(index) { it.copy(failed = true) }
                    cancelled = true
                    notReached = accepted.size - index
                    break
                } catch (stop: StopMove) {
                    cleanTemps(index)
                    updateItem(index) { it.copy(failed = true) }
                    failures += MoveFailure(item.displayName, stop.reason)
                    notReached = accepted.size - index - 1
                    break
                }
                doneBytes += item.entries.sumOf { it.size }
                doneFiles++
                progress(null)
            }
            if (journal.items.all(::isSettled)) {
                Files.deleteIfExists(journalFile)
                syncDirectory(journalFile.parent ?: journalFile)
            }
            return MoveReport(
                destinationPath = destination.toString(),
                destinationName = destinationName,
                moved = moved,
                failures = failures,
                cancelled = cancelled,
                notReached = notReached,
                originalsKept = kept,
            )
        }

        private fun keptOriginals(index: Int): List<MoveFailure> {
            val item = journal.items[index]
            return item.entries.filter { it.kind != MoveKind.RecordOnly && it.state != EntryState.Removed }
                .map { MoveFailure(it.name, MoveFailureReason.OriginalNotRemoved, lastRemovalError[it.from]) }
        }

        private val lastRemovalError = mutableMapOf<String, String?>()

        /** Null when moved. Otherwise why not, with everything this move made removed again. */
        private fun processItem(index: Int): MoveFailureReason? {
            val item = journal.items[index]
            val files = item.entries.withIndex().filter { it.value.kind != MoveKind.RecordOnly }
            // Look before touching anything.
            for ((_, e) in files) {
                val source = Paths.get(e.from)
                if (Files.isSymbolicLink(source)) return MoveFailureReason.Symlink
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) return MoveFailureReason.CouldNotRead
                if (existsIgnoringCase(destination.resolve(e.name))) return MoveFailureReason.NameTaken
            }
            val published = mutableListOf<Int>()
            var durable = true
            try {
                if (files.isNotEmpty()) {
                    val linked = files.all { (_, e) -> sameStore(Paths.get(e.from), destination) } && canLink(Paths.get(files.first().value.from))
                    if (linked) {
                        updateItem(index) { it.copy(entries = it.entries.map { e -> if (e.kind == MoveKind.RecordOnly) e else e.copy(linked = true, temp = "") }) }
                    }
                    if (!linked) {
                        for ((ei, e) in files) copyOne(index, ei, e)
                    }
                    for ((ei, _) in files) {
                        val entry = journal.items[index].entries[ei]
                        publishOne(index, ei, entry)?.let { reason ->
                            rollback(index, published)
                            return reason
                        }
                        durable = durable && syncDirectory(destination.resolve(entry.name).parent ?: destination)
                        updateEntry(index, ei) { it.copy(state = EntryState.Published) }
                        published += ei
                        checkpoint("published")
                        val temp = journal.items[index].entries[ei].temp
                        if (temp.isNotEmpty()) Files.deleteIfExists(destination.resolve(temp))
                    }
                }
                if (!canCommit(journal.items[index])) {
                    rollback(index, published)
                    return MoveFailureReason.RecordChanged
                }
                updateItem(index) { it.copy(record = RecordState.Updating) }
                checkpoint("record-updating")
                onMoved(journal.items[index], item.mainName?.let { destination.resolve(it) })
                checkpoint("record-updated")
                updateItem(index) { it.copy(record = RecordState.Updated) }
            } catch (error: MoveCancelled) {
                rollback(index, published)
                throw error
            } catch (error: StopMove) {
                rollback(index, published)
                throw error
            } catch (failure: ItemFailure) {
                rollback(index, published)
                return failure.reason
            } catch (error: IOException) {
                rollback(index, published)
                return if (error.message.orEmpty().contains("space", ignoreCase = true)) throw StopMove(MoveFailureReason.NoSpace) else MoveFailureReason.CouldNotWrite
            }
            removeOriginals(index, durable)
            return null
        }

        private fun copyOne(index: Int, ei: Int, e: MoveEntry) {
            val source = Paths.get(e.from)
            val temp = destination.resolve(e.temp)
            updateEntry(index, ei) { it.copy(state = EntryState.Copying) }
            checkpoint("copying")
            val before = Files.size(source) to Files.getLastModifiedTime(source)
            val hash = try {
                copy(source, temp, { bytes -> progress(journal.items[index].displayName, bytes, before.first) }, isCancelled)
            } catch (error: IOException) {
                Files.deleteIfExists(temp)
                if (error.message.orEmpty().contains("space", ignoreCase = true)) throw StopMove(MoveFailureReason.NoSpace)
                throw error
            }
            checkpoint("copied")
            if (Files.size(source) != before.first || Files.getLastModifiedTime(source) != before.second) {
                Files.deleteIfExists(temp)
                throw ItemFailure(MoveFailureReason.ChangedWhileCopying)
            }
            if (Files.size(temp) != before.first) {
                Files.deleteIfExists(temp)
                throw ItemFailure(MoveFailureReason.CopyDidNotMatch)
            }
            updateEntry(index, ei) { it.copy(state = EntryState.Copied, hash = hash, size = before.first) }
            checkpoint("copy-recorded")
        }

        /** Null when the file is now at its final name and has been read back; otherwise the reason it is not. */
        private fun publishOne(index: Int, ei: Int, e: MoveEntry): MoveFailureReason? {
            val source = Paths.get(e.from)
            val target = destination.resolve(e.name)
            val temp = if (e.temp.isEmpty()) null else destination.resolve(e.temp)
            target.parent?.let { Files.createDirectories(it) }
            var moved = false
            try {
                Files.createLink(target, temp ?: source)
            } catch (_: FileAlreadyExistsException) {
                return MoveFailureReason.NameTaken
            } catch (error: IOException) {
                // The volume has no hard links. The temporary file is renamed instead, and a rename that
                // would replace a file is refused.
                if (temp == null) return MoveFailureReason.CouldNotWrite
                try {
                    Files.move(temp, target)
                    moved = true
                } catch (_: FileAlreadyExistsException) {
                    return MoveFailureReason.NameTaken
                }
            } catch (error: UnsupportedOperationException) {
                if (temp == null) return MoveFailureReason.CouldNotWrite
                try {
                    Files.move(temp, target)
                    moved = true
                } catch (_: FileAlreadyExistsException) {
                    return MoveFailureReason.NameTaken
                }
            }
            checkpoint("linked")
            if (temp != null) {
                // Read back once, whole, before anything of the original is touched.
                val expected = journal.items[index].entries[ei].hash
                if (hashOf(target) != expected || Files.size(target) != journal.items[index].entries[ei].size) {
                    runCatching { Files.delete(target) }
                    if (!moved) runCatching { Files.deleteIfExists(temp) }
                    return MoveFailureReason.CopyDidNotMatch
                }
            }
            return null
        }

        /** Removes what this move made, and nothing else, and marks the item failed. */
        private fun rollback(index: Int, published: List<Int>) {
            val item = journal.items[index]
            published.forEach { ei ->
                val e = item.entries[ei]
                val target = destination.resolve(e.name)
                // Ours: the move published it and read it back. The original is still there.
                if (Files.isRegularFile(Paths.get(e.from), LinkOption.NOFOLLOW_LINKS)) runCatching { Files.deleteIfExists(target) }
            }
            cleanTemps(index)
            updateItem(index) { it.copy(failed = true) }
        }

        private fun cleanTemps(index: Int) {
            journal.items[index].entries.filter { it.temp.isNotEmpty() }.forEach { runCatching { Files.deleteIfExists(destination.resolve(it.temp)) } }
        }

        private fun removeOriginals(index: Int, durable: Boolean) {
            var canRemove = durable
            val item = journal.items[index]
            item.entries.forEachIndexed { ei, e ->
                if (e.kind == MoveKind.RecordOnly) return@forEachIndexed
                if (!canRemove) return@forEachIndexed
                val source = Paths.get(e.from)
                try {
                    deleteFile(source)
                    if (!syncDirectory(source.parent ?: source)) canRemove = false
                    updateEntry(index, ei) { it.copy(state = EntryState.Removed) }
                } catch (error: IOException) {
                    lastRemovalError[e.from] = error.message ?: error.javaClass.simpleName
                } catch (error: SecurityException) {
                    lastRemovalError[e.from] = error.message ?: "not allowed"
                }
            }
            // Sidecar folders that are now empty go too; one that still holds something stays.
            item.entries.map { Paths.get(it.from).parent }.distinct().forEach { folder ->
                if (folder != null && folder.fileName?.toString()?.let { it.contains('.') } == true) runCatching { Files.delete(folder) }
            }
            checkpoint("original-removed")
        }

        private fun canLink(source: Path): Boolean {
            val probe = destination.resolve(".nuvio-$txId-probe")
            return try {
                Files.createLink(probe, source)
                true
            } catch (_: Exception) {
                false
            } finally {
                runCatching { Files.deleteIfExists(probe) }
            }
        }
    }

    private class ItemFailure(val reason: MoveFailureReason) : IOException()

    /** An item is settled when nothing of it is left for a recovery to do. */
    private fun isSettled(item: MoveItem): Boolean = when {
        item.failed -> true
        item.record == RecordState.Pending -> item.entries.all { it.state == EntryState.Planned && !it.linked }
        item.record == RecordState.Updated -> item.entries.all { it.kind == MoveKind.RecordOnly || it.state == EntryState.Removed }
        else -> false
    }

    private fun existsIgnoringCase(path: Path): Boolean {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        val parent = path.parent ?: return false
        if (!Files.isDirectory(parent)) return false
        val name = path.fileName.toString()
        return Files.list(parent).use { stream -> stream.anyMatch { it.fileName.toString().equals(name, ignoreCase = true) } }
    }

    /**
     * Settles a move that was cut short, by what the journal proves. A temporary file with this move's
     * own name may go. A file at a final name is this move's only if the journal says it was published
     * and it matches the journal (same file as the original for a link, same hash for a copy); anything
     * else there is left alone. An original is removed only beside such a file.
     *
     * An item whose journal says "record updating" cannot be told from before or after the record
     * changed. [recordSide] says where the record is now; without it such items are left for later.
     * When the record is still at the original, [repoint] points it to the published file, from the
     * journal and not from a search, and the move is finished.
     */
    fun recover(
        recordSide: ((itemId: String, destinationId: String, destination: Path) -> RecordSide)? = null,
        repoint: (MoveItem, Path?) -> Unit = { _, _ -> },
    ): RecoveryReport? {
        if (!Files.isRegularFile(journalFile)) return null
        val journal = runCatching { json.decodeFromString<MoveJournal>(Files.readString(journalFile)) }.getOrNull()
        if (journal == null) {
            runCatching { Files.move(journalFile, journalFile.resolveSibling(journalFile.fileName.toString() + ".unreadable"), StandardCopyOption.REPLACE_EXISTING) }
            return null
        }
        val destination = Paths.get(journal.destination)
        var finished = 0
        var rolledBack = 0
        var left = 0
        var kept = 0
        val unresolved = mutableListOf<String>()
        val items = journal.items.map { item ->
            if (isSettled(item)) return@map item
            when (item.record) {
                RecordState.Pending -> {
                    val result = rollBackItem(item, destination)
                    if (result.second) left++ else rolledBack++
                    result.first
                }
                RecordState.Updating -> {
                    val side = recordSide?.invoke(item.itemId, journal.destinationId, destination)
                    when {
                        side == null -> {
                            unresolved += item.itemId
                            item
                        }
                        side == RecordSide.AtDestination || (side == RecordSide.AtSource && allProven(item, destination)) -> {
                            if (side == RecordSide.AtSource) repoint(item, item.mainName?.let { destination.resolve(it) })
                            val result = finishItem(item, destination)
                            if (result.second) kept++ else finished++
                            result.first.copy(record = RecordState.Updated)
                        }
                        else -> {
                            left++
                            item
                        }
                    }
                }
                RecordState.Updated -> {
                    val result = finishItem(item, destination)
                    if (result.second) kept++ else finished++
                    result.first
                }
            }
        }
        val updated = journal.copy(items = items)
        if (updated.items.all(::isSettled)) {
            Files.deleteIfExists(journalFile)
            syncDirectory(journalFile.parent ?: journalFile)
        } else {
            writeJournal(updated)
        }
        return RecoveryReport(finished, rolledBack, left, journal.pausedIds, unresolved, kept)
    }

    /** Whether the file of [entry] is at its final name and the journal proves it is this move's. */
    private fun provenOurs(entry: MoveEntry, destination: Path): Boolean {
        if (entry.state != EntryState.Published && entry.state != EntryState.Removed) return false
        val target = destination.resolve(entry.name)
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return false
        val source = Paths.get(entry.from)
        if (entry.linked) {
            return !Files.exists(source, LinkOption.NOFOLLOW_LINKS) || runCatching { Files.isSameFile(source, target) }.getOrDefault(false)
        }
        return runCatching { Files.size(target) == entry.size && hashOf(target) == entry.hash }.getOrDefault(false)
    }

    private fun allProven(item: MoveItem, destination: Path): Boolean =
        item.entries.filter { it.kind != MoveKind.RecordOnly }.all { provenOurs(it, destination) }

    /** Rolls back an item whose record was never changed. Second value: something was left because it was not proven ours. */
    private fun rollBackItem(item: MoveItem, destination: Path): Pair<MoveItem, Boolean> {
        var left = false
        item.entries.filter { it.kind != MoveKind.RecordOnly }.forEach { entry ->
            val source = Paths.get(entry.from)
            val target = destination.resolve(entry.name)
            val temp = if (entry.temp.isEmpty()) null else destination.resolve(entry.temp)
            val sourceThere = Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
            val targetThere = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            val ours = targetThere && sourceThere && when {
                entry.state == EntryState.Published && !entry.linked ->
                    runCatching { Files.size(target) == entry.size && hashOf(target) == entry.hash }.getOrDefault(false)
                // Linked to the original, or linked to this move's temporary file: the same file, so it is ours.
                entry.linked -> runCatching { Files.isSameFile(source, target) }.getOrDefault(false)
                temp != null && Files.exists(temp) -> runCatching { Files.isSameFile(temp, target) }.getOrDefault(false)
                else -> false
            }
            if (ours) runCatching { Files.delete(target) } else if (targetThere && entry.state != EntryState.Planned) left = true
            if (targetThere && !ours && entry.state == EntryState.Planned) left = true
            if (temp != null) runCatching { Files.deleteIfExists(temp) }
        }
        return item.copy(failed = true) to left
    }

    /** Removes the originals of an item whose record is at the published files. Second value: some could not be removed. */
    private fun finishItem(item: MoveItem, destination: Path): Pair<MoveItem, Boolean> {
        var keptSome = false
        val entries = item.entries.map { entry ->
            if (entry.kind == MoveKind.RecordOnly || entry.state == EntryState.Removed) return@map entry
            val source = Paths.get(entry.from)
            if (!provenOurs(entry, destination)) {
                keptSome = true
                return@map entry
            }
            try {
                if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) deleteFile(source)
                syncDirectory(source.parent ?: source)
                entry.copy(state = EntryState.Removed)
            } catch (_: IOException) {
                keptSome = true
                entry
            }
        }
        return item.copy(entries = entries) to keptSome
    }

    private fun writeJournal(journal: MoveJournal) {
        journalFile.parent?.let { Files.createDirectories(it) }
        val temporary = journalFile.resolveSibling(journalFile.fileName.toString() + ".tmp")
        FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            channel.write(ByteBuffer.wrap(json.encodeToString(journal).toByteArray(Charsets.UTF_8)))
            channel.force(true)
        }
        try {
            Files.move(temporary, journalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, journalFile, StandardCopyOption.REPLACE_EXISTING)
        }
        syncDirectory(journalFile.parent ?: journalFile)
    }

    companion object {
        /** Copies [from] to a new file [to] (never replacing one), flushes it, and returns the SHA-256 of the bytes read. */
        fun copyFile(from: Path, to: Path, onBytes: (Long) -> Unit, cancelled: () -> Boolean): String {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            Files.newInputStream(from).use { input ->
                FileChannel.open(to, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        if (cancelled()) throw MoveCancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(ByteBuffer.wrap(buffer, 0, read))
                        written += read
                        onBytes(written)
                    }
                    output.force(true)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun sha256Of(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Flushes a folder's entries to disk. Not every platform lets a folder be opened for this. */
        fun flushDirectory(directory: Path): Boolean = try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            true
        } catch (_: Exception) {
            false
        }
    }
}

internal fun onSameFileStore(source: Path, destination: Path): Boolean =
    runCatching { Files.getFileStore(source) == Files.getFileStore(destination) }.getOrDefault(false)
