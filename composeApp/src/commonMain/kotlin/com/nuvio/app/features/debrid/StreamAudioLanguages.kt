package com.nuvio.app.features.debrid

/**
 * What a source's text says about its audio.
 *
 * [audio] holds the languages the source states as audio, among the ones [DebridStreamLanguage]
 * lists, plus [DebridStreamLanguage.MULTI] for multi-audio. A language that appears only as a
 * subtitle language is not in it. [looksForeign] is set when the text points at foreign audio
 * without naming a listed language: a subtitle-only marker ("Subbed", "SubsPlease") or a language
 * the app does not offer. A source with empty [audio] and no [looksForeign] states nothing.
 */
internal data class AudioLanguageReading(
    val audio: Set<DebridStreamLanguage>,
    val looksForeign: Boolean,
)

private typealias L = DebridStreamLanguage

internal object StreamAudioLanguages {
    fun read(texts: List<String>, parsedLanguages: List<String> = emptyList()): AudioLanguageReading {
        val audio = linkedSetOf<L>()
        var foreign = false
        for (value in parsedLanguages) {
            when (val mark = parsedMark(value)) {
                is Mark.Lang -> if (mark.language != null) audio += mark.language else foreign = true
                Mark.Multi -> audio += L.MULTI
                else -> Unit
            }
        }
        for (text in texts) {
            val marks = marksOf(text)
            marks.forEachIndexed { i, mark ->
                val before = marks.getOrNull(i - 1)
                val after = marks.getOrNull(i + 1)
                when (mark) {
                    is Mark.Lang ->
                        if (before != Mark.Subtitles && after != Mark.Subtitles) {
                            if (mark.language != null) audio += mark.language else foreign = true
                        }
                    Mark.Multi -> if (after != Mark.Subtitles) audio += L.MULTI
                    // A bare "Dub" is an English dub; next to a language it belongs to that language.
                    Mark.Dub -> if (before !is Mark.Lang && after !is Mark.Lang) audio += L.EN
                    Mark.Subtitles ->
                        if (before !is Mark.Lang && before != Mark.Multi && after !is Mark.Lang) foreign = true
                    Mark.Plain -> Unit
                }
            }
        }
        audio -= L.UNKNOWN
        return AudioLanguageReading(audio, foreign)
    }

    private sealed interface Mark {
        /** [language] is null for a language the app does not list. */
        data class Lang(val language: L?) : Mark
        object Multi : Mark
        object Dub : Mark
        object Subtitles : Mark
        object Plain : Mark
    }

    private val subtitleWords = setOf(
        "sub", "subs", "subbed", "subtitle", "subtitles", "subtitled", "hardsub", "hardsubs", "softsub", "softsubs",
    )
    private val dubWords = setOf("dub", "dubs", "dubbed", "dubbing")
    private val multiWords = setOf("multi", "dual", "multiaudio", "dualaudio", "multilang")
    private val gluedSuffixes = listOf("subbed", "dubbed", "subs", "sub", "dub")

    private val unlisted: L? = null

    /** Words that name a language in any case. */
    private val names: Map<String, L?> = buildMap {
        fun add(language: L?, vararg words: String) = words.forEach { put(it, language) }
        add(L.EN, "english", "eng")
        add(L.HI, "hindi", "hin")
        add(L.IT, "italian", "italiano", "ita")
        add(L.ES, "spanish", "espanol", "español", "castellano", "esp")
        add(L.LA, "latino", "latam", "latinoamericano")
        add(L.FR, "french", "francais", "français", "truefrench", "vff", "vfq", "vfi")
        add(L.DE, "german", "deutsch", "ger", "deu")
        add(L.PT, "portuguese", "portugues", "português", "dublado", "ptbr")
        add(L.PL, "polish", "polski", "lektor")
        add(L.CS, "czech", "cesky")
        add(L.JA, "japanese", "jpn")
        add(L.KO, "korean")
        add(L.ZH, "chinese", "mandarin", "cantonese")
        add(
            unlisted,
            "hungarian", "magyar", "hebrew", "russian", "ukrainian", "arabic", "turkish", "tamil", "telugu",
            "malayalam", "kannada", "dutch", "flemish", "swedish", "norwegian", "danish", "finnish", "greek",
            "romanian", "thai", "vietnamese", "indonesian", "bulgarian", "serbian", "croatian", "slovenian",
            "slovak", "estonian", "latvian", "lithuanian", "persian", "farsi", "urdu", "bengali", "punjabi",
            "marathi", "gujarati", "malay", "filipino", "tagalog",
        )
    }

    /** Short codes, which collide with words of titles and tags, so they count only in capitals. */
    private val capitalCodes: Map<String, L?> = buildMap {
        fun add(language: L?, vararg words: String) = words.forEach { put(it, language) }
        add(L.EN, "EN")
        add(L.ES, "ES", "SPA")
        add(L.LA, "LAT")
        add(L.FR, "FR", "VF", "FRA", "FRE")
        add(L.DE, "DE")
        add(L.PT, "PT", "POR")
        add(L.PL, "PL", "POL")
        add(L.CS, "CZ", "CZE", "CES")
        add(L.JA, "JP", "JA", "JAP")
        add(L.KO, "KR", "KO", "KOR")
        add(L.ZH, "CN", "ZH", "CHI", "ZHO", "CHS", "CHT")
        add(
            unlisted,
            "HU", "HUN", "HEB", "RU", "RUS", "UA", "UKR", "ARA", "TR", "TUR", "NL", "DUT", "NLD", "SWE", "NOR",
            "DAN", "FIN", "GRE", "ELL", "ROU", "RUM", "THA", "VIE", "IND", "TAM", "TEL",
        )
    }

