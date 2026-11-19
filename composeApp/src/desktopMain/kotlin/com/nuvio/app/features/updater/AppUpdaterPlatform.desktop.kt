package com.nuvio.app.features.updater

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.settings.ForkIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_download_failed
import nuvio.composeapp.generated.resources.updates_download_failed_http
import nuvio.composeapp.generated.resources.updates_downloaded_file_missing
import nuvio.composeapp.generated.resources.updates_empty_download_body
import org.jetbrains.compose.resources.getString
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

private const val desktopUpdaterPreferencesName = "nuvio_updater"
private const val ignoredTagKey = "ignored_release_tag"
private const val updateChannelKey = "update_channel"
private const val autoDailyCheckKey = "auto_daily_check_enabled"
private const val lastCheckEpochMsKey = "last_check_epoch_ms"
private const val lastWhatsNewVersionKey = "last_seen_whats_new_version"

private val desktopUpdaterHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(60))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

actual object AppUpdaterPlatform {
    private val currentOs: DesktopUpdaterOs = DesktopUpdaterOs.current()
    private val store = DesktopStorage.store(desktopUpdaterPreferencesName)
    actual val isDebugBuild: Boolean = false
    actual val hasSingleUpdateChannel: Boolean = true
    actual val usesForkUpdateFeed: Boolean = true

    private val linuxInstallMethod: LinuxInstallMethod by lazy {
        if (currentOs == DesktopUpdaterOs.LINUX) detectLinuxInstallMethod() else LinuxInstallMethod.UNKNOWN
    }

    actual val isSupported: Boolean
        get() = currentOs == DesktopUpdaterOs.MACOS

    actual val releaseSource: AppUpdateReleaseSource = AppUpdateReleaseSource(
        owner = UpdateFeedSelector.PUBLIC_OWNER,
        repo = UpdateFeedSelector.PUBLIC_REPO,
        channelBranch = null,
        includePrereleases = true,
        userAgent = "NuvioRoid",
    )

    actual val assetSelector: AppUpdateAssetSelector
        get() = currentOs.assetSelector(linuxInstallMethod)

    actual val currentVersionName: String = NuvioRoidVersion.fromForkIdentity().format()

    actual val currentBuildNumber: Int = ForkIdentity.BUILD

    actual fun getSupportedAbis(): List<String> = emptyList()

    actual fun currentHostArch(): String {
        val arch = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
        return when {
            arch == "aarch64" || arch == "arm64" -> "arm64"
            arch.contains("64") -> "x86_64"
            else -> arch
        }
    }

    actual fun getIgnoredTag(): String? = store.getString(ignoredTagKey)

    actual fun setIgnoredTag(tag: String?) {
        store.putString(ignoredTagKey, tag)
    }

    actual fun getUpdateChannel(): String? = store.getString(updateChannelKey)

    actual fun setUpdateChannel(channel: String) {
        store.putString(updateChannelKey, channel)
    }

    actual fun getAutoDailyCheckEnabled(): Boolean =
        store.getBoolean(autoDailyCheckKey) ?: true

    actual fun setAutoDailyCheckEnabled(enabled: Boolean) {
        store.putBoolean(autoDailyCheckKey, enabled)
    }

    actual fun getLastCheckEpochMs(): Long? =
        store.getString(lastCheckEpochMsKey)?.toLongOrNull()?.takeIf { it > 0L }

    actual fun setLastCheckEpochMs(epochMs: Long) {
        store.putString(lastCheckEpochMsKey, epochMs.toString())
    }

    actual fun getLastWhatsNewVersion(): String? = store.getString(lastWhatsNewVersionKey)

    actual fun setLastWhatsNewVersion(version: String?) {
        store.putString(lastWhatsNewVersionKey, version)
    }

    actual fun nowEpochMs(): Long = System.currentTimeMillis()

    actual fun deleteDownloadedUpdate(path: String) {
        val tempRoot = MacOsUpdateInstaller.tempUpdatesDir().toAbsolutePath().normalize()
        if (Files.isSymbolicLink(tempRoot) || !Files.isDirectory(tempRoot, LinkOption.NOFOLLOW_LINKS)) return
        val realRoot = runCatching { tempRoot.toRealPath() }.getOrNull() ?: return
        val target = runCatching { Path.of(path).toAbsolutePath().normalize() }.getOrNull() ?: return
        if (!target.startsWith(tempRoot) || target == tempRoot) return
        val realParent = runCatching { target.parent?.toRealPath() }.getOrNull() ?: return
        if (!realParent.startsWith(realRoot)) return
        runCatching { MacOsUpdateInstaller.deletePathWithoutFollowingSymlinks(target) }
    }

    actual suspend fun readLocalFeedText(location: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val path = resolveLocalPath(location)
            require(Files.isRegularFile(path)) {
                "Update manifest file was not found at $location"
            }
            Files.readString(path, Charsets.UTF_8)
        }
    }

    actual suspend fun downloadUpdateAsset(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val tempDir = MacOsUpdateInstaller.tempUpdatesDir()
            MacOsUpdateInstaller.clearTempUpdatesDir(tempDir)
            val normalizedTempDir = tempDir.toAbsolutePath().normalize()
            val safeName = assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val destinationPath = normalizedTempDir.resolve(safeName).normalize()
            require(
                safeName.isNotBlank() &&
                    safeName != "." &&
                    safeName != ".." &&
                    destinationPath.parent == normalizedTempDir,
            ) { "Invalid update file name: $assetName" }
            val destination = destinationPath.toFile()

            if (!PrivateManifestUpdateFeed.isHttpLocation(assetUrl)) {
                return@runCatching stageLocalAsset(
                    source = resolveLocalPath(assetUrl),
                    destination = destination,
                    onProgress = onProgress,
                )
            }

            val tempFile = tempDir.resolve("$safeName.part").toFile()
            val request = HttpRequest.newBuilder()
                .uri(URI(assetUrl))
                .header("User-Agent", "NuvioRoid")
                .GET()
                .build()
            val response = desktopUpdaterHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                error(runBlocking { getString(Res.string.updates_download_failed_http, response.statusCode()) })
            }

            val totalBytes = response.headers().firstValue("Content-Length").orElse(null)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            var downloadedBytes = 0L
            try {
                response.body()?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read.toLong()
                            onProgress(downloadedBytes, totalBytes)
                        }
                        output.flush()
                    }
                } ?: error(runBlocking { getString(Res.string.updates_empty_download_body) })

                if (totalBytes != null && downloadedBytes != totalBytes) {
                    error(runBlocking { getString(Res.string.updates_download_failed) })
                }

                if (!tempFile.renameTo(destination)) {
                    tempFile.copyTo(destination, overwrite = true)
                    tempFile.delete()
                }
                destination.absolutePath
            } catch (t: Throwable) {
                if (tempFile.exists()) tempFile.delete()
                throw t
            }
        }
    }

    actual suspend fun verifyDownloadedUpdate(path: String, expectedSha256: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                when (val result = MacOsUpdateInstaller.verifyFileSha256(Path.of(path), expectedSha256)) {
                    is Sha256VerificationResult.Verified -> Unit
                    is Sha256VerificationResult.Refused -> error(result.message)
                }
            }
        }

    actual fun canInstallDownloadedUpdate(): Boolean = currentOs == DesktopUpdaterOs.MACOS

    actual fun openInstallPermissionSettings() = Unit

    actual fun installDownloadedUpdate(path: String): Result<Unit> = runBlocking {
        installVerifiedUpdate(path, expectedSha256 = null)
    }

    actual suspend fun installVerifiedUpdate(
        path: String,
        expectedSha256: String?,
        expectedVersion: String?,
        ensureCanCommitInstall: () -> Unit,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (currentOs != DesktopUpdaterOs.MACOS) {
                return@withContext Result.failure(
                    IllegalStateException("In-app updates are only supported on macOS."),
                )
            }
            MacOsUpdateInstaller.installVerifiedArtifact(
                stagedArtifact = Path.of(path),
                expectedSha256 = expectedSha256,
                expectedVersion = expectedVersion?.let { NuvioRoidVersion.parse(it) },
                canCommitInstall = {
                    ensureCanCommitInstall()
                    null
                },
            )
        }

    private fun stageLocalAsset(
        source: Path,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): String {
        check(source.exists()) { runBlocking { getString(Res.string.updates_downloaded_file_missing) } }
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(source)) {
            error(
                "Uncompressed app folders can't be verified with a SHA-256 code. " +
                    "Package the update as a .dmg file with a sha256 checksum instead.",
            )
        }
        val totalBytes = runCatching { Files.size(source) }.getOrNull()?.takeIf { it > 0L }
        val tempFile = File(destination.parentFile, "${destination.name}.part")
        var copiedBytes = 0L
        try {
            Files.newInputStream(source).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copiedBytes += read.toLong()
                        onProgress(copiedBytes, totalBytes)
                    }
                    output.flush()
                }
            }
            if (!tempFile.renameTo(destination)) {
                tempFile.copyTo(destination, overwrite = true)
                tempFile.delete()
            }
            return destination.absolutePath
        } catch (t: Throwable) {
            if (tempFile.exists()) tempFile.delete()
            throw t
        }
    }

    private fun resolveLocalPath(location: String): Path {
        val trimmed = location.trim()
        return when {
            trimmed.startsWith("file://", ignoreCase = true) -> Paths.get(URI(trimmed))
            trimmed.startsWith("~/") -> Path.of(System.getProperty("user.home"), trimmed.substring(2))
            else -> Path.of(trimmed)
        }.toAbsolutePath().normalize()
    }
}

