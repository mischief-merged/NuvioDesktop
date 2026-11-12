package com.nuvio.app.features.tracking.outbox

import com.nuvio.app.core.storage.DesktopStorage

internal actual object TrackerOutboxStorage {
    private val store = DesktopStorage.store("nuvio_tracker_outbox")
    private const val payloadKey = "tracker_outbox_payload"

    actual fun loadPayload(): String? =
        store.getString(payloadKey)

    actual fun savePayload(payload: String) {
        store.putString(payloadKey, payload)
    }

    actual fun saveUnreadablePayload(payload: String, savedAtEpochMs: Long) {
        store.putString("${payloadKey}_unreadable_$savedAtEpochMs", payload)
    }
}
