package com.nuvio.app

import androidx.compose.runtime.Composable
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsScreen
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.DownloadShowRoute
import com.nuvio.app.navigation.DownloadsRoute
import com.nuvio.app.navigation.NuvioNavigator

@Composable
internal fun DownloadsDestination(
    route: DownloadsRoute,
    navController: NuvioNavigator,
    useNativeNavigation: Boolean,
    onOpenDownload: (DownloadItem) -> Unit,
    onChooseAnotherSource: ((DownloadItem) -> Unit)? = null,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    DownloadsScreen(
        onBack = onBack,
        onOpenDownload = onOpenDownload,
        onChooseAnotherSource = onChooseAnotherSource,
        onOpenShowDetails = { type, id, title -> navController.navigate(DetailRoute(type = type, id = id, title = title)) },
        onNavigateToShow = if (useNativeNavigation) {
            { showId, title -> navController.navigate(DownloadShowRoute(showId, title)) }
        } else {
            null
        },
    )
}

@Composable
internal fun DownloadShowDestination(
    route: DownloadShowRoute,
    navController: NuvioNavigator,
    onOpenDownload: (DownloadItem) -> Unit,
    onChooseAnotherSource: ((DownloadItem) -> Unit)? = null,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    DownloadsScreen(
        onBack = onBack,
        onOpenDownload = onOpenDownload,
        onChooseAnotherSource = onChooseAnotherSource,
        initialShowId = route.showId,
        onBackFromShow = onBack,
    )
}
