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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Copy, verify, remove, and every way a move can stop. Temporary folders only. */
class DownloadMoverTest {
    private val world = FolderWorld()
    private val source = world.root.resolve("from").createDirectories()
    private val target = world.root.resolve("to").createDirectories()
    private val journal = world.root.resolve("journal.json")

    private class Crash(val at: String) : Error()

    @AfterTest
    fun clean_up() = world.close()

    private fun files(count: Int, prefix: String = "f"): List<Pair<Path, ByteArray>> = (1..count).map { n ->
        val path = source.resolve("$prefix$n.mp4").writeVideo(n)
        path to Files.readAllBytes(path)
    }

    private fun entries(files: List<Pair<Path, ByteArray>>) = files.mapIndexed { index, (path, bytes) ->
        MoveEntry("item$index", path.name, path.toString(), MoveKind.File, bytes.size.toLong())
    }

    private fun mover(
        crashAt: String? = null,
        crossVolume: Boolean = true,
        copy: ((Path, Path, (Long) -> Unit, () -> Boolean) -> Unit)? = null,
    ) = DownloadMover(
        journalFile = journal,
        checkpoint = { step -> if (step == crashAt) throw Crash(step) },
        copy = copy ?: DownloadMover::copyFile,
        sameStore = { _, _ -> !crossVolume },
    )

    private fun plan(files: List<Pair<Path, ByteArray>>) = MovePlan(target, "to-id", entries(files), pausedIds = listOf("item0"))

    private fun run(
        mover: DownloadMover,
        plan: MovePlan,
        cancelAfterFiles: Int? = null,
        moved: MutableList<Pair<MoveEntry, Path?>> = mutableListOf(),
    ): MoveReport {
        var done = 0
        return mover.run(
            plan = plan,
            isCancelled = { cancelAfterFiles != null && done >= cancelAfterFiles },
            onProgress = { done = it.doneFiles },
            onMoved = { entry, path -> moved += entry to path },
        )
    }

    @Test
    fun a_file_is_copied_checked_and_only_then_removed_from_the_old_place() {
        val files = files(2)
        val moved = mutableListOf<Pair<MoveEntry, Path?>>()

        val report = run(mover(), plan(files), moved = moved)

        assertEquals(2, report.moved)
        assertTrue(report.failures.isEmpty() && !report.cancelled)
        files.forEach { (path, bytes) ->
            assertFalse(path.exists(), "the original is still there")
            assertContentEquals(bytes, Files.readAllBytes(target.resolve(path.name)))
            assertFalse(target.resolve(path.name + ".moving").exists())
        }
        assertEquals(listOf(target.resolve("f1.mp4"), target.resolve("f2.mp4")), moved.map { it.second })
        assertFalse(journal.exists(), "the journal was left behind")
    }

    @Test
    fun the_originals_are_still_there_when_the_record_is_told_about_the_copy() {
        val files = files(1)
        var originalWasThere = false

        mover().run(plan(files), { false }, {}) { entry, path ->
            originalWasThere = java.nio.file.Paths.get(entry.from).exists() && path!!.exists()
        }

        assertTrue(originalWasThere)
    }

    @Test
    fun on_one_volume_a_move_is_a_rename() {
        val files = files(2)
        var copied = false

        val report = run(mover(crossVolume = false, copy = { _, _, _, _ -> copied = true }), plan(files))

        assertEquals(2, report.moved)
        assertFalse(copied, "bytes were copied for a rename")
        files.forEach { (path, bytes) ->
            assertFalse(path.exists())
            assertContentEquals(bytes, Files.readAllBytes(target.resolve(path.name)))
        }
    }

