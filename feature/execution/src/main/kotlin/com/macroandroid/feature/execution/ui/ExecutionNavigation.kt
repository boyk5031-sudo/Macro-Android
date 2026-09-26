package com.macroandroid.feature.execution.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

@Serializable
data object ExecutionsDestination

/** Argument name matches [ExecutionDetailViewModel.ARG_ID]; deep link used by execution notifications. */
@Serializable
data class ExecutionDetailDestination(val executionId: String)

const val EXECUTION_DEEP_LINK = "macroandroid://execution/{executionId}"

fun NavController.navigateToExecution(id: String) = navigate(ExecutionDetailDestination(id))

fun NavGraphBuilder.executionGraph(
    onOpenDetail: (String) -> Unit,
    onOpenMacro: (String) -> Unit,
    onBack: () -> Unit,
) {
    composable<ExecutionsDestination> { ExecutionsRoute(onOpenDetail = onOpenDetail) }
    composable<ExecutionDetailDestination>(
        deepLinks = listOf(navDeepLink<ExecutionDetailDestination>(basePath = "macroandroid://execution")),
    ) { entry ->
        entry.toRoute<ExecutionDetailDestination>()
        ExecutionDetailRoute(onBack = onBack, onOpenMacro = onOpenMacro, onOpenExecution = onOpenDetail)
    }
}
