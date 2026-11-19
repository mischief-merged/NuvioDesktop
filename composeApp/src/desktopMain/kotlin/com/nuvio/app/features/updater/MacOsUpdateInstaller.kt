package com.nuvio.app.features.updater

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_staged_bytes_changed
import nuvio.composeapp.generated.resources.updates_payload_build_missing
import org.jetbrains.compose.resources.getString
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.awt.Desktop
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.concurrent.thread
import kotlin.system.exitProcess

internal sealed interface Sha256VerificationResult {
    data class Verified(val actualSha256: String) : Sha256VerificationResult
    data class Refused(val message: String, val actualSha256: String? = null) : Sha256VerificationResult
}

internal data class SettingsBackupResult(
    val backupDir: Path,
    val backedUpFiles: List<String>,
)

internal data class MacOsInstallCommands(
    val attachDmg: (dmgPath: Path, mountDir: Path) -> Result<Unit>,
    val detachDmg: (mountDir: Path) -> Unit,
    val copyApp: (sourceApp: Path, destinationApp: Path) -> Result<Unit>,
    val clearQuarantine: (appPath: Path) -> Result<Unit>,
    val moveToTrash: (target: Path) -> Boolean,
    val launchRelaunchHelper: (installedApp: Path, stageDir: Path, currentPid: Long) -> Result<Unit>,
    val scheduleExit: () -> Unit,
)

internal data class ParsedAppBundleMetadata(
    val bundleIdentifier: String?,
    val executableName: String?,
    val nuvioRoidVersion: String?,
    val bundleShortVersion: String?,
    val bundleVersion: String?,
)

internal object MacOsUpdateInstaller {
    const val EXPECTED_BUNDLE_IDENTIFIER = "io.github.mischiefmerged.nuvioroid"
    private val hexSha256Pattern = Regex("^[0-9a-fA-F]{64}$")
    private const val COMMAND_TIMEOUT_SECONDS = 120L

    fun tempUpdatesDir(): Path =
        Path.of(System.getProperty("java.io.tmpdir"), "nuvioroid-updates").also { dir ->
            ensurePrivateDirectory(dir)
        }

    fun clearTempUpdatesDir(dir: Path = tempUpdatesDir()) {
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return
        Files.list(dir).use { stream ->
            stream.forEach { entry ->
                runCatching { deletePathWithoutFollowingSymlinks(entry) }
            }
        }
    }

