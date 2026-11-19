package com.nuvio.app.features.updater

data class AppUpdateReleaseSource(
    val owner: String,
    val repo: String,
    val channelBranch: String? = null,
    val includePrereleases: Boolean = false,
    val userAgent: String,
)

data class AppUpdateAssetSelector(
    val fileExtensions: List<String>,
    val contentTypes: List<String> = emptyList(),
    val preferredNameFragments: List<String> = emptyList(),
    val fallbackNameFragments: List<String> = emptyList(),
)

expect object AppUpdaterPlatform {
    val isSupported: Boolean
    val isDebugBuild: Boolean
    val hasSingleUpdateChannel: Boolean
    val usesForkUpdateFeed: Boolean

    fun getSupportedAbis(): List<String>

    fun currentHostArch(): String

    val releaseSource: AppUpdateReleaseSource

    val assetSelector: AppUpdateAssetSelector

    val currentVersionName: String

    val currentBuildNumber: Int

    fun getIgnoredTag(): String?

    fun setIgnoredTag(tag: String?)

    fun getUpdateChannel(): String?

    fun setUpdateChannel(channel: String)

    fun getAutoDailyCheckEnabled(): Boolean

    fun setAutoDailyCheckEnabled(enabled: Boolean)

    fun getLastCheckEpochMs(): Long?

    fun setLastCheckEpochMs(epochMs: Long)

    fun getLastWhatsNewVersion(): String?

    fun setLastWhatsNewVersion(version: String?)

    fun nowEpochMs(): Long

    fun deleteDownloadedUpdate(path: String)

    suspend fun readLocalFeedText(location: String): Result<String>

    suspend fun downloadUpdateAsset(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String>

    suspend fun verifyDownloadedUpdate(path: String, expectedSha256: String?): Result<Unit>

    fun canInstallDownloadedUpdate(): Boolean

    fun openInstallPermissionSettings()

    fun installDownloadedUpdate(path: String): Result<Unit>

    suspend fun installVerifiedUpdate(
        path: String,
        expectedSha256: String?,
        expectedVersion: String? = null,
        ensureCanCommitInstall: () -> Unit = {},
    ): Result<Unit>
}
