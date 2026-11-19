package com.nuvio.app.features.updater

import com.nuvio.app.features.settings.ForkIdentity
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NuvioRoidUpdateFeedAndVersionTest {
    @Test
    fun `empty feed config selects public GitHub feed and non-empty selects private feed`() {
        val publicFeed = UpdateFeedSelector.currentFeed(configuredFeedLocation = "   ", hostArch = "arm64")
        assertIs<PublicGitHubUpdateFeed>(publicFeed)
        assertEquals(UpdateFeedKind.PUBLIC_GITHUB, publicFeed.info.kind)
        assertEquals("mischief-merged/NuvioRoid", publicFeed.info.location)

        val privateFeed = UpdateFeedSelector.currentFeed(
            configuredFeedLocation = "http://192.168.1.20:8080/nuvioroid.json",
            hostArch = "arm64",
        )
        assertIs<PrivateManifestUpdateFeed>(privateFeed)
        assertEquals(UpdateFeedKind.PRIVATE_MANIFEST, privateFeed.info.kind)
        assertEquals("http://192.168.1.20:8080/nuvioroid.json", privateFeed.info.location)
    }

    @Test
    fun `public GitHub feed picks newest non-draft nuvioroid release for host arch with sha256`() = runTest {
        val releasesFixture = """
            [
              {
                "tag_name": "nuvioroid-0.1.36.4",
                "name": "Draft release",
                "draft": true,
                "prerelease": false,
                "body": "Should be ignored",
                "assets": [
                  {"name": "NuvioRoid-macOS-arm64-0.1.36.4.dmg", "browser_download_url": "https://example.test/0.1.36.4-arm64.dmg", "size": 1000},
                  {"name": "SHA256SUMS.txt", "browser_download_url": "https://example.test/0.1.36.4/SHA256SUMS.txt"}
                ]
              },
              {
                "tag_name": "v0.1.99",
                "name": "Upstream tag",
                "draft": false,
                "assets": [
                  {"name": "Nuvio-macOS-arm64-0.1.99.dmg", "browser_download_url": "https://example.test/upstream.dmg", "size": 1000},
                  {"name": "SHA256SUMS.txt", "browser_download_url": "https://example.test/upstream/SHA256SUMS.txt"}
                ]
              },
              {
                "tag_name": "nuvioroid-1.1.36.1",
                "name": "First alpha",
                "draft": false,
                "prerelease": true,
                "body": "Initial release",
                "assets": [
                  {"name": "Nuvio-macOS-arm64-0.1.36-alpha.dmg", "browser_download_url": "https://example.test/1.1.36.1-arm64.dmg", "size": 1200},
                  {"name": "SHA256SUMS.txt", "browser_download_url": "https://example.test/1.1.36.1/SHA256SUMS.txt"}
                ]
              },
              {
                "tag_name": "nuvioroid-0.1.36.3",
                "name": "NuvioRoid 0.1.36.3",
                "draft": false,
                "prerelease": true,
                "html_url": "https://github.com/mischief-merged/NuvioRoid/releases/tag/nuvioroid-0.1.36.3",
                "body": "- Added safe updates\n- Fixed crashes",
                "assets": [
                  {"name": "NuvioRoid-macOS-x86_64-0.1.36.3.dmg", "browser_download_url": "https://example.test/x86_64.dmg", "size": 2100},
                  {"name": "NuvioRoid-macOS-arm64-0.1.36.3.dmg", "browser_download_url": "https://example.test/arm64.dmg", "size": 2000},
                  {"name": "SHA256SUMS.txt", "browser_download_url": "https://example.test/0.1.36.3/SHA256SUMS.txt"}
                ]
              }
            ]
        """.trimIndent()

        val expectedArmHash = "a".repeat(64)
        val expectedX64Hash = "b".repeat(64)
        val sha256SumsFixture = """
            $expectedX64Hash  NuvioRoid-macOS-x86_64-0.1.36.3.dmg
            $expectedArmHash  NuvioRoid-macOS-arm64-0.1.36.3.dmg
        """.trimIndent()

        val requestedUrls = mutableListOf<String>()
        val fetcher = object : FeedTextFetcher {
            override suspend fun fetchHttp(url: String, headers: Map<String, String>): Result<FeedHttpResponse> {
                requestedUrls += url
                return when {
                    url.startsWith("https://api.github.com/repos/mischief-merged/NuvioRoid/releases") ->
                        Result.success(FeedHttpResponse(200, releasesFixture))
                    url == "https://example.test/0.1.36.3/SHA256SUMS.txt" ->
                        Result.success(FeedHttpResponse(200, sha256SumsFixture))
                    else -> Result.failure(IllegalStateException("Unexpected URL: $url"))
                }
            }

            override suspend fun readLocal(location: String): Result<String> =
                Result.failure(UnsupportedOperationException())
        }

        val armResult = PublicGitHubUpdateFeed(hostArch = "arm64", fetcher = fetcher).fetchLatest().getOrThrow()
        assertNotNull(armResult)
        assertEquals("nuvioroid-0.1.36.3", armResult.tag)
        assertEquals("0.1.36.3", armResult.versionDisplay)
        assertEquals(3, armResult.version.build)
        assertEquals("NuvioRoid-macOS-arm64-0.1.36.3.dmg", armResult.assetName)
        assertEquals("https://example.test/arm64.dmg", armResult.assetUrl)
        assertEquals(expectedArmHash, armResult.expectedSha256)
        assertTrue(armResult.notes.contains("Added safe updates"))
        assertTrue(requestedUrls.none { it.contains("NuvioMedia") })

        val x64Result = PublicGitHubUpdateFeed(hostArch = "x86_64", fetcher = fetcher).fetchLatest().getOrThrow()
        assertNotNull(x64Result)
        assertEquals("NuvioRoid-macOS-x86_64-0.1.36.3.dmg", x64Result.assetName)
        assertEquals(expectedX64Hash, x64Result.expectedSha256)
    }

    @Test
    fun `public GitHub feed never selects an asset built for the wrong architecture`() {
        val releasesJson = """
            [
              {
                "tag_name": "nuvioroid-0.1.36.3",
                "draft": false,
                "assets": [
                  {"name": "NuvioRoid-macOS-arm64-0.1.36.3.dmg", "browser_download_url": "https://example.test/arm64.dmg"},
                  {"name": "SHA256SUMS", "browser_download_url": "https://example.test/SHA256SUMS"}
                ]
              }
            ]
        """.trimIndent()

        assertNotNull(PublicGitHubUpdateFeed.selectNewestGitHubRelease(releasesJson, "arm64"))
        assertNull(PublicGitHubUpdateFeed.selectNewestGitHubRelease(releasesJson, "x86_64"))
    }

    @Test
    fun `private manifest feed parses HTTPS and local file manifests and resolves relative paths`() = runTest {
        val hash = "c".repeat(64)
        val httpManifest = """
            {
              "version": "0.1.36-alpha",
              "build": 5,
              "dmg": "NuvioRoid-macOS-arm64-0.1.36.5.dmg",
              "sha256": "$hash",
              "notes": "LAN build notes",
              "sizeBytes": 123456
            }
        """.trimIndent()
        val httpFeed = PrivateManifestUpdateFeed(
            feedLocation = "https://192.168.1.50:8000/updates/manifest.json",
            fetcher = object : FeedTextFetcher {
                override suspend fun fetchHttp(url: String, headers: Map<String, String>): Result<FeedHttpResponse> {
                    assertEquals("https://192.168.1.50:8000/updates/manifest.json", url)
                    return Result.success(FeedHttpResponse(200, httpManifest))
                }

                override suspend fun readLocal(location: String): Result<String> =
                    Result.failure(UnsupportedOperationException())
            },
        )
        val parsedHttp = httpFeed.fetchLatest().getOrThrow()
        assertNotNull(parsedHttp)
        assertEquals("0.1.36.5", parsedHttp.versionDisplay)
        assertEquals(5, parsedHttp.version.build)
        assertEquals("https://192.168.1.50:8000/updates/NuvioRoid-macOS-arm64-0.1.36.5.dmg", parsedHttp.assetUrl)
        assertEquals(hash, parsedHttp.expectedSha256)
        assertEquals("LAN build notes", parsedHttp.notes)
        assertEquals(123456L, parsedHttp.assetSizeBytes)

        val tempDir = Files.createTempDirectory("nuvioroid-manifest-test")
        try {
            val manifestFile = tempDir.resolve("update.json")
            manifestFile.writeText(
                """
                {
                  "version": "0.1.37.1",
                  "build": 1,
                  "path": "./NuvioRoid-0.1.37.1.dmg",
                  "sha256": "${"D".repeat(64)}"
                }
                """.trimIndent(),
            )
            val localFeed = PrivateManifestUpdateFeed(feedLocation = manifestFile.toString())
            val parsedLocal = localFeed.fetchLatest().getOrThrow()
            assertNotNull(parsedLocal)
            assertEquals("0.1.37.1", parsedLocal.versionDisplay)
            assertEquals(tempDir.resolve("NuvioRoid-0.1.37.1.dmg").toString(), parsedLocal.assetUrl)
            assertEquals("d".repeat(64), parsedLocal.expectedSha256)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `version ordering uses ForkIdentity and treats legacy 1_1_x macOS package tag as older`() {
        val local = NuvioRoidVersion.fromForkIdentity(upstreamVersion = "0.1.36-alpha", build = ForkIdentity.BUILD)
        assertEquals("0.1.36.${ForkIdentity.BUILD}", local.format())

        // Legacy 1.1.36.1 tag from the macOS jpackage version must not look newer than 0.1.36.2
        assertFalse(NuvioRoidVersion.isRemoteNewer("nuvioroid-1.1.36.1", localVersion = local))
        assertFalse(NuvioRoidVersion.isRemoteNewer("1.1.36.1", localVersion = local))

        // Same version and build is not newer
        assertFalse(NuvioRoidVersion.isRemoteNewer("nuvioroid-0.1.36.${ForkIdentity.BUILD}", localVersion = local))
        assertFalse(
            NuvioRoidVersion.isRemoteNewer(
                remoteVersion = "0.1.36-alpha",
                remoteBuild = ForkIdentity.BUILD,
                localVersion = local,
            ),
        )

        // Higher build on same upstream version is newer
        assertTrue(NuvioRoidVersion.isRemoteNewer("nuvioroid-0.1.36.${ForkIdentity.BUILD + 1}", localVersion = local))
        assertTrue(
            NuvioRoidVersion.isRemoteNewer(
                remoteVersion = "0.1.36-alpha",
                remoteBuild = ForkIdentity.BUILD + 1,
                localVersion = local,
            ),
        )

        // Higher upstream patch or minor is newer regardless of build reset to 1
        assertTrue(NuvioRoidVersion.isRemoteNewer("nuvioroid-0.1.37.1", localVersion = local))
        assertTrue(NuvioRoidVersion.isRemoteNewer("nuvioroid-0.2.0.1", localVersion = local))
        assertTrue(NuvioRoidVersion.isRemoteNewer("nuvioroid-1.0.0.1", localVersion = local))
    }
}
