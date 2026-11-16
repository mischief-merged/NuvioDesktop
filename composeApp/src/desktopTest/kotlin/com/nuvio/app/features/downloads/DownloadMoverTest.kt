package com.nuvio.app.features.downloads

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mover, one test per failure class the three reviews found. Every test runs in temporary folders.
 * The rule they all check: no user file is ever deleted; an original is in place or in the Trash.
 */
class DownloadMoverTest {
    private val world = FolderWorld()

    @AfterTest
    fun clean_up() {
        world.close()
    }

    private fun completed(name: String, seed: Int, location: DesktopDownloadLocation? = null, sidecar: List<String> = emptyList()): DownloadItem {
        val folder = location?.path ?: world.appFolder.createDirectories()
        val video = folder.resolve("$name.mp4").writeVideo(seed)
        sidecar.forEachIndexed { i, rel ->
            val file = video.resolveSibling("${video.fileName}.subtitles").resolve(rel)
            file.parent.createDirectories()
            Files.writeString(file, "subtitle $name $i")
        }
        return movieItem(name, location?.id, video, Files.size(video))
    }

    private fun partial(name: String, size: Int, location: DesktopDownloadLocation? = null): DownloadItem {
        val folder = location?.path ?: world.appFolder.createDirectories()
        Files.write(folder.resolve("$name.mp4.part"), ByteArray(size) { (it * 7).toByte() })
        return movieItem(name, location?.id).copy(status = DownloadStatus.Paused, localFileUri = null, downloadedBytes = size.toLong(), totalBytes = null)
    }

    private fun fileOf(item: DownloadItem): Path = DownloadLocations.fileOfUri(item.localFileUri!!)!!.toPath()

    private fun folders(hooks: DownloadsHooks, mover: DownloadMover = world.mover(), writerWaitMs: Long = 2_000L, isPlaying: (DownloadItem) -> Boolean = { false }) =
        DesktopDownloadFolders(world.locations, world.validator, mover, hooks, writerWaitMs = writerWaitMs, isPlaying = isPlaying)

    private fun bytes(path: Path) = Files.readAllBytes(path)

    private fun noLocksHeld(vararg items: DownloadItem) = items.none { ItemLocks.isHeld(it.id) || WriterTokens.kindOf(it.id) != null }

    // The ordinary move.

    @Test
    fun a_move_copies_verifies_publishes_repoints_the_record_and_sends_the_original_and_its_sidecar_to_the_trash() {
        val item = completed("film", 1, sidecar = listOf("en.srt", "extra/skip.json"))
        val drive = world.driveLocation("Archive")
        val video = bytes(fileOf(item))
        val hooks = ProfileHooks(listOf(item))

        val report = folders(hooks).move(setOf(item.id), drive.path.toString())

        assertEquals(1, report.moved)
        assertTrue(report.failures.isEmpty() && report.originalsKept.isEmpty())
        val record = hooks.items.single()
        assertEquals(drive.id, record.locationId)
        assertContentEquals(video, bytes(fileOf(record)))
        assertEquals("subtitle film 1", Files.readString(drive.path.resolve("film.mp4.subtitles/extra/skip.json")))
        assertFalse(fileOf(item).exists())
        assertFalse(world.appFolder.resolve("film.mp4.subtitles").exists())
        assertTrue(world.trash.holds(video), "the original is not in the Trash")
        assertTrue(world.trash.files().any { it.toString().endsWith("film.mp4.subtitles/extra/skip.json") })
        assertTrue(world.tempsIn(drive.path).isEmpty())
        assertTrue(world.mover().journalFiles().isEmpty())
        assertTrue(noLocksHeld(item))
    }

    @Test
    fun a_partial_download_moves_with_its_record_and_its_partial_goes_to_the_trash() {
        val item = partial("show", 5_000)
        val drive = world.driveLocation("Archive")
        val before = bytes(world.appFolder.resolve("show.mp4.part"))
        val hooks = ProfileHooks(listOf(item))

        val report = folders(hooks).move(setOf(item.id), drive.path.toString())

        assertEquals(1, report.moved)
        assertEquals(drive.id, hooks.items.single().locationId)
        assertContentEquals(before, bytes(drive.path.resolve("show.mp4.part")))
        assertTrue(world.trash.holds(before))
    }

