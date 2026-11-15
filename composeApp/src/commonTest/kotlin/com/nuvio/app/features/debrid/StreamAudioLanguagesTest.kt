package com.nuvio.app.features.debrid

import com.nuvio.app.features.debrid.DebridStreamLanguage.EN
import com.nuvio.app.features.debrid.DebridStreamLanguage.ES
import com.nuvio.app.features.debrid.DebridStreamLanguage.FR
import com.nuvio.app.features.debrid.DebridStreamLanguage.IT
import com.nuvio.app.features.debrid.DebridStreamLanguage.JA
import com.nuvio.app.features.debrid.DebridStreamLanguage.KO
import com.nuvio.app.features.debrid.DebridStreamLanguage.MULTI
import com.nuvio.app.features.debrid.DebridStreamLanguage.PL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamAudioLanguagesTest {
    private fun read(vararg texts: String) = StreamAudioLanguages.read(texts.toList())
    private fun audio(vararg texts: String) = read(*texts).audio

    @Test
    fun `a release that states no language reads as nothing and not foreign`() {
        val reading = read(
            "Show.S01E01.2160p.NF.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-GRP.mkv",
            "hevc | DV | HDR | Atmos | 5.1\n💾 14.4 GB",
        )
        assertEquals(emptySet(), reading.audio)
        assertFalse(reading.looksForeign)
    }

    @Test
    fun `scene language tags are read`() {
        assertEquals(setOf(IT, EN), audio("Movie.2024.1080p.WEB-DL.iTA.ENG.AC3-GRP.mkv"))
        assertEquals(setOf(FR), audio("Movie.2024.TRUEFRENCH.1080p.WEB.x264-GRP"))
        assertEquals(setOf(PL), audio("Movie.2024.PLDUB.1080p.WEB-DL.x264"))
        assertEquals(setOf(EN), audio("Movie 2024 1080p [EN]"))
        assertEquals(setOf(JA), audio("Show.S01E01.JPN.1080p"))
    }

    @Test
    fun `short codes count only in capitals so words of titles are not languages`() {
        assertEquals(emptySet(), audio("It.2017.1080p.BluRay.x264-GRP.mkv"))
        assertEquals(emptySet(), audio("La.La.Land.2016.2160p.UHD.BluRay.mkv"))
        assertEquals(emptySet(), audio("La.Casa.de.Papel.S01E01.1080p.WEB.x264"))
        assertEquals(emptySet(), audio("The.Chi.S01E01.1080p.WEB.h264"))
        assertEquals(setOf(FR), audio("Movie.2024.FR.1080p"))
    }

    @Test
    fun `sizes and audio formats are not languages`() {
        assertEquals(emptySet(), audio("💾 2.35 GB", "SIZE 2.2 GB"))
        assertEquals(emptySet(), audio("Movie.1080p.BluRay.DTS-ES.6.1.x264"))
        assertEquals(setOf(ES), audio("Movie.1080p.ES.DTS-ES"))
    }

    @Test
    fun `a language next to a subtitle word is a subtitle language and not audio`() {
        assertEquals(setOf(KO), audio("Drama.S01E01.Korean.1080p.WEB.ENG.SUB"))
        val french = read("Movie.2024.1080p.WEB-DL.x264.FRENCH.SUB-GRP")
        assertEquals(emptySet(), french.audio)
        assertFalse(french.looksForeign)
        assertEquals(emptySet(), audio("Movie.2024.1080p.ESP.Subs-GRP"))
        assertEquals(emptySet(), audio("Movie.2023.1080p.WEB-DL.ESub.mkv"))
        val multiSubs = read("Movie.2024.1080p.BluRay.Multi-Subs")
        assertEquals(emptySet(), multiSubs.audio)
        assertFalse(multiSubs.looksForeign)
    }

    @Test
    fun `a bare subtitle word marks the release as foreign`() {
        val reading = read("[SubsPlease] Frieren - 01 (1080p) [ABC123].mkv")
        assertEquals(emptySet(), reading.audio)
        assertTrue(reading.looksForeign)
        assertTrue(read("Show.S01E01.Subbed.720p").looksForeign)
    }

    @Test
    fun `a language the app does not list marks the release as foreign without naming one`() {
        for (text in listOf("Movie.2024.1080p.HUN-GRP", "Movie.2024.Russian.1080p", "Movie.2024.HebDub.720p", "🇷🇺")) {
            val reading = read(text)
            assertEquals(emptySet(), reading.audio, text)
            assertTrue(reading.looksForeign, text)
        }
    }

    @Test
    fun `camel cased and glued tags are read piece by piece`() {
        assertEquals(setOf(IT, EN), audio("Movie.2024.ITAEng.1080p"))
        assertEquals(setOf(PL), audio("Movie.2024.PLDUB.1080p"))
    }

    @Test
    fun `multi audio and dual audio read as multi`() {
        assertEquals(setOf(MULTI), audio("[Group] Frieren S01 1080p Dual Audio"))
        assertEquals(setOf(MULTI), audio("Movie.2024.MULTi.1080p.WEB"))
    }

    @Test
    fun `a bare dub is English and a dub next to a language belongs to it`() {
        assertEquals(setOf(EN), audio("Frieren S01E01 Dubbed 1080p"))
        assertEquals(setOf(EN), audio("Frieren S01E01 English Dub 1080p"))
        assertEquals(setOf(FR), audio("Frieren S01E01 FR Dub 1080p"))
    }

    @Test
    fun `flags and globes are read`() {
        assertEquals(setOf(EN, IT), audio("🇬🇧 / 🇮🇹"))
        assertEquals(setOf(MULTI), audio("🌍 Multi"))
    }

    @Test
    fun `values from a parser count in any case`() {
        assertEquals(setOf(ES), StreamAudioLanguages.read(emptyList(), listOf("es")).audio)
        assertEquals(setOf(FR), StreamAudioLanguages.read(emptyList(), listOf("French")).audio)
        assertEquals(setOf(MULTI), StreamAudioLanguages.read(emptyList(), listOf("dual audio")).audio)
        val hungarian = StreamAudioLanguages.read(emptyList(), listOf("hu"))
        assertEquals(emptySet(), hungarian.audio)
        assertTrue(hungarian.looksForeign)
    }

    @Test
    fun `a subtitle word applies to the whole list of languages it belongs to`() {
        for (text in listOf(
            "Movie.1080p.FRENCH.GERMAN.SUBS",
            "Movie.1080p.SUBS.FRENCH.GERMAN",
            "Movie 1080p Subs: French, German",
            "Movie 1080p French and German Subs",
            "Movie.1080p.FRENCH+GERMAN.SUBS",
            "Movie.1080p.FRENCH-GERMAN-ESUB",
            "Movie.1080p.FR.DE.SUB-GRP",
        )) {
            val reading = read(text)
            assertEquals(emptySet(), reading.audio, text)
            assertFalse(reading.looksForeign, text)
        }
    }

    @Test
    fun `languages away from the subtitle word stay audio`() {
        val reading = read("Movie.FRENCH.1080p.WEB.ENG.GERMAN.SUBS")
        assertEquals(setOf(FR), reading.audio)
        assertTrue(reading.possiblySubtitles)
    }

    @Test
    fun `a text with a subtitle word and audio languages not tied to it is flagged as possibly subtitles`() {
        val reading = read("Subs.1080p.French")
        assertEquals(setOf(FR), reading.audio)
        assertTrue(reading.possiblySubtitles)
        assertFalse(read("Movie.1080p.French").possiblySubtitles)
        assertFalse(read("Movie.1080p.FRENCH.SUB").possiblySubtitles)
    }

    @Test
    fun `a ten thousand character text is read quickly`() {
        val text = "Movie FRENCH GERMAN Subs Dub ".repeat(400)
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        read(text)
        assertTrue(text.length >= 10_000)
        assertTrue(started.elapsedNow() < kotlin.time.Duration.parse("2s"))
    }
}
