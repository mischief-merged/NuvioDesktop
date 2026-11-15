package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamAutoPlayMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceFailoverSettingsTest {

    @Test
    fun movingToTheNextSourceIsOnByDefaultOnDesktop() {
        assertTrue(DEFAULT_SOURCE_FAILOVER_ENABLED)
        assertTrue(PlayerSettingsUiState().streamAutoPlayFallbackOnFailure)
    }

    @Test
    fun theSettingAppliesInManualModeToo() {
        val manual = PlayerSettingsUiState(streamAutoPlayMode = StreamAutoPlayMode.MANUAL)
        assertTrue(manual.streamAutoPlayFallbackActive)
    }

    @Test
    fun theSettingIsOffWhenTheUserTurnedItOffOrAnExternalPlayerPlays() {
        assertFalse(PlayerSettingsUiState(streamAutoPlayFallbackOnFailure = false).streamAutoPlayFallbackActive)
        assertFalse(PlayerSettingsUiState(externalPlayerEnabled = true).streamAutoPlayFallbackActive)
    }

    @Test
    fun theAndroidEngineSwitchDoesNotBlockMovingOnOnDesktop() {
        // The stored value defaults to on, but desktop has no engine switch and no setting for it.
        val settings = PlayerSettingsUiState(autoSwitchPlaybackEngineOnError = true)
        assertFalse(settings.engineSwitchOnErrorBlocksSourceFailover)
    }
}
