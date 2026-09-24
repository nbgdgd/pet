package com.aniblaze.database.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimal desktop stand-in for the Android DataStore-backed settings store —
 * [AnixartSource] only reads [preferredVoiceover] to pick a default dub. Real
 * desktop settings (grid columns, etc.) persist separately via AppSettings
 * (a small JSON file); this class exists purely so the ported source files
 * compile and run unmodified.
 */
data class UserSettings(
    val preferredVoiceover: String = "",
)

class SettingsDataStore(initialVoiceover: String = "") {
    private val _settings = MutableStateFlow(UserSettings(preferredVoiceover = initialVoiceover))
    val settings: Flow<UserSettings> = _settings.asStateFlow()

    fun setPreferredVoiceover(name: String) {
        _settings.value = _settings.value.copy(preferredVoiceover = name)
    }
}
