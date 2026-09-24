package com.aniblaze.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.repository.AnimeRepository
import com.aniblaze.aggregator.search.searchHint
import com.aniblaze.aggregator.search.searchTitles
import com.aniblaze.database.settings.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class SearchState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<Anime> = emptyList(),
    val error: String? = null,
    /**
     * Строчка над выдачей: «Искали „наруто“» или «Точного совпадения нет». Пусто, если
     * объяснять нечего. Без неё умный поиск выглядит сломанным: человек напечатал
     * «Yfhenj», получил «Наруто» и не понимает, при чём тут оно.
     */
    val hint: String = "",
)

sealed interface SearchIntent {
    data class QueryChanged(val query: String) : SearchIntent
    data object Submit : SearchIntent
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: AnimeRepository,
    private val store: SettingsDataStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    /** Recent search queries (newest first) for the suggestions panel. */
    val history: StateFlow<List<String>> = store.settings
        .map { it.searchHistory }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Always-available popular searches. */
    val popular: List<String> = POPULAR_QUERIES

    private val queryFlow = MutableStateFlow("")

    init {
        // Instant-as-you-type suggestions with a short debounce.
        queryFlow
            .debounce(200)
            .distinctUntilChanged()
            .filter { it.length >= 2 }
            .onEach { runSearch(it) }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: SearchIntent) {
        when (intent) {
            is SearchIntent.QueryChanged -> {
                _state.value = _state.value.copy(query = intent.query)
                queryFlow.value = intent.query
            }
            SearchIntent.Submit -> {
                recordSearch(_state.value.query)
                runSearchNow(_state.value.query)
            }
        }
    }

    /** Sets the query (e.g. tapping a suggestion) and runs the search immediately. */
    fun searchFor(query: String) {
        _state.value = _state.value.copy(query = query)
        queryFlow.value = query
        recordSearch(query)
        runSearchNow(query)
    }

    fun recordSearch(query: String) {
        if (query.trim().length >= 2) viewModelScope.launch { store.addSearch(query) }
    }

    fun clearHistory() = viewModelScope.launch { store.clearSearchHistory() }.let {}

    private fun runSearchNow(query: String) {
        if (query.length >= 2) viewModelScope.launch { runSearch(query) }
    }

    private suspend fun runSearch(query: String) {
        _state.value = _state.value.copy(isSearching = true, error = null)
        runCatching { repository.search(query) }
            .onSuccess { results ->
                _state.value = _state.value.copy(
                    isSearching = false,
                    results = results,
                    hint = searchHint(query, searchTitles(query, results, limit = 1)),
                )
            }
            .onFailure {
                Timber.e(it, "search failed")
                _state.value = _state.value.copy(
                    isSearching = false,
                    error = "Search failed. Check your connection.",
                )
            }
    }

    private companion object {
        val POPULAR_QUERIES = listOf(
            "Атака титанов", "Клинок, рассекающий демонов", "Магическая битва",
            "Человек-бензопила", "Ван-Пис", "Наруто", "Поднятие уровня в одиночку",
            "Синяя тюрьма", "Ванпачмен", "Токийские мстители",
        )
    }
}