private enum class DesktopUpdaterOs {
    WINDOWS,
    MACOS,
    LINUX,
    UNKNOWN;

    fun assetSelector(linuxInstallMethod: LinuxInstallMethod): AppUpdateAssetSelector {
        val archFragments = desktopArchitectureFragments()
        return when (this) {
            WINDOWS -> AppUpdateAssetSelector(
                fileExtensions = listOf(".msi", ".exe"),
                preferredNameFragments = archFragments + listOf("windows", "win"),
                fallbackNameFragments = listOf("universal", "all"),
            )
            MACOS -> AppUpdateAssetSelector(
                fileExtensions = listOf(".dmg"),
                preferredNameFragments = archFragments + listOf("macos", "mac", "darwin"),
                fallbackNameFragments = listOf("universal", "all"),
            )
            LINUX -> AppUpdateAssetSelector(
                fileExtensions = linuxUpdateFileExtensions(linuxInstallMethod),
                preferredNameFragments = archFragments + listOf("linux"),
                fallbackNameFragments = listOf("universal", "all"),
            )
            UNKNOWN -> AppUpdateAssetSelector(fileExtensions = emptyList())
        }
    }

    companion object {
        fun current(): DesktopUpdaterOs {
            val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            return when {
                osName.contains("win") -> WINDOWS
                osName.contains("mac") -> MACOS
                osName.contains("linux") -> LINUX
                else -> UNKNOWN
            }
        }
    }
}

