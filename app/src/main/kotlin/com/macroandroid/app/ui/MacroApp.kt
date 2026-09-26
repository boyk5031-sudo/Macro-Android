package com.macroandroid.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import com.macroandroid.app.BuildConfig
import com.macroandroid.app.R
import com.macroandroid.automation.model.MacroId
import com.macroandroid.core.ui.component.ConfirmDialog
import com.macroandroid.core.ui.error.ErrorMessages
import com.macroandroid.feature.apkimport.ui.ApkListDestination
import com.macroandroid.feature.apkimport.ui.apkGraph
import com.macroandroid.feature.apkimport.ui.navigateToApkDetail
import com.macroandroid.feature.apps.ui.AppsDestination
import com.macroandroid.feature.apps.ui.appsGraph
import com.macroandroid.feature.apps.ui.navigateToAppDetail
import com.macroandroid.feature.execution.ui.executionGraph
import com.macroandroid.feature.execution.ui.navigateToExecution
import com.macroandroid.feature.macros.ui.macrosGraph
import com.macroandroid.feature.macros.ui.navigateToMacroEditor
import com.macroandroid.feature.macros.ui.navigateToMacroPreview
import com.macroandroid.feature.scheduling.ui.navigateToScheduleEditor
import com.macroandroid.feature.scheduling.ui.navigateToSchedules
import com.macroandroid.feature.scheduling.ui.schedulingGraph
import com.macroandroid.feature.settings.ui.AppVersion
import com.macroandroid.feature.settings.ui.HelpTopics
import com.macroandroid.feature.settings.ui.OnboardingDestination
import com.macroandroid.feature.settings.ui.navigateToHelp
import com.macroandroid.feature.settings.ui.settingsGraph

@Composable
fun MacroApp(appState: MacroAppState, mainState: MainUiState, viewModel: MainViewModel) {
    val navController = appState.navController
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val pendingRun by viewModel.pendingRun.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { e ->
            when (e) {
                is MainNavEvent.OpenExecution -> navController.navigateToExecution(e.executionId)
                MainNavEvent.NewMacro -> navController.navigateToMacroEditor(null)
                MainNavEvent.OpenRuns -> appState.navigateToTopLevel(TopLevelDestination.RUNS)
                is MainNavEvent.Error -> snackbar.showSnackbar(context.getString(ErrorMessages.titleRes(e.error)))
            }
        }
    }

    val current = appState.currentTopLevel
    val showBars = current != null && mainState.onboardingCompleted
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            if (showBars) {
                TopLevelDestination.entries.forEach { dest ->
                    item(
                        selected = current == dest,
                        onClick = { appState.navigateToTopLevel(dest) },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(stringResource(dest.label)) },
                    )
                }
            }
        },
    ) {
        MacroNavHost(appState, mainState, Modifier.fillMaxSize())
        SnackbarHost(snackbar)
    }

    pendingRun?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.run_confirm_title, p.macroName),
            text = stringResource(R.string.run_confirm_body),
            confirmLabel = stringResource(R.string.run_confirm_run),
            dismissLabel = stringResource(android.R.string.cancel),
            onConfirm = viewModel::confirmPendingRun,
            onDismiss = viewModel::dismissPendingRun,
        )
    }
}

@Composable
private fun MacroNavHost(appState: MacroAppState, mainState: MainUiState, modifier: Modifier = Modifier) {
    val navController = appState.navController
    val back: () -> Unit = appState::back
    NavHost(
        navController = navController,
        startDestination = if (mainState.onboardingCompleted) AppsDestination else OnboardingDestination,
        modifier = modifier,
    ) {
        appsGraph(
            onOpenDetail = navController::navigateToAppDetail,
            onOpenHelp = { navController.navigateToHelp(HelpTopics.PACKAGE_VISIBILITY) },
            onOpenApkFiles = { navController.navigate(ApkListDestination) },
            onBack = back,
        )
        apkGraph(
            onOpenDetail = navController::navigateToApkDetail,
            onOpenHelpInstall = { navController.navigateToHelp(HelpTopics.INSTALL) },
            onBack = back,
        )
        macrosGraph(
            onOpenEditor = navController::navigateToMacroEditor,
            onOpenPreview = navController::navigateToMacroPreview,
            onOpenExecution = navController::navigateToExecution,
            onOpenSchedules = { id: MacroId -> navController.navigateToSchedules(id) },
            onBack = back,
        )
        executionGraph(
            onOpenDetail = navController::navigateToExecution,
            onOpenMacro = { navController.navigateToMacroEditor(MacroId(it)) },
            onBack = back,
        )
        schedulingGraph(
            onOpenEditor = { s, m -> navController.navigateToScheduleEditor(s, m) },
            onBack = back,
        )
        settingsGraph(
            navController = navController,
            version = AppVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()),
            onBack = back,
            onOnboardingDone = {
                navController.navigate(AppsDestination) { popUpTo(OnboardingDestination) { inclusive = true } }
            },
            settingsHasBack = false,
        )
    }
}
