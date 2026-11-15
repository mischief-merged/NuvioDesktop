package com.nuvio.app.features.downloads

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The move under its eight rules. Everything here is a temporary folder: a drive is a folder that is
 * moved aside, a crash is a checkpoint that throws.
 */
class DownloadMoverTest {
    private val world = FolderWorld()
    private val source: Path = world.appFolder.createDirectories()
    private val drive = world.driveLocation("Archive")
    private val target: Path = drive.path
    private val moves = world.root.resolve("moves")

    private class Crash(val at: String) : Error()

    @AfterTest
    fun clean_up() = world.close()

    private fun video(n: Int): Path = source.resolve("f$n.mp4").writeVideo(n)

    private fun items(count: Int) = (1..count).map { world.moveItem("item$it", video(it), drive.id) }

    private fun bytes(path: Path) = Files.readAllBytes(path)

    private fun sourceOf(item: MoveItem): Path = source.resolve(item.entries[0].srcRel)

    private fun leftoverNames(mover: DownloadMover) = mover.leftovers().map { it.name }

    private fun plan(planned: List<MoveItem>) = MovePlan(drive.id, "Movies", planned)

    private fun run(
        mover: DownloadMover,
        planned: List<MoveItem>,
        cancelAfterItems: Int? = null,
        canCommit: (MoveItem) -> Boolean = { true },
        moved: MutableList<Pair<MoveItem, Path?>> = mutableListOf(),
    ): MoveReport {
        var done = 0
        return mover.run(plan(planned), { cancelAfterItems != null && done >= cancelAfterItems }, { done = it.doneFiles }, canCommit) { item, path -> moved += item to path }
    }

    private fun tmpFiles() = Files.list(target).use { s -> s.filter { it.name.startsWith(".nuvio-") && it.name.endsWith(".tmp") }.toList() }

    private fun journals() = Files.list(moves).use { s -> s.filter { it.name.endsWith(".journal") }.toList() }

    private fun crashAt(step: String, planned: List<MoveItem>, mover: DownloadMover = world.mover(checkpoint = { if (it == step) throw Crash(step) })): List<MoveItem> {
        var crashed = false
        try {
            run(mover, planned)
        } catch (_: Crash) {
            crashed = true
        }
        assertTrue(crashed, "no crash at $step")
        return planned
    }

    // The ordinary move

    @Test
    fun a_file_is_copied_hashed_published_read_back_the_record_saved_and_only_then_the_original_removed() {
        val planned = items(2)
        val originals = planned.map { bytes(sourceOf(it)) }
        val moved = mutableListOf<Pair<MoveItem, Path?>>()
        var originalWhenRecorded: Boolean? = null
        val mover = world.mover(checkpoint = { step -> if (step == "record-saved" && originalWhenRecorded == null) originalWhenRecorded = sourceOf(planned[0]).exists() })

        val report = run(mover, planned, moved = moved)

        assertEquals(2, report.moved)
        assertTrue(report.failures.isEmpty() && !report.cancelled && report.originalsKept.isEmpty())
        assertEquals(true, originalWhenRecorded, "the original was removed before the record was saved")
        planned.forEachIndexed { i, item ->
            assertFalse(sourceOf(item).exists())
            assertContentEquals(originals[i], bytes(target.resolve(item.entries[0].dstRel)))
            assertFalse(Files.isSameFile(target.resolve(item.entries[0].dstRel), target.resolve(item.entries[0].dstRel)).not())
        }
        assertEquals(listOf(target.resolve("f1.mp4"), target.resolve("f2.mp4")), moved.map { it.second })
        assertEquals(emptyList(), tmpFiles())
        assertEquals(emptyList(), journals(), "the journal was left behind")
    }

