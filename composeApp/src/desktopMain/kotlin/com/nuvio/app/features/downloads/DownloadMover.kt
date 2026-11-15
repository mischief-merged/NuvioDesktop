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

/** How far one file got. Each step is written to the journal before the next one starts. */
@Serializable
internal enum class EntryState {
    Planned,

    /** An empty temporary file with this move's id in its name was created, and its identity journalled. */
    TempCreated,

    /** The copy is complete and the hash of the whole file is in the journal. */
    Copied,

    /** Written before the final name is made, so that a crash shows that a file may exist there. */
    Publishing,

    /** At the final name, read back, and the destination folder flushed. */
    Published,

    /** The original is gone. */
    Removed,

    /** Nothing of this move is left for this file: its temporary file is gone and nothing was made at the final name. */
    Cleaned,

    /** The user chose to keep both copies. */
    Kept,
}

@Serializable
internal data class MoveEntry(
    val srcLocationId: String,
    val srcRel: String,
    val dstLocationId: String,
    val dstRel: String,
    val kind: MoveKind,
    val size: Long = 0L,
    /** SHA-256 of the whole file, taken while it was copied. */
    val hash: String? = null,
    /** The identity (file key) of the original when the move looked at it. */
    val srcKey: String? = null,
    val temp: String = "",
    val tempKey: String? = null,
    /** Moved by a hard link inside one volume: no bytes are copied and both names are the same file. */
    val linked: Boolean = false,
    val state: EntryState = EntryState.Planned,
)

@Serializable
internal data class MoveItem(
    val profileId: Int,
    val itemId: String,
    /** What the record said when the move was planned: the only record this move may change. */
    val recordLocationId: String?,
    val recordRel: String?,
    val entries: List<MoveEntry>,
    val recorded: Boolean = false,
) {
    val displayName: String
        get() = entries.firstOrNull { it.kind != MoveKind.Companion }?.dstRel?.removeSuffix(".part") ?: itemId

    val main: MoveEntry? get() = entries.firstOrNull { it.kind != MoveKind.Companion }
}

@Serializable
internal data class MoveJournal(
    val version: Int = 3,
    val txId: String,
    val destinationLocationId: String,
    val items: List<MoveItem>,
)

internal class MovePlan(
    val destinationId: String,
    val destinationName: String,
    val items: List<MoveItem>,
)

internal enum class RecordSide { AtDestination, AtSource, Missing }

internal class RecoveryContext(
    val profileId: Int,
    /** The record still matches what the journal planned: same profile, same item, still at the source. */
    val recordMatches: (MoveItem) -> Boolean,
    val relocate: (MoveItem, java.nio.file.Path?) -> Unit,
)

internal data class RecoveryReport(
    val tempsRemoved: Int = 0,
    val recordsFinished: Int = 0,
    /** Entries on a location that is away or whose marker does not match: not touched. */
    val pending: Int = 0,
    val unresolvedJournals: Int = 0,
    val unreadable: Int = 0,
    /** Another move or recovery holds the lock. Nothing was done. */
    val busy: Boolean = false,
)

/** Thrown inside a copy when the user cancelled. */
internal class MoveCancelled : Exception()

/** A step that ends the whole move. */
private class StopMove(val reason: MoveFailureReason) : Exception()

/** The journal could not be written. The move stops there and nothing is deleted. */
internal class JournalFailed(cause: Throwable) : IOException(cause)

