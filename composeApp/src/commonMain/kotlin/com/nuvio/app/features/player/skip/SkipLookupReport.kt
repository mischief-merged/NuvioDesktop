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
 * Only a request that never got an answer counts as a failure: no connection, a timeout, a
 * dropped transfer. A service that answered, even with an error status, an empty body or text that
 * cannot be read, did answer. That also means a missing entry (which services often report as an
 * error status) is told apart from an unreachable service.
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
    // The client raises these when the service did answer (error status, empty body) or the body was unreadable.
    if (error is IllegalStateException || error is IllegalArgumentException) return
    coroutineContext[SkipLookupReport]?.markUnanswered()
}

/** True when a report listens to this coroutine and one of its requests got no answer. */
internal suspend fun skipLookupHadUnansweredRequest(): Boolean =
    coroutineContext[SkipLookupReport]?.hadUnansweredRequest == true
