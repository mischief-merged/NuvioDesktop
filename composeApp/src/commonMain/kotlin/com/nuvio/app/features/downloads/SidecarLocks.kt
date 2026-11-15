package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One lock per video for everything written beside it: subtitles, skip times, next-episode details,
 * artwork. A writer takes it, reads what is saved again, checks that it still has something to do,
 * and only then writes. That way two writers (playback and a background pass, say) cannot undo each
 * other, and nobody writes beside a video that was deleted or moved in the meantime.
 */
internal object SidecarLocks {
    private val guard = SynchronizedObject()
    private val locks = mutableMapOf<String, Mutex>()

    suspend fun <T> withLock(localVideoUri: String, block: suspend () -> T): T {
        val mutex = synchronized(guard) { locks.getOrPut(keyOf(localVideoUri)) { Mutex() } }
        return mutex.withLock { block() }
    }

    private fun keyOf(uri: String) = uri.trim().removePrefix("file:").trimStart('/')
}

/** Whether a video still has a reason to get files saved beside it: it is a finished download that is still listed. */
internal object DownloadCompanionGuard {
    internal var stillWanted: (localVideoUri: String) -> Boolean =
        { uri -> DownloadsRepository.findCompletedDownloadByLocalFile(uri) != null }
}
