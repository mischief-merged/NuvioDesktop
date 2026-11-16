package com.nuvio.app.features.downloads

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.profile_switch_blocked_by_move
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileSwitchDuringMoveTest {
    @Test
    fun switching_profile_while_a_move_runs_is_refused_with_a_plain_message_and_changes_nothing() {
        val before = ProfileRepository.activeProfileId
        val moving = object : DownloadFolderController by NoDownloadFolders {
            override val state: StateFlow<DownloadFolderState> = MutableStateFlow(DownloadFolderState(moving = true))
        }
        val previous = DownloadFolders.controller
        DownloadFolders.controller = moving
        try {
            runBlocking { ProfileRepository.switchToProfile(before + 5) }
        } finally {
            DownloadFolders.controller = previous
        }

        assertEquals(before, ProfileRepository.activeProfileId)
        assertEquals(runBlocking { getString(Res.string.profile_switch_blocked_by_move) }, NuvioToastController.currentToast.value?.message)
    }
}
