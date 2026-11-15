package com.nuvio.app.features.downloads

internal actual object DownloadSpeedStorage {
    actual fun loadBytesPerSecond(): Long? = null
    actual fun saveBytesPerSecond(value: Long?) = Unit
}
