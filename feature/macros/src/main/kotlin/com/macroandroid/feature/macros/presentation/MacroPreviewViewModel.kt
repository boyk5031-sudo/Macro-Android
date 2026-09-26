package com.macroandroid.feature.macros.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroId
import com.macroandroid.core.database.repository.MacroRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MacroPreviewViewModel @Inject constructor(
    savedState: SavedStateHandle,
    macros: MacroRepository,
) : ViewModel() {
    private val id = MacroId(checkNotNull(savedState[MacroEditorViewModel.ARG_ID]) { "macroId missing" })

    val macro: StateFlow<Macro?> = macros.observe(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val loaded: StateFlow<Boolean> = macros.observe(id).map { true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
