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

/** The transaction: copy, hash, publish without replacing, read back, record, only then remove. Temporary folders only. */
class DownloadMoverTest {
    private val world = FolderWorld()
    private val source = world.root.resolve("from").createDirectories()
    private val target = world.root.resolve("to").createDirectories()
    private val journal = world.root.resolve("journal.json")

    private class Crash(val at: String) : Error()

    @AfterTest
    fun clean_up() = world.close()

    private fun video(n: Int, folder: Path = source): Path = folder.resolve("f$n.mp4").writeVideo(n)

    private fun item(id: String, video: Path, vararg companions: Pair<Path, String>): MoveItem = MoveItem(
        id,
        listOf(MoveEntry(video.name, video.toString(), MoveKind.File, Files.size(video))) +
            companions.map { (path, name) -> MoveEntry(name, path.toString(), MoveKind.Companion, Files.size(path)) },
    )

    private fun items(count: Int) = (1..count).map { item("item$it", video(it)) }

    private fun mover(
        crashAt: String? = null,
        copyPath: Boolean = true,
        copy: ((Path, Path, (Long) -> Unit, () -> Boolean) -> String)? = null,
        dirSync: (Path) -> Boolean = { true },
        deleteFile: (Path) -> Unit = { Files.delete(it) },
        checkpoint: (String) -> Unit = {},
        hashOf: (Path) -> String = DownloadMover::sha256Of,
    ) = DownloadMover(
        journalFile = journal,
        checkpoint = { step ->
            checkpoint(step)
            if (step == crashAt) throw Crash(step)
        },
        copy = copy ?: DownloadMover::copyFile,
        hashOf = hashOf,
        sameStore = { _, _ -> !copyPath },
        syncDirectory = dirSync,
        deleteFile = deleteFile,
    )

    private fun run(
        mover: DownloadMover,
        items: List<MoveItem>,
        cancelAfterItems: Int? = null,
        canCommit: (MoveItem) -> Boolean = { true },
        moved: MutableList<Pair<MoveItem, Path?>> = mutableListOf(),
    ): MoveReport {
        var done = 0
        return mover.run(
            plan = MovePlan(target, "to-id", items, pausedIds = listOf("running")),
            isCancelled = { cancelAfterItems != null && done >= cancelAfterItems },
            onProgress = { done = it.doneFiles },
            canCommit = canCommit,
            onMoved = { item, path -> moved += item to path },
        )
    }

    private fun bytes(path: Path) = Files.readAllBytes(path)

    private fun leftovers() = Files.list(target).use { s -> s.filter { it.name.startsWith(".nuvio-") }.toList() }

    // The ordinary move

    @Test
    fun a_file_is_copied_hashed_published_read_back_and_only_then_removed() {
        val planned = items(2)
        val originals = planned.map { bytes(Path.of(it.entries[0].from)) }
        val moved = mutableListOf<Pair<MoveItem, Path?>>()
        var originalWasThere: Boolean? = null

        val report = run(mover(checkpoint = { step -> if (step == "record-updated" && originalWasThere == null) originalWasThere = Path.of(planned[0].entries[0].from).exists() }), planned, moved = moved)

        assertEquals(2, report.moved)
        assertTrue(report.failures.isEmpty() && !report.cancelled && report.originalsKept.isEmpty())
        assertEquals(true, originalWasThere, "the original was removed before the record was saved")
        planned.forEachIndexed { i, it ->
            val name = it.entries[0].name
            assertFalse(Path.of(it.entries[0].from).exists())
            assertContentEquals(originals[i], bytes(target.resolve(name)))
            assertFalse(Files.isSameFile(target.resolve(name), target.resolve(name)).not())
        }
        assertEquals(listOf(target.resolve("f1.mp4"), target.resolve("f2.mp4")), moved.map { it.second })
        assertEquals(emptyList(), leftovers())
        assertFalse(journal.exists(), "the journal was left behind")
    }

