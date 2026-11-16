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
        assertTrue(Files.list(folder).use { s -> s.noneMatch { it.fileName.toString().endsWith(".tmp") } })
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

    @Test
    fun a_folder_the_app_made_before_folders_were_marked_is_adopted_when_its_contents_are_exactly_ours() {
        folder.createDirectories()
        Files.writeString(folder.resolve("manifest.json"), """{"complete":true,"tracks":[{"sourceUrl":"https://x/a.srt","fileName":"0.srt","language":"en"},{"sourceUrl":"https://x/b.srt","fileName":"1.srt","language":"fr"}]}""")
        Files.writeString(folder.resolve("0.srt"), "en")
        Files.writeString(folder.resolve("1.srt"), "fr")
        Files.writeString(folder.resolve(SkipTimesSidecar.FILE_NAME), "{}")
        Files.writeString(folder.resolve(EpisodeListSidecar.FILE_NAME), "{}")
        Files.write(folder.resolve("${ArtworkRole.Poster.fileStem}.jpg"), byteArrayOf(1, 2, 3))

        assertFalse(storage().refused())
        storage().write("2.srt", "de")

        assertTrue(folder.resolve(SidecarOwnership.MarkerName).exists())
        assertEquals("de", Files.readString(folder.resolve("2.srt")))
        assertEquals("en", Files.readString(folder.resolve("0.srt")))
    }

    @Test
    fun a_folder_with_an_unknown_file_or_a_manifest_that_does_not_match_is_not_adopted() {
        folder.createDirectories()
        Files.writeString(folder.resolve("manifest.json"), """{"complete":true,"tracks":[{"sourceUrl":"https://x/a.srt","fileName":"0.srt","language":"en"}]}""")
        Files.writeString(folder.resolve("0.srt"), "en")
        Files.writeString(folder.resolve("holiday.jpg"), "a photo of the user's")
        assertTrue(storage().refused())

        Files.delete(folder.resolve("holiday.jpg"))
        Files.writeString(folder.resolve("1.srt"), "a subtitle the manifest does not list")
        assertTrue(storage().refused())
        assertFailsWith<java.io.IOException> { storage().write("0.srt", "replaced") }
        assertEquals("en", Files.readString(folder.resolve("0.srt")))
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
