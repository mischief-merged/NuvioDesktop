package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.downloads.BatchQuality
import com.nuvio.app.features.downloads.SeasonDownloadDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamAutoPlaySelectorTest {

    @Test
    fun `bingeGroup-first selects matching stream before first stream mode`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "1080p",
            bingeGroup = "other-group",
        )
        val preferred = stream(
            addonName = "AddonB",
            url = "https://example.com/preferred.m3u8",
            name = "720p",
            bingeGroup = "same-group",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, preferred),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true,
        )

        assertEquals(preferred, selected)
    }

    @Test
    fun `falls back to normal mode when no bingeGroup match exists`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "First",
            bingeGroup = "group-a",
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            name = "Second",
            bingeGroup = "group-b",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "missing-group",
            preferBingeGroupInSelection = true,
        )

        assertEquals(first, selected)
    }

    @Test
    fun `bingeGroup-first respects source and addon plugin filters`() {
        val filteredOutAddonMatch = stream(
            addonName = "AddonFilteredOut",
            url = "https://example.com/addon-match.m3u8",
            bingeGroup = "same-group",
        )
        val allowedPluginMatch = stream(
            addonName = "PluginAllowed",
            url = "https://example.com/plugin-match.m3u8",
            bingeGroup = "same-group",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(filteredOutAddonMatch, allowedPluginMatch),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
            installedAddonNames = setOf("AddonFilteredOut"),
            selectedAddons = emptySet(),
            selectedPlugins = setOf("PluginAllowed"),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true,
        )

        assertEquals(allowedPluginMatch, selected)
    }

    @Test
    fun `blank preferredBingeGroup behaves as disabled`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            bingeGroup = "group-a",
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            bingeGroup = "group-b",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "   ",
            preferBingeGroupInSelection = true,
        )

        assertEquals(first, selected)
    }

    @Test
    fun `manual mode remains manual even with matching bingeGroup`() {
        val matched = stream(
            addonName = "AddonA",
            url = "https://example.com/match.m3u8",
            bingeGroup = "same-group",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(matched),
            mode = StreamAutoPlayMode.MANUAL,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true,
        )

        assertNull(selected)
    }

    @Test
    fun `first stream mode can select direct debrid candidate without resolved URL`() {
        val directDebrid = stream(
            addonName = "Torbox Instant",
            url = null,
            name = "TB Instant",
            directDebrid = true,
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(directDebrid),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(directDebrid, selected)
    }

    @Test
    fun `first stream mode does not auto select external url browser link`() {
        val external = stream(
            addonName = "External Addon",
            externalUrl = "https://example.com/watch",
            name = "Watch on site",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(external),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("External Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertNull(selected)
    }

    @Test
    fun `timeout evaluation keeps pending regex debrid candidate open`() {
        val pending = stream(
            addonName = "Torrentio",
            name = "The Show 1080p",
            infoHash = "hash-pending",
            cacheState = StreamDebridCacheState.CHECKING,
        )

        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(pending),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "1080p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Torrentio"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            debridEnabled = true,
            activeResolverProviderId = "premiumize",
        )

        assertNull(evaluation.stream)
        assertTrue(evaluation.hasPendingDebridCandidate)
    }

    @Test
    fun `timeout evaluation still selects direct link while debrid candidate is pending`() {
        val pending = stream(
            addonName = "Torrentio",
            name = "The Show 1080p",
            infoHash = "hash-pending",
            cacheState = StreamDebridCacheState.CHECKING,
        )
        val direct = stream(
            addonName = "Direct Addon",
            url = "https://example.com/video.mp4",
            name = "The Show 1080p",
        )

        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(pending, direct),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "1080p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Torrentio", "Direct Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            debridEnabled = true,
            activeResolverProviderId = "premiumize",
        )

        assertEquals(direct, evaluation.stream)
        assertFalse(evaluation.hasPendingDebridCandidate)
    }

    @Test
    fun `direct debrid candidate must match active resolver`() {
        val torbox = stream(
            addonName = "Comet",
            name = "TB Instant",
            directDebrid = true,
            directDebridService = "torbox",
        )

        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(torbox),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Comet"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            debridEnabled = true,
            activeResolverProviderId = "premiumize",
        )

        assertNull(evaluation.stream)
        assertFalse(evaluation.hasPendingDebridCandidate)
    }

    @Test
    fun `nested exclusions do not crash regex selection`() {
        val stream = stream(
            addonName = "Direct Addon",
            url = "https://example.com/video.mp4",
            name = "Movie 1080p WEB",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(stream),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "^(?!.*\\b(CAM(?!RIP)|TS)\\b).*1080p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Direct Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(stream, selected)
    }

    private fun stream(
        addonName: String,
        url: String? = null,
        externalUrl: String? = null,
        name: String? = null,
        bingeGroup: String? = null,
        directDebrid: Boolean = false,
        directDebridService: String = "torbox",
        infoHash: String? = null,
        cacheState: StreamDebridCacheState? = null,
        videoSize: Long? = null,
    ): StreamItem = StreamItem(
        name = name,
        url = url,
        externalUrl = externalUrl,
        infoHash = infoHash,
        addonName = addonName,
        addonId = "addon:$addonName",
        clientResolve = if (directDebrid) {
            StreamClientResolve(
                type = "debrid",
                service = directDebridService,
                isCached = true,
                infoHash = "hash",
            )
        } else {
            null
        },
        debridCacheStatus = cacheState?.let { state ->
            StreamDebridCacheStatus(
                providerId = "premiumize",
                providerName = "Premiumize",
                state = state,
            )
        },
        behaviorHints = StreamBehaviorHints(
            bingeGroup = bingeGroup,
            videoSize = videoSize,
        ),
    )

    private val preferredRuntimeMinutes = 50

    private fun sizeFor(megabitsPerSecond: Double): Long =
        (megabitsPerSecond * 1_000_000 * preferredRuntimeMinutes * 60 / 8).toLong()

    private fun preferred(
        streams: List<StreamItem>,
        preferences: SeasonDownloadDefaults = SeasonDownloadDefaults(),
        mode: StreamAutoPlayMode = StreamAutoPlayMode.PREFERRED,
        source: StreamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES,
        installed: Set<String> = streams.map { it.addonName }.toSet(),
        selectedAddons: Set<String> = emptySet(),
        selectedPlugins: Set<String> = emptySet(),
        runtimeMinutes: Int? = preferredRuntimeMinutes,
    ): StreamAutoPlayEvaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
        streams = streams,
        mode = mode,
        regexPattern = "",
        source = source,
        installedAddonNames = installed,
        selectedAddons = selectedAddons,
        selectedPlugins = selectedPlugins,
        preferences = preferences,
        runtimeMinutes = runtimeMinutes,
    )

    @Test
    fun `preferred mode picks the saved quality even when other sources are listed first`() {
        val uhd = stream("A", url = "https://example.com/uhd.mkv", name = "2160p")
        val hd = stream("A", url = "https://example.com/hd.mkv", name = "1080p")
        val sd = stream("A", url = "https://example.com/sd.mkv", name = "720p")

        val evaluation = preferred(listOf(uhd, sd, hd), SeasonDownloadDefaults(quality = BatchQuality.P1080))

        assertEquals(hd, evaluation.stream)
        assertEquals(listOf(hd), evaluation.readyStreams)
    }

    @Test
    fun `preferred mode with no saved quality picks the highest resolution, not the first listed`() {
        val sd = stream("A", url = "https://example.com/sd.mkv", name = "720p")
        val uhd = stream("A", url = "https://example.com/uhd.mkv", name = "2160p")

        assertEquals(uhd, preferred(listOf(sd, uhd)).stream)
    }

    @Test
    fun `preferred mode respects the bitrate cap`() {
        val tooBig = stream("A", url = "https://example.com/big.mkv", name = "1080p", videoSize = sizeFor(30.0))
        val fits = stream("A", url = "https://example.com/fits.mkv", name = "1080p", videoSize = sizeFor(8.0))
        val preferences = SeasonDownloadDefaults(maxBitrateMbps = 10)

        assertEquals(fits, preferred(listOf(tooBig, fits), preferences).stream)
        assertNull(preferred(listOf(tooBig), preferences).stream)
    }

    @Test
    fun `preferred mode cannot check a bitrate cap without a runtime and keeps the source`() {
        val big = stream("A", url = "https://example.com/big.mkv", name = "1080p", videoSize = sizeFor(30.0))

        val evaluation = preferred(listOf(big), SeasonDownloadDefaults(maxBitrateMbps = 10), runtimeMinutes = null)

        assertEquals(big, evaluation.stream)
    }

    @Test
    fun `preferred mode prefers the audio language, accepts untagged and drops other languages`() {
        val french = stream("A", url = "https://example.com/fr.mkv", name = "1080p FRENCH")
        val untagged = stream("A", url = "https://example.com/untagged.mkv", name = "1080p")
        val english = stream("A", url = "https://example.com/en.mkv", name = "1080p ENGLISH")
        val preferences = SeasonDownloadDefaults(audioLanguage = DebridStreamLanguage.EN)

        val evaluation = preferred(listOf(french, untagged, english), preferences)

        assertEquals(listOf(english, untagged), evaluation.readyStreams)
        assertEquals(english, evaluation.stream)
        assertEquals(untagged, preferred(listOf(french, untagged), preferences).stream)
        assertNull(preferred(listOf(french), preferences).stream)
    }

    @Test
    fun `preferred mode honours the source scope`() {
        val plugin = stream("Plugin", url = "https://example.com/plugin.mkv", name = "2160p")
        val addon = stream("Addon", url = "https://example.com/addon.mkv", name = "1080p")

        val evaluation = preferred(
            streams = listOf(plugin, addon),
            source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
            installed = setOf("Addon"),
        )

        assertEquals(addon, evaluation.stream)
    }

    @Test
    fun `preferred mode honours the selected addons and plugins`() {
        val addonA = stream("AddonA", url = "https://example.com/a.mkv", name = "2160p")
        val addonB = stream("AddonB", url = "https://example.com/b.mkv", name = "1080p")
        val pluginX = stream("PluginX", url = "https://example.com/x.mkv", name = "2160p")
        val installed = setOf("AddonA", "AddonB")

        assertEquals(
            addonB,
            preferred(listOf(addonA, addonB), installed = installed, selectedAddons = setOf("AddonB")).stream,
        )
        assertNull(
            preferred(
                listOf(pluginX),
                installed = installed,
                selectedPlugins = setOf("PluginY"),
            ).stream,
        )
    }

    @Test
    fun `preferred mode falls back to the picker when nothing fits`() {
        val hd = stream("A", url = "https://example.com/hd.mkv", name = "1080p")

        val evaluation = preferred(listOf(hd), SeasonDownloadDefaults(quality = BatchQuality.P2160))

        assertNull(evaluation.stream)
        assertTrue(evaluation.readyStreams.isEmpty())
        assertFalse(evaluation.hasPendingDebridCandidate)
    }

    @Test
    fun `preferred mode never plays a source that is not auto playable`() {
        val browserLink = stream("A", externalUrl = "https://example.com/watch", name = "1080p")

        assertNull(preferred(listOf(browserLink)).stream)
    }

    @Test
    fun `preferred mode still prefers the binge group stream`() {
        val best = stream("A", url = "https://example.com/best.mkv", name = "2160p", bingeGroup = "other")
        val grouped = stream("A", url = "https://example.com/grouped.mkv", name = "720p", bingeGroup = "same")

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(best, grouped),
            mode = StreamAutoPlayMode.PREFERRED,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("A"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same",
            preferBingeGroupInSelection = true,
        )

        assertEquals(grouped, selected)
    }

    @Test
    fun `saved preferences do not change the existing modes`() {
        val uhd = stream("A", url = "https://example.com/uhd.mkv", name = "2160p")
        val hd = stream("A", url = "https://example.com/hd.mkv", name = "1080p")
        val preferences = SeasonDownloadDefaults(quality = BatchQuality.P1080)

        assertEquals(uhd, preferred(listOf(uhd, hd), preferences, mode = StreamAutoPlayMode.FIRST_STREAM).stream)
        assertNull(preferred(listOf(uhd, hd), preferences, mode = StreamAutoPlayMode.MANUAL).stream)
    }
}
