package com.nuvio.app.features.downloads

internal expect object DownloadSpeedStorage {
    fun loadBytesPerSecond(): Long?
    fun saveBytesPerSecond(value: Long?)
}
