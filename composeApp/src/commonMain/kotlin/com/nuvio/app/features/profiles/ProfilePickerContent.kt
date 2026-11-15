package com.nuvio.app.features.profiles

internal data class ProfilePickerContent(
    val showAddTile: Boolean,
    val showEmptyGuidance: Boolean,
    val showManageControl: Boolean,
)

/**
 * Decides which optional parts of the profile picker are visible.
 *
 * With no profiles on a finished load the picker must offer a way forward without edit
 * mode: an "Add profile" tile and a line of guidance. Otherwise the tile appears only in
 * edit mode, as before. "Manage Profiles" has nothing to manage while the list is empty,
 * unless edit mode is already on and has to be left.
 */
internal fun profilePickerContent(
    profileCount: Int,
    isLoaded: Boolean,
    isEditMode: Boolean,
    maxProfiles: Int = MAX_PROFILES,
): ProfilePickerContent {
    val canAdd = profileCount < maxProfiles
    val isEmpty = isLoaded && profileCount == 0
    return ProfilePickerContent(
        showAddTile = canAdd && (isEditMode || isEmpty),
        showEmptyGuidance = isEmpty,
        showManageControl = !isEmpty || isEditMode,
    )
}
