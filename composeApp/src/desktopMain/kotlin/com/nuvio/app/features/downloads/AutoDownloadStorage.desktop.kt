package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object AutoDownloadStorage {
    private val store = DesktopStorage.store("nuvio_auto_downloads")

    actual fun loadPayload(profileId: Int): String? =
        store.getString(ProfileScopedKey.of("auto_downloads", profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        store.putString(ProfileScopedKey.of("auto_downloads", profileId), payload)
    }

    actual fun saveUnreadablePayload(profileId: Int, payload: String) {
        store.putString(ProfileScopedKey.of("auto_downloads_unreadable", profileId), payload)
    }
}
