package com.aniblaze.search

import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.repository.AnimeRepository
import com.aniblaze.database.settings.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A cinema category shown as a chip in the Кино tab. */
data class CinemaKind(val key: String, val label: String)

data class CinemaSection(val key: String, val title: String, val items: List<Anime>)

/**
 * Реальные секции одного каталога: без повторного запроса и без отдельной базы.
 * Исходный порядок источника считается популярным, остальные ряды сортируются по
 * уже полученным рейтингу и году. Один тайтл может честно попасть в разные ряды.
 */
internal fun cinemaSections(items: List<Anime>, limit: Int = Int.MAX_VALUE): List<CinemaSection> {
    val unique = items.distinctBy { it.id }
    if (unique.isEmpty()) return emptyList()
    val popular = unique.take(limit)
    val best = unique.asSequence()
        .filter { it.rating > 0.0 }
        .sortedByDescending { it.rating / it.ratingMax.coerceAtLeast(1.0) }
        .take(limit)
        .toList()
    val newest = unique.asSequence()
        .filter { it.year > 0 }
        .sortedWith(compareByDescending<Anime> { it.year }.thenByDescending { it.rating / it.ratingMax.coerceAtLeast(1.0) })
        .take(limit)
        .toList()
    return listOf(
        CinemaSection("popular", "Популярное", popular),
        CinemaSection("best", "Высокий рейтинг", best),
        CinemaSection("new", "Новинки", newest),
    ).filter { it.items.isNotEmpty() }
}

data class CinemaState(
    val kind: String = "FILM",
    val query: String = "",
    val items: List<Anime> = emptyList(),
    val isLoading: Boolean = false,
    val canLoadMore: Boolean = true,
)

@HiltViewModel
class CinemaViewModel @Inject constructor(
    private val repository: AnimeRepository,
    settings: SettingsDataStore,
) : ViewModel() {

    val kinds = listOf(
        CinemaKind("FILM", "Фильмы"),
        CinemaKind("SERIES", "Сериалы"),
        CinemaKind("CARTOON", "Мультфильмы"),
        CinemaKind("CARTOON_SERIES", "Мультсериалы"),
    )

    // Как на главной аниме: раскрытые секции и позиция списка переживают переход
    // в карточку тайтла и возврат назад.
    val expandedSections = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    val listState = LazyListState()
    val gridColumns: StateFlow<Int> = settings.settings.map { it.gridColumns }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _state = MutableStateFlow(CinemaState())
    val state: StateFlow<CinemaState> = _state.asStateFlow()

    private var page = 1

    init {
        load(reset = true)
    }

    fun setKind(key: String) {
        if (key == _state.value.kind && _state.value.query.isBlank()) return
        page = 1
        _state.update { it.copy(kind = key, query = "", items = emptyList(), canLoadMore = true) }
        load(reset = true)
    }

    fun onQuery(q: String) {
        _state.update { it.copy(query = q) }
        if (q.isBlank()) {
            page = 1
            load(reset = true)
        }
    }

    fun submitSearch() {
        if (_state.value.query.length < 2) return
        page = 1
        _state.update { it.copy(items = emptyList(), canLoadMore = false) }
        load(reset = true)
    }

    fun loadMore() {
        val s = _state.value
        if (s.isLoading || !s.canLoadMore || s.query.length >= 2) return
        page++
        load(reset = false)
    }

    /** При раскрытии ряда сразу добавляем несколько страниц, как на главной аниме. */
    fun expandSection() {
        val s = _state.value
        if (s.isLoading || !s.canLoadMore || s.query.length >= 2) return
        viewModelScope.launch {
            repeat(2) {
                if (!_state.value.canLoadMore) return@launch
                loadNextPage()
            }
        }
    }

    fun refresh() {
        page = 1
        load(reset = true)
    }

    private fun load(reset: Boolean) {
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val s = _state.value
            val fresh = runCatching {
                if (s.query.length >= 2) repository.cinemaSearch(s.query)
                else repository.cinemaBrowse(s.kind, page)
            }.getOrDefault(emptyList())
            _state.update { cur ->
                val merged = if (reset) fresh else (cur.items + fresh).distinctBy { it.id }
                cur.copy(
                    items = merged,
                    isLoading = false,
                    canLoadMore = cur.query.length < 2 && fresh.isNotEmpty(),
                )
            }
        }
    }

    private suspend fun loadNextPage() {
        val before = _state.value
        if (before.isLoading || !before.canLoadMore || before.query.length >= 2) return
        page++
        _state.update { it.copy(isLoading = true) }
        val fresh = runCatching { repository.cinemaBrowse(before.kind, page) }.getOrDefault(emptyList())
        _state.update { cur ->
            cur.copy(
                items = (cur.items + fresh).distinctBy { it.id },
                isLoading = false,
                canLoadMore = fresh.isNotEmpty(),
            )
        }
    }
}