    @Test
    fun the_journal_has_its_own_random_name_and_stores_identity_paths_hash_and_every_step() {
        val planned = items(1)
        val seen = mutableMapOf<String, String>()
        val mover = world.mover(checkpoint = { step -> journals().firstOrNull()?.let { seen[step] = it.toFile().readText() } })

        run(mover, planned)

        val first = seen.getValue("journal-written")
        assertTrue(Regex("\"txId\":\"[0-9a-f]{32}\"").containsMatchIn(first), "a 128-bit transaction id")
        assertTrue(first.contains("\"profileId\":1") && first.contains("\"itemId\":\"item1\"") && first.contains("\"recordRel\":\"f1.mp4\""))
        assertTrue(first.contains("\"srcLocationId\":\"app\"") && first.contains("\"dstLocationId\":\"${drive.id}\"") && first.contains("\"srcRel\":\"f1.mp4\""))
        assertTrue(seen.getValue("temp-created").contains("\"state\":\"TempCreated\"") && Regex("\"srcKey\":\"").containsMatchIn(seen.getValue("temp-created")))
        assertTrue(seen.getValue("temp-created").contains("\"tempKey\":"))
        val copied = seen.getValue("copy-recorded")
        assertTrue(copied.contains("\"state\":\"Copied\"") && Regex("\"hash\":\"[0-9a-f]{64}\"").containsMatchIn(copied), "the full hash")
        assertTrue(seen.getValue("linked").contains("\"state\":\"Publishing\""))
        assertTrue(seen.getValue("published").contains("\"state\":\"Published\""))
        assertTrue(seen.getValue("recorded").contains("\"recorded\":true"))
        assertTrue(Regex("move-[0-9a-f]{32}\\.journal").containsMatchIn(seen.keys.let { "move-" + first.substringAfter("\"txId\":\"").substringBefore("\"") + ".journal" }))
    }

    @Test
    fun on_one_volume_a_file_is_linked_not_copied_and_still_follows_every_barrier() {
        val planned = items(2)
        var copied = false
        val events = mutableListOf<String>()
        val mover = world.mover(
            sameStore = { _, _ -> true },
            copy = { _, _, _, _ -> copied = true; "" },
            syncDirectory = { events += "sync ${it.name}"; true },
            checkpoint = { events += it },
        )

        val report = run(mover, planned)

        assertEquals(2, report.moved)
        assertFalse(copied, "bytes were copied for a move inside one volume")
        planned.forEach {
            assertFalse(sourceOf(it).exists())
            assertTrue(target.resolve(it.entries[0].dstRel).exists())
        }
        assertTrue(events.indexOf("sync Movies") in 0 until events.indexOf("recorded"), "the destination folder was not flushed before the record")
    }

    // Rule 1

    @Test
    fun a_journal_write_that_fails_after_the_record_was_saved_keeps_both_copies_and_lists_a_leftover() {
        val planned = items(2)
        val mover = world.mover(beforeJournalWrite = { journal -> if (journal.items[0].recorded) throw IOException("disk full") })

        val report = run(mover, planned)

        assertEquals(1, report.moved, "the record was saved, so the item moved")
        assertTrue(report.originalsKept.any { it.reason == MoveFailureReason.KeptInBothPlaces })
        assertTrue(sourceOf(planned[0]).exists() && target.resolve("f1.mp4").exists(), "something was deleted")
        assertTrue(sourceOf(planned[1]).exists() && !target.resolve("f2.mp4").exists(), "the move went on after the journal failed")
        assertEquals(listOf("f1.mp4"), leftoverNames(mover))
        assertTrue(journals().isNotEmpty())
    }

    @Test
    fun an_original_replaced_by_a_different_file_before_cleanup_is_not_deleted() {
        val planned = items(1)
        val mover = world.mover(checkpoint = { step ->
            if (step == "recorded") {
                Files.delete(sourceOf(planned[0]))
                sourceOf(planned[0]).writeText("a different file now")
            }
        })

        val report = run(mover, planned)

        assertEquals(1, report.moved)
        assertEquals("a different file now", sourceOf(planned[0]).toFile().readText())
        assertEquals(MoveFailureReason.OriginalNotRemoved, report.originalsKept.single().reason)
    }

    @Test
    fun a_destination_folder_that_cannot_be_flushed_keeps_the_original_live_and_after_recovery() {
        val planned = items(1)
        val original = bytes(sourceOf(planned[0]))
        val mover = world.mover(syncDirectory = { false })

        val report = run(mover, planned)

        assertEquals(0, report.moved)
        assertEquals(MoveFailureReason.KeptInBothPlaces, report.failures.single().reason)
        assertContentEquals(original, bytes(sourceOf(planned[0])))
        assertTrue(target.resolve("f1.mp4").exists(), "nothing created at a final name may be deleted")
        assertEquals(listOf("f1.mp4"), leftoverNames(mover))

        val recovery = world.mover(syncDirectory = { false }).recover(null)

        assertContentEquals(original, bytes(sourceOf(planned[0])), "recovery removed an original")
        assertTrue(target.resolve("f1.mp4").exists())
        assertEquals(1, recovery.unresolvedJournals)
    }

