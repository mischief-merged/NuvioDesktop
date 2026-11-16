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
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
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
    /**
     * On a volume without hard links the copy is written straight into the final name, created by this
     * move with create-new: [direct] says so, and [finalKey] is that file's identity, proven to be the
     * file this move created before it is journalled.
     */
    val direct: Boolean = false,
    val finalKey: String? = null,
    /** SHA-256 of the whole file, read from the original while copying and again, by its path, from the flushed copy. */
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

/** A file this move created with create-new, proven to be that file, and the channel it was created with. */
private class Owned(val path: Path, val channel: FileChannel, val key: String)

/**
 * Moves downloads to another folder. It never deletes a user's file: the original goes to the Trash,
 * and only after its copy is verified, published and the record durably points at it. Nothing it does
 * can replace or truncate a file it did not create: every file it writes is created with create-new and
 * written through the channel that created it, never reopened by name, and nothing is renamed over
 * anything. The only files it deletes are files this transaction created, after their identity is
 * checked again. See the design document, section 5.
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
    /** Writes the bytes of [from] through [to], flushes it, and returns the SHA-256 of what it read. */
    private val copy: (from: Path, to: FileChannel, onBytes: (Long) -> Unit, cancelled: () -> Boolean) -> String = ::copyFile,
    /** The SHA-256 of a file, read by its path without following a link. */
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

    // Journals. Each write is a new version `move-<tx>-<seq>.journal` created with create-new, flushed,
    // and only then are the older versions of that transaction removed. Nothing is renamed over anything.
    // The current version is the newest one that can be read.

    private class Tx(val id: String, val versions: List<Pair<Long, Path>>) {
        val newest: Path get() = versions.first().second
        val nextSeq: Long get() = versions.maxOf { it.first } + 1
    }

    private val journalName = Regex("""^move-([0-9A-Za-z]+)(?:-(\d+))?\.journal$""")

    private fun transactions(): List<Tx> {
        val files = runCatching { Files.list(journalDir).use { it.toList() } }.getOrDefault(emptyList())
        return files.mapNotNull { file ->
            journalName.matchEntire(file.fileName.toString())?.let { m -> Triple(m.groupValues[1], m.groupValues[2].toLongOrNull() ?: 0L, file) }
        }.groupBy { it.first }.map { (id, versions) ->
            Tx(id, versions.map { it.second to it.third }.sortedByDescending { it.first })
        }.sortedBy { it.id }
    }

    private fun current(tx: Tx): MoveJournal? = tx.versions.firstNotNullOfOrNull { (_, file) -> read(file) }

    /** The newest journal file of each transaction. */
    fun journalFiles(): List<Path> = transactions().map { it.newest }

    fun hasJournals(): Boolean = transactions().isNotEmpty()

    private fun read(file: Path): MoveJournal? = runCatching {
        json.decodeFromString<MoveJournal>(String(Files.readAllBytes(file), Charsets.UTF_8))
    }.getOrNull()

    /** Journals that block a new move: unreadable, or with an item still under way. */
    fun unresolvedJournals(): List<Path> = transactions().filter { tx -> current(tx)?.items?.any { it.isOpen } ?: true }.map { it.newest }

    /** Every location any journal names. Null when a journal cannot be read: then nothing may be forgotten. */
    fun referencedLocations(): Set<String>? {
        val ids = mutableSetOf<String>()
        for (tx in transactions()) {
            val journal = current(tx) ?: return null
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

    /** This volume has no hard links: the entry is written straight into its final name instead. */
    private class NoLinks : Exception()

    private inner class Transaction(
        val plan: MovePlan,
        val isCancelled: () -> Boolean,
        val onProgress: (MoveProgress) -> Unit,
        val records: MoveRecords,
    ) {
        val txId: String = txIdFactory()
        var journal = MoveJournal(txId = txId, destinationLocationId = plan.destinationId, items = plan.items)
        val totalBytes = plan.items.sumOf { item -> item.entries.sumOf { it.size } }
        var doneBytes = 0L
        var doneFiles = 0

        /** Known once a link failed for a reason other than a taken name. */
        var linksUnsupported = false

        fun save() {
            try {
                beforeJournalWrite(journal)
                writeJournal(txId, journal, first = false)
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
                writeJournal(txId, journal, first = true)
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
            if (settled) removeJournal(txId)
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
         * Removes what this item's move created and no record uses (each only if it is still the file
         * that was created) and ends it: a leftover when the record moved or a verified copy of this move
         * stands at a final name, otherwise aborted. Something that could not be removed keeps it open.
         */
        private fun abort(index: Int) {
            val item = journal.items[index]
            val outcome = cleanUp(item) ?: return
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
                    fileKeyOf(source) ?: throw ItemFailure(MoveFailureReason.CouldNotRead)
                }
                if (planned.entries.isNotEmpty()) {
                    updateItem(index) { it.copy(entries = it.entries.mapIndexed { i, e -> e.copy(srcKey = keys[i]) }) }
                    for (ei in planned.entries.indices) {
                        if (!linksUnsupported) copyToTemp(index, ei, destination)
                    }
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
            // From here a copy may exist at a final name: a failure leaves a verified copy where it is and lists it.
            try {
                for (ei in planned.entries.indices) {
                    val reason = if (linksUnsupported || journal.items[index].entries[ei].temp.isEmpty()) {
                        publishDirect(index, ei, destination)
                    } else {
                        try {
                            publishByLink(index, ei, destination)
                        } catch (_: NoLinks) {
                            // This volume has no hard links: the copy is written straight into its final name.
                            linksUnsupported = true
                            publishDirect(index, ei, destination)
                        }
                    }
                    reason?.let { return failed(index, it) }
                }
            } catch (failure: ItemFailure) {
                return failed(index, failure.reason, failure.detail)
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

        /** Copies the original through the channel that created [owned], checks the original did not change, and returns the hash. */
        private fun copyInto(owned: Owned, e: MoveEntry, index: Int): String {
            val source = path(e.srcLocationId, e.srcRel) ?: throw ItemFailure(MoveFailureReason.CouldNotRead)
            val before = sourceState(source)
            if (before.third != e.srcKey) throw ItemFailure(MoveFailureReason.ChangedWhileCopying)
            val hash = try {
                owned.channel.use { channel -> copy(source, channel, { bytes -> progress(journal.items[index].displayName, bytes, before.first) }, isCancelled) }
            } catch (error: IOException) {
                if (isNoSpace(error)) throw StopMove(MoveFailureReason.NoSpace)
                throw ItemFailure(MoveFailureReason.CouldNotWrite, error.message)
            }
            checkpoint("copied")
            if (sourceState(source) != before) throw ItemFailure(MoveFailureReason.ChangedWhileCopying)
            // The flushed copy is read back whole, by its path, and must still be the file this move created.
            if (!readsBack(owned.path, owned.key, before.first, hash)) throw ItemFailure(MoveFailureReason.CopyDidNotMatch)
            return hash
        }

        private fun sourceState(source: Path) = Triple(Files.size(source), Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS), fileKeyOf(source))

        /** Hard-link volumes: the copy goes to a temporary file of this move first. */
        private fun copyToTemp(index: Int, ei: Int, destination: Path) {
            val e = journal.items[index].entries[ei]
            var owned: Owned? = null
            for (attempt in 0 until 5) {
                owned = createOwned(destination.resolve(".nuvio-$txId-${randomName()}.tmp")) ?: continue
                break
            }
            val temp = owned ?: throw ItemFailure(MoveFailureReason.CouldNotWrite)
            try {
                updateEntry(index, ei) { it.copy(state = EntryState.TempCreated, temp = temp.path.fileName.toString(), tempKey = temp.key) }
            } catch (failed: JournalFailed) {
                runCatching { temp.channel.close() }
                removeOwned(temp.path, temp.key)
                throw failed
            }
            checkpoint("temp-created")
            val hash = copyInto(temp, e, index)
            updateEntry(index, ei) { it.copy(state = EntryState.Copied, hash = hash, size = e.size) }
            checkpoint("copy-verified")
        }

        /** Publishes a verified temporary copy with a hard link, which never replaces a name. Null when done, else why not. */
        private fun publishByLink(index: Int, ei: Int, destination: Path): MoveFailureReason? {
            updateEntry(index, ei) { it.copy(state = EntryState.Publishing) }
            checkpoint("publishing")
            val e = journal.items[index].entries[ei]
            if (e.hash == null || e.tempKey == null) return MoveFailureReason.CopyDidNotMatch
            val temp = destination.resolve(e.temp)
            val target = destination.resolve(e.dstRel)
            if (existsIgnoringCase(target)) return MoveFailureReason.NameTaken
            target.parent?.let { Files.createDirectories(it) }
            try {
                createLink(target, temp)
            } catch (_: FileAlreadyExistsException) {
                return MoveFailureReason.NameTaken
            } catch (_: IOException) {
                if (!removeOwned(temp, e.tempKey)) return MoveFailureReason.CouldNotWrite
                throw NoLinks()
            } catch (_: UnsupportedOperationException) {
                if (!removeOwned(temp, e.tempKey)) return MoveFailureReason.CouldNotWrite
                throw NoLinks()
            }
            // The final name must be the verified copy itself.
            if (Files.isSymbolicLink(target) || fileKeyOf(target) != e.tempKey || Files.size(target) != e.size) return MoveFailureReason.CopyDidNotMatch
            removeOwned(temp, e.tempKey)
            checkpoint("published-unflushed")
            return finishPublishing(index, ei, destination, target)
        }

        /**
         * Volumes without hard links: the final name is created with create-new (so nothing is replaced),
         * the original is copied through the channel that created it, and it is read back by its path.
         */
        private fun publishDirect(index: Int, ei: Int, destination: Path): MoveFailureReason? {
            // A temporary copy made before this volume was found to have no links is this move's own: it goes first.
            journal.items[index].entries[ei].let { e ->
                if (e.temp.isNotEmpty()) {
                    val temp = destination.resolve(e.temp)
                    removeOwned(temp, e.tempKey)
                    if (Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) return MoveFailureReason.CouldNotWrite
                }
            }
            updateEntry(index, ei) { it.copy(state = EntryState.Publishing, direct = true, temp = "", tempKey = null) }
            checkpoint("publishing")
            val e = journal.items[index].entries[ei]
            val target = destination.resolve(e.dstRel)
            // A taken name: nothing was created, so the entry is not left looking as if it might have been.
            val taken = { updateEntry(index, ei) { it.copy(state = EntryState.Planned, direct = false) }; MoveFailureReason.NameTaken }
            if (existsIgnoringCase(target)) return taken()
            target.parent?.let { Files.createDirectories(it) }
            val owned = createOwned(target) ?: return taken()
            try {
                updateEntry(index, ei) { it.copy(finalKey = owned.key) }
            } catch (failed: JournalFailed) {
                runCatching { owned.channel.close() }
                removeOwned(owned.path, owned.key)
                throw failed
            }
            checkpoint("direct-created")
            val hash = copyInto(owned, e, index)
            updateEntry(index, ei) { it.copy(hash = hash) }
            checkpoint("published-unflushed")
            return finishPublishing(index, ei, destination, target)
        }

        /** Every folder from the file up to the location, so that no folder made here can be lost; then Published. */
        private fun finishPublishing(index: Int, ei: Int, destination: Path, target: Path): MoveFailureReason? {
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

    /**
     * Creates [path] with create-new (which also refuses a link standing there) and proves the name is the
     * file just created: a random mark is written through the new channel, the identity is read by name,
     * the mark is read back by name without following links, and the identity is read again. Then the mark
     * is cut from the channel and the empty file is returned with its open channel. Null when the name is
     * taken or the proof fails; a name that turned out not to be ours is left alone.
     */
    private fun createOwned(path: Path): Owned? {
        val channel = try {
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        } catch (_: FileAlreadyExistsException) {
            return null
        } catch (error: IOException) {
            if (isNoSpace(error)) throw StopMove(MoveFailureReason.NoSpace)
            throw error
        }
        try {
            val mark = ByteArray(16).also(random::nextBytes)
            val buffer = ByteBuffer.wrap(mark)
            while (buffer.hasRemaining()) channel.write(buffer, buffer.position().toLong())
            val first = fileKeyOf(path)
            val seen = runCatching {
                Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { reader ->
                    val read = ByteBuffer.allocate(16)
                    while (read.hasRemaining() && reader.read(read) >= 0) Unit
                    read.array()
                }
            }.getOrNull()
            val second = fileKeyOf(path)
            if (first == null || first != second || seen == null || !seen.contentEquals(mark)) {
                channel.close()
                return null
            }
            // The channel is this move's own file: cutting it back to nothing touches nothing else.
            channel.truncate(0L)
            channel.position(0L)
            return Owned(path, channel, first)
        } catch (error: IOException) {
            runCatching { channel.close() }
            throw error
        }
    }

    /** [path] is still the file with [key], is [size] bytes long, and its content read by name hashes to [hash]. */
    private fun readsBack(path: Path, key: String, size: Long, hash: String): Boolean = runCatching {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || fileKeyOf(path) != key) return@runCatching false
        val read = hashOf(path)
        fileKeyOf(path) == key && Files.size(path) == size && read == hash
    }.getOrDefault(false)

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

    /** The only real delete: a file this transaction created, if it is still that file. */
    private fun removeOwned(path: Path, key: String?): Boolean {
        if (key == null) return false
        return runCatching {
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && fileKeyOf(path) == key) {
                Files.delete(path)
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    /** Removes what [item]'s move created and no record uses. The outcome, or null when something of it could not be settled. */
    private fun cleanUp(item: MoveItem): ItemOutcome? {
        var unsettled = false
        var leftover = item.recorded
        item.entries.forEach { e ->
            val root = resolve(e.dstLocationId)
            if (root == null) {
                if (e.temp.isNotEmpty() || e.state >= EntryState.Publishing) unsettled = true
                return@forEach
            }
            if (e.temp.isNotEmpty() && e.tempKey != null) {
                val temp = root.resolve(e.temp)
                removeOwned(temp, e.tempKey)
                if (Files.exists(temp, LinkOption.NOFOLLOW_LINKS) && fileKeyOf(temp) == e.tempKey) unsettled = true
            }
            if (e.state < EntryState.Publishing) return@forEach
            val target = root.resolve(e.dstRel)
            // A direct copy that was never verified and published is this move's own unfinished file.
            if (e.direct && e.state == EntryState.Publishing && !item.recorded && e.finalKey != null) removeOwned(target, e.finalKey)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                val key = fileKeyOf(target)
                if (key != null && (key == e.tempKey || key == e.finalKey) || e.direct && e.finalKey == null || e.state >= EntryState.Published) leftover = true
            }
        }
        return when {
            unsettled -> null
            leftover -> ItemOutcome.Leftover
            else -> ItemOutcome.Aborted
        }
    }

    private fun removeJournal(txId: String) {
        transactions().firstOrNull { it.id == txId }?.versions?.forEach { (_, file) -> runCatching { Files.deleteIfExists(file) } }
        runCatching { syncDirectory(journalDir) }
    }

    /**
     * After a crash. It touches no user file: it removes files this transaction created and no record uses
     * (rule 1 of the design), and ends each item that was under way as aborted or as a leftover the user
     * resolves. It never re-points a record. An unreadable journal is kept as it is and stays open.
     */
    fun recover(): RecoveryReport {
        val held = lock.tryAcquire() ?: return RecoveryReport(busy = true)
        held.use {
            var removed = 0
            var leftovers = 0
            var unresolved = 0
            var unreadable = 0
            transactions().forEach { tx ->
                val journal = current(tx)
                if (journal == null) {
                    unreadable++
                    unresolved++
                    return@forEach
                }
                val items = journal.items.map { item ->
                    if (!item.isOpen) return@map item
                    val before = ownedFilesPresent(item)
                    val outcome = cleanUp(item)
                    removed += before - ownedFilesPresent(item)
                    when (outcome) {
                        null -> item
                        ItemOutcome.Leftover -> item.copy(outcome = outcome).also { leftovers++ }
                        else -> item.copy(outcome = outcome)
                    }
                }
                val updated = journal.copy(items = items)
                if (!settle(tx, updated) || updated.items.any { it.isOpen }) unresolved++
            }
            return RecoveryReport(removed, leftovers, unresolved, unreadable)
        }
    }

    private fun ownedFilesPresent(item: MoveItem): Int = item.entries.sumOf { e ->
        val root = resolve(e.dstLocationId) ?: return@sumOf 0
        val temp = if (e.temp.isNotEmpty() && Files.exists(root.resolve(e.temp), LinkOption.NOFOLLOW_LINKS) && fileKeyOf(root.resolve(e.temp)) == e.tempKey) 1 else 0
        val direct = if (e.direct && e.finalKey != null && fileKeyOf(root.resolve(e.dstRel)) == e.finalKey && e.state == EntryState.Publishing) 1 else 0
        temp + direct
    }

    /** Writes [journal] as a new version, or removes it when nothing in it is open or left over. False when that failed. */
    private fun settle(tx: Tx, journal: MoveJournal): Boolean = try {
        val finished = journal.items.none { it.isOpen || it.outcome == ItemOutcome.Leftover }
        if (finished) {
            removeJournal(tx.id)
        } else if (current(tx) != journal) {
            writeJournal(tx.id, journal, first = false)
        }
        true
    } catch (_: IOException) {
        false
    }

    /** Leftovers to show, and journals that block new moves. */
    fun leftovers(): List<LeftoverCopy> = transactions().flatMap { tx ->
        val unresolvedRow = LeftoverCopy("journal:${tx.id}", tx.newest.fileName.toString(), 0L, tx.newest.toString(), "", LeftoverKind.Unresolved)
        val journal = current(tx) ?: return@flatMap listOf(unresolvedRow)
        val rows = journal.items.mapIndexedNotNull { ii, item ->
            if (item.outcome != ItemOutcome.Leftover) return@mapIndexedNotNull null
            val main = item.main ?: return@mapIndexedNotNull null
            val source = locationPath(main.srcLocationId)?.resolve(main.srcRel)
            val target = locationPath(main.dstLocationId)?.resolve(main.dstRel)
            val size = listOfNotNull(target, source).firstNotNullOfOrNull { runCatching { Files.size(it) }.getOrNull() } ?: main.size
            LeftoverCopy("${journal.txId}:$ii", item.displayName.removeSuffix(".part"), size, source?.toString().orEmpty(), target?.toString().orEmpty())
        }
        rows + if (journal.items.any { it.isOpen }) listOf(unresolvedRow) else emptyList()
    }

    private fun findItem(id: String): Triple<Tx, MoveJournal, Int>? {
        val parts = id.split(':')
        if (parts.size != 2) return null
        val tx = transactions().firstOrNull { it.id == parts[0] } ?: return null
        val journal = current(tx) ?: return null
        val ii = parts[1].toIntOrNull() ?: return null
        if (journal.items.getOrNull(ii)?.outcome != ItemOutcome.Leftover) return null
        return Triple(tx, journal, ii)
    }

    private fun withOutcome(journal: MoveJournal, ii: Int, outcome: ItemOutcome) =
        journal.copy(items = journal.items.mapIndexed { i, item -> if (i == ii) item.copy(outcome = outcome) else item })

    /** The user keeps both copies. Only the journal changes. */
    fun keepBoth(id: String): Boolean {
        val held = lock.tryAcquire() ?: return false
        held.use {
            val (tx, journal, ii) = findItem(id) ?: return false
            return settle(tx, withOutcome(journal, ii, ItemOutcome.Kept))
        }
    }

    /**
     * The user sets an unresolved journal aside: every version of it is kept under `set-aside/`, never
     * overwriting anything, and it no longer blocks new moves. No other file is touched.
     */
    fun setAside(id: String): Boolean {
        if (!id.startsWith("journal:")) return false
        val held = lock.tryAcquire() ?: return false
        held.use {
            val tx = transactions().firstOrNull { "journal:${it.id}" == id } ?: return false
            return runCatching {
                val folder = Files.createDirectories(journalDir.resolve("set-aside"))
                tx.versions.forEach { (_, file) -> keepAside(file, folder) }
                check(syncDirectory(folder))
                syncDirectory(journalDir)
            }.isSuccess
        }
    }

    /** Puts our own journal file into [folder] under a name nobody has, then lets go of the old name. Nothing is overwritten. */
    private fun keepAside(file: Path, folder: Path) {
        while (true) {
            val target = folder.resolve("${file.fileName}.${randomHex(4)}").takeIf { Files.exists(folder.resolve(file.fileName.toString()), LinkOption.NOFOLLOW_LINKS) }
                ?: folder.resolve(file.fileName.toString())
            try {
                createLink(target, file)
            } catch (_: FileAlreadyExistsException) {
                continue
            } catch (_: Exception) {
                // No links here: the bytes go into a file created with create-new.
                try {
                    FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { out ->
                        val bytes = ByteBuffer.wrap(Files.readAllBytes(file))
                        while (bytes.hasRemaining()) out.write(bytes)
                        out.force(true)
                    }
                } catch (_: FileAlreadyExistsException) {
                    continue
                }
            }
            Files.delete(file)
            return
        }
    }

    /**
     * Sends the extra copy of a leftover to the Trash, if and only if: every file has its two copies with
     * equal hashes; the record of that profile points at the copy that stays; and, immediately before EACH
     * removal, the extra file still has the identity it had when it was hashed, the kept copy still has its
     * own, no record of any profile owns it, and the record is still on the kept side. The first check that
     * fails stops the remaining removals. Never deletes.
     */
    fun removeExtraCopy(
        id: String,
        records: MoveRecords,
        /** Takes the item lock that delete and the move use; null when something else has the item. */
        lockItem: (String) -> Closeable? = { Closeable {} },
    ): LeftoverOutcome {
        val held = lock.tryAcquire() ?: return LeftoverOutcome.Refused
        held.use {
            val (tx, journal, ii) = findItem(id) ?: return LeftoverOutcome.Refused
            val item = journal.items[ii]
            val itemLock = lockItem(item.itemId) ?: return LeftoverOutcome.Refused
            itemLock.use { return removeExtraCopyLocked(tx, journal, ii, records) }
        }
    }

    private class Proof(val keep: Path, val keepKey: String, val extra: Path, val extraKey: String, val size: Long)

    private fun removeExtraCopyLocked(tx: Tx, journal: MoveJournal, ii: Int, records: MoveRecords): LeftoverOutcome {
        val item = journal.items[ii]
        val side = records.sideOf(item)
        if (side == RecordSide.Missing) return LeftoverOutcome.Refused
        val proofs = mutableListOf<Proof>()
        for (e in item.entries) {
            val source = path(e.srcLocationId, e.srcRel) ?: return LeftoverOutcome.Refused
            val target = path(e.dstLocationId, e.dstRel) ?: return LeftoverOutcome.Refused
            val (keep, extra) = if (side == RecordSide.AtSource) source to target else target to source
            if (Files.isSymbolicLink(keep) || !Files.isRegularFile(keep, LinkOption.NOFOLLOW_LINKS)) return LeftoverOutcome.Refused
            if (!Files.exists(extra, LinkOption.NOFOLLOW_LINKS)) continue
            if (Files.isSymbolicLink(extra) || !Files.isRegularFile(extra, LinkOption.NOFOLLOW_LINKS)) return LeftoverOutcome.Refused
            val keepKey = fileKeyOf(keep) ?: return LeftoverOutcome.Refused
            val extraKey = fileKeyOf(extra) ?: return LeftoverOutcome.Refused
            if (keepKey == extraKey) return LeftoverOutcome.Refused
            val size = Files.size(keep)
            val same = runCatching { size == Files.size(extra) && hashOf(keep) == hashOf(extra) }.getOrDefault(false)
            // The identities must not have changed while the two were read.
            if (!same || fileKeyOf(keep) != keepKey || fileKeyOf(extra) != extraKey) return LeftoverOutcome.Refused
            proofs += Proof(keep, keepKey, extra, extraKey, size)
        }
        for (proof in proofs) {
            // Every proof again, immediately before this one removal; anything changed stops the rest.
            val unchanged = !Files.isSymbolicLink(proof.extra) && fileKeyOf(proof.extra) == proof.extraKey &&
                runCatching { Files.size(proof.extra) }.getOrNull() == proof.size &&
                !Files.isSymbolicLink(proof.keep) && fileKeyOf(proof.keep) == proof.keepKey &&
                runCatching { Files.size(proof.keep) }.getOrNull() == proof.size &&
                records.otherOwner(item, proof.extra) == null && records.sideOf(item) == side
            if (!unchanged || !sendToTrash(proof.extra)) return LeftoverOutcome.Refused
        }
        settle(tx, withOutcome(journal, ii, ItemOutcome.Removed))
        return LeftoverOutcome.Removed
    }

    /** A new version of the journal of [txId], created with create-new and flushed, then older versions go. */
    private fun writeJournal(txId: String, journal: MoveJournal, first: Boolean) {
        Files.createDirectories(journalDir)
        val tx = transactions().firstOrNull { it.id == txId }
        if (first && tx != null) throw FileAlreadyExistsException("journal $txId")
        val seq = tx?.nextSeq ?: 1L
        val file = journalDir.resolve("move-$txId-${"%08d".format(seq)}.journal")
        FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val bytes = ByteBuffer.wrap(json.encodeToString(journal).toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        if (!syncDirectory(journalDir)) throw IOException("The folder of move journals could not be flushed")
        tx?.versions?.forEach { (_, old) -> runCatching { Files.deleteIfExists(old) } }
    }

    companion object {
        private val random = SecureRandom()

        fun randomHex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        /**
         * Copies [from], opened for reading without following a link, through [to] (the channel that created
         * the copy, never a reopened name), flushes it, and returns the SHA-256 of the bytes read.
         */
        fun copyFile(from: Path, to: FileChannel, onBytes: (Long) -> Unit, cancelled: () -> Boolean): String {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            Files.newByteChannel(from, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteBuffer.allocate(1024 * 1024)
                while (true) {
                    if (cancelled()) throw MoveCancelled()
                    buffer.clear()
                    val read = input.read(buffer)
                    if (read < 0) break
                    buffer.flip()
                    digest.update(buffer.array(), 0, read)
                    while (buffer.hasRemaining()) to.write(buffer)
                    written += read
                    onBytes(written)
                }
                to.force(true)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** The SHA-256 of [path], read without following a link. */
        fun sha256Of(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteBuffer.allocate(1024 * 1024)
                while (true) {
                    buffer.clear()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer.array(), 0, read)
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

        /** The identity of a file: its file key where the platform has one, otherwise its creation time. */
        fun identityOf(path: Path): String? = runCatching {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            // Without a file key (Windows), the creation time: it does not change as the file is written.
            attributes.fileKey()?.toString() ?: "created:${attributes.creationTime().to(java.util.concurrent.TimeUnit.NANOSECONDS)}"
        }.getOrNull()
    }
}
