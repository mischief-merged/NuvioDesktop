package com.nuvio.app.features.downloads

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Where a file is found across locations, and what an unplugged drive looks like. Temporary folders only. */
class DownloadLocationsTest {
    private val world = FolderWorld()

    @AfterTest
    fun clean_up() = world.close()

    private fun found(lookup: FileLookup) = assertIs<FileLookup.Found>(lookup)

    @Test
    fun a_file_is_found_at_its_recorded_address_in_the_app_folder_or_in_a_chosen_one() {
        val inApp = world.appFolder.resolve("a.mp4").writeVideo(1)
        val drive = world.driveLocation("Archive")
        val onDrive = drive.path.resolve("b.mp4").writeVideo(2)

        val first = found(world.locations.locate(null, inApp.toUri().toString(), "a.mp4"))
        val second = found(world.locations.locate(drive.id, onDrive.toUri().toString(), "b.mp4"))

        assertEquals(DownloadLocations.AppId, first.locationId)
        assertEquals(drive.id, second.locationId)
    }

    @Test
    fun a_file_of_the_same_name_in_another_location_is_never_adopted_by_a_record() {
        val drive = world.driveLocation("Archive")
        val oldAddress = world.appFolder.resolve("c.mp4").toUri().toString()
        drive.path.resolve("c.mp4").writeVideo(3)

        // The record says the app folder. A file of that name on a drive is another file.
        assertEquals(FileLookup.Missing, world.locations.locate(null, oldAddress, "c.mp4"))
    }

    @Test
    fun an_unplugged_drive_is_unreachable_and_not_missing_and_comes_back_by_itself() {
        val drive = world.driveLocation("Archive")
        val file = drive.path.resolve("d.mp4").writeVideo(4)
        val address = file.toUri().toString()

        world.unplug("Archive")
        val away = world.locations.locate(drive.id, address, "d.mp4")
        world.replug("Archive")
        val back = world.locations.locate(drive.id, address, "d.mp4")

        assertEquals(FileLookup.Unreachable(drive.id, FolderAvailability.NotConnected), away)
        found(back)
    }

    @Test
    fun a_folder_missing_on_a_drive_that_is_there_is_not_found_and_still_not_missing() {
        val drive = world.driveLocation("Archive")
        val file = drive.path.resolve("e.mp4").writeVideo(5)
        val address = file.toUri().toString()

        drive.path.toFile().deleteRecursively()

        assertEquals(FileLookup.Unreachable(drive.id, FolderAvailability.NotFound), world.locations.locate(drive.id, address, "e.mp4"))
    }

    @Test
    fun a_path_that_something_recreated_without_the_marker_does_not_pass_for_the_drive() {
        val drive = world.driveLocation("Archive")
        world.unplug("Archive")
        // What macOS does when a program writes to /Volumes/Archive/Movies while the drive is away.
        drive.path.createDirectories()

        // The folder is there but was not set up as a download location: nothing is lost, choose it again.
        assertEquals(FolderAvailability.NotSetUp, world.locations.availability(drive))
    }

    @Test
    fun a_file_that_is_really_gone_from_a_connected_location_is_missing() {
        val drive = world.driveLocation("Archive")

        assertEquals(FileLookup.Missing, world.locations.locate(drive.id, drive.path.resolve("x.mp4").toUri().toString(), "x.mp4"))
    }

    @Test
    fun a_path_on_a_drive_that_is_not_ours_and_not_there_is_unreachable() {
        val address = world.volumes.resolve("Gone").resolve("Films/y.mp4").toUri().toString()

        val lookup = world.locations.locate(null, address, "y.mp4")

        assertIs<FileLookup.Unreachable>(lookup)
    }

    @Test
    fun the_app_folder_is_always_available_and_the_default_is_where_new_downloads_go() {
        assertEquals(FolderAvailability.Available, world.locations.availability(world.locations.appLocation()))
        assertEquals(DownloadLocations.AppId, world.locations.defaultLocation().id)

        val drive = world.locations.setDefault(world.volumes.resolve("Archive/Movies").also { it.createDirectories() })

        assertEquals(drive.id, world.locations.defaultLocation().id)
        // The same folder given again is the same location.
        assertEquals(drive.id, world.locations.register(drive.path).id)
    }