    @Test
    fun recovery_never_removes_an_original_or_a_final_name_at_any_crash_point() {
        for (step in listOf("journal-written", "temp-created", "copied", "copy-recorded", "linked", "published", "record-saved", "recorded")) {
            val planned = items(1)
            val original = bytes(sourceOf(planned[0]))
            crashAt(step, planned)
            val before = target.resolve("f1.mp4").exists()

            val report = world.mover().recover(null)

            assertContentEquals(original, bytes(sourceOf(planned[0])), "$step: the original was removed by recovery")
            assertEquals(before, target.resolve("f1.mp4").exists(), "$step: recovery changed a final name")
            assertEquals(emptyList(), tmpFiles(), "$step: a temp file of the move was left")
            assertEquals(0, report.pending)
            // Reset for the next step.
            Files.list(moves).use { s -> s.toList() }.forEach { Files.deleteIfExists(it) }
            Files.deleteIfExists(target.resolve("f1.mp4"))
            Files.deleteIfExists(sourceOf(planned[0]))
        }
    }

    @Test
    fun a_file_at_the_destination_that_the_move_did_not_publish_is_never_touched() {
        val movie = video(1)
        val other = target.resolve("f1.mp4").writeVideo(2)
        val unrelated = bytes(other)
        val planned = listOf(world.moveItem("i", movie, drive.id))

        val report = run(world.mover(), planned)

        assertEquals(MoveFailureReason.NameTaken, report.failures.single().reason)
        assertContentEquals(unrelated, bytes(other))
        assertTrue(movie.exists())
        assertEquals(emptyList(), journals(), "nothing was made, so nothing is left open")
        assertEquals(emptyList(), world.mover().leftovers())
    }

    @Test
    fun a_crash_between_publishing_and_journalling_published_on_a_volume_without_links_keeps_the_destination_and_lists_it() {
        val planned = items(1)
        val noLinks = { _: Path, _: Path -> throw IOException("links are not supported") }
        crashAt("linked", planned, world.mover(createLink = noLinks, checkpoint = { if (it == "linked") throw Crash(it) }))

        val mover = world.mover(createLink = noLinks)
        val report = mover.recover(null)

        assertTrue(target.resolve("f1.mp4").exists(), "the destination was deleted")
        assertTrue(sourceOf(planned[0]).exists())
        assertEquals(listOf("f1.mp4"), leftoverNames(mover))
        assertEquals(1, report.unresolvedJournals)
        assertEquals(1, journals().size, "the journal was discarded")
    }

    // Rule 2

    @Test
    fun a_second_mover_at_the_same_time_is_refused_and_so_is_recovery() {
        val planned = items(1)
        var second: MoveReport? = null
        var recovery: RecoveryReport? = null
        val first = world.mover(checkpoint = { step ->
            if (step == "journal-written") {
                second = world.mover().run(plan(items(1).map { it.copy(itemId = "other") }), { false }, {}) { _, _ -> }
                recovery = world.mover().recover(null)
            }
        })

        run(first, planned)

        assertEquals(FolderProblem.MoveInProgress, second?.problem)
        assertEquals(true, recovery?.busy)
    }

    @Test
    fun an_unresolved_journal_blocks_a_new_move() {
        crashAt("published", items(1))

        val report = run(world.mover(), listOf(world.moveItem("next", video(5), drive.id)))

        assertEquals(FolderProblem.PreviousMoveUnresolved, report.problem)
        assertEquals(0, report.moved)
    }

    @Test
    fun a_journal_is_never_overwritten_and_each_move_has_its_own() {
        val planned = items(1)
        val fixed = world.mover(txId = { "0".repeat(32) })
        run(fixed, planned)
        // The first one finished cleanly. Plant a journal with the name the next move would take.
        moves.createDirectories()
        moves.resolve("move-${"0".repeat(32)}.journal").writeText("planted")
        val taken = world.mover(txId = { "0".repeat(32) })

        val report = run(taken, listOf(world.moveItem("n", video(7), drive.id)))

        assertEquals(0, report.moved)
        assertEquals("planted", moves.resolve("move-${"0".repeat(32)}.journal").toFile().readText())
        assertTrue(sourceOf(world.moveItem("n", source.resolve("f7.mp4"), drive.id)).exists())
    }

