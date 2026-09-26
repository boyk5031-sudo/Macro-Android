package com.macroandroid.feature.trigger.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.macroandroid.automation.trigger.TriggerId
import kotlinx.serialization.Serializable

@Serializable
data object TriggersDestination

/** Argument name matches [com.macroandroid.feature.trigger.presentation.TriggerEditorViewModel.ARG_TRIGGER_ID]. */
@Serializable
data class TriggerEditorDestination(val triggerId: String? = null)

fun NavController.navigateToTriggers() = navigate(TriggersDestination)
fun NavController.navigateToTriggerEditor(id: TriggerId? = null) = navigate(TriggerEditorDestination(id?.value))

fun NavGraphBuilder.triggerGraph(
    onOpenEditor: (TriggerId?) -> Unit,
    onOpenDisclosure: () -> Unit,
    onBack: () -> Unit,
) {
    composable<TriggersDestination> {
        TriggerListRoute(onOpenEditor = onOpenEditor, onOpenDisclosure = onOpenDisclosure, onBack = onBack)
    }
    composable<TriggerEditorDestination> {
        TriggerEditorRoute(onOpenDisclosure = onOpenDisclosure, onBack = onBack)
    }
}
