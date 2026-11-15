package com.nuvio.app.features.downloads

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The folders of the desktop app: moving a show, a selection or everything, with drives coming and going. */
class DesktopDownloadFoldersTest {
    private val world = FolderWorld()

    @AfterTest
    fun clean_up() = world.close()

    private class Hooks(var items: List<DownloadItem>) : DownloadsHooks {
        val log = mutableListOf<String>()
        var onRelocate: (String) -> Unit = {}
        override fun items() = items
        override fun pause(downloadId: String) {
            log += "pause $downloadId"
            items = items.map { if (it.id == downloadId) it.copy(status = DownloadStatus.Paused) else it }
        }
        override fun resume(downloadId: String) {
            log += "resume $downloadId"
        }
        override fun relocate(downloadId: String, locationId: String, localFileUri: String?) {
            log += "relocate $downloadId"
            onRelocate(downloadId)
            items = items.map {
                if (it.id == downloadId) it.copy(locationId = locationId, localFileUri = localFileUri ?: it.localFileUri) else it
            }
        }
        override fun resumeHeld() {
            log += "resumeHeld"
        }
    }

    private fun controller(hooks: Hooks, crashAt: String? = null) = DesktopDownloadFolders(
        locations = world.locations,
        validator = world.validator,
        mover = DownloadMover(
            world.root.resolve("journal.json"),
            checkpoint = { if (it == crashAt) throw IllegalStateException("crash at $it") },
            sameStore = { _, _ -> false },
        ),
        hooks = hooks,
    )

    private fun episode(show: String, n: Int, status: DownloadStatus = DownloadStatus.Completed, bytes: Long = 200_000L): DownloadItem {
        val base = episodeItem(show, n, status = status, bytes = bytes)
        val file = world.appFolder.resolve(base.fileName).writeVideo(show.hashCode() + n)
        return base.copy(localFileUri = file.toUri().toString())
    }

    private fun fileOf(item: DownloadItem): Path = world.appFolder.resolve(item.fileName)

    @Test
    fun moving_one_show_moves_its_episodes_and_leaves_every_other_download_alone() {
        val showA = (1..3).map { episode("A", it) }
        val showB = (1..2).map { episode("B", it) }
        val drive = world.driveLocation("Archive")
        val originals = (showA + showB).associate { it.id to Files.readAllBytes(fileOf(it)) }
        val hooks = Hooks(showA + showB)
        val folders = controller(hooks)

        val report = folders.move(showA.map { it.id }.toSet(), drive.path.toString())

        assertEquals(3, report.moved)
        assertEquals("Movies", report.destinationName)
        assertTrue(report.failures.isEmpty())
        showA.forEach {
            assertFalse(fileOf(it).exists())
            assertContentEquals(originals.getValue(it.id), Files.readAllBytes(drive.path.resolve(it.fileName)))
        }
        showB.forEach {
            assertContentEquals(originals.getValue(it.id), Files.readAllBytes(fileOf(it)))
            assertFalse(drive.path.resolve(it.fileName).exists())
        }
        assertEquals(setOf(drive.id), hooks.items.filter { it.parentMetaId == "A" }.map { it.locationId }.toSet())
        assertEquals(setOf<String?>(null), hooks.items.filter { it.parentMetaId == "B" }.map { it.locationId }.toSet())
    }

    @Test
    fun after_a_show_moved_its_next_episode_goes_with_it_and_other_shows_keep_going_to_the_default() {
        val showA = (1..2).map { episode("A", it) }
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(showA)
        val folders = controller(hooks)

        folders.move(showA.map { it.id }.toSet(), drive.path.toString())

        assertEquals(drive.id, folders.placementFor(hooks.items, "A", isEpisode = true))
        assertEquals(DownloadLocations.AppId, folders.placementFor(hooks.items, "Z", isEpisode = true))
    }

    @Test
    fun the_state_lists_each_location_with_its_items_size_free_space_and_whether_it_is_connected() {
        val items = (1..2).map { episode("A", it, bytes = 1_000L) } + episode("B", 1, bytes = 500L)
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(items)
        val folders = controller(hooks)
        folders.move(items.filter { it.parentMetaId == "A" }.map { it.id }.toSet(), drive.path.toString())

        folders.refresh()
        val state = folders.state.value

        val app = state.locations.first { it.isAppFolder }
        val onDrive = state.locations.first { it.id == drive.id }
        assertEquals(1, app.itemCount)
        assertEquals(500L, app.totalBytes)
        assertEquals(2, onDrive.itemCount)
        assertEquals(2_000L, onDrive.totalBytes)
        assertNotNull(onDrive.freeBytes)
        assertTrue(state.hasSeveralLocations)
        assertTrue(app.isDefault && !onDrive.isDefault)

        world.unplug("Archive")
        folders.refresh()

        val away = folders.state.value.locations.first { it.id == drive.id }
        assertEquals(FolderAvailability.NotConnected, away.availability)
        assertNull(away.freeBytes)
        assertEquals(listOf(drive.id), folders.state.value.unreachable.map { it.id })
        assertEquals(2, away.itemCount, "an item on a drive that is away is still counted")
    }

