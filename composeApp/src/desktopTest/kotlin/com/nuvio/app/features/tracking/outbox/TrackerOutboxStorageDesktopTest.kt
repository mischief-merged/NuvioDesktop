package com.nuvio.app.features.tracking.outbox

import com.nuvio.app.core.storage.DesktopStorage
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackerOutboxStorageDesktopTest {
    @Test
    fun an_unreadable_payload_is_stored_apart_from_the_live_one() {
        val store = DesktopStorage.store("nuvio_tracker_outbox")
        val previous = TrackerOutboxStorage.loadPayload()
        try {
            TrackerOutboxStorage.savePayload("live")
            TrackerOutboxStorage.saveUnreadablePayload("broken", savedAtEpochMs = 12L)
            TrackerOutboxStorage.saveUnreadablePayload("also broken", savedAtEpochMs = 13L)

            assertEquals("live", TrackerOutboxStorage.loadPayload())
            assertEquals("broken", store.getString("tracker_outbox_payload_unreadable_12"))
            assertEquals("also broken", store.getString("tracker_outbox_payload_unreadable_13"))
        } finally {
            store.removeAll(listOf("tracker_outbox_payload_unreadable_12", "tracker_outbox_payload_unreadable_13"))
            if (previous == null) store.remove("tracker_outbox_payload") else TrackerOutboxStorage.savePayload(previous)
        }
    }
}
