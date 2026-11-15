package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamAutoPlayModeTest {
    @Test
    fun `every mode round trips through its stored name`() {
        StreamAutoPlayMode.entries.forEach { mode ->
            assertEquals(mode, StreamAutoPlayMode.fromStored(mode.name))
        }
    }

    @Test
    fun `the new mode is stored as PREFERRED`() {
        assertEquals("PREFERRED", StreamAutoPlayMode.PREFERRED.name)
        assertEquals(StreamAutoPlayMode.PREFERRED, StreamAutoPlayMode.fromStored("PREFERRED"))
    }

    @Test
    fun `a missing, blank or unknown stored value reads as manual and does not throw`() {
        listOf(null, "", " ", "preferred", "BEST_MATCH", "SOME_FUTURE_MODE").forEach { stored ->
            assertEquals(StreamAutoPlayMode.MANUAL, StreamAutoPlayMode.fromStored(stored), "stored=$stored")
        }
    }
}
