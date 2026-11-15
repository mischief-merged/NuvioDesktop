package com.nuvio.app.features.downloads

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadIdUniquenessTest {
    @Test
    fun ids_handed_out_at_the_same_millisecond_from_several_threads_are_all_distinct() {
        val threads = 8
        val perThread = 5_000
        val now = 1_700_000_000_000L
        val ids = ConcurrentLinkedQueue<String>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            thread(isDaemon = true) {
                start.await()
                repeat(perThread) { ids += DownloadsRepository.nextDownloadId(now) }
                done.countDown()
            }
        }
        start.countDown()

        assertTrue(done.await(60, TimeUnit.SECONDS), "id generation did not finish")
        assertEquals(threads * perThread, ids.toSet().size, "duplicate download ids were handed out")
    }
}
