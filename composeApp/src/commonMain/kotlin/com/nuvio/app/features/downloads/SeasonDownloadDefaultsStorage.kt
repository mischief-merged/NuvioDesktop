package com.nuvio.app.features.downloads

internal expect object SeasonDownloadDefaultsStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)

    /** The answer to the first-start preferences dialog, kept apart from the defaults themselves. */
    fun loadSetupAnswer(profileId: Int): String?
    fun saveSetupAnswer(profileId: Int, answer: String)
}
