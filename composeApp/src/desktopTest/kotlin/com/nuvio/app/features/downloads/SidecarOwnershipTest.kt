package com.nuvio.app.features.downloads

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review 5 follow-up, 2a: the app writes, replaces and removes only in a sidecar folder it made itself.
 * A folder of that name that was already there is left exactly as it is.
 */
class SidecarOwnershipTest {
    private val dir = Files.createTempDirectory("nuvio-sidecar").toRealPath()

    @AfterTest
    fun clean_up() {
        dir.toFile().deleteRecursively()
    }

    private val video = dir.resolve("Movie.mkv").also { Files.writeString(it, "video") }
    private val folder = dir.resolve("Movie.mkv.subtitles")
    private fun storage() = DownloadSubtitleStorage(video.toUri().toString())

    @Test
    fun a_folder_the_app_makes_is_marked_and_written() {
        storage().write("0.srt", "one")
        storage().write("0.srt", "two")

        assertEquals("two", Files.readString(folder.resolve("0.srt")))
        assertTrue(folder.resolve(SidecarOwnership.MarkerName).exists())
        assertFalse(storage().refused())
        assertTrue(Files.list(folder).use { s -> s.noneMatch { SidecarOwnership.isTemporaryName(it.fileName.toString()) } })
    }

    @Test
    fun a_users_own_folder_of_that_name_is_never_written_replaced_cleaned_or_removed() {
        folder.createDirectories()
        Files.writeString(folder.resolve("0.srt"), "the user's own subtitles")
        Files.writeString(folder.resolve("notes.tmp"), "the user's temp file")
        Files.writeString(folder.resolve("manifest.json"), "{ my own json }")

        assertTrue(storage().refused())
        assertFailsWith<java.io.IOException> { storage().write("0.srt", "replaced") }
        assertFailsWith<java.io.IOException> { storage().write(SkipTimesSidecar.FILE_NAME, "x") }
        storage().remove()

        assertEquals("the user's own subtitles", Files.readString(folder.resolve("0.srt")))
        assertEquals("the user's temp file", Files.readString(folder.resolve("notes.tmp")))
        assertEquals("{ my own json }", Files.readString(folder.resolve("manifest.json")))
        assertFalse(folder.resolve(SidecarOwnership.MarkerName).exists())
        assertFalse(folder.resolve(SkipTimesSidecar.FILE_NAME).exists())
    }

    /** Files exactly as this app writes them: made through its own writers for another video, then copied without the mark. */
    private fun ourFilesInto(target: java.nio.file.Path, subtitles: Map<String, String>) {
        val other = dir.resolve("Other.mkv").also { Files.writeString(it, "v") }
        val uri = other.toUri().toString()
        assertTrue(SkipTimesSidecar.write(uri, SavedSkipTimes(2L, listOf(com.nuvio.app.features.player.skip.SkipInterval(10.0, 90.0, "intro", "introdb")))))
        assertTrue(EpisodeListSidecar.write(uri, SavedEpisodes(fetchedAtEpochMs = 1L, showName = "Show", parentMetaId = "tt1", parentMetaType = "series")))
        val source = dir.resolve("Other.mkv.subtitles")
        target.createDirectories()
        listOf(SkipTimesSidecar.FILE_NAME, EpisodeListSidecar.FILE_NAME).forEach { Files.copy(source.resolve(it), target.resolve(it)) }
        Files.write(target.resolve("${ArtworkRole.Poster.fileStem}.jpg"), byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(200))
        Files.writeString(target.resolve("${ArtworkRole.Thumbnail.fileStem}.none"), """{"v":2,"reason":"refused","attempts":1,"at":5}""")
        if (subtitles.isNotEmpty()) {
            val tracks = subtitles.keys.joinToString(",") { """{"sourceUrl":"https://x/$it","fileName":"$it","language":"en"}""" }
            Files.writeString(target.resolve("manifest.json"), """{"complete":true,"tracks":[$tracks]}""")
            subtitles.forEach { (name, text) -> Files.writeString(target.resolve(name), text) }
        }
    }

