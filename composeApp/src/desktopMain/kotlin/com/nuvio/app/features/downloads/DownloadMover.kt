package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable
internal enum class MoveKind {
    /** The finished video of a download. */
    File,

    /** The partial file of a download that is not finished. */
    Partial,

    /** A file in the dedicated sidecar folder of the video (see [DownloadCompanions]). */
    Companion,
}

/** How far one file got. Each step is in the journal before the next one starts. */
@Serializable
internal enum class EntryState {
    Planned,

    /** An empty temporary file with this move's id in its name exists; its name and identity are journalled. */
    TempCreated,

    /** The temporary file holds the whole copy, read back, and its hash is journalled. */
    Copied,

    /** Written before the final name is made: from here on a copy may exist at the final name. */
    Publishing,

    /** At the final name, checked to be the verified file, and every folder up to the location flushed. */
    Published,

    /** The original was sent to the Trash. */
    Trashed,
}

/** How an item ended. Null while it is under way: then it is open, and an open item blocks new moves. */
@Serializable
internal enum class ItemOutcome {
    /** Moved, record confirmed, originals in the Trash. */
    Done,

    /** Nothing of it was published; this move's temporary files are gone. */
    Aborted,

    /** Something exists in two places, or a copy was published that the record does not use. Listed for the user. */
    Leftover,

    /** The user chose to keep both copies. */
    Kept,

    /** The user had the extra copy sent to the Trash. */
    Removed,
}

@Serializable
internal data class MoveEntry(
    val srcLocationId: String,
    val srcRel: String,
    val dstLocationId: String,
    val dstRel: String,
    val kind: MoveKind,
    val size: Long = 0L,
    /** The identity (file key) of the original when the move looked at it. */
    val srcKey: String? = null,
    val temp: String = "",
    val tempKey: String? = null,
    /** On a volume without hard links: the identity of the empty file that reserved the final name. */
    val reservedKey: String? = null,
    /** SHA-256 of the whole file, read from the original while copying and again from the flushed copy. */
    val hash: String? = null,
    val state: EntryState = EntryState.Planned,
)

@Serializable
internal data class MoveItem(
    val profileId: Int,
    val itemId: String,
    /** The record as it was planned: the only record this move may change. */
    val recordLocationId: String?,
    val recordUri: String?,
    val recordFileName: String,
    val completed: Boolean,
    /** The address a finished record gets at the destination. */
    val newUri: String? = null,
    val entries: List<MoveEntry> = emptyList(),
    /** The compare-and-relocate confirmed the record now points at the destination. */
    val recorded: Boolean = false,
    val outcome: ItemOutcome? = null,
) {
    val displayName: String get() = recordFileName.ifBlank { itemId }
    val main: MoveEntry? get() = entries.firstOrNull { it.kind != MoveKind.Companion }

    /** Under way with something already done on disk. */
    val isOpen: Boolean get() = outcome == null && (recorded || entries.any { it.state != EntryState.Planned })
}

@Serializable
internal data class MoveJournal(
    val version: Int = 4,
    val txId: String,
    val destinationLocationId: String,
    val items: List<MoveItem>,
)

internal class MovePlan(
    val destinationId: String,
    val destinationName: String,
    val items: List<MoveItem>,
)

internal enum class RecordSide { AtSource, AtDestination, Missing }

/** What the mover asks of the lists of downloads. */
internal interface MoveRecords {
    /** The one compare-and-relocate (design, section 5, rule 4). */
    fun relocate(item: MoveItem, destinationId: String): RelocateResult

    /** Where the record of [item], in its own profile, points now. */
    fun sideOf(item: MoveItem): RecordSide

    /** A record of any profile, other than [item]'s, that owns [path]: its name, or null. */
    fun otherOwner(item: MoveItem, path: Path): String?
}

internal data class RecoveryReport(
    val tempsRemoved: Int = 0,
    val leftovers: Int = 0,
    /** Journals that stay open: unreadable, or with a temporary file on a location that is away. */
    val unresolved: Int = 0,
    val unreadable: Int = 0,
    /** Another move or recovery holds the lock. Nothing was done. */
    val busy: Boolean = false,
)

/** Thrown inside a copy when the user cancelled. */
internal class MoveCancelled : Exception()

/** The journal could not be written or flushed. The move stops there. */
internal class JournalFailed(cause: Throwable) : IOException(cause)

