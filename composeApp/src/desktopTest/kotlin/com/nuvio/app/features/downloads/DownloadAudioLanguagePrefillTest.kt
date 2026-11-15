package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamLanguage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadAudioLanguagePrefillTest {
    // A profile id no real profile uses, so stored preferences of real profiles are never touched.
    private val profile = 9_900_041

    @BeforeTest
    @AfterTest
    fun clear_test_profile() {
        SeasonDownloadDefaultsStorage.savePayload(profile, "")
    }

    @Test
    fun a_named_playback_language_gives_the_same_download_language() {
        assertEquals(DebridStreamLanguage.EN, downloadLanguageFromPlayback("en", emptyList()))
        assertEquals(DebridStreamLanguage.PT, downloadLanguageFromPlayback("pt-br", emptyList()))
        assertEquals(DebridStreamLanguage.FR, downloadLanguageFromPlayback("fr", listOf("de")))
    }

    @Test
    fun the_device_language_stands_in_for_device() {
        assertEquals(DebridStreamLanguage.DE, downloadLanguageFromPlayback("device", listOf("de-DE", "en")))
        assertNull(downloadLanguageFromPlayback("device", emptyList()))
    }

    @Test
    fun default_and_original_and_languages_no_source_is_tagged_with_give_none() {
        assertNull(downloadLanguageFromPlayback("default", listOf("en")))
        assertNull(downloadLanguageFromPlayback("original", listOf("en")))
        assertNull(downloadLanguageFromPlayback("af", emptyList()))
        assertNull(downloadLanguageFromPlayback("", emptyList()))
    }

    @Test
    fun a_profile_with_nothing_saved_starts_with_the_playback_language_and_nothing_is_saved() {
        val start = downloadChoicesStart(profile, "en")

        assertEquals(SeasonDownloadDefaults(audioLanguage = DebridStreamLanguage.EN), start.defaults)
        assertEquals(true, start.languageFromPlayback)
        assertEquals(false, SeasonDownloadDefaultsRepository.hasSaved(profile))
    }

    @Test
    fun a_profile_with_nothing_saved_and_no_matching_language_starts_neutral() {
        val start = downloadChoicesStart(profile, "original")

        assertEquals(SeasonDownloadDefaults(), start.defaults)
        assertEquals(false, start.languageFromPlayback)
    }

    @Test
    fun saved_choices_are_never_overwritten_including_a_saved_any_language() {
        SeasonDownloadDefaultsRepository.save(profile, SeasonDownloadDefaults(BatchQuality.P720, audioLanguage = null))
        val anyLanguage = downloadChoicesStart(profile, "en")
        assertEquals(SeasonDownloadDefaults(BatchQuality.P720), anyLanguage.defaults)
        assertEquals(false, anyLanguage.languageFromPlayback)

        SeasonDownloadDefaultsRepository.save(profile, SeasonDownloadDefaults(audioLanguage = DebridStreamLanguage.IT))
        assertEquals(DebridStreamLanguage.IT, downloadChoicesStart(profile, "en").defaults.audioLanguage)
    }
}
