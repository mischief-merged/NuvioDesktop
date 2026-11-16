package com.nuvio.app.features.downloads

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedArtworkTest {
    private lateinit var folder: File
    private var now = 1_700_000_000_000L
    private val fetched = mutableListOf<String>()
    private var answer: (String) -> ByteArray? = { jpeg }
    private val realGuard = DownloadCompanionGuard.stillWanted
    private var wanted = true

    private companion object {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
    }

    @BeforeTest
    fun setUp() {
        folder = Files.createTempDirectory("nuvio-art").toFile()
        DownloadCompanionGuard.stillWanted = { wanted }
    }

    @AfterTest
    fun tearDown() {
        DownloadCompanionGuard.stillWanted = realGuard
        folder.deleteRecursively()
    }

    private fun companion() = ArtworkCompanion(fetchImage = { fetched += it; answer(it)?.let { bytes -> ImageFetch.Bytes(bytes) } ?: ImageFetch.Unreachable }, nowEpochMs = { now })
    private fun uriOf(name: String = "a.mkv") = File(folder, name).also { it.writeText("v") }.toURI().toString()
    private fun episode(uri: String?) = DownloadItem(
        id = "d1", contentType = "series", parentMetaId = "tt1", parentMetaType = "series", videoId = "tt1:1:1", title = "Show",
        poster = "https://img/poster.jpg", background = "https://img/bg.jpg", logo = "https://img/logo.png",
        seasonNumber = 1, episodeNumber = 1, episodeThumbnail = "https://img/thumb.jpg", streamTitle = "s", providerName = "p",
        sourceUrl = "https://example.com/a.mkv", localFileUri = uri, fileName = "a.mkv", status = DownloadStatus.Completed,
        createdAtEpochMs = 1, updatedAtEpochMs = 2,
    )

    private val dir get() = File(folder, "a.mkv.subtitles")

    @Test
    fun the_poster_and_thumbnail_are_saved_by_role_and_nothing_else_is_fetched() = runBlocking {
        val uri = uriOf()
        assertEquals(CompanionOutcome.Saved, companion().run(episode(uri), uri))

        assertEquals(listOf("https://img/poster.jpg", "https://img/thumb.jpg"), fetched)
        assertEquals(setOf("poster.jpg", "thumbnail.jpg"), dir.list().orEmpty().filterNot { it == SidecarOwnership.MarkerName }.toSet())
        assertContentEquals(jpeg, File(dir, "poster.jpg").readBytes())
    }

    @Test
    fun the_extension_comes_from_the_bytes() = runBlocking {
        val uri = uriOf()
        answer = { png }
        companion().run(episode(uri), uri)

        assertTrue(File(dir, "poster.png").isFile)
        assertNull(SavedArtwork.extensionOf(byteArrayOf(60, 104, 116, 109, 108)))
        assertEquals("webp", SavedArtwork.extensionOf("RIFF1234WEBPxx".encodeToByteArray()))
        assertEquals("gif", SavedArtwork.extensionOf("GIF89a".encodeToByteArray()))
    }

    @Test
    fun a_movie_saves_only_a_poster() = runBlocking {
        val uri = uriOf()
        companion().run(episode(uri).copy(contentType = "movie", seasonNumber = null, episodeNumber = null), uri)

        assertEquals(listOf("https://img/poster.jpg"), fetched)
    }

    @Test
    fun an_image_that_is_too_large_or_not_an_image_is_not_saved_and_not_asked_again_soon() = runBlocking {
        val uri = uriOf()
        answer = { url -> if (url.endsWith("poster.jpg")) ByteArray(SavedArtwork.MaxBytes + 1) { 1 } else "<html>".encodeToByteArray() }
        val companion = companion()

        assertEquals(CompanionOutcome.NothingFound, companion.run(episode(uri), uri))
        assertEquals(setOf("poster.none", "thumbnail.none"), dir.list().orEmpty().filterNot { it == SidecarOwnership.MarkerName }.toSet())
        assertFalse(companion.isDue(episode(uri), uri))

        now += SavedArtwork.RecheckAgeMs
        assertTrue(companion.isDue(episode(uri), uri))
    }

    @Test
    fun a_server_that_does_not_answer_saves_nothing_and_leaves_what_is_saved() = runBlocking {
        val uri = uriOf()
        companion().run(episode(uri), uri)
        File(dir, "thumbnail.jpg").delete()
        val poster = File(dir, "poster.jpg").readBytes()

        answer = { null }
        assertEquals(CompanionOutcome.Failed, companion().run(episode(uri), uri))

        assertContentEquals(poster, File(dir, "poster.jpg").readBytes())
        assertFalse(File(dir, "thumbnail.jpg").exists())
        // Nothing was answered, so it is marked as a failure to retry soon, not as a settled no.
        assertFalse(ArtworkMarker.read(uri, ArtworkRole.Thumbnail)!!.answered)
    }

    @Test
    fun saved_artwork_is_not_fetched_again() = runBlocking {
        val uri = uriOf()
        val companion = companion()
        companion.run(episode(uri), uri)
        fetched.clear()

        assertFalse(companion.isDue(episode(uri), uri))
        companion.run(episode(uri), uri)
        assertEquals(emptyList(), fetched)
    }

    @Test
    fun nothing_is_written_for_a_download_that_was_deleted_meanwhile() = runBlocking {
        val uri = uriOf()
        wanted = false
        assertEquals(CompanionOutcome.Failed, companion().run(episode(uri), uri))
        assertFalse(dir.exists())
    }

    @Test
    fun an_address_of_a_saved_poster_or_thumbnail_maps_to_its_file_and_others_do_not() = runBlocking {
        val uri = uriOf()
        companion().run(episode(uri), uri)
        val items = listOf(episode(uri))

        assertTrue(SavedArtwork.savedFileFor("https://img/poster.jpg", items)!!.endsWith("a.mkv.subtitles/poster.jpg"))
        assertTrue(SavedArtwork.savedFileFor("https://img/thumb.jpg", items)!!.endsWith("a.mkv.subtitles/thumbnail.jpg"))
        assertNull(SavedArtwork.savedFileFor("https://img/bg.jpg", items))
        assertNull(SavedArtwork.savedFileFor("https://img/other.jpg", items))
        assertNull(SavedArtwork.savedFileFor("https://img/poster.jpg", listOf(episode(uri).copy(status = DownloadStatus.Paused))))
    }

    @Test
    fun the_saved_file_is_served_first_offline_and_only_as_a_fallback_online() {
        assertEquals(ArtworkPlan.Network, SavedArtwork.plan(offline = true, savedFile = null))
        assertEquals(ArtworkPlan.SavedFirst, SavedArtwork.plan(offline = true, savedFile = "file:/x"))
        assertEquals(ArtworkPlan.NetworkThenSaved, SavedArtwork.plan(offline = false, savedFile = "file:/x"))
        assertEquals(ArtworkPlan.Network, SavedArtwork.plan(offline = false, savedFile = null))
    }

    @Test
    fun the_files_travel_with_the_video_in_its_sibling_folder_and_leave_no_temporary_file() = runBlocking {
        val uri = uriOf()
        companion().run(episode(uri), uri)

        assertEquals(setOf("a.mkv", "a.mkv.subtitles"), folder.list().orEmpty().filterNot { it == SidecarOwnership.MarkerName }.toSet())
        assertTrue(dir.list().orEmpty().none { it.endsWith(".tmp") })
    }

    @Test
    fun an_image_another_run_saved_while_this_one_fetched_is_not_replaced_or_duplicated() = runBlocking {
        val uri = uriOf()
        val companion = ArtworkCompanion(
            fetchImage = {
                // Another run saves the poster, as a different type, while this fetch is in flight.
                DownloadSubtitleStorage(uri).writeBytes("poster.png", png)
                ImageFetch.Bytes(jpeg)
            },
            nowEpochMs = { now },
        )

        companion.run(episode(uri).copy(episodeNumber = null, seasonNumber = null), uri)

        assertEquals(setOf("poster.png"), dir.list().orEmpty().filterNot { it == SidecarOwnership.MarkerName }.toSet())
        assertContentEquals(png, File(dir, "poster.png").readBytes())
    }
}
