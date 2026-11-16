package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.skip.SkipInterval
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The sidecar next to a video, in a temporary folder. No network. */
class SkipTimesSidecarTest {
    private lateinit var folder: File
    private lateinit var video: File
    private val uri get() = video.toURI().toString()
    private val sidecar get() = File(File(video.path + ".subtitles"), SkipTimesSidecar.FILE_NAME)

    @BeforeTest
    fun create_a_video_in_a_temporary_folder() {
        folder = Files.createTempDirectory("nuvio-skip-sidecar").toFile()
        video = File(folder, "Show.S01E01.mkv").also { it.writeText("video") }
    }

    @AfterTest
    fun remove_the_temporary_folder() {
        folder.deleteRecursively()
    }

    private val intro = SkipInterval(12.5, 95.0, "intro", "introdb")
    private val credits = SkipInterval(1300.0, Double.MAX_VALUE, "ed", "animeskip")

    private fun saved() = (SkipTimesSidecar.read(uri) as SavedSkipTimesRead.Saved).times

    @Test
    fun nothing_is_missing_when_no_file_was_written() {
        assertEquals(SavedSkipTimesRead.Missing, SkipTimesSidecar.read(uri))
    }

    @Test
    fun intervals_types_sources_and_time_survive_a_write_and_a_read() {
        assertTrue(SkipTimesSidecar.write(uri, SavedSkipTimes(1_700_000_000_000L, listOf(intro, credits))))

        assertEquals(SavedSkipTimes(1_700_000_000_000L, listOf(intro, credits)), saved())
        val text = sidecar.readText()
        assertTrue("\"version\":1" in text.replace(" ", ""))
        assertTrue("introdb" in text && "animeskip" in text && "\"type\"" in text)
    }

    @Test
    fun a_search_that_found_nothing_is_recorded_with_its_time() {
        SkipTimesSidecar.write(uri, SavedSkipTimes(5_000L, emptyList()))

        val times = saved()
        assertTrue(times.noneFound)
        assertEquals(5_000L, times.fetchedAtEpochMs)
    }

    @Test
    fun the_name_is_the_video_name_plus_dot_subtitles_with_a_fixed_file_inside() {
        SkipTimesSidecar.write(uri, SavedSkipTimes(2L, listOf(intro)))

        assertTrue(sidecar.isFile)
        assertEquals("Show.S01E01.mkv.subtitles", sidecar.parentFile.name)
        assertEquals("skip-times.json", sidecar.name)
        assertEquals(folder, sidecar.parentFile.parentFile)
    }

    @Test
    fun a_corrupt_file_is_ignored_and_may_be_replaced() {
        // A corrupt file in the app's own (marked) folder; in a folder that is not the app's, nothing is replaced.
        assertTrue(SidecarOwnership.ensureOwned(sidecar.parentFile.toPath(), sidecar.parentFile.name.removeSuffix(".subtitles")))
        sidecar.writeText("{ not json")
        assertEquals(SavedSkipTimesRead.Unusable, SkipTimesSidecar.read(uri))

        assertTrue(SkipTimesSidecar.write(uri, SavedSkipTimes(9L, listOf(intro))))
        assertEquals(listOf(intro), saved().intervals)
    }

    @Test
    fun a_file_of_the_wrong_shape_is_ignored() {
        sidecar.parentFile.mkdirs()
        for (text in listOf("[]", "\"x\"", "{}", "{\"version\":1}", "{\"version\":\"one\",\"fetchedAtEpochMs\":5}",
            "{\"version\":1,\"fetchedAtEpochMs\":5,\"intervals\":[{\"startTime\":\"a\"}]}", "{\"version\":0,\"fetchedAtEpochMs\":5}")) {
            sidecar.writeText(text)
            assertEquals(SavedSkipTimesRead.Unusable, SkipTimesSidecar.read(uri), text)
        }
    }

    @Test
    fun a_file_of_a_newer_version_is_ignored_and_never_overwritten() {
        sidecar.parentFile.mkdirs()
        val newer = "{\"version\":2,\"fetchedAtEpochMs\":5,\"something\":[1,2,3]}"
        sidecar.writeText(newer)

        assertEquals(SavedSkipTimesRead.NewerVersion, SkipTimesSidecar.read(uri))
        assertEquals(false, SkipTimesSidecar.write(uri, SavedSkipTimes(9L, listOf(intro))))
        assertEquals(newer, sidecar.readText())
    }

    @Test
    fun unknown_fields_in_this_version_are_ignored() {
        sidecar.parentFile.mkdirs()
        sidecar.writeText(
            "{\"version\":1,\"fetchedAtEpochMs\":7,\"extra\":true,\"intervals\":" +
                "[{\"startTime\":1.0,\"endTime\":2.0,\"type\":\"op\",\"provider\":\"aniskip\",\"more\":1}]}",
        )

        assertEquals(listOf(SkipInterval(1.0, 2.0, "op", "aniskip")), saved().intervals)
    }

    @Test
    fun an_interval_that_makes_no_sense_is_dropped_on_read() {
        sidecar.parentFile.mkdirs()
        sidecar.writeText(
            "{\"version\":1,\"fetchedAtEpochMs\":7,\"intervals\":[" +
                "{\"startTime\":-1.0,\"endTime\":2.0,\"type\":\"op\",\"provider\":\"a\"}," +
                "{\"startTime\":5.0,\"endTime\":5.0,\"type\":\"op\",\"provider\":\"a\"}," +
                "{\"startTime\":6.0,\"endTime\":9.0,\"type\":\"recap\",\"provider\":\"a\"}]}",
        )

        assertEquals(listOf(SkipInterval(6.0, 9.0, "recap", "a")), saved().intervals)
    }

    @Test
    fun an_address_that_is_not_a_file_reads_as_missing_and_writes_nothing() {
        assertEquals(SavedSkipTimesRead.Missing, SkipTimesSidecar.read("https://example.com/a.mkv"))
        assertEquals(false, SkipTimesSidecar.write("https://example.com/a.mkv", SavedSkipTimes(2L, listOf(intro))))
    }

    @Test
    fun the_sidecar_is_not_a_download_the_folder_listing_still_shows_only_the_video() {
        SkipTimesSidecar.write(uri, SavedSkipTimes(2L, listOf(intro)))

        assertEquals(setOf("Show.S01E01.mkv", "Show.S01E01.mkv.subtitles"), folder.list().orEmpty().toSet())
        assertIs<SavedSkipTimesRead.Saved>(SkipTimesSidecar.read(uri))
    }
}
