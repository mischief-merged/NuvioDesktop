package com.nuvio.app.features.updater

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class UpdatePreferences(
    versionName: String = AppUpdaterPlatform.currentVersionName,
    private val readChannel: () -> String? = AppUpdaterPlatform::getUpdateChannel,
    private val writeChannel: (String) -> Unit = AppUpdaterPlatform::setUpdateChannel,
    private val writeIgnoredTag: (String?) -> Unit = AppUpdaterPlatform::setIgnoredTag,
    private val readAutoDailyCheck: () -> Boolean = AppUpdaterPlatform::getAutoDailyCheckEnabled,
    private val writeAutoDailyCheck: (Boolean) -> Unit = AppUpdaterPlatform::setAutoDailyCheckEnabled,
    private val readLastCheckEpochMs: () -> Long? = AppUpdaterPlatform::getLastCheckEpochMs,
    private val writeLastCheckEpochMs: (Long) -> Unit = AppUpdaterPlatform::setLastCheckEpochMs,
    private val readLastWhatsNewVersion: () -> String? = AppUpdaterPlatform::getLastWhatsNewVersion,
    private val writeLastWhatsNewVersion: (String?) -> Unit = AppUpdaterPlatform::setLastWhatsNewVersion,
    private val persistDefaults: Boolean = AppUpdaterPlatform.isSupported,
) {
    private val storedChannel = UpdateChannel.fromStoredValue(readChannel())
    private val singleChannel = AppUpdaterPlatform.hasSingleUpdateChannel
    private val defaultChannel = if (singleChannel) {
        UpdateChannel.ALL_RELEASES
    } else {
        storedChannel ?: UpdateChannel.defaultForVersion(versionName)
    }
    private val _channel = MutableStateFlow(defaultChannel)
    val channel = _channel.asStateFlow()

    private val _autoDailyCheckEnabled = MutableStateFlow(readAutoDailyCheck())
    val autoDailyCheckEnabled = _autoDailyCheckEnabled.asStateFlow()

    private val _lastCheckEpochMs = MutableStateFlow(readLastCheckEpochMs())
    val lastCheckEpochMs = _lastCheckEpochMs.asStateFlow()

    private val _lastWhatsNewVersion = MutableStateFlow(readLastWhatsNewVersion())
    val lastWhatsNewVersion = _lastWhatsNewVersion.asStateFlow()

    init {
        if (persistDefaults && (storedChannel == null || storedChannel != defaultChannel)) {
            writeChannel(_channel.value.storedValue)
        }
    }

    fun setChannel(channel: UpdateChannel) {
        val selectedChannel = if (singleChannel) UpdateChannel.ALL_RELEASES else channel
        if (_channel.value == selectedChannel) return
        writeChannel(selectedChannel.storedValue)
        writeIgnoredTag(null)
        _channel.value = selectedChannel
    }

    fun setAutoDailyCheckEnabled(enabled: Boolean) {
        if (_autoDailyCheckEnabled.value == enabled) return
        writeAutoDailyCheck(enabled)
        _autoDailyCheckEnabled.value = enabled
    }

    fun recordCheckCompleted(epochMs: Long) {
        writeLastCheckEpochMs(epochMs)
        _lastCheckEpochMs.value = epochMs
    }

    fun shouldShowWhatsNew(currentVersion: String): Boolean =
        shouldShowWhatsNewForVersion(currentVersion, _lastWhatsNewVersion.value)

    fun markWhatsNewSeen(version: String) {
        val normalized = version.trim()
        if (normalized.isEmpty() || _lastWhatsNewVersion.value == normalized) return
        writeLastWhatsNewVersion(normalized)
        _lastWhatsNewVersion.value = normalized
    }

    companion object {
        val shared by lazy { UpdatePreferences() }

        internal fun shouldShowWhatsNewForVersion(
            currentVersion: String,
            lastSeenVersion: String?,
        ): Boolean {
            val normalizedCurrent = currentVersion.trim()
            if (normalizedCurrent.isEmpty()) return false
            return lastSeenVersion?.trim() != normalizedCurrent
        }
    }
}
