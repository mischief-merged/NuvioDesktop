package com.nuvio.app.features.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.tracking.outbox.OutboxCounts
import com.nuvio.app.features.tracking.outbox.QueueSyncResult
import com.nuvio.app.features.tracking.outbox.QueueSyncState
import com.nuvio.app.features.tracking.outbox.TrackerQueueController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.settings_trakt_disconnect
import nuvio.composeapp.generated.resources.settings_trakt_disconnect_anyway
import nuvio.composeapp.generated.resources.settings_trakt_disconnect_description
import nuvio.composeapp.generated.resources.settings_trakt_disconnect_pending
import nuvio.composeapp.generated.resources.settings_trakt_disconnect_sync_first
import nuvio.composeapp.generated.resources.settings_trakt_queue_dismiss
import nuvio.composeapp.generated.resources.settings_trakt_queue_dropped
import nuvio.composeapp.generated.resources.settings_trakt_queue_dropped_description
import nuvio.composeapp.generated.resources.settings_trakt_queue_result_all_sent
import nuvio.composeapp.generated.resources.settings_trakt_queue_result_not_accepted
import nuvio.composeapp.generated.resources.settings_trakt_queue_result_nothing_waiting
import nuvio.composeapp.generated.resources.settings_trakt_queue_result_offline
import nuvio.composeapp.generated.resources.settings_trakt_queue_result_partial
import nuvio.composeapp.generated.resources.settings_trakt_queue_result_refused
import nuvio.composeapp.generated.resources.settings_trakt_queue_result_retry_in
import nuvio.composeapp.generated.resources.settings_trakt_queue_sync_now
import nuvio.composeapp.generated.resources.settings_trakt_queue_syncing
import nuvio.composeapp.generated.resources.settings_trakt_queue_try_again
import nuvio.composeapp.generated.resources.settings_trakt_queue_undeliverable
import nuvio.composeapp.generated.resources.settings_trakt_queue_undeliverable_description
import nuvio.composeapp.generated.resources.settings_trakt_queue_waiting
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TrackerQueueSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private class FakeQueue : TrackerQueueController {
        override val counts = MutableStateFlow(OutboxCounts())
        override val syncState = MutableStateFlow<QueueSyncState>(QueueSyncState.Idle)
        var syncs = 0
        var retries = 0
        var dismissedUndeliverable = 0
        var dismissedDropped = 0
        var cleared = 0

        override fun syncNow() {
            syncs++
            syncState.value = QueueSyncState.Syncing
        }

        override fun retryUndeliverable() {
            retries++
        }

        override fun dismissUndeliverable() {
            dismissedUndeliverable++
        }

        override fun dismissDropped() {
            dismissedDropped++
        }

        override fun clearResult() {
            cleared++
        }
    }

    private val queue = FakeQueue()

    private fun result(
        sent: Int = 0,
        stillWaiting: Int = 0,
        newlyRefused: Int = 0,
        offline: Boolean = false,
        retryInMs: Long? = null,
    ) = QueueSyncResult(sent, stillWaiting, newlyRefused, offline, retryInMs)

    // The line and Sync now

    @Test
    fun nothing_is_shown_while_nothing_is_queued() {
        showSection()

        compose.onNodeWithText(plural(Res.plurals.settings_trakt_queue_waiting, 0)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(Res.string.settings_trakt_queue_sync_now)).assertDoesNotExist()
    }

    @Test
    fun waiting_watches_are_counted_in_a_line_with_the_right_plural() {
        queue.counts.value = OutboxCounts(waiting = 3)
        showSection()

        compose.onNodeWithText(plural(Res.plurals.settings_trakt_queue_waiting, 3)).assertIsDisplayed()
        assertEquals("3 watches waiting to sync to Trakt", plural(Res.plurals.settings_trakt_queue_waiting, 3))

        compose.runOnIdle { queue.counts.value = OutboxCounts(waiting = 1) }
        compose.onNodeWithText("1 watch waiting to sync to Trakt").assertIsDisplayed()
    }

    @Test
    fun the_line_follows_the_queue_and_goes_away_when_it_is_empty() {
        queue.counts.value = OutboxCounts(waiting = 2)
        showSection()
        compose.onNodeWithText(plural(Res.plurals.settings_trakt_queue_waiting, 2)).assertIsDisplayed()

        compose.runOnIdle { queue.counts.value = OutboxCounts() }

        compose.onNodeWithText(plural(Res.plurals.settings_trakt_queue_waiting, 2)).assertDoesNotExist()
    }

    @Test
    fun sync_now_is_a_named_button_that_starts_a_sync_and_shows_progress() {
        queue.counts.value = OutboxCounts(waiting = 2)
        showSection()

        compose.onNodeWithContentDescription(text(Res.string.settings_trakt_queue_sync_now))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
            .assertIsEnabled()
            .performClick()

        assertEquals(1, queue.syncs)
        compose.onNodeWithContentDescription(text(Res.string.settings_trakt_queue_sync_now))
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(Res.string.settings_trakt_queue_syncing)))
        compose.onNode(
            hasText(text(Res.string.settings_trakt_queue_syncing)) and
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        ).assertIsDisplayed()
    }

    // The result, in place

    @Test
    fun the_result_replaces_the_progress_in_place_and_is_announced() {
        queue.counts.value = OutboxCounts(waiting = 2)
        showSection()
        compose.runOnIdle { queue.syncState.value = QueueSyncState.Done(result(sent = 1, stillWaiting = 1)) }

        compose.onNode(
            hasText(text(Res.string.settings_trakt_queue_result_partial, 1, 1)) and
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        ).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.settings_trakt_queue_syncing)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(Res.string.settings_trakt_queue_sync_now)).assertIsEnabled()
    }

    @Test
    fun a_sync_that_sent_everything_still_says_so_after_the_line_has_gone() {
        queue.counts.value = OutboxCounts(waiting = 2)
        showSection()

        compose.runOnIdle {
            queue.counts.value = OutboxCounts()
            queue.syncState.value = QueueSyncState.Done(result(sent = 2))
        }

        compose.onNodeWithText(plural(Res.plurals.settings_trakt_queue_result_all_sent, 2)).assertIsDisplayed()
        compose.onNodeWithText(plural(Res.plurals.settings_trakt_queue_waiting, 2)).assertDoesNotExist()
    }

    @Test
    fun every_kind_of_result_has_its_own_plain_sentence() {
        showSection()
        val cases = listOf(
            result(sent = 1) to plural(Res.plurals.settings_trakt_queue_result_all_sent, 1),
            result() to text(Res.string.settings_trakt_queue_result_nothing_waiting),
            result(stillWaiting = 2, offline = true) to text(Res.string.settings_trakt_queue_result_offline),
            result(sent = 2, stillWaiting = 1) to text(Res.string.settings_trakt_queue_result_partial, 2, 1),
            result(stillWaiting = 2) to text(Res.string.settings_trakt_queue_result_not_accepted),
            result(stillWaiting = 2, retryInMs = 90_000L) to plural(Res.plurals.settings_trakt_queue_result_retry_in, 2),
            result(newlyRefused = 1) to plural(Res.plurals.settings_trakt_queue_result_refused, 1),
            result(sent = 1, newlyRefused = 2) to
                plural(Res.plurals.settings_trakt_queue_result_all_sent, 1) + " " + plural(Res.plurals.settings_trakt_queue_result_refused, 2),
        )
        assertEquals(cases.size, cases.map { it.second }.toSet().size, "two results share a sentence")

        cases.forEach { (case, sentence) ->
            compose.runOnIdle { queue.syncState.value = QueueSyncState.Done(case) }

            compose.onNodeWithText(sentence).assertIsDisplayed()
        }
    }

    @Test
    fun a_retry_time_is_rounded_up_to_whole_minutes() {
        showSection()

        compose.runOnIdle { queue.syncState.value = QueueSyncState.Done(result(stillWaiting = 1, retryInMs = 61_000L)) }

        compose.onNodeWithText(plural(Res.plurals.settings_trakt_queue_result_retry_in, 2)).assertIsDisplayed()
        assertEquals(
            "Trakt did not take the waiting watches this time. Nuvio will try again in about 2 minutes.",
            plural(Res.plurals.settings_trakt_queue_result_retry_in, 2),
        )
    }

    @Test
    fun leaving_the_screen_forgets_the_last_result() {
        showSection()
        compose.runOnIdle { queue.syncState.value = QueueSyncState.Done(result(sent = 1)) }

        compose.runOnIdle { shown = false }
        compose.waitForIdle()

        assertEquals(1, queue.cleared)
    }

    // Refused and dropped watches

    @Test
    fun refused_watches_are_counted_explained_and_can_be_retried_or_dismissed() {
        queue.counts.value = OutboxCounts(undeliverable = 2, retryable = 2)
        showSection()
        val title = plural(Res.plurals.settings_trakt_queue_undeliverable, 2)

        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.settings_trakt_queue_undeliverable_description)).assertIsDisplayed()
        compose.onNodeWithContentDescription("${text(Res.string.settings_trakt_queue_try_again)}, $title")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        compose.onNodeWithContentDescription("${text(Res.string.settings_trakt_queue_dismiss)}, $title").performClick()

        assertEquals(1, queue.retries)
        assertEquals(1, queue.dismissedUndeliverable)
        assertEquals(0, queue.dismissedDropped)
    }

    @Test
    fun there_is_no_retry_when_none_of_the_refused_watches_can_be_sent_again() {
        queue.counts.value = OutboxCounts(undeliverable = 1, retryable = 0)
        showSection()
        val title = plural(Res.plurals.settings_trakt_queue_undeliverable, 1)

        compose.onNodeWithContentDescription("${text(Res.string.settings_trakt_queue_try_again)}, $title").assertDoesNotExist()
        compose.onNodeWithContentDescription("${text(Res.string.settings_trakt_queue_dismiss)}, $title").assertIsDisplayed()
    }

    @Test
    fun watches_removed_for_age_are_counted_explained_and_can_be_dismissed() {
        queue.counts.value = OutboxCounts(droppedForAge = 3)
        showSection()
        val title = plural(Res.plurals.settings_trakt_queue_dropped, 3)

        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.settings_trakt_queue_dropped_description)).assertIsDisplayed()
        compose.onNodeWithContentDescription("${text(Res.string.settings_trakt_queue_dismiss)}, $title").performClick()

        assertEquals(1, queue.dismissedDropped)
        assertEquals(0, queue.dismissedUndeliverable)
    }

    @Test
    fun the_two_dismiss_buttons_are_told_apart_by_name_and_neither_state_relies_on_colour() {
        queue.counts.value = OutboxCounts(undeliverable = 1, retryable = 1, droppedForAge = 1)
        showSection()

        compose.onNodeWithContentDescription(
            "${text(Res.string.settings_trakt_queue_dismiss)}, ${plural(Res.plurals.settings_trakt_queue_undeliverable, 1)}",
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "${text(Res.string.settings_trakt_queue_dismiss)}, ${plural(Res.plurals.settings_trakt_queue_dropped, 1)}",
        ).assertIsDisplayed()
        compose.onNodeWithText(plural(Res.plurals.settings_trakt_queue_undeliverable, 1)).assertIsDisplayed()
        compose.onNodeWithText(plural(Res.plurals.settings_trakt_queue_dropped, 1)).assertIsDisplayed()
    }

    // The disconnect warning

    @Test
    fun disconnecting_with_nothing_waiting_asks_the_plain_question() {
        var disconnected = 0
        showDialog(onConfirm = { disconnected++ })

        compose.onNodeWithText(text(Res.string.settings_trakt_disconnect_description)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.settings_trakt_disconnect_sync_first)).assertDoesNotExist()
        compose.onNode(hasText(text(Res.string.settings_trakt_disconnect)) and hasAnyAncestor(isDialog())).performClick()

        assertEquals(1, disconnected)
    }

    @Test
    fun disconnecting_with_watches_waiting_warns_with_their_number_and_offers_both_choices() {
        queue.counts.value = OutboxCounts(waiting = 3)
        var disconnected = 0
        showDialog(onConfirm = { disconnected++ })

        compose.onNodeWithText(plural(Res.plurals.settings_trakt_disconnect_pending, 3)).assertIsDisplayed()
        assertEquals(
            "3 watches have not been sent to Trakt yet. If you disconnect, they will not be sent unless you reconnect the same account within 30 days of each watch being saved.",
            plural(Res.plurals.settings_trakt_disconnect_pending, 3),
        )
        compose.onNodeWithText(text(Res.string.settings_trakt_disconnect_description)).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.settings_trakt_disconnect_sync_first)).assertIsDisplayed()

        compose.onNodeWithText(text(Res.string.settings_trakt_disconnect_anyway)).performClick()

        assertEquals(1, disconnected)
        assertEquals(0, queue.syncs, "disconnecting anyway sent the watches")
    }

    @Test
    fun cancelling_the_warning_disconnects_nothing_and_syncs_nothing() {
        queue.counts.value = OutboxCounts(waiting = 1)
        var disconnected = 0
        var dismissed = 0
        showDialog(onConfirm = { disconnected++ }, onDismiss = { dismissed++ })

        compose.onNodeWithText(text(Res.string.action_cancel)).performClick()

        assertEquals(0, disconnected)
        assertEquals(1, dismissed)
        assertEquals(0, queue.syncs)
    }

    @Test
    fun sync_first_sends_the_watches_shows_progress_and_blocks_disconnecting_meanwhile() {
        queue.counts.value = OutboxCounts(waiting = 2)
        var disconnected = 0
        showDialog(onConfirm = { disconnected++ })

        compose.onNodeWithText(text(Res.string.settings_trakt_disconnect_sync_first)).performClick()

        assertEquals(1, queue.syncs)
        assertEquals(0, disconnected)
        compose.onNode(
            hasText(text(Res.string.settings_trakt_queue_syncing)) and
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        ).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.settings_trakt_disconnect_anyway)).assertIsNotEnabled()
    }

    @Test
    fun after_syncing_first_everything_sent_turns_the_warning_into_the_plain_question_with_the_result() {
        queue.counts.value = OutboxCounts(waiting = 2)
        var disconnected = 0
        showDialog(onConfirm = { disconnected++ })

        compose.runOnIdle {
            queue.counts.value = OutboxCounts()
            queue.syncState.value = QueueSyncState.Done(result(sent = 2))
        }

        compose.onNodeWithText(plural(Res.plurals.settings_trakt_queue_result_all_sent, 2)).assertIsDisplayed()
        compose.onNodeWithText(plural(Res.plurals.settings_trakt_disconnect_pending, 2)).assertDoesNotExist()
        compose.onNode(hasText(text(Res.string.settings_trakt_disconnect)) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, disconnected)
    }

    @Test
    fun after_syncing_first_what_is_left_keeps_the_warning_with_the_new_number_and_the_reason() {
        queue.counts.value = OutboxCounts(waiting = 3)
        showDialog()

        compose.runOnIdle {
            queue.counts.value = OutboxCounts(waiting = 2)
            queue.syncState.value = QueueSyncState.Done(result(sent = 1, stillWaiting = 2, offline = true))
        }

        compose.onNodeWithText(plural(Res.plurals.settings_trakt_disconnect_pending, 2)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.settings_trakt_queue_result_offline)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.settings_trakt_disconnect_sync_first)).assertIsEnabled()
        compose.onNodeWithText(text(Res.string.settings_trakt_disconnect_anyway)).assertIsEnabled()
    }

    @Test
    fun another_service_never_gets_the_trakt_warning() {
        queue.counts.value = OutboxCounts(waiting = 3)
        compose.setContent {
            NuvioTheme {
                TrackingDisconnectDialog(brand = TrackingBrand.SIMKL, onConfirm = {}, onDismiss = {}, queue = queue)
            }
        }

        compose.onNodeWithText(plural(Res.plurals.settings_trakt_disconnect_pending, 3)).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.settings_trakt_disconnect_sync_first)).assertDoesNotExist()
    }

    private var shown by mutableStateOf(true)

    private fun showSection() {
        compose.setContent {
            NuvioTheme {
                if (shown) TrackerQueueSection(queue = queue)
            }
        }
    }

    private fun showDialog(onConfirm: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        compose.setContent {
            NuvioTheme {
                TrackingDisconnectDialog(brand = TrackingBrand.TRAKT, onConfirm = onConfirm, onDismiss = onDismiss, queue = queue)
            }
        }
    }

    private fun text(resource: StringResource, vararg arguments: Any): String =
        runBlocking { getString(resource, *arguments) }

    private fun plural(resource: org.jetbrains.compose.resources.PluralStringResource, quantity: Int): String =
        runBlocking { getPluralString(resource, quantity, quantity) }
}
