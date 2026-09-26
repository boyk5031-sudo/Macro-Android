package com.macroandroid.feature.settings.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

@Serializable
data object SettingsDestination

@Serializable
data object PermissionCenterDestination

@Serializable
data object DisclosureDestination

@Serializable
data object AuditLogDestination

@Serializable
data class HelpDestination(val topic: String? = null)

@Serializable
data object AboutDestination

@Serializable
data object OnboardingDestination

fun NavController.navigateToPermissionCenter() = navigate(PermissionCenterDestination)
fun NavController.navigateToDisclosure() = navigate(DisclosureDestination)
fun NavController.navigateToHelp(topic: String? = null) = navigate(HelpDestination(topic))

data class AppVersion(val name: String, val code: Long)

fun NavGraphBuilder.settingsGraph(
    navController: NavController,
    version: AppVersion,
    onBack: () -> Unit,
    onOnboardingDone: () -> Unit,
    settingsHasBack: Boolean,
) {
    composable<SettingsDestination> {
        SettingsRoute(
            onOpenPermissionCenter = { navController.navigate(PermissionCenterDestination) },
            onOpenAuditLog = { navController.navigate(AuditLogDestination) },
            onOpenHelp = { navController.navigate(HelpDestination()) },
            onOpenAbout = { navController.navigate(AboutDestination) },
            onBack = if (settingsHasBack) onBack else null,
        )
    }
    composable<PermissionCenterDestination> {
        PermissionCenterRoute(
            onOpenDisclosure = { navController.navigate(DisclosureDestination) },
            onOpenHelp = { navController.navigate(HelpDestination(HelpTopics.TROUBLESHOOTING)) },
            onBack = onBack,
        )
    }
    composable<DisclosureDestination> { DisclosureRoute(onBack = onBack) }
    composable<AuditLogDestination> { AuditLogRoute(onBack = onBack) }
    composable<HelpDestination> { entry -> HelpRoute(initialTopic = entry.toRoute<HelpDestination>().topic, onBack = onBack) }
    composable<AboutDestination> {
        AboutRoute(
            versionName = version.name,
            versionCode = version.code,
            onOpenHelp = { navController.navigate(HelpDestination()) },
            onBack = onBack,
        )
    }
    composable<OnboardingDestination> { OnboardingRoute(onDone = onOnboardingDone) }
}
