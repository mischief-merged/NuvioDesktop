package com.nuvio.app.core.storage

import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class DesktopStorageTest {
    @Test
    fun unchanged_operations_do_not_rewrite_the_store() {
        val directory = Files.createTempDirectory("desktop-storage-test")
        val file = directory.resolve("preferences.properties")
        try {
            val store = DesktopStorage.Store(file)
            store.putString("key", "value")
            val sentinel = FileTime.fromMillis(1_000L)
            Files.setLastModifiedTime(file, sentinel)

            store.putString("key", "value")
            store.remove("missing")
            store.removeAll(listOf("also-missing"))

            assertEquals(sentinel, Files.getLastModifiedTime(file))

            store.putString("key", "updated")

            assertNotEquals(sentinel, Files.getLastModifiedTime(file))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun inTemporaryDirectory(test: (file: java.nio.file.Path) -> Unit) {
        val directory = Files.createTempDirectory("desktop-storage-test")
        try {
            test(directory.resolve("preferences.properties"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_write_that_is_cut_short_leaves_the_store_as_it_was() = inTemporaryDirectory { file ->
        DesktopStorage.Store(file).putString("key", "value")
        val interrupted = DesktopStorage.Store(file) { _, output ->
            output.write("key=upd".toByteArray())
            output.flush()
            throw IOException("the app was quit")
        }

        assertFailsWith<IOException> { interrupted.putString("key", "updated") }

        assertEquals("value", DesktopStorage.Store(file).getString("key"))
        assertEquals("key=upd", Files.readString(file.resolveSibling("preferences.properties.tmp")))
    }

    @Test
    fun a_half_written_file_left_beside_the_store_is_not_read_and_does_not_stop_the_next_write() = inTemporaryDirectory { file ->
        DesktopStorage.Store(file).putString("key", "value")
        Files.writeString(file.resolveSibling("preferences.properties.tmp"), "key=upd")

        val store = DesktopStorage.Store(file)

        assertEquals("value", store.getString("key"))
        store.putString("key", "updated")
        assertEquals("updated", DesktopStorage.Store(file).getString("key"))
    }

    @Test
    fun a_store_that_was_left_empty_is_read_from_its_previous_copy() = inTemporaryDirectory { file ->
        val writer = DesktopStorage.Store(file)
        writer.putString("key", "value")
        writer.putString("other", "kept")
        Files.write(file, ByteArray(0))

        val store = DesktopStorage.Store(file)

        assertEquals("value", store.getString("key"))
    }

    @Test
    fun the_previous_copy_is_not_replaced_by_the_empty_store_it_was_read_in_place_of() = inTemporaryDirectory { file ->
        val writer = DesktopStorage.Store(file)
        writer.putString("key", "value")
        writer.putString("other", "kept")
        Files.write(file, ByteArray(0))

        DesktopStorage.Store(file).putString("new", "added")
        Files.write(file, ByteArray(0))

        assertEquals("value", DesktopStorage.Store(file).getString("key"))
    }

    @Test
    fun a_store_that_was_never_written_reads_as_empty() = inTemporaryDirectory { file ->
        assertNull(DesktopStorage.Store(file).getString("key"))
        assertNull(DesktopStorage.Store(file).getPreviousString("key"))
    }

    @Test
    fun the_previous_copy_holds_the_value_from_before_the_last_write() = inTemporaryDirectory { file ->
        val store = DesktopStorage.Store(file)
        store.putString("key", "first")
        store.putString("key", "second")

        assertEquals("second", store.getString("key"))
        assertEquals("first", store.getPreviousString("key"))
    }
}
