package com.nuvio.app.features.downloads

import org.junit.After
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Looks at real files made with ffmpeg through the external ffmpeg route, which is what the
 * application falls back to; the built-in route has its own tests. Skipped where ffmpeg and
 * ffprobe are not installed.
 */
class DesktopDownloadedFramesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val savedBuiltIn = builtInFrameLook

    @Before
    fun useTheExternalRouteOnly() {
        builtInFrameLook = { _, _, _ -> null }
    }

    @After
    fun restoreTheBuiltInRoute() {
        builtInFrameLook = savedBuiltIn
    }

    private val ffmpeg: File? = findFfmpegTool("ffmpeg")

    private fun makeVideo(name: String, vararg arguments: String): File {
        assumeNotNull(ffmpeg)
        assumeNotNull(findFfmpegTool("ffprobe"))
        val file = File(folder.root, name)
        val process = ProcessBuilder(listOf(ffmpeg!!.path, "-v", "error", "-nostdin", "-y") + arguments + file.path)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        assumeTrue("ffmpeg could not make the test video: $output", process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0)
        return file
    }

    private fun samplesOf(file: File) = sampleDownloadedFrames(file.absolutePath)

    private fun confirmed(file: File, first: FrameSamples) =
        confirmedFramesProblem(first) { sampleConfirmingDownloadedFrames(file.absolutePath, first.durationMs) }

    private val healthyArguments = arrayOf(
        "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=10:duration=60",
        "-c:v", "libx264", "-preset", "ultrafast", "-g", "20", "-pix_fmt", "yuv420p",
    )

    @Test
    fun a_video_with_motion_has_decoded_frames_at_every_position_and_is_healthy() {
        val file = makeVideo("healthy.mkv", *healthyArguments)

        val samples = samplesOf(file)

        assertNotNull(samples)
        assertEquals(FrameSamplePositions.size, samples.samples.size)
        assertTrue(samples.samples.all { it.pixels?.size == FramePixelCount })
        assertTrue(samples.durationMs in 59_000..61_000, "duration ${samples.durationMs}")
        assertNull(samples.problem())
        assertNull(confirmed(file, samples).problem)
    }

    @Test
    fun a_title_the_container_states_is_read_and_held_against_the_episode() {
        val file = makeVideo("titled.mkv", *healthyArguments, "-metadata", "title=Pantheon.S01E02.1080p")

        val samples = samplesOf(file)

        assertNotNull(samples)
        assertEquals("Pantheon.S01E02.1080p", samples.containerTitle)
        assertNull(samples.problem())
        assertTrue(samples.titleNamesAnotherEpisode(expectedSeason = 1, expectedEpisode = 1))
        assertFalse(samples.titleNamesAnotherEpisode(expectedSeason = 1, expectedEpisode = 2))
    }

    @Test
    fun one_picture_shown_for_the_whole_running_time_is_a_still_picture() {
        val file = makeVideo(
            "still.mkv",
            "-f", "lavfi", "-i", "smptebars=size=320x240:rate=10:duration=60",
            "-c:v", "libx264", "-preset", "ultrafast", "-g", "20", "-pix_fmt", "yuv420p",
        )

        val samples = samplesOf(file)

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

        val samples = samplesOf(file)

        assertNotNull(samples)
        assertEquals(DownloadedFramesProblem.Blank, samples.problem())
        assertEquals(DownloadedFramesProblem.Blank, confirmed(file, samples).problem)
    }

    @Test
    fun a_file_cut_off_at_forty_percent_does_not_decode_at_its_end() {
        val whole = makeVideo("whole.mkv", *healthyArguments)
        val cut = File(folder.root, "cut.mkv")
        cut.writeBytes(whole.readBytes().let { it.copyOf((it.size * 0.4).toInt()) })

        val samples = samplesOf(cut)

        // Matroska states its duration in the header, so the cut file can still be looked at: the
        // start decodes and the end does not.
        assertNotNull(samples)
        assertEquals(DownloadedFramesProblem.BrokenEnd, samples.problem())
    }

    @Test
    fun something_that_is_not_a_video_cannot_be_looked_at() {
        assumeNotNull(ffmpeg)
        val file = folder.newFile("page.mkv").also { it.writeText("<html>not a video</html>") }

        assertNull(samplesOf(file))
    }

    @Test
    fun a_file_that_is_not_there_cannot_be_looked_at() {
        assertNull(sampleDownloadedFrames(File(folder.root, "nothing.mkv").absolutePath))
    }

    @Test
    fun the_tools_are_looked_for_in_the_property_folder_first_and_not_found_in_an_empty_one() {
        assumeNotNull(ffmpeg)
        val empty = folder.newFolder("empty")

        assertEquals(ffmpeg!!.parentFile, findFfmpegTool("ffmpeg", property = ffmpeg.parent, environment = empty.path, path = "")!!.parentFile)
        assertNull(findFfmpegTool("ffmpeg-that-does-not-exist", property = empty.path, environment = null, path = ""))
    }
}
