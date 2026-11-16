package com.nuvio.app.features.downloads

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Review 6: a move never puts anything into a sidecar folder it did not create; a partial file is sent to the
 * Trash only with its identity proven, and a moved one keeps a recorded identity; and a partial file from
 * before identities were recorded is adopted only when it is provably the download's own.
 */
class Review6Test {
    private val world = FolderWorld()

    @AfterTest
    fun clean_up() = world.close()

    private fun film(): DownloadItem {
        val video = world.appFolder.createDirectories().resolve("film.mp4").writeVideo(1)
        val sidecar = world.appFolder.resolve("film.mp4.subtitles").createDirectories()
        Files.writeString(sidecar.resolve("0.srt"), "en")
        Files.writeString(sidecar.resolve(SidecarOwnership.MarkerName), "nuvio-sidecar 1\nvideo=film.mp4\n")
        return movieItem("film", null, video, Files.size(video))
    }

    private fun folders(hooks: ProfileHooks, mover: DownloadMover = world.mover()) =
        DesktopDownloadFolders(world.locations, world.validator, mover, hooks, trashPath = world.trash::send)

    @Test
    fun a_sidecar_folder_already_at_the_destination_refuses_the_item_and_gets_nothing_not_even_the_mark() {
        val item = film()
        val drive = world.driveLocation("Archive")
        val foreign = drive.path.resolve("film.mp4.subtitles").createDirectories()
        Files.writeString(foreign.resolve("notes.txt"), "the user's notes")
        Files.writeString(foreign.resolve("draft.srt.tmp"), "the user's draft")
        val hooks = ProfileHooks(listOf(item))

        val report = folders(hooks).move(setOf(item.id), drive.path.toString())

        assertEquals(MoveFailureReason.NameTaken, report.failures.single().reason)
        assertEquals("a folder with that name is already there", report.failures.single().detail)
        assertEquals(setOf("notes.txt", "draft.srt.tmp"), Files.list(foreign).use { s -> s.map { it.fileName.toString() }.toList() }.toSet())
        assertFalse(drive.path.resolve("film.mp4").exists())
        assertEquals(item, hooks.items.single())
    }

    @Test
    fun the_destination_sidecar_folder_is_created_by_the_move_and_removed_again_when_it_stays_empty() {
        val item = film()
        val drive = world.driveLocation("Archive")
        // Every companion's publication fails as if its name were taken, after the folder was created.
        val mover = world.mover(createLink = { link, existing ->
            if (link.parent.fileName.toString() == "film.mp4.subtitles") throw FileAlreadyExistsException(link.toString())
            Files.createLink(link, existing)
        })

        folders(ProfileHooks(listOf(item)), mover).move(setOf(item.id), drive.path.toString())

        assertFalse(drive.path.resolve("film.mp4.subtitles").exists(), "the empty folder this move created was left")
        assertTrue(world.appFolder.resolve("film.mp4.subtitles/0.srt").exists())
    }

    @Test
    fun a_partial_file_goes_to_the_trash_only_with_its_recorded_identity() {
        val app = world.appFolder.createDirectories()
        val part = app.resolve("ep.mp4.part").also { Files.write(it, ByteArray(100) { 1 }) }
        val item = movieItem("ep").copy(status = DownloadStatus.Paused, localFileUri = null)
        val folders = folders(ProfileHooks(listOf(item)))

        assertFalse(folders.removePartialOf(item), "a partial file without a recorded identity was sent away")
        assertFalse(folders.removePartialOf(item.copy(partialFileKey = "(dev=1,ino=1)")))
        assertTrue(part.exists())
        assertTrue(folders.removePartialOf(item.copy(partialFileKey = DownloadMover.identityOf(part))))
        assertFalse(part.exists())
        assertTrue(world.trash.holds(ByteArray(100) { 1 }))
    }

    @Test
    fun a_moved_partial_file_has_its_new_identity_recorded_in_the_same_step() {
        val app = world.appFolder.createDirectories()
        val part = app.resolve("ep.mp4.part").also { Files.write(it, ByteArray(5_000) { 3 }) }
        val item = movieItem("ep").copy(status = DownloadStatus.Paused, localFileUri = null, downloadedBytes = 5_000L, partialFileKey = DownloadMover.identityOf(part))
        val drive = world.driveLocation("Archive")
        val hooks = ProfileHooks(listOf(item))

        assertEquals(1, folders(hooks).move(setOf(item.id), drive.path.toString()).moved)

        val moved = hooks.items.single()
        assertEquals(drive.id, moved.locationId)
        assertEquals(DownloadMover.identityOf(drive.path.resolve("ep.mp4.part")), moved.partialFileKey)
        assertTrue(OwnedPartial(moved.partialFileKey).proven(drive.path.resolve("ep.mp4.part")))
    }

    private fun legacy(size: Long) = movieItem("ep").copy(status = DownloadStatus.Paused, localFileUri = null, downloadedBytes = size)

    private fun legacyPart(size: Int): Path = world.appFolder.createDirectories().resolve("ep.mp4.part").also { Files.write(it, ByteArray(size) { 2 }) }

    @Test
    fun a_legacy_partial_is_adopted_when_every_condition_holds() {
        val part = legacyPart(4_000)
        val item = legacy(4_000)

        assertEquals(DownloadMover.identityOf(part), legacyPartialKey(item, 1, part, world.locations, listOf(RecordRef.of(1, item))))
        assertEquals(4_000L, Files.size(part))
    }

    @Test
    fun a_legacy_partial_is_not_adopted_when_any_condition_fails_and_is_left_untouched() {
        val part = legacyPart(4_000)
        val item = legacy(4_000)
        val records = listOf(RecordRef.of(1, item))

        assertNull(legacyPartialKey(item.copy(partialFileKey = "(dev=1,ino=1)"), 1, part, world.locations, records), "a recorded identity")
        assertNull(legacyPartialKey(legacy(3_999), 1, part, world.locations, records), "a size other than the stored bytes")
        assertNull(legacyPartialKey(item, 1, part, world.locations, records + RecordRef.of(2, item.copy(id = "theirs"))), "another profile's claim")
        assertNull(legacyPartialKey(item, 1, part, world.locations, null), "lists that cannot be read")
        val elsewhere = world.root.resolve("elsewhere").createDirectories().resolve("ep.mp4.part").also { Files.write(it, ByteArray(4_000)) }
        assertNull(legacyPartialKey(item, 1, elsewhere, world.locations, records), "not in the record's own location")
        val drive = world.driveLocation("Archive")
        assertNull(legacyPartialKey(item.copy(locationId = drive.id), 1, part, world.locations, records), "a record of another location")
        Files.delete(part)
        val target = world.root.resolve("target.part").also { Files.write(it, ByteArray(4_000)) }
        Files.createSymbolicLink(part, target)
        assertNull(legacyPartialKey(item, 1, part, world.locations, records), "a link")
        assertEquals(4_000L, Files.size(target))
        assertNotNull(Files.readSymbolicLink(part))
    }
}
