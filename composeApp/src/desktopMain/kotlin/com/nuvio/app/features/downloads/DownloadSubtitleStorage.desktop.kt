package com.nuvio.app.features.downloads

import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal actual class DownloadSubtitleStorage actual constructor(localVideoUri: String) {
    private val directory = File(File(URI(localVideoUri)).path + ".subtitles")

    actual fun read(fileName: String): String? =
        runCatching { file(fileName).takeIf(File::isFile)?.readText(Charsets.UTF_8) }.getOrNull()

    /**
     * Writes to a temporary file beside the target and renames it over the target, so that a crash
     * leaves the old file or the new one and never half of one. Temporary files left by a crash are
     * never read (they have another name) and are removed here.
     */
    actual fun write(fileName: String, text: String) = writeBytes(fileName, text.toByteArray(Charsets.UTF_8))

    actual fun writeBytes(fileName: String, bytes: ByteArray) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create subtitle directory" }
        val target = file(fileName)
        directory.listFiles { candidate -> candidate.name.endsWith(TemporarySuffix) }.orEmpty().forEach { it.delete() }
        val temporary = File(directory, "${target.name}$TemporarySuffix")
        try {
            temporary.writeBytes(bytes)
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    actual fun localFileUri(fileName: String): String? =
        file(fileName).takeIf(File::isFile)?.toURI()?.toString()

    actual fun remove() {
        directory.deleteRecursively()
    }

    private companion object {
        const val TemporarySuffix = ".tmp"
    }

    private fun file(fileName: String): File {
        require(fileName.isNotBlank() && File(fileName).name == fileName && fileName != "." && fileName != ".." && !fileName.endsWith(TemporarySuffix))
        return File(directory, fileName)
    }
}
