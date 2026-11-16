package com.nuvio.app.features.downloads

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Review 4, finding 6: a finished record without an address is never given a file by name or size. It
 * shows "File not found", and only a file the user picks and confirms is adopted, with every check.
 */
class LocateFileTest {
    private val world = FolderWorld()

    @AfterTest
    fun clean_up() = world.close()

    private fun addressless(id: String, size: Long) = movieItem(id).copy(localFileUri = null, downloadedBytes = size, totalBytes = size)

    private fun folders(hooks: ProfileHooks) = DesktopDownloadFolders(world.locations, world.validator, world.mover(), hooks, trashPath = world.trash::send)

    @Test
    fun a_same_named_same_sized_file_is_never_adopted_at_load() {
        val file = world.appFolder.createDirectories().resolve("a.mp4").writeVideo(1)
        val item = addressless("a", Files.size(file))

        assertNull(DownloadsPlatformDownloader.restoreItem(item).localFileUri)
    }

    @Test
    fun a_finished_record_without_an_address_shows_file_not_found_where_locations_are_handled() {
        val item = addressless("a", 100)

        assertEquals(DownloadPhase.FileNotFound, downloadRowStatus(item, 0L, addressRequired = true).phase)
        assertEquals(DownloadPhase.Completed, downloadRowStatus(item, 0L).phase)
    }

    @Test
    fun the_file_the_user_picks_is_adopted_only_inside_a_download_folder_and_only_when_no_other_record_uses_it() {
        val app = world.appFolder.createDirectories()
        val picked = app.resolve("picked.mp4").writeVideo(1)
        val item = addressless("a", Files.size(picked))
        val hooks = ProfileHooks(mapOf(1 to listOf(item), 2 to listOf(movieItem("theirs", file = picked))))
        val folders = folders(hooks)
        val outside = world.root.resolve("elsewhere/x.mp4").writeVideo(2)
        val link = app.resolve("link.mp4").also { Files.createSymbolicLink(it, outside) }

        assertFalse(folders.adoptFile(item, outside.toString()), "a file outside every download folder")
        assertFalse(folders.adoptFile(item, link.toString()), "a link")
        assertFalse(folders.adoptFile(item, picked.toString()), "a file another profile uses")
        assertNull(hooks.list(1).single().localFileUri)

        hooks.setList(2, emptyList())
        assertTrue(folders.adoptFile(item, picked.toString()))
        assertEquals(picked.toFile(), DownloadLocations.fileOfUri(hooks.list(1).single().localFileUri!!))
        // Once it has an address, it is never given another this way.
        assertFalse(folders.adoptFile(item, picked.toString()))
        assertTrue(Files.exists(picked) && world.trash.files().isEmpty())
    }
}
