package com.macroandroid.feature.scheduling.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.ScheduleId
import kotlinx.serialization.Serializable

/** Argument names match [SchedulesViewModel.ARG_MACRO_ID] / [ScheduleEditorViewModel.ARG_*]. */
@Serializable
data class SchedulesDestination(val macroId: String? = null)

@Serializable
data class ScheduleEditorDestination(val scheduleId: String? = null, val macroId: String? = null)

fun NavController.navigateToSchedules(macroId: MacroId? = null) = navigate(SchedulesDestination(macroId?.value))
fun NavController.navigateToScheduleEditor(scheduleId: ScheduleId? = null, macroId: MacroId? = null) =
    navigate(ScheduleEditorDestination(scheduleId?.value, macroId?.value))

fun NavGraphBuilder.schedulingGraph(
    onOpenEditor: (ScheduleId?, MacroId?) -> Unit,
    onBack: () -> Unit,
    listHasBack: Boolean,
) {
    composable<SchedulesDestination> {
        SchedulesRoute(onOpenEditor = onOpenEditor, onBack = if (listHasBack) onBack else null)
    }
    composable<ScheduleEditorDestination> {
        ScheduleEditorRoute(onBack = onBack)
    }
}
