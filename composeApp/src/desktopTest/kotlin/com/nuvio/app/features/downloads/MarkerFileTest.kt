package com.nuvio.app.features.downloads

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review 5, item 1: choosing a folder never overwrites or follows a file already at the marker's name. */
class MarkerFileTest {
    private val world = FolderWorld()

    @AfterTest
    fun clean_up() = world.close()

    private fun folder(name: String) = world.volumes.resolve("Disk").resolve(name).createDirectories()

    @Test
    fun a_users_file_at_the_marker_name_is_never_overwritten_and_the_folder_is_refused() {
        val chosen = folder("Movies")
        val users = chosen.resolve(DownloadLocations.MarkerName).also { Files.writeString(it, "my own notes") }

        assertEquals(FolderCheck.Problem(FolderProblem.MarkerTaken), world.validator.check(chosen.toString(), 0L))
        assertNull(world.locations.registerChecked(chosen))
        val report = DesktopDownloadFolders(world.locations, world.validator, world.mover(), ProfileHooks(emptyList())).move(emptySet(), chosen.toString())

        assertEquals(FolderProblem.MarkerTaken, report.problem)
        assertEquals("my own notes", Files.readString(users))
        assertTrue(world.locations.all().none { it.path == chosen })
    }

    @Test
    fun a_link_at_the_marker_name_is_never_followed() {
        val chosen = folder("Movies")
        val victim = world.root.resolve("victim.txt").also { Files.writeString(it, "precious") }
        Files.createSymbolicLink(chosen.resolve(DownloadLocations.MarkerName), victim)

        assertNull(world.locations.registerChecked(chosen))
        assertEquals(FolderCheck.Problem(FolderProblem.MarkerTaken), world.validator.check(chosen.toString(), 0L))
        assertEquals("precious", Files.readString(victim))
    }

    @Test
    fun a_marker_of_ours_that_no_location_holds_is_adopted_and_one_of_another_location_is_refused() {
        val forgotten = folder("Old")
        Files.writeString(forgotten.resolve(DownloadLocations.MarkerName), "loc-1a2b3c4d")
        val adopted = world.locations.registerChecked(forgotten)
        assertEquals("loc-1a2b3c4d", adopted?.id)
        assertEquals(FolderAvailability.Available, world.locations.availability(adopted!!))

        val copy = folder("Copy")
        Files.writeString(copy.resolve(DownloadLocations.MarkerName), "loc-1a2b3c4d")
        assertNull(world.locations.registerChecked(copy))
        assertEquals("loc-1a2b3c4d", Files.readString(copy.resolve(DownloadLocations.MarkerName)))
    }

    @Test
    fun a_new_folder_gets_its_marker_created_new_and_the_write_probe_leaves_nothing() {
        val chosen = folder("Fresh")
        assertEquals(FolderCheck.Ok, world.validator.check(chosen.toString(), 0L))
        val location = world.locations.registerChecked(chosen)!!

        assertEquals(location.id, Files.readString(chosen.resolve(DownloadLocations.MarkerName)).trim())
        assertEquals(listOf(DownloadLocations.MarkerName), Files.list(chosen).use { s -> s.map { it.fileName.toString() }.toList() })
        assertFalse(Files.isSymbolicLink(chosen.resolve(DownloadLocations.MarkerName)))
    }
}
