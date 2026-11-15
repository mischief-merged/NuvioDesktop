package com.nuvio.app.features.downloads

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_auto_check_now
import nuvio.composeapp.generated.resources.downloads_auto_checking_toast
import nuvio.composeapp.generated.resources.downloads_auto_keep_option
import nuvio.composeapp.generated.resources.downloads_auto_rule_summary
import nuvio.composeapp.generated.resources.downloads_auto_section_title
import nuvio.composeapp.generated.resources.downloads_auto_status_checking
import nuvio.composeapp.generated.resources.downloads_auto_status_could_not_check
import nuvio.composeapp.generated.resources.downloads_auto_status_downloading
import nuvio.composeapp.generated.resources.downloads_auto_status_failed
import nuvio.composeapp.generated.resources.downloads_auto_status_no_source
import nuvio.composeapp.generated.resources.downloads_auto_status_not_checked
import nuvio.composeapp.generated.resources.downloads_auto_status_paused
import nuvio.composeapp.generated.resources.downloads_auto_status_private_sources
import nuvio.composeapp.generated.resources.downloads_auto_status_pending
import nuvio.composeapp.generated.resources.downloads_auto_status_stopped
import nuvio.composeapp.generated.resources.downloads_auto_status_up_to_date
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AutoDownloadSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private class FakeController : AutoDownloadController {
        override val rules = MutableStateFlow(emptyList<AutoDownloadRule>())
        override val statuses = MutableStateFlow(emptyMap<String, AutoDownloadStatus>())
        var checks = 0

        override fun save(profileId: Int, meta: MetaDetails, keepCount: Int, scope: AutoDownloadScope) = Unit

        override fun turnOff(profileId: Int, ruleKey: String) = Unit

        override fun checkNow() {
            checks++
        }
    }

    private val controller = FakeController()
    private val rule = testRule(keepCount = 5)

    private val statusTexts = mapOf(
        AutoDownloadStatus.NotChecked to Res.string.downloads_auto_status_not_checked,
        AutoDownloadStatus.Checking to Res.string.downloads_auto_status_checking,
        AutoDownloadStatus.UpToDate to Res.string.downloads_auto_status_up_to_date,
        AutoDownloadStatus.Pending to Res.string.downloads_auto_status_pending,
        AutoDownloadStatus.Downloading to Res.string.downloads_auto_status_downloading,
        AutoDownloadStatus.Paused to Res.string.downloads_auto_status_paused,
        AutoDownloadStatus.CouldNotCheck to Res.string.downloads_auto_status_could_not_check,
        AutoDownloadStatus.NoSource to Res.string.downloads_auto_status_no_source,
        AutoDownloadStatus.PrivateSourcesSkipped to Res.string.downloads_auto_status_private_sources,
        AutoDownloadStatus.Failed to Res.string.downloads_auto_status_failed,
        AutoDownloadStatus.Stopped to Res.string.downloads_auto_status_stopped,
    )

    @Test
    fun nothing_is_shown_without_a_rule() {
        show()

        compose.onNodeWithText(text(Res.string.downloads_auto_section_title)).assertDoesNotExist()
    }

    @Test
    fun each_rule_is_listed_with_its_show_and_count_under_a_heading() {
        controller.rules.value = listOf(rule)
        show()

        compose.onNode(
            hasText(text(Res.string.downloads_auto_section_title)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        ).assertIsDisplayed()
        val count = runBlocking { getPluralString(Res.plurals.downloads_auto_keep_option, 5, 5) }
        compose.onNodeWithText(text(Res.string.downloads_auto_rule_summary, "Show", count)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_auto_status_not_checked)).assertIsDisplayed()
    }

    @Test
    fun every_status_is_shown_as_its_own_text() {
        controller.rules.value = listOf(rule)
        show()
        assertEquals(AutoDownloadStatus.entries.toSet(), statusTexts.keys)
        assertEquals(statusTexts.size, statusTexts.values.map { text(it) }.toSet().size, "two statuses share a text")

        statusTexts.forEach { (status, resource) ->
            compose.runOnIdle { controller.statuses.value = mapOf(rule.key to status) }

            compose.onNodeWithText(text(resource)).assertIsDisplayed()
        }
    }

    @Test
    fun check_now_starts_a_check_and_says_so() {
        controller.rules.value = listOf(rule)
        show()

        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_check_now))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .requestFocus()
            .performKeyInput { pressKey(Key.Enter) }

        assertEquals(1, controller.checks)
        assertEquals(text(Res.string.downloads_auto_checking_toast), NuvioToastController.currentToast.value?.message)
    }

    private fun show() {
        compose.setContent {
            NuvioTheme {
                AutoDownloadSection(controller = controller)
            }
        }
    }

    private fun text(resource: StringResource, vararg arguments: Any): String =
        runBlocking { getString(resource, *arguments) }
}
