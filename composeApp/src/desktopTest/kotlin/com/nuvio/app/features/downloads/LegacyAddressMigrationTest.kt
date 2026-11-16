package com.nuvio.app.features.downloads

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The one-time migration that gives an address to a finished record written before addresses were kept. */
class LegacyAddressMigrationTest {
    private val world = FolderWorld()

    @AfterTest
    fun clean_up() = world.close()

    private fun legacy(id: String, size: Long) =
        movieItem(id).copy(localFileUri = null, downloadedBytes = size, totalBytes = size)

    @Test
    fun the_address_is_recorded_when_the_file_is_there_with_the_records_size_and_nobody_else_owns_it() {
        val file = world.appFolder.createDirectories().resolve("a.mp4").writeVideo(1)
        val item = legacy("a", Files.size(file))

        assertEquals(file.toUri().toString(), legacyAddressFor(item, 1, world.locations, listOf(RecordRef.of(1, item))))
    }

    @Test
    fun nothing_is_recorded_for_a_file_of_another_size_a_link_a_file_another_record_owns_or_unknown_records() {
        val app = world.appFolder.createDirectories()
        val file = app.resolve("a.mp4").writeVideo(1)
        val size = Files.size(file)
        val item = legacy("a", size)

        assertNull(legacyAddressFor(legacy("a", size + 1), 1, world.locations, emptyList()))
        assertNull(legacyAddressFor(item, 1, world.locations, null))
        // Another profile's record has that file.
        val owner = movieItem("other", file = file)
        assertNull(legacyAddressFor(item, 1, world.locations, listOf(RecordRef.of(2, owner))))
        // An unfinished record in the same folder will write that name.
        val waiting = movieItem("a").copy(id = "waiting", status = DownloadStatus.Paused, localFileUri = null)
        assertNull(legacyAddressFor(item, 1, world.locations, listOf(RecordRef.of(1, waiting))))
        // A link is never taken for the file.
        val target = world.root.resolve("elsewhere/b.mp4").writeVideo(2)
        Files.createSymbolicLink(app.resolve("b.mp4"), target)
        assertNull(legacyAddressFor(legacy("b", Files.size(target)), 1, world.locations, emptyList()))
        // A record that has an address is never given another.
        assertNull(legacyAddressFor(item.copy(localFileUri = app.resolve("x.mp4").toUri().toString()), 1, world.locations, emptyList()))
    }

    @Test
    fun nothing_is_recorded_while_the_records_location_is_away() {
        val drive = world.driveLocation("Archive")
        val file = drive.path.resolve("a.mp4").writeVideo(1)
        val item = legacy("a", Files.size(file)).copy(locationId = drive.id)
        world.unplug("Archive")

        assertNull(legacyAddressFor(item, 1, world.locations, emptyList()))
    }
}
