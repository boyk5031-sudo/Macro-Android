package com.macroandroid.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.macroandroid.app.R
import com.macroandroid.feature.apps.ui.AppsDestination
import com.macroandroid.feature.execution.ui.ExecutionsDestination
import com.macroandroid.feature.macros.ui.MacrosDestination
import com.macroandroid.feature.scheduling.ui.SchedulesDestination
import com.macroandroid.feature.settings.ui.SettingsDestination
import kotlin.reflect.KClass

enum class TopLevelDestination(val route: Any, val routeClass: KClass<*>, val icon: ImageVector, val label: Int) {
    APPS(AppsDestination, AppsDestination::class, Icons.Outlined.Apps, R.string.nav_apps),
    MACROS(MacrosDestination, MacrosDestination::class, Icons.Outlined.AutoAwesome, R.string.nav_macros),
    RUNS(ExecutionsDestination, ExecutionsDestination::class, Icons.Outlined.History, R.string.nav_runs),
    SCHEDULES(SchedulesDestination(), SchedulesDestination::class, Icons.Outlined.Schedule, R.string.nav_schedules),
    SETTINGS(SettingsDestination, SettingsDestination::class, Icons.Outlined.Settings, R.string.nav_settings),
}

@Stable
class MacroAppState(val navController: NavHostController) {
    val currentDestination: NavDestination?
        @Composable get() = navController.currentBackStackEntryAsState().value?.destination

    val currentTopLevel: TopLevelDestination?
        @Composable get() {
            val dest = currentDestination ?: return null
            return TopLevelDestination.entries.firstOrNull { top -> dest.hierarchy.any { it.hasRoute(top.routeClass) } }
        }

    fun navigateToTopLevel(destination: TopLevelDestination) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun back() {
        navController.popBackStack()
    }
}

@Composable
fun rememberMacroAppState(navController: NavHostController = rememberNavController()): MacroAppState =
    remember(navController) { MacroAppState(navController) }
