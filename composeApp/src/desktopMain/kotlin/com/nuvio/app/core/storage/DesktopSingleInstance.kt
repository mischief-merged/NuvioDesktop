package com.nuvio.app.core.storage

import java.io.Closeable
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * One copy of the app per app data folder: an OS lock on `instance.lock`, taken at start and held while
 * the app runs. A second copy cannot take it and stops before touching anything.
 */
internal object DesktopSingleInstance {
    @Volatile
    private var held: Closeable? = null

    /** The lock on [folder], or null when another copy (or this one) holds it. */
    fun tryAcquire(folder: Path): Closeable? {
        val channel = try {
            Files.createDirectories(folder)
            FileChannel.open(folder.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (_: IOException) {
            return null
        }
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: IOException) {
            null
        }
        if (lock == null) {
            runCatching { channel.close() }
            return null
        }
        return Closeable {
            runCatching { lock.release() }
            runCatching { channel.close() }
        }
    }

    /** Takes the lock for the running app and keeps it until the process ends. False when another copy has it. */
    fun acquireForApp(folder: Path): Boolean {
        if (held != null) return true
        held = tryAcquire(folder) ?: return false
        return true
    }
}
