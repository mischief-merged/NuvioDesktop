package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpisodeFocusStateTest {
    @Test
    fun aNewRequestNeedsPositioningAndIsNotPlaced() {
        val focus = EpisodeFocusState("e1", 3)
        assertTrue(focus.needsPositioning)
        assertFalse(focus.placed)
        assertFalse(focus.highlighted)
    }

    @Test
    fun placingConsumesTheRequestOnce() {
        val focus = EpisodeFocusState("e1")
        assertTrue(focus.markPlaced())
        assertFalse(focus.markPlaced())
        assertFalse(focus.needsPositioning)
    }

    @Test
    fun aSecondCompositionOfTheCardDoesNotRestartTheHighlight() {
        val focus = EpisodeFocusState("e1")
        focus.markPlaced()
        focus.highlighted = false
        assertFalse(focus.markPlaced())
        assertFalse(focus.highlighted)
    }
}
