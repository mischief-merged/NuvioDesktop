package com.nuvio.app.features.tracking.outbox

internal expect object TrackerOutboxStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
    fun saveUnreadablePayload(payload: String, savedAtEpochMs: Long)
}
