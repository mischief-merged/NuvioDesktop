package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.AddonSubtitle
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Saving subtitles beside a download, with fake servers and temporary files. */
class DesktopSubtitlesSaveTest {
    private lateinit var folder: File
    private var now = 1_700_000_000_000L
    private var online = true
    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"
    private val bodies = mutableMapOf<String, String>()
    private val broken = mutableSetOf<String>()
    private val fetched = mutableListOf<String>()
    private val realFetch = DownloadSubtitles.fetchText
    private val realAddon = DownloadSubtitles.loadAddon
    private val realGuard = DownloadCompanionGuard.stillWanted
    private var addonAnswer: List<AddonSubtitle> = emptyList()

    @BeforeTest
    fun setUp() {
        folder = Files.createTempDirectory("nuvio-subs").toFile()
        DownloadSubtitles.fetchText = { url, _ ->
            fetched += url
            if (url in broken) throw IOException("no route")
            bodies[url] ?: throw IllegalStateException("HTTP 404")
        }
        DownloadSubtitles.loadAddon = { addonAnswer }
        DownloadCompanionGuard.stillWanted = { true }
    }

    @AfterTest
    fun tearDown() {
        DownloadSubtitles.fetchText = realFetch
        DownloadSubtitles.loadAddon = realAddon
        DownloadCompanionGuard.stillWanted = realGuard
        folder.deleteRecursively()
    }

    private fun video(name: String = "a.mkv") = File(folder, name).also { it.writeText("v") }.toURI().toString()

    private fun item(vararg urls: String, id: String = "d1") = DownloadItem(
        id = id, contentType = "movie", parentMetaId = "tt1", parentMetaType = "movie", videoId = "tt1", title = "Film",
        streamTitle = "s", providerName = "p", sourceUrl = "https://example.com/a.mkv", fileName = "a.mkv",
        sourceSubtitles = urls.map { StreamSubtitle(url = it, language = "en", name = "English") },
        status = DownloadStatus.Completed, createdAtEpochMs = 1, updatedAtEpochMs = 2,
    )

    private fun companions() = DownloadCompanions(
        listOf(SubtitlesCompanion { now }), { online }, { now }, CoroutineScope(Dispatchers.Unconfined), {}, Dispatchers.Default,
    )

    @Test
    fun subtitles_from_the_source_are_saved_beside_the_file_and_counted() = runBlocking {
        val uri = video()
        bodies["https://s/en.srt"] = srt
        bodies["https://s/fr.srt"] = srt

        val result = DownloadSubtitles.prepare(item("https://s/en.srt", "https://s/fr.srt"), uri)

        assertEquals(2, result.saved)
        assertEquals(2, DownloadSubtitles.savedCount(uri))
        assertEquals(2, DownloadSubtitles.localSubtitles(uri).size)
    }

    @Test
    fun an_addon_subtitle_is_saved_too() = runBlocking {
        val uri = video()
        bodies["https://a/x.srt"] = srt
        addonAnswer = listOf(AddonSubtitle(id = "1", url = "https://a/x.srt", language = "en", display = "English (Addon)", addonName = "Addon"))

        val item = item().copy(subtitleRequests = listOf(com.nuvio.app.features.player.SubtitleAddonRequest("https://a/req", "a", "Addon")))
        assertEquals(1, DownloadSubtitles.prepare(item, uri).saved)
    }

    @Test
    fun a_start_with_no_connection_is_made_up_at_completion_and_keeps_what_was_saved() = runBlocking {
        val uri = video()
        val item = item("https://s/en.srt", "https://s/fr.srt")
        bodies["https://s/fr.srt"] = srt
        broken += "https://s/en.srt"

        val first = DownloadSubtitles.prepare(item, uri)
        assertEquals(1, first.saved)
        assertEquals(1, first.unreachable)
        // Not stamped: a look that met an unreachable server is repeated.
        assertTrue(DownloadSubtitles.isDue(item, uri, now) || DownloadSubtitles.savedCount(uri) == 1)

        broken.clear()
        bodies["https://s/en.srt"] = srt
        val second = DownloadSubtitles.prepare(item, uri, recheck = true, nowEpochMs = DownloadsClock.nowEpochMs() + DownloadSubtitles.RETRY_COOLDOWN_MS + 5_000)
        assertEquals(2, second.saved)
        assertEquals(1, second.newlySaved)
    }