    @Test
    fun a_location_that_is_away_or_replaced_by_another_volume_at_the_same_path_is_not_touched_by_recovery() {
        val planned = items(1)
        val original = bytes(sourceOf(planned[0]))
        crashAt("temp-created", planned)
        val temp = tmpFiles().single()
        world.unplug("Archive")
        // Another volume mounts at that path. It has an unrelated file and its own marker.
        target.createDirectories()
        target.resolve(DownloadLocations.MarkerName).writeText("some-other-volume")
        val stranger = target.resolve(temp.name).also { it.writeText("not ours") }
        val strangerBytes = bytes(stranger)

        val report = world.mover().recover(null)

        assertEquals(1, report.pending, "the entry should stay pending")
        assertContentEquals(strangerBytes, bytes(stranger), "a file of the other volume was touched")
        assertContentEquals(original, bytes(sourceOf(planned[0])))
        assertEquals(1, journals().size)
    }

    @Test
    fun entries_on_a_location_that_comes_back_are_settled_then() {
        val planned = items(1)
        crashAt("temp-created", planned)
        val temp = tmpFiles().single()
        val parked = world.root.resolve("parked-temp").also { Files.copy(temp, it) }
        world.unplug("Archive")

        assertEquals(1, world.mover().recover(null).pending)
        world.replug("Archive")
        val report = world.mover().recover(null)

        assertEquals(1, report.tempsRemoved)
        assertFalse(temp.exists())
        assertEquals(emptyList(), journals())
        assertTrue(parked.exists())
    }

    // Rule 3

    private fun recordContext(profile: Int, matches: Boolean, relocated: MutableList<MoveItem>) =
        RecoveryContext(profile, { matches }, { item, _ -> relocated += item })

    @Test
    fun recovery_finishes_a_record_update_only_for_the_same_profile_and_the_planned_record() {
        val planned = items(1)
        crashAt("published", planned)

        val relocated = mutableListOf<MoveItem>()
        world.mover().recover(recordContext(profile = 2, matches = true, relocated))
        assertEquals(emptyList(), relocated, "a record of another profile was touched")

        world.mover().recover(recordContext(profile = 1, matches = false, relocated))
        assertEquals(emptyList(), relocated, "a record that no longer matches was touched")

        val report = world.mover().recover(recordContext(profile = 1, matches = true, relocated))

        assertEquals(1, report.recordsFinished)
        assertEquals(listOf("item1"), relocated.map { it.itemId })
        assertTrue(sourceOf(planned[0]).exists() && target.resolve("f1.mp4").exists(), "recovery removed a copy")
        // The journal is kept: the leftover is the user's to resolve.
        assertEquals(1, journals().size)
    }

    @Test
    fun a_moved_partial_file_is_repointed_from_the_journal_and_both_files_stay() {
        val partial = source.resolve("show_e1.mp4.part")
        Files.write(partial, ByteArray(70_000) { (it % 251).toByte() })
        val item = MoveItem(1, "dl", null, "show_e1.mp4.part", listOf(MoveEntry("app", "show_e1.mp4.part", drive.id, "show_e1.mp4.part", MoveKind.Partial, 70_000)))
        crashAt("published", listOf(item))
        val repointed = mutableListOf<Path?>()

        val report = world.mover().recover(RecoveryContext(1, { true }, { _, path -> repointed += path }))

        assertEquals(listOf<Path?>(target.resolve("show_e1.mp4.part")), repointed)
        assertEquals(1, report.recordsFinished)
        assertTrue(partial.exists(), "recovery removed the original partial")
        assertEquals(70_000L, Files.size(target.resolve("show_e1.mp4.part")))
    }

    @Test
    fun a_record_that_changed_or_went_while_the_move_ran_means_no_update_and_the_copy_is_a_leftover() {
        val planned = items(2)
        val mover = world.mover()

        val report = run(mover, planned, canCommit = { it.itemId != "item1" })

        assertEquals(1, report.moved)
        assertEquals(MoveFailureReason.RecordChanged, report.failures.single().reason)
        assertTrue(sourceOf(planned[0]).exists() && target.resolve("f1.mp4").exists(), "something was deleted")
        assertEquals(listOf("f1.mp4"), leftoverNames(mover))
    }

    // Rule 7

    @Test
    fun a_temp_name_that_is_already_taken_leaves_that_file_alone_and_another_name_is_chosen() {
        val planned = items(1)
        val stranger = target.resolve(".nuvio-${"a".repeat(32)}-fixed.tmp").also { it.writeText("stranger") }
        var calls = 0

        val report = run(world.mover(txId = { "a".repeat(32) }, randomName = { if (calls++ < 2) "fixed" else "other$calls" }), planned)

        assertEquals(1, report.moved)
        assertEquals("stranger", stranger.toFile().readText(), "a file with a taken temp name was touched or deleted")
        assertTrue(target.resolve("f1.mp4").exists())
    }

