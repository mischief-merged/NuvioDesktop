package com.nuvio.app.features.player.skip

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Lets a caller learn whether a skip-time lookup could not reach a service, which the lookup
 * functions themselves hide by answering with an empty list.
 *
 * A caller adds one to the coroutine context of the lookup. Every request made inside that
 * coroutine, including those of child coroutines, reports to it. Without one nothing is recorded,
 * so the lookups used while playing an online title behave as before.
 *
 * Only a successful, readable response or a clear "no entry" (see [classifySkipStatus]) counts as
 * an answer. A server error, a rate limit, an authentication failure, a timeout, a dropped
 * connection or a body that cannot be decoded leaves the lookup incomplete.
 */
internal class SkipLookupReport : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SkipLookupReport>

    private val lock = SynchronizedObject()
    private var unanswered = false

    /** True once any request made under this report got no answer. */
    val hadUnansweredRequest: Boolean get() = synchronized(lock) { unanswered }

    internal fun markUnanswered() = synchronized(lock) { unanswered = true }
}

/** Called where a lookup request failed. Rethrows cancellation, records the rest if a report is listening. */
internal suspend fun reportSkipLookupFailure(error: Throwable) {
    if (error is CancellationException) throw error
    // Every failure that reaches here is no answer: a request that failed, an error status, or a body that could not be read.
    coroutineContext[SkipLookupReport]?.markUnanswered()
}

/** True when a report listens to this coroutine and one of its requests got no answer. */
internal suspend fun skipLookupHadUnansweredRequest(): Boolean =
    coroutineContext[SkipLookupReport]?.hadUnansweredRequest == true

/** What a status code means for a lookup. The one table every provider goes through. */
internal enum class SkipStatusClass {
    /** A successful response: its body is the answer, and must be readable. */
    Ok,

    /** The service says it has no entry for this title (HTTP 404 or 410). That is an answer. */
    NoEntry,

    /** Server errors (5xx), rate limits (429), authentication failures, redirects and anything else: no answer. */
    Unanswered,
}

internal fun classifySkipStatus(status: Int): SkipStatusClass = when (status) {
    in 200..299 -> SkipStatusClass.Ok
    404, 410 -> SkipStatusClass.NoEntry
    else -> SkipStatusClass.Unanswered
}

/** The result of one request to a skip-time provider. */
internal sealed interface SkipHttpResult {
    data class Ok(val body: String) : SkipHttpResult
    data object NoEntry : SkipHttpResult
    data object Unanswered : SkipHttpResult
}

internal object SkipHttp {
    /** Replaced in tests. */
    internal var transport: suspend (method: String, url: String, headers: Map<String, String>, body: String) ->
    com.nuvio.app.features.addons.RawHttpResponse = { method, url, headers, body ->
        com.nuvio.app.features.addons.httpRequestRaw(method = method, url = url, headers = headers, body = body)
    }

    /**
     * Makes a request and classifies it. A request that fails to be made (no connection, timeout) or
     * whose status is not an answer is recorded as unanswered with any listening [SkipLookupReport].
     */
    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
    ): SkipHttpResult {
        val response = try {
            transport(method, url, headers, body)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportSkipLookupFailure(error)
            return SkipHttpResult.Unanswered
        }
        return when (classifySkipStatus(response.status)) {
            SkipStatusClass.Ok -> SkipHttpResult.Ok(response.body)
            SkipStatusClass.NoEntry -> SkipHttpResult.NoEntry
            SkipStatusClass.Unanswered -> {
                reportSkipLookupFailure(IllegalStateException("HTTP ${response.status}"))
                SkipHttpResult.Unanswered
            }
        }
    }

    suspend fun get(url: String, headers: Map<String, String> = mapOf("Accept" to "application/json")): SkipHttpResult =
        request("GET", url, headers)
}
