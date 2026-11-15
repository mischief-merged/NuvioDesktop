package com.nuvio.app.features.watching.sync

import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.outbox.OutboxEntry
import com.nuvio.app.features.tracking.outbox.OutboxMedia
import com.nuvio.app.features.tracking.outbox.OutboxMediaKind
import com.nuvio.app.features.tracking.outbox.OutboxSendOutcome
import com.nuvio.app.features.tracking.outbox.SameWatchWindowMs
import com.nuvio.app.features.tracking.outbox.TrackerOutboxAdapter
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import com.nuvio.app.features.trakt.traktRetryAfterMs
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

private const val BASE_URL = "https://api.trakt.tv"
private const val HISTORY_PAGE_SIZE = 100
private const val HISTORY_MAX_PAGES = 10

/**
 * Delivers queued watches to Trakt through `/sync/history`, which records the time the watch
 * happened. A replayed scrobble would be stamped with the time of the replay instead.
 */
internal class TraktOutboxAdapter(
    private val isOnline: () -> Boolean = { !NetworkStatusRepository.uiState.value.isOfflineLike },
    private val activeProfileId: () -> Int = { ProfileRepository.activeProfileId },
    private val accountKey: (profileId: Int) -> String? = TraktAuthRepository::connectedAccountKey,
    private val authorizedHeaders: suspend (profileId: Int) -> Map<String, String>? =
        { profileId -> TraktAuthRepository.authorizedHeaders(profileId) },
    private val request: suspend (method: String, url: String, headers: Map<String, String>, body: String) -> RawHttpResponse =
        { method, url, headers, body -> httpRequestRaw(method = method, url = url, headers = headers, body = body) },
) : TrackerOutboxAdapter {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /** Null while the device is known to be offline, so that no attempt is spent on a request that cannot leave it. */
    override fun connectedAccountKey(profileId: Int): String? =
        if (isOnline() && activeProfileId() == profileId) accountKey(profileId) else null

    override suspend fun send(batch: List<OutboxEntry>): OutboxSendOutcome {
        val first = batch.firstOrNull() ?: return OutboxSendOutcome.Delivered
        if (batch.any { it.profileId != first.profileId || it.accountKey != first.accountKey }) {
            return OutboxSendOutcome.Retry(retryAfterMs = null, maybeDelivered = false, reason = "mixed batch")
        }
        val headers = headersFor(first)
            ?: return OutboxSendOutcome.Retry(retryAfterMs = null, maybeDelivered = false, reason = "Trakt login unavailable")
        val response = try {
            request("POST", "$BASE_URL/sync/history", headers, json.encodeToString(buildHistoryAddRequest(batch)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return OutboxSendOutcome.Retry(retryAfterMs = null, maybeDelivered = true, reason = error.message ?: "transport failure")
        }
        return classify(response, batch.size)
    }

    /**
     * Reads the history around the watch time instead of asking for one title, so no stored id ever
     * becomes part of a URL.
     */
    override suspend fun isRecorded(entry: OutboxEntry): Boolean? {
        val headers = headersFor(entry) ?: return null
        val type = if (entry.media.kind == OutboxMediaKind.MOVIE) "movies" else "episodes"
        val startAt = TraktWatchedSyncAdapter.epochMsToIso(entry.watchedAtEpochMs - SameWatchWindowMs)
        val endAt = TraktWatchedSyncAdapter.epochMsToIso(entry.watchedAtEpochMs + SameWatchWindowMs + 1_000L)
        for (page in 1..HISTORY_MAX_PAGES) {
            val url = "$BASE_URL/sync/history/$type?start_at=$startAt&end_at=$endAt&page=$page&limit=$HISTORY_PAGE_SIZE"
            val response = try {
                request("GET", url, headers, "")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                return null
            }
            if (response.status !in 200..299) return null
            val items = runCatching { json.decodeFromString<List<TraktHistoryItemDto>>(response.body) }.getOrNull()
                ?: return null
            if (items.any { it.records(entry) }) return true
            if (items.size < HISTORY_PAGE_SIZE) return false
        }
        return null
    }

    internal fun classify(response: RawHttpResponse, batchSize: Int): OutboxSendOutcome {
        val status = response.status
        return when {
            status in 200..299 -> classifyAccepted(response.body, batchSize)
            status == 400 || status == 404 || status == 422 -> OutboxSendOutcome.Rejected("HTTP $status")
            else -> OutboxSendOutcome.Retry(
                retryAfterMs = response.traktRetryAfterMs(),
                maybeDelivered = status == 408 || status in 500..599,
                reason = "HTTP $status",
            )
        }
    }

    /**
     * A batch is delivered when Trakt reports every entry as added. One entry Trakt could not match is
     * rejected. Any other accepted answer (a partly matched batch, a body that cannot be read) does not
     * say what was recorded, so each entry is checked against the history and sent again alone.
     */
    private fun classifyAccepted(body: String, batchSize: Int): OutboxSendOutcome {
        val result = runCatching { json.decodeFromString<TraktHistoryAddResponseDto>(body) }.getOrNull()
        val added = result?.added?.let { (it.movies ?: 0) + (it.episodes ?: 0) } ?: 0
        val notFound = result?.notFound?.let {
            it.movies.orEmpty().size + it.shows.orEmpty().size + it.seasons.orEmpty().size + it.episodes.orEmpty().size
        } ?: 0
        return when {
            notFound == 0 && added >= batchSize -> OutboxSendOutcome.Delivered
            batchSize == 1 && added == 0 && notFound > 0 -> OutboxSendOutcome.Rejected("Trakt could not match the title")
            else -> OutboxSendOutcome.Retry(retryAfterMs = null, maybeDelivered = true, reason = "Trakt did not confirm the batch")
        }
    }

    internal fun buildHistoryAddRequest(batch: List<OutboxEntry>): TraktHistoryAddRequestDto {
        val movies = batch.filter { it.media.kind == OutboxMediaKind.MOVIE }.map { entry ->
            TraktHistoryMovieRequestDto(ids = entry.media.toIdsDto(), watchedAt = entry.watchedAtIso())
        }
        val shows = batch.filter { it.media.kind == OutboxMediaKind.EPISODE }
            .groupBy { it.media.toIdsDto() }
            .map { (ids, showEntries) ->
                TraktHistoryShowRequestDto(
                    ids = ids,
                    seasons = showEntries.groupBy { it.media.season ?: 0 }.map { (season, seasonEntries) ->
                        TraktHistorySeasonRequestDto(
                            number = season,
                            episodes = seasonEntries.map { entry ->
                                TraktHistoryEpisodeRequestDto(number = entry.media.number ?: 0, watchedAt = entry.watchedAtIso())
                            },
                        )
                    },
                )
            }
        return TraktHistoryAddRequestDto(
            movies = movies.takeIf { it.isNotEmpty() },
            shows = shows.takeIf { it.isNotEmpty() },
        )
    }

    /** Null unless the account the entry was queued under is still the connected one once the token is ready. */
    private suspend fun headersFor(entry: OutboxEntry): Map<String, String>? {
        if (connectedAccountKey(entry.profileId) != entry.accountKey) return null
        val headers = authorizedHeaders(entry.profileId) ?: return null
        if (connectedAccountKey(entry.profileId) != entry.accountKey) return null
        return mapOf("Accept" to "application/json", "Content-Type" to "application/json") + headers
    }

    private fun OutboxEntry.watchedAtIso(): String = TraktWatchedSyncAdapter.epochMsToIso(watchedAtEpochMs)

    private fun OutboxMedia.toIdsDto(): TraktSyncIdsDto = TraktSyncIdsDto(trakt = trakt, imdb = imdb, tmdb = tmdb)

    private fun TraktHistoryItemDto.records(entry: OutboxEntry): Boolean {
        val watchedAtEpochMs = watchedAt?.let(TraktPlatformClock::parseIsoDateTimeToEpochMs) ?: return false
        if (abs(watchedAtEpochMs - entry.watchedAtEpochMs) >= SameWatchWindowMs) return false
        return when (entry.media.kind) {
            OutboxMediaKind.MOVIE -> movie?.ids.matches(entry.media)
            OutboxMediaKind.EPISODE -> show?.ids.matches(entry.media) &&
                episode?.season == entry.media.season &&
                episode?.number == entry.media.number
        }
    }

    private fun TraktSyncIdsDto?.matches(media: OutboxMedia): Boolean =
        this != null && media.sharesAnyId(imdb = imdb, tmdb = tmdb, trakt = trakt)
}

@Serializable
private data class TraktHistoryItemDto(
    @SerialName("watched_at") val watchedAt: String? = null,
    @SerialName("movie") val movie: TraktSyncMediaDto? = null,
    @SerialName("show") val show: TraktSyncMediaDto? = null,
    @SerialName("episode") val episode: TraktSyncEpisodeDto? = null,
)