    @Test
    fun on_one_volume_a_file_is_linked_not_copied_and_the_original_goes_after_the_record() {
        val planned = items(2)
        var copied = false

        val report = run(mover(copyPath = false, copy = { _, _, _, _ -> copied = true; "" }), planned)

        assertEquals(2, report.moved)
        assertFalse(copied, "bytes were copied for a move inside one volume")
        planned.forEach {
            assertFalse(Path.of(it.entries[0].from).exists())
            assertTrue(target.resolve(it.entries[0].name).exists())
        }
    }

    @Test
    fun the_journal_is_flushed_with_every_step_before_the_next_one_starts() {
        val planned = items(1)
        val seen = mutableMapOf<String, String>()
        val flushed = mutableListOf<Path>()

        run(mover(dirSync = { flushed.add(it); true }, checkpoint = { step ->
            if (journal.exists()) seen[step] = journal.toFile().readText()
        }), planned)

        assertTrue(seen.getValue("journal-written").contains("\"temp\":\".nuvio-"), "the temporary name is reserved up front")
        assertTrue(seen.getValue("copying").contains("\"state\":\"Copying\""))
        val copied = seen.getValue("copy-recorded")
        assertTrue(copied.contains("\"state\":\"Copied\"") && Regex("\"hash\":\"[0-9a-f]{64}\"").containsMatchIn(copied), "the hash of the whole file is journalled")
        assertTrue(seen.getValue("published").contains("\"state\":\"Published\""))
        assertTrue(seen.getValue("record-updating").contains("\"record\":\"Updating\""))
        assertTrue(seen.getValue("original-removed").contains("\"state\":\"Removed\""))
        assertTrue(target in flushed && source in flushed && journal.parent in flushed, "folders were not flushed")
    }

    @Test
    fun the_destination_folder_is_flushed_before_the_original_is_deleted() {
        val planned = items(1)
        val events = mutableListOf<String>()

        run(mover(dirSync = { events += "sync ${it.name}"; true }, deleteFile = { events += "delete ${it.name}"; Files.delete(it) }), planned)

        assertTrue(events.indexOf("sync to") in 0 until events.indexOf("delete f1.mp4"), events.toString())
        assertTrue(events.indexOf("delete f1.mp4") < events.lastIndexOf("sync from"), events.toString())
    }

    @Test
    fun where_folders_cannot_be_flushed_the_original_stays_and_the_next_start_confirms_and_removes_it() {
        val planned = items(1)
        val original = Path.of(planned[0].entries[0].from)

        val report = run(mover(dirSync = { false }), planned)

        assertEquals(1, report.moved)
        assertTrue(original.exists(), "the original was removed before the destination was durable")
        assertTrue(journal.exists())

        val recovery = assertNotNull(mover().recover())

        assertEquals(1, recovery.finished)
        assertFalse(original.exists())
        assertTrue(target.resolve("f1.mp4").exists())
        assertFalse(journal.exists())
    }

    // Failures

