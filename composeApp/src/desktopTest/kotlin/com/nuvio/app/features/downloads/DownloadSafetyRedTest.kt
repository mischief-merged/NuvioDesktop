package com.nuvio.app.features.downloads

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Written against the first version, to show the review's findings fail there. */
class DownloadSafetyRedTest {
    private val world = FolderWorld()
    private val realBackend = DownloadsRepository.backend

    @BeforeTest fun setUp() { DownloadsRepository.clearLocalState() }

    @AfterTest
    fun tearDown() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        world.close()
    }

    private class Crash : Error()

    @Test
    fun recovery_never_touches_a_destination_file_the_transaction_did_not_publish() {
        val source = world.root.resolve("from").createDirectories().resolve("movie.mp4").writeVideo(1)
        val target = world.root.resolve("to").createDirectories()
        val unrelated = target.resolve("movie.mp4").writeVideo(2) // same size, other content
        val unrelatedBytes = Files.readAllBytes(unrelated)
        val sourceBytes = Files.readAllBytes(source)
        val journal = world.root.resolve("journal.json")
        val entries = listOf(MoveEntry("i", "movie.mp4", source.toString(), MoveKind.File, Files.size(source)))
        try {
            DownloadMover(journal, checkpoint = { if (it == "journal-written") throw Crash() }, sameStore = { _, _ -> false })
                .run(MovePlan(target, "d", entries), { false }, {}) { _, _ -> }
        } catch (_: Crash) {
        }

        DownloadMover(journal, sameStore = { _, _ -> false }).recover()

        assertContentEquals(unrelatedBytes, Files.readAllBytes(unrelated), "an unrelated file was deleted or replaced")
        assertContentEquals(sourceBytes, Files.readAllBytes(source))
    }

    @Test
    fun recovery_never_deletes_a_source_beside_an_identical_but_unpublished_destination() {
        val source = world.root.resolve("from").createDirectories().resolve("movie.mp4").writeVideo(1)
        val target = world.root.resolve("to").createDirectories()
        Files.copy(source, target.resolve("movie.mp4"))
        val journal = world.root.resolve("journal.json")
        val entries = listOf(MoveEntry("i", "movie.mp4", source.toString(), MoveKind.File, Files.size(source)))
        try {
            DownloadMover(journal, checkpoint = { if (it == "journal-written") throw Crash() }, sameStore = { _, _ -> false })
                .run(MovePlan(target, "d", entries), { false }, {}) { _, _ -> }
        } catch (_: Crash) {
        }

        DownloadMover(journal, sameStore = { _, _ -> false }).recover()

        assertTrue(source.exists(), "the source was deleted beside a file this move never published")
    }

    @Test
    fun a_record_is_never_given_another_locations_file_of_the_same_name() {
        val a = world.driveLocation("A")
        val b = world.driveLocation("B")
        val file = a.path.resolve("x.mp4").writeVideo(1)
        b.path.resolve("x.mp4").writeVideo(2)
        val address = file.toUri().toString()
        world.unplug("A")

        val lookup = world.locations.locate(a.id, address, "x.mp4")

        assertIs<FileLookup.Unreachable>(lookup)
    }

    @Test
    fun deleting_a_partial_download_on_an_unplugged_drive_keeps_its_record() {
        val drive = world.driveLocation("Archive")
        val backend = LocationsBackend(world)
        DownloadsRepository.backend = backend
        val partial = episodeItem("tt1", 1, status = DownloadStatus.Paused, locationId = drive.id)
        backend.payload = storedPayload(listOf(partial))
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
        world.unplug("Archive")

        val result = DownloadsRepository.deleteDownload(partial.id)

        assertEquals(DownloadDeleteResult.NotReachable, result)
        assertEquals(1, DownloadsRepository.uiState.value.items.size)
    }

    @Test
    fun a_download_that_is_a_symlink_is_not_moved() {
        val from = world.root.resolve("from").createDirectories()
        val real = world.root.resolve("elsewhere").createDirectories().resolve("real.mp4").writeVideo(1)
        val link = from.resolve("movie.mp4")
        Files.createSymbolicLink(link, real)
        val target = world.root.resolve("to").createDirectories()
        val entries = listOf(MoveEntry("i", "movie.mp4", link.toString(), MoveKind.File, Files.size(real)))

        val report = DownloadMover(world.root.resolve("j.json")).run(MovePlan(target, "d", entries), { false }, {}) { _, _ -> }

        assertEquals(0, report.moved)
        assertTrue(Files.isSymbolicLink(link), "the link itself was moved")
        assertTrue(!target.resolve("movie.mp4").exists() && Files.isRegularFile(real))
        assertEquals(1, report.failures.size)
        link.name
    }
}
