package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetaDetailsRuntimeTest {
    private val show = MetaDetails(
        id = "tt1",
        type = "series",
        name = "Show",
        runtime = "45 min",
        videos = listOf(
            MetaVideo(id = "tt1:1:1", title = "One", season = 1, episode = 1, runtime = 42),
            MetaVideo(id = "tt1:1:2", title = "Two", season = 1, episode = 2),
        ),
    )

    @Test
    fun `an episode uses its own runtime, found by id or by season and episode`() {
        assertEquals(42, show.runtimeMinutesFor("tt1:1:1", 1, 1))
        assertEquals(42, show.runtimeMinutesFor("other-id", 1, 1))
    }

    @Test
    fun `an episode without a runtime is unknown, not the runtime of the show`() {
        assertNull(show.runtimeMinutesFor("tt1:1:2", 1, 2))
    }

    @Test
    fun `a movie uses the runtime of the title`() {
        val movie = MetaDetails(id = "tt2", type = "movie", name = "Movie", runtime = "1h 58m")
        assertEquals(118, movie.runtimeMinutesFor("tt2", null, null))
        assertNull(movie.runtimeMinutesFor("tt3", null, null))
    }
}