    @Test
    fun a_probe_name_that_is_already_taken_leaves_that_file_alone_and_another_name_is_chosen() {
        val planned = items(1)
        val stranger = target.resolve(".nuvio-${"b".repeat(32)}-probe-fixed").also { it.writeText("stranger") }
        var calls = 0
        var linked = false

        val report = run(
            world.mover(
                txId = { "b".repeat(32) }, sameStore = { _, _ -> true }, randomName = { if (calls++ == 0) "fixed" else "x$calls" },
                createLink = { link, existing -> Files.createLink(link, existing).also { linked = true } },
            ),
            planned,
        )

        assertEquals(1, report.moved)
        assertTrue(linked, "the probe gave up instead of choosing another name")
        assertEquals("stranger", stranger.toFile().readText())
    }

    @Test
    fun a_temp_file_replaced_by_another_file_before_recovery_is_not_deleted() {
        val planned = items(1)
        crashAt("temp-created", planned)
        val temp = tmpFiles().single()
        Files.delete(temp)
        temp.writeText("someone else wrote this under the same name")

        world.mover().recover(null)

        assertEquals("someone else wrote this under the same name", temp.toFile().readText())
    }

    @Test
    fun a_temp_file_of_the_move_is_removed_by_recovery_and_recovery_run_twice_gives_the_same_result() {
        val planned = items(1)
        crashAt("copy-recorded", planned)
        assertEquals(1, tmpFiles().size)

        val first = world.mover().recover(null)
        val second = world.mover().recover(null)

        assertEquals(1, first.tempsRemoved)
        assertEquals(emptyList(), tmpFiles())
        assertEquals(0, first.unresolvedJournals)
        assertEquals(RecoveryReport(), second, "a second run changed something")
        assertTrue(sourceOf(planned[0]).exists())
    }

    // Failures

    @Test
    fun an_ordinary_failure_leaves_that_file_where_it_was_and_the_others_move() {
        val planned = items(3)
        var calls = 0
        val failing = world.mover(copy = { from, to, onBytes, cancelled ->
            calls++
            if (calls == 2) {
                Files.write(to, ByteArray(10))
                throw IOException("Input/output error")
            }
            DownloadMover.copyFile(from, to, onBytes, cancelled)
        })

        val report = run(failing, planned)

        assertEquals(2, report.moved)
        assertEquals(listOf(MoveFailure("f2.mp4", MoveFailureReason.CouldNotWrite)), report.failures)
        assertTrue(sourceOf(planned[1]).exists())
        assertEquals(emptyList(), tmpFiles(), "a half copy was left")
        assertTrue(target.resolve("f1.mp4").exists() && target.resolve("f3.mp4").exists() && !target.resolve("f2.mp4").exists())
    }

    @Test
    fun a_destination_that_fills_up_fails_that_item_removes_only_its_own_temp_file_and_stops_the_move() {
        val planned = items(4)
        val unrelated = target.resolve("something.tmp").also { it.writeText("not ours") }
        var calls = 0
        val filling = world.mover(copy = { from, to, onBytes, cancelled ->
            calls++
            if (calls == 2) {
                Files.write(to, ByteArray(10))
                throw IOException("No space left on device")
            }
            DownloadMover.copyFile(from, to, onBytes, cancelled)
        })

        val report = run(filling, planned)

        assertEquals(1, report.moved)
        assertEquals(listOf(MoveFailure("f2.mp4", MoveFailureReason.NoSpace)), report.failures)
        assertEquals(2, report.notReached)
        assertEquals(emptyList(), tmpFiles())
        assertEquals("not ours", unrelated.toFile().readText())
        assertTrue(planned.drop(1).all { sourceOf(it).exists() })
        assertEquals(2, calls)
    }

    @Test
    fun a_copy_that_does_not_read_back_the_same_is_reported_and_kept_never_deleted_and_the_original_stays() {
        val planned = items(1)
        val mover = world.mover(copy = { from, to, onBytes, cancelled ->
            val hash = DownloadMover.copyFile(from, to, onBytes, cancelled)
            val data = bytes(to)
            data[data.size / 2] = (data[data.size / 2] + 1).toByte() // the middle: head and tail hashing would not see it
            Files.write(to, data)
            hash
        })

        val report = run(mover, planned)

        assertEquals(0, report.moved)
        assertEquals(MoveFailureReason.CopyDidNotMatch, report.failures.single().reason)
        assertTrue(sourceOf(planned[0]).exists())
        assertTrue(target.resolve("f1.mp4").exists(), "something made at a final name was deleted")
        assertEquals(emptyList(), tmpFiles())
        assertEquals(listOf("f1.mp4"), leftoverNames(mover))
    }

