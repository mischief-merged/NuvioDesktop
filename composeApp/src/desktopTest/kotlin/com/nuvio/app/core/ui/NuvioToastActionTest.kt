package com.nuvio.app.core.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class NuvioToastActionTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun clear() {
        NuvioToastController.dismiss()
    }

    @Test
    fun a_toast_with_an_action_shows_a_named_button_that_runs_the_action_and_closes_the_toast() {
        var runs = 0
        compose.setContent { NuvioTheme { NuvioToastHost() } }

        compose.runOnIdle { NuvioToastController.show("It will be deleted", durationMillis = 60_000L, actionLabel = "Keep", onAction = { runs++ }) }

        compose.onNodeWithText("It will be deleted").assertIsDisplayed()
        compose.onNodeWithText("Keep")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
            .performClick()
        compose.waitForIdle()

        assertEquals(1, runs)
        assertNull(NuvioToastController.currentToast.value)
    }

    @Test
    fun a_toast_with_an_action_is_announced_when_it_appears() {
        compose.setContent { NuvioTheme { NuvioToastHost() } }

        compose.runOnIdle { NuvioToastController.show("It will be deleted", durationMillis = 60_000L, actionLabel = "Keep", onAction = {}) }

        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assertIsDisplayed()
    }

    @Test
    fun a_plain_toast_has_no_button() {
        compose.setContent { NuvioTheme { NuvioToastHost() } }

        compose.runOnIdle { NuvioToastController.show("Saved", durationMillis = 60_000L) }

        compose.onNodeWithText("Saved").assertIsDisplayed()
        compose.onNodeWithText("Keep").assertDoesNotExist()
    }

    @Test
    fun an_action_without_a_label_or_a_label_without_an_action_is_dropped() {
        NuvioToastController.show("A", actionLabel = "Keep", onAction = null)
        assertNull(NuvioToastController.currentToast.value?.actionLabel)
        NuvioToastController.show("B", actionLabel = null, onAction = {})
        assertNull(NuvioToastController.currentToast.value?.onAction)
    }
}
