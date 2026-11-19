package com.nuvio.app.features.updater

import com.nuvio.app.features.downloads.DownloadCompanion
import com.nuvio.app.features.downloads.DownloadCompanions
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NuvioRoidUpdaterHardeningTest {
    private lateinit var tempDir: Path
    private lateinit var scope: CoroutineScope
    private lateinit var originalTempDir: String

    @BeforeTest
    fun setUp() {
        originalTempDir = System.getProperty("java.io.tmpdir")
        tempDir = Files.createTempDirectory("nuvioroid-hardening-test")
        System.setProperty("java.io.tmpdir", tempDir.toString())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        System.setProperty("java.io.tmpdir", originalTempDir)
        deleteNoFollow(tempDir)
    }

    private fun deleteNoFollow(root: Path) {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            root,
            java.util.EnumSet.noneOf(java.nio.file.FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    Files.deleteIfExists(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                    Files.deleteIfExists(dir)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            },
        )
    }

    // --- Finding 1 hardening ---

    @Test
    fun `public GitHub feed rejects non-HTTPS asset or checksum URLs`() = runTest {
        val releasesJson = """
            [
              {
                "tag_name": "nuvioroid-0.1.36.9",
                "draft": false,
                "assets": [
                  {"name": "NuvioRoid-macOS-arm64-0.1.36.9.dmg", "browser_download_url": "http://insecure.example/app.dmg", "size": 1000},
                  {"name": "SHA256SUMS", "browser_download_url": "https://example.test/SHA256SUMS"}
                ]
              }
            ]
        """.trimIndent()
        val fetcher = object : FeedTextFetcher {
            override suspend fun fetchHttp(url: String, headers: Map<String, String>): Result<FeedHttpResponse> =
                when {
                    url.contains("/releases") -> Result.success(FeedHttpResponse(200, releasesJson))
                    else -> Result.success(FeedHttpResponse(200, "${"a".repeat(64)}  NuvioRoid-macOS-arm64-0.1.36.9.dmg"))
                }

            override suspend fun readLocal(location: String): Result<String> =
                Result.failure(UnsupportedOperationException())
        }

        val result = PublicGitHubUpdateFeed(hostArch = "arm64", fetcher = fetcher).fetchLatest()
        assertTrue(result.isFailure, "Expected public feed to reject non-HTTPS asset URL")
    }

    @Test
    fun `private HTTPS manifest rejects local file paths and HTTP downgrade`() {
        val hash = "a".repeat(64)
        val httpsManifestWithHttpArtifact = """
            {"version": "0.1.36.5", "dmg": "http://192.168.1.50/app.dmg", "sha256": "$hash"}
        """.trimIndent()
        assertFails("HTTPS feed must not allow plaintext HTTP artifact URL") {
            PrivateManifestUpdateFeed.parseManifest(httpsManifestWithHttpArtifact, "https://updates.example/manifest.json")
        }

        val httpManifestWithFileUrl = """
            {"version": "0.1.36.5", "url": "file:///Users/victim/secret.dmg", "sha256": "$hash"}
        """.trimIndent()
        assertFails("HTTP LAN feed must not point to local file:// URL") {
            PrivateManifestUpdateFeed.parseManifest(httpManifestWithFileUrl, "https://192.168.1.50/manifest.json")
        }

        val httpManifestWithLocalPath = """
            {"version": "0.1.36.5", "path": "/Applications/Other.app", "sha256": "$hash"}
        """.trimIndent()
        assertFails("HTTP LAN feed must not point to local filesystem path") {
            PrivateManifestUpdateFeed.parseManifest(httpManifestWithLocalPath, "https://192.168.1.50/manifest.json")
        }
    }

    @Test
    fun `plaintext manifest is refused before any network request even with a matching checksum`() = runTest {
        var requested = false
        val manifest = """{"version":"0.1.36.9","url":"https://example.test/new.dmg","sha256":"${"a".repeat(64)}"}"""
        val feed = PrivateManifestUpdateFeed(
            feedLocation = "http://192.168.1.50/manifest.json",
            fetcher = object : FeedTextFetcher {
                override suspend fun fetchHttp(url: String, headers: Map<String, String>): Result<FeedHttpResponse> {
                    requested = true
                    return Result.success(FeedHttpResponse(200, manifest))
                }
                override suspend fun readLocal(location: String): Result<String> = error("Unexpected local read")
            },
        )
        assertTrue(feed.fetchLatest().isFailure)
        assertFalse(requested)
        assertFails { PrivateManifestUpdateFeed.parseManifest(manifest, feed.info.location) }
    }

    @Test
    fun `payload without a fork build number cannot match a newer advertised build`() {
        val app = createValidBundle(tempDir.resolve("Ambiguous.app"), version = null, shortVersion = "1.1.37")
        val result = MacOsUpdateInstaller.validatePayloadVersion(
            app,
            expectedVersion = NuvioRoidVersion(0, 1, 37, 5),
            installedVersion = NuvioRoidVersion(0, 1, 36, 2),
        )
        assertTrue(result.isFailure, "Three-part package version cannot establish the advertised fork build")
    }

    @Test
    fun `installer refuses changed extracted bytes before replacing the working app`() {
        val stagingRoot = tempDir.resolve("tmp-updates").createDirectories()
        val installedApp = createValidBundle(tempDir.resolve("Applications/NuvioRoid.app"), marker = "old")
        val dmg = stagingRoot.resolve("update.dmg").apply { writeText("verified image") }
        val hash = MacOsUpdateInstaller.computeSha256Hex(dmg)
        var copies = 0
        var exited = false
        val commands = noopCommands(attachDmg = { _, mount ->
            createValidBundle(mount.resolve("NuvioRoid.app"), version = "0.1.36.3", marker = "new")
            Result.success(Unit)
        }).copy(copyApp = { source, destination ->
            copyAppFixture(source, destination)
            copies++
            if (copies == 2) destination.resolve("Contents/marker.txt").writeText("changed after extraction")
            Result.success(Unit)
        }, scheduleExit = { exited = true })
        val result = MacOsUpdateInstaller.installVerifiedArtifact(
            stagedArtifact = dmg,
            expectedSha256 = hash,
            runningAppBundle = installedApp,
            appSupportDir = tempDir.resolve("AppSupport").createDirectories(),
            backupRootDir = tempDir.resolve("Backups"),
            tempRoot = stagingRoot,
            commands = commands,
        )
        assertTrue(result.isFailure)
        assertFalse(exited)
        assertEquals("old", installedApp.resolve("Contents/marker.txt").readText())
    }

    @Test
    fun `installer refuses image bytes changed between hashing and mounting`() {
        val stagingRoot = tempDir.resolve("tmp-updates").createDirectories()
        val installedApp = createValidBundle(tempDir.resolve("Applications/NuvioRoid.app"), marker = "old")
        val dmg = stagingRoot.resolve("update.dmg").apply { writeText("verified image") }
        val hash = MacOsUpdateInstaller.computeSha256Hex(dmg)
        var exited = false
        val commands = noopCommands(attachDmg = { attached, mount ->
            attached.writeText("substituted image")
            createValidBundle(mount.resolve("NuvioRoid.app"), version = "0.1.36.3", marker = "substituted")
            Result.success(Unit)
        }).copy(scheduleExit = { exited = true })
        val result = MacOsUpdateInstaller.installVerifiedArtifact(
            stagedArtifact = dmg,
            expectedSha256 = hash,
            runningAppBundle = installedApp,
            appSupportDir = tempDir.resolve("AppSupport").createDirectories(),
            backupRootDir = tempDir.resolve("Backups"),
            tempRoot = stagingRoot,
            commands = commands,
        )
        assertTrue(result.isFailure)
        assertFalse(exited)
        assertEquals("old", installedApp.resolve("Contents/marker.txt").readText())
    }

    @Test
    fun `payload must match the advertised build even when both builds are newer than installed`() {
        val installed = NuvioRoidVersion(0, 1, 36, 2)
        val advertised = NuvioRoidVersion(0, 1, 36, 5)
        val differentBuild = createValidBundle(tempDir.resolve("Different.app"), version = "0.1.36.3")
        val matchingBuild = createValidBundle(tempDir.resolve("Matching.app"), version = "0.1.36.5")
        assertTrue(MacOsUpdateInstaller.validatePayloadVersion(differentBuild, advertised, installed).isFailure)
        assertTrue(MacOsUpdateInstaller.validatePayloadVersion(matchingBuild, advertised, installed).isSuccess)
    }

    @Test
    fun `feed redirects refuse HTTP before requesting it and allow HTTPS asset redirects`() = runTest {
        val requested = mutableListOf<String>()
        val insecure = fetchHttpsFeedText("https://updates.example/manifest.json", emptyMap()) { url, _ ->
            requested += url
            FeedHttpResponse(302, "", "http://updates.example/manifest.json")
        }
        assertTrue(insecure.isFailure)
        assertEquals(listOf("https://updates.example/manifest.json"), requested)

        requested.clear()
        val secure = fetchHttpsFeedText("https://updates.example/SHA256SUMS", emptyMap()) { url, _ ->
            requested += url
            if (requested.size == 1) FeedHttpResponse(302, "", "https://assets.example/SHA256SUMS")
            else FeedHttpResponse(200, "verified release checksums")
        }
        assertEquals("verified release checksums", secure.getOrThrow().body)
        assertEquals(listOf("https://updates.example/SHA256SUMS", "https://assets.example/SHA256SUMS"), requested)
    }

    @Test
    fun `feed redirect loops stop after a bounded number of requests`() = runTest {
        var requests = 0
        val result = fetchHttpsFeedText("https://updates.example/loop", emptyMap()) { _, _ ->
            requests++
            FeedHttpResponse(307, "", "https://updates.example/loop")
        }
        assertTrue(result.isFailure)
        assertEquals(6, requests)
    }

    // --- Finding 2 ---

    @Test
    fun `temp staging cleanup never follows directory symlinks to delete user data`() {
        val userDataDir = tempDir.resolve("user-data").createDirectories()
        val userFile = userDataDir.resolve("important.mkv").apply { writeText("precious data") }

        val stagingDir = tempDir.resolve("staging").createDirectories()
        val bundleDir = stagingDir.resolve("stage-1/NuvioRoid.app/Contents").createDirectories()
        Files.createSymbolicLink(bundleDir.resolve("Resources"), userDataDir)

        MacOsUpdateInstaller.clearTempUpdatesDir(stagingDir)

        assertTrue(userFile.exists(), "Symlink target file outside staging must not be deleted")
        assertEquals("precious data", userFile.readText())
        assertFalse(bundleDir.resolve("Resources").exists(), "Symlink inside staging should be unlinked")
    }

    @Test
    fun `deleteDownloadedUpdate never follows directory symlinks inside tempUpdatesDir`() {
        val userDataDir = tempDir.resolve("user-data-2").createDirectories()
        val userFile = userDataDir.resolve("keep.mkv").apply { writeText("keep me") }

        val tempRoot = MacOsUpdateInstaller.tempUpdatesDir()
        val stagedDir = Files.createTempDirectory(tempRoot, "nuvioroid-test-del-")
        Files.createSymbolicLink(stagedDir.resolve("linked"), userDataDir)
        try {
            AppUpdaterPlatform.deleteDownloadedUpdate(stagedDir.toString())
            assertTrue(userFile.exists(), "deleteDownloadedUpdate must not follow symlinks")
            assertFalse(stagedDir.exists(), "Staged directory should be deleted")
        } finally {
            MacOsUpdateInstaller.deletePathWithoutFollowingSymlinks(stagedDir)
        }
    }

    // --- Finding 3 ---

    @Test
    fun `uncompressed app directory without sha256 is refused by verification and installer`() = runTest {
        val stagingRoot = tempDir.resolve("tmp-updates").createDirectories()
        val stagedApp = createValidBundle(stagingRoot.resolve("NuvioRoid.app"))
        val installedApp = createValidBundle(tempDir.resolve("Applications/NuvioRoid.app"))

        val verifyResult = AppUpdaterPlatform.verifyDownloadedUpdate(stagedApp.toString(), expectedSha256 = null)
        assertTrue(verifyResult.isFailure, "Verification must refuse uncompressed .app directory without checksum")
        assertTrue(stagedApp.resolve("Contents/marker.txt").exists(), "Refusing an app folder must preserve its files")

        val installResult = MacOsUpdateInstaller.installVerifiedArtifact(
            stagedArtifact = stagedApp,
            expectedSha256 = null,
            runningAppBundle = installedApp,
            appSupportDir = tempDir.resolve("AppSupport").createDirectories(),
            backupRootDir = tempDir.resolve("Backups"),
            tempRoot = stagingRoot,
            commands = noopCommands(),
        )
        assertTrue(installResult.isFailure, "Installer must refuse uncompressed .app directory without checksum")
    }

    // --- Finding 4 ---

    @Test
    fun `failed final copy keeps original installed app intact in place`() {
        val stagingRoot = tempDir.resolve("tmp-updates").createDirectories()
        val appsDir = tempDir.resolve("Applications").createDirectories()
        val installedApp = createValidBundle(appsDir.resolve("NuvioRoid.app"), marker = "old")
        val dmgFile = stagingRoot.resolve("NuvioRoid-0.1.36.3.dmg").apply { writeText("dmg bytes") }
        val dmgHash = MacOsUpdateInstaller.computeSha256Hex(dmgFile)

        var copyAttempt = 0
        val commands = MacOsInstallCommands(
            attachDmg = { _, mountDir ->
                createValidBundle(mountDir.resolve("NuvioRoid.app"), version = "0.1.36.3", marker = "new")
                Result.success(Unit)
            },
            detachDmg = { mountDir -> MacOsUpdateInstaller.deletePathWithoutFollowingSymlinks(mountDir) },
            copyApp = { src, dest ->
                copyAttempt += 1
                if (copyAttempt == 1) {
                    copyAppFixture(src, dest)
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("Simulated out of disk space during final copy"))
                }
            },
            clearQuarantine = { Result.success(Unit) },
            moveToTrash = { target ->
                run { MacOsUpdateInstaller.deletePathWithoutFollowingSymlinks(target); true }
            },
            launchRelaunchHelper = { _, _, _ -> Result.success(Unit) },
            scheduleExit = {},
        )

        val result = MacOsUpdateInstaller.installVerifiedArtifact(
            stagedArtifact = dmgFile,
            expectedSha256 = dmgHash,
            runningAppBundle = installedApp,
            appSupportDir = tempDir.resolve("AppSupport").createDirectories(),
            backupRootDir = tempDir.resolve("Backups"),
            tempRoot = stagingRoot,
            commands = commands,
        )

        assertTrue(result.isFailure, "Expected install to fail when final copy fails")
        assertTrue(installedApp.exists(), "Original installed app must not be left missing after copy failure")
        assertEquals("old", installedApp.resolve("Contents/marker.txt").readText())
    }

    // --- Finding 5 ---

    @Test
    fun `installer rejects upstream or unrelated app bundle with wrong bundle identifier`() {
        val mountDir = tempDir.resolve("mount").createDirectories()
        createValidBundle(
            mountDir.resolve("Nuvio.app"),
            bundleId = "com.nuvio.media",
            executableName = "Nuvio",
        )
        assertNull(
            MacOsUpdateInstaller.findAppBundleInDirectory(mountDir),
            "Upstream bundle identifier must not be selected",
        )
    }

    @Test
    fun `public GitHub feed prefers NuvioRoid DMG over unrelated DMG for same architecture`() {
        val releasesJson = """
            [
              {
                "tag_name": "nuvioroid-0.1.36.3",
                "draft": false,
                "assets": [
                  {"name": "Nuvio-macOS-arm64-0.1.36.3.dmg", "browser_download_url": "https://example.test/upstream.dmg"},
                  {"name": "NuvioRoid-macOS-arm64-0.1.36.3.dmg", "browser_download_url": "https://example.test/fork.dmg"},
                  {"name": "SHA256SUMS", "browser_download_url": "https://example.test/SHA256SUMS"}
                ]
              }
            ]
        """.trimIndent()
        val selected = PublicGitHubUpdateFeed.selectNewestGitHubRelease(releasesJson, "arm64")
        assertNotNull(selected)
        assertEquals("NuvioRoid-macOS-arm64-0.1.36.3.dmg", selected.dmgAsset.name)
    }

    // --- Finding 6 ---

    @Test
    fun `unusable app bundle with malformed Info_plist or missing non-executable binary fails validation`() {
        val malformed = tempDir.resolve("Malformed.app")
        malformed.resolve("Contents/MacOS").createDirectories()
        malformed.resolve("Contents/Info.plist").writeText("<plist/>")
        assertFalse(MacOsUpdateInstaller.isValidAppBundle(malformed), "Malformed plist must fail validation")

        val missingExe = tempDir.resolve("MissingExe.app")
        missingExe.resolve("Contents/MacOS").createDirectories()
        missingExe.resolve("Contents/Info.plist").writeText(
            plistXml(
                bundleId = "io.github.mischiefmerged.nuvioroid",
                executableName = "NuvioRoid",
                version = "0.1.36.3",
            ),
        )
        assertFalse(MacOsUpdateInstaller.isValidAppBundle(missingExe), "Empty Contents/MacOS must fail validation")

        val nonExec = tempDir.resolve("NonExec.app")
        nonExec.resolve("Contents/MacOS").createDirectories()
        val bin = nonExec.resolve("Contents/MacOS/NuvioRoid").apply { writeText("#!/bin/sh\n") }
        bin.toFile().setExecutable(false)
        nonExec.resolve("Contents/Info.plist").writeText(
            plistXml(
                bundleId = "io.github.mischiefmerged.nuvioroid",
                executableName = "NuvioRoid",
                version = "0.1.36.3",
            ),
        )
        assertFalse(MacOsUpdateInstaller.isValidAppBundle(nonExec), "Non-executable binary must fail validation")
    }

    // --- Finding 7 ---

    @Test
    fun `installer stages DMG into private owner-only file before attaching`() {
        val stagingRoot = tempDir.resolve("tmp-updates").createDirectories()
        val appsDir = tempDir.resolve("Applications").createDirectories()
        val installedApp = createValidBundle(appsDir.resolve("NuvioRoid.app"), version = "0.1.36.2", marker = "old")
        val dmgFile = stagingRoot.resolve("NuvioRoid-0.1.36.3.dmg").apply { writeText("dmg bytes") }
        val dmgHash = MacOsUpdateInstaller.computeSha256Hex(dmgFile)

        var attachedPath: Path? = null
        val commands = noopCommands(
            attachDmg = { dmg, mountDir ->
                attachedPath = dmg
                createValidBundle(mountDir.resolve("NuvioRoid.app"), version = "0.1.36.3", marker = "new")
                Result.success(Unit)
            },
        )

        val result = MacOsUpdateInstaller.installVerifiedArtifact(
            stagedArtifact = dmgFile,
            expectedSha256 = dmgHash,
            runningAppBundle = installedApp,
            appSupportDir = tempDir.resolve("AppSupport").createDirectories(),
            backupRootDir = tempDir.resolve("Backups"),
            tempRoot = stagingRoot,
            commands = commands,
        )
        assertTrue(result.isSuccess, "Expected install to succeed: ${result.exceptionOrNull()}")
        assertNotNull(attachedPath)
        assertNotEquals(dmgFile.toAbsolutePath().normalize(), attachedPath!!.toAbsolutePath().normalize())
        val parentPerms = Files.getPosixFilePermissions(attachedPath!!.parent)
        assertFalse(PosixFilePermission.GROUP_WRITE in parentPerms)
        assertFalse(PosixFilePermission.OTHERS_WRITE in parentPerms)
        assertFalse(PosixFilePermission.OTHERS_READ in parentPerms)
    }

    // --- Finding 8 ---

    @Test
    fun `installer refuses older payload inside DMG even if feed advertised newer version`() {
        val stagingRoot = tempDir.resolve("tmp-updates").createDirectories()
        val appsDir = tempDir.resolve("Applications").createDirectories()
        val installedApp = createValidBundle(appsDir.resolve("NuvioRoid.app"), version = "0.1.36.2", marker = "current")
        val dmgFile = stagingRoot.resolve("NuvioRoid-0.1.36.3.dmg").apply { writeText("old payload bytes") }
        val dmgHash = MacOsUpdateInstaller.computeSha256Hex(dmgFile)

        val commands = noopCommands(
            attachDmg = { _, mountDir ->
                // Legacy 0.1.36.1 bundle packaged as CFBundleShortVersionString=1.1.36
                createValidBundle(
                    mountDir.resolve("NuvioRoid.app"),
                    version = null,
                    shortVersion = "1.1.36",
                    marker = "old-0.1.36.1",
                )
                Result.success(Unit)
            },
        )

        val result = MacOsUpdateInstaller.installVerifiedArtifact(
            stagedArtifact = dmgFile,
            expectedSha256 = dmgHash,
            runningAppBundle = installedApp,
            appSupportDir = tempDir.resolve("AppSupport").createDirectories(),
            backupRootDir = tempDir.resolve("Backups"),
            tempRoot = stagingRoot,
            commands = commands,
        )

        assertTrue(result.isFailure, "Installer must reject older payload inside DMG")
        assertEquals("current", installedApp.resolve("Contents/marker.txt").readText())
    }

    // --- Finding 9 ---

    @Test
    fun `clearMacQuarantine does not clear quarantine on external symlink target`() {
        val externalFile = tempDir.resolve("external-app.txt").apply { writeText("quarantined") }
        val setProc = ProcessBuilder("xattr", "-w", "com.apple.quarantine", "0081;00000000;Safari;", externalFile.toString())
            .redirectErrorStream(true)
            .start()
        assertEquals(0, setProc.waitFor(), "Precondition: set xattr on external file")

        val appBundle = createValidBundle(tempDir.resolve("Staged.app"))
        Files.createSymbolicLink(appBundle.resolve("Contents/evil-link"), externalFile)

        MacOsUpdateInstaller.clearMacQuarantine(appBundle).getOrThrow()

        val checkProc = ProcessBuilder("xattr", "-p", "com.apple.quarantine", externalFile.toString())
            .redirectErrorStream(true)
            .start()
        val code = checkProc.waitFor()
        assertEquals(0, code, "Quarantine attribute on external symlink target must remain intact")
    }

    @Test
    fun `installer rejects bundle with escaping symlink before clearing quarantine or installing`() {
        val externalFile = tempDir.resolve("external-target.txt").apply { writeText("outside") }
        val stagingRoot = tempDir.resolve("tmp-updates").createDirectories()
        val appsDir = tempDir.resolve("Applications").createDirectories()
        val installedApp = createValidBundle(appsDir.resolve("NuvioRoid.app"), version = "0.1.36.2", marker = "old")
        val dmgFile = stagingRoot.resolve("NuvioRoid-0.1.36.3.dmg").apply { writeText("dmg bytes") }
        val dmgHash = MacOsUpdateInstaller.computeSha256Hex(dmgFile)

        var quarantineCleared = false
        val commands = noopCommands(
            attachDmg = { _, mountDir ->
                val app = createValidBundle(mountDir.resolve("NuvioRoid.app"), version = "0.1.36.3", marker = "new")
                Files.createSymbolicLink(app.resolve("Contents/escape"), externalFile)
                Result.success(Unit)
            },
        ).copy(
            clearQuarantine = {
                quarantineCleared = true
                Result.success(Unit)
            },
        )

        val result = MacOsUpdateInstaller.installVerifiedArtifact(
            stagedArtifact = dmgFile,
            expectedSha256 = dmgHash,
            runningAppBundle = installedApp,
            appSupportDir = tempDir.resolve("AppSupport").createDirectories(),
            backupRootDir = tempDir.resolve("Backups"),
            tempRoot = stagingRoot,
            commands = commands,
        )

        assertTrue(result.isFailure, "Bundle with escaping symlink must be rejected")
        assertFalse(quarantineCleared, "Quarantine removal must not run on bundle with escaping symlink")
        assertEquals("old", installedApp.resolve("Contents/marker.txt").readText())
    }

    // --- Finding 10 ---

    @Test
    fun `manifest and staging reject parent directory traversal asset names`() = runTest {
        val hash = "a".repeat(64)
        val traversalManifest = """
            {"version": "0.1.36.5", "path": "/Volumes/Share/release/..", "sha256": "$hash"}
        """.trimIndent()
        assertFails("Manifest with trailing /.. path must be rejected") {
            PrivateManifestUpdateFeed.parseManifest(traversalManifest, "/tmp/manifest.json")
        }

        val sourceDir = tempDir.resolve("share/release").createDirectories()
        sourceDir.resolve("canary.txt").writeText("overwrite attempt")
        val downloadResult = AppUpdaterPlatform.downloadUpdateAsset(
            assetUrl = sourceDir.toString(),
            assetName = "..",
            onProgress = { _, _ -> },
        )
        assertTrue(downloadResult.isFailure, "Staging assetName '..' must fail")
        assertFalse(
            MacOsUpdateInstaller.tempUpdatesDir().parent.resolve("canary.txt").exists(),
            "Staging '..' must never write into parent of tempUpdatesDir",
        )
    }

    // --- Finding 11 (Sequence A & Sequence B) ---

    @Test
    fun `installer aborts before commit and controller defers when playback resumes mid-install`() {
        val stagingRoot = tempDir.resolve("tmp-updates").createDirectories()
        val appsDir = tempDir.resolve("Applications").createDirectories()
        val installedApp = createValidBundle(appsDir.resolve("NuvioRoid.app"), version = "0.1.36.2", marker = "old")
        val dmgFile = stagingRoot.resolve("NuvioRoid-0.1.36.3.dmg").apply { writeText("dmg bytes") }
        val dmgHash = MacOsUpdateInstaller.computeSha256Hex(dmgFile)

        var playing = false
        var trashedOrRelaunched = false
        val gate = InstallGate(isVideoPlaying = { playing }, isDownloadBusy = { false })

        val commands = noopCommands(
            attachDmg = { _, mountDir ->
                // Simulate playback resuming while DMG is being mounted/staged
                playing = true
                createValidBundle(mountDir.resolve("NuvioRoid.app"), version = "0.1.36.3", marker = "new")
                Result.success(Unit)
            },
        ).copy(
            moveToTrash = {
                trashedOrRelaunched = true
                false
            },
            launchRelaunchHelper = { _, _, _ ->
                trashedOrRelaunched = true
                Result.success(Unit)
            },
            scheduleExit = { trashedOrRelaunched = true },
        )

        var storedChannel: String? = null
        val prefs = UpdatePreferences(
            versionName = "0.1.36.2",
            readChannel = { storedChannel },
            writeChannel = { storedChannel = it },
            writeIgnoredTag = {},
            readAutoDailyCheck = { true },
            writeAutoDailyCheck = {},
            readLastCheckEpochMs = { null },
            writeLastCheckEpochMs = {},
            readLastWhatsNewVersion = { "0.1.36.2" },
            writeLastWhatsNewVersion = {},
            persistDefaults = false,
        )
        val remoteUpdate = AppUpdate(
            tag = "nuvioroid-0.1.36.3",
            title = "NuvioRoid 0.1.36.3",
            notes = "Notes",
            releaseUrl = null,
            assetName = "NuvioRoid-0.1.36.3.dmg",
            assetUrl = "https://example.test/0.1.36.3.dmg",
            assetSizeBytes = 100L,
            expectedSha256 = dmgHash,
            versionDisplay = "0.1.36.3",
            buildNumber = 3,
        )
        val controller = AppUpdaterController(
            scope = scope,
            preferences = prefs,
            fetchUpdate = { Result.success(remoteUpdate) },
            installGate = gate,
            downloadAsset = { _, _, _ -> Result.success(dmgFile.toString()) },
            verifyAsset = { _, _ -> Result.success(Unit) },
            installAsset = { path, sha, expectedVer, ensureCanCommit ->
                MacOsUpdateInstaller.installVerifiedArtifact(
                    stagedArtifact = Path.of(path),
                    expectedSha256 = sha,
                    runningAppBundle = installedApp,
                    appSupportDir = tempDir.resolve("AppSupport").createDirectories(),
                    backupRootDir = tempDir.resolve("Backups"),
                    tempRoot = stagingRoot,
                    expectedVersion = expectedVer?.let { NuvioRoidVersion.parse(it) },
                    installedVersion = NuvioRoidVersion(0, 1, 36, 2),
                    canCommitInstall = {
                        ensureCanCommit()
                        null
                    },
                    commands = commands,
                )
            },
            nowEpochMs = { 1_700_000_000_000L },
            feedInfoProvider = { UpdateFeedInfo(UpdateFeedKind.PUBLIC_GITHUB, "mischief-merged/NuvioRoid") },
            usesForkFeed = true,
            localForkVersion = NuvioRoidVersion(0, 1, 36, 2),
            currentVersionDisplay = "0.1.36.2",
            currentBuildNumber = 2,
        )

        controller.checkForUpdates(force = true, showNoUpdateFeedback = false)
        controller.downloadUpdate()

        assertFalse(trashedOrRelaunched, "Installer must abort before trashing/relaunching when activity resumes")
        assertEquals("old", installedApp.resolve("Contents/marker.txt").readText())
        assertEquals(InstallBlockReason.VIDEO_PLAYING, controller.uiState.value.deferredInstallReason)
        assertNull(controller.uiState.value.errorMessage)

        // When playback stops, the controller clears the deferral and re-offers
        playing = false
        controller.refreshInstallGate()
        assertNull(controller.uiState.value.deferredInstallReason)
        assertTrue(controller.uiState.value.showDialog)
    }

    @Test
    fun `hasActiveOrFinishingDownloads stays true while post-download companions are still saving`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val slowCompanion = object : DownloadCompanion {
            override val name: String = "slow-subtitles"
            override fun isDue(item: DownloadItem, localVideoUri: String): Boolean = true
            override suspend fun run(item: DownloadItem, localVideoUri: String): com.nuvio.app.features.downloads.CompanionOutcome {
                started.complete(Unit)
                gate.await()
                return com.nuvio.app.features.downloads.CompanionOutcome.Saved
            }
        }
        val companions = DownloadCompanions(
            companions = listOf(slowCompanion),
            isOnline = { true },
            scope = scope,
            io = Dispatchers.Default,
        )
        val prevCompanions = DownloadsRepository.companions
        DownloadsRepository.companions = { companions }
        try {
            val videoPath = tempDir.resolve("video.mkv").apply { writeText("video") }.toString()
            val item = DownloadItem(
                id = "dl-1",
                contentType = "movie",
                parentMetaId = "tt123",
                parentMetaType = "movie",
                videoId = "tt123",
                title = "Sample",
                streamTitle = "1080p",
                providerName = "Test",
                sourceUrl = "https://example.test/video.mkv",
                localFileUri = videoPath,
                fileName = "video.mkv",
                status = DownloadStatus.Completed,
                downloadedBytes = 100L,
                totalBytes = 100L,
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L,
            )
            val job = companions.saveAfterDownload(item, item.localFileUri!!)
            started.await()

            assertTrue(
                DownloadsRepository.hasActiveOrFinishingDownloads(),
                "Gate must treat active post-download companion saves as finishing downloads",
            )

            gate.complete(Unit)
            job.join()
            // Allow any state update to settle
            delay(10)
            assertFalse(DownloadsRepository.hasActiveOrFinishingDownloads())
            scope.cancel()
            companions.saveAfterDownload(item, item.localFileUri!!).join()
            assertFalse(companions.hasActiveSaves.value, "A save cancelled before starting must release its activity count")
        } finally {
            DownloadsRepository.companions = prevCompanions
        }
    }

    private fun createValidBundle(
        bundleDir: Path,
        bundleId: String = "io.github.mischiefmerged.nuvioroid",
        executableName: String = "NuvioRoid",
        version: String? = "0.1.36.2",
        shortVersion: String = "1.1.36",
        marker: String = "v",
    ): Path {
        val contents = bundleDir.resolve("Contents").createDirectories()
        val exe = contents.resolve("MacOS").createDirectories().resolve(executableName)
        exe.writeText("#!/bin/sh\n")
        exe.toFile().setExecutable(true)
        contents.resolve("Info.plist").writeText(plistXml(bundleId, executableName, version, shortVersion))
        contents.resolve("marker.txt").writeText(marker)
        return bundleDir
    }

    private fun plistXml(
        bundleId: String,
        executableName: String,
        version: String?,
        shortVersion: String = "1.1.36",
    ): String {
        val nuvioRoidKey = if (version != null) {
            "\n    <key>NuvioRoidVersion</key>\n    <string>$version</string>"
        } else {
            ""
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>CFBundleIdentifier</key>
                <string>$bundleId</string>
                <key>CFBundleExecutable</key>
                <string>$executableName</string>
                <key>CFBundleShortVersionString</key>
                <string>$shortVersion</string>
                <key>CFBundleVersion</key>
                <string>$shortVersion</string>$nuvioRoidKey
            </dict>
            </plist>
        """.trimIndent().trimStart()
    }

    private fun noopCommands(
        attachDmg: (Path, Path) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    ): MacOsInstallCommands = MacOsInstallCommands(
        attachDmg = attachDmg,
        detachDmg = { mountDir -> MacOsUpdateInstaller.deletePathWithoutFollowingSymlinks(mountDir) },
        copyApp = { src, dest ->
            copyAppFixture(src, dest)
            Result.success(Unit)
        },
        clearQuarantine = { Result.success(Unit) },
        moveToTrash = { target -> run { MacOsUpdateInstaller.deletePathWithoutFollowingSymlinks(target); true } },
        launchRelaunchHelper = { _, _, _ -> Result.success(Unit) },
        scheduleExit = {},
    )
}

/** Mirrors the bundle copy command, preserving executable bits and symbolic links. */
internal fun copyAppFixture(source: Path, destination: Path) {
    Files.walk(source).use { entries ->
        entries.forEach { entry ->
            val target = destination.resolve(source.relativize(entry))
            if (Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(target)
            } else {
                Files.copy(
                    entry,
                    target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.COPY_ATTRIBUTES,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                )
            }
        }
    }
}