    @Test
    fun a_failure_halfway_leaves_that_file_where_it_was_and_the_others_move() {
        val files = files(3)
        var calls = 0
        val failing = mover(copy = { from, to, onBytes, cancelled ->
            calls++
            if (calls == 2) {
                Files.write(to, ByteArray(10)) // a half-written copy
                throw IOException("No space left on device")
            }
            DownloadMover.copyFile(from, to, onBytes, cancelled)
        })

        val report = run(failing, plan(files))

        assertEquals(2, report.moved)
        assertEquals(listOf(MoveFailure("f2.mp4", MoveFailureReason.NoSpace)), report.failures)
        assertTrue(files[1].first.exists(), "the file that failed was lost")
        assertContentEquals(files[1].second, Files.readAllBytes(files[1].first))
        assertFalse(target.resolve("f2.mp4").exists())
        assertFalse(target.resolve("f2.mp4.moving").exists(), "a half copy was left")
        assertTrue(target.resolve("f1.mp4").exists() && target.resolve("f3.mp4").exists())
    }

    @Test
    fun a_copy_that_does_not_match_is_refused_and_the_original_kept() {
        val files = files(1)
        val corrupting = mover(copy = { from, to, onBytes, cancelled ->
            DownloadMover.copyFile(from, to, onBytes, cancelled)
            val bytes = Files.readAllBytes(to)
            bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte() // the tail the hash reads
            Files.write(to, bytes)
        })

        val report = run(corrupting, plan(files))

        assertEquals(0, report.moved)
        assertEquals(MoveFailureReason.CopyDidNotMatch, report.failures.single().reason)
        assertContentEquals(files[0].second, Files.readAllBytes(files[0].first))
        assertFalse(target.resolve("f1.mp4").exists() || target.resolve("f1.mp4.moving").exists())
    }

    @Test
    fun a_file_that_changes_while_it_is_copied_is_not_moved() {
        val files = files(1)
        val growing = mover(copy = { from, to, onBytes, cancelled ->
            DownloadMover.copyFile(from, to, onBytes, cancelled)
            Files.write(from, byteArrayOf(1, 2, 3), java.nio.file.StandardOpenOption.APPEND)
        })

        val report = run(growing, plan(files))

        assertEquals(MoveFailureReason.ChangedWhileCopying, report.failures.single().reason)
        assertTrue(files[0].first.exists())
        assertFalse(target.resolve("f1.mp4.moving").exists())
    }

    @Test
    fun a_name_that_is_already_taken_is_not_overwritten() {
        val files = files(1)
        target.resolve("f1.mp4").writeText("something else")

        val report = run(mover(), plan(files))

        assertEquals(MoveFailureReason.NameTaken, report.failures.single().reason)
        assertEquals("something else", target.resolve("f1.mp4").toFile().readText())
        assertTrue(files[0].first.exists())
    }

    @Test
    fun cancelling_halfway_stops_after_the_current_file_and_leaves_the_rest_untouched() {
        val files = files(4)

        val report = run(mover(), plan(files), cancelAfterFiles = 2)

        assertTrue(report.cancelled)
        assertEquals(2, report.moved)
        assertEquals(2, report.notReached)
        assertEquals(listOf(false, false, true, true), files.map { it.first.exists() })
        assertEquals(listOf(true, true, false, false), files.map { target.resolve(it.first.name).exists() })
        assertFalse(journal.exists())
    }

    @Test
    fun cancelling_in_the_middle_of_a_copy_removes_the_half_copy_and_keeps_the_original() {
        val files = files(2)
        var cancel = false
        val cancelling = mover(copy = { from, to, onBytes, cancelled ->
            Files.write(to, ByteArray(100))
            cancel = true
            if (cancelled()) throw MoveCancelled()
            DownloadMover.copyFile(from, to, onBytes, cancelled)
        })

        val report = cancelling.run(plan(files), { cancel }, {}) { _, _ -> }

        assertTrue(report.cancelled)
        assertEquals(0, report.moved)
        assertTrue(files.all { it.first.exists() })
        assertFalse(target.resolve("f1.mp4.moving").exists())
    }