    @Test
    fun a_failed_or_empty_lookup_never_removes_saved_subtitles() = runBlocking {
        val uri = video()
        val item = item("https://s/en.srt")
        bodies["https://s/en.srt"] = srt
        DownloadSubtitles.prepare(item, uri)

        bodies.clear()
        broken += "https://s/en.srt"
        val again = DownloadSubtitles.prepare(item, uri, recheck = true)
        val none = DownloadSubtitles.prepare(item("https://s/other.srt"), uri, recheck = true)

        assertEquals(1, again.saved)
        assertEquals(1, none.saved)
        assertEquals(1, DownloadSubtitles.savedCount(uri))
    }

    @Test
    fun a_file_that_is_not_a_subtitle_is_not_saved() = runBlocking {
        val uri = video()
        bodies["https://s/bad.srt"] = "<html>not found</html>"

        assertEquals(0, DownloadSubtitles.prepare(item("https://s/bad.srt"), uri).saved)
        assertEquals(0, DownloadSubtitles.savedCount(uri))
    }

    @Test
    fun a_look_that_found_nothing_is_repeated_only_after_the_age_limit() = runBlocking {
        val uri = video()
        val item = item("https://s/en.srt")
        DownloadSubtitles.prepare(item, uri) // 404: answered, nothing
        val stamp = DownloadsClock.nowEpochMs()

        assertFalse(DownloadSubtitles.isDue(item, uri, stamp))
        assertFalse(DownloadSubtitles.isDue(item, uri, stamp + DownloadSubtitles.RECHECK_EMPTY_AGE_MS - 1_000))
        assertTrue(DownloadSubtitles.isDue(item, uri, stamp + DownloadSubtitles.RECHECK_EMPTY_AGE_MS + 1_000))
    }

    @Test
    fun a_download_that_names_no_subtitle_source_is_never_due() {
        assertFalse(DownloadSubtitles.isDue(item(), video(), now))
    }

    @Test
    fun a_download_with_saved_subtitles_is_not_due_and_one_with_a_lost_file_is() = runBlocking {
        val uri = video()
        val item = item("https://s/en.srt")
        bodies["https://s/en.srt"] = srt
        DownloadSubtitles.prepare(item, uri)
        assertFalse(DownloadSubtitles.isDue(item, uri, now))

        File(folder, "a.mkv.subtitles").listFiles().orEmpty().filter { it.name.endsWith(".srt") }.forEach { it.delete() }
        assertTrue(DownloadSubtitles.isDue(item, uri, now))
    }

    @Test
    fun the_shared_background_pass_fills_in_subtitles_for_old_downloads_and_skips_unavailable_ones() = runBlocking {
        val here = video("a.mkv")
        bodies["https://s/en.srt"] = srt
        val items = listOf(item("https://s/en.srt"), item("https://s/en.srt", id = "d2"))

        val summary = companions().backfill(items) { if (it.id == "d1") here else null }

        assertEquals(1, summary.looked)
        assertEquals(1, summary.saved)
        assertEquals(1, summary.unavailable)
        assertEquals(1, DownloadSubtitles.savedCount(here))
    }

    @Test
    fun the_pass_does_nothing_offline_and_nothing_is_fetched() = runBlocking {
        online = false
        val here = video()
        companions().backfill(listOf(item("https://s/en.srt"))) { here }

        assertEquals(emptyList(), fetched)
        assertEquals(0, DownloadSubtitles.savedCount(here))
    }

    @Test
    fun a_download_finished_while_offline_at_the_start_gets_subtitles_when_it_completes_online() = runBlocking {
        val uri = video()
        bodies["https://s/en.srt"] = srt

        companions().saveAfterDownload(item("https://s/en.srt"), uri).join()

        assertEquals(1, DownloadSubtitles.savedCount(uri))
    }

    @Test
    fun nothing_is_saved_for_a_download_that_was_deleted_meanwhile() = runBlocking {
        val uri = video()
        bodies["https://s/en.srt"] = srt
        DownloadCompanionGuard.stillWanted = { false }

        companions().ensure(item("https://s/en.srt"), uri)

        assertFalse(File(folder, "a.mkv.subtitles").exists())
    }

    @Test
    fun subtitles_are_saved_in_the_folder_that_travels_with_the_video() = runBlocking {
        val uri = video()
        bodies["https://s/en.srt"] = srt
        DownloadSubtitles.prepare(item("https://s/en.srt"), uri)

        assertTrue(File(folder, "a.mkv.subtitles/manifest.json").isFile)
        assertEquals(setOf("a.mkv", "a.mkv.subtitles"), folder.list().orEmpty().toSet())
    }

    // ---- one saved subtitle must not hide the failed ones ----

