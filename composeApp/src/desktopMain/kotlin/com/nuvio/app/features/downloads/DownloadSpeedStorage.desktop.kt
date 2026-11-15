package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage

internal actual object DownloadSpeedStorage {
    private val store = DesktopStorage.store("nuvio_download_speed")

    actual fun loadBytesPerSecond(): Long? =
        store.getString("bytes_per_second")?.toLongOrNull()

    actual fun saveBytesPerSecond(value: Long?) {
        store.putString("bytes_per_second", value?.toString())
    }
}
