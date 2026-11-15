package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamLanguage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadedAudioTracksTest {
    private fun languagesOf(bytes: ByteArray): List<String>? =
        audioTrackLanguages(bytes.size.toLong()) { offset, length ->
            if (offset < 0 || offset >= bytes.size) null
            else bytes.copyOfRange(offset.toInt(), minOf(offset + length, bytes.size.toLong()).toInt())
        }

    @Test
    fun the_audio_languages_of_a_matroska_file_are_read_in_track_order() {
        val file = matroskaFile(listOf(matroskaVideo, matroskaAudio("lit"), matroskaAudio("eng")), mediaBytes = 4_096)

        assertEquals(listOf("lit", "eng"), languagesOf(file))
    }

    @Test
    fun a_matroska_track_is_read_by_its_newer_language_tag_when_it_has_one() {
        val file = matroskaFile(listOf(matroskaAudio("eng", languageBcp47 = "pt-BR")))

        assertEquals(listOf("pt-br"), languagesOf(file))
    }

    @Test
    fun a_matroska_track_without_a_language_or_marked_undetermined_reads_as_untagged() {
        val file = matroskaFile(listOf(matroskaAudio(null), matroskaAudio("und")))

        assertEquals(listOf("", ""), languagesOf(file))
    }

    @Test
    fun a_matroska_file_without_audio_tracks_reads_as_an_empty_list() {
        assertEquals(emptyList(), languagesOf(matroskaFile(listOf(matroskaVideo))))
    }

    @Test
    fun matroska_tracks_that_come_only_after_the_media_are_not_looked_for() {
        assertNull(languagesOf(matroskaFile(listOf(matroskaAudio("eng")), mediaBytes = 4_096, tracksFirst = false)))
    }

    @Test
    fun a_matroska_file_that_is_cut_off_inside_its_track_list_is_not_read() {
        val file = matroskaFile(listOf(matroskaAudio("rus"), matroskaAudio("eng")))

        assertNull(languagesOf(file.copyOf(file.size - 20)))
    }

    @Test
    fun a_matroska_track_list_with_an_entry_that_cannot_be_read_is_not_read_in_part() {
        val file = matroskaFile(listOf(matroskaAudio("spa"), matroskaAudio("eng")))
        val secondLanguage = file.indexOfLast { it == 0x9c.toByte() }
        // The size of the second track's language now reaches past its track entry.
        file[secondLanguage + 8] = 0x7f

        assertNull(languagesOf(file))
    }

    @Test
    fun an_mp4_file_with_a_track_whose_kind_cannot_be_told_is_not_read_in_part() {
        val file = mp4FileWithAudio(listOf("spa", "eng"))
        val lastHandler = file.indices.last { index -> index + 4 <= file.size && file.copyOfRange(index, index + 4).decodeToString() == "hdlr" }
        "free".encodeToByteArray().copyInto(file, lastHandler)

        assertNull(languagesOf(file))
    }

    @Test
    fun the_audio_languages_of_an_mp4_file_are_read_in_track_order_wherever_the_movie_header_is() {
        assertEquals(listOf("rus", "eng"), languagesOf(mp4FileWithAudio(listOf("rus", "eng"), mediaBytes = 2_048)))
        assertEquals(listOf("rus", "eng"), languagesOf(mp4FileWithAudio(listOf("rus", "eng"), mediaBytes = 2_048, movieHeaderFirst = false)))
    }

    @Test
    fun an_mp4_track_without_a_language_or_marked_undetermined_reads_as_untagged() {
        assertEquals(listOf("", ""), languagesOf(mp4FileWithAudio(listOf(null, "und"))))
    }

    @Test
    fun a_file_that_is_neither_kind_or_has_no_track_list_is_not_read() {
        assertNull(languagesOf("<html>not a video</html>".encodeToByteArray()))
        assertNull(languagesOf(mp4File(durationSeconds = 60).copyOf(24)))
        assertNull(languagesOf(ByteArray(0)))
    }

    @Test
    fun the_audio_languages_are_among_what_a_look_at_a_file_on_disk_tells() {
        val file = File.createTempFile("nuvio-audio-tracks", ".mkv")
        try {
            file.writeBytes(matroskaFile(listOf(matroskaVideo, matroskaAudio("lit"), matroskaAudio("eng")), mediaBytes = 1_024))

            assertEquals(listOf("lit", "eng"), inspectDownloadedFile(file.toURI().toString())?.audioLanguages)
        } finally {
            file.delete()
        }
    }

    @Test
    fun a_file_whose_tracks_all_state_another_language_lacks_the_chosen_one() {
        assertEquals(AudioTrackCheck.Missing, checkAudioTracks(listOf("rus"), DebridStreamLanguage.EN))
        assertEquals(AudioTrackCheck.Missing, checkAudioTracks(listOf("lit", "rus"), DebridStreamLanguage.EN))
    }

    @Test
    fun a_file_whose_first_track_is_in_the_chosen_language_is_fine_as_it_is() {
        assertEquals(AudioTrackCheck.First, checkAudioTracks(listOf("eng", "lit"), DebridStreamLanguage.EN))
        assertEquals(AudioTrackCheck.First, checkAudioTracks(listOf("en-us"), DebridStreamLanguage.EN))
        assertEquals(AudioTrackCheck.First, checkAudioTracks(listOf("fra"), DebridStreamLanguage.FR))
        assertEquals(AudioTrackCheck.First, checkAudioTracks(listOf("fre"), DebridStreamLanguage.FR))
    }

    @Test
    fun a_file_with_the_chosen_language_on_a_later_track_names_the_language_to_start_on() {
        assertEquals(AudioTrackCheck.Later("en"), checkAudioTracks(listOf("lit", "eng"), DebridStreamLanguage.EN))
        assertEquals(AudioTrackCheck.Later("es"), checkAudioTracks(listOf("eng", "spa"), DebridStreamLanguage.LA))
    }

    @Test
    fun nothing_is_held_against_a_file_when_a_track_is_untagged_or_the_tracks_or_the_choice_are_not_known() {
        assertEquals(AudioTrackCheck.Unknown, checkAudioTracks(listOf("rus", ""), DebridStreamLanguage.EN))
        assertEquals(AudioTrackCheck.Unknown, checkAudioTracks(listOf(""), DebridStreamLanguage.EN))
        assertEquals(AudioTrackCheck.Unknown, checkAudioTracks(null, DebridStreamLanguage.EN))
        assertEquals(AudioTrackCheck.Unknown, checkAudioTracks(emptyList(), DebridStreamLanguage.EN))
        assertEquals(AudioTrackCheck.Unknown, checkAudioTracks(listOf("rus"), null))
        assertEquals(AudioTrackCheck.Unknown, checkAudioTracks(listOf("rus"), DebridStreamLanguage.MULTI))
    }
}
