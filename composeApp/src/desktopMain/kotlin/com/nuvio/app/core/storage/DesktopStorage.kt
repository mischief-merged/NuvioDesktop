package com.nuvio.app.core.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.Locale
import java.util.Properties
import kotlin.io.path.exists

internal object DesktopStorage {
    private val json = Json { ignoreUnknownKeys = true }
    private val stores = mutableMapOf<String, Store>()

    val rootDir: Path by lazy {
        resolveAppDataDir().also { Files.createDirectories(it) }
    }

    val cacheDir: Path by lazy {
        resolveCacheDir().also { Files.createDirectories(it) }
    }

    fun store(name: String): Store = synchronized(stores) {
        stores.getOrPut(name) { Store(rootDir.resolve("$name.properties")) }
    }

    fun wipe() {
        synchronized(stores) {
            stores.values.forEach(Store::clearInMemory)
            stores.clear()
        }
        if (!rootDir.exists()) return
        Files.walk(rootDir).use { stream ->
            stream
                .sorted(Comparator.reverseOrder())
                .filter { it != rootDir }
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    private fun resolveAppDataDir(): Path {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        return when {
            osName.contains("mac") -> userHome.resolve("Library/Application Support/Nuvio")
            osName.contains("win") -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                (appData?.let(Paths::get) ?: userHome.resolve("AppData/Roaming")).resolve("Nuvio")
            }
            else -> {
                val xdgConfig = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                (xdgConfig?.let(Paths::get) ?: userHome.resolve(".config")).resolve("nuvio")
            }
        }
    }

    private fun resolveCacheDir(): Path {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        return when {
            osName.contains("mac") -> userHome.resolve("Library/Caches/Nuvio")
            osName.contains("win") -> {
                val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                (localAppData?.let(Paths::get) ?: userHome.resolve("AppData/Local")).resolve("Nuvio/Cache")
            }
            else -> {
                val xdgCache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
                (xdgCache?.let(Paths::get) ?: userHome.resolve(".cache")).resolve("nuvio")
            }
        }
    }

    internal class Store(
        private val file: Path,
        private val write: (Properties, OutputStream) -> Unit = { properties, output ->
            properties.store(output, "Nuvio desktop preferences")
        },
    ) {
        private val lock = Any()
        private val properties = Properties()
        private var loaded = false
        private var fileIsGood = false
        private val temporaryFile = file.resolveSibling("${file.fileName}.tmp")
        private val previousFile = file.resolveSibling("${file.fileName}.bak")

        fun contains(key: String): Boolean = synchronized(lock) {
            ensureLoaded()
            properties.containsKey(key)
        }

        fun getString(key: String): String? = synchronized(lock) {
            ensureLoaded()
            properties.getProperty(key)
        }

        /** The value the previous copy of the store holds, for a value of the store that cannot be used. */
        fun getPreviousString(key: String): String? = synchronized(lock) {
            Properties().takeIf { read(previousFile, it) }?.getProperty(key)
        }

        fun putString(key: String, value: String?) = synchronized(lock) {
            ensureLoaded()
            val changed = if (value == null) {
                properties.remove(key) != null
            } else {
                properties.setProperty(key, value) != value
            }
            if (changed) persist()
        }

        /** Every key the store holds. */
        fun keys(): Set<String> = synchronized(lock) {
            ensureLoaded()
            properties.stringPropertyNames().toSet()
        }

        /** Flushes the saved file and its folder to disk. For the few writes that must survive a power cut. */
        fun sync(): Boolean = synchronized(lock) {
            ensureLoaded()
            runCatching {
                java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
                runCatching { java.nio.channels.FileChannel.open(file.parent, java.nio.file.StandardOpenOption.READ).use { it.force(true) } }
            }.isSuccess
        }

        fun getBoolean(key: String): Boolean? =
            getString(key)?.toBooleanStrictOrNull()

        fun putBoolean(key: String, value: Boolean) {
            putString(key, value.toString())
        }

        fun getInt(key: String): Int? =
            getString(key)?.toIntOrNull()

        fun putInt(key: String, value: Int) {
            putString(key, value.toString())
        }

        fun getFloat(key: String): Float? =
            getString(key)?.toFloatOrNull()

        fun putFloat(key: String, value: Float) {
            putString(key, value.toString())
        }

        fun getStringSet(key: String): Set<String>? =
            getString(key)?.let { payload ->
                runCatching { json.decodeFromString<List<String>>(payload).toSet() }.getOrNull()
            }

        fun putStringSet(key: String, values: Set<String>) {
            putString(key, json.encodeToString(values.toList()))
        }

        fun remove(key: String) = synchronized(lock) {
            ensureLoaded()
            if (properties.remove(key) != null) persist()
        }

        fun removeAll(keys: Iterable<String>) = synchronized(lock) {
            ensureLoaded()
            var changed = false
            keys.forEach { key ->
                if (properties.remove(key) != null) changed = true
            }
            if (changed) persist()
        }

        fun clearInMemory() = synchronized(lock) {
            properties.clear()
            loaded = false
        }

        private fun ensureLoaded() {
            if (loaded) return
            loaded = true
            properties.clear()
            if (!file.exists()) return
            fileIsGood = read(file, properties)
            if (!fileIsGood) {
                properties.clear()
                read(previousFile, properties)
            }
        }

        private fun read(source: Path, into: Properties): Boolean =
            runCatching {
                if (!Files.isRegularFile(source) || Files.size(source) == 0L) return false
                Files.newInputStream(source).use(into::load)
                true
            }.getOrDefault(false)

        /**
         * The file is never written in place: the new content goes to a file beside it, which then
         * takes its place in one step, so a write that is cut short leaves the file as it was. The
         * content the file had before stays beside it as the previous copy.
         */
        private fun persist() {
            Files.createDirectories(file.parent)
            Files.newOutputStream(temporaryFile).use { output ->
                write(properties, output)
                output.flush()
            }
            if (fileIsGood) keepPreviousCopy()
            try {
                Files.move(temporaryFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING)
            }
            fileIsGood = true
        }

        private fun keepPreviousCopy() {
            runCatching {
                Files.deleteIfExists(previousFile)
                try {
                    Files.createLink(previousFile, file)
                } catch (_: Exception) {
                    Files.copy(file, previousFile, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