    @Test
    fun a_partial_move_moves_only_what_was_chosen() {
        val files = files(4)
        val chosen = files.filterIndexed { index, _ -> index % 2 == 0 }

        val report = run(mover(), plan(chosen))

        assertEquals(2, report.moved)
        assertEquals(listOf(false, true, false, true), files.map { it.first.exists() })
        assertEquals(listOf("f1.mp4", "f3.mp4"), Files.list(target).use { s -> s.map { it.name }.sorted().toList() })
    }

    @Test
    fun subtitles_beside_the_video_follow_it_and_the_old_folder_goes_only_once_they_are_copied() {
        val files = files(1)
        val subtitles = source.resolve("f1.mp4.subtitles").createDirectories()
        subtitles.resolve("en.srt").writeText("hello")

        run(mover(), plan(files))

        assertEquals("hello", target.resolve("f1.mp4.subtitles/en.srt").toFile().readText())
        assertFalse(subtitles.exists())
    }

    @Test
    fun a_partial_file_and_a_record_only_entry_are_carried() {
        val partial = source.resolve("g.mp4.part")
        Files.write(partial, ByteArray(5_000) { it.toByte() })
        val moved = mutableListOf<Pair<MoveEntry, Path?>>()
        val entries = listOf(
            MoveEntry("a", "g.mp4.part", partial.toString(), MoveKind.Partial, 5_000),
            MoveEntry("b", "h.mp4", "", MoveKind.RecordOnly, 0),
        )

        val report = run(mover(), MovePlan(target, "to-id", entries), moved = moved)

        assertEquals(2, report.moved)
        assertEquals(5_000L, Files.size(target.resolve("g.mp4.part")))
        assertFalse(partial.exists())
        assertNull(moved[1].second)
    }

    // Recovery: a crash at each step, then a start that settles it.

    private fun crashAt(step: String, crossVolume: Boolean = true, count: Int = 3): List<Pair<Path, ByteArray>> {
        val files = files(count)
        var crashed = false
        try {
            run(mover(crashAt = step, crossVolume = crossVolume), plan(files))
        } catch (error: Crash) {
            crashed = true
        }
        assertTrue(crashed, "no crash at $step")
        assertTrue(journal.exists(), "the journal must be there after a crash")
        return files
    }

    private fun assertNothingLost(files: List<Pair<Path, ByteArray>>) {
        files.forEach { (path, bytes) ->
            val places = listOf(path, target.resolve(path.name)).filter { it.exists() }
            assertTrue(places.isNotEmpty(), "${path.name} exists nowhere")
            places.forEach { assertContentEquals(bytes, Files.readAllBytes(it), "${path.name} is damaged") }
        }
        assertEquals(emptyList(), Files.list(target).use { s -> s.filter { it.name.endsWith(".moving") }.toList() })
    }

    @Test
    fun a_crash_before_anything_was_copied_rolls_back() {
        val files = crashAt("journal-written")

        val report = assertNotNull(mover().recover())

        assertEquals(RecoveryReport(finished = 0, rolledBack = 3, conflicts = 0, pausedIds = listOf("item0")), report)
        assertNothingLost(files)
        assertTrue(files.all { it.first.exists() })
        assertFalse(journal.exists())
    }

    @Test
    fun a_crash_with_a_half_made_copy_removes_the_copy_and_keeps_the_original() {
        val files = crashAt("copied")

        // The copy was whole but not yet checked: either way the original stays until a checked copy stands beside it.
        val report = assertNotNull(mover().recover())

        assertNothingLost(files)
        assertTrue(files[0].first.exists() || target.resolve("f1.mp4").exists())
        assertEquals(0, report.conflicts)
        assertFalse(target.resolve("f1.mp4.moving").exists())
    }

    @Test
    fun a_crash_after_the_copy_was_checked_but_before_it_was_renamed_rolls_back_the_entry() {
        val files = crashAt("verified")

        mover().recover()

        assertNothingLost(files)
        assertTrue(files[0].first.exists())
        assertFalse(target.resolve("f1.mp4").exists())
    }

