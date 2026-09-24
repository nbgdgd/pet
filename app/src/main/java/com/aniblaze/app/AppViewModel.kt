package com.aniblaze.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniblaze.database.settings.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    settings: SettingsDataStore,
) : ViewModel() {
    /** null = still loading, false = show onboarding, true = show app. */
    val onboarded: StateFlow<Boolean?> = settings.settings
        .map { it.onboarded }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Same accessibility/UI scale choices as the desktop app. */
    val fontScale: StateFlow<Float> = settings.settings
        .map { it.fontScale }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)
}
