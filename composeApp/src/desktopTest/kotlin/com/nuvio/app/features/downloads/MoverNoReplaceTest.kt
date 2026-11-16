package com.nuvio.app.features.downloads

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review 4, findings 1 and 2: nothing the mover writes can replace or truncate a file it did not create,
 * even when another process swaps a pathname between two of its steps.
 */
class MoverNoReplaceTest {
    private val world = FolderWorld()

    @AfterTest
    fun clean_up() = world.close()

    private fun film(seed: Int = 1): DownloadItem {
        val video = world.appFolder.createDirectories().resolve("film.mp4").writeVideo(seed)
        return movieItem("film", null, video, Files.size(video))
    }

    private fun fileOf(item: DownloadItem): Path = DownloadLocations.fileOfUri(item.localFileUri!!)!!.toPath()

    private fun folders(hooks: DownloadsHooks, mover: DownloadMover) =
        DesktopDownloadFolders(world.locations, world.validator, mover, hooks, writerWaitMs = 2_000L)

    /** The mover's source text: none of the calls that can replace or truncate an existing file may appear. */
    @Test
    fun the_mover_has_no_replacing_move_no_truncating_open_and_no_reopened_write() {
        val source = listOf(
            Paths.get("src/desktopMain/kotlin/com/nuvio/app/features/downloads/DownloadMover.kt"),
            Paths.get("composeApp/src/desktopMain/kotlin/com/nuvio/app/features/downloads/DownloadMover.kt"),
        ).first { it.exists() }
        val code = Files.readAllLines(source).filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
        listOf("REPLACE_EXISTING", "ATOMIC_MOVE", "TRUNCATE_EXISTING", "Files.move(", "Files.copy(", "Files.write(", "newOutputStream", "renameTo(").forEach {
            assertFalse(it in code, "the mover still uses $it")
        }
    }

    @Test
    fun a_temporary_name_swapped_for_a_link_to_a_users_file_never_truncates_that_file() {
        val item = film()
        val drive = world.driveLocation("Archive")
        val victim = world.root.resolve("precious.mkv").writeVideo(42)
        val victimBytes = Files.readAllBytes(victim)
        val mover = world.mover(checkpoint = { step ->
            if (step == "temp-created") {
                // Another process replaces the temporary name with a link to someone's file.
                val temp = world.tempsIn(drive.path).single()
                Files.move(temp, world.root.resolve("moved-aside.tmp"))
                Files.createSymbolicLink(temp, victim)
            }
        })
        val hooks = ProfileHooks(listOf(item))

        val report = folders(hooks, mover).move(setOf(item.id), drive.path.toString())

        assertContentEquals(victimBytes, Files.readAllBytes(victim), "the user's file was written through the swapped link")
        assertEquals(0, report.moved)
        assertEquals(item, hooks.items.single())
        assertTrue(fileOf(item).exists())
        assertTrue(world.trash.files().isEmpty())
    }

    @Test
    fun on_a_volume_without_links_a_final_name_swapped_for_a_users_file_is_never_overwritten_or_removed() {
        val item = film()
        val drive = world.driveLocation("Archive")
        val users = "a file someone put at the final name".toByteArray()
        var swapped = false
        val mover = world.mover(
            createLink = { _, _ -> throw UnsupportedOperationException("no links") },
            checkpoint = { step ->
                if (step == "direct-created" && !swapped) {
                    swapped = true
                    val target = drive.path.resolve("film.mp4")
                    Files.move(target, world.root.resolve("ours-aside.mp4"))
                    Files.write(target, users)
                }
            },
        )
        val hooks = ProfileHooks(listOf(item))

        val report = folders(hooks, mover).move(setOf(item.id), drive.path.toString())
        world.mover(createLink = { _, _ -> throw UnsupportedOperationException("no links") }).recover()

        assertTrue(swapped, "the move never created the final name directly")
        assertContentEquals(users, Files.readAllBytes(drive.path.resolve("film.mp4")))
        assertEquals(0, report.moved)
        assertEquals(item, hooks.items.single())
        assertTrue(fileOf(item).exists())
        assertTrue(world.trash.files().isEmpty())
    }

    @Test
    fun on_a_volume_without_links_a_move_writes_the_final_name_directly_and_verifies_it() {
        val item = film()
        val drive = world.driveLocation("Archive")
        val bytes = Files.readAllBytes(fileOf(item))
        val steps = mutableListOf<String>()
        val mover = world.mover(createLink = { _, _ -> throw UnsupportedOperationException("no links") }, checkpoint = { steps += it })
        val hooks = ProfileHooks(listOf(item))

        val report = folders(hooks, mover).move(setOf(item.id), drive.path.toString())

        assertEquals(1, report.moved)
        assertTrue("direct-created" in steps)
        assertContentEquals(bytes, Files.readAllBytes(drive.path.resolve("film.mp4")))
        assertTrue(world.tempsIn(drive.path).isEmpty())
        assertTrue(world.trash.holds(bytes))
    }

    @Test
    fun a_direct_copy_that_fails_verification_is_removed_only_while_it_is_still_the_file_this_move_created() {
        val item = film()
        val drive = world.driveLocation("Archive")
        val mover = world.mover(
            createLink = { _, _ -> throw UnsupportedOperationException("no links") },
            hashOf = { path -> if (path.startsWith(drive.path)) "bad" else DownloadMover.sha256Of(path) },
        )

        val report = folders(ProfileHooks(listOf(item)), mover).move(setOf(item.id), drive.path.toString())

        assertEquals(MoveFailureReason.CopyDidNotMatch, report.failures.single().reason)
        assertFalse(drive.path.resolve("film.mp4").exists())
        assertTrue(fileOf(item).exists())
        assertTrue(world.mover().journalFiles().isEmpty())
    }
}