    @Test
    fun a_crash_after_the_copy_was_renamed_finishes_it_by_removing_the_original() {
        val files = crashAt("renamed")

        val report = assertNotNull(mover().recover())

        assertNothingLost(files)
        assertFalse(files[0].first.exists(), "the original should be removed beside a checked copy")
        assertTrue(target.resolve("f1.mp4").exists())
        assertEquals(1, report.finished)
        assertEquals(2, report.rolledBack)
    }

    @Test
    fun a_crash_after_the_record_was_updated_also_finishes_it() {
        val files = crashAt("record-updated")

        mover().recover()

        assertNothingLost(files)
        assertFalse(files[0].first.exists())
        assertTrue(target.resolve("f1.mp4").exists())
    }

    @Test
    fun a_crash_after_the_original_was_removed_is_already_finished() {
        val files = crashAt("original-removed")

        val report = assertNotNull(mover().recover())

        assertNothingLost(files)
        assertFalse(files[0].first.exists())
        assertContentEquals(files[0].second, Files.readAllBytes(target.resolve("f1.mp4")))
        assertEquals(1, report.finished)
    }

    @Test
    fun a_crash_after_a_rename_on_one_volume_is_finished_too() {
        val files = crashAt("renamed", crossVolume = false)

        mover(crossVolume = false).recover()

        assertNothingLost(files)
        assertFalse(files[0].first.exists())
        assertTrue(target.resolve("f1.mp4").exists())
    }

    @Test
    fun a_copy_that_is_not_whole_is_never_trusted_to_replace_the_original() {
        val files = crashAt("copied", count = 1)
        // Damage the finished copy as a failing disk might.
        Files.write(target.resolve("f1.mp4"), ByteArray(10))

        val report = assertNotNull(mover().recover())

        assertContentEquals(files[0].second, Files.readAllBytes(files[0].first))
        assertEquals(0, report.finished)
    }

    @Test
    fun an_interrupted_partial_move_recovers_what_was_done_and_leaves_the_rest() {
        val files = files(4)
        var count = 0
        var crashed = false
        try {
            mover().run(
                plan = plan(files),
                isCancelled = { false },
                onProgress = {},
                onMoved = { _, _ -> if (++count == 2) throw Crash("record") },
            )
        } catch (_: Crash) {
            crashed = true
        }
        assertTrue(crashed)

        val report = assertNotNull(mover().recover())

        assertNothingLost(files)
        // File 1 was done, file 2 had its copy standing beside the original, files 3 and 4 were not reached.
        assertEquals(listOf(false, false, true, true), files.map { it.first.exists() })
        assertEquals(listOf(true, true, false, false), files.map { target.resolve(it.first.name).exists() })
        assertEquals(2, report.finished)
        assertEquals(2, report.rolledBack)
    }

    @Test
    fun nothing_to_recover_when_there_is_no_journal_and_an_unreadable_journal_is_put_aside() {
        assertNull(mover().recover())

        journal.writeText("not json")

        assertNull(mover().recover())
        assertFalse(journal.exists())
        assertTrue(world.root.resolve("journal.json.unreadable").exists())
    }

    @Test
    fun the_journal_is_written_whole_before_the_first_byte_moves() {
        val files = files(1)
        var seen: String? = null
        val watching = DownloadMover(
            journalFile = journal,
            checkpoint = { step -> if (step == "journal-written") seen = journal.toFile().readText() },
            sameStore = { _, _ -> false },
        )

        watching.run(plan(files), { false }, {}) { _, _ -> }

        val text = assertNotNull(seen)
        assertTrue(text.contains("f1.mp4") && text.contains("to-id") && text.contains("item0"))
        assertFalse(journal.resolveSibling("journal.json.tmp").exists())
    }
}
