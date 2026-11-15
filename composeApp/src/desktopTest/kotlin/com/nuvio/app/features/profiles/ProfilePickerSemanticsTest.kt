package com.nuvio.app.features.profiles

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.compose_profile_add_profile
import nuvio.composeapp.generated.resources.profile_card_edit_label
import nuvio.composeapp.generated.resources.profile_card_locked_label
import nuvio.composeapp.generated.resources.profile_manage_profiles
import nuvio.composeapp.generated.resources.profile_manage_state_off
import nuvio.composeapp.generated.resources.profile_manage_state_on
import nuvio.composeapp.generated.resources.profile_picker_empty_guidance
import nuvio.composeapp.generated.resources.profile_who_is_watching
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfilePickerSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    private var addClicks = 0
    private val selected = mutableListOf<Int>()
    private val edited = mutableListOf<Int>()

    private fun text(resource: StringResource, vararg args: Any): String =
        runBlocking { getString(resource, *args) }

    private fun profile(index: Int, name: String, pin: Boolean = false) =
        NuvioProfile(profileIndex = index, name = name, pinEnabled = pin)

    private fun setPicker(profiles: List<NuvioProfile>, isLoaded: Boolean = true) {
        compose.setContent {
            MaterialTheme {
                var isEditMode by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxSize()) {
                    ProfilePickerBody(
                        profiles = profiles,
                        isLoaded = isLoaded,
                        isTabletLayout = true,
                        interactionEnabled = true,
                        isEditMode = isEditMode,
                        onEditModeChange = { isEditMode = it },
                        titleAlpha = { 1f },
                        titleOffset = { 0f },
                        manageAlpha = { 1f },
                        onProfileClick = { profile ->
                            if (isEditMode) edited += profile.profileIndex else selected += profile.profileIndex
                        },
                        onAddProfile = { addClicks++ },
                        onHoverChange = { _, _ -> },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun titleIsAHeading() {
        setPicker(listOf(profile(1, "Ada")))

        compose.onNodeWithText(text(Res.string.profile_who_is_watching))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun profileCardIsAButtonLabelledWithTheName() {
        setPicker(listOf(profile(1, "Ada"), profile(2, "Grace")))

        compose.onNodeWithContentDescription("Ada").assert(hasRole(Role.Button)).assertIsEnabled()
        compose.onNodeWithContentDescription("Grace").assert(hasRole(Role.Button))
    }

    @Test
    fun lockedProfileSaysItIsLockedWithAPin() {
        setPicker(listOf(profile(1, "Ada", pin = true)))

        compose.onNodeWithContentDescription(text(Res.string.profile_card_locked_label, "Ada"))
            .assert(hasRole(Role.Button))
    }

    @Test
    fun clickingAProfileSelectsIt() {
        setPicker(listOf(profile(1, "Ada"), profile(2, "Grace")))

        compose.onNodeWithContentDescription("Grace").performClick()

        assertEquals(listOf(2), selected)
    }

    @Test
    fun manageProfilesIsAButtonAndExposesEditModeState() {
        setPicker(listOf(profile(1, "Ada")))
        val manage = text(Res.string.profile_manage_profiles)

        compose.onNodeWithText(manage)
            .assert(hasRole(Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(Res.string.profile_manage_state_off)))
        compose.onAllNodesWithContentDescription(text(Res.string.compose_profile_add_profile)).assertCountEquals(0)

        compose.onNodeWithText(manage).performClick()

        compose.onNodeWithText(text(Res.string.action_done))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(Res.string.profile_manage_state_on)))
        compose.onNodeWithContentDescription(text(Res.string.compose_profile_add_profile)).assert(hasRole(Role.Button))
        compose.onNodeWithContentDescription(text(Res.string.profile_card_edit_label, "Ada")).assert(hasRole(Role.Button))
    }

    @Test
    fun emptyPickerOffersAddProfileAndGuidanceWithoutEditMode() {
        setPicker(emptyList())

        compose.onNodeWithText(text(Res.string.profile_who_is_watching))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText(text(Res.string.profile_picker_empty_guidance)).assertExists()
        compose.onNodeWithContentDescription(text(Res.string.compose_profile_add_profile))
            .assert(hasRole(Role.Button))
            .performClick()
        assertEquals(1, addClicks)
        compose.onAllNodesWithContentDescription(text(Res.string.profile_manage_profiles)).assertCountEquals(0)
        compose.onAllNodesWithText(text(Res.string.profile_manage_profiles)).assertCountEquals(0)
    }

    @Test
    fun emptyPickerBeforeLoadingShowsNoGuidance() {
        setPicker(emptyList(), isLoaded = false)

        compose.onAllNodesWithText(text(Res.string.profile_picker_empty_guidance)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(text(Res.string.compose_profile_add_profile)).assertCountEquals(0)
    }

    @Test
    fun addProfileCanBeActivatedWithEnterAndSpace() {
        setPicker(emptyList())
        val add = compose.onNodeWithContentDescription(text(Res.string.compose_profile_add_profile))

        add.requestFocus()
        add.performKeyInput { pressKey(Key.Enter) }
        add.performKeyInput { pressKey(Key.Spacebar) }

        assertEquals(2, addClicks)
    }

    @Test
    fun profileCardCanBeActivatedWithTheKeyboard() {
        setPicker(listOf(profile(1, "Ada")))
        val card = compose.onNode(hasContentDescription("Ada"))

        card.requestFocus()
        card.performKeyInput { pressKey(Key.Enter) }
        card.performKeyInput { pressKey(Key.Spacebar) }

        assertEquals(listOf(1, 1), selected)
    }
}