    /**
     * Deletes [path] (and its descendants if it is a real directory) without ever following
     * symbolic links at [path] or anywhere inside it.
     */
    fun deletePathWithoutFollowingSymlinks(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path)
            return
        }
        Files.walkFileTree(
            path,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    if (Files.isSymbolicLink(file)) {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }
                    throw exc
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    fun verifyFileSha256(file: Path, expectedSha256: String?): Sha256VerificationResult {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Sha256VerificationResult.Refused("The downloaded update file is missing.")
        }
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            return Sha256VerificationResult.Refused(
                "Uncompressed app folders can't be verified with a SHA-256 code. " +
                    "Package the update as a .dmg file with a sha256 checksum instead.",
            )
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            runCatching { deletePathWithoutFollowingSymlinks(file) }
            return Sha256VerificationResult.Refused("The downloaded update file is not a regular file.")
        }
        val expected = validateExpectedSha256(expectedSha256)
            ?: run {
                runCatching { deletePathWithoutFollowingSymlinks(file) }
                val message = if (expectedSha256.isNullOrBlank()) {
                    "This update didn't include a verification code, so it wasn't installed."
                } else {
                    "This update's verification code is invalid, so it wasn't installed."
                }
                return Sha256VerificationResult.Refused(message)
            }
        val actual = runCatching { computeSha256Hex(file) }.getOrElse { error ->
            return Sha256VerificationResult.Refused(
                "Couldn't verify the downloaded update (${error.message ?: "read error"}).",
            )
        }
        if (actual != expected) {
            runCatching { deletePathWithoutFollowingSymlinks(file) }
            return Sha256VerificationResult.Refused(
                message = "The downloaded update didn't match its verification code and wasn't installed.",
                actualSha256 = actual,
            )
        }
        return Sha256VerificationResult.Verified(actual)
    }

    fun computeSha256Hex(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        Files.newInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyAndVerifyFileSha256(
        source: Path,
        destination: Path,
        expectedSha256: String?,
    ): Sha256VerificationResult {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return Sha256VerificationResult.Refused("The downloaded update file is missing.")
        }
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            return Sha256VerificationResult.Refused(
                "Uncompressed app folders can't be verified with a SHA-256 code. " +
                    "Package the update as a .dmg file with a sha256 checksum instead.",
            )
        }
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            runCatching { deletePathWithoutFollowingSymlinks(source) }
            return Sha256VerificationResult.Refused("The downloaded update file is not a regular file.")
        }
        val expected = validateExpectedSha256(expectedSha256)
            ?: run {
                runCatching { deletePathWithoutFollowingSymlinks(source) }
                val message = if (expectedSha256.isNullOrBlank()) {
                    "This update didn't include a verification code, so it wasn't installed."
                } else {
                    "This update's verification code is invalid, so it wasn't installed."
                }
                return Sha256VerificationResult.Refused(message)
            }

        val actual = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            createPrivateRegularFile(destination)
            Files.newInputStream(source).use { input ->
                Files.newOutputStream(destination).use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrElse { error ->
            runCatching { deletePathWithoutFollowingSymlinks(destination) }
            return Sha256VerificationResult.Refused(
                "Couldn't verify the downloaded update (${error.message ?: "read error"}).",
            )
        }

        if (actual != expected) {
            runCatching { deletePathWithoutFollowingSymlinks(destination) }
            runCatching { deletePathWithoutFollowingSymlinks(source) }
            return Sha256VerificationResult.Refused(
                message = "The downloaded update didn't match its verification code and wasn't installed.",
                actualSha256 = actual,
            )
        }
        return Sha256VerificationResult.Verified(actual)
    }

    private fun validateExpectedSha256(expectedSha256: String?): String? {
        val normalized = expectedSha256?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty() || !hexSha256Pattern.matches(normalized)) return null
        return normalized
    }

    /**
     * Copies settings files from [appSupportDir] into a timestamped folder inside [backupRootDir].
     * Never writes to, deletes, or alters any file in [appSupportDir].
     */
    fun backUpSettingsFiles(
        appSupportDir: Path = DesktopStorage.rootDir,
        backupRootDir: Path = DesktopStorage.cacheDir.resolve("update-settings-backups"),
        timestampMs: Long = System.currentTimeMillis(),
    ): Result<SettingsBackupResult> = runCatching {
        val targetDir = backupRootDir.resolve("backup-$timestampMs")
        Files.createDirectories(targetDir)
        if (!Files.isDirectory(appSupportDir, LinkOption.NOFOLLOW_LINKS)) {
            return@runCatching SettingsBackupResult(targetDir, emptyList())
        }
        val backedUp = mutableListOf<String>()
        Files.list(appSupportDir).use { stream ->
            stream.forEach { entry ->
                val name = entry.fileName?.toString().orEmpty()
                if (
                    !Files.isSymbolicLink(entry) &&
                    Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) &&
                    name.endsWith(".properties", ignoreCase = true)
                ) {
                    Files.copy(entry, targetDir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
                    backedUp += name
                }
            }
        }
        SettingsBackupResult(backupDir = targetDir, backedUpFiles = backedUp.sorted())
    }

    fun resolveRunningAppBundle(
        jpackageAppPath: String? = System.getProperty("jpackage.app-path"),
        processCommand: String? = ProcessHandle.current().info().command().orElse(null),
    ): Path? {
        val executable = jpackageAppPath?.trim()?.takeIf { it.isNotEmpty() }
            ?: processCommand?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val exePath = runCatching { Path.of(executable).toAbsolutePath().normalize() }.getOrNull() ?: return null
        // Walk up from Contents/MacOS/<binary> to *.app
        var cursor: Path? = exePath.parent
        while (cursor != null) {
            val name = cursor.fileName?.toString().orEmpty()
            if (name.endsWith(".app", ignoreCase = true) && Files.isDirectory(cursor.resolve("Contents"))) {
                return cursor
            }
            cursor = cursor.parent
        }
        return null
    }

    fun installVerifiedArtifact(
        stagedArtifact: Path,
        expectedSha256: String?,
        runningAppBundle: Path? = resolveRunningAppBundle(),
        appSupportDir: Path = DesktopStorage.rootDir,
        backupRootDir: Path = DesktopStorage.cacheDir.resolve("update-settings-backups"),
        tempRoot: Path = tempUpdatesDir(),
        currentPid: Long = ProcessHandle.current().pid(),
        timestampMs: Long = System.currentTimeMillis(),
        expectedVersion: NuvioRoidVersion? = null,
        installedVersion: NuvioRoidVersion? = NuvioRoidVersion.fromForkIdentity(),
        canCommitInstall: () -> InstallBlockReason? = { null },
        commands: MacOsInstallCommands = defaultCommands(),
    ): Result<Unit> = runCatching {
        val targetApp = runningAppBundle
            ?: error(
                "Automatic updates only work from an installed NuvioRoid.app bundle. " +
                    "Open the downloaded file to update manually.",
            )
        val targetParent = targetApp.parent
            ?: error("Could not find the folder containing NuvioRoid.app.")
        val normalizedTarget = targetApp.toAbsolutePath().normalize()
        if (normalizedTarget.startsWith(Path.of("/Volumes"))) {
            error("Move NuvioRoid into your Applications folder before updating.")
        }
        if (!Files.isWritable(targetParent)) {
            error(
                "NuvioRoid doesn't have permission to update in $targetParent. " +
                    "Move it to a writable Applications folder and try again.",
            )
        }

        ensurePrivateDirectory(tempRoot)
        val stageDir = createPrivateTempDirectory(tempRoot, "stage-")
        var stagingParentInApps: Path? = null
        var backupParentInApps: Path? = null
        var keepStageDirForRelaunchHelper = false

        try {
            val verifiedDmg = stageDir.resolve("verified.dmg")
            when (val verification = copyAndVerifyFileSha256(stagedArtifact, verifiedDmg, expectedSha256)) {
                is Sha256VerificationResult.Verified -> Unit
                is Sha256VerificationResult.Refused -> error(verification.message)
            }

            val stagedApp = stageDir.resolve(targetApp.fileName.toString())
            val expectedBundleFiles: Map<String, String>
            val mountDir = createPrivateTempDirectory(stageDir, "mount-")
            var mounted = false
            try {
                commands.attachDmg(verifiedDmg, mountDir).getOrThrow()
                mounted = true
                require(computeSha256Hex(verifiedDmg) == validateExpectedSha256(expectedSha256)) {
                    runBlocking { getString(Res.string.updates_staged_bytes_changed) }
                }
                val appInsideDmg = findAppBundleInDirectory(mountDir)
                    ?: error("The downloaded disk image didn't contain a valid NuvioRoid app bundle.")
                expectedBundleFiles = snapshotBundleFiles(appInsideDmg)
                commands.copyApp(appInsideDmg, stagedApp).getOrThrow()
            } finally {
                if (mounted) {
                    commands.detachDmg(mountDir)
                }
                runCatching { deletePathWithoutFollowingSymlinks(mountDir) }
            }

            if (!isValidAppBundle(stagedApp)) {
                error("The new app bundle is incomplete or invalid and wasn't installed.")
            }
            require(snapshotBundleFiles(stagedApp) == expectedBundleFiles) {
                runBlocking { getString(Res.string.updates_staged_bytes_changed) }
            }
            validatePayloadVersion(
                appPath = stagedApp,
                expectedVersion = expectedVersion,
                installedVersion = installedVersion,
            ).getOrThrow()

            commands.clearQuarantine(stagedApp).getOrThrow()

            // Stage onto the target directory's filesystem before moving the existing app aside,
            // so a disk-full or I/O failure during copy never leaves the installed app missing.
            val localStagingParent = createPrivateTempDirectory(targetParent, ".nuvioroid-stage-")
            stagingParentInApps = localStagingParent
            val stagedInTargetParent = localStagingParent.resolve(normalizedTarget.fileName)
            commands.copyApp(stagedApp, stagedInTargetParent).getOrThrow()
            if (!isValidAppBundle(stagedInTargetParent)) {
                error("The new app bundle couldn't be copied completely, so the update stopped.")
            }
            validatePayloadVersion(
                appPath = stagedInTargetParent,
                expectedVersion = expectedVersion,
                installedVersion = installedVersion,
            ).getOrThrow()
            commands.clearQuarantine(stagedInTargetParent).getOrThrow()

            // Back up settings before modifying the installed app bundle.
            backUpSettingsFiles(
                appSupportDir = appSupportDir,
                backupRootDir = backupRootDir,
                timestampMs = timestampMs,
            ).getOrThrow()

            require(snapshotBundleFiles(stagedInTargetParent) == expectedBundleFiles) {
                runBlocking { getString(Res.string.updates_staged_bytes_changed) }
            }
            // Final activity-gate check immediately before swapping the installed bundle.
            canCommitInstall()?.let { blockReason ->
                throw InstallDeferredException(blockReason)
            }

            val localBackupParent = createPrivateTempDirectory(targetParent, ".nuvioroid-backup-")
            backupParentInApps = localBackupParent
            val backupApp = localBackupParent.resolve(normalizedTarget.fileName)

            Files.move(normalizedTarget, backupApp)
            var swappedIntoPlace = false
            try {
                Files.move(stagedInTargetParent, normalizedTarget)
                swappedIntoPlace = true
                if (!isValidAppBundle(normalizedTarget)) {
                    error("The new app bundle couldn't be verified at $normalizedTarget.")
                }
            } catch (t: Throwable) {
                if (swappedIntoPlace || Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
                    runCatching { deletePathWithoutFollowingSymlinks(normalizedTarget) }
                }
                runCatching {
                    if (Files.exists(backupApp, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        Files.move(backupApp, normalizedTarget)
                    }
                }
                throw t
            }

            val movedToTrash = commands.moveToTrash(backupApp)
            if (!movedToTrash || Files.exists(backupApp, LinkOption.NOFOLLOW_LINKS)) {
                runCatching {
                    if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
                        Files.move(normalizedTarget, stagedInTargetParent)
                    }
                    if (Files.exists(backupApp, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        Files.move(backupApp, normalizedTarget)
                    }
                }
                error("Couldn't move the previous NuvioRoid app to the Trash, so the update stopped.")
            }

            runCatching { deletePathWithoutFollowingSymlinks(localStagingParent) }
            runCatching { deletePathWithoutFollowingSymlinks(localBackupParent) }
            stagingParentInApps = null
            backupParentInApps = null

            commands.launchRelaunchHelper(normalizedTarget, stageDir, currentPid).getOrThrow()
            keepStageDirForRelaunchHelper = true
            commands.scheduleExit()
        } finally {
            stagingParentInApps?.let { runCatching { deletePathWithoutFollowingSymlinks(it) } }
            backupParentInApps?.let { backupDir ->
                // Only remove the wrapper directory when it is empty (the previous app was moved or restored).
                val isEmpty = runCatching {
                    Files.list(backupDir).use { !it.findFirst().isPresent }
                }.getOrDefault(false)
                if (isEmpty) {
                    runCatching { Files.deleteIfExists(backupDir) }
                }
            }
            if (!keepStageDirForRelaunchHelper) {
                runCatching { deletePathWithoutFollowingSymlinks(stageDir) }
            }
        }
    }

    internal fun findAppBundleInDirectory(
        directory: Path,
        expectedBundleId: String = EXPECTED_BUNDLE_IDENTIFIER,
    ): Path? {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return null
        val apps = Files.list(directory).use { stream ->
            stream.filter { entry ->
                !Files.isSymbolicLink(entry) &&
                    Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) &&
                    entry.fileName.toString().endsWith(".app", ignoreCase = true) &&
                    isValidAppBundle(entry, expectedBundleId)
            }.toList()
        }
        return apps.firstOrNull { it.fileName.toString().equals("NuvioRoid.app", ignoreCase = true) }
            ?: apps.firstOrNull()
    }

    internal fun isValidAppBundle(
        appPath: Path,
        expectedBundleId: String = EXPECTED_BUNDLE_IDENTIFIER,
    ): Boolean {
        if (Files.isSymbolicLink(appPath) || !Files.isDirectory(appPath, LinkOption.NOFOLLOW_LINKS)) {
            return false
        }
        val contentsDir = appPath.resolve("Contents")
        val macOsDir = contentsDir.resolve("MacOS")
        val plistPath = contentsDir.resolve("Info.plist")
        if (Files.isSymbolicLink(contentsDir) || !Files.isDirectory(contentsDir, LinkOption.NOFOLLOW_LINKS)) {
            return false
        }
        if (Files.isSymbolicLink(macOsDir) || !Files.isDirectory(macOsDir, LinkOption.NOFOLLOW_LINKS)) {
            return false
        }
        if (hasEscapingSymlinks(appPath)) {
            return false
        }
        val metadata = parseInfoPlist(plistPath) ?: return false
        if (metadata.bundleIdentifier != expectedBundleId) {
            return false
        }
        val executableName = metadata.executableName?.trim().orEmpty()
        if (
            executableName.isEmpty() ||
            executableName == "." ||
            executableName == ".." ||
            '/' in executableName ||
            '\\' in executableName
        ) {
            return false
        }
        val executablePath = macOsDir.resolve(executableName)
        return !Files.isSymbolicLink(executablePath) &&
            Files.isRegularFile(executablePath, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { Files.size(executablePath) > 0L }.getOrDefault(false) &&
            Files.isExecutable(executablePath)
    }

    internal fun validatePayloadVersion(
        appPath: Path,
        expectedVersion: NuvioRoidVersion?,
        installedVersion: NuvioRoidVersion?,
    ): Result<Unit> = runCatching {
        val metadata = parseInfoPlist(appPath.resolve("Contents/Info.plist"))
            ?: error("The downloaded app bundle is missing a readable Info.plist.")
        val rawNuvioRoidVersion = metadata.nuvioRoidVersion?.trim()?.takeIf { it.isNotEmpty() }
        val payloadVersion: NuvioRoidVersion?
        if (rawNuvioRoidVersion != null) {
            payloadVersion = NuvioRoidVersion.parse(rawNuvioRoidVersion)
        } else {
            val rawPlistVersion = metadata.bundleVersion?.trim()?.takeIf { it.isNotEmpty() }
                ?: metadata.bundleShortVersion?.trim()?.takeIf { it.isNotEmpty() }
            payloadVersion = NuvioRoidVersion.parse(rawPlistVersion)
        }
        if (payloadVersion == null) {
            error("The downloaded app bundle is missing version information.")
        }
        if (payloadVersion.build <= 0) {
            error(runBlocking { getString(Res.string.updates_payload_build_missing) })
        }
        if (installedVersion != null && payloadVersion <= installedVersion) {
            error(
                "The downloaded app bundle (${payloadVersion.format()}) is not newer than " +
                    "the installed version (${installedVersion.format()}).",
            )
        }
        if (expectedVersion != null) {
            val matchesExpected = payloadVersion == expectedVersion
            if (!matchesExpected) {
                error(
                    "The downloaded app bundle version (${payloadVersion.format()}) doesn't match " +
                        "the expected update (${expectedVersion.format()}).",
                )
            }
        }
    }

    /** Records names, link destinations, executable flags and file bytes without traversing links. */
    private fun snapshotBundleFiles(root: Path): Map<String, String> = Files.walk(root).use { entries ->
        entries.toList().associate { entry ->
            val relative = root.relativize(entry).toString()
            val value = when {
                Files.isSymbolicLink(entry) -> "link:${Files.readSymbolicLink(entry)}"
                Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) -> "directory"
                Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) ->
                    "file:${Files.isExecutable(entry)}:${computeSha256Hex(entry)}"
                else -> error(runBlocking { getString(Res.string.updates_staged_bytes_changed) })
            }
            relative to value
        }
    }

    internal fun parseInfoPlist(plistPath: Path): ParsedAppBundleMetadata? {
        if (Files.isSymbolicLink(plistPath) || !Files.isRegularFile(plistPath, LinkOption.NOFOLLOW_LINKS)) {
            return null
        }
        val rawBytes = runCatching { Files.readAllBytes(plistPath) }.getOrNull() ?: return null
        if (rawBytes.isEmpty()) return null
        val xmlBytes = if (isBinaryPlist(rawBytes)) {
            convertBinaryPlistToXml(plistPath) ?: return null
        } else {
            rawBytes
        }
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
            val plistRoot = doc.documentElement ?: return@runCatching null
            if (plistRoot.tagName != "plist") return@runCatching null
            val dictElement = firstChildElement(plistRoot, "dict") ?: return@runCatching null
            val entries = readPlistDictStrings(dictElement)
            ParsedAppBundleMetadata(
                bundleIdentifier = entries["CFBundleIdentifier"],
                executableName = entries["CFBundleExecutable"],
                nuvioRoidVersion = entries["NuvioRoidVersion"],
                bundleShortVersion = entries["CFBundleShortVersionString"],
                bundleVersion = entries["CFBundleVersion"],
            )
        }.getOrNull()
    }

    private fun isBinaryPlist(bytes: ByteArray): Boolean =
        bytes.size >= 6 &&
            bytes[0] == 'b'.code.toByte() &&
            bytes[1] == 'p'.code.toByte() &&
            bytes[2] == 'l'.code.toByte() &&
            bytes[3] == 'i'.code.toByte() &&
            bytes[4] == 's'.code.toByte() &&
            bytes[5] == 't'.code.toByte()

    private fun convertBinaryPlistToXml(plistPath: Path): ByteArray? = runCatching {
        val process = ProcessBuilder("plutil", "-convert", "xml1", "-o", "-", plistPath.toAbsolutePath().toString())
            .start()
        val xml = process.inputStream.readAllBytes()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() == 0 && xml.isNotEmpty()) xml else null
    }.getOrNull()

    private fun firstChildElement(parent: Element, tagName: String): Element? {
        var child = parent.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE && (child as Element).tagName == tagName) {
                return child
            }
            child = child.nextSibling
        }
        return null
    }

    private fun readPlistDictStrings(dict: Element): Map<String, String> {
        val map = linkedMapOf<String, String>()
        var currentKey: String? = null
        var node = dict.firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE) {
                val elem = node as Element
                if (elem.tagName == "key") {
                    currentKey = elem.textContent?.trim()
                } else {
                    if (currentKey != null && elem.tagName == "string") {
                        map[currentKey] = elem.textContent?.trim().orEmpty()
                    }
                    currentKey = null
                }
            }
            node = node.nextSibling
        }
        return map
    }

    /**
     * Returns true if any symlink inside [bundleRoot] is absolute or resolves outside [bundleRoot].
     */
    internal fun hasEscapingSymlinks(bundleRoot: Path): Boolean {
        val normalizedRoot = bundleRoot.toAbsolutePath().normalize()
        val realRoot = runCatching { normalizedRoot.toRealPath() }.getOrNull() ?: return true
        var escaping = false
        Files.walkFileTree(
            normalizedRoot,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (Files.isSymbolicLink(file)) {
                        val target = runCatching { Files.readSymbolicLink(file) }.getOrNull()
                        if (target == null || target.isAbsolute) {
                            escaping = true
                            return FileVisitResult.TERMINATE
                        }
                        val resolvedLexical = file.parent.resolve(target).normalize()
                        if (!resolvedLexical.startsWith(normalizedRoot)) {
                            escaping = true
                            return FileVisitResult.TERMINATE
                        }
                        val realParent = runCatching { file.parent.toRealPath() }.getOrNull()
                        if (realParent == null || !realParent.startsWith(realRoot)) {
                            escaping = true
                            return FileVisitResult.TERMINATE
                        }
                        if (Files.exists(resolvedLexical)) {
                            val realTarget = runCatching { resolvedLexical.toRealPath() }.getOrNull()
                            if (realTarget == null || !realTarget.startsWith(realRoot)) {
                                escaping = true
                                return FileVisitResult.TERMINATE
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return escaping
    }

    internal fun buildRelaunchScript(
        installedApp: Path,
        stageDir: Path,
        currentPid: Long,
    ): String {
        val appQuoted = singleQuote(installedApp.toAbsolutePath().normalize().toString())
        val stageQuoted = singleQuote(stageDir.toAbsolutePath().normalize().toString())
        val blockedMessage =
            "NuvioRoid was updated, but macOS blocked opening the unsigned app. " +
                "Open NuvioRoid from Applications once, then go to System Settings > Privacy & Security and click \"Open Anyway\"."
        val alertQuoted = singleQuote("display alert \"NuvioRoid Updated\" message \"${blockedMessage.replace("\"", "\\\"")}\"")
        return buildString {
            append("i=0; ")
            append("while [ \$i -lt 100 ] && kill -0 $currentPid 2>/dev/null; do ")
            append("sleep 0.1; i=\$((i+1)); ")
            append("done; ")
            append("rm -rf -- $stageQuoted >/dev/null 2>&1 || true; ")
            append("if ! open -n $appQuoted; then ")
            append("osascript -e $alertQuoted >/dev/null 2>&1 || true; ")
            append("open 'x-apple.systempreferences:com.apple.preference.security' >/dev/null 2>&1 || true; ")
            append("fi")
        }
    }

    private fun defaultCommands(): MacOsInstallCommands = MacOsInstallCommands(
        attachDmg = { dmg, mountDir ->
            runCommand(
                listOf(
                    "hdiutil",
                    "attach",
                    "-nobrowse",
                    "-readonly",
                    "-noverify",
                    "-mountpoint",
                    mountDir.toAbsolutePath().toString(),
                    dmg.toAbsolutePath().toString(),
                ),
                errorPrefix = "Couldn't open the downloaded disk image",
            )
        },
        detachDmg = { mountDir ->
            val target = mountDir.toAbsolutePath().toString()
            val clean = runCommand(listOf("hdiutil", "detach", target), errorPrefix = "detach")
            if (clean.isFailure) {
                runCommand(listOf("hdiutil", "detach", "-force", target), errorPrefix = "detach")
            }
        },
        copyApp = { src, dest ->
            runCommand(
                listOf("ditto", src.toAbsolutePath().toString(), dest.toAbsolutePath().toString()),
                errorPrefix = "Couldn't copy the new app into place",
            )
        },
        clearQuarantine = { appPath ->
            clearMacQuarantine(appPath)
        },
        moveToTrash = { target ->
            runCatching {
                Desktop.isDesktopSupported() &&
                    Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH) &&
                    Desktop.getDesktop().moveToTrash(target.toFile()) &&
                    !Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            }.getOrDefault(false)
        },
        launchRelaunchHelper = { installedApp, stageDir, currentPid ->
            runCatching {
                val script = buildRelaunchScript(installedApp, stageDir, currentPid)
                ProcessBuilder("sh", "-c", script)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                Unit
            }
        },
        scheduleExit = {
            thread(name = "nuvioroid-updater-exit", isDaemon = true) {
                Thread.sleep(250)
                exitProcess(0)
            }
        },
    )

    internal fun clearMacQuarantine(appPath: Path): Result<Unit> = runCatching {
        // -s acts on symbolic links themselves rather than following them outside the bundle.
        val process = ProcessBuilder(
            "xattr",
            "-drs",
            "com.apple.quarantine",
            appPath.toAbsolutePath().toString(),
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Timed out clearing the macOS quarantine attribute.")
        }
        val code = process.exitValue()
        // xattr returns non-zero with "No such xattr" when the attribute is already absent.
        if (code != 0 && !output.contains("No such xattr", ignoreCase = true)) {
            error(
                "Couldn't clear the macOS quarantine attribute on this unsigned build ($output). " +
                    "If macOS blocks opening NuvioRoid, go to System Settings > Privacy & Security and click \"Open Anyway\".",
            )
        }
    }

    private fun ensurePrivateDirectory(dir: Path) {
        if (Files.isSymbolicLink(dir)) {
            error("Staging directory cannot be a symbolic link: $dir")
        }
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            runCatching {
                Files.createDirectories(
                    dir,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
            }.getOrElse {
                Files.createDirectories(dir)
            }
        }
        runCatching {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        }
    }

    private fun createPrivateTempDirectory(parent: Path, prefix: String): Path =
        runCatching {
            Files.createTempDirectory(
                parent,
                prefix,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
        }.getOrElse {
            Files.createTempDirectory(parent, prefix)
        }

    private fun createPrivateRegularFile(file: Path) {
        Files.deleteIfExists(file)
        runCatching {
            Files.createFile(
                file,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
        }.getOrElse {
            Files.createFile(file)
        }
    }

    private fun runCommand(command: List<String>, errorPrefix: String): Result<Unit> = runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("$errorPrefix (timed out).")
        }
        val code = process.exitValue()
        if (code != 0) {
            val detail = output.ifBlank { "exit code $code" }
            throw IOException("$errorPrefix: $detail")
        }
    }

    private fun singleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
