package com.aniblaze.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniblaze.aggregator.model.AgeRating
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.CatalogFilter
import com.aniblaze.aggregator.model.CatalogSort
import com.aniblaze.aggregator.model.CatalogTag
import com.aniblaze.aggregator.model.ContentType
import com.aniblaze.aggregator.model.EpisodeRange
import com.aniblaze.aggregator.model.TitleStatus
import com.aniblaze.aggregator.repository.AnimeRepository
import com.aniblaze.database.settings.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Year
import javax.inject.Inject

data class CatalogState(
    val items: List<Anime> = emptyList(),
    val filter: CatalogFilter = CatalogFilter(),
    val isLoading: Boolean = false,
    val canLoadMore: Boolean = true,
)

@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val repository: AnimeRepository,
    store: SettingsDataStore,
) : ViewModel() {

    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    /** User-chosen grid columns (0 = Auto). */
    val gridColumns: StateFlow<Int> = store.settings.map { it.gridColumns }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // Held in the VM so the scroll position survives navigating into a title and back.
    val gridState = androidx.compose.foundation.lazy.grid.LazyGridState()

    // Готовые списки для панели фильтров. Собраны один раз: экран не должен строить их
    // в теле composable — это и аллокация на каждую пересборку, и потеря
    // пропускаемости, потому что параметр приходил бы с новой identity.
    val sorts: List<CatalogSort> = CatalogSort.entries
    val primaryTags: List<CatalogTag> = CatalogTag.entries.filter { it.primary }
    val moreTags: List<CatalogTag> = CatalogTag.entries.filterNot { it.primary }
    val statuses: List<TitleStatus> = TitleStatus.entries
    val ageRatings: List<AgeRating> = AgeRating.entries
    val episodeRanges: List<EpisodeRange> = EpisodeRange.entries
    val contentTypes: List<ContentType> = ContentType.entries
    val years: List<Int> = (Year.now().value downTo 1990).toList()

    /** Пороги оценки в десятибалльной шкале — так их называют люди. */
    val ratingSteps: List<Double> = listOf(6.0, 7.0, 8.0, 9.0)

    private var rawPage = 0
    private var exhausted = false

    init {
        loadMore()
    }

    /**
     * Применить фильтр целиком. Панель редактирует свою копию и отдаёт её сюда одним
     * куском: на телефоне лента не должна перезагружаться на каждый тап по жанру,
     * пока панель ещё открыта.
     */
    fun applyFilter(filter: CatalogFilter) {
        if (filter == _state.value.filter) return
        _state.value = _state.value.copy(filter = filter)
        restart()
    }

    fun setSort(sort: CatalogSort) = applyFilter(_state.value.filter.copy(sort = sort))

    fun clearFilter() = applyFilter(_state.value.filter.cleared())

    fun refresh() = restart()

    private fun restart() {
        rawPage = 0
        exhausted = false
        _state.value = _state.value.copy(items = emptyList(), canLoadMore = true)
        viewModelScope.launch { gridState.scrollToItem(0) }
        loadMore()
    }

    fun loadMore() {
        val s = _state.value
        if (s.isLoading || exhausted) return
        _state.value = s.copy(isLoading = true)
        val filter = s.filter
        viewModelScope.launch {
            val added = mutableListOf<Anime>()
            var pagesFetched = 0
            // Сервер отбирает по жанрам, годам, статусу, возрасту, числу серий и типу,
            // но не по оценке — её отсекает проверка на нашей стороне. Поэтому страниц
            // иногда нужно несколько, чтобы набрать полный экран.
            while (added.size < 15 && pagesFetched < 4 && !exhausted) {
                val page = runCatching { repository.catalogFiltered(filter, rawPage) }
                    .getOrDefault(emptyList())
                rawPage++
                pagesFetched++
                if (page.isEmpty()) {
                    exhausted = true
                    break
                }
                added += page
            }
            // Фильтр мог смениться, пока страница летела: тогда этот ответ уже не про
            // то, что человек видит на экране, и подмешивать его в ленту нельзя.
            if (_state.value.filter != filter) return@launch
            val merged = (_state.value.items + added).distinctBy { it.id }
            Timber.d(
                "[Catalog] +%d (total %d) tags=%s years=%d-%d sort=%s",
                added.size, merged.size, filter.tags, filter.yearFrom, filter.yearTo, filter.sort,
            )
            _state.value = _state.value.copy(
                items = merged,
                isLoading = false,
                canLoadMore = !exhausted,
            )
        }
    }
}
