package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.build.AppVersionPolicy
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_about_based_on_version_format
import nuvio.composeapp.generated.resources.compose_about_made_with
import nuvio.composeapp.generated.resources.compose_about_version_format
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingsAttribution(
    isTablet: Boolean,
    modifier: Modifier = Modifier,
) {
    ForkAttribution(isTablet = isTablet, modifier = modifier)
}