/** An OS-level exclusive lock on a file, so that two windows or processes cannot move or recover at once. */
internal class MoveLock(private val file: Path) {
    fun tryAcquire(): Closeable? {
        val channel = try {
            file.parent?.let { Files.createDirectories(it) }
            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (_: IOException) {
            return null
        }
        val lock: FileLock? = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: IOException) {
            null
        }
        if (lock == null) {
            runCatching { channel.close() }
            return null
        }
        return Closeable {
            runCatching { lock.release() }
            runCatching { channel.close() }
        }
    }
}

/**
 * Moves downloads to another folder. It never deletes a user's file: the original goes to the Trash,
 * and only after its copy is verified, published and the record durably points at it. The only file it
 * deletes is a temporary file of its own transaction. See the design document, section 5.
 */
internal class DownloadMover(
    private val journalDir: Path,
    /** The folder of a location that can be used right now (marker validated), or null. */
    private val resolve: (String) -> Path?,
    /** Sends a file or folder to the Trash. True only when it is out of its place. */
    private val trash: (Path) -> Boolean,
    /** The folder of a location whether or not it can be used now, for showing paths. */
    private val locationPath: (String) -> Path? = resolve,
    /** Called at every step that matters. Tests make it throw an Error there to simulate a crash. */
    private val checkpoint: (String) -> Unit = {},
    /** Writes the bytes of [from] into the existing file [to], flushes it, and returns the SHA-256 of what it read. */
    private val copy: (from: Path, to: Path, onBytes: (Long) -> Unit, cancelled: () -> Boolean) -> String = ::copyFile,
    private val hashOf: (Path) -> String = ::sha256Of,
    /** Flushes a folder to disk. False where the platform cannot. */
    private val syncDirectory: (Path) -> Boolean = ::flushDirectory,
    private val createLink: (link: Path, existing: Path) -> Unit = { link, existing -> Files.createLink(link, existing) },
    private val fileKeyOf: (Path) -> String? = ::identityOf,
    private val randomName: () -> String = { randomHex(8) },
    private val txIdFactory: () -> String = { randomHex(16) },
    /** Called before every journal write. Tests make it throw to see what a failed journal write leaves. */
    private val beforeJournalWrite: (MoveJournal) -> Unit = {},
    private val lock: MoveLock = MoveLock(journalDir.resolve("move.lock")),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun path(locationId: String, rel: String): Path? = resolve(locationId)?.resolve(rel)

    fun journalFiles(): List<Path> = runCatching {
        Files.list(journalDir).use { s -> s.filter { it.fileName.toString().let { n -> n.startsWith("move-") && n.endsWith(".journal") } }.sorted().toList() }
    }.getOrDefault(emptyList())

    fun hasJournals(): Boolean = journalFiles().isNotEmpty()

    private fun read(file: Path): MoveJournal? = runCatching { json.decodeFromString<MoveJournal>(Files.readString(file)) }.getOrNull()

    /** Journals that block a new move: unreadable, or with an item still under way. */
    fun unresolvedJournals(): List<Path> = journalFiles().filter { file -> read(file)?.items?.any { it.isOpen } ?: true }

    /** Every location any journal names. Null when a journal cannot be read: then nothing may be forgotten. */
    fun referencedLocations(): Set<String>? {
        val ids = mutableSetOf<String>()
        for (file in journalFiles()) {
            val journal = read(file) ?: return null
            ids += journal.destinationLocationId
            journal.items.forEach { item -> item.entries.forEach { ids += it.srcLocationId; ids += it.dstLocationId } }
            journal.items.forEach { item -> item.recordLocationId?.let { ids += it } }
        }
        return ids
    }

    fun run(
        plan: MovePlan,
        isCancelled: () -> Boolean,
        onProgress: (MoveProgress) -> Unit,
        records: MoveRecords,
    ): MoveReport {
        val held = lock.tryAcquire() ?: return MoveReport("", plan.destinationName, 0, problem = FolderProblem.MoveInProgress)
        held.use {
            if (unresolvedJournals().isNotEmpty()) return MoveReport("", plan.destinationName, 0, problem = FolderProblem.PreviousMoveUnresolved)
            return Transaction(plan, isCancelled, onProgress, records).run()
        }
    }

    /** One item failed before anything was published. */
    private class ItemFailure(val reason: MoveFailureReason, val detail: String? = null) : Exception()

    /** The whole move stops (no space). */
    private class StopMove(val reason: MoveFailureReason) : Exception()

    private inner class Transaction(
        val plan: MovePlan,
        val isCancelled: () -> Boolean,
        val onProgress: (MoveProgress) -> Unit,
        val records: MoveRecords,
    ) {
        val txId: String = txIdFactory()
        val journalPath: Path = journalDir.resolve("move-$txId.journal")
        var journal = MoveJournal(txId = txId, destinationLocationId = plan.destinationId, items = plan.items)
        val totalBytes = plan.items.sumOf { item -> item.entries.sumOf { it.size } }
        var doneBytes = 0L
        var doneFiles = 0

        fun save() {
            try {
                beforeJournalWrite(journal)
                writeJournal(journalPath, journal, txId, create = false)
            } catch (error: IOException) {
                throw JournalFailed(error)
            }
        }

        fun updateItem(index: Int, change: (MoveItem) -> MoveItem) {
            journal = journal.copy(items = journal.items.mapIndexed { i, item -> if (i == index) change(item) else item })
            save()
        }

        fun updateEntry(index: Int, entry: Int, change: (MoveEntry) -> MoveEntry) =
            updateItem(index) { item -> item.copy(entries = item.entries.mapIndexed { i, e -> if (i == entry) change(e) else e }) }

        fun progress(current: String?, bytes: Long = 0L, total: Long = 0L) = onProgress(
            MoveProgress(plan.destinationName, journal.items.size, doneFiles, totalBytes, doneBytes + bytes, current, bytes, total, isCancelled()),
        )

        fun run(): MoveReport {
            val destination = resolve(plan.destinationId) ?: return MoveReport("", plan.destinationName, 0, problem = FolderProblem.NotConnected)
            try {
                beforeJournalWrite(journal)
                writeJournal(journalPath, journal, txId, create = true)
            } catch (_: IOException) {
                // Nothing was touched. A journal that did get written holds only planned items and is settled.
                return MoveReport(destination.toString(), plan.destinationName, 0, problem = FolderProblem.NotWritable)
            }
            checkpoint("journal-created")
            val failures = mutableListOf<MoveFailure>()
            val kept = mutableListOf<MoveFailure>()
            var moved = 0
            var notReached = 0
            var cancelled = false
            progress(null)
            for (index in journal.items.indices) {
                val item = journal.items[index]
                if (isCancelled()) {
                    cancelled = true
                    notReached = journal.items.size - index
                    break
                }
                progress(item.displayName, 0L, item.entries.sumOf { it.size })
                try {
                    val result = moveOne(index, destination)
                    if (result.moved) moved++
                    result.failure?.let { failures += it }
                    kept += result.originalsKept
                } catch (_: MoveCancelled) {
                    abort(index)
                    cancelled = true
                    notReached = journal.items.size - index
                    break
                } catch (stop: StopMove) {
                    abort(index)
                    failures += MoveFailure(item.displayName, stop.reason)
                    notReached = journal.items.size - index - 1
                    break
                } catch (_: JournalFailed) {
                    // Without a journal nothing more is safe. What exists stays where it is; recovery lists it.
                    failures += MoveFailure(item.displayName, MoveFailureReason.KeptInBothPlaces)
                    notReached = journal.items.size - index - 1
                    break
                }
                doneBytes += item.entries.sumOf { it.size }
                doneFiles++
                progress(null)
            }
            val settled = journal.items.all { it.outcome == ItemOutcome.Done || it.outcome == ItemOutcome.Aborted || !it.isOpen && it.outcome == null }
            if (settled) deleteJournal(journalPath)
            return MoveReport(
                destinationPath = destination.toString(),
                destinationName = plan.destinationName,
                moved = moved,
                failures = failures,
                cancelled = cancelled,
                notReached = notReached,
                originalsKept = kept,
            )
        }

        private inner class ItemResult(val moved: Boolean, val failure: MoveFailure? = null, val originalsKept: List<MoveFailure> = emptyList())

        /**
         * Removes this item's temporary files (each only if still ours) and ends it: a leftover when the record
         * moved or a copy of this move stands at a final name, otherwise aborted.
         */
        private fun abort(index: Int) {
            val item = journal.items[index]
            item.entries.forEach { e -> if (e.temp.isNotEmpty()) path(e.dstLocationId, e.temp)?.let { removeTemp(it, e.tempKey) } }
            val ours = item.entries.any { e ->
                e.state >= EntryState.Publishing && path(e.dstLocationId, e.dstRel)?.let { target ->
                    Files.exists(target, LinkOption.NOFOLLOW_LINKS) && fileKeyOf(target).let { it != null && (it == e.tempKey || it == e.reservedKey) }
                } != false
            }
            val outcome = if (item.recorded || ours) ItemOutcome.Leftover else ItemOutcome.Aborted
            runCatching { updateItem(index) { it.copy(outcome = outcome) } }
        }

        private fun failed(index: Int, reason: MoveFailureReason, detail: String? = null): ItemResult {
            abort(index)
            return ItemResult(false, MoveFailure(journal.items[index].displayName, reason, detail))
        }

        private fun moveOne(index: Int, destination: Path): ItemResult {
            val planned = journal.items[index]
            try {
                // Look before touching anything.
                val keys = planned.entries.map { e ->
                    val source = path(e.srcLocationId, e.srcRel) ?: throw ItemFailure(MoveFailureReason.CouldNotRead)
                    if (Files.isSymbolicLink(source)) throw ItemFailure(MoveFailureReason.Symlink)
                    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw ItemFailure(MoveFailureReason.CouldNotRead)
                    val target = destination.resolve(e.dstRel)
                    if (e.dstLocationId != plan.destinationId || existsIgnoringCase(target)) throw ItemFailure(MoveFailureReason.NameTaken)
                    (records.otherOwner(planned, source) ?: records.otherOwner(planned, target))
                        ?.let { throw ItemFailure(MoveFailureReason.OwnedByAnother, it) }
                    fileKeyOf(source)
                }
                if (planned.entries.isNotEmpty()) {
                    updateItem(index) { it.copy(entries = it.entries.mapIndexed { i, e -> e.copy(srcKey = keys[i]) }) }
                    planned.entries.indices.forEach { copyOne(index, it, destination) }
                }
            } catch (failure: ItemFailure) {
                return failed(index, failure.reason, failure.detail)
            } catch (failed: JournalFailed) {
                throw failed
            } catch (error: IOException) {
                return failed(index, MoveFailureReason.Other, error.message)
            } catch (error: RuntimeException) {
                return failed(index, MoveFailureReason.Other, error.message)
            }
            // From here a copy may exist at a final name: a failure leaves everything where it is and lists it.
            try {
                for (ei in planned.entries.indices) {
                    publishOne(index, ei, destination)?.let { return failed(index, it) }
                }
            } catch (failed: JournalFailed) {
                throw failed
            } catch (error: IOException) {
                return failed(index, MoveFailureReason.KeptInBothPlaces, error.message)
            } catch (error: RuntimeException) {
                return failed(index, MoveFailureReason.KeptInBothPlaces, error.message)
            }
            val answer = runCatching { records.relocate(journal.items[index], plan.destinationId) }.getOrDefault(RelocateResult.NotDurable)
            if (answer != RelocateResult.Confirmed) {
                val reason = if (answer == RelocateResult.RecordGone || answer == RelocateResult.RecordChanged) {
                    MoveFailureReason.RecordChanged
                } else {
                    MoveFailureReason.KeptInBothPlaces
                }
                return failed(index, reason)
            }
            checkpoint("record-saved")
            // A journal that cannot say so stops the move here: the record points at the copy, the original stays.
            updateItem(index) { it.copy(recorded = true) }
            checkpoint("recorded")
            val kept = try {
                trashOriginals(index)
            } catch (error: RuntimeException) {
                listOf(MoveFailure(planned.displayName, MoveFailureReason.OriginalNotRemoved, error.message))
            }
            updateItem(index) { it.copy(outcome = if (kept.isEmpty()) ItemOutcome.Done else ItemOutcome.Leftover) }
            checkpoint("done")
            return ItemResult(true, null, kept)
        }

        private fun copyOne(index: Int, ei: Int, destination: Path) {
            val e = journal.items[index].entries[ei]
            val source = path(e.srcLocationId, e.srcRel) ?: throw ItemFailure(MoveFailureReason.CouldNotRead)
            // An empty temporary file, created without replacing anything, with this move's id in its name.
            var temp: Path? = null
            for (attempt in 0 until 5) {
                val candidate = destination.resolve(".nuvio-$txId-${randomName()}.tmp")
                try {
                    Files.newByteChannel(candidate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close()
                    temp = candidate
                    break
                } catch (_: FileAlreadyExistsException) {
                    // Someone else's file has that name: it is left alone and another is chosen.
                } catch (error: IOException) {
                    throw ItemFailure(if (isNoSpace(error)) MoveFailureReason.NoSpace else MoveFailureReason.CouldNotWrite)
                }
            }
            val created = temp ?: throw ItemFailure(MoveFailureReason.CouldNotWrite)
            val key = fileKeyOf(created)
            try {
                updateEntry(index, ei) { it.copy(state = EntryState.TempCreated, temp = created.fileName.toString(), tempKey = key) }
            } catch (failed: JournalFailed) {
                removeTemp(created, key)
                throw failed
            }
            checkpoint("temp-created")
            val before = Triple(Files.size(source), Files.getLastModifiedTime(source), fileKeyOf(source))
            val hash = try {
                copy(source, created, { bytes -> progress(journal.items[index].displayName, bytes, before.first) }, isCancelled)
            } catch (error: IOException) {
                if (isNoSpace(error)) throw StopMove(MoveFailureReason.NoSpace)
                throw ItemFailure(MoveFailureReason.CouldNotWrite, error.message)
            }
            checkpoint("copied")
            if (Triple(Files.size(source), Files.getLastModifiedTime(source), fileKeyOf(source)) != before || before.third != e.srcKey) {
                throw ItemFailure(MoveFailureReason.ChangedWhileCopying)
            }
            // The flushed copy is read back whole: what is on the disk is what was read from the original.
            val readBack = runCatching { hashOf(created) }.getOrNull()
            if (Files.size(created) != before.first || readBack != hash) throw ItemFailure(MoveFailureReason.CopyDidNotMatch)
            updateEntry(index, ei) { it.copy(state = EntryState.Copied, hash = hash, size = before.first) }
            checkpoint("copy-verified")
        }

        /** Publishes one verified copy at its final name without replacing anything. Null when done, else why not. */
        private fun publishOne(index: Int, ei: Int, destination: Path): MoveFailureReason? {
            updateEntry(index, ei) { it.copy(state = EntryState.Publishing) }
            checkpoint("publishing")
            val e = journal.items[index].entries[ei]
            if (e.hash == null || e.tempKey == null) return MoveFailureReason.CopyDidNotMatch
            val temp = destination.resolve(e.temp)
            val target = destination.resolve(e.dstRel)
            if (existsIgnoringCase(target)) return MoveFailureReason.NameTaken
            target.parent?.let { Files.createDirectories(it) }
            val linked = try {
                createLink(target, temp)
                true
            } catch (_: FileAlreadyExistsException) {
                return MoveFailureReason.NameTaken
            } catch (_: IOException) {
                false
            } catch (_: UnsupportedOperationException) {
                false
            }
            if (!linked) {
                // No hard links on this volume: reserve the final name empty, then rename the copy onto that reservation.
                try {
                    Files.newByteChannel(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close()
                } catch (_: FileAlreadyExistsException) {
                    return MoveFailureReason.NameTaken
                }
                val reserved = fileKeyOf(target) ?: return MoveFailureReason.CouldNotWrite
                updateEntry(index, ei) { it.copy(reservedKey = reserved) }
                if (fileKeyOf(target) != reserved || Files.size(target) != 0L) return MoveFailureReason.NameTaken
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    return MoveFailureReason.CouldNotWrite
                }
            }
            // The final name must be the verified copy itself.
            if (Files.isSymbolicLink(target) || fileKeyOf(target) != e.tempKey || Files.size(target) != e.size) return MoveFailureReason.CopyDidNotMatch
            removeTemp(temp, e.tempKey)
            checkpoint("published-unflushed")
            // Every folder from the file up to the location, so that no folder made here can be lost.
            var folder: Path? = target.parent
            while (folder != null && folder.startsWith(destination)) {
                if (!syncDirectory(folder)) return MoveFailureReason.KeptInBothPlaces
                if (folder == destination) break
                folder = folder.parent
            }
            updateEntry(index, ei) { it.copy(state = EntryState.Published) }
            checkpoint("published")
            return null
        }

        /** Sends the originals to the Trash, each only if it is still the file that was copied and no other record owns it. */
        private fun trashOriginals(index: Int): List<MoveFailure> {
            val item = journal.items[index]
            val kept = mutableListOf<MoveFailure>()
            val main = item.main ?: return kept
            val mainIndex = item.entries.indexOf(main)
            val source = path(main.srcLocationId, main.srcRel)
            fun keep(detail: String) {
                kept += MoveFailure(item.displayName, MoveFailureReason.OriginalNotRemoved, detail)
            }
            var mainGone = false
            when {
                source == null -> keep("its location is not connected")
                !Files.exists(source, LinkOption.NOFOLLOW_LINKS) -> mainGone = true
                Files.isSymbolicLink(source) || fileKeyOf(source) != main.srcKey -> keep("it is not the file that was copied")
                records.otherOwner(item, source) != null -> keep("another download uses it")
                !sendToTrash(source) -> keep("the Trash did not take it")
                else -> {
                    checkpoint("trashed")
                    updateEntry(index, mainIndex) { it.copy(state = EntryState.Trashed) }
                    mainGone = true
                }
            }
            val companions = item.entries.withIndex().filter { it.value.kind == MoveKind.Companion }
            // The sidecar folder follows its video: it goes only when the video went.
            if (companions.isNotEmpty() && source != null && mainGone) {
                val folder = source.resolveSibling(DownloadCompanions.sidecarFolderName(source.fileName.toString()))
                val root = resolve(main.srcLocationId)
                val expected = companions.associate { it.value.srcRel to it.value.srcKey }
                val now = if (root == null || Files.isSymbolicLink(folder)) null else listFiles(folder)?.associate { root.relativize(it).toString() to fileKeyOf(it) }
                when {
                    now == null || now != expected -> keep("its sidecar folder changed")
                    records.otherOwner(item, folder) != null -> keep("another download uses its sidecar folder")
                    !sendToTrash(folder) -> keep("the Trash did not take its sidecar folder")
                    else -> updateItem(index) { it.copy(entries = it.entries.map { e -> if (e.kind == MoveKind.Companion) e.copy(state = EntryState.Trashed) else e }) }
                }
            }
            return kept
        }
    }

    private fun sendToTrash(path: Path): Boolean =
        runCatching { trash(path) }.getOrDefault(false) && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    /** Every regular file under [folder]; null when it is not a plain folder or holds a link. */
    private fun listFiles(folder: Path): List<Path>? {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return null
        val all = runCatching { Files.walk(folder).use { it.toList() } }.getOrNull() ?: return null
        if (all.any { Files.isSymbolicLink(it) }) return null
        return all.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
    }

    private fun isNoSpace(error: IOException) = error.message.orEmpty().contains("space", ignoreCase = true)

    private fun existsIgnoringCase(path: Path): Boolean {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        val parent = path.parent ?: return false
        if (!Files.isDirectory(parent)) return false
        val name = path.fileName.toString()
        return Files.list(parent).use { stream -> stream.anyMatch { it.fileName.toString().equals(name, ignoreCase = true) } }
    }

    /** The only real delete: a temporary file of this move, if it is still that file. */
    private fun removeTemp(temp: Path, key: String?): Boolean {
        if (key == null) return false
        return runCatching {
            if (Files.isRegularFile(temp, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(temp) && fileKeyOf(temp) == key) {
                Files.delete(temp)
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private fun deleteJournal(path: Path) {
        runCatching {
            Files.deleteIfExists(path)
            syncDirectory(journalDir)
        }
    }

    /**
     * After a crash. It touches no user file: it removes this transaction's temporary files (rule 1 of the
     * design) and ends each item that was under way as aborted (nothing published) or as a leftover the
     * user resolves. It never re-points a record. An unreadable journal is kept as it is and stays open.
     */
    fun recover(): RecoveryReport {
        val held = lock.tryAcquire() ?: return RecoveryReport(busy = true)
        held.use {
            var temps = 0
            var leftovers = 0
            var unresolved = 0
            var unreadable = 0
            journalFiles().forEach { file ->
                val journal = read(file)
                if (journal == null) {
                    unreadable++
                    unresolved++
                    return@forEach
                }
                val items = journal.items.map { item ->
                    if (!item.isOpen) return@map item
                    var pending = false
                    item.entries.forEach { e ->
                        if (e.temp.isEmpty() || !e.temp.contains(journal.txId)) return@forEach
                        val root = resolve(e.dstLocationId)
                        if (root == null) {
                            pending = true
                            return@forEach
                        }
                        if (removeTemp(root.resolve(e.temp), e.tempKey)) temps++
                    }
                    val publishedSomewhere = item.entries.any { e ->
                        e.state >= EntryState.Publishing && (resolve(e.dstLocationId)?.resolve(e.dstRel)?.let { Files.exists(it, LinkOption.NOFOLLOW_LINKS) } ?: true)
                    }
                    when {
                        pending -> item
                        item.recorded || publishedSomewhere -> item.copy(outcome = ItemOutcome.Leftover).also { leftovers++ }
                        else -> item.copy(outcome = ItemOutcome.Aborted)
                    }
                }
                val updated = journal.copy(items = items)
                if (!settle(file, updated) || updated.items.any { it.isOpen }) unresolved++
            }
            return RecoveryReport(temps, leftovers, unresolved, unreadable)
        }
    }

    /** Writes [journal] back, or removes it when nothing in it is open or left over. False when that failed. */
    private fun settle(file: Path, journal: MoveJournal): Boolean = try {
        val finished = journal.items.none { it.isOpen || it.outcome == ItemOutcome.Leftover }
        if (finished) {
            Files.deleteIfExists(file)
            syncDirectory(journalDir)
        } else if (read(file) != journal) {
            writeJournal(file, journal, journal.txId, create = false)
        }
        true
    } catch (_: IOException) {
        false
    }

    /** Leftovers to show, and journals that block new moves. */
    fun leftovers(): List<LeftoverCopy> = journalFiles().flatMap { file ->
        val journal = read(file)
            ?: return@flatMap listOf(LeftoverCopy("journal:${file.fileName}", file.fileName.toString(), 0L, file.toString(), "", LeftoverKind.Unresolved))
        val open = journal.items.any { it.isOpen }
        val rows = journal.items.mapIndexedNotNull { ii, item ->
            if (item.outcome != ItemOutcome.Leftover) return@mapIndexedNotNull null
            val main = item.main ?: return@mapIndexedNotNull null
            val source = locationPath(main.srcLocationId)?.resolve(main.srcRel)
            val target = locationPath(main.dstLocationId)?.resolve(main.dstRel)
            val size = listOfNotNull(target, source).firstNotNullOfOrNull { runCatching { Files.size(it) }.getOrNull() } ?: main.size
            LeftoverCopy("${journal.txId}:$ii", item.displayName.removeSuffix(".part"), size, source?.toString().orEmpty(), target?.toString().orEmpty())
        }
        rows + if (open) listOf(LeftoverCopy("journal:${file.fileName}", file.fileName.toString(), 0L, file.toString(), "", LeftoverKind.Unresolved)) else emptyList()
    }

    private fun findItem(id: String): Triple<Path, MoveJournal, Int>? {
        val parts = id.split(':')
        if (parts.size != 2) return null
        val file = journalFiles().firstOrNull { it.fileName.toString() == "move-${parts[0]}.journal" } ?: return null
        val journal = read(file) ?: return null
        val ii = parts[1].toIntOrNull() ?: return null
        if (journal.items.getOrNull(ii)?.outcome != ItemOutcome.Leftover) return null
        return Triple(file, journal, ii)
    }

    private fun withOutcome(journal: MoveJournal, ii: Int, outcome: ItemOutcome) =
        journal.copy(items = journal.items.mapIndexed { i, item -> if (i == ii) item.copy(outcome = outcome) else item })

    /** The user keeps both copies. Only the journal changes. */
    fun keepBoth(id: String): Boolean {
        val held = lock.tryAcquire() ?: return false
        held.use {
            val (file, journal, ii) = findItem(id) ?: return false
            return settle(file, withOutcome(journal, ii, ItemOutcome.Kept))
        }
    }

    /**
     * The user sets an unresolved journal aside: it is kept under `set-aside/`, never overwriting
     * anything, and no longer blocks new moves. No other file is touched.
     */
    fun setAside(id: String): Boolean {
        if (!id.startsWith("journal:")) return false
        val held = lock.tryAcquire() ?: return false
        held.use {
            val file = journalFiles().firstOrNull { "journal:${it.fileName}" == id } ?: return false
            return runCatching {
                val folder = Files.createDirectories(journalDir.resolve("set-aside"))
                var target = folder.resolve(file.fileName.toString())
                while (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) target = folder.resolve("${file.fileName}.${randomHex(4)}")
                // Renamed, never copied over anything: an earlier journal set aside under that name stays.
                Files.move(file, target)
                syncDirectory(folder)
                syncDirectory(journalDir)
            }.isSuccess
        }
    }

    /**
     * Sends the extra copy of a leftover to the Trash, if and only if, read again now: every file has its two
     * copies with equal hashes, the record of that profile points at the copy that stays (checked again just
     * before), and no other record of any profile owns the extra copy. Never deletes.
     */
    fun removeExtraCopy(
        id: String,
        records: MoveRecords,
        /** Takes the item lock that delete and the move use; null when something else has the item. */
        lockItem: (String) -> Closeable? = { Closeable {} },
    ): LeftoverOutcome {
        val held = lock.tryAcquire() ?: return LeftoverOutcome.Refused
        held.use {
            val (file, journal, ii) = findItem(id) ?: return LeftoverOutcome.Refused
            val item = journal.items[ii]
            val itemLock = lockItem(item.itemId) ?: return LeftoverOutcome.Refused
            itemLock.use { return removeExtraCopyLocked(file, journal, ii, records) }
        }
    }

    private fun removeExtraCopyLocked(file: Path, journal: MoveJournal, ii: Int, records: MoveRecords): LeftoverOutcome {
        val item = journal.items[ii]
        val side = records.sideOf(item)
        if (side == RecordSide.Missing) return LeftoverOutcome.Refused
        val pairs = item.entries.map { e ->
            val source = path(e.srcLocationId, e.srcRel) ?: return LeftoverOutcome.Refused
            val target = path(e.dstLocationId, e.dstRel) ?: return LeftoverOutcome.Refused
            if (side == RecordSide.AtSource) source to target else target to source
        }
        val extras = mutableListOf<Path>()
        for ((keep, extra) in pairs) {
            if (Files.isSymbolicLink(keep) || !Files.isRegularFile(keep, LinkOption.NOFOLLOW_LINKS)) return LeftoverOutcome.Refused
            if (!Files.exists(extra, LinkOption.NOFOLLOW_LINKS)) continue
            if (Files.isSymbolicLink(extra) || !Files.isRegularFile(extra, LinkOption.NOFOLLOW_LINKS)) return LeftoverOutcome.Refused
            val same = runCatching { Files.size(keep) == Files.size(extra) && hashOf(keep) == hashOf(extra) }.getOrDefault(false)
            if (!same || records.otherOwner(item, extra) != null) return LeftoverOutcome.Refused
            extras.add(extra)
        }
        // The record again, immediately before anything moves.
        if (records.sideOf(item) != side) return LeftoverOutcome.Refused
        if (!extras.all(::sendToTrash)) return LeftoverOutcome.Refused
        settle(file, withOutcome(journal, ii, ItemOutcome.Removed))
        return LeftoverOutcome.Removed
    }

    private fun writeJournal(file: Path, journal: MoveJournal, txId: String, create: Boolean) {
        Files.createDirectories(journalDir)
        val temporary = journalDir.resolve("move-$txId.${randomHex(8)}.tmp")
        FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            channel.write(ByteBuffer.wrap(json.encodeToString(journal).toByteArray(Charsets.UTF_8)))
            channel.force(true)
        }
        try {
            if (create) {
                // A journal is never overwritten: a name that exists fails the move before it starts.
                Files.move(temporary, file)
            } else {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: IOException) {
            runCatching { Files.deleteIfExists(temporary) }
            throw error
        }
        if (!syncDirectory(journalDir)) throw IOException("The folder of move journals could not be flushed")
    }

    companion object {
        private val random = SecureRandom()

        fun randomHex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        /** Writes [from] into the existing file [to], flushes it, and returns the SHA-256 of the bytes read. */
        fun copyFile(from: Path, to: Path, onBytes: (Long) -> Unit, cancelled: () -> Boolean): String {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            Files.newInputStream(from).use { input ->
                FileChannel.open(to, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        if (cancelled()) throw MoveCancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        val chunk = ByteBuffer.wrap(buffer, 0, read)
                        while (chunk.hasRemaining()) output.write(chunk)
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

        /** The identity of a file: its file key where the platform has one, otherwise its size and creation time. */
        fun identityOf(path: Path): String? = runCatching {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            attributes.fileKey()?.toString() ?: "${attributes.size()}:${attributes.creationTime().toMillis()}"
        }.getOrNull()
    }
}