    @Test
    fun some_locations_can_be_connected_while_others_wait() {
        val a = episode("A", 1)
        val driveOne = world.driveLocation("One")
        val driveTwo = world.driveLocation("Two")
        val hooks = Hooks(listOf(a))
        val folders = controller(hooks)
        folders.move(setOf(a.id), driveOne.path.toString())
        val b = episode("B", 1)
        hooks.items = hooks.items + b
        folders.move(setOf(b.id), driveTwo.path.toString())

        world.unplug("One")
        folders.refresh()

        val moved = hooks.items.associateBy { it.id }
        assertFalse(folders.isConnected(moved.getValue(a.id)))
        assertTrue(folders.isConnected(moved.getValue(b.id)))
        assertTrue(folders.heldByFolder(moved.getValue(a.id)))
        assertFalse(folders.heldByFolder(moved.getValue(b.id)))
        assertEquals(FileLookup.Unreachable(driveOne.id, FolderAvailability.NotConnected), folders.lookup(moved.getValue(a.id)))
        assertTrue(folders.lookup(moved.getValue(b.id)) is FileLookup.Found)
    }

    @Test
    fun a_move_that_includes_an_item_on_an_unplugged_drive_moves_the_rest_and_reports_that_one() {
        val a = episode("A", 1)
        val b = episode("B", 1)
        val driveOne = world.driveLocation("One")
        val driveTwo = world.driveLocation("Two")
        val hooks = Hooks(listOf(a, b))
        val folders = controller(hooks)
        folders.move(setOf(a.id), driveOne.path.toString())
        val bytesOfA = Files.readAllBytes(driveOne.path.resolve(a.fileName))
        world.unplug("One")

        val report = folders.move(setOf(a.id, b.id), driveTwo.path.toString())

        assertEquals(1, report.moved)
        assertEquals(1, report.unreachable)
        assertTrue(driveTwo.path.resolve(b.fileName).exists())
        world.replug("One")
        assertContentEquals(bytesOfA, Files.readAllBytes(driveOne.path.resolve(a.fileName)), "the file on the drive that was away was touched")
    }

    @Test
    fun active_downloads_of_the_moved_items_pause_first_and_resume_after_and_are_held_meanwhile() {
        val done = episode("A", 1)
        val running = episodeItem("A", 2, status = DownloadStatus.Downloading, bytes = 5_000L)
        val partial = world.appFolder.resolve(running.fileName + ".part")
        Files.write(partial, ByteArray(5_000) { it.toByte() })
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(listOf(done, running))
        val folders = controller(hooks)
        var heldWhileMoving: Boolean? = null
        hooks.onRelocate = { id -> if (id == running.id) heldWhileMoving = folders.heldByFolder(running) }

        val report = folders.move(setOf(done.id, running.id), drive.path.toString())

        assertEquals(2, report.moved)
        assertEquals(true, heldWhileMoving)
        assertFalse(folders.heldByFolder(hooks.items.first { it.id == running.id }.copy()))
        assertEquals("pause ${running.id}", hooks.log.first())
        assertTrue(hooks.log.indexOf("resume ${running.id}") > hooks.log.indexOf("relocate ${running.id}"))
        assertTrue(drive.path.resolve(running.fileName + ".part").exists())
        assertFalse(partial.exists())
    }

    @Test
    fun progress_is_reported_per_file_and_overall_and_clears_afterwards() {
        val items = (1..3).map { episode("A", it) }
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(items)
        val folders = controller(hooks)
        var seen: MoveProgress? = null
        hooks.onRelocate = { seen = folders.progress.value }

        folders.move(items.map { it.id }.toSet(), drive.path.toString())

        val progress = assertNotNull(seen)
        assertEquals(3, progress.totalFiles)
        assertEquals("Movies", progress.destinationName)
        assertTrue(progress.totalBytes > 0)
        assertNull(folders.progress.value)
        assertFalse(folders.state.value.moving)
    }

    @Test
    fun cancelling_leaves_the_finished_files_moved_and_the_rest_in_place() {
        val items = (1..4).map { episode("A", it) }
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(items)
        val folders = controller(hooks)
        hooks.onRelocate = { folders.cancelMove() }

        val report = folders.move(items.map { it.id }.toSet(), drive.path.toString())

        assertTrue(report.cancelled)
        assertEquals(1, report.moved)
        assertEquals(3, report.notReached)
        assertEquals(listOf(false, true, true, true), items.map { fileOf(it).exists() })
        assertEquals(listOf(true, false, false, false), items.map { drive.path.resolve(it.fileName).exists() })
        assertFalse(world.root.resolve("journal.json").exists())
    }

