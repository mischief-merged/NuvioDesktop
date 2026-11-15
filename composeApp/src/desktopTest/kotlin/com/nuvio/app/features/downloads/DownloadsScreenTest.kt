package com.nuvio.app.features.downloads

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_result_picture_ok
import nuvio.composeapp.generated.resources.downloads_status_auto
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test

/** The Downloads screen, driven by the real repository over an in-memory store. No network, no real folder, no real Trash. */
@OptIn(ExperimentalTestApi::class)
class DownloadsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    internal class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val started = mutableListOf<String>()

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
        ): DownloadsTaskHandle {
            started += request.item.id
            return object : DownloadsTaskHandle {
                override fun cancel() = Unit
            }
        }

        override fun removeFile(localFileUri: String?): Boolean = true
        override fun removePartialFile(destinationFileName: String): Boolean = true
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
    }

    private val backend = FakeBackend()
    private val realBackend = DownloadsRepository.backend

    @Before
    fun use_an_in_memory_store() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
    }

    @After
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
    }

    private fun text(resource: StringResource, vararg arguments: Any) = runBlocking { getString(resource, *arguments) }

    /** One stored download with the fields that have no default, plus [extra]. */
    private fun stored(
        id: String,
        status: String,
        title: String = "Pantheon",
        season: Int? = 2,
        episode: Int? = 3,
        extra: String = "",
        updatedAt: Long = System.currentTimeMillis(),
    ): String {
        val kind = if (season != null) {
            """"contentType":"series","parentMetaType":"series","seasonNumber":$season,"episodeNumber":$episode,"videoId":"tt1:$season:$episode","parentMetaId":"tt1","""
        } else {
            """"contentType":"movie","parentMetaType":"movie","videoId":"tt2","parentMetaId":"tt2","""
        }
        return """{"id":"$id",$kind"title":"$title","streamTitle":"1080p","providerName":"Addon",""" +
            """"sourceUrl":"https://example.com/$id.mkv","fileName":"$id.mkv","status":"$status",""" +
            """"createdAtEpochMs":1,"updatedAtEpochMs":$updatedAt$extra}"""
    }

    private fun show(vararg items: String) {
        backend.payload = """{"items":[${items.joinToString(",")}]}"""
        compose.setContent {
            NuvioTheme {
                DownloadsScreen(onBack = {}, onOpenDownload = {}, showAutoDownloads = false)
            }
        }
    }

    @Test
    fun a_download_waiting_on_a_silent_connection_says_it_is_reconnecting() {
        show(
            stored("a", "Downloading", extra = ""","downloadedBytes":10,"totalBytes":100""", updatedAt = 1),
        )

        compose.onNodeWithText(text(DownloadPhase.Reconnecting.message, "10 B / 100 B")).assertIsDisplayed()
    }

    @Test
    fun a_download_with_all_its_bytes_says_it_is_checking_the_file() {
        show(stored("a", "Downloading", extra = ""","downloadedBytes":100,"totalBytes":100""", updatedAt = 1))

        compose.onNodeWithText(text(DownloadPhase.CheckingFile.message)).assertIsDisplayed()
    }

    @Test
    fun a_download_stopped_by_the_storage_limit_says_so_and_is_marked_auto() {
        show(
            stored(
                "a", "Paused",
                extra = ""","downloadedBytes":10,"totalBytes":100,"autoDownloadRuleKey":"r","autoDownloadStop":"StorageLimit"""",
            ),
        )

        compose.onNodeWithText(
            text(Res.string.downloads_status_auto) + " • " + text(DownloadPhase.PausedStorageLimit.message, "10 B / 100 B"),
        ).assertIsDisplayed()
    }

    @Test
    fun a_finished_movie_says_what_it_was_checked_against() {
        show(
            stored(
                "m", "Completed", title = "Film", season = null, episode = null,
                extra = ""","localFileUri":"file:/m.mkv","downloadedBytes":100,"totalBytes":100,"verifiedBy":"Hash","framesCheck":"Passed"""",
            ),
        )

        compose.onNodeWithText(
            text(DownloadResult.VerifiedBySource.message) + " • " + text(Res.string.downloads_result_picture_ok),
        ).assertIsDisplayed()
    }

    @Test
    fun a_suspect_finished_movie_says_so() {
        show(
            stored(
                "m", "Completed", title = "Film", season = null, episode = null,
                extra = ""","localFileUri":"file:/m.mkv","downloadedBytes":100,"totalBytes":100,"verifiedBy":"Size","framesCheck":"Suspect"""",
            ),
        )

        compose.onNodeWithText(text(DownloadResult.Suspect.message)).assertIsDisplayed()
    }

    @Test
    fun a_download_from_before_checks_were_recorded_says_not_checked() {
        show(stored("m", "Completed", title = "Film", season = null, episode = null, extra = ""","localFileUri":"file:/m.mkv","downloadedBytes":100,"totalBytes":100"""))

        compose.onNodeWithText(text(DownloadResult.NotChecked.message)).assertIsDisplayed()
    }
}
