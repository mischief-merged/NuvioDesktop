package com.nuvio.app.features.downloads

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_error_file_checksum
import nuvio.composeapp.generated.resources.downloads_error_frames_undecodable
import nuvio.composeapp.generated.resources.downloads_error_not_enough_space
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadFailuresTest {
    private fun text(resource: StringResource) = runBlocking { getString(resource) }

    private fun failed(kind: DownloadFailureKind?, message: String? = "Boom") = DownloadItem(
        id = "a", contentType = "series", parentMetaId = "tt1", parentMetaType = "series", videoId = "tt1:2:3",
        title = "Pantheon", seasonNumber = 2, episodeNumber = 3, streamTitle = "1080p", providerName = "Addon",
        sourceUrl = "https://example.com/a.mkv", fileName = "a.mkv", status = DownloadStatus.Failed,
        errorMessage = message, createdAtEpochMs = 1, updatedAtEpochMs = 2, failureKind = kind,
    )

    @Test
    fun the_messages_of_this_app_and_of_the_system_are_told_apart() {
        assertEquals(DownloadFailureKind.BadFile, classifyDownloadFailure(text(Res.string.downloads_error_file_checksum)))
        assertEquals(DownloadFailureKind.NotEnoughSpace, classifyDownloadFailure(text(Res.string.downloads_error_not_enough_space)))
        assertEquals(DownloadFailureKind.NotEnoughSpace, classifyDownloadFailure("No space left on device"))
        assertEquals(DownloadFailureKind.NotEnoughSpace, classifyDownloadFailure("There is not enough space on the disk"))
    }

    @Test
    fun a_refusal_by_the_source_is_not_a_reason_to_try_again_but_a_busy_server_is() {
        listOf(400, 401, 403, 404, 410).forEach { status ->
            assertEquals(DownloadFailureKind.SourceRefused, classifyDownloadFailure("Download failed with HTTP $status"), "$status")
        }
        listOf(408, 429, 500, 502, 503).forEach { status ->
            assertEquals(DownloadFailureKind.Other, classifyDownloadFailure("Download failed with HTTP $status"), "$status")
        }
        assertEquals(DownloadFailureKind.SourceRefused, classifyDownloadFailure("Request failed with HTTP 404"))
    }

    @Test
    fun a_lost_connection_is_worth_another_try() {
        assertEquals(DownloadFailureKind.Other, classifyDownloadFailure("Connection lost"))
        assertEquals(DownloadFailureKind.Other, classifyDownloadFailure("Connection stalled"))
        assertEquals(DownloadFailureKind.Other, classifyDownloadFailure(""))
    }

    @Test
    fun only_a_bad_file_or_a_refusal_needs_another_source() {
        assertTrue(failed(DownloadFailureKind.BadFile).needsAnotherSource)
        assertTrue(failed(DownloadFailureKind.SourceRefused).needsAnotherSource)
        assertFalse(failed(DownloadFailureKind.NotEnoughSpace).needsAnotherSource)
        assertFalse(failed(DownloadFailureKind.Other).needsAnotherSource)
        assertFalse(failed(null).needsAnotherSource)
        assertFalse(failed(DownloadFailureKind.BadFile).copy(status = DownloadStatus.Paused).needsAnotherSource)
    }

    @Test
    fun the_reason_is_plain_for_each_kind() {
        val space = failureReasonMessage(failed(DownloadFailureKind.NotEnoughSpace, "No space left on device"))
        assertEquals(text(Res.string.downloads_error_not_enough_space), (space as FailureText.Resource).resource.let(::text))

        val refused = failureReasonMessage(failed(DownloadFailureKind.SourceRefused, "Download failed with HTTP 404"))
        assertFalse(text((refused as FailureText.Resource).resource).contains("HTTP"))

        val bad = failureReasonMessage(failed(DownloadFailureKind.BadFile, "The downloaded video is blank"))
        assertEquals("The downloaded video is blank", (bad as FailureText.Literal).text)

        val none = failureReasonMessage(failed(null, message = null))
        assertTrue(none is FailureText.Resource)
        assertTrue(failureReasonMessage(failed(DownloadFailureKind.Other, "  ")) is FailureText.Resource)
    }

    @Test
    fun every_kind_has_its_own_next_step_and_none_uses_jargon() {
        val steps = DownloadFailureKind.entries.map { text(failureNextStepMessage(failed(it))) }
        assertEquals(steps.size, steps.toSet().size, "two kinds share a next step")
        val jargon = listOf("checksum", "decode", "digest", "hash", "codec", "http")
        (steps + text(failureNextStepMessage(failed(null)))).forEach { step ->
            jargon.forEach { term -> assertFalse(term in step.lowercase(), "'$term' is in \"$step\"") }
        }
    }

    @Test
    fun the_message_of_a_failed_file_check_is_plain() {
        val jargon = listOf("checksum", "decode", "digest", "hash", "codec")
        listOf(Res.string.downloads_error_file_checksum, Res.string.downloads_error_frames_undecodable).forEach { resource ->
            jargon.forEach { term -> assertFalse(term in text(resource).lowercase(), "'$term' is in ${text(resource)}") }
        }
    }

    // A list stored before failures were recorded must still read; one that this build wrote must read in an older build.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private val oldStoredFailure = """{"id":"a","contentType":"movie","parentMetaId":"tt1","parentMetaType":"movie","videoId":"tt1","title":"Film",""" +
        """"streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/a.mkv","fileName":"a.mkv",""" +
        """"status":"Failed","errorMessage":"Connection lost","createdAtEpochMs":1,"updatedAtEpochMs":2}"""

    @Test
    fun a_failed_download_stored_before_failures_were_recorded_still_reads() {
        val item = json.decodeFromString<DownloadItem>(oldStoredFailure)
        assertEquals(DownloadStatus.Failed, item.status)
        assertNull(item.failureKind)
        assertEquals("Connection lost", item.errorMessage)
        assertFalse(item.needsAnotherSource)
    }

    @Test
    fun a_failure_kind_that_a_later_build_adds_reads_as_unknown_and_not_as_a_lost_item() {
        val stored = oldStoredFailure.replace(""""status":"Failed",""", """"status":"Failed","failureKind":"SomethingNew",""")
        val item = json.decodeFromString<DownloadItem>(stored)
        assertEquals(DownloadStatus.Failed, item.status)
        assertNull(item.failureKind)
    }

    @Test
    fun a_recorded_failure_kind_survives_being_stored() {
        val item = failed(DownloadFailureKind.BadFile)
        assertEquals(DownloadFailureKind.BadFile, json.decodeFromString<DownloadItem>(json.encodeToString(DownloadItem.serializer(), item)).failureKind)
    }
}