    @Test
    fun a_move_to_a_drive_that_is_not_there_does_not_start() {
        val item = episode("A", 1)
        val drive = world.driveLocation("Archive")
        world.unplug("Archive")
        val folders = controller(Hooks(listOf(item)))

        val report = folders.move(setOf(item.id), drive.path.toString())

        assertEquals(0, report.moved)
        assertEquals(FolderProblem.NotAFolder, report.problem)
        assertTrue(fileOf(item).exists())
    }

    @Test
    fun a_move_cut_short_by_a_crash_is_settled_on_the_next_start_and_the_paused_downloads_come_back() {
        val items = (1..3).map { episode("A", it) }
        val running = episodeItem("A", 9, status = DownloadStatus.Downloading)
        val drive = world.driveLocation("Archive")
        val originals = items.associate { it.id to Files.readAllBytes(fileOf(it)) }
        val hooks = Hooks(items + running)
        var crashed = false
        try {
            controller(hooks, crashAt = "renamed").move((items + running).map { it.id }.toSet(), drive.path.toString())
        } catch (_: IllegalStateException) {
            crashed = true
        }
        assertTrue(crashed)
        assertTrue(world.root.resolve("journal.json").exists())

        val report = assertNotNull(controller(Hooks(emptyList())).recoverInterrupted())

        assertEquals(listOf(running.id), report.pausedIds)
        assertFalse(world.root.resolve("journal.json").exists())
        items.forEach {
            val places = listOf(fileOf(it), drive.path.resolve(it.fileName)).filter { p -> p.exists() }
            assertTrue(places.isNotEmpty(), "${it.id} is lost")
            places.forEach { p -> assertContentEquals(originals.getValue(it.id), Files.readAllBytes(p)) }
        }
        // The first file was finished by the crash step: it is on the drive only; the rest stayed.
        assertTrue(drive.path.resolve(items[0].fileName).exists() && !fileOf(items[0]).exists())
        assertTrue(fileOf(items[1]).exists() && !drive.path.resolve(items[1].fileName).exists())
        // Records follow the files at the next load: the file is found on the drive although the record says the app folder.
        val lookup = world.locations.locate(null, items[0].localFileUri, items[0].fileName)
        assertEquals(drive.id, (lookup as FileLookup.Found).locationId)
    }

    @Test
    fun a_move_inside_one_volume_needs_no_free_space_and_a_copy_across_volumes_needs_its_size() {
        val item = episode("A", 1)
        val drive = world.driveLocation("Archive")
        val size = Files.size(fileOf(item))
        val sameVolume = DesktopDownloadFolders(
            world.locations, world.validator, DownloadMover(world.root.resolve("j.json")), Hooks(listOf(item)),
        )

        // The temporary folders share one volume here, so a rename carries nothing.
        assertEquals(0L, sameVolume.bytesToCopy(setOf(item.id), drive.path.toString()))
        assertTrue(size > 0L)
    }

    @Test
    fun a_location_coming_back_resumes_the_downloads_that_waited_and_wakes_auto_download() {
        val item = episode("A", 1)
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(listOf(item))
        var woke = 0
        val folders = DesktopDownloadFolders(
            world.locations, world.validator, DownloadMover(world.root.resolve("j.json")), hooks, onReturned = { woke++ },
        )
        folders.move(setOf(item.id), drive.path.toString())
        hooks.log.clear()

        folders.refresh()
        world.unplug("Archive")
        folders.refresh()
        assertFalse("resumeHeld" in hooks.log)
        world.replug("Archive")
        folders.refresh()

        assertTrue("resumeHeld" in hooks.log)
        assertEquals(1, woke)
    }

    @Test
    fun a_location_that_holds_nothing_after_a_move_is_forgotten_but_the_default_stays() {
        val item = episode("A", 1)
        val first = world.driveLocation("One")
        val second = world.driveLocation("Two")
        val hooks = Hooks(listOf(item))
        val folders = controller(hooks)
        folders.move(setOf(item.id), first.path.toString())

        folders.move(setOf(item.id), second.path.toString())

        // The first location holds nothing now and is forgotten; the one in use stays (under the id it was registered with).
        assertEquals(listOf(world.appFolder, second.path), world.locations.all().map { it.path })
        assertEquals(second.path, world.locations.all().last().path)
    }

    @Test
    fun setting_the_default_changes_where_new_downloads_go_and_moves_nothing() {
        val item = episode("A", 1)
        val drive = world.driveLocation("Archive")
        val folders = controller(Hooks(listOf(item)))

        assertTrue(folders.setDefault(drive.path.toString()))

        assertEquals(drive.id, folders.state.value.defaultLocation?.id)
        assertEquals(drive.id, folders.placementFor(emptyList(), "new", isEpisode = true))
        assertTrue(fileOf(item).exists())
        assertFalse(folders.setDefault(world.root.resolve("not-there").toString()))
    }
}
