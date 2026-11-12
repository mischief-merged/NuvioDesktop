package com.nuvio.app.features.downloads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
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
import nuvio.composeapp.generated.resources.downloads_auto_status_low_disk_space
import nuvio.composeapp.generated.resources.downloads_auto_status_storage_limit
import nuvio.composeapp.generated.resources.downloads_auto_status_up_to_date
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit_description
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit_none
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit_value
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
        override val storageLimitGb = MutableStateFlow<Int?>(50)
        var checks = 0
        val limitsSet = mutableListOf<Pair<Int, Int?>>()

        override fun setStorageLimit(profileId: Int, limitGb: Int?) {
            limitsSet += profileId to limitGb
        }

        override fun save(profileId: Int, meta: MetaDetails, keepCount: Int, scope: AutoDownloadScope) = Unit

        override fun turnOff(profileId: Int, ruleKey: String) = Unit

        override fun checkNow() {
            checks++
        }
    }

    private val controller = FakeController()
    private val profile = 9_900_007
    private val otherProfile = 9_900_008
    private var shownProfile by mutableIntStateOf(profile)
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
        AutoDownloadStatus.StorageLimitReached to Res.string.downloads_auto_status_storage_limit,
        AutoDownloadStatus.LowDiskSpace to Res.string.downloads_auto_status_low_disk_space,
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

    @Test
    fun the_storage_limit_is_shown_with_its_description_and_current_value() {
        controller.rules.value = listOf(rule)
        controller.storageLimitGb.value = 50
        show()

        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit_description)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit_value, 50)).assertIsDisplayed()
    }

    @Test
    fun the_storage_limit_is_a_button_named_by_its_title_with_the_limit_as_state() {
        controller.rules.value = listOf(rule)
        show()

        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_storage_limit))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(Res.string.downloads_auto_storage_limit_value, 50)))
            .assertHasClickAction()
    }

    @Test
    fun the_limit_is_chosen_with_the_keyboard_from_named_radio_buttons_of_one_group() {
        controller.rules.value = listOf(rule)
        show()
        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_storage_limit))
            .requestFocus()
            .performKeyInput { pressKey(Key.Enter) }
        val radioButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

        (listOf(text(Res.string.downloads_auto_storage_limit_none)) + listOf(10, 25, 50, 100, 250).map { text(Res.string.downloads_auto_storage_limit_value, it) })
            .forEach { name ->
                compose.onNode(hasContentDescription(name) and radioButton)
                    .assert(hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)))
            }
        compose.onNode(hasContentDescription(text(Res.string.downloads_auto_storage_limit_value, 250)) and radioButton)
            .requestFocus()
            .performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf<Pair<Int, Int?>>(profile to 250), controller.limitsSet)
    }

    @Test
    fun no_limit_is_shown_in_words() {
        controller.rules.value = listOf(rule)
        controller.storageLimitGb.value = null
        show()

        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit_none)).assertIsDisplayed()
    }

    @Test
    fun the_picker_offers_every_limit_and_saves_the_chosen_one_for_the_profile_it_was_opened_for() {
        controller.rules.value = listOf(rule)
        show()

        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit)).performClick()
        compose.onNode(hasText(text(Res.string.downloads_auto_storage_limit_value, 50)) and hasAnyAncestor(isDialog())).assertIsSelected()
        listOf(10, 25, 100, 250).forEach {
            compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit_value, it)).assertExists()
        }
        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit_none)).assertExists()
        compose.runOnIdle { shownProfile = otherProfile }
        compose.waitForIdle()
        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit_value, 100)).performClick()

        assertEquals(listOf<Pair<Int, Int?>>(profile to 100), controller.limitsSet)
    }

    @Test
    fun choosing_no_limit_saves_no_limit() {
        controller.rules.value = listOf(rule)
        show()

        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit)).performClick()
        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit_none)).performClick()

        assertEquals(listOf<Pair<Int, Int?>>(profile to null), controller.limitsSet)
    }

    private fun show() {
        compose.setContent {
            NuvioTheme {
                AutoDownloadSection(controller = controller, profileId = shownProfile)
            }
        }
    }

    private fun text(resource: StringResource, vararg arguments: Any): String =
        runBlocking { getString(resource, *arguments) }
}
