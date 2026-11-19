package com.nuvio.app.features.updater

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.settings.SettingsCategory
import com.nuvio.app.features.settings.SettingsPage
import com.nuvio.app.features.settings.releaseNotesSettingsContent
import com.nuvio.app.features.settings.settingsRootContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NuvioRoidReleaseNotesTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `parser extracts New Improved Fixed and Known issues sections and skips headers for headline`() {
        val markdown = """
            <!-- Draft comment -->
            # NuvioRoid 0.1.36.4

            ## New
            - Added **offline** release notes history.
            - Added `Update details` sheet.

            ### Improved
            * Faster startup checks.

            **Fixed**
            - Fixed crash when switching profiles.

            Known issues:
            - Unsigned macOS builds require Open Anyway once.
        """.trimIndent()

        val parsed = ReleaseNotesParser.parse(markdown)
        assertEquals(
            listOf(
                ReleaseNoteSectionKind.NEW,
                ReleaseNoteSectionKind.IMPROVED,
                ReleaseNoteSectionKind.FIXED,
                ReleaseNoteSectionKind.KNOWN_ISSUES,
            ),
            parsed.sections.map { it.kind },
        )
        assertEquals(
            listOf("Added offline release notes history.", "Added Update details sheet."),
            parsed.sections[0].items,
        )
        assertEquals(listOf("Faster startup checks."), parsed.sections[1].items)
        assertEquals(listOf("Fixed crash when switching profiles."), parsed.sections[2].items)
        assertEquals(listOf("Unsigned macOS builds require Open Anyway once."), parsed.sections[3].items)
        assertEquals("Added offline release notes history.", parsed.headline)
    }

    @Test
    fun `parser handles unstructured plain text and empty notes`() {
        val plain = ReleaseNotesParser.parse("Faster downloads and safer updates")
        assertEquals(1, plain.sections.size)
        assertEquals(ReleaseNoteSectionKind.OTHER, plain.sections.first().kind)
        assertEquals("Faster downloads and safer updates", plain.headline)

        val empty = ReleaseNotesParser.parse("   \n<!-- empty -->\n  ")
        assertTrue(empty.sections.isEmpty())
        assertNull(empty.headline)
    }

    @Test
    fun `version range selection returns versions between installed and target newest first`() {
        val releases = listOf(
            entry("1.1.36.1", "nuvioroid-1.1.36.1", "## New\n- Initial release."),
            entry("0.1.36.2", "nuvioroid-0.1.36.2", "## New\n- Installed version."),
            entry("0.1.36.3", "nuvioroid-0.1.36.3", "## New\n- Added release notes."),
            entry("0.1.36.4", "nuvioroid-0.1.36.4", "## Improved\n- Smarter source ranking."),
            entry("0.1.36.5", "nuvioroid-0.1.36.5", "## Fixed\n- Fixed stall recovery."),
            entry("0.1.36.6", "nuvioroid-0.1.36.6", "## New\n- Future version beyond target."),
        )

        val selected = ReleaseNotesParser.selectVersionRange(
            releases = releases,
            installedVersion = NuvioRoidVersion(0, 1, 36, 2),
            targetVersion = NuvioRoidVersion(0, 1, 36, 5),
        )

        assertEquals(listOf("0.1.36.5", "0.1.36.4", "0.1.36.3"), selected.map { it.versionDisplay })

        val summary = ReleaseNotesParser.formatBannerSummary(selected)
        assertNotNull(summary)
        val lines = summary.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("0.1.36.5: Fixed stall recovery."))
        assertTrue(lines[1].startsWith("0.1.36.4: Smarter source ranking."))
        assertTrue(lines[2].startsWith("0.1.36.3: Added release notes."))
    }

    @Test
    fun `public GitHub feed collects intermediate release notes for range selection`() = runTest {
        val releasesJson = """
            [
              {
                "tag_name": "nuvioroid-0.1.36.5",
                "name": "NuvioRoid 0.1.36.5",
                "draft": false,
                "body": "## New\n- Version five feature.",
                "assets": [
                  {"name": "NuvioRoid-macOS-arm64-0.1.36.5.dmg", "browser_download_url": "https://example.test/5.dmg", "size": 5000},
                  {"name": "SHA256SUMS", "browser_download_url": "https://example.test/5/SHA256SUMS"}
                ]
              },
              {
                "tag_name": "nuvioroid-0.1.36.4",
                "name": "NuvioRoid 0.1.36.4",
                "draft": true,
                "body": "## New\n- Draft should not appear.",
                "assets": []
              },
              {
                "tag_name": "nuvioroid-0.1.36.3",
                "name": "NuvioRoid 0.1.36.3",
                "draft": false,
                "body": "## Fixed\n- Version three fix.",
                "assets": [
                  {"name": "NuvioRoid-macOS-arm64-0.1.36.3.dmg", "browser_download_url": "https://example.test/3.dmg", "size": 3000},
                  {"name": "SHA256SUMS", "browser_download_url": "https://example.test/3/SHA256SUMS"}
                ]
              },
              {
                "tag_name": "nuvioroid-0.1.36.2",
                "name": "NuvioRoid 0.1.36.2",
                "draft": false,
                "body": "## New\n- Already installed.",
                "assets": []
              }
            ]
        """.trimIndent()
        val sums = "${"a".repeat(64)}  NuvioRoid-macOS-arm64-0.1.36.5.dmg"
        val fetcher = object : FeedTextFetcher {
            override suspend fun fetchHttp(url: String, headers: Map<String, String>): Result<FeedHttpResponse> =
                when {
                    url.contains("/releases") -> Result.success(FeedHttpResponse(200, releasesJson))
                    url.endsWith("/SHA256SUMS") -> Result.success(FeedHttpResponse(200, sums))
                    else -> Result.failure(IllegalStateException("Unexpected URL $url"))
                }

            override suspend fun readLocal(location: String): Result<String> =
                Result.failure(UnsupportedOperationException())
        }

        val candidate = PublicGitHubUpdateFeed(hostArch = "arm64", fetcher = fetcher).fetchLatest().getOrThrow()
        assertNotNull(candidate)
        val range = ReleaseNotesParser.selectVersionRange(
            releases = candidate.releaseHistory,
            installedVersion = NuvioRoidVersion(0, 1, 36, 2),
            targetVersion = candidate.version,
        )
        assertEquals(listOf("0.1.36.5", "0.1.36.3"), range.map { it.versionDisplay })
    }

    @Test
    fun `private manifest feed parses markdown notes and optional releases history`() {
        val rawJson = """
            {
              "version": "0.1.36.5",
              "build": 5,
              "dmg": "NuvioRoid-0.1.36.5.dmg",
              "sha256": "${"b".repeat(64)}",
              "sizeBytes": 10485760,
              "notes": "## New\n- Private build five.",
              "releases": [
                {
                  "version": "0.1.36.3",
                  "notes": "## Fixed\n- Private build three fix."
                },
                {
                  "version": "0.1.36.4",
                  "notes": "## Improved\n- Private build four improvement."
                }
              ]
            }
        """.trimIndent()

        val candidate = PrivateManifestUpdateFeed.parseManifest(rawJson, "https://192.168.1.10/update.json")
        val range = ReleaseNotesParser.selectVersionRange(
            releases = candidate.releaseHistory,
            installedVersion = NuvioRoidVersion(0, 1, 36, 2),
            targetVersion = candidate.version,
        )
        assertEquals(listOf("0.1.36.5", "0.1.36.4", "0.1.36.3"), range.map { it.versionDisplay })
    }

    @Test
    fun `whats new flag shows once per version and never repeats after dismissal`() {
        assertTrue(UpdatePreferences.shouldShowWhatsNewForVersion("0.1.36.2", null))
        assertTrue(UpdatePreferences.shouldShowWhatsNewForVersion("0.1.36.2", "0.1.36.1"))
        assertFalse(UpdatePreferences.shouldShowWhatsNewForVersion("0.1.36.2", "0.1.36.2"))

        var persistedChannel: String? = null
        var persistedWhatsNew: String? = null
        fun makePrefs() = UpdatePreferences(
            versionName = "0.1.36.2",
            readChannel = { persistedChannel },
            writeChannel = { persistedChannel = it },
            writeIgnoredTag = {},
            readAutoDailyCheck = { true },
            writeAutoDailyCheck = {},
            readLastCheckEpochMs = { null },
            writeLastCheckEpochMs = {},
            readLastWhatsNewVersion = { persistedWhatsNew },
            writeLastWhatsNewVersion = { persistedWhatsNew = it },
            persistDefaults = false,
        )

        val firstLaunchController = createController(prefs = makePrefs(), versionDisplay = "0.1.36.2", build = 2)
        assertTrue(firstLaunchController.uiState.value.showWhatsNew)
        assertTrue(firstLaunchController.uiState.value.whatsNewEntries.any { it.versionDisplay == "0.1.36.2" })

        firstLaunchController.dismissWhatsNew()
        assertFalse(firstLaunchController.uiState.value.showWhatsNew)
        assertEquals("0.1.36.2", persistedWhatsNew)

        val secondLaunchSameVersion = createController(prefs = makePrefs(), versionDisplay = "0.1.36.2", build = 2)
        assertFalse(secondLaunchSameVersion.uiState.value.showWhatsNew)

        val nextVersionHistory = LocalReleaseNotesHistory.entries + entry(
            "0.1.36.3",
            "nuvioroid-0.1.36.3",
            "## New\n- Next version notes.",
        )
        val afterUpdateController = createController(
            prefs = makePrefs(),
            versionDisplay = "0.1.36.3",
            build = 3,
            localNotes = nextVersionHistory,
        )
        assertTrue(afterUpdateController.uiState.value.showWhatsNew)
        assertEquals(listOf("0.1.36.3"), afterUpdateController.uiState.value.whatsNewEntries.map { it.versionDisplay })
    }

    @Test
    fun `banner and Update details sheet show multi-version notes newest first with size and channel`() {
        val history = listOf(
            entry("0.1.36.5", "nuvioroid-0.1.36.5", "## New\n- Release five feature."),
            entry("0.1.36.4", "nuvioroid-0.1.36.4", "## Improved\n- Release four improvement."),
            entry("0.1.36.3", "nuvioroid-0.1.36.3", "## Fixed\n- Release three bug fix."),
            entry("0.1.36.2", "nuvioroid-0.1.36.2", "## New\n- Installed version should not show."),
        )
        val remoteUpdate = AppUpdate(
            tag = "nuvioroid-0.1.36.5",
            title = "NuvioRoid 0.1.36.5",
            notes = "## New\n- Release five feature.",
            releaseUrl = null,
            assetName = "NuvioRoid-0.1.36.5.dmg",
            assetUrl = "https://example.test/0.1.36.5.dmg",
            assetSizeBytes = 120L * 1024L * 1024L,
            expectedSha256 = "a".repeat(64),
            versionDisplay = "0.1.36.5",
            buildNumber = 5,
            releaseHistory = history,
        )
        val controller = createController(
            prefs = UpdatePreferences(
                versionName = "0.1.36.2",
                readChannel = { null },
                writeChannel = {},
                writeIgnoredTag = {},
                readAutoDailyCheck = { true },
                writeAutoDailyCheck = {},
                readLastCheckEpochMs = { null },
                writeLastCheckEpochMs = {},
                readLastWhatsNewVersion = { "0.1.36.2" },
                writeLastWhatsNewVersion = {},
                persistDefaults = false,
            ),
            remoteUpdate = remoteUpdate,
            feedInfo = UpdateFeedInfo(UpdateFeedKind.PRIVATE_MANIFEST, "http://192.168.1.50/update.json"),
        )
        controller.checkForUpdates(force = true, showNoUpdateFeedback = false)

        compose.setContent {
            NuvioTheme {
                AppUpdaterHost(controller = controller) {
                    Text("App content")
                }
            }
        }

        compose.onNodeWithText("NuvioRoid 0.1.36.5 • 120 MB • Private").assertIsDisplayed()
        compose.onNodeWithText("0.1.36.5: Release five feature.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("0.1.36.4: Release four improvement.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("0.1.36.3: Release three bug fix.", substring = true).assertIsDisplayed()

        compose.onNodeWithText("Update details").performClick()

        compose.onNodeWithText("Updating from 0.1.36.2 to 0.1.36.5").assertIsDisplayed()
        compose.onNodeWithText("Channel: Private (http://192.168.1.50/update.json)").assertIsDisplayed()
        compose.onNodeWithText("Size: 120 MB").assertIsDisplayed()
        compose.onNodeWithText("New").assertIsDisplayed()
        compose.onNodeWithText("Improved").assertIsDisplayed()
        compose.onNodeWithText("Fixed").assertIsDisplayed()
        compose.onNodeWithText("Release five feature.").assertIsDisplayed()
        compose.onNodeWithText("Release four improvement.").assertIsDisplayed()
        compose.onNodeWithText("Release three bug fix.").assertIsDisplayed()
        compose.onNodeWithText("Installed version should not show.", substring = true).assertDoesNotExist()
    }

    @Test
    fun `whats new sheet renders on first launch and dismisses permanently`() {
        var persistedWhatsNew: String? = null
        val prefs = UpdatePreferences(
            versionName = "0.1.36.2",
            readChannel = { null },
            writeChannel = {},
            writeIgnoredTag = {},
            readAutoDailyCheck = { true },
            writeAutoDailyCheck = {},
            readLastCheckEpochMs = { null },
            writeLastCheckEpochMs = {},
            readLastWhatsNewVersion = { persistedWhatsNew },
            writeLastWhatsNewVersion = { persistedWhatsNew = it },
            persistDefaults = false,
        )
        val controller = createController(prefs = prefs, versionDisplay = "0.1.36.2", build = 2)

        compose.setContent {
            NuvioTheme {
                AppUpdaterHost(controller = controller) {
                    Text("App content")
                }
            }
        }

        compose.onNodeWithText("What's new in NuvioRoid 0.1.36.2").assertIsDisplayed()
        compose.onNodeWithText("Got it").performClick()
        assertFalse(controller.uiState.value.showWhatsNew)
        assertEquals("0.1.36.2", persistedWhatsNew)
        compose.onNodeWithText("What's new in NuvioRoid 0.1.36.2").assertDoesNotExist()
    }

    @Test
    fun `settings System shows Release notes row and offline full history`() {
        var openedPage: SettingsPage? = null
        compose.setContent {
            NuvioTheme {
                LazyColumn {
                    settingsRootContent(
                        isTablet = true,
                        category = SettingsCategory.System,
                        showNotificationsEntry = false,
                        onPageClick = { openedPage = it },
                        onCheckForUpdatesClick = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Release notes").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(SettingsPage.ReleaseNotes, openedPage)
    }

    @Test
    fun `offline release notes page displays all local versions newest first`() {
        compose.setContent {
            NuvioTheme {
                LazyColumn {
                    releaseNotesSettingsContent(
                        isTablet = true,
                        entries = LocalReleaseNotesHistory.entries,
                        currentVersionDisplay = "0.1.36.2",
                    )
                }
            }
        }

        compose.onNodeWithText("NuvioRoid 0.1.36.2").assertIsDisplayed()
        compose.onNodeWithText("Installed").assertIsDisplayed()
        compose.onNodeWithText("NuvioRoid 0.1.36.1").performScrollTo().assertIsDisplayed()
    }

    private fun entry(version: String, tag: String, notes: String): ReleaseNoteEntry {
        val parsed = NuvioRoidVersion.parse(version)!!
        return ReleaseNoteEntry(
            version = parsed,
            versionDisplay = parsed.format(),
            tag = tag,
            title = "NuvioRoid ${parsed.format()}",
            notes = notes,
        )
    }

    private fun createController(
        prefs: UpdatePreferences,
        versionDisplay: String = "0.1.36.2",
        build: Int = 2,
        remoteUpdate: AppUpdate? = null,
        feedInfo: UpdateFeedInfo = UpdateFeedInfo(UpdateFeedKind.PUBLIC_GITHUB, "mischief-merged/NuvioRoid"),
        localNotes: List<ReleaseNoteEntry> = LocalReleaseNotesHistory.entries,
    ): AppUpdaterController = AppUpdaterController(
        scope = scope,
        preferences = prefs,
        fetchUpdate = {
            if (remoteUpdate != null) Result.success(remoteUpdate)
            else Result.failure(NoChannelReleaseException())
        },
        installGate = InstallGate(isVideoPlaying = { false }, isDownloadBusy = { false }),
        downloadAsset = { _, _, _ -> Result.failure(UnsupportedOperationException()) },
        verifyAsset = { _, _ -> Result.success(Unit) },
        installAsset = { _, _, _, _ -> Result.success(Unit) },
        nowEpochMs = { 1_700_000_000_000L },
        feedInfoProvider = { feedInfo },
        usesForkFeed = true,
        localForkVersion = NuvioRoidVersion.parse(versionDisplay, explicitBuild = build)!!,
        currentVersionDisplay = versionDisplay,
        currentBuildNumber = build,
        localReleaseNotes = localNotes,
    )
}
