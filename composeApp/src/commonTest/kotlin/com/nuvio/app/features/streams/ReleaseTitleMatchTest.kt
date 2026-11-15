package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseTitleMatchTest {
    private fun release(name: String?) = StreamItem(
        name = "Torz 1080p",
        addonName = "A",
        addonId = "addon:A",
        behaviorHints = StreamBehaviorHints(filename = name),
    )

    private fun flags(names: List<String?>, title: String?) =
        ReleaseTitleMatch.differentTitle(names.map(::release), title)

    @Test
    fun `a release of another title is flagged when another release is the wanted title`() {
        assertEquals(
            listOf(true, false, false),
            flags(
                listOf("Totally.Different.Show.S01E01.1080p.mkv", "The.Wire.S01E01.720p.mkv", "The Wire S01 E01.mkv"),
                "The Wire",
            ),
        )
    }

    @Test
    fun `nothing is flagged when no release is the wanted title`() {
        assertEquals(
            listOf(false, false),
            flags(listOf("Совсем другое S01E01.mkv", "Other.Words.S01E01.1080p"), "The Wire"),
        )
    }

    @Test
    fun `a release with no name or a partly matching one is not flagged`() {
        assertEquals(
            listOf(false, false, false),
            flags(listOf(null, "Wire.Something.Else.S01E01", "The.Wire.S01E01"), "The Wire"),
        )
    }

    @Test
    fun `an unknown or very short wanted title flags nothing`() {
        assertEquals(listOf(false, false), flags(listOf("Aaa.Bbb.1080p", "It.2017.1080p"), null))
        assertEquals(listOf(false, false), flags(listOf("Aaa.Bbb.1080p", "It.2017.1080p"), "It"))
    }

    @Test
    fun `punctuation accents and run together words do not count against a release`() {
        for ((title, name) in listOf(
            "Spider-Man" to "SpiderMan.2021.1080p.mkv",
            "Amélie" to "Amelie.2001.BluRay.mkv",
            "Season of the Witch" to "Season.of.the.Witch.2011.1080p.mkv",
        )) {
            assertEquals(listOf(true, false), flags(listOf("Unrelated Film 1080p", name), title))
        }
    }

    @Test
    fun `the folder line of a description names the release when there is no file name`() {
        val other = release(null).copy(description = "📁 Unrelated Show S01E01\n1080p")
        val same = release(null).copy(description = "📁 The Wire S01E01\n1080p")
        assertEquals(listOf(true, false), ReleaseTitleMatch.differentTitle(listOf(other, same), "The Wire"))
    }

    @Test
    fun `equal releases are judged separately`() {
        val one = release("Unrelated.Film.1080p")
        assertEquals(listOf(true, true, false), ReleaseTitleMatch.differentTitle(listOf(one, one, release("The.Wire.S01E01")), "The Wire"))
    }
}
