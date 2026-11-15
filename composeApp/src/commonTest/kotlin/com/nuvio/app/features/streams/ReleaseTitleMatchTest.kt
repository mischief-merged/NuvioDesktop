package com.nuvio.app.features.streams

import com.nuvio.app.features.streams.TitleVerdict.Different
import com.nuvio.app.features.streams.TitleVerdict.Same
import com.nuvio.app.features.streams.TitleVerdict.Unclear
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseTitleMatchTest {
    private fun release(name: String?) = StreamItem(
        name = "Torz 1080p",
        addonName = "A",
        addonId = "addon:A",
        behaviorHints = StreamBehaviorHints(filename = name),
    )

    private fun verdicts(names: List<String?>, vararg titles: String) =
        ReleaseTitleMatch.verdicts(names.map(::release), titles.toList())

    @Test
    fun `a release is the same title, another title or unclear`() {
        assertEquals(
            listOf(Different, Same, Same),
            verdicts(
                listOf("Totally.Different.Show.S01E01.1080p.mkv", "The.Wire.S01E01.720p.mkv", "The Wire S01 E01.mkv"),
                "The Wire",
            ),
        )
    }

    @Test
    fun `a release with no name or a partly matching one is unclear`() {
        assertEquals(
            listOf(Unclear, Unclear, Same),
            verdicts(listOf(null, "Bad.Something.Else.S01E01", "Breaking.Bad.S01E01"), "Breaking Bad"),
        )
    }

    @Test
    fun `an unknown or very short title judges nothing`() {
        assertEquals(listOf(Unclear, Unclear), verdicts(listOf("Aaa.Bbb.1080p", "It.2017.1080p")))
        for (short in listOf("It", "Her", "Up")) {
            assertEquals(listOf(Unclear, Unclear), verdicts(listOf("Aaa.Bbb.1080p", "$short.2017.1080p"), short), short)
        }
    }

    @Test
    fun `a release that matches any known title is the same title`() {
        assertEquals(
            listOf(Same, Same, Different),
            verdicts(
                listOf("Spirited.Away.2001.720p", "Sen.to.Chihiro.no.Kamikakushi.2001.2160p", "Unrelated.Film.2001.2160p"),
                "Spirited Away",
                "Sen to Chihiro no Kamikakushi",
            ),
        )
    }

    @Test
    fun `punctuation accents and run together words do not count against a release`() {
        for ((title, name) in listOf(
            "Spider-Man" to "SpiderMan.2021.1080p.mkv",
            "Amélie" to "Amelie.2001.BluRay.mkv",
            "Season of the Witch" to "Season.of.the.Witch.2011.1080p.mkv",
        )) {
            assertEquals(listOf(Different, Same), verdicts(listOf("Unrelated Film 1080p", name), title), title)
        }
    }

    @Test
    fun `numbers that open a release name are not read as the title end`() {
        assertEquals(listOf(Same), verdicts(listOf("Blade.Runner.2049.2017.2160p.mkv"), "Blade Runner 2049"))
        assertEquals(listOf(Unclear), verdicts(listOf("1917.2019.2160p.mkv"), "1917"))
    }

    @Test
    fun `the folder line of a description names the release when there is no file name`() {
        val other = release(null).copy(description = "📁 Unrelated Show S01E01\n1080p")
        val same = release(null).copy(description = "📁 The Wire S01E01\n1080p")
        assertEquals(listOf(Different, Same), ReleaseTitleMatch.verdicts(listOf(other, same), listOf("The Wire")))
    }

    @Test
    fun `a very long release name is judged quickly`() {
        val long = "Some.Words.".repeat(900)
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        ReleaseTitleMatch.verdicts(listOf(release(long)), listOf("The Wire"))
        assertEquals(true, started.elapsedNow() < kotlin.time.Duration.parse("2s"))
    }
}
