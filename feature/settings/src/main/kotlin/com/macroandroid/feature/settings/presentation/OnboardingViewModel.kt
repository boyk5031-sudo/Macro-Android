package com.macroandroid.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.core.datastore.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val prefs: UserPreferencesRepository) : ViewModel() {
    fun complete(onDone: () -> Unit) = viewModelScope.launch {
        prefs.setOnboardingCompleted(true)
        onDone()
    }
}
