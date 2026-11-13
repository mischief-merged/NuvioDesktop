package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Looks at real files with the player library the application ships, and checks the order of the
 * routes. The real-file tests are skipped where that library cannot be loaded or ffmpeg, which makes
 * the files, is not installed.
 */
class DesktopBuiltInFramesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val savedBuiltIn = builtInFrameLook
    private val savedExternal = externalFrameLook

    @After
    fun restoreRoutes() {
        builtInFrameLook = savedBuiltIn
        externalFrameLook = savedExternal
    }

    private fun bridgeAvailable(): Boolean = DesktopHostOs.current == DesktopHostOs.MACOS &&
        runCatching { NativePlayerBridge.ensureLoaded() }.isSuccess

    private fun makeVideo(name: String, vararg arguments: String): File {
        assumeTrue("the bundled player library cannot be loaded here", bridgeAvailable())
        val ffmpeg = File("/opt/homebrew/bin/ffmpeg").takeIf { it.canExecute() } ?: findFfmpegTool("ffmpeg")
        assumeTrue("ffmpeg is not installed", ffmpeg != null)
        val file = File(folder.root, name)
        val process = ProcessBuilder(listOf(ffmpeg!!.path, "-v", "error", "-nostdin", "-y") + arguments + file.path)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        assumeTrue("ffmpeg could not make the test video: $output", process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0)
        return file
    }

    /** Counts the asks of the external route, which finds nothing: a test of the built-in route must see none. */
    private fun builtInOnly(): AtomicInteger {
        val externalCalls = AtomicInteger()
        externalFrameLook = { _, _, _ -> externalCalls.incrementAndGet(); null }
        return externalCalls
    }

    private fun confirmed(file: File, first: FrameSamples) =
        confirmedFramesProblem(first) { sampleConfirmingDownloadedFrames(file.absolutePath, first.durationMs) }

    private val healthyArguments = arrayOf(
        "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=10:duration=60",
        "-c:v", "libx264", "-preset", "ultrafast", "-g", "20", "-pix_fmt", "yuv420p",
    )

    @Test
    fun a_video_with_motion_has_decoded_frames_at_every_position_and_is_healthy() {
        val file = makeVideo("healthy.mkv", *healthyArguments)
        val externalCalls = builtInOnly()

        val samples = sampleDownloadedFrames(file.absolutePath)

        assertNotNull(samples)
        assertEquals(FrameSamplePositions.size, samples.samples.size)
        assertTrue(samples.samples.all { it.pixels?.size == FramePixelCount })
        assertTrue(samples.durationMs in 59_000..61_000, "duration ${samples.durationMs}")
        assertEquals(FrameSamplePositions.map { it.second }, samples.samples.map { it.window })
        assertNull(samples.problem())
        assertNull(confirmed(file, samples).problem)
        assertEquals(0, externalCalls.get())
    }

    @Test
    fun every_position_gives_its_own_picture() {
        val file = makeVideo("moving.mkv", *healthyArguments)
        builtInOnly()

        val samples = sampleDownloadedFrames(file.absolutePath)

        assertNotNull(samples)
        val pictures = samples.samples.map { it.pixels!!.toList() }
        assertEquals(pictures.size, pictures.toSet().size)
    }

    @Test
    fun a_title_the_container_states_is_read_and_held_against_the_episode() {
        val file = makeVideo("titled.mkv", *healthyArguments, "-metadata", "title=Pantheon.S01E02.1080p")
        builtInOnly()

        val samples = sampleDownloadedFrames(file.absolutePath)

        assertNotNull(samples)
        assertEquals("Pantheon.S01E02.1080p", samples.containerTitle)
        assertNull(samples.problem())
        assertTrue(samples.titleNamesAnotherEpisode(expectedSeason = 1, expectedEpisode = 1))
        assertFalse(samples.titleNamesAnotherEpisode(expectedSeason = 1, expectedEpisode = 2))
    }

    @Test
    fun a_file_name_is_not_taken_for_a_container_title() {
        val file = makeVideo("Show.S09E09.mkv", *healthyArguments)
        builtInOnly()

        val samples = sampleDownloadedFrames(file.absolutePath)

        assertNotNull(samples)
        assertNull(samples.containerTitle)
    }

    @Test
    fun one_picture_shown_for_the_whole_running_time_is_a_still_picture() {
        val file = makeVideo(
            "still.mkv",
            "-f", "lavfi", "-i", "smptebars=size=320x240:rate=10:duration=60",
            "-c:v", "libx264", "-preset", "ultrafast", "-g", "20", "-pix_fmt", "yuv420p",
        )
        builtInOnly()

        val samples = sampleDownloadedFrames(file.absolutePath)

        assertNotNull(samples)
        assertEquals(DownloadedFramesProblem.StillPicture, samples.problem())
        assertEquals(DownloadedFramesProblem.StillPicture, confirmed(file, samples).problem)
    }

    @Test
    fun a_black_video_is_blank() {
        val file = makeVideo(
            "black.mkv",
            "-f", "lavfi", "-i", "color=c=black:size=320x240:rate=10:duration=60",
            "-c:v", "libx264", "-preset", "ultrafast", "-g", "20", "-pix_fmt", "yuv420p",
        )
        builtInOnly()

        val samples = sampleDownloadedFrames(file.absolutePath)

        assertNotNull(samples)
        assertEquals(DownloadedFramesProblem.Blank, samples.problem())
        assertEquals(DownloadedFramesProblem.Blank, confirmed(file, samples).problem)
    }

    @Test
    fun a_flat_grey_wide_video_is_blank_and_not_lifted_by_borders() {
        val file = makeVideo(
            "grey.mkv",
            "-f", "lavfi", "-i", "color=c=gray:size=640x360:rate=10:duration=60",
            "-c:v", "libx264", "-preset", "ultrafast", "-g", "20", "-pix_fmt", "yuv420p",
        )
        builtInOnly()

        val samples = sampleDownloadedFrames(file.absolutePath)

        assertNotNull(samples)
        assertEquals(DownloadedFramesProblem.Blank, samples.problem())
    }

    @Test
    fun a_file_cut_off_at_forty_percent_does_not_decode_at_its_end() {
        val whole = makeVideo("whole.mkv", *healthyArguments)
        val cut = File(folder.root, "cut.mkv")
        cut.writeBytes(whole.readBytes().let { it.copyOf((it.size * 0.4).toInt()) })
        builtInOnly()

        val samples = sampleDownloadedFrames(cut.absolutePath)

        assertNotNull(samples)
        assertEquals(DownloadedFramesProblem.BrokenEnd, samples.problem())
    }

    @Test
    fun something_that_is_not_a_video_cannot_be_looked_at() {
        assumeTrue("the bundled player library cannot be loaded here", bridgeAvailable())
        val file = folder.newFile("page.mkv").also { it.writeText("<html>not a video</html>") }
        val externalCalls = builtInOnly()

        assertNull(sampleDownloadedFrames(file.absolutePath))
        // The built-in route found nothing it could read, so the external one was asked too.
        assertEquals(1, externalCalls.get())
    }

    @Test
    fun a_file_that_is_not_there_cannot_be_looked_at_by_either_route() {
        val externalCalls = AtomicInteger()
        val builtInCalls = AtomicInteger()
        builtInFrameLook = { _, _, _ -> builtInCalls.incrementAndGet(); null }
        externalFrameLook = { _, _, _ -> externalCalls.incrementAndGet(); null }

        assertNull(sampleDownloadedFrames(File(folder.root, "nothing.mkv").absolutePath))
        assertEquals(0, builtInCalls.get())
        assertEquals(0, externalCalls.get())
    }

    @Test
    fun when_the_built_in_route_gives_frames_the_external_tools_are_not_run() {
        val file = folder.newFile("any.mkv").also { it.writeText("x") }
        val given = FrameSamples(60_000, emptyList(), "built in")
        val externalCalls = AtomicInteger()
        builtInFrameLook = { _, _, _ -> given }
        externalFrameLook = { _, _, _ -> externalCalls.incrementAndGet(); null }

        assertSame(given, sampleDownloadedFrames(file.absolutePath))
        assertSame(given, sampleConfirmingDownloadedFrames(file.absolutePath, 60_000))
        assertEquals(0, externalCalls.get())
    }

    @Test
    fun when_the_built_in_route_is_not_there_or_fails_the_external_route_is_used() {
        val file = folder.newFile("any.mkv").also { it.writeText("x") }
        val fallback = FrameSamples(61_000, emptyList(), "external")
        externalFrameLook = { _, _, _ -> fallback }

        builtInFrameLook = { _, _, _ -> null }
        assertSame(fallback, sampleDownloadedFrames(file.absolutePath))
        assertSame(fallback, sampleConfirmingDownloadedFrames(file.absolutePath, 61_000))

        builtInFrameLook = { _, _, _ -> throw UnsatisfiedLinkError("no library") }
        assertSame(fallback, sampleDownloadedFrames(file.absolutePath))
    }

    @Test
    fun when_neither_route_can_look_there_are_no_frames_and_no_fault() {
        val file = folder.newFile("any.mkv").also { it.writeText("x") }
        builtInFrameLook = { _, _, _ -> null }
        externalFrameLook = { _, _, _ -> throw IllegalStateException("tool failed") }

        assertNull(sampleDownloadedFrames(file.absolutePath))
        assertNull(sampleConfirmingDownloadedFrames(file.absolutePath, 60_000))
    }

    @Test
    fun the_routes_are_asked_for_the_positions_and_the_duration_that_were_given() {
        val file = folder.newFile("any.mkv").also { it.writeText("x") }
        val asked = mutableListOf<Pair<List<Pair<Double, FrameWindow>>, Long?>>()
        builtInFrameLook = { _, positions, known -> asked += positions to known; null }
        externalFrameLook = { _, _, _ -> null }

        sampleDownloadedFrames(file.absolutePath)
        sampleConfirmingDownloadedFrames(file.absolutePath, 90_000)

        assertEquals(FrameSamplePositions to null, asked[0])
        assertEquals(ConfirmingFramePositions.map { it to FrameWindow.Middle } to 90_000L, asked[1])
    }

    @Test
    fun the_confirming_frames_of_a_real_file_use_the_duration_that_was_given() {
        val file = makeVideo("confirm.mkv", *healthyArguments)
        val externalCalls = builtInOnly()

        val samples = sampleConfirmingDownloadedFrames(file.absolutePath, 60_000)

        assertNotNull(samples)
        assertEquals(60_000, samples.durationMs)
        assertEquals(ConfirmingFramePositions.size, samples.samples.size)
        assertTrue(samples.samples.all { it.window == FrameWindow.Middle && it.pixels?.size == FramePixelCount })
        assertEquals(0, externalCalls.get())
    }
}
