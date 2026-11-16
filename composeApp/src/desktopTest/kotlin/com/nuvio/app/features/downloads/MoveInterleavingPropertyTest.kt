package com.nuvio.app.features.downloads

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Many random interleavings, on real threads, of moves, deletes, writers that pause and resume, profile
 * switches, failing flushes, failing record saves and a failing Trash. After each round: every original
 * is in place or in the Trash (a partial file may only have grown), every record of every profile points
 * at an existing file, nothing is left locked, and no temporary file of a move is left.
 */
class MoveInterleavingPropertyTest {
    private val rounds = 30

    @Test
    fun random_interleavings_of_move_delete_pause_resume_and_profile_switch_never_lose_a_file() {
        repeat(rounds) { round(it) }
    }

    private fun round(seed: Int) {
        val world = FolderWorld()
        try {
            runRound(world, seed)
        } finally {
            world.close()
        }
    }

    private fun runRound(world: FolderWorld, seed: Int) {
        val chaos = Random(seed)
        val lock = Any()
        fun chance(p: Double) = synchronized(lock) { chaos.nextDouble() < p }
        fun <T> pick(list: List<T>): T? = synchronized(lock) { list.randomOrNull(chaos) }

        val app = world.appFolder.createDirectories()
        val drives = listOf(world.driveLocation("A"), world.driveLocation("B"))
        val destinations = listOf(app) + drives.map { it.path }
        val originals = mutableMapOf<Path, ByteArray>()
        val partials = mutableMapOf<Path, ByteArray>()

        fun completed(id: String, s: Int, sidecar: Boolean): DownloadItem {
            val video = app.resolve("$id.mp4").writeVideo(seed * 100 + s, 20_000)
            originals[video] = Files.readAllBytes(video)
            if (sidecar) {
                val sub = app.resolve("$id.mp4.subtitles/en.srt").also { it.parent.createDirectories(); Files.writeString(it, "sub $id $seed") }
                originals[sub] = Files.readAllBytes(sub)
            }
            return movieItem(id, null, video, Files.size(video))
        }
        fun partial(id: String): DownloadItem {
            val file = app.resolve("$id.mp4.part").also { Files.write(it, ByteArray(3_000) { b -> (b + seed).toByte() }) }
            partials[file] = Files.readAllBytes(file)
            return movieItem(id).copy(status = DownloadStatus.Paused, localFileUri = null, downloadedBytes = 3_000L, totalBytes = null)
        }
        val one = listOf(completed("c1", 1, true), completed("c2", 2, false), completed("c3", 3, true), completed("c4", 4, false), partial("p1"), partial("p2"))
        val two = listOf(completed("own", 5, false), one[1].copy(id = "shares-c2"))
        val hooks = ProfileHooks(mapOf(1 to one, 2 to two))
        val mover = world.mover(
            syncDirectory = { !chance(0.04) },
            trash = { path -> if (chance(0.1)) false else world.trash.send(path) },
        )
        val folders = DesktopDownloadFolders(world.locations, world.validator, mover, hooks, writerWaitMs = 150L)
        val ids = one.map { it.id }

        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        fun worker(name: String, block: () -> Unit) = Thread({
            try {
                block()
            } catch (error: Throwable) {
                errors += error
            }
        }, name)

        val moverThread = worker("mover") {
            repeat(6) {
                hooks.saveFails = chance(0.15)
                val chosen = ids.filter { chance(0.5) }.toSet()
                val target = pick(destinations)!!
                folders.move(chosen, target.toString())
                hooks.saveFails = false
                if (chance(0.5)) {
                    folders.refresh()
                    folders.state.value.leftovers.forEach { leftover ->
                        when {
                            leftover.kind == LeftoverKind.Unresolved -> Unit
                            chance(0.5) -> folders.removeExtraCopy(leftover.id)
                            else -> folders.keepBoth(leftover.id)
                        }
                    }
                }
            }
        }
        val deleter = worker("delete") {
            repeat(4) {
                Thread.sleep(synchronized(lock) { chaos.nextLong(1, 15) })
                val id = pick(ids) ?: return@repeat
                // As the repository deletes: under the item lock, the file to the Trash, then the record goes.
                if (!ItemLocks.tryAcquire(id)) return@repeat
                try {
                    val record = hooks.recordOf(1, id) ?: return@repeat
                    val file = if (record.status == DownloadStatus.Completed) {
                        DownloadLocations.fileOfUri(record.localFileUri!!)!!.toPath()
                    } else {
                        world.locations.locationOfRecord(record.locationId, null).path.resolve(record.fileName + ".part")
                    }
                    val sharedByAnotherProfile = hooks.list(2).any { it.localFileUri != null && it.localFileUri == record.localFileUri }
                    if (sharedByAnotherProfile) return@repeat
                    if (Files.exists(file) && !world.trash.send(file)) return@repeat
                    file.resolveSibling("${file.fileName}.subtitles").takeIf { Files.isDirectory(it) }?.let { world.trash.send(it) }
                    hooks.update(1) { list -> list.filterNot { it.id == id } }
                } finally {
                    ItemLocks.release(id)
                }
            }
        }
        val writer = worker("writer") {
            repeat(10) {
                Thread.sleep(synchronized(lock) { chaos.nextLong(1, 10) })
                val id = pick(listOf("p1", "p2"))!!
                // A transfer takes the writer token, writes at its record's location, and gives the token back when it pauses.
                if (!WriterTokens.tryAcquire(id, WriterTokens.Kind.Writer)) return@repeat
                try {
                    val record = hooks.recordOf(1, id) ?: return@repeat
                    val file = world.locations.locationOfRecord(record.locationId, null).path.resolve(record.fileName + ".part")
                    if (Files.isRegularFile(file)) Files.write(file, ByteArray(100) { 9 }, StandardOpenOption.APPEND)
                } finally {
                    WriterTokens.release(id)
                }
            }
        }
        val switcher = worker("profiles") {
            repeat(3) {
                Thread.sleep(synchronized(lock) { chaos.nextLong(1, 20) })
                hooks.profile = 2
                Thread.sleep(2)
                hooks.profile = 1
            }
        }
        val threads = listOf(moverThread, deleter, writer, switcher)
        threads.forEach(Thread::start)
        threads.forEach { it.join(120_000) }
        threads.filter { it.isAlive }.takeIf { it.isNotEmpty() }?.let { alive ->
            alive.forEach(Thread::interrupt)
            fail("round $seed: deadlock or hang in ${alive.map { it.name }}")
        }
        errors.firstOrNull()?.let { throw AssertionError("round $seed: ${it.message}", it) }

        // The app starts again.
        hooks.profile = 1
        world.mover().recover()

        originals.forEach { (path, bytes) ->
            val inPlace = Files.isRegularFile(path) && Files.readAllBytes(path).contentEquals(bytes)
            if (!inPlace && !world.trash.holds(bytes)) fail("round $seed: $path is neither in place nor in the Trash")
        }
        partials.forEach { (path, bytes) ->
            val grownFrom: (Path) -> Boolean = { file -> Files.size(file) >= bytes.size && Files.readAllBytes(file).copyOf(bytes.size).contentEquals(bytes) }
            val somewhere = (listOf(path) + world.trash.files()).any { Files.isRegularFile(it) && grownFrom(it) } ||
                hooks.list(1).any { r -> r.status != DownloadStatus.Completed && world.locations.locationOfRecord(r.locationId, null).path.resolve(r.fileName + ".part").let { Files.isRegularFile(it) && grownFrom(it) } }
            if (!somewhere) fail("round $seed: the partial $path was lost")
        }
        listOf(1, 2).forEach { profile ->
            hooks.list(profile).forEach { record ->
                val file = if (record.status == DownloadStatus.Completed) {
                    DownloadLocations.fileOfUri(record.localFileUri!!)!!.toPath()
                } else {
                    world.locations.locationOfRecord(record.locationId, null).path.resolve(record.fileName + ".part")
                }
                if (!Files.isRegularFile(file)) fail("round $seed: record ${record.id} of profile $profile points at a missing $file")
            }
        }
        (destinations).forEach { assertEquals(emptyList(), world.tempsIn(it), "round $seed: temporary file left") }
        assertTrue(ids.none { ItemLocks.isHeld(it) || WriterTokens.kindOf(it) != null }, "round $seed: a lock or token was kept")
        assertFalse(folders.state.value.moving, "round $seed: the move gate was kept")
        assertTrue(world.mover().unresolvedJournals().isEmpty(), "round $seed: an unresolved journal is left")
    }
}
