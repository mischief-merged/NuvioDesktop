package com.nuvio.app.features.downloads

/**
 * What a profile did with the one-time dialog that asks for its quality and language preferences.
 * Stored by name. A missing or unknown value, written by another version, reads as [NotAsked].
 */
internal enum class PreferenceSetupAnswer {
    NotAsked,

    /** The dialog was shown and the choices were saved. */
    Saved,

    /** The dialog was shown and closed without saving. */
    Skipped,

    /** The profile had saved download choices already, so the dialog was never shown. */
    AlreadyConfigured;

    companion object {
        fun fromStored(value: String?): PreferenceSetupAnswer =
            entries.firstOrNull { it.name == value } ?: NotAsked
    }
}

internal enum class PreferenceSetupAction {
    /** Show the dialog now. */
    Show,

    /** Do not show it, and remember that this profile does not need it. */
    MarkAlreadyConfigured,

    /** Do nothing now. */
    Nothing,
}

/**
 * What the one-time preferences dialog should do for a profile.
 *
 * A profile that was asked, or that has saved download choices, is never asked. [canInterrupt] is
 * false while the app is not at rest on its main content, for instance while a source list or the
 * player is open or a link is being opened, and the dialog then waits.
 */
internal fun preferenceSetupAction(
    answer: PreferenceSetupAnswer,
    hasSavedDownloadDefaults: Boolean,
    canInterrupt: Boolean,
): PreferenceSetupAction = when {
    answer != PreferenceSetupAnswer.NotAsked -> PreferenceSetupAction.Nothing
    hasSavedDownloadDefaults -> PreferenceSetupAction.MarkAlreadyConfigured
    canInterrupt -> PreferenceSetupAction.Show
    else -> PreferenceSetupAction.Nothing
}

internal object PreferenceSetupRepository {
    fun answer(profileId: Int): PreferenceSetupAnswer =
        PreferenceSetupAnswer.fromStored(SeasonDownloadDefaultsStorage.loadSetupAnswer(profileId))

    fun record(profileId: Int, answer: PreferenceSetupAnswer) {
        SeasonDownloadDefaultsStorage.saveSetupAnswer(profileId, answer.name)
    }

    fun actionFor(profileId: Int, canInterrupt: Boolean): PreferenceSetupAction =
        preferenceSetupAction(
            answer = answer(profileId),
            hasSavedDownloadDefaults = SeasonDownloadDefaultsRepository.hasSaved(profileId),
            canInterrupt = canInterrupt,
        )
}