    // Review 1.

    @Test
    fun a_file_already_at_the_destination_is_never_replaced_or_taken_for_the_moves_output() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val stranger = drive.path.resolve("film.mp4").writeVideo(99)
        val strangerBytes = bytes(stranger)
        val hooks = ProfileHooks(listOf(item))

        val report = folders(hooks).move(setOf(item.id), drive.path.toString())
        assertFailsWith<Crash> { folders(hooks, world.mover(checkpoint = { if (it == "journal-created") throw Crash(it) })).move(setOf(item.id), drive.path.toString()) }
        world.mover().recover()

        assertEquals(MoveFailureReason.NameTaken, report.failures.single().reason)
        assertContentEquals(strangerBytes, bytes(stranger))
        assertTrue(fileOf(item).exists())
        assertEquals(item, hooks.items.single())
        assertTrue(world.mover().journalFiles().isEmpty())
    }

    @Test
    fun the_size_and_hash_are_taken_only_after_the_writer_stopped_and_while_the_move_holds_its_token() {
        val item = partial("live", 1_000).copy(status = DownloadStatus.Downloading)
        val partFile = world.appFolder.resolve("live.mp4.part")
        val drive = world.driveLocation("Archive")
        val hooks = object : ProfileHooks(listOf(item)) {
            override fun pauseAndWait(downloadId: String): Boolean {
                // The writer flushes its last bytes while it stops.
                Files.write(partFile, ByteArray(500) { 1 }, java.nio.file.StandardOpenOption.APPEND)
                return super.pauseAndWait(downloadId)
            }
        }
        var tokenDuringCopy: WriterTokens.Kind? = null
        val mover = world.mover(copy = { from, to, onBytes, cancelled ->
            tokenDuringCopy = WriterTokens.kindOf(item.id)
            DownloadMover.copyFile(from, to, onBytes, cancelled)
        })

        val report = folders(hooks, mover).move(setOf(item.id), drive.path.toString())

        assertEquals(1, report.moved)
        assertEquals(WriterTokens.Kind.Mover, tokenDuringCopy)
        assertEquals(1_500L, Files.size(drive.path.resolve("live.mp4.part")))
        assertTrue("resume ${item.id}" in hooks.log)
        assertTrue(noLocksHeld(item))
    }

    @Test
    fun a_delete_cannot_take_an_item_while_the_move_has_it() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        var deleteGotIt: Boolean? = null
        val mover = world.mover(checkpoint = { if (it == "copy-verified") deleteGotIt = ItemLocks.tryAcquire(item.id) })

        folders(ProfileHooks(listOf(item)), mover).move(setOf(item.id), drive.path.toString())

        assertEquals(false, deleteGotIt)
        assertTrue(noLocksHeld(item))
    }

    @Test
    fun a_record_whose_drive_is_away_never_moves_a_same_named_file_from_another_drive() {
        val away = world.driveLocation("Away")
        val other = world.driveLocation("Other")
        val item = completed("film", 1, location = away)
        val sameName = other.path.resolve("film.mp4").writeVideo(2)
        val sameNameBytes = bytes(sameName)
        world.unplug("Away")
        val hooks = ProfileHooks(listOf(item))

        val report = folders(hooks).move(setOf(item.id), world.appFolder.toString())

        assertEquals(0, report.moved)
        assertEquals(1, report.unreachable)
        assertContentEquals(sameNameBytes, bytes(sameName))
        assertFalse(world.appFolder.resolve("film.mp4").exists())
        assertEquals(item, hooks.items.single())
    }

    @Test
    fun a_folder_flush_that_fails_keeps_the_original_changes_no_record_and_lists_the_copy() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val video = bytes(fileOf(item))
        val hooks = ProfileHooks(listOf(item))
        val mover = world.mover(syncDirectory = { dir -> !dir.startsWith(drive.path) })

        val report = folders(hooks, mover).move(setOf(item.id), drive.path.toString())

        assertEquals(0, report.moved)
        assertEquals(MoveFailureReason.KeptInBothPlaces, report.failures.single().reason)
        assertContentEquals(video, bytes(fileOf(item)))
        assertEquals(item, hooks.items.single())
        assertTrue(world.trash.files().isEmpty())
        assertEquals(1, world.mover().leftovers().size)
    }

    @Test
    fun a_link_is_never_moved_and_what_it_points_at_is_untouched() {
        val real = world.root.resolve("elsewhere/real.mp4").writeVideo(1)
        val link = world.appFolder.createDirectories().resolve("film.mp4")
        Files.createSymbolicLink(link, real)
        val item = movieItem("film", file = link, bytes = Files.size(real))
        val drive = world.driveLocation("Archive")

        val report = folders(ProfileHooks(listOf(item))).move(setOf(item.id), drive.path.toString())

        assertEquals(0, report.moved)
        assertEquals(MoveFailureReason.Symlink, report.failures.single().reason)
        assertTrue(Files.isSymbolicLink(link) && real.exists())
        assertTrue(world.trash.files().isEmpty())
    }

    @Test
    fun a_file_that_appears_at_the_final_name_during_the_move_is_never_replaced() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val intruder = "someone else's file".toByteArray()
        val mover = world.mover(createLink = { link, existing ->
            if (!link.fileName.toString().startsWith(".nuvio-")) Files.write(link, intruder, java.nio.file.StandardOpenOption.CREATE_NEW)
            Files.createLink(link, existing)
        })
        val hooks = ProfileHooks(listOf(item))

        val report = folders(hooks, mover).move(setOf(item.id), drive.path.toString())

        assertEquals(MoveFailureReason.NameTaken, report.failures.single().reason)
        assertContentEquals(intruder, bytes(drive.path.resolve("film.mp4")))
        assertTrue(fileOf(item).exists())
        assertEquals(item, hooks.items.single())
        assertTrue(world.tempsIn(drive.path).isEmpty())
        // The file at the final name is not this move's, so nothing is listed as its copy.
        assertTrue(world.mover().leftovers().isEmpty())
    }

    @Test
    fun a_crash_between_publishing_the_video_and_its_subtitles_leaves_every_original_in_place() {
        val item = completed("film", 1, sidecar = listOf("en.srt"))
        val drive = world.driveLocation("Archive")
        val subtitle = world.appFolder.resolve("film.mp4.subtitles/en.srt")
        var published = 0
        val mover = world.mover(checkpoint = { if (it == "published" && ++published == 1) throw Crash(it) })
        val hooks = ProfileHooks(listOf(item))

        assertFailsWith<Crash> { folders(hooks, mover).move(setOf(item.id), drive.path.toString()) }
        val recovered = world.mover().recover()

        assertTrue(fileOf(item).exists() && subtitle.exists())
        assertEquals("subtitle film 0", Files.readString(subtitle))
        assertEquals(item, hooks.items.single())
        assertEquals(1, recovered.leftovers)
        assertTrue(world.tempsIn(drive.path).isEmpty())
    }

    // Review 2.

    @Test
    fun a_journal_that_cannot_be_written_after_the_record_was_saved_keeps_both_files() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val video = bytes(fileOf(item))
        val hooks = ProfileHooks(listOf(item))
        val mover = world.mover(beforeJournalWrite = { journal -> if (journal.items.any { it.recorded }) throw java.io.IOException("disk full") })

        val report = folders(hooks, mover).move(setOf(item.id), drive.path.toString())
        world.mover().recover()

        assertEquals(MoveFailureReason.KeptInBothPlaces, report.failures.single().reason)
        // The record was confirmed: it points at the copy. The original was not touched.
        assertEquals(drive.id, hooks.items.single().locationId)
        assertContentEquals(video, bytes(fileOf(hooks.items.single())))
        assertContentEquals(video, bytes(fileOf(item)))
        assertTrue(world.trash.files().isEmpty())
        assertEquals(1, world.mover().leftovers().size)
    }

    @Test
    fun an_original_replaced_by_another_file_before_the_trash_step_is_kept() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val replacement = "a different file under the same name".toByteArray()
        val mover = world.mover(checkpoint = {
            if (it == "recorded") {
                // Another program puts a new file under the old name; the move must not send it away.
                Files.move(fileOf(item), world.root.resolve("kept-aside.mp4"))
                Files.write(fileOf(item), replacement)
            }
        })

        val report = folders(ProfileHooks(listOf(item)), mover).move(setOf(item.id), drive.path.toString())

        assertEquals(1, report.moved)
        assertEquals(MoveFailureReason.OriginalNotRemoved, report.originalsKept.single().reason)
        assertContentEquals(replacement, bytes(fileOf(item)))
        assertTrue(world.trash.files().isEmpty())
    }

    @Test
    fun recovery_never_touches_a_folder_at_the_drives_path_that_lacks_its_marker() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        assertFailsWith<Crash> { folders(ProfileHooks(listOf(item)), world.mover(checkpoint = { if (it == "copy-verified") throw Crash(it) })).move(setOf(item.id), drive.path.toString()) }
        val temp = world.tempsIn(drive.path).single()
        world.unplug("Archive")
        // Another disk mounts at the same path, with a file under the temporary file's name.
        val impostor = drive.path.createDirectories().resolve(temp.fileName.toString())
        Files.writeString(impostor, "not the move's")

        val first = world.mover().recover()

        assertEquals(1, first.unresolved)
        assertEquals("not the move's", Files.readString(impostor))
        assertEquals(FolderProblem.PreviousMoveUnresolved, folders(ProfileHooks(listOf(item))).move(setOf(item.id), world.driveLocation("Other").path.toString()).problem)

        world.root.resolve("Volumes/Archive").toFile().deleteRecursively()
        world.replug("Archive")
        val second = world.mover().recover()
        assertEquals(1, second.tempsRemoved)
        assertEquals(0, second.unresolved)
        assertTrue(fileOf(item).exists())
    }

    @Test
    fun recovery_never_sends_an_original_to_the_trash_even_after_the_record_moved() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val hooks = ProfileHooks(listOf(item))
        assertFailsWith<Crash> { folders(hooks, world.mover(checkpoint = { if (it == "recorded") throw Crash(it) })).move(setOf(item.id), drive.path.toString()) }

        val report = world.mover().recover()

        assertEquals(1, report.leftovers)
        assertTrue(fileOf(item).exists())
        assertTrue(world.trash.files().isEmpty())
        assertEquals(drive.id, hooks.items.single().locationId)
        assertTrue(fileOf(hooks.items.single()).exists())
    }

    @Test
    fun a_second_window_can_neither_move_nor_recover_while_the_first_holds_the_lock() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val held = assertNotNull(MoveLock(world.root.resolve("moves/move.lock")).tryAcquire())
        try {
            val report = folders(ProfileHooks(listOf(item))).move(setOf(item.id), drive.path.toString())
            assertEquals(FolderProblem.MoveInProgress, report.problem)
            assertTrue(world.mover().recover().busy)
            assertTrue(fileOf(item).exists())
        } finally {
            held.close()
        }
        assertEquals(1, folders(ProfileHooks(listOf(item))).move(setOf(item.id), drive.path.toString()).moved)
    }

    @Test
    fun the_record_is_repointed_in_the_profile_that_started_the_move_even_when_another_became_active() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val otherProfilesRecord = movieItem("film", file = world.root.resolve("p2/film.mp4").writeVideo(5))
        val hooks = ProfileHooks(mapOf(1 to listOf(item), 2 to listOf(otherProfilesRecord)), profile = 1)
        hooks.onRelocate = { hooks.profile = 2 }

        val report = folders(hooks).move(setOf(item.id), drive.path.toString())

        assertEquals(1, report.moved)
        assertEquals(drive.id, hooks.list(1).single().locationId)
        assertEquals(otherProfilesRecord, hooks.list(2).single())
    }

    @Test
    fun a_crash_while_publishing_on_a_volume_without_hard_links_keeps_the_evidence_and_lists_it() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val noLinks: (Path, Path) -> Unit = { _, _ -> throw UnsupportedOperationException("no links here") }
        val hooks = ProfileHooks(listOf(item))
        assertFailsWith<Crash> {
            folders(hooks, world.mover(createLink = noLinks, checkpoint = { if (it == "published-unflushed") throw Crash(it) })).move(setOf(item.id), drive.path.toString())
        }

        val report = world.mover(createLink = noLinks).recover()

        assertEquals(1, report.leftovers)
        assertContentEquals(bytes(fileOf(item)), bytes(drive.path.resolve("film.mp4")))
        val leftover = world.mover().leftovers().single()
        assertEquals(drive.path.resolve("film.mp4").toString(), leftover.destinationPath)
        assertEquals(1, world.mover().journalFiles().size)
    }

    @Test
    fun a_temporary_name_that_is_already_taken_is_left_alone_and_another_is_chosen() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val taken = drive.path.resolve(".nuvio-feedfeedfeedfeedfeedfeedfeedfeed-fixed.tmp")
        Files.writeString(taken, "someone's file")
        val names = ArrayDeque(listOf("fixed"))
        val mover = world.mover(txId = { "feedfeedfeedfeedfeedfeedfeedfeed" }, randomName = { names.removeFirstOrNull() ?: DownloadMover.randomHex(8) })

        val report = folders(ProfileHooks(listOf(item)), mover).move(setOf(item.id), drive.path.toString())

        assertEquals(1, report.moved)
        assertEquals("someone's file", Files.readString(taken))
    }

    // Review 3.

    @Test
    fun a_record_save_that_is_not_confirmed_durable_keeps_the_original_and_the_record() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val hooks = ProfileHooks(listOf(item)).apply { saveFails = true }

        val report = folders(hooks).move(setOf(item.id), drive.path.toString())

        assertEquals(MoveFailureReason.KeptInBothPlaces, report.failures.single().reason)
        assertEquals(item, hooks.items.single())
        assertTrue(fileOf(item).exists())
        assertTrue(world.trash.files().isEmpty())
        assertEquals(1, world.mover().leftovers().size)
    }

    @Test
    fun a_file_another_profile_refers_to_is_never_moved() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val shared = item.copy(id = "other-id")
        val hooks = ProfileHooks(mapOf(1 to listOf(item), 2 to listOf(shared)))

        val report = folders(hooks).move(setOf(item.id), drive.path.toString())

        assertEquals(MoveFailureReason.OwnedByAnother, report.failures.single().reason)
        assertTrue(fileOf(item).exists())
        assertFalse(drive.path.resolve("film.mp4").exists())
    }

    @Test
    fun a_partial_file_two_unfinished_records_share_is_never_moved() {
        val first = partial("ep", 2_000)
        val second = first.copy(id = "second")
        val drive = world.driveLocation("Archive")
        val hooks = ProfileHooks(listOf(first, second))

        val report = folders(hooks).move(setOf(first.id), drive.path.toString())

        assertEquals(MoveFailureReason.OwnedByAnother, report.failures.single().reason)
        assertTrue(world.appFolder.resolve("ep.mp4.part").exists())
    }

    @Test
    fun a_record_list_of_another_profile_that_cannot_be_read_stops_every_item() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val hooks = ProfileHooks(listOf(item)).apply { unreadable = true }

        val report = folders(hooks).move(setOf(item.id), drive.path.toString())

        assertEquals(0, report.moved)
        assertEquals(MoveFailureReason.OwnedByAnother, report.failures.single().reason)
        assertTrue(fileOf(item).exists())
    }

    @Test
    fun a_destination_name_an_unfinished_download_will_write_is_refused() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val waiting = movieItem("film", drive.id).copy(id = "waiting", status = DownloadStatus.Paused, localFileUri = null)
        val hooks = ProfileHooks(listOf(item, waiting))

        val report = folders(hooks).move(setOf(item.id), drive.path.toString())

        assertEquals(MoveFailureReason.OwnedByAnother, report.failures.single().reason)
        assertTrue(fileOf(item).exists())
    }

    @Test
    fun a_recorded_file_that_is_missing_is_never_replaced_by_a_file_of_the_same_name() {
        val app = world.appFolder.createDirectories()
        val stranger = app.resolve("movie.mkv").writeVideo(3)
        val record = movieItem("m").copy(fileName = "movie.mkv", localFileUri = app.resolve("old-name.mkv").toUri().toString())

        assertEquals(FileLookup.Missing, world.locations.locate(record.locationId, record.localFileUri, record.fileName))
        assertEquals(FileLookup.Missing, world.locations.locate(null, null, "movie.mkv"))
        val report = folders(ProfileHooks(listOf(record))).move(setOf(record.id), world.driveLocation("Archive").path.toString())
        assertEquals(0, report.moved)
        assertTrue(stranger.exists())
    }

    @Test
    fun a_location_named_by_a_journal_is_never_forgotten() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val hooks = ProfileHooks(listOf(item)).apply { saveFails = true }

        folders(hooks).move(setOf(item.id), drive.path.toString())

        // No record uses the drive, but the leftover copy there is named by a journal.
        assertTrue(drive.id in world.locations.all().map { it.id })
        assertEquals(setOf(DownloadLocations.AppId, drive.id), world.mover().referencedLocations())
    }

    @Test
    fun an_unreadable_journal_means_no_location_is_forgotten() {
        val item = completed("film", 1)
        val first = world.driveLocation("One")
        val hooks = ProfileHooks(listOf(item))
        val folders = folders(hooks)
        folders.move(setOf(item.id), first.path.toString())
        val spare = world.driveLocation("Spare")
        Files.writeString(world.root.resolve("moves").createDirectories().resolve("move-broken.journal"), "{ not json")

        assertNull(world.mover().referencedLocations())
        assertEquals(FolderProblem.PreviousMoveUnresolved, folders.move(setOf(hooks.items.single().id), world.appFolder.toString()).problem)
        assertTrue(spare.id in world.locations.all().map { it.id })
    }

    @Test
    fun every_new_folder_up_to_the_location_is_flushed_before_the_record_moves_and_a_failed_one_stops_the_item() {
        val item = completed("film", 1, sidecar = listOf("deep/er/en.srt"))
        val drive = world.driveLocation("Archive")
        val flushed = java.util.Collections.synchronizedSet(mutableSetOf<Path>())
        val hooks = ProfileHooks(listOf(item))
        var flushedBeforeRecord: Set<Path> = emptySet()
        hooks.onRelocate = { flushedBeforeRecord = flushed.toSet() }

        folders(hooks, world.mover(syncDirectory = { flushed.add(it); true })).move(setOf(item.id), drive.path.toString())

        val sidecar = drive.path.resolve("film.mp4.subtitles")
        assertTrue(flushedBeforeRecord.containsAll(listOf(drive.path, sidecar, sidecar.resolve("deep"), sidecar.resolve("deep/er"))), "$flushedBeforeRecord")

        // The same move again, with the flush of one new ancestor folder failing: nothing goes to the Trash.
        val again = completed("again", 2, sidecar = listOf("deep/er/en.srt"))
        val againHooks = ProfileHooks(listOf(again))
        val report = folders(againHooks, world.mover(syncDirectory = { it != drive.path.resolve("again.mp4.subtitles") })).move(setOf(again.id), drive.path.toString())
        assertEquals(MoveFailureReason.KeptInBothPlaces, report.failures.single().reason)
        assertTrue(fileOf(again).exists())
        assertEquals(again, againHooks.items.single())
    }

    @Test
    fun every_entry_has_its_whole_hash_in_the_journal_before_it_is_published() {
        val item = completed("film", 1, sidecar = listOf("en.srt"))
        val drive = world.driveLocation("Archive")
        val expected = mapOf(
            "film.mp4" to DownloadMover.sha256Of(fileOf(item)),
            "film.mp4.subtitles/en.srt" to DownloadMover.sha256Of(world.appFolder.resolve("film.mp4.subtitles/en.srt")),
        )
        val problems = mutableListOf<String>()
        val mover = world.mover(beforeJournalWrite = { journal ->
            journal.items.flatMap { it.entries }.filter { it.state >= EntryState.Publishing }.forEach { e ->
                if (e.hash != expected[e.dstRel]) problems += "${e.dstRel} at ${e.state} has ${e.hash}"
            }
        })

        assertEquals(1, folders(ProfileHooks(listOf(item)), mover).move(setOf(item.id), drive.path.toString()).moved)
        assertEquals(emptyList(), problems)
    }

    @Test
    fun an_unreadable_journal_blocks_new_moves_is_kept_byte_for_byte_listed_and_can_be_set_aside_without_overwriting() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val moves = world.root.resolve("moves").createDirectories()
        val broken = moves.resolve("move-0123.journal")
        Files.writeString(broken, "{\"txId\":\"0123\",\"items\":[{\"trunc")
        val earlier = moves.resolve("set-aside").createDirectories().resolve("move-0123.journal")
        Files.writeString(earlier, "an earlier one")
        val hooks = ProfileHooks(listOf(item))
        val folders = folders(hooks)

        assertEquals(FolderProblem.PreviousMoveUnresolved, folders.move(setOf(item.id), drive.path.toString()).problem)
        val recovered = world.mover().recover()
        assertEquals(1, recovered.unreadable)
        assertEquals("{\"txId\":\"0123\",\"items\":[{\"trunc", Files.readString(broken))
        folders.refresh()
        val row = folders.state.value.leftovers.single()
        assertEquals(LeftoverKind.Unresolved, row.kind)

        assertTrue(folders.setAside(row.id))

        assertEquals("an earlier one", Files.readString(earlier))
        assertEquals(2, Files.list(moves.resolve("set-aside")).use { it.count() })
        assertEquals(1, folders.move(setOf(item.id), drive.path.toString()).moved)
    }

    @Test
    fun a_resume_that_throws_releases_the_gate_and_every_other_resume_still_runs() {
        val a = partial("a", 100).copy(status = DownloadStatus.Downloading)
        val b = partial("b", 100).copy(status = DownloadStatus.Downloading)
        val drive = world.driveLocation("Archive")
        val hooks = ProfileHooks(listOf(a, b))
        hooks.onResume = { if (it == a.id) throw IllegalStateException("disk full") }
        val folders = folders(hooks)

        folders.move(setOf(a.id, b.id), drive.path.toString())

        assertTrue("resume ${b.id}" in hooks.log)
        assertFalse(folders.state.value.moving)
        assertTrue(noLocksHeld(a, b))
        val c = completed("c", 3)
        hooks.items = hooks.items + c
        assertEquals(1, folders.move(setOf(c.id), drive.path.toString()).moved)
    }

    // The rest of the rules.

    @Test
    fun a_trash_that_refuses_leaves_the_original_where_it_was_and_says_so() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        world.trash.fails = true
        val hooks = ProfileHooks(listOf(item))

        val report = folders(hooks).move(setOf(item.id), drive.path.toString())

        assertEquals(1, report.moved)
        assertEquals(MoveFailureReason.OriginalNotRemoved, report.originalsKept.single().reason)
        assertTrue(fileOf(item).exists())
        assertEquals(drive.id, hooks.items.single().locationId)
        assertEquals(1, world.mover().leftovers().size)
        // A leftover is not an unresolved move: the next move may start.
        assertTrue(world.mover().unresolvedJournals().isEmpty())
    }

    @Test
    fun removing_the_extra_copy_needs_equal_content_and_the_record_on_the_kept_side_and_uses_the_trash() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        world.trash.fails = true
        val hooks = ProfileHooks(listOf(item))
        val folders = folders(hooks)
        folders.move(setOf(item.id), drive.path.toString())
        world.trash.fails = false
        folders.refresh()
        val leftover = folders.state.value.leftovers.single()

        // The copies differ: refused, nothing moves.
        val original = fileOf(item)
        val originalBytes = bytes(original)
        Files.write(original, "changed".toByteArray())
        assertEquals(LeftoverOutcome.Refused, folders.removeExtraCopy(leftover.id))
        assertTrue(original.exists())
        Files.write(original, originalBytes)

        // The record points nowhere the journal knows: refused.
        val moved = hooks.items.single()
        hooks.items = listOf(moved.copy(locationId = "elsewhere"))
        assertEquals(LeftoverOutcome.Refused, folders.removeExtraCopy(leftover.id))
        hooks.items = listOf(moved)

        // Another download holds the item: refused.
        assertTrue(ItemLocks.tryAcquire(item.id))
        assertEquals(LeftoverOutcome.Refused, folders.removeExtraCopy(leftover.id))
        ItemLocks.release(item.id)

        assertEquals(LeftoverOutcome.Removed, folders.removeExtraCopy(leftover.id))
        assertFalse(original.exists())
        assertTrue(world.trash.holds(originalBytes))
        assertTrue(fileOf(moved).exists())
        assertTrue(world.mover().journalFiles().isEmpty())
    }

    @Test
    fun keeping_both_changes_only_the_journal() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val hooks = ProfileHooks(listOf(item)).apply { saveFails = true }
        val folders = folders(hooks)
        folders.move(setOf(item.id), drive.path.toString())
        folders.refresh()

        folders.keepBoth(folders.state.value.leftovers.single().id)

        assertTrue(fileOf(item).exists() && drive.path.resolve("film.mp4").exists())
        assertTrue(world.mover().journalFiles().isEmpty())
        assertTrue(world.trash.files().isEmpty())
    }

    @Test
    fun cancelling_during_a_copy_removes_only_this_moves_temporary_file() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        lateinit var folders: DesktopDownloadFolders
        val mover = world.mover(copy = { from, to, onBytes, cancelled ->
            folders.cancelMove()
            DownloadMover.copyFile(from, to, onBytes, cancelled)
        })
        val hooks = ProfileHooks(listOf(item))
        folders = folders(hooks, mover)

        val report = folders.move(setOf(item.id), drive.path.toString())

        assertTrue(report.cancelled)
        assertTrue(fileOf(item).exists())
        assertEquals(item, hooks.items.single())
        assertTrue(world.tempsIn(drive.path).isEmpty())
        assertTrue(world.mover().journalFiles().isEmpty())
    }

    @Test
    fun a_copy_that_does_not_read_back_the_same_is_never_published() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val mover = world.mover(hashOf = { path -> if (path.fileName.toString().startsWith(".nuvio-")) "bad" else DownloadMover.sha256Of(path) })

        val report = folders(ProfileHooks(listOf(item)), mover).move(setOf(item.id), drive.path.toString())

        assertEquals(MoveFailureReason.CopyDidNotMatch, report.failures.single().reason)
        assertFalse(drive.path.resolve("film.mp4").exists())
        assertTrue(world.tempsIn(drive.path).isEmpty())
        assertTrue(fileOf(item).exists())
    }

    @Test
    fun the_trash_used_by_the_app_takes_a_nested_file_or_folder_inside_a_location_and_nothing_outside() {
        val drive = world.driveLocation("Archive")
        val sent = mutableListOf<java.io.File>()
        val trash = DesktopDownloadsTrash(
            downloadsDir = { world.appFolder.createDirectories() },
            systemTrash = { file -> sent += file; file.renameTo(world.root.resolve("sys-trash-${sent.size}").toFile()) },
            otherDirs = { listOf(drive.path) },
        )
        val nested = drive.path.resolve("film.mp4.subtitles/en.srt").also { it.parent.createDirectories(); Files.writeString(it, "x") }
        val outside = world.root.resolve("outside.mp4").writeVideo(1)

        assertTrue(trash.moveToTrash(nested.parent))
        assertFalse(trash.moveToTrash(outside))
        assertFalse(trash.moveToTrash(drive.path))

        assertFalse(nested.exists())
        assertTrue(outside.exists() && drive.path.exists())
        val held = DesktopDownloadsTrash(downloadsDir = { world.appFolder.createDirectories() }, systemTrash = null, otherDirs = { listOf(drive.path) })
        val file = drive.path.resolve("a.part").also { Files.writeString(it, "y") }
        assertTrue(held.moveToTrash(file))
        assertIs<Path>(Files.list(drive.path.resolve(".removed")).use { it.toList() }.first { it.fileName.toString().endsWith("a.part") })
    }

    @Test
    fun publication_does_not_fail_over_to_replacing_when_links_and_creation_both_say_the_name_exists() {
        val item = completed("film", 1)
        val drive = world.driveLocation("Archive")
        val mover = world.mover(createLink = { link, _ -> throw FileAlreadyExistsException(link.toString()) })
        val hooks = ProfileHooks(listOf(item))

        val report = folders(hooks, mover).move(setOf(item.id), drive.path.toString())

        assertEquals(MoveFailureReason.NameTaken, report.failures.single().reason)
        assertTrue(fileOf(item).exists())
        assertEquals(item, hooks.items.single())
    }
}
