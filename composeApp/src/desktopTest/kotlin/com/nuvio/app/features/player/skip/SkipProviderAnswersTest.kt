package com.nuvio.app.features.player.skip

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.simkl.SimklConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What counts as a provider having answered. Only a successful response, or a clear "no entry for
 * this title" (HTTP 404 or 410, or a well-formed empty result), is an answer. Anything else leaves
 * the lookup incomplete, so that it can never replace saved segments.
 */
class SkipProviderAnswersTest {
    private val realTransport = SkipHttp.transport

    @kotlin.test.BeforeTest
    fun configure() {
        SkipIntroApi.introDbBaseUrlOverride = "https://introdb.test"
    }

    @AfterTest
    fun restore() {
        SkipHttp.transport = realTransport
        SkipIntroApi.introDbBaseUrlOverride = null
    }

    private fun response(status: Int, body: String = "") =
        RawHttpResponse(status = status, statusText = "", url = "", body = body, headers = emptyMap())

    private val answers = listOf(
        // status, body, answered?
        Triple(200, "{\"found\":true}", true),
        Triple(404, "", true),
        Triple(410, "", true),
        Triple(500, "oops", false),
        Triple(502, "", false),
        Triple(503, "", false),
        Triple(429, "slow down", false),
        Triple(401, "", false),
        Triple(403, "", false),
        Triple(408, "", false),
        Triple(301, "", false),
    )

    @Test
    fun the_status_table_is_in_one_place() {
        for ((status, _, answered) in answers) {
            assertEquals(answered, classifySkipStatus(status) != SkipStatusClass.Unanswered, "status $status")
        }
        assertEquals(SkipStatusClass.Ok, classifySkipStatus(204))
        assertEquals(SkipStatusClass.NoEntry, classifySkipStatus(404))
    }

    private suspend fun unanswered(block: suspend () -> Unit): Boolean {
        val report = SkipLookupReport()
        withContext(report) { block() }
        return report.hadUnansweredRequest
    }

    private fun respond(status: Int, body: String) {
        SkipHttp.transport = { _, _, _, _ -> response(status, body) }
    }

    // ---- IntroDB (episodes and movies) ----

    @Test
    fun introdb_episode_lookup_per_status() = runBlocking {
        for ((status, body, answered) in answers) {
            respond(status, if (status == 200) "{\"imdb_id\":\"tt1\",\"intro\":{\"start_sec\":1.0,\"end_sec\":2.0}}" else body)
            assertEquals(!answered, unanswered { SkipIntroApi.getIntroDbSegments("tt1", 1, 1) }, "introdb status $status")
        }
    }

    @Test
    fun introdb_movie_lookup_per_status() = runBlocking {
        for ((status, body, answered) in answers) {
            respond(status, if (status == 200) "{\"imdb_id\":\"tt1\"}" else body)
            assertEquals(!answered, unanswered { SkipIntroApi.getIntroDbMovieSegments("tt1") }, "introdb movie status $status")
        }
    }

    @Test
    fun introdb_with_an_unreadable_body_is_unanswered_and_with_an_empty_result_is_answered() = runBlocking {
        respond(200, "<html>maintenance</html>")
        assertTrue(unanswered { SkipIntroApi.getIntroDbSegments("tt1", 1, 1) })
        respond(200, "{}")
        assertFalse(unanswered { SkipIntroApi.getIntroDbSegments("tt1", 1, 1) })
    }

    // ---- AniSkip ----

    @Test
    fun aniskip_per_status() = runBlocking {
        for ((status, body, answered) in answers) {
            respond(status, if (status == 200) "{\"found\":true,\"results\":[]}" else body)
            assertEquals(!answered, unanswered { SkipIntroApi.getAniSkipTimes("1", 1) }, "aniskip status $status")
        }
    }

    @Test
    fun aniskip_found_false_is_an_answer_and_a_broken_body_is_not() = runBlocking {
        respond(200, "{\"found\":false}")
        assertFalse(unanswered { SkipIntroApi.getAniSkipTimes("1", 1) })
        respond(200, "not json")
        assertTrue(unanswered { SkipIntroApi.getAniSkipTimes("1", 1) })
    }

    // ---- Anime-Skip (GraphQL: always answers with a body) ----

    @Test
    fun animeskip_per_status_and_graphql_errors() = runBlocking {
        for ((status, _, answered) in answers) {
            respond(status, if (status == 200) "{\"data\":{\"findShowsByExternalId\":[]}}" else "")
            // A 404 on a fixed POST endpoint means the service is broken, not that the title has no entry.
            val expectAnswered = answered && status != 404 && status != 410
            assertEquals(!expectAnswered, unanswered { SkipIntroApi.queryAnimeSkip("id", "{ q }") }, "animeskip status $status")
        }
        respond(200, "{\"errors\":[{\"message\":\"rate limited\"}]}")
        assertTrue(unanswered { SkipIntroApi.queryAnimeSkip("id", "{ q }") })
        respond(200, "{\"data\":null}")
        assertTrue(unanswered { SkipIntroApi.queryAnimeSkip("id", "{ q }") })
    }

    // ---- Simkl id lookups ----

    @Test
    fun simkl_lookups_per_status() = runBlocking {
        val saved = SimklConfig.CLIENT_ID
        if (saved.isBlank()) return@runBlocking // not configured in this build: nothing is asked
        SimklIdResolver.clearCache()
        for ((status, _, answered) in answers) {
            respond(status, if (status == 200) "[]" else "")
            SimklIdResolver.clearCache()
            assertEquals(!answered, unanswered { SimklIdResolver.resolveIds("mal", "1") }, "simkl status $status")
        }
    }

    @Test
    fun a_connection_that_failed_or_timed_out_is_unanswered_for_every_provider() = runBlocking {
        SkipHttp.transport = { _, _, _, _ -> throw IOException("no route") }
        assertTrue(unanswered { SkipIntroApi.getIntroDbSegments("tt1", 1, 1) })
        assertTrue(unanswered { SkipIntroApi.getIntroDbMovieSegments("tt1") })
        assertTrue(unanswered { SkipIntroApi.getAniSkipTimes("1", 1) })
        assertTrue(unanswered { SkipIntroApi.queryAnimeSkip("id", "{ q }") })
    }
}
