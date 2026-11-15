package com.nuvio.app.features.details

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The episode the show page was asked to scroll to. The card of that episode reports back when it
 * is on screen, which ends the search for it, and wears a marker until [highlighted] is cleared.
 */
internal class EpisodeFocusState(val videoId: String, val indexInSeason: Int = 0) {
    /** Set when the season row that holds the episode is on screen, so the page stops stepping down. */
    var sectionReached by mutableStateOf(false)
        private set
    var placed by mutableStateOf(false)
        private set

    fun markSectionReached() {
        sectionReached = true
    }

    var highlighted by mutableStateOf(false)

    fun markPlaced() {
        if (placed) return
        placed = true
        highlighted = true
    }
}

internal val LocalEpisodeFocus = staticCompositionLocalOf<EpisodeFocusState?> { null }

/**
 * Desktop only: what the Play button on an episode thumbnail does. Null leaves the thumbnail
 * without a button, which is how the cards look on every other platform.
 */
internal val LocalEpisodeThumbnailPlay = staticCompositionLocalOf<((MetaVideo) -> Unit)?> { null }
