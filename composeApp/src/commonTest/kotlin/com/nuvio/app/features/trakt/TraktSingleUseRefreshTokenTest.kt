package com.nuvio.app.features.trakt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraktSingleUseRefreshTokenTest {
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val accepted =
        """{"access_token":"new-access","refresh_token":"new-refresh","token_type":"bearer","expires_in":604800,"created_at":5}"""

    private fun login(refresh: String, user: String) = codec.encodeToString(
        TraktAuthState.serializer(),
        TraktAuthState(
            accessToken = "old-access", refreshToken = refresh, tokenType = "bearer",
            createdAt = 1L, expiresIn = 100, username = user, userSlug = user,
        ),
    )

    private fun storage() = mutableMapOf(1 to login("old-refresh", "user-one"), 2 to login("other-refresh", "user-two"))

    private fun store(storage: MutableMap<Int, String>, status: Int = 200, body: String = accepted) =
        storeRotatedTraktTokens(
            profileId = 1,
            responseStatus = status,
            responseBody = body,
            spentRefreshToken = "old-refresh",
            loadPayload = { storage[it] },
            savePayload = { profileId, payload -> storage[profileId] = payload },
        )

    @Test
    fun an_accepted_answer_is_saved_under_the_requesting_profile_only() {
        val storage = storage()
        val otherProfile = storage.getValue(2)

        assertTrue(store(storage))

        val saved = codec.decodeFromString(TraktAuthState.serializer(), storage.getValue(1))
        assertEquals("new-access", saved.accessToken)
        assertEquals("new-refresh", saved.refreshToken)
        assertEquals(5L, saved.createdAt)
        assertEquals(604800, saved.expiresIn)
        assertEquals("user-one", saved.username)
        assertEquals(otherProfile, storage.getValue(2))
        assertEquals(setOf(1, 2), storage.keys)
    }

    @Test
    fun a_rejected_or_failed_answer_writes_nothing() {
        for (status in listOf(400, 401, 500, 503)) {
            val storage = storage()
            assertFalse(store(storage, status = status), "status=$status")
            assertEquals(storage(), storage, "status=$status")
        }
    }

    @Test
    fun a_login_that_was_replaced_meanwhile_is_left_untouched() {
        val storage = storage().also { it[1] = login("reconnected-refresh", "user-one") }
        val before = storage.toMap()

        assertFalse(store(storage))
        assertEquals(before, storage)
    }

    @Test
    fun a_login_that_was_cleared_meanwhile_is_not_restored() {
        val signedOut = codec.encodeToString(TraktAuthState.serializer(), TraktAuthState())
        for (cleared in listOf(null, "", signedOut)) {
            val storage = storage()
            if (cleared == null) storage.remove(1) else storage[1] = cleared
            val before = storage.toMap()

            assertFalse(store(storage), "cleared=$cleared")
            assertEquals(before, storage, "cleared=$cleared")
        }
    }

    @Test
    fun a_malformed_answer_or_stored_login_writes_nothing() {
        for (body in listOf("", "{not json", """{"access_token":"only-half"}""")) {
            val storage = storage()
            assertFalse(store(storage, body = body), "body=$body")
            assertEquals(storage(), storage, "body=$body")
        }
        val corrupt = storage().also { it[1] = "{not json" }
        assertFalse(store(corrupt))
        assertEquals("{not json", corrupt[1])
    }
}
