package com.macroandroid.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.core.database.repository.AuditEntry
import com.macroandroid.core.database.repository.ExecutionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** FR-SEC-2: read-only view of the append-only audit table. */
@HiltViewModel
class AuditLogViewModel @Inject constructor(executions: ExecutionRepository) : ViewModel() {
    val entries: StateFlow<ImmutableList<AuditEntry>?> = executions.observeAudit(LIMIT)
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val LIMIT = 1_000
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