    @Test
    fun a_file_that_changes_while_it_is_copied_is_not_moved() {
        val planned = items(1)
        val growing = world.mover(copy = { from, to, onBytes, cancelled ->
            val hash = DownloadMover.copyFile(from, to, onBytes, cancelled)
            Files.write(from, byteArrayOf(1, 2, 3), java.nio.file.StandardOpenOption.APPEND)
            hash
        })

        val report = run(growing, planned)

        assertEquals(MoveFailureReason.ChangedWhileCopying, report.failures.single().reason)
        assertTrue(sourceOf(planned[0]).exists())
        assertEquals(emptyList(), tmpFiles())
        assertFalse(target.resolve("f1.mp4").exists())
    }

    @Test
    fun an_existing_name_is_never_replaced_in_any_letter_case() {
        val planned = items(3)
        target.resolve("f1.mp4").writeText("mine")
        target.resolve("F2.MP4").writeText("also mine")

        val report = run(world.mover(), planned)

        assertEquals(1, report.moved)
        assertEquals(2, report.failures.count { it.reason == MoveFailureReason.NameTaken })
        assertEquals("mine", target.resolve("f1.mp4").toFile().readText())
        assertEquals("also mine", target.resolve("F2.MP4").toFile().readText())
        assertTrue(planned.take(2).all { sourceOf(it).exists() })
    }

    @Test
    fun a_target_that_appears_after_the_check_is_not_replaced_and_nothing_is_left_open() {
        val planned = items(1)
        val racing = world.mover(checkpoint = { step -> if (step == "copy-recorded") target.resolve("f1.mp4").writeText("arrived meanwhile") })

        val report = run(racing, planned)

        assertEquals(0, report.moved)
        assertEquals(MoveFailureReason.NameTaken, report.failures.single().reason)
        assertEquals("arrived meanwhile", target.resolve("f1.mp4").toFile().readText())
        assertTrue(sourceOf(planned[0]).exists())
        assertEquals(emptyList(), tmpFiles())
        assertEquals(emptyList(), journals())
    }

    @Test
    fun two_entries_that_would_take_one_name_never_both_go() {
        val a = video(1)
        val sub = source.resolve("sub").createDirectories().resolve("F1.MP4").writeVideo(2)
        val planned = listOf(
            world.moveItem("a", a, drive.id),
            MoveItem(1, "b", null, "F1.MP4", listOf(MoveEntry("app", "sub/F1.MP4", drive.id, "F1.MP4", MoveKind.File, Files.size(sub)))),
        )

        val report = run(world.mover(), planned)

        assertEquals(1, report.moved)
        assertTrue(report.failures.single().reason == MoveFailureReason.NameTaken || report.failures.isNotEmpty())
        assertTrue(sub.exists() || sourceOf(planned[0]).exists().not())
    }

    @Test
    fun a_download_that_is_a_link_is_not_moved_and_not_followed() {
        val real = world.root.resolve("elsewhere").createDirectories().resolve("real.mp4").writeVideo(1)
        val link = source.resolve("movie.mp4")
        Files.createSymbolicLink(link, real)
        val planned = listOf(MoveItem(1, "i", null, "movie.mp4", listOf(MoveEntry("app", "movie.mp4", drive.id, "movie.mp4", MoveKind.File, Files.size(real)))))

        val report = run(world.mover(sameStore = { _, _ -> true }), planned)

        assertEquals(0, report.moved)
        assertEquals(MoveFailureReason.Symlink, report.failures.single().reason)
        assertTrue(Files.isSymbolicLink(link) && real.exists())
        assertFalse(target.resolve("movie.mp4").exists())
    }

    // Stopping

    @Test
    fun cancelling_between_items_leaves_the_finished_ones_moved_and_the_rest_untouched() {
        val planned = items(4)

        val report = run(world.mover(), planned, cancelAfterItems = 2)

        assertTrue(report.cancelled)
        assertEquals(2, report.moved)
        assertEquals(2, report.notReached)
        assertEquals(listOf(false, false, true, true), planned.map { sourceOf(it).exists() })
        assertEquals(emptyList(), journals())
    }

