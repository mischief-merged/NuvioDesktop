package com.nuvio.app.features.downloads

import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_error_file_checksum
import nuvio.composeapp.generated.resources.downloads_error_not_enough_space
import nuvio.composeapp.generated.resources.downloads_failed_next_bad_file
import nuvio.composeapp.generated.resources.downloads_failed_next_not_enough_space
import nuvio.composeapp.generated.resources.downloads_failed_next_other
import nuvio.composeapp.generated.resources.downloads_failed_next_source_refused
import nuvio.composeapp.generated.resources.downloads_failed_reason_other
import nuvio.composeapp.generated.resources.downloads_failed_reason_source_refused
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

private val HttpStatus = Regex("""HTTP\s+(\d{3})""")

private val NoSpacePhrases = listOf("no space left", "not enough space", "not enough room", "disk full", "disk is full")

/**
 * What kind of failure a downloader's message reports. The downloaders report only text, so the
 * kind is read from it: the messages of this app by their wording, the ones of the system by the
 * phrases they use.
 */
internal fun classifyDownloadFailure(message: String): DownloadFailureKind {
    val text = message.trim()
    val lower = text.lowercase()
    if (text == runBlocking { getString(Res.string.downloads_error_file_checksum) }) return DownloadFailureKind.BadFile
    if (text == runBlocking { getString(Res.string.downloads_error_not_enough_space) } || NoSpacePhrases.any { it in lower }) {
        return DownloadFailureKind.NotEnoughSpace
    }
    val status = HttpStatus.find(text)?.groupValues?.get(1)?.toIntOrNull()
    // 408 and 429 ask to try again later, so they are not a refusal.
    if (status != null && status in 400..499 && status != 408 && status != 429) return DownloadFailureKind.SourceRefused
    return DownloadFailureKind.Other
}

/** What a failed download says went wrong, in plain words. */
internal fun failureReasonMessage(item: DownloadItem): FailureText =
    when (item.failureKind) {
        DownloadFailureKind.NotEnoughSpace -> FailureText.Resource(Res.string.downloads_error_not_enough_space)
        DownloadFailureKind.SourceRefused -> FailureText.Resource(Res.string.downloads_failed_reason_source_refused)
        // BadFile and Other keep what the repository wrote when it failed: a plain sentence.
        DownloadFailureKind.BadFile, DownloadFailureKind.Other, null ->
            item.errorMessage?.takeIf { it.isNotBlank() }?.let { FailureText.Literal(it) }
                ?: FailureText.Resource(Res.string.downloads_failed_reason_other)
    }

/** What a failed download suggests doing next. */
internal fun failureNextStepMessage(item: DownloadItem): StringResource =
    when (item.failureKind) {
        DownloadFailureKind.NotEnoughSpace -> Res.string.downloads_failed_next_not_enough_space
        DownloadFailureKind.SourceRefused -> Res.string.downloads_failed_next_source_refused
        DownloadFailureKind.BadFile -> Res.string.downloads_failed_next_bad_file
        DownloadFailureKind.Other, null -> Res.string.downloads_failed_next_other
    }

internal sealed interface FailureText {
    class Resource(val resource: StringResource) : FailureText
    class Literal(val text: String) : FailureText
}
