package com.nuvio.app.features.updater

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.settings.SettingsCategory
import com.nuvio.app.features.settings.settingsRootContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NuvioRoidSafeInstallAndGateTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var tempDir: Path
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("nuvioroid-updater-test")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `checksum verification passes on match and refuses and cleans up on mismatch`() {
        val goodFile = tempDir.resolve("good.dmg").apply { writeText("valid dmg payload") }
        val goodHash = MacOsUpdateInstaller.computeSha256Hex(goodFile)

        val verified = MacOsUpdateInstaller.verifyFileSha256(goodFile, goodHash.uppercase())
        assertIs<Sha256VerificationResult.Verified>(verified)
        assertTrue(goodFile.exists())

        val badFile = tempDir.resolve("tampered.dmg").apply { writeText("tampered dmg payload") }
        val refused = MacOsUpdateInstaller.verifyFileSha256(badFile, "0".repeat(64))
        assertIs<Sha256VerificationResult.Refused>(refused)
        assertTrue(refused.message.contains("match", ignoreCase = true))
        assertFalse(badFile.exists())

        val missingHashFile = tempDir.resolve("nohash.dmg").apply { writeText("payload") }
        val refusedMissing = MacOsUpdateInstaller.verifyFileSha256(missingHashFile, null)
        assertIs<Sha256VerificationResult.Refused>(refusedMissing)
        assertFalse(missingHashFile.exists())
    }

    @Test
    fun `settings backup copies properties without touching Application Support data`() {
        val appSupport = tempDir.resolve("ApplicationSupport/NuvioRoid").createDirectories()
        val settingsA = appSupport.resolve("nuvio_theme_settings.properties").apply { writeText("theme=dark\n") }
        val settingsB = appSupport.resolve("nuvio_updater.properties").apply { writeText("auto=true\n") }
        val downloadsDir = appSupport.resolve("downloads").createDirectories()
        val userVideo = downloadsDir.resolve("movie.mkv").apply { writeText("video bytes") }
        val backupRoot = tempDir.resolve("Caches/NuvioRoid/backups")

        val backup = MacOsUpdateInstaller.backUpSettingsFiles(
            appSupportDir = appSupport,
            backupRootDir = backupRoot,
            timestampMs = 123456L,
        ).getOrThrow()

        assertEquals(listOf("nuvio_theme_settings.properties", "nuvio_updater.properties"), backup.backedUpFiles)
        assertEquals("theme=dark\n", backup.backupDir.resolve("nuvio_theme_settings.properties").readText())
        assertEquals("auto=true\n", backup.backupDir.resolve("nuvio_updater.properties").readText())
        // User data in Application Support remains completely untouched
        assertTrue(settingsA.exists())
        assertTrue(settingsB.exists())
        assertTrue(userVideo.exists())
        assertFalse(backup.backupDir.resolve("movie.mkv").exists())
    }

    @Test
    fun `safe macOS install backs up settings, trashes old app, copies new app, clears quarantine, and relaunches`() {
        val appSupport = tempDir.resolve("ApplicationSupport/NuvioRoid").createDirectories()
        appSupport.resolve("nuvio_theme_settings.properties").writeText("theme=amoled\n")
        val backupRoot = tempDir.resolve("Caches/backups")
        val stagingRoot = tempDir.resolve("tmp-updates").createDirectories()
        val appsDir = tempDir.resolve("Applications").createDirectories()
        val installedApp = createFakeAppBundle(appsDir.resolve("NuvioRoid.app"), versionMarker = "old")
        val trashDir = tempDir.resolve("Trash").createDirectories()
        val dmgFile = stagingRoot.resolve("NuvioRoid-0.1.36.3.dmg").apply { writeText("fake dmg") }
        val dmgHash = MacOsUpdateInstaller.computeSha256Hex(dmgFile)

        val steps = mutableListOf<String>()
        val commands = MacOsInstallCommands(
            attachDmg = { _, mountDir ->
                steps += "attach"
                createFakeAppBundle(mountDir.resolve("NuvioRoid.app"), versionMarker = "new")
                Result.success(Unit)
            },
            detachDmg = { mountDir ->
                steps += "detach"
                MacOsUpdateInstaller.deletePathWithoutFollowingSymlinks(mountDir)
            },
            copyApp = { src, dest ->
                steps += "copy:${dest.fileName}"
                copyAppFixture(src, dest)
                Result.success(Unit)
            },
            clearQuarantine = { path ->
                steps += "xattr:${path.fileName}"
                Result.success(Unit)
            },
            moveToTrash = { target ->
                steps += "trash:${target.fileName}"
                copyAppFixture(target, trashDir.resolve(target.fileName.toString()))
                run { MacOsUpdateInstaller.deletePathWithoutFollowingSymlinks(target); true }
            },
            launchRelaunchHelper = { app, _, pid ->
                steps += "relaunch:${app.fileName}:$pid"
                Result.success(Unit)
            },
            scheduleExit = { steps += "exit" },
        )

        val result = MacOsUpdateInstaller.installVerifiedArtifact(
            stagedArtifact = dmgFile,
            expectedSha256 = dmgHash,
            runningAppBundle = installedApp,
            appSupportDir = appSupport,
            backupRootDir = backupRoot,
            tempRoot = stagingRoot,
            currentPid = 999L,
            timestampMs = 42L,
            commands = commands,
        )

        assertTrue(result.isSuccess, "Expected install to succeed: ${result.exceptionOrNull()}")
        assertEquals("new", installedApp.resolve("Contents/marker.txt").readText())
        assertEquals("old", trashDir.resolve("NuvioRoid.app/Contents/marker.txt").readText())
        assertEquals("theme=amoled\n", backupRoot.resolve("backup-42/nuvio_theme_settings.properties").readText())
        assertTrue(steps.indexOf("trash:NuvioRoid.app") < steps.indexOf("relaunch:NuvioRoid.app:999"))
        assertTrue("exit" in steps)
        assertTrue("detach" in steps)
    }

    @Test
    fun `install aborts without deleting or overwriting if moving old app to Trash fails`() {
        val appSupport = tempDir.resolve("ApplicationSupport/NuvioRoid").createDirectories()
        val backupRoot = tempDir.resolve("Caches/backups")
        val stagingRoot = tempDir.resolve("tmp-updates").createDirectories()
        val appsDir = tempDir.resolve("Applications").createDirectories()
        val installedApp = createFakeAppBundle(appsDir.resolve("NuvioRoid.app"), versionMarker = "old")
        val dmgFile = stagingRoot.resolve("NuvioRoid-0.1.36.3.dmg").apply { writeText("fake dmg") }
        val dmgHash = MacOsUpdateInstaller.computeSha256Hex(dmgFile)

        var relaunched = false
        val commands = MacOsInstallCommands(
            attachDmg = { _, mountDir ->
                createFakeAppBundle(mountDir.resolve("NuvioRoid.app"), versionMarker = "new")
                Result.success(Unit)
            },
            detachDmg = { mountDir -> MacOsUpdateInstaller.deletePathWithoutFollowingSymlinks(mountDir) },
            copyApp = { src, dest ->
                copyAppFixture(src, dest)
                Result.success(Unit)
            },
            clearQuarantine = { Result.success(Unit) },
            moveToTrash = { false },
            launchRelaunchHelper = { _, _, _ ->
                relaunched = true
                Result.success(Unit)
            },
            scheduleExit = { relaunched = true },
        )

        val result = MacOsUpdateInstaller.installVerifiedArtifact(
            stagedArtifact = dmgFile,
            expectedSha256 = dmgHash,
            runningAppBundle = installedApp,
            appSupportDir = appSupport,
            backupRootDir = backupRoot,
            tempRoot = stagingRoot,
            currentPid = 999L,
            timestampMs = 43L,
            commands = commands,
        )

        assertTrue(result.isFailure)
        assertEquals("old", installedApp.resolve("Contents/marker.txt").readText())
        assertFalse(relaunched)
    }

    @Test
    fun `relaunch script waits for current pid and explains plainly if macOS blocks opening`() {
        val script = MacOsUpdateInstaller.buildRelaunchScript(
            installedApp = Path.of("/Applications/NuvioRoid.app"),
            stageDir = Path.of("/tmp/nuvioroid-updates/stage-1"),
            currentPid = 4242L,
        )
        assertTrue(script.contains("kill -0 4242"))
        assertTrue(script.contains("open -n '/Applications/NuvioRoid.app'"))
        assertTrue(script.contains("Open Anyway"))
        assertTrue(script.contains("Privacy & Security"))
    }

    @Test
    fun `controller refuses install on checksum mismatch and surfaces message`() {
        val stagedDmg = tempDir.resolve("bad.dmg").apply { writeText("corrupt") }
        var installInvoked = false
        val controller = createTestController(
            remoteUpdate = testAppUpdate(expectedSha256 = "f".repeat(64)),
            downloadResult = { _, _, _ -> Result.success(stagedDmg.toString()) },
            verifyResult = { path, expected ->
                MacOsUpdateInstaller.verifyFileSha256(Path.of(path), expected).let { res ->
                    when (res) {
                        is Sha256VerificationResult.Verified -> Result.success(Unit)
                        is Sha256VerificationResult.Refused -> Result.failure(IllegalStateException(res.message))
                    }
                }
            },
            installResult = { _, _ ->
                installInvoked = true
                Result.success(Unit)
            },
        )

        controller.checkForUpdates(force = true, showNoUpdateFeedback = false)
        assertTrue(controller.uiState.value.isUpdateAvailable)

        controller.downloadUpdate()

        assertFalse(installInvoked)
        assertNull(controller.uiState.value.downloadedUpdatePath)
        assertNotNull(controller.uiState.value.errorMessage)
        assertTrue(controller.uiState.value.errorMessage!!.contains("match", ignoreCase = true))
        assertTrue(controller.uiState.value.showDialog)
    }

    @Test
    fun `not now gate defers install while video plays or download is active and offers again later`() {
        val stagedDmg = tempDir.resolve("valid.dmg").apply { writeText("valid") }
        val validHash = MacOsUpdateInstaller.computeSha256Hex(stagedDmg)
        var playing = true
        var downloading = false
        var installCount = 0

        val controller = createTestController(
            remoteUpdate = testAppUpdate(expectedSha256 = validHash),
            downloadResult = { _, _, _ -> Result.success(stagedDmg.toString()) },
            verifyResult = { _, _ -> Result.success(Unit) },
            installResult = { _, _ ->
                installCount += 1
                Result.success(Unit)
            },
            installGate = InstallGate(
                isVideoPlaying = { playing },
                isDownloadBusy = { downloading },
            ),
        )

        controller.checkForUpdates(force = true, showNoUpdateFeedback = false)
        controller.downloadUpdate()

        // Blocked because video is playing
        assertEquals(0, installCount)
        assertEquals(InstallBlockReason.VIDEO_PLAYING, controller.uiState.value.deferredInstallReason)
        assertEquals(stagedDmg.toString(), controller.uiState.value.downloadedUpdatePath)

        // Video stops, but a download is still finishing -> still blocked
        playing = false
        downloading = true
        controller.refreshInstallGate()
        assertEquals(0, installCount)
        assertEquals(InstallBlockReason.DOWNLOAD_ACTIVE, controller.uiState.value.deferredInstallReason)

        // User dismissed banner while waiting
        controller.dismissDialog()
        assertFalse(controller.uiState.value.showDialog)

        // Download finishes -> controller clears block and quietly re-offers the banner (does not force restart)
        downloading = false
        controller.refreshInstallGate()
        assertEquals(0, installCount)
        assertNull(controller.uiState.value.deferredInstallReason)
        assertTrue(controller.uiState.value.showDialog)

        // Now user clicks Restart to update -> installs
        controller.installDownloadedUpdate()
        assertEquals(1, installCount)
    }

    @Test
    fun `daily automatic check runs when due and skips within 24 hours`() {
        val dayMs = DAILY_CHECK_INTERVAL_MS
        assertTrue(shouldRunDailyCheck(lastCheckEpochMs = null, nowEpochMs = 1_000_000L))
        assertFalse(shouldRunDailyCheck(lastCheckEpochMs = 1_000_000L, nowEpochMs = 1_000_000L + dayMs - 1L))
        assertTrue(shouldRunDailyCheck(lastCheckEpochMs = 1_000_000L, nowEpochMs = 1_000_000L + dayMs))
    }

    @Test
    fun `settings System group shows Check for updates, current version, channel, and daily toggle`() {
        UpdatePreferences.shared.setAutoDailyCheckEnabled(true)
        var checkClicked = false
        compose.setContent {
            NuvioTheme {
                LazyColumn {
                    settingsRootContent(
                        isTablet = true,
                        category = SettingsCategory.System,
                        showNotificationsEntry = false,
                        onPageClick = {},
                        onCheckForUpdatesClick = { checkClicked = true },
                    )
                }
            }
        }

        compose.onNodeWithText("Updates").assertIsDisplayed()
        compose.onNodeWithText("Version 0.1.36.2 (build 2)", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Public (GitHub Releases)", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Last checked:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Check for updates").assertIsDisplayed().performClick()
        assertTrue(checkClicked)
        compose.onNodeWithText("Check for updates automatically").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOn().performClick()
        assertFalse(UpdatePreferences.shared.autoDailyCheckEnabled.value)
        compose.onNode(isToggleable()).assertIsOff().performClick()
        assertTrue(UpdatePreferences.shared.autoDailyCheckEnabled.value)
    }

    @Test
    fun `quiet banner shows version, release notes, and Restart to update`() {
        val controller = createTestController(
            remoteUpdate = testAppUpdate(expectedSha256 = "a".repeat(64)).copy(
                notes = "Faster downloads and safer updates",
            ),
            downloadResult = { _, _, _ -> Result.success("") },
            verifyResult = { _, _ -> Result.success(Unit) },
            installResult = { _, _ -> Result.success(Unit) },
        )
        controller.checkForUpdates(force = true, showNoUpdateFeedback = false)

        compose.setContent {
            NuvioTheme {
                AppUpdaterHost(controller = controller) {
                    Text("App content")
                }
            }
        }

        compose.onNodeWithText("NuvioRoid 0.1.36.99", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Faster downloads and safer updates", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Restart to update").assertIsDisplayed()
    }

    private fun createFakeAppBundle(bundleDir: Path, versionMarker: String): Path {
        val contents = bundleDir.resolve("Contents").createDirectories()
        val exe = contents.resolve("MacOS").createDirectories().resolve("NuvioRoid")
        exe.writeText("#!/bin/sh\n")
        exe.toFile().setExecutable(true)
        val bundleVersion = if (versionMarker == "new") "0.1.36.3" else "0.1.36.2"
        contents.resolve("Info.plist").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>CFBundleIdentifier</key>
                <string>io.github.mischiefmerged.nuvioroid</string>
                <key>CFBundleExecutable</key>
                <string>NuvioRoid</string>
                <key>CFBundleShortVersionString</key>
                <string>1.1.36</string>
                <key>CFBundleVersion</key>
                <string>1.1.36</string>
                <key>NuvioRoidVersion</key>
                <string>$bundleVersion</string>
            </dict>
            </plist>
            """.trimIndent(),
        )
        contents.resolve("marker.txt").writeText(versionMarker)
        return bundleDir
    }

    private fun testAppUpdate(expectedSha256: String): AppUpdate = AppUpdate(
        tag = "nuvioroid-0.1.36.99",
        title = "NuvioRoid 0.1.36.99",
        notes = "Release notes",
        releaseUrl = null,
        assetName = "NuvioRoid-macOS-arm64-0.1.36.99.dmg",
        assetUrl = "https://example.test/update.dmg",
        assetSizeBytes = 1024L,
        expectedSha256 = expectedSha256,
        versionDisplay = "0.1.36.99",
        buildNumber = 99,
    )

    private fun createTestController(
        remoteUpdate: AppUpdate,
        downloadResult: suspend (String, String, (Long, Long?) -> Unit) -> Result<String>,
        verifyResult: suspend (String, String?) -> Result<Unit>,
        installResult: suspend (String, String?) -> Result<Unit>,
        installGate: InstallGate = InstallGate(isVideoPlaying = { false }, isDownloadBusy = { false }),
        seenWhatsNewVersion: String? = "0.1.36.2",
    ): AppUpdaterController {
        var storedChannel: String? = null
        var storedAuto = true
        var storedLastCheck: Long? = null
        var storedWhatsNew = seenWhatsNewVersion
        val prefs = UpdatePreferences(
            versionName = "0.1.36.2",
            readChannel = { storedChannel },
            writeChannel = { storedChannel = it },
            writeIgnoredTag = {},
            readAutoDailyCheck = { storedAuto },
            writeAutoDailyCheck = { storedAuto = it },
            readLastCheckEpochMs = { storedLastCheck },
            writeLastCheckEpochMs = { storedLastCheck = it },
            readLastWhatsNewVersion = { storedWhatsNew },
            writeLastWhatsNewVersion = { storedWhatsNew = it },
            persistDefaults = false,
        )
        return AppUpdaterController(
            scope = scope,
            preferences = prefs,
            fetchUpdate = { Result.success(remoteUpdate) },
            installGate = installGate,
            downloadAsset = downloadResult,
            verifyAsset = verifyResult,
            installAsset = { path, sha, _, _ -> installResult(path, sha) },
            nowEpochMs = { 1_700_000_000_000L },
            feedInfoProvider = { UpdateFeedInfo(UpdateFeedKind.PUBLIC_GITHUB, "mischief-merged/NuvioRoid") },
            usesForkFeed = true,
            localForkVersion = NuvioRoidVersion(0, 1, 36, 2),
            currentVersionDisplay = "0.1.36.2",
            currentBuildNumber = 2,
        )
    }
}