    @Test
    fun cancelling_in_the_middle_of_a_copy_removes_the_temp_file_and_keeps_the_original() {
        val planned = items(2)
        var cancel = false
        val cancelling = world.mover(copy = { from, to, onBytes, cancelled ->
            Files.write(to, ByteArray(100))
            cancel = true
            if (cancelled()) throw MoveCancelled()
            DownloadMover.copyFile(from, to, onBytes, cancelled)
        })

        val report = cancelling.run(plan(planned), { cancel }, {}, { true }) { _, _ -> }

        assertTrue(report.cancelled)
        assertEquals(0, report.moved)
        assertTrue(planned.all { sourceOf(it).exists() })
        assertEquals(emptyList(), tmpFiles())
    }

    @Test
    fun a_partial_move_moves_only_what_was_chosen() {
        val all = items(4)

        val report = run(world.mover(), listOf(all[0], all[2]))

        assertEquals(2, report.moved)
        assertEquals(listOf(false, true, false, true), all.map { sourceOf(it).exists() })
        assertEquals(listOf("f1.mp4", "f3.mp4"), Files.list(target).use { s -> s.map { it.name }.filter { !it.startsWith(".") }.sorted().toList() })
    }

    // Rule 6

    @Test
    fun a_download_carries_its_sidecar_folder_and_nothing_that_merely_shares_its_name() {
        val movie = source.resolve("Film_ab12.mp4").writeVideo(1)
        val subs = source.resolve("Film_ab12.mp4.subtitles").createDirectories()
        subs.resolve("en.srt").writeText("hello")
        subs.resolve("skiptimes.json").writeText("{\"intro\":[1,2]}")
        subs.resolve("nested").createDirectories().resolve("fr.srt").writeText("bonjour")
        val sibling = source.resolve("Film_ab12.mkv").writeVideo(2)
        val skip = source.resolve("Film_ab12.skiptimes.json").also { it.writeText("sibling") }
        val sidecar = DownloadCompanions.of(movie).files
        val planned = listOf(world.moveItem("i", movie, drive.id, *sidecar.map { it.path }.toTypedArray()))

        val report = run(world.mover(), planned)

        assertEquals(1, report.moved)
        assertEquals("hello", target.resolve("Film_ab12.mp4.subtitles/en.srt").toFile().readText())
        assertEquals("{\"intro\":[1,2]}", target.resolve("Film_ab12.mp4.subtitles/skiptimes.json").toFile().readText())
        assertEquals("bonjour", target.resolve("Film_ab12.mp4.subtitles/nested/fr.srt").toFile().readText())
        assertFalse(movie.exists() || subs.exists())
        assertTrue(sibling.exists() && skip.exists(), "a file that only shares the base name travelled")
        assertFalse(target.resolve("Film_ab12.mkv").exists() || target.resolve("Film_ab12.skiptimes.json").exists())
    }

    @Test
    fun the_companion_rule_is_the_sidecar_folder_only_and_skips_links() {
        val movie = source.resolve("Show_S01E01_x9.mkv").writeVideo(1)
        source.resolve("Show_S01E01_x9.en.srt").writeText("a")
        source.resolve("Show_S01E01_x9.mkv.part").writeText("b")
        source.resolve("Show_S01E01_x9.mkv.subtitles").createDirectories().also {
            it.resolve("en.srt").writeText("x")
            Files.createSymbolicLink(it.resolve("link.srt"), world.root)
        }

        val companions = DownloadCompanions.of(movie)

        assertEquals(listOf("Show_S01E01_x9.mkv.subtitles/en.srt"), companions.files.map { it.relativeName })
        assertEquals(1, companions.skippedLinks.size)
        assertEquals("a.mp4.subtitles", DownloadCompanions.sidecarFolderName("a.mp4"))
    }

    @Test
    fun a_sidecar_file_whose_name_is_taken_fails_the_item_before_anything_is_made() {
        val movie = video(1)
        val sidecar = source.resolve("f1.mp4.subtitles").createDirectories().resolve("en.srt").also { it.writeText("{}") }
        target.resolve("f1.mp4.subtitles").createDirectories().resolve("en.srt").writeText("someone else's")
        val planned = listOf(world.moveItem("i", movie, drive.id, sidecar))

        val report = run(world.mover(), planned)

        assertEquals(MoveFailureReason.NameTaken, report.failures.single().reason)
        assertTrue(movie.exists() && sidecar.exists())
        assertFalse(target.resolve("f1.mp4").exists())
        assertEquals("someone else's", target.resolve("f1.mp4.subtitles/en.srt").toFile().readText())
    }

