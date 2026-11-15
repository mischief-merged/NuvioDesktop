package com.nuvio.app.features.downloads

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadedFileTest {
    private val folder: File = Files.createTempDirectory("nuvio-file-test").toFile()

    @AfterTest
    fun remove_the_temporary_folder() {
        folder.deleteRecursively()
    }

    private fun file(name: String, bytes: ByteArray) = File(folder, name).also { it.writeBytes(bytes) }

    private fun durationOf(bytes: ByteArray) = mp4DurationMs(bytes.size.toLong()) { offset, length ->
        if (offset >= bytes.size) null else bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + length).toInt())
    }

    @Test
    fun the_usual_video_containers_are_recognised_by_their_first_bytes() {
        val transportStream = ByteArray(376).also { it[0] = 0x47; it[188] = 0x47 }
        mapOf(
            "mp4" to mp4File(durationSeconds = 5),
            "mp4 that starts with its media" to mp4Box("mdat", ByteArray(16)),
            "matroska" to matroskaFile(16),
            "avi" to "RIFF".encodeToByteArray() + ByteArray(4) + "AVI LIST".encodeToByteArray(),
            "transport stream" to transportStream,
            "program stream" to byteArrayOf(0, 0, 1, 0xba.toByte(), 0, 0),
            "flash video" to "FLV".encodeToByteArray() + ByteArray(8),
            "ogg" to "OggS".encodeToByteArray() + ByteArray(8),
        ).forEach { (name, bytes) -> assertTrue(startsLikeVideoContainer(bytes), name) }
    }

    @Test
    fun an_error_page_some_text_and_an_empty_file_are_not_video() {
        listOf(
            "<!DOCTYPE html><html><body>File not found</body></html>".encodeToByteArray(),
            """{"error":"not cached"}""".encodeToByteArray(),
            "RIFF".encodeToByteArray() + ByteArray(4) + "WAVEfmt ".encodeToByteArray(),
            ByteArray(0),
            ByteArray(4),
        ).forEach { assertFalse(startsLikeVideoContainer(it), it.decodeToString()) }
    }

    @Test
    fun the_duration_of_an_mp4_is_read_from_its_movie_header_wherever_that_is() {
        assertEquals(5_000L, durationOf(mp4File(durationSeconds = 5)))
        assertEquals(2_700_000L, durationOf(mp4File(durationSeconds = 2_700, mediaBytes = 4_096, movieHeaderFirst = false)))
    }

    @Test
    fun a_movie_header_with_64_bit_times_is_read_too() {
        val header = ByteArray(4).also { it[0] = 1 } + ByteArray(16) + byteArrayOf(0, 0, 0x03, 0xe8.toByte()) + ByteArray(7) + byteArrayOf(10) + ByteArray(80)
        val file = mp4Box("ftyp", ByteArray(16)) + mp4Box("moov", mp4Box("mvhd", header))

        assertEquals(10L, durationOf(file))
    }

    @Test
    fun a_file_without_a_readable_movie_header_has_no_duration() {
        assertNull(durationOf(matroskaFile(64)))
        assertNull(durationOf(mp4Box("ftyp", ByteArray(16)) + mp4Box("mdat", ByteArray(64))))
        assertNull(durationOf(mp4Box("ftyp", ByteArray(16)) + mp4Box("moov", mp4Box("trak", ByteArray(32)))))
        assertNull(durationOf(mp4Box("ftyp", ByteArray(16)) + byteArrayOf(0, 0, 0, 2, 'm'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'v'.code.toByte())))
        assertNull(durationOf(mp4File(durationSeconds = 5).copyOf(40)))
        assertNull(durationOf(ByteArray(0)))
    }

    @Test
    fun a_file_on_disk_is_looked_at_without_reading_it_through() {
        val clip = file("clip.mp4", mp4File(durationSeconds = 4, mediaBytes = 70_000))

        val facts = inspectDownloadedFile(clip.toURI().toString())

        assertEquals(DownloadedFileFacts(exists = true, sizeBytes = clip.length(), isVideoContainer = true, durationMs = 4_000, audioLanguages = emptyList()), facts)
    }

    @Test
    fun a_matroska_file_is_a_video_without_a_known_duration() {
        val video = file("video.mkv", matroskaFile(1_000))

        assertEquals(DownloadedFileFacts(exists = true, sizeBytes = 1_004, isVideoContainer = true), inspectDownloadedFile(video.toURI().toString()))
    }

    @Test
    fun a_page_saved_under_a_video_name_is_not_a_video() {
        val page = file("episode.mp4", "<html>gone</html>".encodeToByteArray())

        assertEquals(false, inspectDownloadedFile(page.toURI().toString())?.isVideoContainer)
    }

    @Test
    fun a_file_that_is_not_there_and_a_folder_do_not_exist_as_downloads() {
        assertEquals(DownloadedFileFacts(exists = false), inspectDownloadedFile(File(folder, "gone.mkv").toURI().toString()))
        assertEquals(DownloadedFileFacts(exists = false), inspectDownloadedFile(folder.toURI().toString()))
    }

    @Test
    fun the_hash_of_a_file_is_its_size_plus_the_sums_of_its_first_and_last_64_kib() {
        val bytes = matroskaFile(mediaBytes = 200_000 - 4)
        bytes[199_992] = 5
        val video = file("video.mkv", bytes)
        // The first eight bytes read as a little-endian long, plus 5 in the last long, plus the size.
        val expected = "%016x".format(0xa3df451aL + 5L + 200_000L)

        assertEquals(expected, inspectDownloadedFile(video.toURI().toString())?.videoHash)
    }

    @Test
    fun a_file_shorter_than_two_chunks_has_no_hash() {
        val video = file("short.mkv", matroskaFile(mediaBytes = 100_000))

        assertNull(inspectDownloadedFile(video.toURI().toString())?.videoHash)
    }

    @Test
    fun a_file_whose_hash_differs_from_the_one_the_source_stated_is_not_the_download() {
        val video = DownloadedFileFacts(exists = true, sizeBytes = 1_000, isVideoContainer = true, videoHash = "00000000000abcde")

        assertNull(video.problem(announcedBytes = 1_000, expectedVideoHash = "00000000000ABCDE"))
        assertNull(video.problem(announcedBytes = 1_000, expectedVideoHash = null))
        assertNull(video.copy(videoHash = null).problem(announcedBytes = 1_000, expectedVideoHash = "00000000000abcde"))
        assertEquals(DownloadedFileProblem.ChecksumMismatch, video.problem(announcedBytes = 1_000, expectedVideoHash = "00000000000abcdf"))
    }

    @Test
    fun what_a_file_was_checked_against_is_the_strongest_check_that_was_possible() {
        val video = DownloadedFileFacts(exists = true, sizeBytes = 1_000, isVideoContainer = true, videoHash = "00000000000abcde")
        fun verification(announced: Long? = null, hash: String? = null, size: Long? = null, digest: Boolean = false, facts: DownloadedFileFacts = video) =
            facts.verification(announcedBytes = announced, expectedVideoHash = hash, expectedSizeBytes = size, digestVerified = digest)

        assertEquals(DownloadVerification.Hash, verification(announced = 1_000, hash = "00000000000abcde", digest = true))
        assertEquals(DownloadVerification.Digest, verification(announced = 1_000, digest = true))
        assertEquals(DownloadVerification.Digest, verification(hash = "00000000000abcde", digest = true, facts = video.copy(videoHash = null)))
        assertEquals(DownloadVerification.Size, verification(announced = 1_000))
        assertEquals(DownloadVerification.Size, verification(size = 1_000))
        assertEquals(DownloadVerification.None, verification(size = 999))
        assertEquals(DownloadVerification.None, verification())
    }

    @Test
    fun only_sixteen_hexadecimal_digits_are_a_video_hash() {
        assertEquals("00000000000abcde", " 00000000000ABCDE ".asVideoHash())
        assertNull("abcde".asVideoHash())
        assertNull("00000000000abcdg".asVideoHash())
        assertNull(null.asVideoHash())
    }

    @Test
    fun a_finished_file_must_be_there_have_the_announced_size_and_be_a_video() {
        val video = DownloadedFileFacts(exists = true, sizeBytes = 1_000, isVideoContainer = true)

        assertNull(video.problem(announcedBytes = 1_000))
        assertNull(video.problem(announcedBytes = null))
        assertNull(video.problem(announcedBytes = 0))
        assertEquals(DownloadedFileProblem.Incomplete, video.problem(announcedBytes = 1_001))
        assertEquals(DownloadedFileProblem.Incomplete, video.problem(announcedBytes = 999))
        assertEquals(DownloadedFileProblem.NotVideo, video.copy(isVideoContainer = false).problem(announcedBytes = 1_000))
        assertEquals(DownloadedFileProblem.Missing, DownloadedFileFacts(exists = false).problem(announcedBytes = null))
    }
}
