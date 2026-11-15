package com.nuvio.app.features.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfilePickerContentTest {
    @Test
    fun loadedEmptyListOffersAddTileAndGuidanceWithoutEditMode() {
        val content = profilePickerContent(profileCount = 0, isLoaded = true, isEditMode = false)

        assertTrue(content.showAddTile)
        assertTrue(content.showEmptyGuidance)
        assertFalse(content.showManageControl)
    }

    @Test
    fun emptyListThatHasNotLoadedYetShowsNoGuidanceOrTile() {
        val content = profilePickerContent(profileCount = 0, isLoaded = false, isEditMode = false)

        assertFalse(content.showAddTile)
        assertFalse(content.showEmptyGuidance)
        assertTrue(content.showManageControl)
    }

    @Test
    fun existingProfilesKeepTheOldBehaviourOutsideEditMode() {
        val content = profilePickerContent(profileCount = 2, isLoaded = true, isEditMode = false)

        assertEquals(ProfilePickerContent(showAddTile = false, showEmptyGuidance = false, showManageControl = true), content)
    }

    @Test
    fun existingProfilesGetTheAddTileInEditMode() {
        val content = profilePickerContent(profileCount = 2, isLoaded = true, isEditMode = true)

        assertEquals(ProfilePickerContent(showAddTile = true, showEmptyGuidance = false, showManageControl = true), content)
    }

    @Test
    fun fullListNeverOffersAnotherTile() {
        val editing = profilePickerContent(MAX_PROFILES, isLoaded = true, isEditMode = true)
        val idle = profilePickerContent(MAX_PROFILES, isLoaded = true, isEditMode = false)

        assertFalse(editing.showAddTile)
        assertFalse(idle.showAddTile)
    }

    @Test
    fun editModeCanAlwaysBeLeftEvenWhenTheListBecomesEmpty() {
        val content = profilePickerContent(profileCount = 0, isLoaded = true, isEditMode = true)

        assertTrue(content.showManageControl)
        assertTrue(content.showAddTile)
    }
}
