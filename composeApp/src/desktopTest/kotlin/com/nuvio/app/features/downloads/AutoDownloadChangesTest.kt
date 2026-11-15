package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Saving, turning off and the limit report what really happened. The storage is in memory and can be made to fail. */
class AutoDownloadChangesTest {
    private class FlakyPayloads : AutoDownloadPayloads {
        val inner = MemoryAutoDownloadPayloads()
        var failing = false

        override fun load(profileId: Int): String? = inner.load(profileId)

        override fun save(profileId: Int, payload: String) {
            if (failing) error("disk is full")
            inner.save(profileId, payload)
        }

        override fun saveUnreadable(profileId: Int, payload: String) = inner.saveUnreadable(profileId, payload)
    }

    private val payloads = FlakyPayloads()
    private var activeProfile = 1
    private val store = AutoDownloadStore(payloads) { activeProfile }
    private val rule = testRule()

    @Test
    fun a_new_rule_is_reported_as_turned_on_and_a_changed_one_as_changed() {
        assertEquals(AutoDownloadSaveResult.TurnedOn, saveAutoDownloadRule(store, 1, rule))
        assertEquals(AutoDownloadSaveResult.Changed, saveAutoDownloadRule(store, 1, rule.copy(keepCount = 7)))

        assertEquals(7, store.currentRules().single().keepCount)
    }

    @Test
    fun a_save_that_fails_is_reported_and_leaves_what_is_shown_as_it_is_stored() {
        payloads.failing = true

        assertEquals(AutoDownloadSaveResult.NotSaved, saveAutoDownloadRule(store, 1, rule))

        assertEquals(emptyList(), store.currentRules())
        assertEquals(emptyList(), store.rules.value)
        assertEquals(emptyMap(), payloads.inner.saved)
    }

    @Test
    fun a_change_that_fails_keeps_the_old_rule_in_view() {
        saveAutoDownloadRule(store, 1, rule)
        payloads.failing = true

        assertEquals(AutoDownloadSaveResult.NotSaved, saveAutoDownloadRule(store, 1, rule.copy(keepCount = 10)))

        assertEquals(3, store.rules.value.single().keepCount)
        assertEquals(3, AutoDownloadStore(payloads.inner) { 1 }.currentRules().single().keepCount)
    }

    @Test
    fun a_save_for_a_profile_that_is_no_longer_active_is_not_saved() {
        activeProfile = 2

        assertEquals(AutoDownloadSaveResult.NotSaved, saveAutoDownloadRule(store, 1, rule))

        assertEquals(emptyList(), store.currentRules())
    }

    @Test
    fun turning_off_reports_success_and_failure() {
        saveAutoDownloadRule(store, 1, rule)
        payloads.failing = true

        assertFalse(removeAutoDownloadRule(store, 1, rule.key))
        assertEquals(1, store.rules.value.size, "a rule that is still stored must still be shown")

        payloads.failing = false
        assertTrue(removeAutoDownloadRule(store, 1, rule.key))
        assertEquals(emptyList(), store.currentRules())
        assertFalse(removeAutoDownloadRule(store, 2, rule.key))
    }

    @Test
    fun the_storage_limit_reports_success_and_failure_and_keeps_the_old_limit_on_failure() {
        store.currentRules()
        payloads.failing = true

        assertFalse(changeAutoDownloadStorageLimit(store, 1, 100))
        assertEquals(AutoDownloadStore.DefaultStorageLimitGb, store.storageLimitGb.value)

        payloads.failing = false
        assertTrue(changeAutoDownloadStorageLimit(store, 1, 100))
        assertEquals(100, store.storageLimitGb.value)
    }
}
