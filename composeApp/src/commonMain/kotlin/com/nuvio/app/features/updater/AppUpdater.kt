package com.nuvio.app.features.updater

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.AppPresenceState
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.downloads.DownloadsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

data class AppUpdate(
    val tag: String,
    val title: String,
    val notes: String,
    val releaseUrl: String?,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long?,
    val expectedSha256: String? = null,
    val versionDisplay: String = tag,
    val buildNumber: Int? = null,
    val releaseHistory: List<ReleaseNoteEntry> = emptyList(),
)

internal data class AppUpdaterUiState(
    val updateChannel: UpdateChannel = UpdateChannel.STABLE,
    val isChecking: Boolean = false,
    val update: AppUpdate? = null,
    val isUpdateAvailable: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val isVerifying: Boolean = false,
    val isInstalling: Boolean = false,
    val deferredInstallReason: InstallBlockReason? = null,
    val downloadedUpdatePath: String? = null,
    val showDialog: Boolean = false,
    val showInstallPermissionDialog: Boolean = false,
    val errorMessage: String? = null,
    val isDebugTest: Boolean = false,
    val feedInfo: UpdateFeedInfo? = null,
    val lastCheckEpochMs: Long? = null,
    val autoDailyCheckEnabled: Boolean = true,
    val currentVersionDisplay: String = AppUpdaterPlatform.currentVersionName,
    val currentBuildNumber: Int = AppUpdaterPlatform.currentBuildNumber,
    val pendingUpdateNotes: List<ReleaseNoteEntry> = emptyList(),
    val showWhatsNew: Boolean = false,
    val whatsNewEntries: List<ReleaseNoteEntry> = emptyList(),
)

internal val LocalAppUpdaterController = staticCompositionLocalOf<AppUpdaterController?> { null }

internal const val DAILY_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
private const val DAILY_CHECK_POLL_INTERVAL_MS = 60L * 60L * 1000L

internal fun shouldRunDailyCheck(lastCheckEpochMs: Long?, nowEpochMs: Long): Boolean =
    lastCheckEpochMs == null || (nowEpochMs - lastCheckEpochMs) >= DAILY_CHECK_INTERVAL_MS

