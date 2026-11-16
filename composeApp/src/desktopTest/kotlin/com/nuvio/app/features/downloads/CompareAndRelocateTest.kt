package com.nuvio.app.features.downloads

import com.nuvio.app.features.profiles.ProfileRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one way a record is re-pointed: one step, in the list of the record's own profile, confirmed on disk. */
class CompareAndRelocateTest {
    private val world = FolderWorld()
    private val backend = LocationsBackend(world)
    private val realBackend = DownloadsRepository.backend
    private val active = ProfileRepository.activeProfileId
    private val other = active + 1

    private class Lists : ProfileLists {
        val stored = mutableMapOf<Int, String>()
        val saved = mutableListOf<Int>()
        var flushes = true

        override fun load(profileId: Int): String? = stored[profileId]

        override fun saveDurably(profileId: Int, payload: String): Boolean {
            saved += profileId
            stored[profileId] = payload
            return flushes
        }
    }

    private val lists = Lists()

    @BeforeTest
    fun use_temporary_lists() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
    }

    @AfterTest
    fun restore() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        world.close()
    }

    private fun load(vararg items: DownloadItem) {
        backend.payload = storedPayload(items.toList())
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
    }

    private val drive by lazy { world.driveLocation("Archive") }
    private fun film(id: String, name: String = "$id.mp4") =
        movieItem(id, file = world.appFolder.resolve(name).writeVideo(id.hashCode())).copy(fileName = name)

    private fun relocate(profile: Int, id: String, expected: DownloadItem) = DownloadsRepository.compareAndRelocate(
        profile, id, lists,
        matches = { it.localFileUri == expected.localFileUri && it.locationId == expected.locationId },
        relocate = { it.copy(locationId = drive.id, localFileUri = drive.path.resolve(it.fileName).toUri().toString()) },
    )

    @Test
    fun a_record_of_another_profile_is_changed_in_that_profiles_list_and_never_in_the_active_one() {
        val mine = film("x", "mine.mp4")
        val theirs = film("x", "theirs.mp4")
        load(mine)
        lists.stored[other] = storedPayload(listOf(theirs))

        assertEquals(RelocateResult.Confirmed, relocate(other, "x", theirs))

        assertEquals(listOf(other), lists.saved)
        assertEquals(mine, DownloadsRepository.uiState.value.items.single())
        assertEquals(drive.id, decodeStoredDownloads(lists.stored.getValue(other))!!.single().locationId)
        assertEquals(drive.id, DownloadsRepository.recordOf(other, "x", lists)?.locationId)
    }

    @Test
    fun a_record_of_the_loaded_profile_is_changed_in_memory_and_saved_under_that_profiles_key() {
        val mine = film("x")
        load(mine)

        assertEquals(RelocateResult.Confirmed, relocate(active, "x", mine))

        assertEquals(listOf(active), lists.saved)
        assertEquals(drive.id, DownloadsRepository.uiState.value.items.single().locationId)
    }

    @Test
    fun a_record_that_changed_since_the_move_planned_is_not_touched_and_nothing_is_saved() {
        val mine = film("x")
        load(mine.copy(localFileUri = world.appFolder.resolve("somewhere-else.mp4").toUri().toString()))

        assertEquals(RelocateResult.RecordChanged, relocate(active, "x", mine))
        assertEquals(RelocateResult.RecordGone, relocate(active, "gone", mine))

        assertTrue(lists.saved.isEmpty())
        assertNull(DownloadsRepository.uiState.value.items.single().locationId)
    }

    @Test
    fun a_save_whose_flush_fails_is_not_confirmed_and_the_list_in_memory_stays_as_it_was() {
        val mine = film("x")
        load(mine)
        lists.flushes = false

        assertEquals(RelocateResult.NotDurable, relocate(active, "x", mine))

        assertEquals(mine, DownloadsRepository.uiState.value.items.single())
    }

    @Test
    fun a_profile_list_that_cannot_be_read_is_reported_and_left_alone() {
        load(film("y"))
        lists.stored[other] = "{ not a list"

        assertEquals(RelocateResult.ListUnreadable, relocate(other, "x", film("x")))
        assertEquals("{ not a list", lists.stored[other])
        assertTrue(lists.saved.isEmpty())
    }

    @Test
    fun the_store_reports_a_flush_only_when_both_the_file_and_its_folder_were_flushed() {
        val store = com.nuvio.app.core.storage.DesktopStorage.Store(world.root.resolve("lists.properties"))
        store.putString("downloads_1", "{}")
        assertTrue(store.sync())
        val missing = com.nuvio.app.core.storage.DesktopStorage.Store(world.root.resolve("not-there/lists.properties"))
        assertEquals(false, missing.sync())
    }
}