    @Test
    fun a_failed_track_is_recorded_even_when_another_saved_and_is_retried_after_the_cooldown() = runBlocking {
        val uri = video()
        val item = item("https://s/en.srt", "https://s/fr.srt")
        bodies["https://s/en.srt"] = srt
        broken += "https://s/fr.srt"
        val start = DownloadsClock.nowEpochMs()

        val first = DownloadSubtitles.prepare(item, uri)
        assertEquals(1, first.saved)
        assertEquals(1, first.unreachable)
        // The manifest says what failed and when; "complete" is not claimed.
        val manifest = File(folder, "a.mkv.subtitles/manifest.json").readText()
        assertTrue("https://s/fr.srt" in manifest && "\"failed\"" in manifest)
        assertTrue("\"complete\":false" in manifest.replace(" ", ""))

        // Not due during the cooldown, due after it.
        assertFalse(DownloadSubtitles.isDue(item, uri, start + DownloadSubtitles.RETRY_COOLDOWN_MS - 5_000))
        assertTrue(DownloadSubtitles.isDue(item, uri, start + DownloadSubtitles.RETRY_COOLDOWN_MS + 5_000))

        // The retry asks for the failed track only and keeps what was saved.
        fetched.clear()
        broken.clear()
        bodies["https://s/fr.srt"] = srt
        val retry = DownloadSubtitles.prepare(item, uri, recheck = true, nowEpochMs = start + DownloadSubtitles.RETRY_COOLDOWN_MS + 5_000)
        assertEquals(listOf("https://s/fr.srt"), fetched)
        assertEquals(2, retry.saved)
        assertEquals(1, retry.newlySaved)
        assertFalse(DownloadSubtitles.isDue(item, uri, start + 10 * DownloadSubtitles.RETRY_COOLDOWN_MS))
        assertTrue("\"complete\":true" in File(folder, "a.mkv.subtitles/manifest.json").readText().replace(" ", ""))
    }

    @Test
    fun a_track_still_in_its_cooldown_is_not_asked_for_again() = runBlocking {
        val uri = video()
        val item = item("https://s/en.srt", "https://s/fr.srt")
        bodies["https://s/en.srt"] = srt
        broken += "https://s/fr.srt"
        val start = DownloadsClock.nowEpochMs()
        DownloadSubtitles.prepare(item, uri)

        fetched.clear()
        DownloadSubtitles.prepare(item, uri, recheck = true, nowEpochMs = start + 1_000)

        assertEquals(emptyList(), fetched)
    }

    @Test
    fun the_cooldown_grows_with_each_failure_and_is_capped() = runBlocking {
        val uri = video()
        val item = item("https://s/fr.srt")
        broken += "https://s/fr.srt"
        var at = DownloadsClock.nowEpochMs()
        var last = 0L
        repeat(6) {
            DownloadSubtitles.prepare(item, uri, recheck = true, nowEpochMs = at)
            val cooldown = DownloadSubtitles.cooldownAfter(attempts = it + 1)
            assertTrue(cooldown >= last)
            assertTrue(cooldown <= DownloadSubtitles.RECHECK_EMPTY_AGE_MS)
            last = cooldown
            at += cooldown + 1
        }
        assertEquals(DownloadSubtitles.RECHECK_EMPTY_AGE_MS, DownloadSubtitles.cooldownAfter(attempts = 50))
    }

    @Test
    fun a_failed_retry_never_removes_a_saved_track_and_the_manifest_stays_readable_by_older_code() = runBlocking {
        val uri = video()
        val item = item("https://s/en.srt", "https://s/fr.srt")
        bodies["https://s/en.srt"] = srt
        broken += "https://s/fr.srt"
        DownloadSubtitles.prepare(item, uri)
        DownloadSubtitles.prepare(item, uri, recheck = true, nowEpochMs = DownloadsClock.nowEpochMs() + DownloadSubtitles.RETRY_COOLDOWN_MS * 2)

        assertEquals(1, DownloadSubtitles.savedCount(uri))
        // The fields older code reads are all there; the new one is an extra key it ignores.
        val older = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .parseToJsonElement(File(folder, "a.mkv.subtitles/manifest.json").readText()) as kotlinx.serialization.json.JsonObject
        assertTrue(older.containsKey("tracks") && older.containsKey("complete"))
    }

    @Test
    fun a_subtitle_the_server_answered_for_with_junk_is_not_retried_as_a_failure() = runBlocking {
        val uri = video()
        val item = item("https://s/en.srt", "https://s/bad.srt")
        bodies["https://s/en.srt"] = srt
        bodies["https://s/bad.srt"] = "<html>no</html>"

        DownloadSubtitles.prepare(item, uri)

        assertFalse("\"failed\":[{" in File(folder, "a.mkv.subtitles/manifest.json").readText().replace(" ", ""))
        assertFalse(DownloadSubtitles.isDue(item, uri, DownloadsClock.nowEpochMs() + DownloadSubtitles.RETRY_COOLDOWN_MS * 10))
    }
}
