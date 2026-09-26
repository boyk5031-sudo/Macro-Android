package com.macroandroid.feature.apps.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

@Serializable
data object AppsDestination

@Serializable
data class AppDetailDestination(val packageName: String)

fun NavController.navigateToAppDetail(packageName: String) = navigate(AppDetailDestination(packageName))

fun NavGraphBuilder.appsGraph(
    onOpenDetail: (String) -> Unit,
    onOpenHelp: () -> Unit,
    onOpenApkFiles: () -> Unit,
    onBack: () -> Unit,
) {
    composable<AppsDestination> { AppsRoute(onOpenDetail = onOpenDetail, onOpenHelp = onOpenHelp, onOpenApkFiles = onOpenApkFiles) }
    composable<AppDetailDestination> { entry ->
        // packageName is exposed to the ViewModel through SavedStateHandle["packageName"].
        entry.toRoute<AppDetailDestination>()
        AppDetailRoute(onBack = onBack)
    }
}
