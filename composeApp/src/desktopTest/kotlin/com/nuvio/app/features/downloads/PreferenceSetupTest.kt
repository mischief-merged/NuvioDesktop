package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamLanguage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PreferenceSetupTest {
    // Profile ids no real profile uses, so stored preferences of real profiles are never touched.
    private val profile = 9_900_021
    private val otherProfile = 9_900_022

    @BeforeTest
    @AfterTest
    fun clear_test_profiles() {
        listOf(profile, otherProfile).forEach {
            SeasonDownloadDefaultsStorage.savePayload(it, "")
            SeasonDownloadDefaultsStorage.saveSetupAnswer(it, "")
        }
    }

    @Test
    fun a_profile_that_was_never_asked_and_has_nothing_saved_is_asked_when_the_app_is_at_rest() {
        assertEquals(
            PreferenceSetupAction.Show,
            preferenceSetupAction(PreferenceSetupAnswer.NotAsked, hasSavedDownloadDefaults = false, canInterrupt = true),
        )
    }

    @Test
    fun the_dialog_waits_while_something_else_has_the_screen() {
        assertEquals(
            PreferenceSetupAction.Nothing,
            preferenceSetupAction(PreferenceSetupAnswer.NotAsked, hasSavedDownloadDefaults = false, canInterrupt = false),
        )
    }

    @Test
    fun a_profile_with_saved_download_choices_is_marked_without_being_asked() {
        listOf(true, false).forEach { canInterrupt ->
            assertEquals(
                PreferenceSetupAction.MarkAlreadyConfigured,
                preferenceSetupAction(PreferenceSetupAnswer.NotAsked, hasSavedDownloadDefaults = true, canInterrupt = canInterrupt),
            )
        }
    }

    @Test
    fun a_profile_that_answered_is_never_asked_again() {
        listOf(true, false).forEach { saved ->
            listOf(true, false).forEach { canInterrupt ->
                listOf(
                    PreferenceSetupAnswer.Saved,
                    PreferenceSetupAnswer.Skipped,
                    PreferenceSetupAnswer.AlreadyConfigured,
                ).forEach { answer ->
                    assertEquals(PreferenceSetupAction.Nothing, preferenceSetupAction(answer, saved, canInterrupt))
                }
            }
        }
    }

    @Test
    fun a_missing_or_unknown_stored_answer_reads_as_not_asked() {
        assertEquals(PreferenceSetupAnswer.NotAsked, PreferenceSetupAnswer.fromStored(null))
        assertEquals(PreferenceSetupAnswer.NotAsked, PreferenceSetupAnswer.fromStored(""))
        assertEquals(PreferenceSetupAnswer.NotAsked, PreferenceSetupAnswer.fromStored("AnsweredOnAFutureVersion"))
        PreferenceSetupAnswer.entries.forEach {
            assertEquals(it, PreferenceSetupAnswer.fromStored(it.name))
        }
    }

    @Test
    fun a_profile_with_no_stored_data_is_not_asked_yet() {
        assertEquals(PreferenceSetupAnswer.NotAsked, PreferenceSetupRepository.answer(profile))
        assertEquals(PreferenceSetupAction.Show, PreferenceSetupRepository.actionFor(profile, canInterrupt = true))
    }

    @Test
    fun stored_download_choices_from_before_the_flag_existed_still_load_and_skip_the_dialog() {
        SeasonDownloadDefaultsStorage.savePayload(
            profile,
            "{\"quality\":\"P1080\",\"maxBitrateMbps\":10,\"audioLanguage\":\"EN\"}",
        )

        assertEquals(PreferenceSetupAnswer.NotAsked, PreferenceSetupRepository.answer(profile))
        assertEquals(
            SeasonDownloadDefaults(BatchQuality.P1080, 10, DebridStreamLanguage.EN),
            SeasonDownloadDefaultsRepository.load(profile),
        )
        assertEquals(PreferenceSetupAction.MarkAlreadyConfigured, PreferenceSetupRepository.actionFor(profile, canInterrupt = true))
    }

    @Test
    fun an_unknown_stored_answer_is_tolerated_and_the_profile_is_asked() {
        SeasonDownloadDefaultsStorage.saveSetupAnswer(profile, "AnsweredOnAFutureVersion")

        assertEquals(PreferenceSetupAnswer.NotAsked, PreferenceSetupRepository.answer(profile))
        assertEquals(PreferenceSetupAction.Show, PreferenceSetupRepository.actionFor(profile, canInterrupt = true))
    }

    @Test
    fun the_answer_is_kept_per_profile_and_apart_from_the_download_choices() {
        PreferenceSetupRepository.record(profile, PreferenceSetupAnswer.Skipped)

        assertEquals(PreferenceSetupAnswer.Skipped, PreferenceSetupRepository.answer(profile))
        assertEquals(PreferenceSetupAnswer.NotAsked, PreferenceSetupRepository.answer(otherProfile))
        assertEquals(false, SeasonDownloadDefaultsRepository.hasSaved(profile))
        assertEquals(PreferenceSetupAction.Nothing, PreferenceSetupRepository.actionFor(profile, canInterrupt = true))
    }

    @Test
    fun saved_choices_are_detected_however_they_were_saved() {
        assertEquals(false, SeasonDownloadDefaultsRepository.hasSaved(profile))
        SeasonDownloadDefaultsRepository.save(profile, SeasonDownloadDefaults())
        assertEquals(true, SeasonDownloadDefaultsRepository.hasSaved(profile))
        assertEquals(false, SeasonDownloadDefaultsRepository.hasSaved(otherProfile))
    }
}
