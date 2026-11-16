package com.nuvio.app.features.details

import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsScreenTest
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedDetailsFallbackTest {
    private val backend = DownloadsScreenTest.FakeBackend()
    private val realBackend = DownloadsRepository.backend

    @BeforeTest
    fun use_an_in_memory_store() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
    }

    @AfterTest
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
    }

    @Test
    fun saved_details_stand_in_when_online_but_the_servers_cannot_be_reached_and_the_load_failed() {
        assertTrue(savedDetailsWanted(detailsLoaded = false, loadError = "failed", condition = NetworkCondition.ServersUnreachable))
        assertTrue(savedDetailsWanted(detailsLoaded = false, loadError = null, condition = NetworkCondition.ServersUnreachable))
    }

    @Test
    fun saved_details_stand_in_when_fully_offline_or_when_a_load_failed_for_any_reason() {
        assertTrue(savedDetailsWanted(false, null, NetworkCondition.NoInternet))
        assertTrue(savedDetailsWanted(false, "failed", NetworkCondition.Online))
        assertTrue(savedDetailsWanted(false, "failed", NetworkCondition.Unknown))
    }

    @Test
    fun saved_details_are_not_used_when_the_real_ones_are_there_or_nothing_is_wrong() {
        assertFalse(savedDetailsWanted(true, null, NetworkCondition.ServersUnreachable))
        assertFalse(savedDetailsWanted(true, "failed", NetworkCondition.NoInternet))
        assertFalse(savedDetailsWanted(false, null, NetworkCondition.Online))
        assertFalse(savedDetailsWanted(false, null, NetworkCondition.Checking))
    }

    private fun record(id: String, season: Int?, episode: Int?, uri: String) =
        """{"id":"$id","contentType":"${if (season == null) "movie" else "series"}","parentMetaType":"${if (season == null) "movie" else "series"}",""" +
            """"videoId":"${if (season == null) "tt1" else "tt1:$season:$episode"}","parentMetaId":"tt1","title":"Pantheon",""" +
            (if (season != null) """"seasonNumber":$season,"episodeNumber":$episode,"episodeTitle":"Ep $episode",""" else "") +
            """"streamTitle":"1080p","providerName":"A","sourceUrl":"https://example.com/$id.mkv","fileName":"$id.mkv",""" +
            """"localFileUri":"$uri","status":"Completed","downloadedBytes":100,"totalBytes":100,"createdAtEpochMs":1,"updatedAtEpochMs":1}"""

    @Test
    fun a_download_made_before_saved_details_existed_still_gives_a_page_from_its_record_alone() = runBlocking {
        // No episodes.json beside the file, and the list of downloads has not been read yet.
        backend.payload = """{"items":[${record("a", 2, 3, "file:/no/such/folder/a.mkv")}]}"""

        val meta = SavedDetails.metaFor("series", "tt1")

        assertNotNull(meta)
        assertEquals("Pantheon", meta.name)
        val video = meta.videos.single()
        assertEquals(2 to 3, video.season!! to video.episode!!)
        assertEquals("Ep 3", video.title)
        assertTrue(video.available, "the downloaded episode is offered to play")
        assertEquals(null, DownloadsRepository.uiState.value.items.firstOrNull { it.id == "zzz" })
    }

    @Test
    fun a_marked_placeholder_does_not_make_a_title_look_downloaded() = runBlocking {
        backend.payload = """{"items":[${record("a", null, null, "file:/no/such/a.mkv").replace("\"status\"", "\"framesCheck\":\"NotTheRealVideo\",\"status\"")}]}"""

        assertNull(SavedDetails.metaFor("movie", "tt1"))
    }
}
