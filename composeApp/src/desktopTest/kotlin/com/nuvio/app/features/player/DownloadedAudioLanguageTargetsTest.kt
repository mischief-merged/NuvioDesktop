package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadedAudioLanguageTargetsTest {
    @Test
    fun the_language_noted_for_a_downloaded_file_comes_before_the_languages_of_the_settings() {
        assertEquals(listOf("en", "lt", "ru"), audioLanguageTargets(downloadLanguage = "en", preferred = listOf("lt", "ru")))
    }

    @Test
    fun a_language_that_is_noted_and_also_in_the_settings_is_asked_for_once_and_first() {
        assertEquals(listOf("en", "lt"), audioLanguageTargets(downloadLanguage = "en", preferred = listOf("lt", "en")))
    }

    @Test
    fun without_a_noted_language_the_languages_of_the_settings_stand_as_they_are() {
        assertEquals(listOf("lt", "en"), audioLanguageTargets(downloadLanguage = null, preferred = listOf("lt", "en")))
    }
}
