package com.nuvio.app.features.updater

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_github_api_error
import nuvio.composeapp.generated.resources.updates_secure_feed_required
import org.jetbrains.compose.resources.getString

internal enum class UpdateFeedKind {
    PUBLIC_GITHUB,
    PRIVATE_MANIFEST,
}

internal data class UpdateFeedInfo(
    val kind: UpdateFeedKind,
    val location: String,
)

internal data class NuvioRoidUpdateCandidate(
    val version: NuvioRoidVersion,
    val versionDisplay: String,
    val tag: String,
    val title: String,
    val notes: String,
    val releaseUrl: String?,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long?,
    val expectedSha256: String?,
    val feedKind: UpdateFeedKind,
    val releaseHistory: List<ReleaseNoteEntry> = emptyList(),
)

internal interface UpdateFeed {
    val info: UpdateFeedInfo
    suspend fun fetchLatest(): Result<NuvioRoidUpdateCandidate?>
}

internal interface FeedTextFetcher {
    suspend fun fetchHttp(url: String, headers: Map<String, String> = emptyMap()): Result<FeedHttpResponse>
    suspend fun readLocal(location: String): Result<String>
}

internal data class FeedHttpResponse(
    val status: Int,
    val body: String,
    val redirectLocation: String? = null,
)

internal object DefaultFeedTextFetcher : FeedTextFetcher {
    override suspend fun fetchHttp(url: String, headers: Map<String, String>): Result<FeedHttpResponse> =
        fetchHttpsFeedText(url, headers) { address, requestHeaders ->
            val response = httpRequestRaw(
                method = "GET",
                url = address,
                headers = requestHeaders,
                body = "",
                followRedirects = false,
            )
            currentCoroutineContext().ensureActive()
            FeedHttpResponse(response.status, response.body, response.headers["location"])
        }

    override suspend fun readLocal(location: String): Result<String> =
        AppUpdaterPlatform.readLocalFeedText(location)
}

/** Checks every redirect before following it, including release-asset redirects. */
internal suspend fun fetchHttpsFeedText(
    url: String,
    headers: Map<String, String>,
    request: suspend (String, Map<String, String>) -> FeedHttpResponse,
): Result<FeedHttpResponse> = runCatching {
    var address = url
    repeat(6) {
        require(PrivateManifestUpdateFeed.isHttpsLocation(address)) {
            getString(Res.string.updates_secure_feed_required)
        }
        val response = request(address, headers)
        if (response.status !in listOf(301, 302, 303, 307, 308)) return@runCatching response
        address = response.redirectLocation ?: error(getString(Res.string.updates_secure_feed_required))
    }
    error(getString(Res.string.updates_secure_feed_required))
}

internal object UpdateFeedSelector {
    const val PUBLIC_OWNER = "mischief-merged"
    const val PUBLIC_REPO = "NuvioRoid"

    fun currentFeed(
        configuredFeedLocation: String = NuvioRoidUpdateConfig.FEED_LOCATION,
        hostArch: String = AppUpdaterPlatform.currentHostArch(),
        fetcher: FeedTextFetcher = DefaultFeedTextFetcher,
    ): UpdateFeed {
        val trimmed = configuredFeedLocation.trim()
        return if (trimmed.isNotEmpty()) {
            PrivateManifestUpdateFeed(feedLocation = trimmed, fetcher = fetcher)
        } else {
            PublicGitHubUpdateFeed(hostArch = hostArch, fetcher = fetcher)
        }
    }
}

