package com.nuvio.app.features.player

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Makes the show name or the episode text of the player a button that leaves the player and opens
 * the show or the episode. Without [onClick] the text is left exactly as it is. The label says where
 * the button leads, and the underline shows that it is one without needing a pointer.
 */
internal fun Modifier.playerTitleLink(onClick: (() -> Unit)?, label: String): Modifier =
    if (onClick == null) {
        this
    } else {
        this
            .clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
    }

internal fun playerTitleLinkDecoration(onClick: (() -> Unit)?): TextDecoration? =
    if (onClick == null) null else TextDecoration.Underline
