package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceholderSourceRulesTest {

    private fun signal(url: String, vararg names: String?) = assessSourceLink(url, names.toList())?.signal

    private fun decisive(url: String, vararg names: String?) = assessSourceLink(url, names.toList())?.decisive

    @Test
    fun aLinkWithATitleAndABodyDescribesItself() {
        assertEquals(PlaceholderSignal.SelfDescribingLink, signal("https://a.example/seg.ts?title=Nope&body=Later"))
        assertEquals(PlaceholderSignal.SelfDescribingLink, signal("https://a.example/v.mp4?title=Nope&message=Later"))
        assertEquals(PlaceholderSignal.SelfDescribingLink, signal("https://a.example/v.mp4?error=1"))
    }

    @Test
    fun anErrorOrStatusFolderOrFileDescribesItself() {
        assertEquals(PlaceholderSignal.SelfDescribingLink, signal("https://a.example/status.mp4"))
        assertEquals(PlaceholderSignal.SelfDescribingLink, signal("https://a.example/error/gone.webm"))
        assertEquals(PlaceholderSignal.SelfDescribingLink, signal("https://a.example/x/failed.mkv"))
    }

    @Test
    fun aRateLimitNoticeIsRecognisedInBothEncodings() {
        assertEquals(PlaceholderSignal.RateLimitNotice, signal("https://a.example/x.mp4?title=Too+Many+Requests&body=slow"))
        assertEquals(PlaceholderSignal.RateLimitNotice, signal("https://a.example/x.mp4?title=Too%20Many%20Requests"))
        assertEquals(PlaceholderSignal.RateLimitNotice, signal("https://a.example/x?body=Debrid+provider+is+rate-limiting"))
    }

    @Test
    fun ordinaryMediaLinksMatchNothing() {
        assertNull(signal("https://cdn.example/movies/title.mp4?name=Feature&quality=1080p"))
        assertNull(signal("https://cdn.example/x.mp4?title=Feature"))
        assertNull(signal("https://cdn.example/dl/9f2c?download=1"))
        assertNull(signal("ftp://cdn.example/error.mp4"))
        assertNull(signal("magnet:?xt=urn:btih:abc&error=1"))
        assertNull(signal(""))
    }

    @Test
    fun aHostOrATitleThatContainsTheWordMatchesNothing() {
        assertNull(signal("https://error.example.com/video.mp4"))
        assertNull(signal("https://status.example.com/films/film.mkv"))
        assertNull(signal("https://cdn.example/Error.2019.1080p.WEB-DL.mkv"))
        assertNull(signal("https://cdn.example/Status.Update.2018.mkv"))
        assertNull(signal("https://cdn.example/Failed.To.Launch.2006.mkv"))
    }

    @Test
    fun knownStaticClipsAreRecognisedInsideAStaticFolder() {
        assertEquals(PlaceholderSignal.StaticStatusClip, signal("https://p.example/static/downloading.mp4"))
        assertEquals(PlaceholderSignal.StaticStatusClip, signal("https://p.example/STATIC/Download_Failed.mp4?x=1"))
        assertEquals(PlaceholderSignal.StaticStatusClip, signal("https://p.example/v0/store/_/static/429.mp4"))
    }

    @Test
    fun theSameNamesElsewhereAreNotClips() {
        assertNull(signal("https://cdn.example/movies/downloading.mp4"))
        assertNull(signal("https://cdn.example/movies/200.mp4"))
        assertNull(signal("https://cdn.example/static/Downloading.The.Dream.2020.mp4"))
        assertNull(signal("https://cdn.example/static/film.mp4"))
    }

    @Test
    fun theExactClipFileNameCountsButATitleContainingTheWordDoesNot() {
        assertEquals(PlaceholderSignal.StatusClipFileName, signal("https://a.example/dl/abc", null, "downloading.mp4"))
        assertTrue(isStatusClipFileName(" Download_Failed.MP4 "))
        assertTrue(isStatusClipFileName("folder/downloading.mp4"))
        assertFalse(isStatusClipFileName("Downloading.Love.2021.1080p.WEB-DL.mkv"))
        assertFalse(isStatusClipFileName("download_failed.mkv"))
        assertFalse(isStatusClipFileName("200.mp4"))
        assertFalse(isStatusClipFileName(null))
        assertNull(signal("https://a.example/dl/abc", "Downloading.Love.2021.1080p.WEB-DL.mkv", null))
    }

    private val mib = 1024L * 1024L
    private val gib = 1024L * mib

    @Test
    fun aTinyFileAgainstALargeDeclaredSizeIsTooSmall() {
        assertTrue(servedFileTooSmallForDeclaredSize(3 * mib, 20 * gib))
        assertTrue(servedFileTooSmallForDeclaredSize(21 * 1024L, 59 * gib))
    }

    @Test
    fun anUnknownSizeClaimsNothing() {
        assertFalse(servedFileTooSmallForDeclaredSize(null, 20 * gib))
        assertFalse(servedFileTooSmallForDeclaredSize(3 * mib, null))
        assertFalse(servedFileTooSmallForDeclaredSize(0L, 20 * gib))
        assertFalse(servedFileTooSmallForDeclaredSize(3 * mib, 0L))
    }

    @Test
    fun aLongFileWithASmallDeclaredSizeIsNotTooSmall() {
        assertFalse(servedFileTooSmallForDeclaredSize(3 * gib, 10 * mib))
        assertFalse(servedFileTooSmallForDeclaredSize(30 * mib, 40 * mib))
    }

    @Test
    fun theSizeRuleNeedsBothTheCeilingAndTheFactor() {
        assertFalse(servedFileTooSmallForDeclaredSize(50 * mib, 400 * mib))
        assertFalse(servedFileTooSmallForDeclaredSize(64 * mib, 40 * gib))
        assertTrue(servedFileTooSmallForDeclaredSize(64 * mib - 1, 40 * gib))
        assertTrue(servedFileTooSmallForDeclaredSize(10 * mib, 101 * mib))
        assertFalse(servedFileTooSmallForDeclaredSize(10 * mib, 100 * mib))
    }

    @Test
    fun aTwoMinuteClipCannotSatisfyALaterResume() {
        assertTrue(isWaitClipForResume(120_000L, 293_502L))
        assertTrue(isWaitClipForResume(121_200L, 600_000L))
    }

    @Test
    fun aTwoMinuteVideoResumedWithinItselfOrOfAnotherLengthIsNotTheWaitClip() {
        assertFalse(isWaitClipForResume(120_000L, 60_000L))
        assertFalse(isWaitClipForResume(120_000L, 0L))
        assertFalse(isWaitClipForResume(300_000L, 600_000L))
        assertFalse(isWaitClipForResume(130_000L, 600_000L))
    }

    @Test
    fun theDurationRuleKeepsItsThresholds() {
        assertTrue(looksLikePlaceholder(120_000L, 100))
        assertTrue(looksLikePlaceholder(120_000L, 24))
        assertFalse(looksLikePlaceholder(8 * 60_000L, 150))
        assertFalse(looksLikePlaceholder(120_000L, null))
    }

    // A link alone never decides, except a known clip in a static folder

    @Test
    fun onlyAKnownClipInAStaticFolderDecidesByItself() {
        assertEquals(true, decisive("https://p.example/static/downloading.mp4"))
        assertEquals(true, decisive("https://p.example/v0/store/_/static/429.mp4"))
        assertEquals(false, decisive("https://a.example/status.mp4"))
        assertEquals(false, decisive("https://a.example/error/gone.webm"))
        assertEquals(false, decisive("https://a.example/v.mp4?error=1"))
        assertEquals(false, decisive("https://a.example/seg.ts?title=Nope&body=Later"))
        assertEquals(false, decisive("https://a.example/x.mp4?title=Too+Many+Requests"))
        assertEquals(false, decisive("https://a.example/dl/abc", "downloading.mp4"))
    }

    @Test
    fun realLookingMediaUnderStatusAndErrorFoldersIsOnlyASuspectAtMost() {
        assertEquals(false, decisive("https://cdn.example/status/feature.mkv"))
        assertEquals(false, decisive("https://cdn.example/error/Some.Movie.2020.1080p.mkv"))
        assertEquals(false, decisive("https://cdn.example/files/feature.mkv?error=0"))
        assertEquals(false, decisive("https://cdn.example/files/feature.mkv?title=Some+Movie&body=full"))
    }

    private fun confirmed(
        suspect: Boolean,
        durationMs: Long,
        runtime: Int? = null,
        resumeMs: Long = 0L,
        served: Long? = null,
        declared: Long? = null,
    ) = isPlaceholderConfirmed(suspect, durationMs, runtime, resumeMs, served, declared)

    @Test
    fun aSuspectNeedsASecondSignalToBeConfirmed() {
        // A healthy feature under /status/: long stable length, nothing else says placeholder.
        assertFalse(confirmed(suspect = true, durationMs = 2 * 60 * 60_000L, runtime = 120))
        assertFalse(confirmed(suspect = true, durationMs = 2 * 60 * 60_000L))
        assertFalse(confirmed(suspect = true, durationMs = 0L))
        // A short stable length confirms a suspect even when the running time is unknown.
        assertTrue(confirmed(suspect = true, durationMs = 120_000L))
        assertTrue(confirmed(suspect = true, durationMs = 4 * 60_000L, runtime = null))
        // The wait clip and the size rule confirm on their own terms.
        assertTrue(confirmed(suspect = true, durationMs = 120_000L, resumeMs = 600_000L))
        assertTrue(confirmed(suspect = true, durationMs = 2 * 60 * 60_000L, served = 3 * mib, declared = 20 * gib))
    }

    @Test
    fun withoutASuspicionAShortStableLengthWithAnUnknownRuntimeIsLeftAlone() {
        assertFalse(confirmed(suspect = false, durationMs = 120_000L))
        assertFalse(confirmed(suspect = false, durationMs = 4 * 60_000L))
        // The existing rules still decide alone.
        assertTrue(confirmed(suspect = false, durationMs = 120_000L, runtime = 100))
        assertTrue(confirmed(suspect = false, durationMs = 120_000L, resumeMs = 600_000L))
    }

    @Test
    fun aSuspectWithALengthAtOrAboveTheCeilingIsNotConfirmedByLengthAlone() {
        assertFalse(confirmed(suspect = true, durationMs = PLACEHOLDER_MAX_DURATION_MS))
        assertTrue(confirmed(suspect = true, durationMs = PLACEHOLDER_MAX_DURATION_MS - 1))
    }
}
