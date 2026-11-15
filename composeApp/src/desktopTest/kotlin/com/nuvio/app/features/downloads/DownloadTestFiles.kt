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
