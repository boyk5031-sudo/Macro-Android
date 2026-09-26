package com.macroandroid.feature.apkimport.presentation

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.feature.apkimport.data.ApkRepository
import com.macroandroid.feature.apkimport.domain.ApkImportOutcome
import com.macroandroid.feature.apkimport.domain.ImportedApk
import com.macroandroid.feature.apkimport.domain.InstalledState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ApkDetailUiState {
    data object Loading : ApkDetailUiState
    data object NotFound : ApkDetailUiState
    data class Ready(val apk: ImportedApk, val installedState: InstalledState?, val busy: Boolean) : ApkDetailUiState
}

sealed interface ApkDetailEvent {
    data class ChecksumVerified(val matches: Boolean) : ApkDetailEvent
    data class Error(val error: AppError) : ApkDetailEvent
    data object Relinked : ApkDetailEvent
    data object Deleted : ApkDetailEvent
}

@HiltViewModel
class ApkDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: ApkRepository,
) : ViewModel() {
    val apkId: String = checkNotNull(savedState[ARG_ID])
    private val busy = MutableStateFlow(false)
    private val _events = MutableSharedFlow<ApkDetailEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ApkDetailEvent> = _events.asSharedFlow()

    val uiState: StateFlow<ApkDetailUiState> = combine(repository.observe(apkId), busy) { apk, b ->
        if (apk == null) ApkDetailUiState.NotFound else ApkDetailUiState.Ready(apk, repository.installedState(apk), b)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ApkDetailUiState.Loading)

    fun reverify() = viewModelScope.launch {
        busy.value = true
        try {
            when (val r = repository.reverify(apkId)) {
                is AppResult.Ok -> _events.emit(ApkDetailEvent.ChecksumVerified(r.value))
                is AppResult.Err -> _events.emit(ApkDetailEvent.Error(r.error))
            }
        } finally {
            busy.value = false
        }
    }

    /** FR-APK-5 "Re-select file": re-imports and relinks when the SHA-256 matches. */
    fun relink(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            busy.value = true
            try {
                val outcome = repository.import(listOf(uri), relinkId = apkId).first()
                when (outcome) {
                    is ApkImportOutcome.Imported -> _events.emit(ApkDetailEvent.Relinked)
                    is ApkImportOutcome.Invalid -> _events.emit(ApkDetailEvent.Error(AppError(outcome.apk.errorCode ?: return@launch)))
                    is ApkImportOutcome.Rejected -> _events.emit(ApkDetailEvent.Error(AppError(outcome.code)))
                }
            } finally {
                busy.value = false
            }
        }
    }

    fun setNotes(notes: String) = viewModelScope.launch { repository.setNotes(apkId, notes) }

    fun shareIntent(apk: ImportedApk): Intent = repository.shareIntent(apk)

    fun delete() = viewModelScope.launch {
        repository.delete(apkId)
        _events.emit(ApkDetailEvent.Deleted)
    }

    companion object {
        const val ARG_ID = "apkId"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
