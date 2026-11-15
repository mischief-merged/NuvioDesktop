package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The exit target is taken only when the Back guard accepts a request. */
class PlayerExitTargetTest {

    private val show = PlayerExitTarget.Show(type = "series", id = "tt1", title = "Show")
    private val episode = PlayerExitTarget.Episode("series", "tt1", "Show", "tt1:1:2", 1, 2)

    private class Harness {
        val guard = PlayerBackReleaseGuard()
        val holder = PlayerExitTargetHolder()
        val opened = mutableListOf<PlayerExitTarget?>()
        var finishRelease: (() -> Unit)? = null

        /** Mirrors the player: the target is set inside the release step, which only an accepted request runs. */
        fun request(target: PlayerExitTarget?) {
            guard.request(
                canStart = { true },
                releaseBeforeBack = { onReleased, _ ->
                    holder.target = target
                    finishRelease = onReleased
                },
                beforePop = {},
                pop = {
                    opened += holder.target
                    true
                },
            )
        }
    }

    @Test
    fun aSecondRequestWhileTheFirstIsReleasingDoesNotChangeTheTarget() {
        val h = Harness()
        h.request(episode)
        h.request(null)
        h.request(show)
        h.finishRelease!!()
        assertEquals(listOf<PlayerExitTarget?>(episode), h.opened)
    }

    @Test
    fun aPlainBackFirstIsNotTurnedIntoAnExitByALaterClick() {
        val h = Harness()
        h.request(null)
        h.request(show)
        h.finishRelease!!()
        assertEquals(listOf<PlayerExitTarget?>(null), h.opened)
    }

    @Test
    fun aRejectedRequestLeavesTheHolderAlone() {
        val h = Harness()
        h.holder.target = show
        h.guard.request(canStart = { false }, releaseBeforeBack = { _, _ -> h.holder.target = null }, beforePop = {}, pop = { true })
        assertEquals(show, h.holder.target)
    }

    @Test
    fun aRetryAfterAFailedReleaseUsesItsOwnTarget() {
        val guard = PlayerBackReleaseGuard()
        val holder = PlayerExitTargetHolder()
        val opened = mutableListOf<PlayerExitTarget?>()
        var fail: ((String) -> Unit)? = null
        guard.request(
            canStart = { true },
            releaseBeforeBack = { _, onFailed -> holder.target = episode; fail = onFailed },
            beforePop = {},
            pop = { opened += holder.target; true },
        )
        fail!!("release failed")
        guard.request(
            canStart = { true },
            releaseBeforeBack = { onReleased, _ -> holder.target = null; onReleased() },
            beforePop = {},
            pop = { opened += holder.target; true },
        )
        assertEquals(listOf<PlayerExitTarget?>(null), opened)
        assertNull(holder.target)
    }
}