/** An OS-level exclusive lock on a file, so that two windows or processes cannot move or recover at once. */
internal class MoveLock(private val file: Path) {
    fun tryAcquire(): Closeable? {
        file.parent?.let { Files.createDirectories(it) }
        val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
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
 * Moves downloads to another folder, keeping a duplicate before ever risking a deletion. See the design
 * document, section 5, for the eight rules this class follows. In short: only the live move removes an
 * original, as its last step, after a verified published copy, a successful folder flush, a durably saved
 * record and a journal entry saying so; recovery removes only its own temporary files and finishes a
 * record update that the journal proves; everything else is left and listed for the user.
 */
internal class DownloadMover(
    private val journalDir: Path,
    /** The folder of a location that can be used right now (marker validated), or null. */
    private val resolve: (String) -> Path?,
    /** Called at every step that matters. Tests make it throw to simulate a crash there. */
    private val checkpoint: (String) -> Unit = {},
    /** Writes the bytes of [from] into the existing file [to] and returns the SHA-256 of what it read. */
    private val copy: (from: Path, to: Path, onBytes: (Long) -> Unit, cancelled: () -> Boolean) -> String = ::copyFile,
    private val hashOf: (Path) -> String = ::sha256Of,
    private val sameStore: (Path, Path) -> Boolean = ::onSameFileStore,
    /** Flushes a folder to disk. False where the platform cannot. */
    private val syncDirectory: (Path) -> Boolean = ::flushDirectory,
    private val deleteFile: (Path) -> Unit = { Files.delete(it) },
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

    /** The location a journalled item is moving to. */
    fun destinationOf(item: MoveItem): String = item.main?.dstLocationId.orEmpty()

    private fun read(file: Path): MoveJournal? = runCatching { json.decodeFromString<MoveJournal>(Files.readString(file)) }.getOrNull()

    private fun isSettled(item: MoveItem): Boolean =
        item.entries.all { it.state == EntryState.Planned || it.state == EntryState.Cleaned || it.state == EntryState.Removed || it.state == EntryState.Kept } &&
            (item.entries.isNotEmpty() || true)

    /** Journals that still have something open. A new move waits for them to be resolved. */
    fun unresolvedJournals(): List<Path> = journalFiles().filter { file ->
        val journal = read(file) ?: return@filter false
        !journal.items.all(::isSettled)
    }

    fun run(
        plan: MovePlan,
        isCancelled: () -> Boolean,
        onProgress: (MoveProgress) -> Unit,
        /** Asked just before the record changes: still the planned record, same profile, still at the source? */
        canCommit: (MoveItem) -> Boolean = { true },
        /** The files of the item are in the destination; [Path] is the main file. The record must be changed and saved durably. */
        onMoved: (MoveItem, Path?) -> Unit,
    ): MoveReport {
        val held = try {
            lock.tryAcquire()
        } catch (_: IOException) {
            null
        } ?: return MoveReport("", plan.destinationName, 0, problem = FolderProblem.MoveInProgress)
        held.use {
            if (unresolvedJournals().isNotEmpty()) return MoveReport("", plan.destinationName, 0, problem = FolderProblem.PreviousMoveUnresolved)
            return Transaction(plan, isCancelled, onProgress, canCommit, onMoved).run()
        }
    }

    private inner class Transaction(
        val plan: MovePlan,
        val isCancelled: () -> Boolean,
        val onProgress: (MoveProgress) -> Unit,
        val canCommit: (MoveItem) -> Boolean,
        val onMoved: (MoveItem, Path?) -> Unit,
    ) {
        val txId: String = txIdFactory()
        val journalPath: Path = journalDir.resolve("move-$txId.journal")
        lateinit var journal: MoveJournal
        var totalBytes = 0L
        var doneBytes = 0L
        var doneFiles = 0
        val destinationRoot: Path? = resolve(plan.destinationId)

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

        fun entry(index: Int, entry: Int) = journal.items[index].entries[entry]

        fun progress(current: String?, bytes: Long = 0L, total: Long = 0L) = onProgress(
            MoveProgress(plan.destinationName, journal.items.size, doneFiles, totalBytes, doneBytes + bytes, current, bytes, total, isCancelled()),
        )

        fun run(): MoveReport {
            val destination = destinationRoot ?: return MoveReport("", plan.destinationName, 0, problem = FolderProblem.NotConnected)
            val failures = mutableListOf<MoveFailure>()
            journal = MoveJournal(txId = txId, destinationLocationId = plan.destinationId, items = plan.items)
            totalBytes = plan.items.sumOf { item -> item.entries.sumOf { it.size } }
            // The journal is created whole, without replacing anything, before the first file is touched.
            try {
                beforeJournalWrite(journal)
                writeJournal(journalPath, journal, txId, create = true)
            } catch (error: IOException) {
                return MoveReport(destination.toString(), plan.destinationName, 0, problem = FolderProblem.PreviousMoveUnresolved)
            }
            checkpoint("journal-written")
            var moved = 0
            var notReached = 0
            var cancelled = false
            val kept = mutableListOf<MoveFailure>()
            progress(null)
            for (index in journal.items.indices) {
                if (isCancelled()) {
                    cancelled = true
                    notReached = journal.items.size - index
                    break
                }
                val item = journal.items[index]
                progress(item.displayName, 0L, item.entries.sumOf { it.size })
                try {
                    val outcome = processItem(index)
                    if (outcome.moved) moved++
                    outcome.reason?.let { failures += MoveFailure(item.displayName, it) }
                    kept += outcome.originalsKept
                } catch (failure: ItemFailure) {
                    cleanTemps(index)
                    failures += MoveFailure(item.displayName, failure.reason)
                } catch (_: MoveCancelled) {
                    cleanTemps(index)
                    cancelled = true
                    notReached = journal.items.size - index
                    break
                } catch (stop: StopMove) {
                    cleanTemps(index)
                    failures += MoveFailure(item.displayName, stop.reason)
                    notReached = journal.items.size - index - 1
                    break
                } catch (_: JournalFailed) {
                    // The journal cannot be written: stop here. Nothing is deleted; what exists is left and listed.
                    failures += MoveFailure(item.displayName, MoveFailureReason.KeptInBothPlaces)
                    notReached = journal.items.size - index - 1
                    break
                }
                doneBytes += item.entries.sumOf { it.size }
                doneFiles++
                progress(null)
            }
            runCatching {
                if (journal.items.all(::isSettled)) deleteJournal(journalPath)
            }
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

        private fun deleteJournal(path: Path) {
            Files.deleteIfExists(path)
            syncDirectory(path.parent ?: path)
        }

        private inner class Outcome(val moved: Boolean, val reason: MoveFailureReason? = null, val originalsKept: List<MoveFailure> = emptyList())

        private fun fail(reason: MoveFailureReason) = Outcome(false, reason)

        private fun processItem(index: Int): Outcome {
            val destination = destinationRoot ?: return fail(MoveFailureReason.CouldNotWrite)
            val item = journal.items[index]
            // Look before touching anything.
            for (entry in item.entries) {
                val source = path(entry.srcLocationId, entry.srcRel) ?: return fail(MoveFailureReason.CouldNotRead)
                if (Files.isSymbolicLink(source)) return fail(MoveFailureReason.Symlink)
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) return fail(MoveFailureReason.CouldNotRead)
                if (existsIgnoringCase(destination.resolve(entry.dstRel))) return fail(MoveFailureReason.NameTaken)
            }
            if (item.entries.isNotEmpty()) {
                val sourceRoot = item.entries.first().let { path(it.srcLocationId, it.srcRel) }!!
                val linked = item.entries.all { sameStore(path(it.srcLocationId, it.srcRel)!!, destination) } && canLink(sourceRoot, destination)
                updateItem(index) { it.copy(entries = it.entries.map { e -> e.copy(srcKey = fileKeyOf(path(e.srcLocationId, e.srcRel)!!), linked = linked) }) }
                if (!linked) item.entries.indices.forEach { copyOne(index, it) }
                for (ei in item.entries.indices) publishOne(index, ei)?.let { return it }
            }
            if (!canCommit(journal.items[index])) return fail(MoveFailureReason.RecordChanged)
            try {
                onMoved(journal.items[index], journal.items[index].main?.let { destination.resolve(it.dstRel) })
            } catch (error: IOException) {
                return fail(MoveFailureReason.CouldNotWrite)
            }
            checkpoint("record-saved")
            val recorded = try {
                updateItem(index) { it.copy(recorded = true) }
                true
            } catch (_: JournalFailed) {
                false
            }
            if (!recorded) {
                // The record points at the new place and the journal does not know. Nothing is deleted.
                return Outcome(true, null, journal.items[index].entries.map { MoveFailure(it.dstRel, MoveFailureReason.KeptInBothPlaces) })
            }
            checkpoint("recorded")
            return Outcome(true, null, removeOriginals(index))
        }

        private fun copyOne(index: Int, ei: Int) {
            val destination = destinationRoot!!
            val e = entry(index, ei)
            val source = path(e.srcLocationId, e.srcRel)!!
            // An empty temporary file, created without replacing anything, with this move's id in its name.
            var tempName: String? = null
            var temp: Path? = null
            repeat(5) {
                if (tempName == null) {
                    val candidate = ".nuvio-$txId-${randomName()}.tmp"
                    try {
                        Files.newByteChannel(destination.resolve(candidate), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close()
                        tempName = candidate
                        temp = destination.resolve(candidate)
                    } catch (_: FileAlreadyExistsException) {
                        // Someone else's file has that name: it is left alone and another is chosen.
                    }
                }
            }
            val created = temp ?: throw StopMove(MoveFailureReason.CouldNotWrite)
            val key = fileKeyOf(created)
            try {
                updateEntry(index, ei) { it.copy(state = EntryState.TempCreated, temp = tempName!!, tempKey = key) }
            } catch (failed: JournalFailed) {
                removeTemp(created, key)
                throw failed
            }
            checkpoint("temp-created")
            val before = Files.size(source) to Files.getLastModifiedTime(source)
            val hash = try {
                copy(source, created, { bytes -> progress(journal.items[index].displayName, bytes, before.first) }, isCancelled)
            } catch (error: IOException) {
                removeTemp(created, key)
                if (error.message.orEmpty().contains("space", ignoreCase = true)) throw StopMove(MoveFailureReason.NoSpace)
                throw ItemFailure(MoveFailureReason.CouldNotWrite)
            } catch (cancel: MoveCancelled) {
                removeTemp(created, key)
                throw cancel
            }
            checkpoint("copied")
            if (Files.size(source) != before.first || Files.getLastModifiedTime(source) != before.second || fileKeyOf(source) != e.srcKey) {
                removeTemp(created, key)
                throw ItemFailure(MoveFailureReason.ChangedWhileCopying)
            }
            if (Files.size(created) != before.first) {
                removeTemp(created, key)
                throw ItemFailure(MoveFailureReason.CopyDidNotMatch)
            }
            updateEntry(index, ei) { it.copy(state = EntryState.Copied, hash = hash, size = before.first) }
            checkpoint("copy-recorded")
        }

        private fun publishOne(index: Int, ei: Int): Outcome? {
            val destination = destinationRoot!!
            val e = entry(index, ei)
            val source = path(e.srcLocationId, e.srcRel)!!
            val target = destination.resolve(e.dstRel)
            val temp = if (e.temp.isEmpty()) null else destination.resolve(e.temp)
            updateEntry(index, ei) { it.copy(state = EntryState.Publishing) }
            target.parent?.let { Files.createDirectories(it) }
            var createdAtFinal = false
            try {
                createLink(target, temp ?: source)
                createdAtFinal = true
            } catch (_: FileAlreadyExistsException) {
                cleanEntry(index, ei)
                return fail(MoveFailureReason.NameTaken)
            } catch (_: IOException) {
                createdAtFinal = false
            } catch (_: UnsupportedOperationException) {
                createdAtFinal = false
            }
            if (!createdAtFinal) {
                // No hard links here. The final name is created without replacing, and the bytes are copied into it.
                if (e.linked) return fail(MoveFailureReason.CouldNotWrite).also { cleanEntry(index, ei) }
                try {
                    Files.newByteChannel(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close()
                } catch (_: FileAlreadyExistsException) {
                    cleanEntry(index, ei)
                    return fail(MoveFailureReason.NameTaken)
                }
                try {
                    copy(source, target, { }, isCancelled)
                } catch (error: IOException) {
                    // Something is at the final name now, made by this move. It is kept and listed, never deleted.
                    removeTempOf(index, ei)
                    return fail(MoveFailureReason.KeptInBothPlaces)
                }
            }
            checkpoint("linked")
            val verified = if (e.linked) {
                runCatching { Files.isSameFile(source, target) }.getOrDefault(false)
            } else {
                runCatching { Files.size(target) == e.size && hashOf(target) == e.hash }.getOrDefault(false)
            }
            removeTempOf(index, ei)
            if (!verified) return fail(MoveFailureReason.CopyDidNotMatch)
            // The destination folder must be known to be on disk before anything else depends on it.
            if (!syncDirectory(target.parent ?: destination)) return fail(MoveFailureReason.KeptInBothPlaces)
            updateEntry(index, ei) { it.copy(state = EntryState.Published) }
            checkpoint("published")
            return null
        }

        /** Removes this entry's temporary file (ours, by identity) and, when nothing was made at the final name, marks it cleaned. */
        private fun cleanEntry(index: Int, ei: Int) {
            removeTempOf(index, ei)
            runCatching { updateEntry(index, ei) { it.copy(state = EntryState.Cleaned) } }
        }

        private fun removeTempOf(index: Int, ei: Int) {
            val e = entry(index, ei)
            if (e.temp.isEmpty()) return
            removeTemp(destinationRoot!!.resolve(e.temp), e.tempKey)
        }

        private fun cleanTemps(index: Int) {
            journal.items[index].entries.indices.forEach { ei ->
                val e = entry(index, ei)
                if (e.state == EntryState.TempCreated || e.state == EntryState.Copied) cleanEntry(index, ei) else removeTempOf(index, ei)
            }
        }

        private fun removeOriginals(index: Int): List<MoveFailure> {
            val kept = mutableListOf<MoveFailure>()
            val item = journal.items[index]
            item.entries.forEachIndexed { ei, e ->
                val source = path(e.srcLocationId, e.srcRel)
                if (source == null || !Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return@forEachIndexed
                // Only the file that was copied: if another one stands there now, it is not ours to delete.
                if (e.srcKey == null || fileKeyOf(source) != e.srcKey) {
                    kept += MoveFailure(e.dstRel, MoveFailureReason.OriginalNotRemoved, "the original is not the file that was copied")
                    return@forEachIndexed
                }
                try {
                    deleteFile(source)
                    syncDirectory(source.parent ?: source)
                    runCatching { updateEntry(index, ei) { it.copy(state = EntryState.Removed) } }
                } catch (error: IOException) {
                    kept += MoveFailure(e.dstRel, MoveFailureReason.OriginalNotRemoved, error.message ?: error.javaClass.simpleName)
                } catch (error: SecurityException) {
                    kept += MoveFailure(e.dstRel, MoveFailureReason.OriginalNotRemoved, error.message ?: "not allowed")
                }
            }
            // Folders left empty by the removal go; one that still holds something stays.
            item.main?.let { main ->
                val base = path(main.srcLocationId, main.srcRel)?.parent
                item.entries.mapNotNull { path(it.srcLocationId, it.srcRel) }
                    .flatMap { generateSequence(it.parent) { dir -> dir.parent }.takeWhile { dir -> base != null && dir != base && dir.startsWith(base) }.toList() }
                    .distinct().sortedByDescending { it.nameCount }
                    .forEach { folder -> runCatching { Files.delete(folder) } }
            }
            checkpoint("original-removed")
            return kept
        }

        private fun canLink(source: Path, destination: Path): Boolean {
            repeat(5) {
                val probe = destination.resolve(".nuvio-$txId-probe-${randomName()}")
                try {
                    createLink(probe, source)
                    // The link was just made by this move, so it is this move's to remove.
                    runCatching { Files.deleteIfExists(probe) }
                    return true
                } catch (_: FileAlreadyExistsException) {
                    // Someone else's file has that name: it is left alone and another is chosen.
                } catch (_: Exception) {
                    return false
                }
            }
            return false
        }
    }

    private class ItemFailure(val reason: MoveFailureReason) : IOException()

    private fun existsIgnoringCase(path: Path): Boolean {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        val parent = path.parent ?: return false
        if (!Files.isDirectory(parent)) return false
        val name = path.fileName.toString()
        return Files.list(parent).use { stream -> stream.anyMatch { it.fileName.toString().equals(name, ignoreCase = true) } }
    }

    /** Removes a temporary file this move created, if it is still that file. */
    private fun removeTemp(temp: Path, key: String?) {
        if (key == null) return
        runCatching {
            if (Files.isRegularFile(temp, LinkOption.NOFOLLOW_LINKS) && fileKeyOf(temp) == key) Files.delete(temp)
        }
    }

    /**
     * After a crash. It removes temporary files this transaction created (the journal names them, the
     * transaction's id is in the name, and the identity recorded at creation still matches), and finishes
     * a record update that the journal proves. It never deletes an original or a file at a final name.
     * A location that is away, or whose marker does not match, is not touched; its entries stay pending.
     */
    fun recover(context: RecoveryContext?): RecoveryReport {
        val held = try {
            lock.tryAcquire()
        } catch (_: IOException) {
            null
        } ?: return RecoveryReport(busy = true)
        held.use {
            var temps = 0
            var finished = 0
            var pending = 0
            var unresolved = 0
            var unreadable = 0
            journalFiles().forEach { file ->
                val journal = read(file)
                if (journal == null) {
                    unreadable++
                    runCatching { Files.move(file, file.resolveSibling(file.fileName.toString() + ".unreadable"), StandardCopyOption.REPLACE_EXISTING) }
                    return@forEach
                }
                val destinationRoot = resolve(journal.destinationLocationId)
                var items = journal.items.map { item ->
                    var entries = item.entries.map { e ->
                        if (e.temp.isEmpty() || destinationRoot == null) {
                            if (e.temp.isNotEmpty()) pending++
                            return@map e
                        }
                        val temp = destinationRoot.resolve(e.temp)
                        if (!e.temp.contains(journal.txId)) return@map e
                        val there = Files.isRegularFile(temp, LinkOption.NOFOLLOW_LINKS)
                        if (there && e.tempKey != null && identityOf(temp) == e.tempKey) {
                            runCatching { Files.delete(temp) }.onSuccess { temps++ }
                        }
                        if (!Files.exists(temp, LinkOption.NOFOLLOW_LINKS) && (e.state == EntryState.TempCreated || e.state == EntryState.Copied)) {
                            e.copy(state = EntryState.Cleaned)
                        } else {
                            e
                        }
                    }
                    var current = item.copy(entries = entries)
                    if (context != null && destinationRoot != null && !current.recorded && current.entries.isNotEmpty() &&
                        context.profileId == current.profileId && current.entries.all { it.state == EntryState.Published } &&
                        provenPublished(current, destinationRoot) && context.recordMatches(current)
                    ) {
                        val done = runCatching { context.relocate(current, current.main?.let { destinationRoot.resolve(it.dstRel) }) }.isSuccess
                        if (done) {
                            current = current.copy(recorded = true)
                            finished++
                        }
                    }
                    current
                }
                val updated = journal.copy(items = items)
                if (updated != journal) runCatching { writeJournal(file, updated, journal.txId, create = false) }
                if (updated.items.all(::isSettled)) {
                    runCatching { Files.deleteIfExists(file); syncDirectory(file.parent ?: file) }
                } else {
                    unresolved++
                }
            }
            return RecoveryReport(temps, finished, pending, unresolved, unreadable)
        }
    }

    /** The journal proves the destination is this move's: published, same file as the original or the same hash. */
    private fun provenPublished(item: MoveItem, destinationRoot: Path): Boolean = item.entries.all { e ->
        val target = destinationRoot.resolve(e.dstRel)
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return@all false
        if (e.linked) {
            val source = path(e.srcLocationId, e.srcRel) ?: return@all false
            runCatching { Files.isSameFile(source, target) }.getOrDefault(false)
        } else {
            runCatching { Files.size(target) == e.size && hashOf(target) == e.hash }.getOrDefault(false)
        }
    }

    /** Files that exist in two places, or at a final name this move may have made, with the original still there. */
    fun leftovers(): List<LeftoverCopy> = journalFiles().flatMap { file ->
        val journal = read(file) ?: return@flatMap emptyList()
        journal.items.flatMapIndexed { ii, item ->
            item.entries.mapIndexedNotNull { ei, e ->
                if (e.state != EntryState.Publishing && e.state != EntryState.Published) return@mapIndexedNotNull null
                val source = path(e.srcLocationId, e.srcRel) ?: return@mapIndexedNotNull null
                val target = path(e.dstLocationId, e.dstRel) ?: return@mapIndexedNotNull null
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return@mapIndexedNotNull null
                LeftoverCopy("${journal.txId}:$ii:$ei", e.dstRel.removeSuffix(".part"), runCatching { Files.size(target) }.getOrDefault(0L), source.toString(), target.toString())
            }
        }
    }

    private fun findEntry(id: String): Triple<Path, MoveJournal, Pair<Int, Int>>? {
        val parts = id.split(':')
        if (parts.size != 3) return null
        val file = journalFiles().firstOrNull { it.fileName.toString() == "move-${parts[0]}.journal" } ?: return null
        val journal = read(file) ?: return null
        val ii = parts[1].toIntOrNull() ?: return null
        val ei = parts[2].toIntOrNull() ?: return null
        if (journal.items.getOrNull(ii)?.entries?.getOrNull(ei) == null) return null
        return Triple(file, journal, ii to ei)
    }

    private fun settleEntry(file: Path, journal: MoveJournal, at: Pair<Int, Int>, state: EntryState) {
        val updated = journal.copy(items = journal.items.mapIndexed { i, item ->
            if (i != at.first) item else item.copy(entries = item.entries.mapIndexed { j, e -> if (j == at.second) e.copy(state = state) else e })
        })
        if (updated.items.all(::isSettled)) {
            runCatching { Files.deleteIfExists(file); syncDirectory(file.parent ?: file) }
        } else {
            runCatching { writeJournal(file, updated, journal.txId, create = false) }
        }
    }

    fun keepBoth(id: String) {
        val (file, journal, at) = findEntry(id) ?: return
        val held = lock.tryAcquire() ?: return
        held.use { settleEntry(file, journal, at, EntryState.Kept) }
    }

    /**
     * Removes the extra copy of a leftover, if and only if the two copies are the same content (or the
     * same file), the record belongs to the active profile and points at the copy that is kept. All of it
     * is checked again immediately before the deletion.
     */
    fun removeExtraCopy(id: String, activeProfileId: Int, recordSide: (MoveItem) -> RecordSide): LeftoverOutcome {
        val (file, journal, at) = findEntry(id) ?: return LeftoverOutcome.Refused
        val held = lock.tryAcquire() ?: return LeftoverOutcome.Refused
        held.use {
            val item = journal.items[at.first]
            val e = item.entries[at.second]
            if (item.profileId != activeProfileId) return LeftoverOutcome.Refused
            val source = path(e.srcLocationId, e.srcRel) ?: return LeftoverOutcome.Refused
            val target = path(e.dstLocationId, e.dstRel) ?: return LeftoverOutcome.Refused
            if (Files.isSymbolicLink(source) || Files.isSymbolicLink(target)) return LeftoverOutcome.Refused
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return LeftoverOutcome.Refused
            val extra = when (recordSide(item)) {
                RecordSide.AtSource -> target
                RecordSide.AtDestination -> source
                RecordSide.Missing -> return LeftoverOutcome.Refused
            }
            val same = runCatching {
                if (e.linked || Files.isSameFile(source, target)) Files.isSameFile(source, target) else hashOf(source) == hashOf(target)
            }.getOrDefault(false)
            if (!same) return LeftoverOutcome.Refused
            // The record again, immediately before the deletion.
            val again = when (recordSide(item)) {
                RecordSide.AtSource -> target
                RecordSide.AtDestination -> source
                RecordSide.Missing -> return LeftoverOutcome.Refused
            }
            if (again != extra) return LeftoverOutcome.Refused
            return try {
                deleteFile(extra)
                syncDirectory(extra.parent ?: extra)
                settleEntry(file, journal, at, EntryState.Removed)
                LeftoverOutcome.Removed
            } catch (_: IOException) {
                LeftoverOutcome.Refused
            }
        }
    }

    private fun writeJournal(file: Path, journal: MoveJournal, txId: String, create: Boolean) {
        journalDir.let { Files.createDirectories(it) }
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
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } catch (error: IOException) {
            runCatching { Files.deleteIfExists(temporary) }
            throw error
        }
        if (!syncDirectory(journalDir)) {
            // Folders cannot be flushed here: the journal is as durable as the platform allows.
        }
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

        /** The identity of a file: its file key where the platform has one, otherwise its size and time. */
        fun identityOf(path: Path): String? = runCatching {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            attributes.fileKey()?.toString() ?: "${attributes.size()}:${attributes.creationTime().toMillis()}"
        }.getOrNull()
    }
}

internal fun onSameFileStore(source: Path, destination: Path): Boolean =
    runCatching { Files.getFileStore(source) == Files.getFileStore(destination) }.getOrDefault(false)
