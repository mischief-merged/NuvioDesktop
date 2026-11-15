package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamLanguage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeasonDownloadDefaultsTest {
    // Profile ids no real profile uses, so stored preferences of real profiles are never touched.
    private val profile = 9_900_001
    private val otherProfile = 9_900_002

    private val chosen = SeasonDownloadDefaults(
        quality = BatchQuality.P1080,
        maxBitrateMbps = 10,
        audioLanguage = DebridStreamLanguage.EN,
    )

    @BeforeTest
    @AfterTest
    fun clear_test_profiles() {
        SeasonDownloadDefaultsStorage.savePayload(profile, "")
        SeasonDownloadDefaultsStorage.savePayload(otherProfile, "")
    }

    @Test
    fun the_defaults_are_neutral_until_the_user_saves_a_choice() {
        val defaults = SeasonDownloadDefaultsRepository.load(profile)

        assertEquals(BatchQuality.BestAvailable, defaults.quality)
        assertNull(defaults.maxBitrateMbps)
        assertNull(defaults.audioLanguage)
    }

    @Test
    fun saved_choices_are_read_back_for_the_profile_they_were_saved_for() {
        SeasonDownloadDefaultsRepository.save(profile, chosen)

        assertEquals(chosen, SeasonDownloadDefaultsRepository.load(profile))
        assertEquals(SeasonDownloadDefaults(), SeasonDownloadDefaultsRepository.load(otherProfile))
    }

    @Test
    fun an_unreadable_payload_falls_back_to_the_neutral_defaults() {
        SeasonDownloadDefaultsStorage.savePayload(profile, "{\"quality\":\"P999\"")

        assertEquals(SeasonDownloadDefaults(), SeasonDownloadDefaultsRepository.load(profile))
    }

    @Test
    fun a_stored_bitrate_or_language_that_is_not_offered_is_read_as_no_preference() {
        SeasonDownloadDefaultsStorage.savePayload(
            profile,
            "{\"quality\":\"P720\",\"maxBitrateMbps\":12,\"audioLanguage\":\"MULTI\"}",
        )

        assertEquals(SeasonDownloadDefaults(quality = BatchQuality.P720), SeasonDownloadDefaultsRepository.load(profile))
    }

    @Test
    fun the_dialog_choices_start_from_the_saved_defaults_and_save_what_the_user_confirms() {
        SeasonDownloadDefaultsRepository.save(profile, chosen)

        val choices = SeasonDownloadChoices(profile)
        assertEquals(BatchQuality.P1080, choices.quality)
        assertEquals(10, choices.maxBitrateMbps)
        assertEquals(DebridStreamLanguage.EN, choices.audioLanguage)

        choices.quality = BatchQuality.P720
        choices.maxBitrateMbps = null
        val confirmed = choices.confirm()

        assertEquals(SeasonDownloadDefaults(BatchQuality.P720, null, DebridStreamLanguage.EN), confirmed)
        assertEquals(confirmed, SeasonDownloadDefaultsRepository.load(profile))
    }

    @Test
    fun choices_confirmed_just_this_time_are_handed_on_but_not_saved() {
        SeasonDownloadDefaultsRepository.save(profile, chosen)
        val choices = SeasonDownloadChoices(profile)

        choices.quality = BatchQuality.P480
        choices.justThisTime = true
        val confirmed = choices.confirm()

        assertEquals(BatchQuality.P480, confirmed.quality)
        assertEquals(chosen, SeasonDownloadDefaultsRepository.load(profile))
    }

    @Test
    fun choices_are_saved_by_default() {
        assertEquals(false, SeasonDownloadChoices(profile).justThisTime)
    }

    @Test
    fun choices_are_saved_for_the_profile_the_dialog_was_opened_for() {
        val otherSaved = SeasonDownloadDefaults(BatchQuality.P480, maxBitrateMbps = 4)
        SeasonDownloadDefaultsRepository.save(otherProfile, otherSaved)
        val choices = SeasonDownloadChoices(profile)
        val otherChoices = SeasonDownloadChoices(otherProfile)

        choices.quality = BatchQuality.P2160
        choices.confirm()

        assertEquals(profile, choices.profileId)
        assertEquals(SeasonDownloadDefaults(BatchQuality.P2160), SeasonDownloadDefaultsRepository.load(profile))
        assertEquals(otherSaved, SeasonDownloadDefaultsRepository.load(otherProfile))
        assertEquals(BatchQuality.P480, otherChoices.quality)
    }
}
