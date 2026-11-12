package com.nuvio.app.features.downloads

import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The time each ffprobe or ffmpeg run may take. */
private const val ToolTimeoutSeconds = 20L

/** The time all runs for one file may take together. Past it the file is not looked at. */
private const val TotalBudgetSeconds = 120L

/** Never more than this much of what a tool writes is kept in memory. */
private const val ProbeOutputLimit = 8 * 1024

/** A frame that does not decode is tried again this long before its position. */
private const val RetryEarlierSeconds = 5.0

/** A run took too long, so what it was to tell is not known. */
private class ToolTimedOut : Exception()

internal actual fun sampleDownloadedFrames(localFileUri: String): FrameSamples? = guarded(localFileUri) { file, ffprobe, ffmpeg ->
    sampleFrames(file, ffprobe, ffmpeg)
}

internal actual fun sampleConfirmingDownloadedFrames(localFileUri: String, durationMs: Long): FrameSamples? =
    guarded(localFileUri) { file, _, ffmpeg ->
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TotalBudgetSeconds)
        val seconds = durationMs / 1000.0
        val samples = ConfirmingFramePositions.map { fraction ->
            frameAt(file, ffmpeg, seconds * fraction, FrameWindow.Middle, deadline) ?: return@guarded null
        }
        FrameSamples(durationMs, samples)
    }

private inline fun guarded(localFileUri: String, look: (File, File, File) -> FrameSamples?): FrameSamples? = try {
    val file = if (localFileUri.startsWith("file:")) File(URI(localFileUri)) else File(localFileUri)
    val ffprobe = findFfmpegTool("ffprobe")
    val ffmpeg = findFfmpegTool("ffmpeg")
    if (!file.isFile || ffprobe == null || ffmpeg == null) null else look(file.absoluteFile, ffprobe, ffmpeg)
} catch (error: Throwable) {
    // Nothing here may fail the download: a look that went wrong is no look.
    null
}

/**
 * Where [name] (ffmpeg or ffprobe) is, or null. Looked for in the folder of the system property
 * nuvio.ffmpeg.dir, that of the environment variable NUVIO_FFMPEG_DIR, the usual install folders and
 * then those of PATH.
 */
internal fun findFfmpegTool(
    name: String,
    property: String? = System.getProperty("nuvio.ffmpeg.dir"),
    environment: String? = System.getenv("NUVIO_FFMPEG_DIR"),
    path: String? = System.getenv("PATH"),
): File? {
    val fileName = if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) "$name.exe" else name
    val folders = listOf(property, environment, "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin") +
        path.orEmpty().split(File.pathSeparatorChar)
    return folders
        .filter { !it.isNullOrBlank() }
        .map { File(it!!, fileName) }
        .firstOrNull { it.isFile && it.canExecute() }
}

private fun sampleFrames(file: File, ffprobe: File, ffmpeg: File): FrameSamples? {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TotalBudgetSeconds)
    val probe = try {
        runTool(
            listOf(
                ffprobe.path, "-v", "error", "-protocol_whitelist", "file",
                "-show_entries", "format=duration:format_tags=title", "-of", "default=nw=1",
                file.path,
            ),
            deadline,
        )
    } catch (error: ToolTimedOut) {
        return null
    }
    if (probe == null || probe.exitCode != 0) return null
    val lines = probe.head.toString(Charsets.UTF_8).lines()
    val durationSeconds = lines.firstNotNullOfOrNull { line ->
        line.takeIf { it.startsWith("duration=") }?.removePrefix("duration=")?.trim()?.toDoubleOrNull()
    }?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val title = lines.firstNotNullOfOrNull { line ->
        line.takeIf { it.startsWith("TAG:title=", ignoreCase = true) }?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }
    }

    val samples = FrameSamplePositions.map { (fraction, window) ->
        frameAt(file, ffmpeg, durationSeconds * fraction, window, deadline) ?: return null
    }
    return FrameSamples(durationMs = (durationSeconds * 1000.0).toLong(), samples = samples, containerTitle = title)
}

/**
 * The sample at [seconds]. When no frame decodes there, one is tried 5 seconds earlier. Null when a
 * tool timed out or could not be started: that is not a frame that failed to decode, so the whole
 * look is given up.
 */
private fun frameAt(file: File, ffmpeg: File, seconds: Double, window: FrameWindow, deadline: Long): FrameSample? {
    val (pixels, ok) = decodeAt(file, ffmpeg, seconds, deadline)
    if (!ok) return null
    val retried = if (pixels == null && seconds > RetryEarlierSeconds) decodeAt(file, ffmpeg, seconds - RetryEarlierSeconds, deadline) else null
    if (retried != null && !retried.second) return null
    return FrameSample((seconds * 1000.0).toLong(), window, pixels ?: retried?.first)
}

/** The picture at [seconds], or null for none, and whether the tool ran to its end. */
private fun decodeAt(file: File, ffmpeg: File, seconds: Double, deadline: Long): Pair<ByteArray?, Boolean> {
    val output = try {
        runTool(
            listOf(
                ffmpeg.path, "-v", "error", "-nostdin", "-protocol_whitelist", "file",
                "-ss", String.format(Locale.ROOT, "%.3f", seconds), "-i", file.path,
                "-frames:v", "1", "-vf", "scale=$FrameSide:$FrameSide,format=gray", "-f", "rawvideo", "-",
            ),
            deadline,
        )
    } catch (error: ToolTimedOut) {
        return null to false
    } ?: return null to false
    return output.head.takeIf { output.totalBytes == FramePixelCount.toLong() && it.size == FramePixelCount } to true
}

private class ToolOutput(val exitCode: Int, val head: ByteArray, val totalBytes: Long)

/**
 * Runs [command] without a shell and returns what it ended with, or null when it could not be
 * started. Its output is read all the way, so it cannot block, and kept only up to
 * [ProbeOutputLimit] bytes. Its error output is discarded. Throws [ToolTimedOut] when it did not
 * end in time; it has been killed then.
 */
private fun runTool(command: List<String>, deadlineNanos: Long): ToolOutput? {
    val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
    if (remainingMs <= 0L) throw ToolTimedOut()
    val process = try {
        ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    } catch (error: Exception) {
        return null
    }
    var head = ByteArray(0)
    var total = 0L
    val reader = Thread {
        runCatching { process.inputStream.drain { chunk, length ->
            if (head.size < ProbeOutputLimit) head += chunk.copyOf(minOf(length, ProbeOutputLimit - head.size))
            total += length
        } }
    }.also { it.isDaemon = true; it.start() }
    runCatching { process.outputStream.close() }
    try {
        val limit = minOf(TimeUnit.SECONDS.toMillis(ToolTimeoutSeconds), remainingMs)
        if (!process.waitFor(limit, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            reader.join(2000)
            throw ToolTimedOut()
        }
        reader.join(5000)
        // The output is complete only once the reader has seen its end.
        if (reader.isAlive) {
            process.destroyForcibly()
            throw ToolTimedOut()
        }
        return ToolOutput(process.exitValue(), head, total)
    } catch (error: InterruptedException) {
        process.destroyForcibly()
        Thread.currentThread().interrupt()
        throw ToolTimedOut()
    }
}

private inline fun InputStream.drain(onChunk: (ByteArray, Int) -> Unit) {
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer)
        if (read < 0) return
        onChunk(buffer, read)
    }
}
