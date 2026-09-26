package com.macroandroid.feature.apkimport.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object ApkListDestination

@Serializable
data class ApkDetailDestination(val apkId: String)

fun NavController.navigateToApkDetail(id: String) = navigate(ApkDetailDestination(id))

fun NavGraphBuilder.apkGraph(onOpenDetail: (String) -> Unit, onOpenHelpInstall: () -> Unit, onBack: () -> Unit) {
    composable<ApkListDestination> { ApkListRoute(onOpenDetail = onOpenDetail) }
    composable<ApkDetailDestination> { ApkDetailRoute(onBack = onBack, onOpenHelpInstall = onOpenHelpInstall) }
}
