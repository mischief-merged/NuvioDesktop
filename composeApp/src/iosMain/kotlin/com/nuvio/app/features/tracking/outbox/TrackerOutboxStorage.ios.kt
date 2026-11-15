package com.nuvio.app.features.tracking.outbox

import platform.Foundation.NSUserDefaults

internal actual object TrackerOutboxStorage {
    private const val payloadKey = "tracker_outbox_payload"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey)
    }

    actual fun saveUnreadablePayload(payload: String, savedAtEpochMs: Long) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = "${payloadKey}_unreadable_$savedAtEpochMs")
    }
}
