package com.nuvio.app.features.trakt

import com.nuvio.app.features.addons.RawHttpResponse

/** Why a Trakt write should be kept for later instead of being given up. */
internal data class TraktUndelivered(
    /** Trakt may have recorded the write although no success was seen. */
    val maybeDelivered: Boolean,
    val retryAfterMs: Long? = null,
)

/**
 * Classifies a failed Trakt write. A missing [response] is a transport failure. Returns null for an
 * answer that a later attempt would not change.
 */
internal fun traktUndeliveredOrNull(response: RawHttpResponse?): TraktUndelivered? = when {
    response == null -> TraktUndelivered(maybeDelivered = true)
    response.status == 429 -> TraktUndelivered(maybeDelivered = false, retryAfterMs = response.traktRetryAfterMs())
    response.status == 408 || response.status in 500..599 ->
        TraktUndelivered(maybeDelivered = true, retryAfterMs = response.traktRetryAfterMs())
    else -> null
}

/** The Retry-After header in its delay-seconds form. */
internal fun RawHttpResponse.traktRetryAfterMs(): Long? =
    headers.entries.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }
        ?.value?.trim()?.toLongOrNull()
        ?.takeIf { it in 0L..MAX_RETRY_AFTER_SECONDS }
        ?.times(1_000L)

private const val MAX_RETRY_AFTER_SECONDS = 86_400L