private fun desktopArchitectureFragments(): List<String> {
    val arch = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
    return when {
        arch == "aarch64" || arch == "arm64" -> listOf("arm64", "aarch64")
        arch == "x86" || arch == "i386" || arch == "i686" -> listOf("x86", "i386", "i686")
        arch.contains("64") -> listOf("x64", "x86_64", "amd64")
        else -> emptyList()
    }
}

internal fun windowsInstallerCommand(updateFile: File): List<String> {
    if (!updateFile.extension.equals("msi", ignoreCase = true)) {
        return listOf(updateFile.absolutePath)
    }

    return listOf("msiexec", "/i", updateFile.absolutePath)
}

internal enum class LinuxInstallMethod {
    APP_IMAGE,
    FLATPAK,
    RPM,
    DEB,
    UNKNOWN,
}

internal const val appImageEnvName = "APPIMAGE"
private const val flatpakIdEnvName = "FLATPAK_ID"
private const val flatpakInfoPath = "/.flatpak-info"
private const val jpackageAppPathProperty = "jpackage.app-path"
private const val javaExecutableName = "java"
private const val packageQueryTimeoutSeconds = 3L
private const val appImageExitWaitTicks = 100

internal fun linuxUpdateFileExtensions(method: LinuxInstallMethod): List<String> = when (method) {
    LinuxInstallMethod.APP_IMAGE -> listOf(".AppImage")
    LinuxInstallMethod.RPM -> listOf(".rpm")
    LinuxInstallMethod.DEB -> listOf(".deb")
    LinuxInstallMethod.FLATPAK -> emptyList()
    LinuxInstallMethod.UNKNOWN -> listOf(".deb", ".AppImage")
}

