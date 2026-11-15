package com.nuvio.app.features.downloads

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeasonBatchRunsTest {
    // A profile id no real profile uses, so stored runs of real profiles are never touched.
    private val profile = 9_900_021

    private fun episode(number: Int, title: String? = "Episode $number") =
        SeasonBatchEpisode(videoId = "tt1:2:$number", seasonNumber = 2, episodeNumber = number, title = title, thumbnail = null)

    private fun season(vararg episodes: SeasonBatchEpisode) = SeasonBatchRequest(
        contentType = "series",
        parentMetaId = "tt1",
        parentMetaType = "series",
        title = "Pantheon",
        logo = null,
        poster = "poster.jpg",
        background = null,
        seasonNumber = 2,
        episodes = episodes.toList(),
        quality = BatchQuality.BestAvailable,
    )

    private fun movie(id: String, title: String) = SeasonBatchEpisode(
        videoId = id,
        seasonNumber = MovieBatchSeasonNumber,
        episodeNumber = 1,
        title = null,
        thumbnail = null,
        movie = SeasonBatchMovie(metaId = id, metaType = "movie", title = title, poster = "$id.jpg"),
    )

    private fun movies(vararg movies: SeasonBatchEpisode, setName: String = "Set") = SeasonBatchRequest(
        contentType = "movie",
        parentMetaId = "movie-set:tt9",
        parentMetaType = "movie",
        title = setName,
        logo = null,
        poster = null,
        background = null,
        seasonNumber = MovieBatchSeasonNumber,
        episodes = movies.toList(),
        quality = BatchQuality.BestAvailable,
    )

    @BeforeTest
    @AfterTest
    fun clear_test_profile() {
        SeasonDownloadDefaultsStorage.saveBatchRunsPayload(profile, "")
    }

    @Test
    fun a_season_run_keeps_the_counts_and_names_the_episodes_that_were_not_found_or_failed() {
        val e1 = episode(1)
        val e2 = episode(2)
        val e3 = episode(3)
        val e4 = episode(4, title = null)
        val e5 = episode(5)
        val request = season(e1, e2, e3, e4, e5)
        val summary = SeasonBatchSummary(
            results = mapOf(
                e5 to SeasonBatchEpisodeResult.Failed,
                e1 to SeasonBatchEpisodeResult.Completed,
                e2 to SeasonBatchEpisodeResult.AlreadyDownloaded,
                e3 to SeasonBatchEpisodeResult.NoMatchingSource,
                e4 to SeasonBatchEpisodeResult.NoMatchingSource,
            ),
            wasCancelled = false,
        )

        val record = assertNotNull(summary.toRunRecord(request, nowEpochMs = 1234L))

        assertEquals(BatchRunKind.Season, record.kind)
        assertEquals("Pantheon", record.title)
        assertEquals(2, record.seasonNumber)
        assertEquals(1234L, record.finishedAtEpochMs)
        assertEquals(listOf(1, 1, 2, 1, 0), listOf(record.added, record.alreadyThere, record.notFound, record.failed, record.paused))
        assertEquals(
            listOf(3 to BatchRunProblemKind.NotFound, 4 to BatchRunProblemKind.NotFound, 5 to BatchRunProblemKind.Failed),
            record.problems.map { it.episodeNumber to it.kind },
        )
        assertEquals("tt1:2:3", record.problems.first().videoId)
        assertEquals("poster.jpg", record.problems.first().poster)
        assertNull(record.problems[1].episodeTitle)
    }

    @Test
    fun a_run_that_was_stopped_before_anything_was_decided_keeps_nothing() {
        val e1 = episode(1)
        val summary = SeasonBatchSummary(mapOf(e1 to SeasonBatchEpisodeResult.Cancelled), wasCancelled = true)

        assertNull(summary.toRunRecord(season(e1), 1L))
    }

    @Test
    fun a_stopped_run_says_so_and_keeps_what_was_decided() {
        val e1 = episode(1)
        val e2 = episode(2)
        val summary = SeasonBatchSummary(
            mapOf(e1 to SeasonBatchEpisodeResult.Completed, e2 to SeasonBatchEpisodeResult.Cancelled),
            wasCancelled = true,
        )

        val record = assertNotNull(summary.toRunRecord(season(e1, e2), 1L))

        assertTrue(record.stopped)
        assertEquals(1, record.added)
    }

    @Test
    fun a_single_movie_is_kept_only_when_it_needs_attention() {
        val film = movie("tt7", "Film")
        val request = movies(film).copy(parentMetaId = "tt7", title = "Film")

        assertNull(SeasonBatchSummary(mapOf(film to SeasonBatchEpisodeResult.Completed), false).toRunRecord(request, 1L))
        assertNull(SeasonBatchSummary(mapOf(film to SeasonBatchEpisodeResult.AlreadyDownloaded), false).toRunRecord(request, 1L))
        val notFound = assertNotNull(SeasonBatchSummary(mapOf(film to SeasonBatchEpisodeResult.NoMatchingSource), false).toRunRecord(request, 1L))
        assertEquals(BatchRunKind.Movie, notFound.kind)
        assertNull(notFound.seasonNumber)
        assertEquals("tt7", notFound.problems.single().videoId)
        assertEquals("Film", notFound.problems.single().title)
        assertNull(notFound.problems.single().episodeNumber)
    }

    @Test
    fun a_movie_set_names_each_movie_that_was_not_found() {
        val a = movie("tt1", "First")
        val b = movie("tt2", "Second").copy(episodeNumber = 2)
        val summary = SeasonBatchSummary(
            mapOf(a to SeasonBatchEpisodeResult.Completed, b to SeasonBatchEpisodeResult.NoMatchingSource),
            wasCancelled = false,
        )

        val record = assertNotNull(summary.toRunRecord(movies(a, b), 1L))

        assertEquals(BatchRunKind.MovieSet, record.kind)
        assertEquals("Set", record.title)
        assertEquals("Second", record.problems.single().title)
        assertEquals("tt2", record.problems.single().parentMetaId)
        assertEquals("movie", record.problems.single().contentType)
    }

    @Test
    fun the_problems_of_one_run_are_capped_and_the_rest_is_counted() {
        val episodes = (1..MaxProblemsPerRun + 5).map { episode(it) }
        val summary = SeasonBatchSummary(episodes.associateWith { SeasonBatchEpisodeResult.NoMatchingSource }, false)

        val record = assertNotNull(summary.toRunRecord(season(*episodes.toTypedArray()), 1L))

        assertEquals(MaxProblemsPerRun, record.problems.size)
        assertEquals(5, record.problemsOmitted)
        assertEquals(MaxProblemsPerRun + 5, record.notFound)
    }

    @Test
    fun a_new_run_replaces_the_run_with_the_same_key_and_the_list_is_capped() {
        val older = BatchRunRecord(key = "a|1", title = "Old", added = 1)
        val newer = BatchRunRecord(key = "a|1", title = "New", added = 2)

        assertEquals(listOf("New"), listOf(older).withRun(newer).map { it.title })

        val many = (1..MaxBatchRuns).map { BatchRunRecord(key = "k$it") }
        val withOne = many.withRun(BatchRunRecord(key = "fresh"))
        assertEquals(MaxBatchRuns, withOne.size)
        assertEquals("fresh", withOne.first().key)
        assertTrue(withOne.none { it.key == "k$MaxBatchRuns" })
    }

    @Test
    fun stored_runs_are_read_back_and_dismissing_removes_one() {
        val one = BatchRunRecord(key = "a|1", title = "One", notFound = 1, problems = listOf(BatchRunProblem(title = "One", videoId = "v")))
        val two = BatchRunRecord(key = "b|1", title = "Two")

        BatchRunSummaries.record(profile, one)
        BatchRunSummaries.record(profile, two)
        assertEquals(listOf("Two", "One"), BatchRunSummaries.load(profile).map { it.title })
        assertEquals("v", BatchRunSummaries.load(profile).last().problems.single().videoId)

        BatchRunSummaries.dismiss(profile, "b|1")
        assertEquals(listOf("One"), BatchRunSummaries.load(profile).map { it.title })
    }

    @Test
    fun nothing_stored_or_unreadable_storage_is_no_runs() {
        assertEquals(emptyList(), BatchRunSummaries.load(profile))
        assertEquals(emptyList(), BatchRunSummaries.decode("not json"))
        assertEquals(emptyList(), BatchRunSummaries.decode("{\"key\":\"x\"}"))
    }

    @Test
    fun a_record_written_by_another_version_still_reads() {
        val payload = """
            [
              {"key":"a|1","title":"Early","added":3,"futureField":{"x":1}},
              {"key":"b|2","kind":"SomethingNew","title":"Odd kind","problems":[{"kind":"Weird","videoId":"v1","title":"T","extra":true}]},
              {"key":"c|3","title":"Bad count","added":"many"},
              {"title":"No key"},
              7
            ]
        """.trimIndent()

        val runs = BatchRunSummaries.decode(payload)

        assertEquals(listOf("a|1", "b|2"), runs.map { it.key })
        assertEquals(3, runs[0].added)
        assertEquals(0, runs[0].notFound)
        assertEquals(emptyList(), runs[0].problems)
        assertEquals(BatchRunKind.Season, runs[1].kind)
        assertEquals(BatchRunProblemKind.Failed, runs[1].problems.single().kind)
        assertEquals("v1", runs[1].problems.single().videoId)
    }

    @Test
    fun the_stand_in_for_choosing_another_source_carries_what_the_list_of_sources_needs() {
        val problem = BatchRunProblem(
            kind = BatchRunProblemKind.NotFound,
            contentType = "series",
            parentMetaId = "tt1",
            parentMetaType = "series",
            videoId = "tt1:2:3",
            title = "Pantheon",
            poster = "p.jpg",
            seasonNumber = 2,
            episodeNumber = 3,
            episodeTitle = "Three",
        )

        val item = problem.asSourceRequest(atEpochMs = 99L)

        assertEquals(listOf("series", "tt1", "series", "tt1:2:3", "Pantheon"), listOf(item.contentType, item.parentMetaId, item.parentMetaType, item.videoId, item.title))
        assertEquals(listOf(2, 3, "Three"), listOf(item.seasonNumber, item.episodeNumber, item.episodeTitle))
        assertEquals("p.jpg", item.poster)
    }
}
