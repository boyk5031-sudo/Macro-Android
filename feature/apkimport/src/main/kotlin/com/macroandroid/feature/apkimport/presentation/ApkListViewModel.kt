package com.macroandroid.feature.apkimport.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.feature.apkimport.data.ApkRepository
import com.macroandroid.feature.apkimport.domain.ApkImportOutcome
import com.macroandroid.feature.apkimport.domain.ApkImportProgress
import com.macroandroid.feature.apkimport.domain.ImportedApk
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ApkSort { NAME, DATE, SIZE }

data class ApkListUiState(
    val apks: ImmutableList<ImportedApk>,
    val query: String,
    val sort: ApkSort,
    val importProgress: ApkImportProgress?,
    val loaded: Boolean,
)

sealed interface ApkListEvent {
    data class ImportFinished(val imported: Int, val invalid: Int, val rejected: List<Pair<String, ErrorCode>>) : ApkListEvent
    data class HighlightExisting(val id: String) : ApkListEvent
    data class Deleted(val name: String) : ApkListEvent
}

@HiltViewModel
class ApkListViewModel @Inject constructor(private val repository: ApkRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(ApkSort.DATE)
    private val progress = MutableStateFlow<ApkImportProgress?>(null)
    private val _events = MutableSharedFlow<ApkListEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ApkListEvent> = _events.asSharedFlow()

    val uiState: StateFlow<ApkListUiState> = combine(repository.observeAll(), query, sort, progress) { list, q, s, p ->
        val filtered = list.filter { a ->
            q.isBlank() || a.title.contains(q, ignoreCase = true) || a.packageName?.contains(q, ignoreCase = true) == true
        }
        val sorted = when (s) {
            ApkSort.NAME -> filtered.sortedWith { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title) }
            ApkSort.DATE -> filtered.sortedByDescending { it.importedAt }
            ApkSort.SIZE -> filtered.sortedByDescending { it.sizeBytes }
        }
        ApkListUiState(sorted.toImmutableList(), q, s, p, loaded = true)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        ApkListUiState(kotlinx.collections.immutable.persistentListOf(), "", ApkSort.DATE, null, loaded = false),
    )

    fun setQuery(value: String) = query.update { value }
    fun setSort(value: ApkSort) = sort.update { value }

    /** Called with the URIs returned by `OpenMultipleDocuments`; runs in the ViewModel scope so rotation does not cancel it. */
    fun import(uris: List<Uri>) {
        if (uris.isEmpty() || progress.value != null) return
        viewModelScope.launch {
            progress.value = ApkImportProgress(0, uris.size.coerceAtMost(ApkRepository.MAX_URIS_PER_IMPORT), null)
            try {
                val outcomes = repository.import(uris) { progress.value = it }
                val imported = outcomes.count { it is ApkImportOutcome.Imported }
                val invalid = outcomes.count { it is ApkImportOutcome.Invalid }
                val rejected = outcomes.filterIsInstance<ApkImportOutcome.Rejected>()
                rejected.firstOrNull { it.code == ErrorCode.DUPLICATE_EXACT && it.existingId != null }
                    ?.let { _events.emit(ApkListEvent.HighlightExisting(it.existingId!!)) }
                _events.emit(ApkListEvent.ImportFinished(imported, invalid, rejected.map { it.displayName to it.code }))
            } finally {
                progress.value = null
            }
        }
    }

    fun delete(apk: ImportedApk) = viewModelScope.launch {
        repository.delete(apk.id)
        _events.emit(ApkListEvent.Deleted(apk.title))
    }

    fun installedState(apk: ImportedApk) = repository.installedState(apk)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
