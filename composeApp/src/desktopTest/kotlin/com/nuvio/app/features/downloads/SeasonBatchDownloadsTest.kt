package com.nuvio.app.features.downloads

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_season_finished
import org.jetbrains.compose.resources.getString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SeasonBatchDownloadsTest {
    /** Keeps the stored payload in memory and never touches a file or the network. */
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null

        override fun loadPayload(): String? = payload

        override fun savePayload(payload: String) {
            this.payload = payload
        }

        override fun restoreItem(item: DownloadItem): DownloadItem = item

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle = object : DownloadsTaskHandle {
            override fun cancel() = Unit
        }

        override fun removeFile(localFileUri: String?): Boolean = true

        override fun removePartialFile(destinationFileName: String): Boolean = true

        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
    }

    private val realBackend = DownloadsRepository.backend

    @BeforeTest
    fun use_an_empty_in_memory_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = FakeBackend()
        NuvioToastController.dismiss()
    }

    @AfterTest
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
    }

    @Test
    fun the_app_wiring_shows_the_summary_when_a_season_has_been_gone_through(): Unit = runBlocking {
        val showId = "wiring-test-${System.nanoTime()}"
        val episodes = (1..3).map { SeasonBatchEpisode("$showId:1:$it", 1, it, "Episode $it", null) }

        val start = SeasonBatchDownloads.start(
            SeasonBatchRequest(
                contentType = "series",
                parentMetaId = showId,
                parentMetaType = "series",
                title = "Show",
                logo = null,
                poster = null,
                background = null,
                seasonNumber = 1,
                episodes = episodes,
                quality = BatchQuality.BestAvailable,
            ),
        )

        assertEquals(SeasonBatchStart.Started, start)
        // Which outcome is counted depends on the sources the app can reach. That a summary is
        // shown for the season does not.
        val heading = getString(Res.string.downloads_season_finished)
        val toast = withTimeout(60_000) { NuvioToastController.currentToast.first { it?.message?.startsWith(heading) == true } }
        assertEquals(true, toast?.message?.startsWith(heading))
        withTimeout(30_000) { SeasonBatchDownloads.running.first { it.isEmpty() } }

        // The result also stays: the toast goes away, the summary on the Downloads screen does not.
        val profile = ProfileRepository.activeProfileId
        val key = seasonBatchKey(showId, 1)
        val record = withTimeout(30_000) {
            var found: BatchRunRecord? = null
            while (found == null) {
                found = BatchRunSummaries.load(profile).firstOrNull { it.key == key }
                if (found == null) delay(50)
            }
            found
        }
        BatchRunSummaries.dismiss(profile, key)
        assertEquals("Show", record.title)
        assertEquals(1, record.seasonNumber)
        assertEquals(3, record.added + record.alreadyThere + record.notFound + record.failed + record.paused)
        assertEquals(record.notFound + record.failed, record.problems.size)
    }
}
