package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadTransferLimitsTest {
    private fun limits(maxTotalBytes: Long?, minFreeBytes: Long = 100) =
        DownloadTransferLimits(maxTotalBytes = { maxTotalBytes }, minFreeBytes = minFreeBytes, onStopped = {})

    @Test
    fun an_announced_size_within_the_limit_and_the_free_space_is_allowed() {
        assertNull(limits(maxTotalBytes = 1_000).stopFor(downloadedBytes = 0, totalBytes = 1_000, freeBytes = 1_100))
    }

    @Test
    fun an_announced_size_above_the_limit_is_refused_before_anything_is_written() {
        assertEquals(
            AutoDownloadStop.StorageLimit,
            limits(maxTotalBytes = 1_000).stopFor(downloadedBytes = 0, totalBytes = 1_001, freeBytes = 1_000_000),
        )
    }

    @Test
    fun bytes_written_beyond_the_limit_stop_a_transfer_of_unknown_size() {
        val limits = limits(maxTotalBytes = 1_000)

        assertNull(limits.stopFor(downloadedBytes = 1_000, totalBytes = null, freeBytes = 1_000_000))
        assertEquals(AutoDownloadStop.StorageLimit, limits.stopFor(downloadedBytes = 1_001, totalBytes = null, freeBytes = 1_000_000))
    }

    @Test
    fun bytes_written_beyond_the_announced_size_still_count() {
        assertEquals(
            AutoDownloadStop.StorageLimit,
            limits(maxTotalBytes = 1_000).stopFor(downloadedBytes = 1_200, totalBytes = 900, freeBytes = 1_000_000),
        )
    }

    @Test
    fun what_is_still_to_be_written_must_leave_the_free_space_floor() {
        val limits = limits(maxTotalBytes = null, minFreeBytes = 100)

        assertNull(limits.stopFor(downloadedBytes = 400, totalBytes = 1_000, freeBytes = 700))
        assertEquals(AutoDownloadStop.LowDiskSpace, limits.stopFor(downloadedBytes = 400, totalBytes = 1_000, freeBytes = 699))
    }

    @Test
    fun free_space_below_the_floor_stops_a_transfer_of_unknown_size() {
        val limits = limits(maxTotalBytes = null, minFreeBytes = 100)

        assertNull(limits.stopFor(downloadedBytes = 5_000, totalBytes = null, freeBytes = 100))
        assertEquals(AutoDownloadStop.LowDiskSpace, limits.stopFor(downloadedBytes = 5_000, totalBytes = null, freeBytes = 99))
    }

    @Test
    fun unknown_free_space_and_no_storage_limit_stop_nothing() {
        assertNull(limits(maxTotalBytes = null).stopFor(downloadedBytes = 0, totalBytes = Long.MAX_VALUE, freeBytes = null))
    }

    @Test
    fun the_storage_limit_is_reported_before_the_free_space() {
        assertEquals(
            AutoDownloadStop.StorageLimit,
            limits(maxTotalBytes = 10).stopFor(downloadedBytes = 0, totalBytes = 1_000, freeBytes = 0),
        )
    }
}