    @Test
    fun a_folder_the_app_made_before_folders_were_marked_is_adopted_when_every_entry_is_ours_and_nothing_is_touched() {
        ourFilesInto(folder, mapOf("0.srt" to "en", "1.srt" to "fr"))
        val before = Files.list(folder).use { s -> s.toList() }.associate { it.fileName.toString() to Files.readAllBytes(it).toList() }

        assertFalse(storage().refused())
        assertTrue(SidecarOwnership.ensureOwned(folder, "Movie.mkv"))

        val after = Files.list(folder).use { s -> s.toList() }.filterNot { it.fileName.toString() == SidecarOwnership.MarkerName }
            .associate { it.fileName.toString() to Files.readAllBytes(it).toList() }
        assertEquals(before, after, "adoption changed or removed something")
        storage().write("2.srt", "de")
        assertEquals("de", Files.readString(folder.resolve("2.srt")))
    }

    @Test
    fun an_empty_folder_and_a_folder_with_only_artwork_markers_are_adopted() {
        folder.createDirectories()
        assertFalse(storage().refused())
        Files.writeString(folder.resolve("${ArtworkRole.Poster.fileStem}.none"), "12345")
        assertFalse(storage().refused())
    }

    @Test
    fun a_users_temporary_looking_file_is_never_adopted_or_removed() {
        folder.createDirectories()
        Files.writeString(folder.resolve("draft.srt.tmp"), "the user's draft")

        assertTrue(storage().refused())
        assertFailsWith<java.io.IOException> { storage().write("0.srt", "x") }

        assertEquals("the user's draft", Files.readString(folder.resolve("draft.srt.tmp")))
        assertFalse(folder.resolve(SidecarOwnership.MarkerName).exists())
    }

    @Test
    fun recognised_names_with_contents_that_are_not_ours_are_not_adopted() {
        listOf(
            SkipTimesSidecar.FILE_NAME to "my notes",
            EpisodeListSidecar.FILE_NAME to "{}",
            "${ArtworkRole.Poster.fileStem}.jpg" to "not an image",
            "${ArtworkRole.Poster.fileStem}.none" to "why I removed it",
            "manifest.json" to "{ my json }",
        ).forEach { (name, text) ->
            folder.toFile().deleteRecursively()
            folder.createDirectories()
            Files.writeString(folder.resolve(name), text)
            assertTrue(storage().refused(), "$name with foreign contents was taken for ours")
            assertEquals(text, Files.readString(folder.resolve(name)))
        }
    }

    @Test
    fun a_folder_with_an_unknown_file_or_a_manifest_that_does_not_match_is_not_adopted() {
        ourFilesInto(folder, mapOf("0.srt" to "en"))
        Files.writeString(folder.resolve("holiday.jpg"), "a photo of the user's")
        assertTrue(storage().refused())

        Files.delete(folder.resolve("holiday.jpg"))
        Files.writeString(folder.resolve("1.srt"), "a subtitle the manifest does not list")
        assertTrue(storage().refused())
        Files.delete(folder.resolve("1.srt"))
        Files.delete(folder.resolve("0.srt"))
        assertTrue(storage().refused(), "a manifest listing a file that is not there was taken for ours")
        assertFailsWith<java.io.IOException> { storage().write("0.srt", "replaced") }
    }

    @Test
    fun only_the_apps_reserved_temporary_names_are_ever_cleaned_up_and_only_in_a_marked_folder() {
        storage().write("0.srt", "one")
        val ours = folder.resolve(SidecarOwnership.newTemporaryName()).also { Files.writeString(it, "left by a crash") }
        val notOurs = folder.resolve("notes.tmp").also { Files.writeString(it, "the user's") }

        storage().write("0.srt", "two")

        assertFalse(ours.exists())
        assertEquals("the user's", Files.readString(notOurs))
        assertFalse(SidecarOwnership.isTemporaryName("x.srt.tmp"))
    }

    @Test
    fun a_link_in_place_of_the_folder_or_the_marker_is_never_followed() {
        val elsewhere = dir.resolve("elsewhere").createDirectories()
        Files.createSymbolicLink(folder, elsewhere)

        assertTrue(storage().refused())
        assertFailsWith<java.io.IOException> { storage().write("0.srt", "x") }
        assertEquals(0L, Files.list(elsewhere).use { it.count() })
    }
}
