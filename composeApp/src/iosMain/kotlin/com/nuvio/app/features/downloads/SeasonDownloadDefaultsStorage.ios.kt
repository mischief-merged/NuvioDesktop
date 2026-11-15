package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object SeasonDownloadDefaultsStorage {
    private const val payloadKey = "season_download_defaults_payload"
    private const val setupAnswerKey = "preference_setup_answer"

    actual fun loadPayload(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey, profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey, profileId))
    }

    actual fun loadSetupAnswer(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(setupAnswerKey, profileId))

    actual fun saveSetupAnswer(profileId: Int, answer: String) {
        NSUserDefaults.standardUserDefaults.setObject(answer, forKey = ProfileScopedKey.of(setupAnswerKey, profileId))
    }
}
