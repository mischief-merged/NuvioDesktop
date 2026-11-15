package com.nuvio.app.features.downloads

internal expect object SeasonDownloadDefaultsStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)
}
