package com.nuvio.app.features.downloads

private fun int32(value: Long) = ByteArray(4) { (value shr (8 * (3 - it))).toByte() }

internal fun mp4Box(type: String, content: ByteArray): ByteArray =
    int32(8L + content.size) + type.encodeToByteArray() + content

/** A movie header box with a 32-bit duration. */
internal fun mvhdBox(timescale: Long, duration: Long): ByteArray =
    mp4Box("mvhd", ByteArray(4) + int32(0) + int32(0) + int32(timescale) + int32(duration) + ByteArray(80))

/** A small but well-formed MP4 file that states [durationSeconds] and carries [mediaBytes] of media. */
internal fun mp4File(durationSeconds: Int, mediaBytes: Int = 0, movieHeaderFirst: Boolean = true): ByteArray {
    val type = mp4Box("ftyp", "isom".encodeToByteArray() + ByteArray(12))
    val movie = mp4Box("moov", mvhdBox(timescale = 1_000, duration = durationSeconds * 1_000L))
    val media = mp4Box("mdat", ByteArray(mediaBytes))
    return if (movieHeaderFirst) type + movie + media else type + media + movie
}

/** The start of a Matroska or WebM file followed by [mediaBytes] of anything. */
internal fun matroskaFile(mediaBytes: Int = 0): ByteArray =
    byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()) + ByteArray(mediaBytes)

private fun ebml(id: Long, content: ByteArray): ByteArray {
    val idBytes = (3 downTo 0).map { (id shr (8 * it)).toByte() }.dropWhile { it.toInt() == 0 }.toByteArray()
    val size = byteArrayOf(0x01) + ByteArray(7) { (content.size.toLong() shr (8 * (6 - it))).toByte() }
    return idBytes + size + content
}

/** One track of a Matroska file. A null language leaves the element out. */
internal class MatroskaTrack(val type: Int, val language: String? = null, val languageBcp47: String? = null)

internal fun matroskaAudio(language: String?, languageBcp47: String? = null) = MatroskaTrack(2, language, languageBcp47)

internal val matroskaVideo = MatroskaTrack(1, "und")

/** A Matroska file with [tracks] before [mediaBytes] of media, or after it when [tracksFirst] is false. */
internal fun matroskaFile(tracks: List<MatroskaTrack>, mediaBytes: Int = 0, tracksFirst: Boolean = true): ByteArray {
    val entries = tracks.map { track ->
        ebml(
            0xae,
            ebml(0x83, byteArrayOf(track.type.toByte())) +
                (track.language?.let { ebml(0x22b59c, it.encodeToByteArray()) } ?: ByteArray(0)) +
                (track.languageBcp47?.let { ebml(0x22b59d, it.encodeToByteArray()) } ?: ByteArray(0)),
        )
    }
    val info = ebml(0x1549a966, ByteArray(24))
    val trackList = ebml(0x1654ae6b, entries.fold(ByteArray(0)) { all, entry -> all + entry })
    val cluster = ebml(0x1f43b675, ByteArray(mediaBytes))
    val segment = if (tracksFirst) info + trackList + cluster else info + cluster + trackList
    return ebml(0x1a45dfa3, ByteArray(20)) + ebml(0x18538067, segment)
}

private fun mp4Track(handler: String, language: String?): ByteArray {
    val packed = language?.fold(0) { value, letter -> (value shl 5) or (letter.code - 0x60) } ?: 0
    val mediaHeader = mp4Box("mdhd", ByteArray(4) + int32(0) + int32(0) + int32(1_000) + int32(0) + byteArrayOf((packed shr 8).toByte(), packed.toByte(), 0, 0))
    val handlerBox = mp4Box("hdlr", ByteArray(8) + handler.encodeToByteArray() + ByteArray(13))
    return mp4Box("trak", mp4Box("tkhd", ByteArray(84)) + mp4Box("mdia", mediaHeader + handlerBox))
}

/** An MP4 file with a video track and one audio track for each of [audioLanguages]; null states no language. */
internal fun mp4FileWithAudio(audioLanguages: List<String?>, mediaBytes: Int = 0, movieHeaderFirst: Boolean = true): ByteArray {
    val type = mp4Box("ftyp", "isom".encodeToByteArray() + ByteArray(12))
    val tracks = listOf(mp4Track("vide", "und")) + audioLanguages.map { mp4Track("soun", it) }
    val movie = mp4Box("moov", tracks.fold(mvhdBox(timescale = 1_000, duration = 60_000)) { all, track -> all + track })
    val media = mp4Box("mdat", ByteArray(mediaBytes))
    return if (movieHeaderFirst) type + movie + media else type + media + movie
}