class AppUpdaterController internal constructor(
    private val scope: CoroutineScope,
    private val preferences: UpdatePreferences = UpdatePreferences.shared,
    private val fetchUpdate: suspend (UpdateChannel) -> Result<AppUpdate> = defaultUpdateFetcher(),
    private val installGate: InstallGate = InstallGate(),
    private val downloadAsset: suspend (String, String, (Long, Long?) -> Unit) -> Result<String> =
        AppUpdaterPlatform::downloadUpdateAsset,
    private val verifyAsset: suspend (String, String?) -> Result<Unit> =
        AppUpdaterPlatform::verifyDownloadedUpdate,
    private val installAsset: suspend (String, String?, String?, () -> Unit) -> Result<Unit> =
        AppUpdaterPlatform::installVerifiedUpdate,
    private val nowEpochMs: () -> Long = AppUpdaterPlatform::nowEpochMs,
    private val feedInfoProvider: () -> UpdateFeedInfo? = {
        if (AppUpdaterPlatform.usesForkUpdateFeed) UpdateFeedSelector.currentFeed().info else null
    },
    private val usesForkFeed: Boolean = AppUpdaterPlatform.usesForkUpdateFeed,
    private val localForkVersion: NuvioRoidVersion = NuvioRoidVersion.fromForkIdentity(),
    private val currentVersionDisplay: String = AppUpdaterPlatform.currentVersionName,
    private val currentBuildNumber: Int = AppUpdaterPlatform.currentBuildNumber,
    private val localReleaseNotes: List<ReleaseNoteEntry> = LocalReleaseNotesHistory.entries,
) {
    private val initialWhatsNew = computeInitialWhatsNew()

    private val _uiState = MutableStateFlow(
        AppUpdaterUiState(
            updateChannel = preferences.channel.value,
            feedInfo = feedInfoProvider(),
            lastCheckEpochMs = if (usesForkFeed) preferences.lastCheckEpochMs.value else null,
            autoDailyCheckEnabled = if (usesForkFeed) preferences.autoDailyCheckEnabled.value else true,
            currentVersionDisplay = currentVersionDisplay,
            currentBuildNumber = currentBuildNumber,
            showWhatsNew = initialWhatsNew.first,
            whatsNewEntries = initialWhatsNew.second,
        ),
    )
    internal val uiState: StateFlow<AppUpdaterUiState> = _uiState.asStateFlow()

    private var autoCheckStarted = false
    private var gateObserverStarted = false
    private var pendingInstallWhenReady = false
    private var updateCheckJob: Job? = null
    private var downloadJob: Job? = null
    private var installJob: Job? = null

    private fun computeInitialWhatsNew(): Pair<Boolean, List<ReleaseNoteEntry>> {
        if (!usesForkFeed) return false to emptyList()
        val seenRaw = preferences.lastWhatsNewVersion.value
        if (!UpdatePreferences.shouldShowWhatsNewForVersion(currentVersionDisplay, seenRaw)) {
            return false to emptyList()
        }
        val entries = LocalReleaseNotesHistory.entriesForWhatsNew(
            currentVersion = localForkVersion,
            lastSeenVersion = NuvioRoidVersion.parse(seenRaw),
            allEntries = localReleaseNotes,
        )
        return true to entries
    }

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            preferences.channel.collect { channel ->
                if (channel != _uiState.value.updateChannel) {
                    updateCheckJob?.cancel()
                    downloadJob?.cancel()
                    installJob?.cancel()
                    pendingInstallWhenReady = false
                    _uiState.value.downloadedUpdatePath?.let(AppUpdaterPlatform::deleteDownloadedUpdate)
                    _uiState.value = AppUpdaterUiState(
                        updateChannel = channel,
                        feedInfo = feedInfoProvider(),
                        lastCheckEpochMs = if (usesForkFeed) preferences.lastCheckEpochMs.value else null,
                        autoDailyCheckEnabled = if (usesForkFeed) preferences.autoDailyCheckEnabled.value else true,
                        currentVersionDisplay = currentVersionDisplay,
                        currentBuildNumber = currentBuildNumber,
                        showWhatsNew = _uiState.value.showWhatsNew,
                        whatsNewEntries = _uiState.value.whatsNewEntries,
                    )
                    if (!AppUpdaterPlatform.isDebugBuild) {
                        checkForUpdates(force = true, showNoUpdateFeedback = false)
                    }
                }
            }
        }
        if (usesForkFeed) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                preferences.autoDailyCheckEnabled.collect { enabled ->
                    _uiState.update { it.copy(autoDailyCheckEnabled = enabled) }
                }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                preferences.lastCheckEpochMs.collect { epochMs ->
                    _uiState.update { it.copy(lastCheckEpochMs = epochMs) }
                }
            }
        }
    }

    fun setAutoDailyCheckEnabled(enabled: Boolean) {
        preferences.setAutoDailyCheckEnabled(enabled)
    }

    fun ensureAutoCheckStarted() {
        if (autoCheckStarted || !AppFeaturePolicy.inAppUpdaterEnabled ||
            !AppUpdaterPlatform.isSupported || AppUpdaterPlatform.isDebugBuild
        ) {
            return
        }
        autoCheckStarted = true
        if (usesForkFeed) {
            if (preferences.autoDailyCheckEnabled.value &&
                shouldRunDailyCheck(preferences.lastCheckEpochMs.value, nowEpochMs())
            ) {
                checkForUpdates(force = false, showNoUpdateFeedback = false)
            }
            scope.launch {
                while (true) {
                    delay(DAILY_CHECK_POLL_INTERVAL_MS)
                    if (preferences.autoDailyCheckEnabled.value &&
                        shouldRunDailyCheck(preferences.lastCheckEpochMs.value, nowEpochMs())
                    ) {
                        checkForUpdates(force = false, showNoUpdateFeedback = false)
                    }
                }
            }
        } else {
            checkForUpdates(force = false, showNoUpdateFeedback = false)
        }
    }

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        if (!AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) {
            if (showNoUpdateFeedback) {
                scope.launch {
                    NuvioToastController.show(getString(Res.string.updates_not_available))
                }
            }
            return
        }

        if (_uiState.value.isDownloading || _uiState.value.isVerifying || _uiState.value.isInstalling) return
        updateCheckJob?.cancel()
        val channel = preferences.channel.value
        updateCheckJob = scope.launch {
            _uiState.update { state ->
                state.copy(
                    isChecking = true,
                    errorMessage = null,
                    showInstallPermissionDialog = false,
                    isDebugTest = false,
                )
            }

            val ignoredTag = AppUpdaterPlatform.getIgnoredTag()
            val result = fetchUpdate(channel)
            currentCoroutineContext().ensureActive()
            if (channel != preferences.channel.value) return@launch

            result.onSuccess { update ->
                if (usesForkFeed) {
                    preferences.recordCheckCompleted(nowEpochMs())
                }
                val remoteNewer = if (usesForkFeed) {
                    NuvioRoidVersion.isRemoteNewer(
                        remoteVersion = update.versionDisplay,
                        remoteBuild = update.buildNumber,
                        localVersion = localForkVersion,
                    )
                } else if (channel == UpdateChannel.ALL_RELEASES) {
                    VersionUtils.isRemoteNewerLegacy(update.tag, AppUpdaterPlatform.currentVersionName)
                } else {
                    VersionUtils.isRemoteNewer(update.tag, AppUpdaterPlatform.currentVersionName)
                }
                val ignored = ignoredTag != null && ignoredTag == update.tag
                val shouldShowDialog = remoteNewer && (force || !ignored)
                val sameUpdateStaged = remoteNewer && _uiState.value.update?.tag == update.tag
                val pendingNotes = if (remoteNewer && usesForkFeed) {
                    computePendingUpdateNotes(update)
                } else {
                    emptyList()
                }

                _uiState.update { state ->
                    state.copy(
                        isChecking = false,
                        update = update.takeIf { remoteNewer },
                        isUpdateAvailable = remoteNewer,
                        pendingUpdateNotes = pendingNotes,
                        isDownloading = false,
                        isVerifying = false,
                        isInstalling = false,
                        downloadProgress = null,
                        downloadedUpdatePath = state.downloadedUpdatePath.takeIf { sameUpdateStaged },
                        deferredInstallReason = state.deferredInstallReason.takeIf { sameUpdateStaged },
                        showDialog = shouldShowDialog,
                        showInstallPermissionDialog = false,
                        errorMessage = null,
                    )
                }

                if (showNoUpdateFeedback && !remoteNewer) {
                    NuvioToastController.show(noUpdateMessage(channel))
                }
            }.onFailure { error ->
                if (usesForkFeed && error is NoChannelReleaseException) {
                    preferences.recordCheckCompleted(nowEpochMs())
                }
                _uiState.update { state ->
                    state.copy(
                        isChecking = false,
                        isDownloading = false,
                        isVerifying = false,
                        isInstalling = false,
                        downloadProgress = null,
                        downloadedUpdatePath = null,
                        deferredInstallReason = null,
                        update = null,
                        isUpdateAvailable = false,
                        pendingUpdateNotes = emptyList(),
                        showDialog = force && error !is NoChannelReleaseException,
                        showInstallPermissionDialog = false,
                        errorMessage = if (force && error !is NoChannelReleaseException) {
                            error.message ?: getString(Res.string.updates_check_failed)
                        } else {
                            null
                        },
                    )
                }

                if (showNoUpdateFeedback) {
                    NuvioToastController.show(
                        if (error is NoChannelReleaseException) noUpdateMessage(channel)
                        else error.message ?: getString(Res.string.updates_check_failed),
                    )
                }
            }
        }
    }

    private suspend fun noUpdateMessage(channel: UpdateChannel): String = getString(
        if (!usesForkFeed && channel == UpdateChannel.STABLE &&
            VersionUtils.isPrerelease(AppUpdaterPlatform.currentVersionName)
        ) {
            Res.string.updates_waiting_for_stable
        } else {
            Res.string.updates_latest_version
        },
    )

    fun showBannerIfUpdateAvailable() {
        if (_uiState.value.isUpdateAvailable && _uiState.value.update != null) {
            _uiState.update { it.copy(showDialog = true) }
        }
    }

    fun dismissDialog() {
        _uiState.update { state ->
            state.copy(
                showDialog = false,
                showInstallPermissionDialog = false,
                errorMessage = null,
            )
        }
    }

    fun dismissWhatsNew() {
        if (usesForkFeed) {
            preferences.markWhatsNewSeen(currentVersionDisplay)
        }
        _uiState.update { it.copy(showWhatsNew = false) }
    }

    private fun computePendingUpdateNotes(update: AppUpdate): List<ReleaseNoteEntry> {
        val targetVersion = NuvioRoidVersion.parse(update.versionDisplay, explicitBuild = update.buildNumber)
            ?: NuvioRoidVersion.parse(update.tag)
            ?: return emptyList()
        val targetEntry = ReleaseNoteEntry(
            version = targetVersion,
            versionDisplay = update.versionDisplay.ifBlank { targetVersion.format() },
            tag = update.tag,
            title = update.title,
            notes = update.notes,
            releaseUrl = update.releaseUrl,
        )
        val sourceReleases = if (update.releaseHistory.isNotEmpty()) {
            update.releaseHistory + targetEntry
        } else {
            listOf(targetEntry)
        }
        return ReleaseNotesParser.selectVersionRange(
            releases = sourceReleases,
            installedVersion = localForkVersion,
            targetVersion = targetVersion,
        ).ifEmpty { listOf(targetEntry) }
    }

    fun ignoreThisVersion() {
        val tag = _uiState.value.update?.tag ?: return
        AppUpdaterPlatform.setIgnoredTag(tag)
        pendingInstallWhenReady = false
        _uiState.update { it.copy(deferredInstallReason = null) }
        dismissDialog()
    }

    fun downloadUpdate() {
        if (_uiState.value.isDownloading || _uiState.value.isVerifying || _uiState.value.isInstalling) return
        val update = _uiState.value.update ?: return
        if (_uiState.value.isDebugTest) {
            runDebugDownloadTest()
            return
        }

        val alreadyDownloaded = _uiState.value.downloadedUpdatePath
        if (alreadyDownloaded != null) {
            installDownloadedUpdate()
            return
        }

        val previousDownload = downloadJob
        previousDownload?.cancel()
        val channel = preferences.channel.value
        downloadJob = scope.launch {
            previousDownload?.join()
            val downloadContext = currentCoroutineContext()
            _uiState.update { state ->
                state.copy(
                    isDownloading = true,
                    isVerifying = false,
                    deferredInstallReason = null,
                    downloadProgress = 0f,
                    errorMessage = null,
                )
            }

            val result = downloadAsset(
                update.assetUrl,
                update.assetName,
            ) { downloadedBytes, totalBytes ->
                downloadContext.ensureActive()
                val progress = if (totalBytes != null && totalBytes > 0L) {
                    (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
                _uiState.update { state ->
                    if (state.updateChannel == channel) state.copy(downloadProgress = progress) else state
                }
            }
            currentCoroutineContext().ensureActive()
            if (channel != preferences.channel.value) return@launch

            val downloadedPath = result.getOrElse { error ->
                _uiState.update { state ->
                    state.copy(
                        isDownloading = false,
                        isVerifying = false,
                        downloadProgress = null,
                        downloadedUpdatePath = null,
                        errorMessage = error.message ?: getString(Res.string.updates_download_failed),
                        showDialog = true,
                    )
                }
                return@launch
            }

            if (usesForkFeed) {
                _uiState.update { state ->
                    state.copy(
                        isDownloading = false,
                        isVerifying = true,
                        downloadProgress = 1f,
                        errorMessage = null,
                    )
                }
                val verification = verifyAsset(downloadedPath, update.expectedSha256)
                currentCoroutineContext().ensureActive()
                if (channel != preferences.channel.value) return@launch
                if (verification.isFailure) {
                    val error = verification.exceptionOrNull()
                    _uiState.update { state ->
                        state.copy(
                            isDownloading = false,
                            isVerifying = false,
                            downloadProgress = null,
                            downloadedUpdatePath = null,
                            errorMessage = error?.message ?: getString(Res.string.updates_download_failed),
                            showDialog = true,
                        )
                    }
                    return@launch
                }
            }

            _uiState.update { state ->
                state.copy(
                    isDownloading = false,
                    isVerifying = false,
                    downloadProgress = 1f,
                    downloadedUpdatePath = downloadedPath,
                    errorMessage = null,
                )
            }
            installDownloadedUpdate()
        }
    }

    fun installDownloadedUpdate() {
        val updatePath = _uiState.value.downloadedUpdatePath ?: return
        if (_uiState.value.isInstalling || _uiState.value.isVerifying || _uiState.value.isDownloading) return

        val blockReason = if (usesForkFeed) installGate.blockReason() else null
        if (blockReason != null) {
            pendingInstallWhenReady = true
            ensureGateObserverStarted()
            _uiState.update { state ->
                state.copy(
                    deferredInstallReason = blockReason,
                    errorMessage = null,
                    showDialog = true,
                )
            }
            return
        }

        if (!AppUpdaterPlatform.canInstallDownloadedUpdate()) {
            _uiState.update { state -> state.copy(showInstallPermissionDialog = true, showDialog = true) }
            return
        }

        pendingInstallWhenReady = false
        if (!usesForkFeed) {
            AppUpdaterPlatform.installDownloadedUpdate(updatePath).onSuccess {
                _uiState.update { state -> state.copy(showInstallPermissionDialog = false) }
            }.onFailure { error ->
                scope.launch {
                    val fallbackMessage = error.message ?: getString(Res.string.updates_install_failed)
                    _uiState.update { state ->
                        state.copy(
                            errorMessage = fallbackMessage,
                            showDialog = true,
                        )
                    }
                }
            }
            return
        }

        val expectedSha256 = _uiState.value.update?.expectedSha256
        val expectedVersion = _uiState.value.update?.versionDisplay
        installJob = scope.launch {
            _uiState.update { state ->
                state.copy(
                    isInstalling = true,
                    deferredInstallReason = null,
                    showInstallPermissionDialog = false,
                    errorMessage = null,
                )
            }
            val result = installAsset(updatePath, expectedSha256, expectedVersion) {
                installGate.blockReason()?.let { reason -> throw InstallDeferredException(reason) }
            }
            result.onSuccess {
                _uiState.update { state ->
                    state.copy(
                        isInstalling = false,
                        showInstallPermissionDialog = false,
                    )
                }
            }.onFailure { error ->
                if (error is InstallDeferredException) {
                    pendingInstallWhenReady = true
                    ensureGateObserverStarted()
                    _uiState.update { state ->
                        state.copy(
                            isInstalling = false,
                            deferredInstallReason = error.reason,
                            showInstallPermissionDialog = false,
                            errorMessage = null,
                            showDialog = true,
                        )
                    }
                    return@onFailure
                }
                val fallbackMessage = error.message ?: getString(Res.string.updates_install_failed)
                _uiState.update { state ->
                    state.copy(
                        isInstalling = false,
                        errorMessage = fallbackMessage,
                        showDialog = true,
                    )
                }
            }
        }
    }

    internal fun refreshInstallGate() {
        if (!pendingInstallWhenReady || _uiState.value.downloadedUpdatePath == null) return
        val reason = installGate.blockReason()
        if (reason != null) {
            _uiState.update { it.copy(deferredInstallReason = reason) }
        } else {
            pendingInstallWhenReady = false
            _uiState.update { state ->
                state.copy(
                    deferredInstallReason = null,
                    showDialog = true,
                )
            }
        }
    }

    private fun ensureGateObserverStarted() {
        if (gateObserverStarted || !usesForkFeed) return
        gateObserverStarted = true
        scope.launch {
            combine(
                AppPresenceState.current,
                DownloadsRepository.uiState,
                DownloadsRepository.activeCompanionSavesFlow(),
            ) { presence, downloads, companionsActive ->
                Triple(presence, downloads, companionsActive)
            }.collect {
                refreshInstallGate()
            }
        }
    }

    fun resumeInstallation() {
        if (AppUpdaterPlatform.canInstallDownloadedUpdate()) {
            installDownloadedUpdate()
        } else {
            AppUpdaterPlatform.openInstallPermissionSettings()
        }
    }

    fun showDebugTestUpdate() {
        if (!AppUpdaterPlatform.isDebugBuild || !AppUpdaterPlatform.isSupported) return

        updateCheckJob?.cancel()
        downloadJob?.cancel()
        _uiState.value = AppUpdaterUiState(
            updateChannel = preferences.channel.value,
            update = AppUpdate(
                tag = "9.9.9",
                title = "Nuvio 9.9.9",
                notes = """
                    A local preview of the new update experience.

                    - The banner pushes the app content down.
                    - Download progress fills the banner with the primary accent.
                    - Release notes live behind the info button.
                """.trimIndent(),
                releaseUrl = null,
                assetName = "Nuvio-debug-preview.apk",
                assetUrl = "debug://update-preview",
                assetSizeBytes = 185L * 1024L * 1024L,
            ),
            isUpdateAvailable = true,
            showDialog = true,
            isDebugTest = true,
        )
    }

    private fun runDebugDownloadTest() {
        downloadJob = scope.launch {
            _uiState.update { state ->
                state.copy(
                    isDownloading = true,
                    downloadProgress = 0f,
                    errorMessage = null,
                )
            }

            for (step in 1..100) {
                delay(35)
                _uiState.update { state -> state.copy(downloadProgress = step / 100f) }
            }

            _uiState.update { state ->
                state.copy(
                    isDownloading = false,
                    isUpdateAvailable = false,
                    downloadProgress = 1f,
                )
            }
        }
    }

    companion object {
        private fun defaultUpdateFetcher(): suspend (UpdateChannel) -> Result<AppUpdate> = { channel ->
            if (AppUpdaterPlatform.usesForkUpdateFeed) {
                UpdateFeedSelector.currentFeed().fetchLatest().mapCatching { candidate ->
                    candidate ?: throw NoChannelReleaseException()
                    AppUpdate(
                        tag = candidate.tag,
                        title = candidate.title,
                        notes = candidate.notes,
                        releaseUrl = candidate.releaseUrl,
                        assetName = candidate.assetName,
                        assetUrl = candidate.assetUrl,
                        assetSizeBytes = candidate.assetSizeBytes,
                        expectedSha256 = candidate.expectedSha256,
                        versionDisplay = candidate.versionDisplay,
                        buildNumber = candidate.version.build,
                        releaseHistory = candidate.releaseHistory,
                    )
                }
            } else {
                AppUpdaterRepository.getLatestChannelUpdate(channel)
            }
        }
    }
}

@Composable
fun rememberAppUpdaterController(): AppUpdaterController {
    val scope = rememberCoroutineScope()
    return remember(scope) { AppUpdaterController(scope) }
}

internal fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val units = listOf("B", "KB", "MB", "GB")
    var value = sizeBytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val roundedValue = if (value >= 10 || unitIndex == 0) {
        value.toInt().toString()
    } else {
        ((value * 10).toInt() / 10.0).toString()
    }
    return "$roundedValue ${localizedByteUnit(units[unitIndex])}"
}
