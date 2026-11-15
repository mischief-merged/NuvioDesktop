package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object DownloadsStorage {
    private const val payloadKey = "downloads_payload"
    private const val unreadablePayloadKey = "downloads_unreadable_payload"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }

    actual fun loadPreviousPayload(): String? = null

    actual fun saveUnreadablePayload(payload: String, atEpochMs: Long) {
        NSUserDefaults.standardUserDefaults.setObject(
            payload,
            forKey = "${ProfileScopedKey.of(unreadablePayloadKey)}_$atEpochMs",
        )
    }
}
