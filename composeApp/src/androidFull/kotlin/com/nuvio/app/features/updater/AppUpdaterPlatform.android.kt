package com.nuvio.app.features.updater

import com.nuvio.app.core.build.AppVersionConfig

actual object AppUpdaterPlatform {
    actual val isSupported: Boolean = true
    actual val isDebugBuild: Boolean
        get() = AndroidAppUpdaterPlatform.isDebugBuild()
    actual val hasSingleUpdateChannel: Boolean = false
    actual val usesForkUpdateFeed: Boolean = false

    actual val releaseSource: AppUpdateReleaseSource = AppUpdateReleaseSource(
        owner = "NuvioMedia",
        repo = "NuvioMobile",
        channelBranch = "cmp-rewrite",
        userAgent = "NuvioMobile",
    )

    actual val assetSelector: AppUpdateAssetSelector
        get() = AppUpdateAssetSelector(
            fileExtensions = listOf(".apk"),
            contentTypes = listOf("application/vnd.android.package-archive"),
            preferredNameFragments = AndroidAppUpdaterPlatform.getSupportedAbis(),
            fallbackNameFragments = listOf("universal", "all"),
        )

    actual val currentVersionName: String = AppVersionConfig.VERSION_NAME

    actual val currentBuildNumber: Int = AppVersionConfig.VERSION_CODE

    actual fun getSupportedAbis(): List<String> = AndroidAppUpdaterPlatform.getSupportedAbis()

    actual fun currentHostArch(): String = ""

    actual fun getIgnoredTag(): String? = AndroidAppUpdaterPlatform.getIgnoredTag()

    actual fun setIgnoredTag(tag: String?) {
        AndroidAppUpdaterPlatform.setIgnoredTag(tag)
    }

    actual fun getUpdateChannel(): String? = AndroidAppUpdaterPlatform.getUpdateChannel()

    actual fun setUpdateChannel(channel: String) {
        AndroidAppUpdaterPlatform.setUpdateChannel(channel)
    }

    actual fun getAutoDailyCheckEnabled(): Boolean = true

    actual fun setAutoDailyCheckEnabled(enabled: Boolean) = Unit

    actual fun getLastCheckEpochMs(): Long? = null

    actual fun setLastCheckEpochMs(epochMs: Long) = Unit

    actual fun getLastWhatsNewVersion(): String? = null

    actual fun setLastWhatsNewVersion(version: String?) = Unit

    actual fun nowEpochMs(): Long = System.currentTimeMillis()

    actual fun deleteDownloadedUpdate(path: String) {
        AndroidAppUpdaterPlatform.deleteDownloadedUpdate(path)
    }

    actual suspend fun readLocalFeedText(location: String): Result<String> =
        Result.failure(UnsupportedOperationException("Local update feeds are not supported on Android."))

    actual suspend fun downloadUpdateAsset(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = AndroidAppUpdaterPlatform.downloadUpdateAsset(assetUrl, assetName, onProgress)

    actual suspend fun verifyDownloadedUpdate(path: String, expectedSha256: String?): Result<Unit> =
        Result.success(Unit)

    actual fun canInstallDownloadedUpdate(): Boolean = AndroidAppUpdaterPlatform.canRequestPackageInstalls()

    actual fun openInstallPermissionSettings() {
        AndroidAppUpdaterPlatform.openUnknownSourcesSettings()
    }

    actual fun installDownloadedUpdate(path: String): Result<Unit> = AndroidAppUpdaterPlatform.installDownloadedUpdate(path)

    actual suspend fun installVerifiedUpdate(
        path: String,
        expectedSha256: String?,
        expectedVersion: String?,
        ensureCanCommitInstall: () -> Unit,
    ): Result<Unit> =
        AndroidAppUpdaterPlatform.installDownloadedUpdate(path)
}
