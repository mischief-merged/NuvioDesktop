package com.nuvio.app.features.tracking.outbox

import android.content.Context
import android.content.SharedPreferences

internal actual object TrackerOutboxStorage {
    private const val preferencesName = "nuvio_tracker_outbox"
    private const val payloadKey = "tracker_outbox_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(): String? =
        preferences?.getString(payloadKey, null)

    actual fun savePayload(payload: String) {
        preferences
            ?.edit()
            ?.putString(payloadKey, payload)
            ?.apply()
    }

    actual fun saveUnreadablePayload(payload: String, savedAtEpochMs: Long) {
        preferences
            ?.edit()
            ?.putString("${payloadKey}_unreadable_$savedAtEpochMs", payload)
            ?.apply()
    }
}
