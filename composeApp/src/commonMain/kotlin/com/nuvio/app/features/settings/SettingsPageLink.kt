package com.nuvio.app.features.settings

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Opens a settings page from a screen outside Settings, such as the source list or Downloads. Null
 * where the host cannot do it; a screen then shows its text without a link.
 */
internal val LocalOpenSettingsPage = staticCompositionLocalOf<((SettingsPage) -> Unit)?> { null }