internal fun resolveLinuxInstallMethod(
    appImagePath: String?,
    appImageExists: Boolean,
    flatpakId: String?,
    flatpakInfoExists: Boolean,
    launcherPath: String?,
    isOwnedByRpm: (String) -> Boolean,
    isOwnedByDpkg: (String) -> Boolean,
): LinuxInstallMethod {
    if (!appImagePath.isNullOrBlank() && appImageExists) return LinuxInstallMethod.APP_IMAGE
    if (!flatpakId.isNullOrBlank() || flatpakInfoExists) return LinuxInstallMethod.FLATPAK

    val path = launcherPath?.takeIf { it.isNotBlank() } ?: return LinuxInstallMethod.UNKNOWN
    if (isOwnedByRpm(path)) return LinuxInstallMethod.RPM
    if (isOwnedByDpkg(path)) return LinuxInstallMethod.DEB
    return LinuxInstallMethod.UNKNOWN
}

private fun detectLinuxInstallMethod(): LinuxInstallMethod {
    val appImagePath = System.getenv(appImageEnvName)
    return resolveLinuxInstallMethod(
        appImagePath = appImagePath,
        appImageExists = !appImagePath.isNullOrBlank() && File(appImagePath).exists(),
        flatpakId = System.getenv(flatpakIdEnvName),
        flatpakInfoExists = File(flatpakInfoPath).exists(),
        launcherPath = linuxLauncherPath(),
        isOwnedByRpm = { path -> packageQuerySucceeds(listOf("rpm", "-qf", path)) },
        isOwnedByDpkg = { path -> packageQuerySucceeds(listOf("dpkg", "-S", path)) },
    )
}

private fun linuxLauncherPath(): String? = linuxLauncherPathFrom(
    jpackageAppPath = System.getProperty(jpackageAppPathProperty),
    processCommand = ProcessHandle.current().info().command().orElse(null),
)

internal fun linuxLauncherPathFrom(
    jpackageAppPath: String?,
    processCommand: String?,
): String? {
    jpackageAppPath?.takeIf { it.isNotBlank() }?.let { return it }

    return processCommand?.takeIf { it.isNotBlank() && File(it).name != javaExecutableName }
}

private fun packageQuerySucceeds(command: List<String>): Boolean = runCatching {
    val process = ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (!process.waitFor(packageQueryTimeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching false
    }
    process.exitValue() == 0
}.getOrDefault(false)

internal fun linuxInstallerCommand(
    method: LinuxInstallMethod,
    updateFile: File,
    appImagePath: String?,
    currentPid: Long,
): List<String> {
    if (method == LinuxInstallMethod.APP_IMAGE && !appImagePath.isNullOrBlank()) {
        return listOf("sh", "-c", appImageReplaceScript(updateFile.absolutePath, appImagePath, currentPid))
    }

    return listOf("xdg-open", updateFile.absolutePath)
}

internal fun appImageReplaceScript(
    downloadedPath: String,
    appImagePath: String,
    currentPid: Long,
): String {
    val downloaded = singleQuote(downloadedPath)
    val target = singleQuote(appImagePath)
    return buildString {
        append("i=0; ")
        append("while [ \$i -lt $appImageExitWaitTicks ] && kill -0 $currentPid 2>/dev/null; do ")
        append("sleep 0.1; i=\$((i+1)); ")
        append("done; ")
        append("if cp -f -- $downloaded $target; then ")
        append("chmod +x -- $target; rm -f -- $downloaded; exec $target; ")
        append("else xdg-open $downloaded; fi")
    }
}

private fun singleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