    @Test
    fun locations_that_hold_nothing_are_forgotten_except_the_default() {
        val a = world.driveLocation("A")
        val b = world.driveLocation("B")
        world.locations.setDefaultId(a.id)

        world.locations.forgetExcept(emptySet())

        assertEquals(listOf(DownloadLocations.AppId, a.id), world.locations.all().map { it.id })
        assertNotEquals(b.id, world.locations.defaultLocation().id)
    }

    @Test
    fun the_setting_is_kept_in_its_own_store_and_survives_a_restart() {
        val drive = world.locations.setDefault(world.volumes.resolve("Archive/Movies").also { it.createDirectories() })

        val again = DownloadLocations(
            DownloadLocationConfig(com.nuvio.app.core.storage.DesktopStorage.Store(world.root.resolve("folders.properties"))),
            { world.appFolder },
            FakeVolumes(world.volumes),
        )

        assertEquals(drive.id, again.defaultLocation().id)
        assertTrue(world.root.resolve("folders.properties").exists())
    }

    // Validation

    private fun check(path: java.nio.file.Path, needed: Long = 0L) = world.validator.check(path.toString(), needed)

    @Test
    fun a_folder_that_exists_or_can_be_created_and_is_writable_is_accepted_and_leaves_no_probe_behind() {
        val existing = world.root.resolve("Elsewhere").createDirectories()
        val created = world.root.resolve("New/Deeper")

        assertEquals(FolderCheck.Ok, check(existing))
        assertEquals(FolderCheck.Ok, check(created))

        assertTrue(created.exists())
        assertEquals(emptyList(), Files.list(existing).use { it.toList() })
    }

    @Test
    fun a_blank_relative_or_file_path_is_not_a_folder() {
        val file = world.root.resolve("a-file").also { it.writeText("x") }

        assertEquals(FolderCheck.Problem(FolderProblem.NotAFolder), world.validator.check("", 0))
        assertEquals(FolderCheck.Problem(FolderProblem.NotAFolder), world.validator.check("relative/path", 0))
        assertEquals(FolderCheck.Problem(FolderProblem.NotAFolder), check(file))
    }

    @Test
    fun a_drive_that_is_not_connected_is_refused_and_its_folder_is_not_created() {
        val target = world.volumes.resolve("Gone/Movies")

        assertEquals(FolderCheck.Problem(FolderProblem.NotConnected), check(target))
        assertTrue(!world.volumes.resolve("Gone").exists())
    }

    @Test
    fun a_folder_that_cannot_be_created_is_refused() {
        val blocker = world.root.resolve("blocker").also { it.writeText("x") }

        assertEquals(FolderCheck.Problem(FolderProblem.CannotCreate), check(blocker.resolve("inside")))
    }

    @Test
    fun a_folder_that_cannot_be_written_is_refused() {
        val readOnly = world.root.resolve("readonly").createDirectories()
        readOnly.toFile().setWritable(false)
        try {
            if (readOnly.toFile().canWrite()) return // running as a user that ignores permissions
            assertEquals(FolderCheck.Problem(FolderProblem.NotWritable), check(readOnly))
        } finally {
            readOnly.toFile().setWritable(true)
        }
    }

    @Test
    fun the_apps_own_data_is_refused_but_its_default_downloads_folder_is_fine() {
        world.appData.createDirectories()

        assertEquals(FolderCheck.Problem(FolderProblem.InsideAppData), check(world.appData))
        assertEquals(FolderCheck.Problem(FolderProblem.InsideAppData), check(world.appData.resolve("other")))
        assertEquals(FolderCheck.Problem(FolderProblem.InsideAppData), check(world.appFolder.resolve("sub")))
        assertEquals(FolderCheck.Ok, check(world.appFolder))
    }

    @Test
    fun a_link_into_the_apps_data_is_not_a_way_around_it() {
        world.appData.createDirectories()
        val link = world.root.resolve("innocent")
        Files.createSymbolicLink(link, world.appData)

        assertEquals(FolderCheck.Problem(FolderProblem.InsideAppData), check(link.resolve("sneaky")))
    }

    @Test
    fun the_program_folder_is_refused() {
        val program = world.root.resolve("program/lib").createDirectories()

        assertEquals(FolderCheck.Problem(FolderProblem.InsideProgram), check(program))
    }