    private val flagCountries: Map<String, L?> = buildMap {
        fun add(language: L?, vararg countries: String) = countries.forEach { put(it, language) }
        add(L.EN, "GB", "US", "AU", "NZ", "IE")
        add(L.HI, "IN")
        add(L.IT, "IT")
        add(L.ES, "ES")
        add(L.LA, "MX", "AR", "CO", "CL", "PE", "VE")
        add(L.FR, "FR")
        add(L.DE, "DE", "AT")
        add(L.PT, "PT", "BR")
        add(L.PL, "PL")
        add(L.CS, "CZ")
        add(L.JA, "JP")
        add(L.KO, "KR")
        add(L.ZH, "CN", "TW", "HK")
    }

    private fun parsedMark(value: String): Mark? {
        val lower = value.trim().lowercase()
        if (lower == "multi" || lower == "multi audio" || lower == "dual audio") return Mark.Multi
        L.entries.firstOrNull { it != L.MULTI && (lower == it.code || lower == it.label.lowercase()) }
            ?.let { return Mark.Lang(it) }
        // A parser's value is not a stray word of a title, so a code counts in any case.
        val key = value.trim()
        return when {
            lower in names -> Mark.Lang(names[lower])
            key.uppercase() in capitalCodes -> Mark.Lang(capitalCodes[key.uppercase()])
            else -> null
        }
    }

    /** The marks of one text, in reading order. Flags and globes are read from the emoji. */
    private fun marksOf(text: String): List<Mark> {
        val marks = mutableListOf<Mark>(Mark.Plain)
        val word = StringBuilder()
        var previousWord: String? = null
        fun flush() {
            if (word.isEmpty()) return
            val text = word.toString()
            marks += wordMarks(text, previousWord)
            previousWord = text
            word.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isLetterOrDigit()) {
                word.append(c)
                i++
                continue
            }
            flush()
            val flag = flagAt(text, i)
            when {
                flag != null -> {
                    marks += if (flag.first in flagCountries) Mark.Lang(flagCountries[flag.first]) else Mark.Lang(unlisted)
                    i += 4
                }
                isGlobe(text, i) -> {
                    marks += Mark.Multi
                    i += 2
                }
                else -> i++
            }
        }
        flush()
        return marks
    }

    private fun wordMarks(word: String, previousWord: String?): List<Mark> {
        classify(word, previousWord)?.let { return listOf(it) }
        // "HebDub", "ESub", "MultiSubs": a language or "multi" glued to a dub or subtitle word.
        val lower = word.lowercase()
        val suffix = gluedSuffixes.firstOrNull { lower.length > it.length && lower.endsWith(it) }
        if (suffix != null) {
            val prefix = word.substring(0, word.length - suffix.length)
            // Glued to a dub or subtitle word a short code is unambiguous, so it counts in any case.
            val head = when {
                prefix == "e" || prefix == "E" -> Mark.Lang(L.EN)
                prefix.uppercase() in capitalCodes -> Mark.Lang(capitalCodes[prefix.uppercase()])
                else -> classify(prefix, previousWord)
            }
            if (head != null) return listOf(head, if (suffix.startsWith("sub")) Mark.Subtitles else Mark.Dub)
        }
        // "SubsPlease", "ITAEng": a camel-cased word is read piece by piece, with only the longer
        // pieces trusted because short pieces are too often part of a name.
        val pieces = Regex("[A-Z]+(?![a-z])|[A-Z]?[a-z]+|[0-9]+").findAll(word).map { it.value }.toList()
        if (pieces.size > 1) {
            val marks = pieces.map { piece -> if (piece.length >= 3) classify(piece, null) else null }
            if (marks.any { it != null }) return marks.map { it ?: Mark.Plain }
        }
        return listOf(Mark.Plain)
    }

    private fun classify(word: String, previousWord: String?): Mark? {
        val lower = word.lowercase()
        return when {
            lower in subtitleWords -> Mark.Subtitles
            lower in dubWords -> Mark.Dub
            lower in multiWords -> Mark.Multi
            lower in names -> Mark.Lang(names[lower])
            // "DTS-ES" is an audio format, not Spanish.
            word == "ES" && previousWord.equals("DTS", ignoreCase = true) -> null
            word in capitalCodes -> Mark.Lang(capitalCodes[word])
            else -> null
        }
    }

    /** The two-letter country of a flag emoji at [index] and the flag's length in chars. */
    private fun flagAt(text: String, index: Int): Pair<String, Int>? {
        fun indicator(at: Int): Char? {
            if (text.getOrNull(at) != '\uD83C') return null
            val low = text.getOrNull(at + 1) ?: return null
            return if (low in '\uDDE6'..'\uDDFF') 'A' + (low - '\uDDE6') else null
        }
        val first = indicator(index) ?: return null
        val second = indicator(index + 2) ?: return null
        return "$first$second" to 4
    }

    /** The globes 🌍 🌎 🌏 🌐, which stand for several languages. */
    private fun isGlobe(text: String, index: Int): Boolean =
        text[index] == '\uD83C' && text.getOrNull(index + 1) in '\uDF0D'..'\uDF10'
}
