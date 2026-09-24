package com.aniblaze.search

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniblaze.aggregator.repository.AnimeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CinemaWebState(
    val embedUrl: String? = null,
    val loading: Boolean = true,
    val error: Boolean = false,
)

/** Resolves the cinemar.cc player embed URL for a kinogo title to load in a WebView. */
@HiltViewModel
class CinemaWebViewModel @Inject constructor(
    private val repository: AnimeRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val pageUrl: String = Uri.decode(savedStateHandle.get<String>("pageUrl").orEmpty())

    private val _state = MutableStateFlow(CinemaWebState())
    val state: StateFlow<CinemaWebState> = _state.asStateFlow()

    init {
        resolve()
    }

    fun resolve() {
        _state.value = CinemaWebState(loading = true)
        viewModelScope.launch {
            val embed = runCatching { repository.cinemaEmbed(pageUrl) }.getOrNull()
            _state.value = if (embed.isNullOrBlank()) {
                CinemaWebState(loading = false, error = true)
            } else {
                CinemaWebState(embedUrl = embed, loading = false)
            }
        }
    }
}
