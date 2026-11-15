package com.nuvio.app.features.settings

import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The words used for sources, downloads and preferences are the same on every screen that names them. */
class UserFacingWordingTest {
    private val sourceScreens: List<StringResource> = listOf(
        Res.string.streams_finding_source,
        Res.string.streams_finding_streams,
        Res.string.streams_refresh,
        Res.string.streams_copy_link,
        Res.string.streams_link_copied,
        Res.string.streams_no_direct_link,
        Res.string.streams_torrent_not_supported,
        Res.string.streams_empty_load_failed_title,
        Res.string.streams_empty_load_failed_message,
        Res.string.streams_empty_no_addons_message,
        Res.string.streams_empty_no_stream_addon_message,
        Res.string.streams_empty_no_stream_addon_title,
        Res.string.streams_empty_no_streams_message,
        Res.string.compose_player_no_streams_found,
        Res.string.player_loading_preparing,
        Res.string.player_loading_starting,
        Res.string.downloads_enqueue_missing_url,
        Res.string.downloads_enqueue_unsupported_format,
        Res.string.settings_playback_page_stream_selection,
        Res.string.settings_playback_page_stream_selection_description,
        Res.string.settings_playback_section_stream_auto_play,
        Res.string.settings_playback_section_stream_selection,
        Res.string.settings_playback_stream_selection_mode,
        Res.string.settings_playback_stream_selection_mode_manual,
        Res.string.settings_playback_stream_timeout,
        Res.string.settings_playback_reuse_last_link,
        Res.string.settings_playback_reuse_last_link_description,
        Res.string.settings_playback_source_scope_all_addons_description,
        Res.string.settings_playback_source_scope_enabled_plugins_only_description,
        Res.string.settings_playback_source_scope_installed_addons_only_description,
        Res.string.settings_playback_auto_play_next_episode_fallback_description,
        Res.string.settings_preferences_play_other,
        Res.string.settings_preferences_open_stream_auto_play,
    )

    private val downloadScreens: List<StringResource> = listOf(
        Res.string.downloads_result_verified_fingerprint,
        Res.string.downloads_result_verified_server,
        Res.string.downloads_error_file_checksum,
        Res.string.downloads_error_frames_undecodable,
        Res.string.downloads_error_frames_broken_end,
        Res.string.downloads_error_frames_broken_start,
        Res.string.downloads_error_frames_blank,
        Res.string.downloads_error_frames_still,
        Res.string.downloads_error_private_address,
        Res.string.settings_preferences_section_storage,
        Res.string.downloads_button_auto_on,
        Res.string.downloads_choices_saved_note,
        Res.string.downloads_season_max_bitrate,
        Res.string.downloads_season_max_bitrate_description,
        Res.string.downloads_season_audio_language_description,
    )

    private fun text(resource: StringResource) = runBlocking { getString(resource) }

    @Test
    fun the_screens_about_sources_say_source_and_not_stream() {
        val offenders = sourceScreens.map(::text).filter { Regex("\\bstreams?\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }

        assertEquals(emptyList(), offenders)
    }

    @Test
    fun the_download_screens_use_plain_words_and_one_name_for_auto_download() {
        val banned = listOf("checksum", "fingerprint", "decoded", "breaks off", "still picture", "local or private", "automatic download")
        val offenders = downloadScreens.map(::text).filter { line -> banned.any { line.contains(it, ignoreCase = true) } }

        assertEquals(emptyList(), offenders)
    }

    @Test
    fun the_units_of_the_speed_limit_and_the_bitrate_are_spelled_out() {
        assertTrue(text(Res.string.settings_download_speed_limit_description).contains("megabytes per second"))
        assertTrue(text(Res.string.downloads_season_max_bitrate).contains("megabits per second"))
        assertTrue(runBlocking { getString(Res.string.downloads_season_bitrate_value, 10) }.contains("megabits per second"))
    }
}