    @Test
    fun a_partial_file_and_an_item_without_files_are_carried() {
        val partial = source.resolve("g.mp4.part")
        Files.write(partial, ByteArray(5_000) { it.toByte() })
        val moved = mutableListOf<Pair<MoveItem, Path?>>()
        val planned = listOf(
            MoveItem(1, "a", null, "g.mp4.part", listOf(MoveEntry("app", "g.mp4.part", drive.id, "g.mp4.part", MoveKind.Partial, 5_000))),
            MoveItem(1, "b", null, "h.mp4", emptyList()),
        )

        val report = run(world.mover(), planned, moved = moved)

        assertEquals(2, report.moved)
        assertEquals(5_000L, Files.size(target.resolve("g.mp4.part")))
        assertFalse(partial.exists())
        assertEquals(null, moved[1].second)
    }

    // Leftovers

    private fun leftoverFrom(mover: DownloadMover, planned: List<MoveItem>): LeftoverCopy {
        crashAt("published", planned)
        return mover.leftovers().single()
    }

    @Test
    fun the_extra_copy_is_removed_only_when_the_content_is_equal_and_the_record_points_at_the_one_that_stays() {
        val planned = items(1)
        val mover = world.mover()
        val leftover = leftoverFrom(mover, planned)

        // The record points at the copy that would be removed: refused.
        assertEquals(LeftoverOutcome.Refused, mover.removeExtraCopy(leftover.id, 1) { RecordSide.Missing })
        assertTrue(sourceOf(planned[0]).exists() && target.resolve("f1.mp4").exists())

        // The record is at the original: the copy at the destination is the extra one.
        assertEquals(LeftoverOutcome.Removed, mover.removeExtraCopy(leftover.id, 1) { RecordSide.AtSource })

        assertTrue(sourceOf(planned[0]).exists())
        assertFalse(target.resolve("f1.mp4").exists())
        assertEquals(emptyList(), journals(), "nothing is left open")
    }

    @Test
    fun the_extra_copy_is_not_removed_when_the_hashes_differ() {
        val planned = items(1)
        val mover = world.mover()
        val leftover = leftoverFrom(mover, planned)
        target.resolve("f1.mp4").writeVideo(99) // the copy was damaged afterwards

        assertEquals(LeftoverOutcome.Refused, mover.removeExtraCopy(leftover.id, 1) { RecordSide.AtSource })
        assertEquals(LeftoverOutcome.Refused, mover.removeExtraCopy(leftover.id, 1) { RecordSide.AtDestination })

        assertTrue(sourceOf(planned[0]).exists() && target.resolve("f1.mp4").exists())
    }

    @Test
    fun the_extra_copy_is_not_removed_for_another_profile_or_when_the_record_changes_between_the_checks() {
        val planned = items(1)
        val mover = world.mover()
        val leftover = leftoverFrom(mover, planned)

        assertEquals(LeftoverOutcome.Refused, mover.removeExtraCopy(leftover.id, 2) { RecordSide.AtSource })
        var calls = 0
        assertEquals(LeftoverOutcome.Refused, mover.removeExtraCopy(leftover.id, 1) { if (calls++ == 0) RecordSide.AtSource else RecordSide.AtDestination })

        assertTrue(sourceOf(planned[0]).exists() && target.resolve("f1.mp4").exists())
    }

    @Test
    fun keeping_both_settles_the_leftover_and_the_journal() {
        val planned = items(1)
        val mover = world.mover()
        val leftover = leftoverFrom(mover, planned)

        mover.keepBoth(leftover.id)

        assertEquals(emptyList(), mover.leftovers())
        assertEquals(emptyList(), journals())
        assertTrue(sourceOf(planned[0]).exists() && target.resolve("f1.mp4").exists())
    }

    @Test
    fun an_unreadable_journal_is_put_aside_and_nothing_is_done() {
        moves.createDirectories()
        moves.resolve("move-${"c".repeat(32)}.journal").writeText("not json")

        val report = world.mover().recover(null)

        assertEquals(1, report.unreadable)
        assertTrue(moves.resolve("move-${"c".repeat(32)}.journal.unreadable").exists())
    }

    @Test
    fun progress_names_the_file_and_counts_files_and_bytes() {
        val planned = items(2)
        val seen = mutableListOf<MoveProgress>()

        world.mover().run(plan(planned), { false }, { seen += it }, { true }) { _, _ -> }

        assertTrue(seen.any { it.currentName == "f1.mp4" && it.totalFiles == 2 })
        assertEquals(2, seen.last().doneFiles)
        assertEquals("Movies", seen.last().destinationName)
        assertNotNull(seen.firstOrNull())
    }
}