internal class PublicGitHubUpdateFeed(
    private val owner: String = UpdateFeedSelector.PUBLIC_OWNER,
    private val repo: String = UpdateFeedSelector.PUBLIC_REPO,
    private val hostArch: String = AppUpdaterPlatform.currentHostArch(),
    private val fetcher: FeedTextFetcher = DefaultFeedTextFetcher,
) : UpdateFeed {
    override val info: UpdateFeedInfo = UpdateFeedInfo(
        kind = UpdateFeedKind.PUBLIC_GITHUB,
        location = "$owner/$repo",
    )

    private val releasesUrl: String = "https://api.github.com/repos/$owner/$repo/releases?per_page=30"

    override suspend fun fetchLatest(): Result<NuvioRoidUpdateCandidate?> = runCatching {
        val response = fetcher.fetchHttp(
            url = releasesUrl,
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to "NuvioRoid",
            ),
        ).getOrThrow()
        if (response.status == 404) return@runCatching null
        if (response.status !in 200..299) {
            error(getString(Res.string.updates_github_api_error, response.status))
        }
        val selected = selectNewestGitHubRelease(response.body, hostArch) ?: return@runCatching null
        if (!isHttpsUrl(selected.dmgAsset.browserDownloadUrl) || !isHttpsUrl(selected.sumsAsset.browserDownloadUrl)) {
            error("Public update links must use HTTPS.")
        }
        val releaseHistory = collectGitHubReleaseNotes(response.body)
        val sumsResponse = fetcher.fetchHttp(
            url = selected.sumsAsset.browserDownloadUrl,
            headers = mapOf("User-Agent" to "NuvioRoid"),
        ).getOrThrow()
        if (sumsResponse.status !in 200..299) {
            error(getString(Res.string.updates_github_api_error, sumsResponse.status))
        }
        val checksums = parseSha256Sums(sumsResponse.body)
        val expectedSha256 = checksums[selected.dmgAsset.name]
            ?: checksums[selected.dmgAsset.name.lowercase()]
        selected.toCandidate(expectedSha256, releaseHistory)
    }

    internal data class SelectedGitHubRelease(
        val release: GitHubReleaseDto,
        val version: NuvioRoidVersion,
        val dmgAsset: GitHubAssetDto,
        val sumsAsset: GitHubAssetDto,
    ) {
        fun toCandidate(
            expectedSha256: String?,
            releaseHistory: List<ReleaseNoteEntry> = emptyList(),
        ): NuvioRoidUpdateCandidate {
            val tag = release.tagName.orEmpty()
            return NuvioRoidUpdateCandidate(
                version = version,
                versionDisplay = version.format(),
                tag = tag,
                title = release.name?.takeIf { it.isNotBlank() } ?: "NuvioRoid ${version.format()}",
                notes = release.body.orEmpty(),
                releaseUrl = release.htmlUrl,
                assetName = dmgAsset.name,
                assetUrl = dmgAsset.browserDownloadUrl,
                assetSizeBytes = dmgAsset.size,
                expectedSha256 = expectedSha256,
                feedKind = UpdateFeedKind.PUBLIC_GITHUB,
                releaseHistory = releaseHistory,
            )
        }
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        private val sha256LinePattern = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""")

        internal fun collectGitHubReleaseNotes(responseBody: String): List<ReleaseNoteEntry> {
            val releases = json.decodeFromString<List<GitHubReleaseDto>>(responseBody)
            return releases
                .asSequence()
                .filterNot(GitHubReleaseDto::draft)
                .mapNotNull { release ->
                    val tag = release.tagName?.trim().orEmpty()
                    if (!tag.startsWith(NuvioRoidVersion.TAG_PREFIX)) return@mapNotNull null
                    val version = NuvioRoidVersion.parse(tag) ?: return@mapNotNull null
                    ReleaseNoteEntry(
                        version = version,
                        versionDisplay = version.format(),
                        tag = tag,
                        title = release.name?.takeIf { it.isNotBlank() } ?: "NuvioRoid ${version.format()}",
                        notes = release.body.orEmpty(),
                        releaseUrl = release.htmlUrl,
                    )
                }
                .distinctBy { it.version }
                .sortedByDescending { it.version }
                .toList()
        }

        internal fun selectNewestGitHubRelease(
            responseBody: String,
            hostArch: String,
        ): SelectedGitHubRelease? {
            val releases = json.decodeFromString<List<GitHubReleaseDto>>(responseBody)
            return releases
                .asSequence()
                .filterNot(GitHubReleaseDto::draft)
                .mapNotNull { release ->
                    val tag = release.tagName?.trim().orEmpty()
                    if (!tag.startsWith(NuvioRoidVersion.TAG_PREFIX)) return@mapNotNull null
                    val version = NuvioRoidVersion.parse(tag) ?: return@mapNotNull null
                    val dmgAsset = selectDmgAssetForArch(release.assets, hostArch) ?: return@mapNotNull null
                    val sumsAsset = selectSha256SumsAsset(release.assets) ?: return@mapNotNull null
                    SelectedGitHubRelease(
                        release = release,
                        version = version,
                        dmgAsset = dmgAsset,
                        sumsAsset = sumsAsset,
                    )
                }
                .sortedByDescending { it.version }
                .firstOrNull()
        }

        internal fun selectDmgAssetForArch(
            assets: List<GitHubAssetDto>,
            hostArch: String,
        ): GitHubAssetDto? {
            val dmgs = assets.filter { it.name.endsWith(".dmg", ignoreCase = true) }
            if (dmgs.isEmpty()) return null
            val normalizedArch = normalizeMacArch(hostArch)
            val compatible = dmgs.filter { asset ->
                val lower = asset.name.lowercase()
                val isArm = lower.contains("arm64") || lower.contains("aarch64")
                val isX64 = lower.contains("x86_64") || lower.contains("x64") || lower.contains("amd64")
                when (normalizedArch) {
                    "arm64" -> !isX64 || isArm || lower.contains("universal")
                    "x86_64" -> !isArm || isX64 || lower.contains("universal")
                    else -> true
                }
            }
            if (compatible.isEmpty()) return null
            val forkCompatible = compatible.filter { it.name.contains("nuvioroid", ignoreCase = true) }
            val candidates = forkCompatible.ifEmpty { compatible }
            val preferredFragments = when (normalizedArch) {
                "arm64" -> listOf("arm64", "aarch64")
                "x86_64" -> listOf("x86_64", "x64", "amd64")
                else -> emptyList()
            }
            for (fragment in preferredFragments) {
                candidates.firstOrNull { it.name.contains(fragment, ignoreCase = true) }?.let { return it }
            }
            return candidates.firstOrNull { asset ->
                asset.name.contains("universal", ignoreCase = true) || asset.name.contains("all", ignoreCase = true)
            } ?: candidates.first()
        }

        internal fun selectSha256SumsAsset(assets: List<GitHubAssetDto>): GitHubAssetDto? =
            assets.firstOrNull { it.name.equals("SHA256SUMS", ignoreCase = true) }
                ?: assets.firstOrNull { it.name.equals("SHA256SUMS.txt", ignoreCase = true) }
                ?: assets.firstOrNull { it.name.startsWith("SHA256SUMS", ignoreCase = true) }

        internal fun parseSha256Sums(content: String): Map<String, String> {
            val map = linkedMapOf<String, String>()
            content.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val match = sha256LinePattern.matchEntire(line) ?: return@forEach
                val digest = match.groupValues[1].lowercase()
                val rawName = match.groupValues[2].trim()
                val baseName = rawName.substringAfterLast('/').substringAfterLast('\\')
                if (baseName.isNotEmpty()) {
                    map[baseName] = digest
                    map[baseName.lowercase()] = digest
                }
                if (rawName.isNotEmpty()) {
                    map[rawName] = digest
                }
            }
            return map
        }

        private fun isHttpsUrl(url: String): Boolean =
            url.trim().startsWith("https://", ignoreCase = true)

        private fun normalizeMacArch(arch: String): String {
            val lower = arch.trim().lowercase()
            return when {
                lower == "aarch64" || lower == "arm64" -> "arm64"
                lower == "x86_64" || lower == "x64" || lower == "amd64" -> "x86_64"
                else -> lower
            }
        }
    }
}

@Serializable
internal data class PrivateManifestReleaseDto(
    val version: String,
    val build: Int? = null,
    val title: String? = null,
    val notes: String? = null,
    val releaseNotes: String? = null,
)

@Serializable
internal data class PrivateUpdateManifestDto(
    val version: String,
    val build: Int? = null,
    val path: String? = null,
    val url: String? = null,
    val dmg: String? = null,
    val app: String? = null,
    val sha256: String? = null,
    val title: String? = null,
    val notes: String? = null,
    val releaseNotes: String? = null,
    val sizeBytes: Long? = null,
    val releases: List<PrivateManifestReleaseDto> = emptyList(),
)

internal class PrivateManifestUpdateFeed(
    private val feedLocation: String,
    private val fetcher: FeedTextFetcher = DefaultFeedTextFetcher,
) : UpdateFeed {
    override val info: UpdateFeedInfo = UpdateFeedInfo(
        kind = UpdateFeedKind.PRIVATE_MANIFEST,
        location = feedLocation,
    )

    override suspend fun fetchLatest(): Result<NuvioRoidUpdateCandidate?> = runCatching {
        val rawText = if (isHttpLocation(feedLocation)) {
            require(isHttpsLocation(feedLocation)) { getString(Res.string.updates_secure_feed_required) }
            val response = fetcher.fetchHttp(
                url = feedLocation,
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to "NuvioRoid",
                ),
            ).getOrThrow()
            if (response.status == 404) return@runCatching null
            if (response.status !in 200..299) {
                error("Private update feed returned HTTP ${response.status}")
            }
            response.body
        } else {
            fetcher.readLocal(feedLocation).getOrThrow()
        }
        parseManifest(rawText, feedLocation)
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        internal fun parseManifest(
            rawJson: String,
            feedLocation: String,
        ): NuvioRoidUpdateCandidate {
            require(!isHttpLocation(feedLocation) || isHttpsLocation(feedLocation)) { "https-required" }
            val dto = json.decodeFromString<PrivateUpdateManifestDto>(rawJson)
            val version = NuvioRoidVersion.parse(dto.version, explicitBuild = dto.build)
                ?: error("Private update manifest has an invalid version: ${dto.version}")
            val rawArtifactLocation = listOf(dto.url, dto.path, dto.dmg, dto.app)
                .firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
                ?: error("Private update manifest is missing a path or URL.")
            if (hasParentTraversalSegment(rawArtifactLocation)) {
                error("Private update manifest has an invalid artifact path.")
            }
            val resolvedLocation = resolveRelativeLocation(rawArtifactLocation, feedLocation)
            if (isHttpLocation(feedLocation)) {
                if (!isHttpLocation(resolvedLocation)) {
                    error("Remote update manifest cannot reference a local file path.")
                }
                if (isHttpsLocation(feedLocation) && !isHttpsLocation(resolvedLocation)) {
                    error("HTTPS update manifest cannot downgrade the download to plaintext HTTP.")
                }
            }
            val rawAssetName = resolvedLocation
                .substringBefore('?')
                .substringBefore('#')
                .trimEnd('/', '\\')
                .substringAfterLast('/')
                .substringAfterLast('\\')
            if (rawAssetName == "." || rawAssetName == "..") {
                error("Private update manifest has an invalid artifact file name.")
            }
            val assetName = rawAssetName.ifBlank { "NuvioRoid-${version.format()}.dmg" }
            val normalizedSha256 = dto.sha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val targetTag = "${NuvioRoidVersion.TAG_PREFIX}${version.format()}"
            val targetTitle = dto.title?.takeIf { it.isNotBlank() } ?: "NuvioRoid ${version.format()}"
            val targetNotes = dto.notes ?: dto.releaseNotes.orEmpty()
            val targetEntry = ReleaseNoteEntry(
                version = version,
                versionDisplay = version.format(),
                tag = targetTag,
                title = targetTitle,
                notes = targetNotes,
                releaseUrl = null,
            )
            val historyEntries = buildList {
                add(targetEntry)
                dto.releases.forEach { releaseDto ->
                    val parsedVersion = NuvioRoidVersion.parse(releaseDto.version, explicitBuild = releaseDto.build)
                        ?: return@forEach
                    add(
                        ReleaseNoteEntry(
                            version = parsedVersion,
                            versionDisplay = parsedVersion.format(),
                            tag = "${NuvioRoidVersion.TAG_PREFIX}${parsedVersion.format()}",
                            title = releaseDto.title?.takeIf { it.isNotBlank() }
                                ?: "NuvioRoid ${parsedVersion.format()}",
                            notes = releaseDto.notes ?: releaseDto.releaseNotes.orEmpty(),
                            releaseUrl = null,
                        ),
                    )
                }
            }
                .distinctBy { it.version }
                .sortedByDescending { it.version }
            return NuvioRoidUpdateCandidate(
                version = version,
                versionDisplay = version.format(),
                tag = targetTag,
                title = targetTitle,
                notes = targetNotes,
                releaseUrl = null,
                assetName = assetName,
                assetUrl = resolvedLocation,
                assetSizeBytes = dto.sizeBytes,
                expectedSha256 = normalizedSha256,
                feedKind = UpdateFeedKind.PRIVATE_MANIFEST,
                releaseHistory = historyEntries,
            )
        }

        internal fun isHttpLocation(location: String): Boolean =
            location.startsWith("http://", ignoreCase = true) ||
                location.startsWith("https://", ignoreCase = true)

        internal fun isHttpsLocation(location: String): Boolean =
            location.startsWith("https://", ignoreCase = true)

        private fun hasParentTraversalSegment(location: String): Boolean {
            val pathPart = location.substringBefore('?').substringBefore('#')
            return pathPart.split('/', '\\').any { it == ".." }
        }

        internal fun resolveRelativeLocation(artifactLocation: String, feedLocation: String): String {
            if (
                artifactLocation.startsWith("http://", ignoreCase = true) ||
                artifactLocation.startsWith("https://", ignoreCase = true) ||
                artifactLocation.startsWith("file://", ignoreCase = true) ||
                artifactLocation.startsWith("/") ||
                artifactLocation.startsWith("~/")
            ) {
                return artifactLocation
            }
            val cleanedArtifact = artifactLocation.removePrefix("./")
            return if (isHttpLocation(feedLocation) || feedLocation.startsWith("file://", ignoreCase = true)) {
                val baseDir = feedLocation.substringBefore('?').substringBeforeLast('/')
                "$baseDir/$cleanedArtifact"
            } else {
                val separator = if ('\\' in feedLocation && '/' !in feedLocation) '\\' else '/'
                val parent = feedLocation.substringBeforeLast(separator, missingDelimiterValue = "")
                if (parent.isEmpty()) cleanedArtifact else "$parent$separator$cleanedArtifact"
            }
        }
    }
}