    @Test
    fun the_trash_is_refused() {
        assertEquals(FolderCheck.Problem(FolderProblem.IsTrash), check(world.root.resolve("home/.Trash")))
        assertEquals(FolderCheck.Problem(FolderProblem.IsTrash), check(world.root.resolve("disk/.Trashes/501")))
        assertEquals(FolderCheck.Problem(FolderProblem.IsTrash), check(world.root.resolve("C/\$RECYCLE.BIN/x")))
        assertEquals(FolderCheck.Problem(FolderProblem.IsTrash), check(world.root.resolve("h/.local/share/Trash/files")))
    }

    @Test
    fun a_folder_inside_or_around_another_download_folder_is_refused_but_the_same_folder_is_not() {
        val drive = world.driveLocation("Archive")

        assertEquals(FolderCheck.Problem(FolderProblem.OverlapsDownloadFolder), check(drive.path.resolve("inner")))
        assertEquals(FolderCheck.Problem(FolderProblem.OverlapsDownloadFolder), check(drive.path.parent))
        assertEquals(FolderCheck.Ok, check(drive.path))
    }

    @Test
    fun a_folder_without_room_for_what_is_about_to_move_is_refused_with_the_numbers() {
        val folder = world.root.resolve("Full").createDirectories()
        val free = Files.getFileStore(folder).usableSpace

        val result = check(folder, needed = free + 1_000_000L)

        val problem = assertIs<FolderCheck.Problem>(result)
        assertEquals(FolderProblem.NotEnoughSpace, problem.kind)
        assertEquals(free + 1_000_000L, problem.neededBytes)
        assertEquals(FolderCheck.Ok, check(folder, needed = 1L))
    }

    // Placement and selection

    @Test
    fun a_new_episode_goes_where_the_show_already_is_and_a_movie_goes_to_the_default() {
        val drive = world.driveLocation("Archive")
        val items = listOf(
            episodeItem("tt1", 1, locationId = drive.id, file = drive.path.resolve("a.mp4")),
            episodeItem("tt2", 1, locationId = null),
        )
        val locationOf = { item: DownloadItem -> world.locations.locationOfRecord(item.locationId, item.localFileUri).id }

        assertEquals(drive.id, placementForNewDownload(items, "tt1", true, DownloadLocations.AppId, locationOf))
        assertEquals(DownloadLocations.AppId, placementForNewDownload(items, "tt2", true, "default-x", locationOf))
        assertEquals("default-x", placementForNewDownload(items, "tt3", true, "default-x", locationOf))
        assertEquals("default-x", placementForNewDownload(items, "tt1", false, "default-x", locationOf))
    }

    @Test
    fun a_show_split_over_two_locations_follows_the_most_recent_episode() {
        val drive = world.driveLocation("Archive")
        val items = listOf(
            episodeItem("tt1", 1, locationId = null, updatedAt = 10),
            episodeItem("tt1", 2, locationId = drive.id, updatedAt = 20),
        )
        val locationOf = { item: DownloadItem -> item.locationId ?: DownloadLocations.AppId }

        assertEquals(drive.id, placementForNewDownload(items, "tt1", true, "other", locationOf))
    }

    @Test
    fun selection_totals_and_fit() {
        val groups = movableGroups(
            listOf(
                episodeItem("tt1", 1, bytes = 300), episodeItem("tt1", 2, bytes = 200), movieItem("m1", bytes = 1_000),
            ),
        ) { DownloadLocations.AppId }
        val all = groups.flatMap { it.items }

        assertEquals(listOf("Film m1", "Show tt1"), groups.map { it.title })
        assertEquals(500L, groups.first { !it.isMovie }.bytes)
        assertEquals(1_300L, selectedBytes(all, setOf("tt1-1-1", "m1")))
        assertEquals(0L, selectedBytes(all, emptySet()))
        assertEquals(SelectionFit.Fits, selectionFit(0, 10))
        assertEquals(SelectionFit.Fits, selectionFit(50, 1_000))
        assertEquals(SelectionFit.Tight, selectionFit(970, 1_000))
        assertEquals(SelectionFit.DoesNotFit, selectionFit(1_001, 1_000))
        assertEquals(SelectionFit.Unknown, selectionFit(5, null))
    }
}
