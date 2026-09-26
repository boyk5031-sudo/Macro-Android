package com.macroandroid.feature.macros.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.macroandroid.automation.model.MacroId
import com.macroandroid.feature.macros.ui.editor.MacroEditorRoute
import kotlinx.serialization.Serializable

@Serializable
data object MacrosDestination

/** `macroId == null` creates a new macro. The argument name matches [MacroEditorViewModel.ARG_ID]. */
@Serializable
data class MacroEditorDestination(val macroId: String? = null)

@Serializable
data class MacroPreviewDestination(val macroId: String)

fun NavController.navigateToMacroEditor(id: MacroId?) = navigate(MacroEditorDestination(id?.value))
fun NavController.navigateToMacroPreview(id: MacroId) = navigate(MacroPreviewDestination(id.value))

fun NavGraphBuilder.macrosGraph(
    onOpenEditor: (MacroId?) -> Unit,
    onOpenPreview: (MacroId) -> Unit,
    onOpenExecution: (String) -> Unit,
    onOpenSchedules: (MacroId) -> Unit,
    onBack: () -> Unit,
) {
    composable<MacrosDestination> {
        MacroListRoute(
            onOpenEditor = onOpenEditor,
            onOpenPreview = onOpenPreview,
            onOpenExecution = onOpenExecution,
            onOpenSchedules = onOpenSchedules,
        )
    }
    composable<MacroEditorDestination> { entry ->
        entry.toRoute<MacroEditorDestination>()
        MacroEditorRoute(onBack = onBack, onOpenPreview = onOpenPreview, onOpenExecution = onOpenExecution)
    }
    composable<MacroPreviewDestination> { entry ->
        entry.toRoute<MacroPreviewDestination>()
        MacroPreviewRoute(onBack = onBack)
    }
}
