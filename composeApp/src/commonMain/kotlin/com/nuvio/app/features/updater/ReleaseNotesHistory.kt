package com.nuvio.app.features.updater

internal object LocalReleaseNotesHistory {
    val entries: List<ReleaseNoteEntry> = listOf(
        ReleaseNoteEntry(
            version = NuvioRoidVersion(0, 1, 36, 2),
            versionDisplay = "0.1.36.2",
            tag = "nuvioroid-0.1.36.2",
            title = "NuvioRoid 0.1.36.2",
            notes = """
                ## New
                - Built-in macOS updates from NuvioRoid releases with file verification, daily automatic checks, and a quiet update banner.
                - Support for custom private update feeds alongside the public GitHub feed.
                - Plain-English release notes in the update banner, an Update details sheet, a one-time What's new sheet after updating, and full offline release notes in Settings.
                - Custom download folders with safe moves across drives and recovery if a move is interrupted.
                - Saved subtitles, episode details, artwork, and skip times beside downloaded videos for offline viewing.
                - Automatic fallback to the next source when playback fails to start, stalls, or hits a provider placeholder clip.
                - Hotspot and Low Data Mode awareness so large downloads wait until you are back on an unmetered connection unless you choose Download anyway.

                ## Improved
                - Source ranking matches episode markers, years, and audio languages more accurately.
                - Deleting or cancelling downloads moves files to the Trash instead of removing them permanently.
                - Settings shows NuvioRoid version, active update channel, and last check time under System.

                ## Fixed
                - Uncached debrid placeholder clips no longer replace real videos or block playback of short sources.
                - Sleeps, seeks, and network changes no longer trigger false playback failure alerts.
                - Signing out or wiping app data keeps your downloads folder and move journals intact.

                ## Known issues
                - Builds are not signed with an Apple developer certificate yet; if macOS blocks opening a new version, open NuvioRoid from Applications once and choose Open Anyway in System Settings > Privacy & Security.
            """.trimIndent(),
        ),
        ReleaseNoteEntry(
            version = NuvioRoidVersion(0, 1, 36, 1),
            versionDisplay = "0.1.36.1",
            tag = "nuvioroid-1.1.36.1",
            title = "NuvioRoid 1.1.36.1",
            notes = """
                ## New
                - Separate NuvioRoid app bundle, icon, and data folder so it stays independent from upstream Nuvio Desktop.
                - Dedicated show and episode pages with neighbour navigation, playability indicators, and direct links from downloads and Continue Watching.
                - Offline watch queue view in Settings > Tracking.
                - Confirmation prompt before quitting while downloads are still in progress.

                ## Improved
                - Clearer plain-English labels across downloads, sources, and language settings, including explicit speed limit units.
                - Empty profile picker shows a tile and guidance to add your first profile.

                ## Fixed
                - Deleting an auto-downloaded episode no longer re-downloads it automatically.
                - Episode focus scrolling and player Back navigation stay consistent when returning to a season list.

                ## Known issues
                - In-app updates were disabled in this initial build; update to 0.1.36.2 or later manually from GitHub Releases.
            """.trimIndent(),
        ),
    )

    fun entriesForWhatsNew(
        currentVersion: NuvioRoidVersion,
        lastSeenVersion: NuvioRoidVersion?,
        allEntries: List<ReleaseNoteEntry> = entries,
    ): List<ReleaseNoteEntry> {
        if (lastSeenVersion != null && lastSeenVersion < currentVersion) {
            val range = ReleaseNotesParser.selectVersionRange(
                releases = allEntries,
                installedVersion = lastSeenVersion,
                targetVersion = currentVersion,
            )
            if (range.isNotEmpty()) return range
        }
        val currentMatch = allEntries.filter { it.version == currentVersion }
        if (currentMatch.isNotEmpty()) return currentMatch
        return allEntries.sortedByDescending { it.version }.take(1)
    }
}
