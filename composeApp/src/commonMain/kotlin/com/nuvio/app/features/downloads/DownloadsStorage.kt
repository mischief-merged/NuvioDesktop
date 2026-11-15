package com.nuvio.app.features.downloads

internal expect object DownloadsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)

    /** The payload from before the last one that was saved, where the platform keeps it. */
    fun loadPreviousPayload(): String?

    /** Keeps a payload that could not be read under a key of its own, so that it is never overwritten. */
    fun saveUnreadablePayload(payload: String, atEpochMs: Long)
}
