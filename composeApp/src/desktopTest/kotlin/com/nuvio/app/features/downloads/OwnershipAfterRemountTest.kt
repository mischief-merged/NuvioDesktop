package com.nuvio.app.features.downloads

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Review 4, finding 4: a claim stored with the old mount path is still a claim at the new one. */
class OwnershipAfterRemountTest {
    private val world = FolderWorld()

    @AfterTest
    fun clean_up() = world.close()

    @Test
    fun another_profiles_claim_on_the_old_mount_path_still_stops_the_move_after_the_drive_remounts() {
        val drive = world.driveLocation("Old")
        val video = drive.path.resolve("film.mp4").writeVideo(1)
        val mine = movieItem("film", drive.id, video, Files.size(video))
        val theirs = mine.copy(id = "theirs")
        world.unplug("Old")
        Files.move(world.root.resolve("Old.unplugged"), world.volumes.resolve("New"))
        assertNotNull(world.locations.rediscover(drive))
        val moved = world.volumes.resolve("New/Movies/film.mp4")
        val hooks = ProfileHooks(mapOf(1 to listOf(mine), 2 to listOf(theirs)))

        val report = DesktopDownloadFolders(world.locations, world.validator, world.mover(), hooks).move(setOf(mine.id), world.appFolder.toString())

        assertEquals(MoveFailureReason.OwnedByAnother, report.failures.single().reason)
        assertTrue(moved.exists())
        assertTrue(world.trash.files().isEmpty())
    }

    @Test
    fun a_claim_whose_location_is_not_known_any_more_stops_every_path_with_its_file_name() {
        val video = world.appFolder.also { Files.createDirectories(it) }.resolve("film.mp4").writeVideo(1)
        val mine = movieItem("film", null, video, Files.size(video))
        val unknown = mine.copy(id = "lost", locationId = "loc-forgotten", localFileUri = world.root.resolve("gone/film.mp4").toUri().toString())
        val ownership = DownloadOwnership(world.locations, listOf(RecordRef.of(1, mine), RecordRef.of(2, unknown)))

        assertEquals("lost", ownership.otherOwner(1, "film", video)?.id)
    }
}
