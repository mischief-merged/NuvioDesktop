package com.nuvio.app.features.downloads

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A crash at every step of a move, one run per step: after any crash and the recovery that follows,
 * every original file is in place or in the Trash, every record of every profile points at an existing
 * file with its content, no temporary file is left, and nothing blocks the next move. Then every
 * leftover is resolved with "Remove the extra copy" and the same holds again.
 */
class MoveCrashMatrixTest {
    private class Scene(val world: FolderWorld, noLinks: Boolean) {
        val source = world.driveLocation("Source")
        val destination = world.driveLocation("Destination")
        val originals = mutableMapOf<Path, ByteArray>()
        val contentOf = mutableMapOf<String, ByteArray>()
        val hooks: ProfileHooks
        val link: (Path, Path) -> Unit =
            if (noLinks) { _, _ -> throw UnsupportedOperationException("no links") } else { a, b -> Files.createLink(a, b) }

        init {
            val app = world.appFolder.createDirectories()
            fun completed(id: String, folder: Path, locationId: String?, seed: Int, sidecar: List<String> = emptyList()): DownloadItem {
                val video = folder.resolve("$id.mp4").writeVideo(seed, 50_000)
                originals[video] = Files.readAllBytes(video)
                contentOf[id] = originals.getValue(video)
                sidecar.forEach { rel ->
                    val file = folder.resolve("$id.mp4.subtitles").resolve(rel)
                    file.parent.createDirectories()
                    Files.writeString(file, "sub $id $rel")
                    originals[file] = Files.readAllBytes(file)
                }
                return movieItem(id, locationId, video, Files.size(video))
            }
            val partialFile = app.resolve("ep.mp4.part").also { Files.write(it, ByteArray(7_000) { b -> (b % 13).toByte() }) }
            originals[partialFile] = Files.readAllBytes(partialFile)
            contentOf["ep"] = originals.getValue(partialFile)
            val profileOne = listOf(
                completed("film", app, null, 1, listOf("en.srt", "deep/fr.srt")),
                completed("other", source.path, source.id, 2),
                movieItem("ep").copy(status = DownloadStatus.Paused, localFileUri = null, downloadedBytes = 7_000L, totalBytes = null),
            )
            val profileTwo = listOf(completed("kept", app, null, 3))
            hooks = ProfileHooks(mapOf(1 to profileOne, 2 to profileTwo))
        }

        fun folders(checkpoint: (String) -> Unit = {}) = DesktopDownloadFolders(
            world.locations, world.validator, world.mover(checkpoint = checkpoint, createLink = link), hooks, writerWaitMs = 2_000L,
        )

        fun move(checkpoint: (String) -> Unit = {}) =
            folders(checkpoint).move(setOf("film", "other", "ep"), destination.path.toString())

        fun check(stage: String) {
            originals.forEach { (path, bytes) ->
                val inPlace = Files.isRegularFile(path) && Files.readAllBytes(path).contentEquals(bytes)
                if (!inPlace && !world.trash.holds(bytes)) fail("$stage: $path is neither in place nor in the Trash")
            }
            listOf(1, 2).forEach { profile ->
                hooks.list(profile).forEach { record ->
                    val file = if (record.status == DownloadStatus.Completed) {
                        DownloadLocations.fileOfUri(record.localFileUri!!)!!.toPath()
                    } else {
                        world.locations.locationOfRecord(record.locationId, null).path.resolve(record.fileName + ".part")
                    }
                    if (!Files.isRegularFile(file)) fail("$stage: the record ${record.id} of profile $profile points at $file, which does not exist")
                    if (!Files.readAllBytes(file).contentEquals(contentOf.getValue(record.id))) fail("$stage: ${record.id} points at other content")
                }
            }
            listOf(world.appFolder, source.path, destination.path).forEach { folder ->
                assertEquals(emptyList(), world.tempsIn(folder), "$stage: a temporary file is left in $folder")
            }
            assertTrue(world.mover().unresolvedJournals().isEmpty(), "$stage: an unresolved journal blocks the next move")
            assertTrue(listOf("film", "other", "ep", "kept").none { ItemLocks.isHeld(it) || WriterTokens.kindOf(it) != null }, "$stage: a lock was kept")
        }
    }

    /** The steps a whole move passes, in order, from one run without a crash. */
    private fun steps(noLinks: Boolean): List<String> {
        val world = FolderWorld()
        try {
            val seen = mutableListOf<String>()
            val scene = Scene(world, noLinks)
            val report = scene.move { seen += it }
            assertEquals(3, report.moved, "the move without a crash did not move everything: $report")
            scene.check("no crash")
            return seen
        } finally {
            world.close()
        }
    }

    private fun matrix(noLinks: Boolean) {
        val steps = steps(noLinks)
        assertTrue(steps.size > 20, "too few steps: $steps")
        for (crashAt in steps.indices) {
            val world = FolderWorld()
            try {
                val scene = Scene(world, noLinks)
                var calls = 0
                val crashed = runCatching { scene.move { if (calls++ == crashAt) throw Crash("${steps[crashAt]} #$crashAt") } }.exceptionOrNull()
                assertTrue(crashed is Crash, "step $crashAt (${steps[crashAt]}) did not crash: $crashed")
                val stage = "crash at ${steps[crashAt]} (#$crashAt)"

                // The app starts again: recovery, which touches no user file.
                val recovered = scene.folders().recoverInterrupted()
                assertTrue(recovered == null || !recovered.busy, stage)
                scene.check(stage)

                // The user resolves every leftover from the settings.
                val folders = scene.folders()
                folders.refresh()
                folders.state.value.leftovers.forEach { leftover ->
                    assertEquals(LeftoverKind.Copy, leftover.kind, stage)
                    folders.removeExtraCopy(leftover.id)
                }
                scene.check("$stage, then Remove the extra copy")

                // And the move can be made again from where things are.
                folders.move(setOf("film", "other", "ep"), scene.destination.path.toString())
                scene.check("$stage, then the move again")
            } finally {
                world.close()
            }
        }
    }

    @Test
    fun a_crash_at_every_step_leaves_every_original_in_place_or_in_the_trash_and_every_record_on_an_existing_file() = matrix(noLinks = false)

    @Test
    fun the_same_on_a_volume_without_hard_links() = matrix(noLinks = true)
}
