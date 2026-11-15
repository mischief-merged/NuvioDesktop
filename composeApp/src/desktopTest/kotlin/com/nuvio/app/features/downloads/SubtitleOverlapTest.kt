package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Subtitle runs that overlap (playback and a background pass, say) never undo each other. */
class SubtitleOverlapTest {
    private lateinit var folder: File
    private val realFetch = DownloadSubtitles.fetchText
    private val realAddon = DownloadSubtitles.loadAddon
    private fun srt(text: String) = "1\n00:00:01,000 --> 00:00:02,000\n$text\n"
    private val bodies = mutableMapOf<String, String>()
    private val delays = mutableMapOf<String, Long>()
    private val broken = mutableSetOf<String>()
    private val fetchCount = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    @BeforeTest
    fun setUp() {
        folder = Files.createTempDirectory("nuvio-overlap").toFile()
        DownloadSubtitles.fetchText = { url, _ ->
            fetchCount.getOrPut(url) { AtomicInteger() }.incrementAndGet()
            delay(delays[url] ?: 0L)
            if (url in broken) throw IOException("no route")
            bodies[url] ?: throw IllegalStateException("HTTP 404")
        }
        DownloadSubtitles.loadAddon = { emptyList() }
    }

    @AfterTest
    fun tearDown() {
        DownloadSubtitles.fetchText = realFetch
        DownloadSubtitles.loadAddon = realAddon
        folder.deleteRecursively()
    }

    private fun uri() = File(folder, "a.mkv").also { it.writeText("v") }.toURI().toString()
    private val dir get() = File(folder, "a.mkv.subtitles")

    private fun item(vararg urls: String) = DownloadItem(
        id = "d1", contentType = "movie", parentMetaId = "tt1", parentMetaType = "movie", videoId = "tt1", title = "Film",
        streamTitle = "s", providerName = "p", sourceUrl = "https://example.com/a.mkv", fileName = "a.mkv",
        sourceSubtitles = urls.map { StreamSubtitle(url = it, language = "lang-${it.substringAfterLast('/')}", name = it.substringAfterLast('/')) },
        status = DownloadStatus.Completed, createdAtEpochMs = 1, updatedAtEpochMs = 2,
    )

    private val foreignManifest =
        "{\"complete\":false,\"tracks\":[{\"sourceUrl\":\"https://s/foreign.srt\",\"fileName\":\"0.srt\",\"language\":\"xx\"}],\"failed\":[]}"

    /** Another run saves a track between this run's read and its write. */
    private fun withForeignTrack(uri: String): (() -> Unit) -> Boolean {
        var injected = false
        return { action ->
            if (!injected) {
                injected = true
                val storage = DownloadSubtitleStorage(uri)
                storage.write("0.srt", srt("foreign"))
                storage.write("manifest.json", foreignManifest)
            }
            action()
            true
        }
    }

    @Test
    fun a_failed_run_that_started_from_an_empty_manifest_does_not_erase_a_track_another_run_saved() = runBlocking {
        val uri = uri()
        bodies["https://s/ok.srt"] = srt("ok")
        broken += "https://s/slow-fail.srt"
        delays["https://s/slow-fail.srt"] = 400

        withTimeout(20_000) {
            val failing = async(Dispatchers.Default) { DownloadSubtitles.prepare(item("https://s/slow-fail.srt"), uri) }
            delay(100)
            val saving = async(Dispatchers.Default) { DownloadSubtitles.prepare(item("https://s/ok.srt"), uri) }
            failing.await()
            saving.await()
        }

        assertEquals(1, DownloadSubtitles.savedCount(uri))
        assertEquals(listOf("lang-ok.srt"), DownloadSubtitles.localSubtitles(uri).map { it.language })
    }

    @Test
    fun two_successful_overlapping_runs_never_write_the_same_numbered_file() = runBlocking {
        val uri = uri()
        bodies["https://s/a.srt"] = srt("Alpha")
        bodies["https://s/b.srt"] = srt("Beta")
        delays["https://s/a.srt"] = 150
        delays["https://s/b.srt"] = 150

        withTimeout(20_000) {
            val first = async(Dispatchers.Default) { DownloadSubtitles.prepare(item("https://s/a.srt"), uri, recheck = true) }
            val second = async(Dispatchers.Default) { DownloadSubtitles.prepare(item("https://s/b.srt"), uri, recheck = true) }
            first.await()
            second.await()
        }

        val tracks = DownloadSubtitles.localSubtitles(uri)
        assertEquals(2, tracks.size)
        assertEquals(2, tracks.map { it.url }.toSet().size, "two tracks point at one file")
        val byLanguage = tracks.associate { it.language to File(java.net.URI(it.url)).readText() }
        assertTrue("Alpha" in byLanguage.getValue("lang-a.srt"))
        assertTrue("Beta" in byLanguage.getValue("lang-b.srt"))
    }

    @Test
    fun only_one_run_per_video_is_in_flight_and_a_second_finds_nothing_left_to_do() = runBlocking {
        val uri = uri()
        bodies["https://s/a.srt"] = srt("Alpha")
        delays["https://s/a.srt"] = 300
        val item = item("https://s/a.srt")

        withTimeout(20_000) {
            val first = async(Dispatchers.Default) { DownloadSubtitles.prepare(item, uri, recheck = true) }
            val second = async(Dispatchers.Default) { DownloadSubtitles.prepare(item, uri, recheck = true) }
            first.await()
            second.await()
        }

        assertEquals(1, fetchCount.getValue("https://s/a.srt").get(), "the same track was fetched twice")
        assertEquals(1, DownloadSubtitles.savedCount(uri))
    }

    @Test
    fun a_write_merges_into_the_manifest_it_finds_and_never_reuses_a_file_name() = runBlocking {
        val uri = uri()
        bodies["https://s/mine.srt"] = srt("mine")

        DownloadSubtitles.prepare(item("https://s/mine.srt"), uri, guardedWrite = withForeignTrack(uri))

        val tracks = DownloadSubtitles.localSubtitles(uri)
        assertEquals(setOf("xx", "lang-mine.srt"), tracks.map { it.language }.toSet())
        val files = tracks.map { File(java.net.URI(it.url)).name }
        assertEquals(2, files.toSet().size)
        assertTrue("foreign" in File(java.net.URI(tracks.first { it.language == "xx" }.url)).readText())
    }

    @Test
    fun a_run_that_only_fails_never_writes_fewer_tracks_than_the_manifest_it_finds() = runBlocking {
        val uri = uri()
        broken += "https://s/dead.srt"

        val result = DownloadSubtitles.prepare(item("https://s/dead.srt"), uri, guardedWrite = withForeignTrack(uri))

        assertEquals(1, DownloadSubtitles.savedCount(uri))
        assertEquals(1, result.unreachable)
        // The failure is recorded beside the track another run saved.
        val manifest = File(dir, "manifest.json").readText()
        assertTrue("dead.srt" in manifest && "foreign.srt" in manifest)
    }

    @Test
    fun a_failure_record_for_a_track_another_run_saved_meanwhile_is_dropped() = runBlocking {
        val uri = uri()
        broken += "https://s/foreign.srt"

        DownloadSubtitles.prepare(item("https://s/foreign.srt"), uri, guardedWrite = withForeignTrack(uri))

        assertEquals(1, DownloadSubtitles.savedCount(uri))
        assertTrue("\"failed\":[]" in File(dir, "manifest.json").readText().replace(" ", ""))
    }
}