    @Test
    fun an_ordinary_failure_leaves_that_file_where_it_was_and_the_others_move() {
        val planned = items(3)
        var calls = 0
        val failing = mover(copy = { from, to, onBytes, cancelled ->
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
        assertTrue(Path.of(planned[1].entries[0].from).exists())
        assertEquals(emptyList(), leftovers(), "a half copy was left")
        assertTrue(target.resolve("f1.mp4").exists() && target.resolve("f3.mp4").exists() && !target.resolve("f2.mp4").exists())
    }

    @Test
    fun a_destination_that_fills_up_fails_that_item_removes_only_its_own_temp_file_and_stops_the_move() {
        val planned = items(4)
        val unrelated = target.resolve("something.tmp").also { it.writeText("not ours") }
        var calls = 0
        val filling = mover(copy = { from, to, onBytes, cancelled ->
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
        assertEquals(2, report.notReached, "the move did not stop")
        assertEquals(emptyList(), leftovers())
        assertEquals("not ours", unrelated.toFile().readText(), "a file the move did not make was touched")
        assertTrue(planned.drop(1).all { Path.of(it.entries[0].from).exists() })
        assertEquals(2, calls)
    }

    @Test
    fun a_copy_that_does_not_read_back_the_same_is_refused_removed_and_the_original_kept() {
        val planned = items(1)
        val corrupting = mover(copy = { from, to, onBytes, cancelled ->
            val hash = DownloadMover.copyFile(from, to, onBytes, cancelled)
            val data = bytes(to)
            data[data.size / 2] = (data[data.size / 2] + 1).toByte() // the middle: head and tail hashing would not see it
            Files.write(to, data)
            hash
        })

        val report = run(corrupting, planned)

        assertEquals(0, report.moved)
        assertEquals(MoveFailureReason.CopyDidNotMatch, report.failures.single().reason)
        assertTrue(Path.of(planned[0].entries[0].from).exists())
        assertFalse(target.resolve("f1.mp4").exists())
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun a_file_that_changes_while_it_is_copied_is_not_moved() {
        val planned = items(1)
        val growing = mover(copy = { from, to, onBytes, cancelled ->
            val hash = DownloadMover.copyFile(from, to, onBytes, cancelled)
            Files.write(from, byteArrayOf(1, 2, 3), java.nio.file.StandardOpenOption.APPEND)
            hash
        })

        val report = run(growing, planned)

        assertEquals(MoveFailureReason.ChangedWhileCopying, report.failures.single().reason)
        assertTrue(Path.of(planned[0].entries[0].from).exists())
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun a_record_that_changed_or_went_while_the_move_ran_stops_that_item_and_removes_the_copy() {
        val planned = items(2)

        val report = run(mover(), planned, canCommit = { it.itemId != "item1" })

        assertEquals(1, report.moved)
        assertEquals(MoveFailureReason.RecordChanged, report.failures.single().reason)
        assertTrue(Path.of(planned[0].entries[0].from).exists())
        assertFalse(target.resolve("f1.mp4").exists(), "the copy of an item that changed was left")
        assertTrue(target.resolve("f2.mp4").exists())
    }

    @Test
    fun an_existing_name_is_never_replaced_in_any_letter_case() {
        val planned = items(3)
        target.resolve("f1.mp4").writeText("mine")
        target.resolve("F2.MP4").writeText("also mine")

        val report = run(mover(), planned)

        assertEquals(1, report.moved)
        assertEquals(2, report.failures.count { it.reason == MoveFailureReason.NameTaken })
        assertEquals("mine", target.resolve("f1.mp4").toFile().readText())
        assertEquals("also mine", target.resolve("F2.MP4").toFile().readText())
        assertTrue(planned.take(2).all { Path.of(it.entries[0].from).exists() })
    }

    @Test
    fun a_target_that_appears_after_the_check_is_not_replaced() {
        val planned = items(1)
        // The race: the name is free when the item is looked at and taken by the time it is published.
        val racing = mover(checkpoint = { step -> if (step == "copy-recorded") target.resolve("f1.mp4").writeText("arrived meanwhile") })

        val report = run(racing, planned)

        assertEquals(0, report.moved)
        assertEquals(MoveFailureReason.NameTaken, report.failures.single().reason)
        assertEquals("arrived meanwhile", target.resolve("f1.mp4").toFile().readText())
        assertTrue(Path.of(planned[0].entries[0].from).exists())
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun two_entries_that_would_take_one_name_do_not_both_go() {
        val a = video(1)
        val b = video(2, source.resolve("other").createDirectories()).let { it.resolveSibling("F1.MP4").also { n -> Files.move(it, n) } }
        val planned = listOf(item("a", a), item("b", b))

        val report = run(mover(), planned)

        assertEquals(1, report.moved)
        assertEquals(MoveFailureReason.NameTaken, report.failures.single().reason)
        assertTrue(b.exists())
    }

    @Test
    fun a_download_that_is_a_link_is_not_moved_and_not_followed() {
        val real = world.root.resolve("elsewhere").createDirectories().resolve("real.mp4").writeVideo(1)
        val link = source.resolve("movie.mp4")
        Files.createSymbolicLink(link, real)
        val planned = listOf(MoveItem("i", listOf(MoveEntry("movie.mp4", link.toString(), MoveKind.File, Files.size(real)))))

        val report = run(mover(copyPath = false), planned)

        assertEquals(0, report.moved)
        assertEquals(MoveFailureReason.Symlink, report.failures.single().reason)
        assertTrue(Files.isSymbolicLink(link) && real.exists())
        assertFalse(target.resolve("movie.mp4").exists())
    }

    // Stopping

    @Test
    fun cancelling_between_items_leaves_the_finished_ones_moved_and_the_rest_untouched() {
        val planned = items(4)

        val report = run(mover(), planned, cancelAfterItems = 2)

        assertTrue(report.cancelled)
        assertEquals(2, report.moved)
        assertEquals(2, report.notReached)
        assertEquals(listOf(false, false, true, true), planned.map { Path.of(it.entries[0].from).exists() })
        assertFalse(journal.exists())
    }

    @Test
    fun cancelling_in_the_middle_of_a_copy_removes_the_temp_file_and_keeps_the_original() {
        val planned = items(2)
        var cancel = false
        val cancelling = mover(copy = { from, to, onBytes, cancelled ->
            Files.write(to, ByteArray(100))
            cancel = true
            if (cancelled()) throw MoveCancelled()
            DownloadMover.copyFile(from, to, onBytes, cancelled)
        })

        val report = cancelling.run(MovePlan(target, "t", planned), { cancel }, {}, { true }) { _, _ -> }

        assertTrue(report.cancelled)
        assertEquals(0, report.moved)
        assertTrue(planned.all { Path.of(it.entries[0].from).exists() })
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun a_partial_move_moves_only_what_was_chosen() {
        val all = items(4)

        val report = run(mover(), listOf(all[0], all[2]))

        assertEquals(2, report.moved)
        assertEquals(listOf(false, true, false, true), all.map { Path.of(it.entries[0].from).exists() })
        assertEquals(listOf("f1.mp4", "f3.mp4"), Files.list(target).use { s -> s.map { it.name }.sorted().toList() })
    }

    // Companions

    @Test
    fun a_download_carries_every_file_that_shares_its_name_and_its_sidecar_folder() {
        val movie = source.resolve("Film_ab12.mp4").writeVideo(1)
        val subs = source.resolve("Film_ab12.mp4.subtitles").createDirectories()
        subs.resolve("en.srt").writeText("hello")
        subs.createDirectories().resolve("nested").createDirectories().resolve("fr.srt").writeText("bonjour")
        val skip = source.resolve("Film_ab12.skiptimes.json").also { it.writeText("{\"intro\":[1,2]}") }
        val unrelated = source.resolve("Other_cd34.mp4").writeVideo(2)
        val companions = DownloadCompanions.of(movie)
        val planned = listOf(MoveItem("i", listOf(MoveEntry(movie.name, movie.toString(), MoveKind.File, Files.size(movie))) +
            companions.files.map { MoveEntry(it.relativeName, it.path.toString(), MoveKind.Companion, Files.size(it.path)) }))

        val report = run(mover(), planned)

        assertEquals(1, report.moved)
        assertEquals("hello", target.resolve("Film_ab12.mp4.subtitles/en.srt").toFile().readText())
        assertEquals("bonjour", target.resolve("Film_ab12.mp4.subtitles/nested/fr.srt").toFile().readText())
        assertEquals("{\"intro\":[1,2]}", target.resolve("Film_ab12.skiptimes.json").toFile().readText())
        assertFalse(movie.exists() || skip.exists() || subs.exists())
        assertTrue(unrelated.exists(), "a file with another name travelled")
    }

    @Test
    fun the_companion_rule_is_one_naming_rule_that_skips_links_and_files_being_written() {
        val movie = source.resolve("Show_S01E01_x9.mkv").writeVideo(1)
        source.resolve("Show_S01E01_x9.en.srt").writeText("a")
        source.resolve("Show_S01E01_x9.mkv.part").writeText("b")
        source.resolve("Show_S01E01_x9.mkv.moving").writeText("c")
        source.resolve(".nuvio-1-0.tmp").writeText("d")
        source.resolve("Show_S01E02_y1.en.srt").writeText("e")
        Files.createSymbolicLink(source.resolve("Show_S01E01_x9.link"), world.root)

        val companions = DownloadCompanions.of(movie)

        assertEquals(listOf("Show_S01E01_x9.en.srt"), companions.files.map { it.relativeName })
        assertEquals(1, companions.skippedLinks.size)
        assertTrue(DownloadCompanions.isCompanionName("a.b.mp4", "a.b.skip.json"))
        assertFalse(DownloadCompanions.isCompanionName("a.mp4", "ab.json"))
        assertFalse(DownloadCompanions.isCompanionName("a.mp4", "a.mp4"))
    }

    @Test
    fun a_companion_that_cannot_be_published_rolls_the_whole_item_back_and_no_original_is_removed() {
        val movie = video(1)
        val sidecar = source.resolve("f1.skiptimes.json").also { it.writeText("{}") }
        target.resolve("f1.skiptimes.json").writeText("someone else's")
        val planned = listOf(item("i", movie, sidecar to "f1.skiptimes.json"))

        val report = run(mover(), planned)

        assertEquals(0, report.moved)
        assertEquals(MoveFailureReason.NameTaken, report.failures.single().reason)
        assertTrue(movie.exists() && sidecar.exists())
        assertFalse(target.resolve("f1.mp4").exists())
        assertEquals("someone else's", target.resolve("f1.skiptimes.json").toFile().readText())
    }

    @Test
    fun a_partial_file_and_a_record_only_item_are_carried() {
        val partial = source.resolve("g.mp4.part")
        Files.write(partial, ByteArray(5_000) { it.toByte() })
        val moved = mutableListOf<Pair<MoveItem, Path?>>()
        val planned = listOf(
            MoveItem("a", listOf(MoveEntry("g.mp4.part", partial.toString(), MoveKind.Partial, 5_000))),
            MoveItem("b", listOf(MoveEntry("h.mp4", "", MoveKind.RecordOnly))),
        )

        val report = run(mover(), planned, moved = moved)

        assertEquals(2, report.moved)
        assertEquals(5_000L, Files.size(target.resolve("g.mp4.part")))
        assertFalse(partial.exists())
        assertNull(moved[1].second)
    }

    // When the original cannot be removed

    @Test
    fun an_original_that_cannot_be_removed_after_a_verified_recorded_copy_counts_as_moved_and_stays_in_the_journal() {
        val planned = items(2)
        var failFor: String? = "f1.mp4"
        val locked = mover(deleteFile = { if (it.name == failFor) throw IOException("Operation not permitted") else Files.delete(it) })

        val report = run(locked, planned)

        assertEquals(2, report.moved)
        assertTrue(report.failures.isEmpty(), "reported as a failed move")
        assertEquals(listOf(MoveFailure("f1.mp4", MoveFailureReason.OriginalNotRemoved, "Operation not permitted")), report.originalsKept)
        assertTrue(Path.of(planned[0].entries[0].from).exists() && target.resolve("f1.mp4").exists())
        assertTrue(journal.exists(), "the journal entry was dropped")

        failFor = "f1.mp4"
        val stillLocked = assertNotNull(mover(deleteFile = { throw IOException("still locked") }).recover())
        assertEquals(1, stillLocked.originalsKept)
        assertTrue(journal.exists())

        val retried = assertNotNull(mover().recover())
        assertEquals(1, retried.finished)
        assertFalse(Path.of(planned[0].entries[0].from).exists())
        assertFalse(journal.exists())
    }

    // Crashes and recovery

    private var lastOriginals: Map<String, ByteArray> = emptyMap()

    private fun crashAt(step: String, count: Int = 2, copyPath: Boolean = true): List<MoveItem> {
        val planned = items(count)
        lastOriginals = originalsOf(planned)
        var crashed = false
        try {
            run(mover(crashAt = step, copyPath = copyPath), planned)
        } catch (_: Crash) {
            crashed = true
        }
        assertTrue(crashed, "no crash at $step")
        assertTrue(journal.exists(), "the journal must be there after a crash")
        return planned
    }

    private fun assertNothingLost(planned: List<MoveItem>, originals: Map<String, ByteArray>) {
        planned.forEach { item ->
            val e = item.entries[0]
            val places = listOf(Path.of(e.from), target.resolve(e.name)).filter { it.exists() }
            assertTrue(places.isNotEmpty(), "${e.name} exists nowhere")
            places.forEach { assertContentEquals(originals.getValue(e.name), bytes(it), "${e.name} is damaged") }
        }
    }

    private fun originalsOf(planned: List<MoveItem>) = planned.associate { it.entries[0].name to bytes(Path.of(it.entries[0].from)) }

    @Test
    fun a_crash_before_the_copy_is_published_is_rolled_back_with_every_file_where_it_was() {
        for (step in listOf("journal-written", "copying", "copied", "copy-recorded", "linked")) {
            val planned = crashAt(step)
            val originals = originalsOf(planned)

            val report = assertNotNull(mover().recover(), step)

            assertNothingLost(planned, originals)
            assertTrue(planned.all { Path.of(it.entries[0].from).exists() }, "$step: an original was removed")
            assertFalse(target.resolve("f1.mp4").exists(), "$step: a file was left at the final name")
            assertEquals(emptyList(), leftovers(), step)
            assertEquals(0, report.finished, step)
            assertFalse(journal.exists(), step)
            // Reset for the next step.
            planned.forEach { Files.deleteIfExists(Path.of(it.entries[0].from)) }
        }
    }

    @Test
    fun a_crash_after_publishing_but_before_the_record_was_touched_removes_only_the_copy_it_proved_it_made() {
        val planned = crashAt("published")
        val originals = originalsOf(planned)

        val report = assertNotNull(mover().recover())

        assertNothingLost(planned, originals)
        assertTrue(planned.all { Path.of(it.entries[0].from).exists() })
        assertFalse(target.resolve("f1.mp4").exists())
        assertEquals(1, report.rolledBack, "only the item that had started has anything to roll back")
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun a_crash_after_the_original_was_removed_is_finished_and_the_journal_goes() {
        val planned = crashAt("original-removed")
        val originals = lastOriginals

        val report = assertNotNull(mover().recover())

        assertNothingLost(planned, originals)
        assertFalse(Path.of(planned[0].entries[0].from).exists())
        assertContentEquals(originals.getValue("f1.mp4"), bytes(target.resolve("f1.mp4")))
        assertFalse(journal.exists())
        assertEquals(0, report.originalsKept)
    }

    @Test
    fun a_crash_while_the_record_was_changing_is_left_alone_until_the_record_says_which_side_it_is_on() {
        for (step in listOf("record-updating", "record-updated")) {
            val planned = crashAt(step, count = 1)
            val originals = originalsOf(planned)

            val first = assertNotNull(mover().recover(), step)

            assertEquals(listOf("item1"), first.unresolved, step)
            assertTrue(Path.of(planned[0].entries[0].from).exists() && target.resolve("f1.mp4").exists(), "$step: something was removed without proof")
            assertTrue(journal.exists())

            val repointed = mutableListOf<Triple<String, String, Path?>>()
            val settled = assertNotNull(
                mover().recover(recordSide = { _, _, _ -> RecordSide.AtSource }, repoint = { item, id, path -> repointed += Triple(item.itemId, id, path) }),
                step,
            )

            assertEquals(1, settled.finished, step)
            assertEquals(listOf(Triple("item1", "to-id", target.resolve("f1.mp4"))), repointed, "$step: the record was not pointed from the journal")
            assertNothingLost(planned, originals)
            assertFalse(Path.of(planned[0].entries[0].from).exists(), step)
            assertFalse(journal.exists(), step)
            Files.deleteIfExists(target.resolve("f1.mp4"))
        }
    }

    @Test
    fun a_record_already_at_the_new_place_is_not_repointed_and_a_missing_record_leaves_everything() {
        val planned = crashAt("record-updating", count = 1)
        var repointed = false

        val missing = assertNotNull(mover().recover(recordSide = { _, _, _ -> RecordSide.Missing }, repoint = { _, _, _ -> repointed = true }))

        assertEquals(1, missing.left)
        assertTrue(Path.of(planned[0].entries[0].from).exists() && target.resolve("f1.mp4").exists())
        assertTrue(journal.exists())

        val done = assertNotNull(mover().recover(recordSide = { _, _, _ -> RecordSide.AtDestination }, repoint = { _, _, _ -> repointed = true }))

        assertEquals(1, done.finished)
        assertFalse(repointed)
        assertFalse(Path.of(planned[0].entries[0].from).exists())
    }

    @Test
    fun a_moved_partial_file_is_repointed_from_the_journal_after_a_crash() {
        val partial = source.resolve("show_e1.mp4.part")
        Files.write(partial, ByteArray(70_000) { (it % 251).toByte() })
        val planned = listOf(MoveItem("dl", listOf(MoveEntry("show_e1.mp4.part", partial.toString(), MoveKind.Partial, 70_000))))
        try {
            run(mover(crashAt = "record-updating"), planned)
        } catch (_: Crash) {
        }
        val repointed = mutableListOf<Path?>()

        val report = assertNotNull(mover().recover(recordSide = { _, _, _ -> RecordSide.AtSource }, repoint = { _, _, path -> repointed += path }))

        assertEquals(listOf<Path?>(target.resolve("show_e1.mp4.part")), repointed)
        assertEquals(1, report.finished)
        assertFalse(partial.exists())
        assertEquals(70_000L, Files.size(target.resolve("show_e1.mp4.part")))
    }

    @Test
    fun a_file_at_the_destination_that_the_move_did_not_publish_is_never_touched_by_recovery() {
        val movie = video(1)
        val other = target.resolve("f1.mp4").writeVideo(2) // same name and size, other content
        val unrelatedBytes = bytes(other)
        val sourceBytes = bytes(movie)
        // A journal that plans f1.mp4 and was cut short right after being written.
        try {
            run(mover(crashAt = "journal-written"), listOf(item("i", movie)))
        } catch (_: Crash) {
        }

        mover().recover()

        assertContentEquals(unrelatedBytes, bytes(other), "an unrelated file was deleted or replaced")
        assertContentEquals(sourceBytes, bytes(movie), "the source was touched")
    }

    @Test
    fun an_identical_file_at_the_destination_that_was_not_published_by_the_move_does_not_let_the_source_go() {
        val movie = video(1)
        Files.copy(movie, target.resolve("f1.mp4"))
        try {
            run(mover(crashAt = "journal-written"), listOf(item("i", movie)))
        } catch (_: Crash) {
        }

        mover().recover()

        assertTrue(movie.exists(), "the source was deleted beside a file this move never published")
        assertTrue(target.resolve("f1.mp4").exists())
    }

    @Test
    fun a_published_name_that_was_replaced_by_another_file_afterwards_is_not_deleted_and_the_source_stays() {
        val planned = crashAt("published", count = 1)
        // Something else took the final name after the crash and before the next start.
        Files.delete(target.resolve("f1.mp4"))
        target.resolve("f1.mp4").writeText("a different file")

        val report = assertNotNull(mover().recover())

        assertEquals("a different file", target.resolve("f1.mp4").toFile().readText())
        assertTrue(Path.of(planned[0].entries[0].from).exists())
        assertEquals(1, report.left)
    }

    @Test
    fun an_unreadable_journal_is_put_aside_and_nothing_is_done() {
        assertNull(mover().recover())
        journal.writeText("not json")

        assertNull(mover().recover())
        assertFalse(journal.exists())
        assertTrue(world.root.resolve("journal.json.unreadable").exists())
    }

    @Test
    fun progress_names_the_file_and_counts_files_and_bytes() {
        val planned = items(2)
        val seen = mutableListOf<MoveProgress>()

        mover().run(MovePlan(target, "t", planned), { false }, { seen += it }, { true }) { _, _ -> }

        assertTrue(seen.any { it.currentName == "f1.mp4" && it.totalFiles == 2 })
        assertEquals(2, seen.last().doneFiles)
        assertTrue(seen.last().doneBytes >= planned.sumOf { it.entries[0].size })
        assertEquals("to", seen.last().destinationName)
    }
}
