package com.aniblaze.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.repository.AnimeRepository
import com.aniblaze.aggregator.repository.ContinueWatchingItem
import com.aniblaze.database.settings.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** A named horizontal row of titles on the Home screen. */
data class HomeSection(val key: String, val title: String, val items: List<Anime>)

data class HomeState(
    val isLoading: Boolean = true,
    val continueWatching: List<ContinueWatchingItem> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
    val randomTarget: String? = null,
    /** Selected season for the "Сезонное аниме" row (1=winter..4=autumn). */
    val seasonalSeason: Int = 1,
    val error: String? = null,
)

sealed interface HomeIntent {
    data object Refresh : HomeIntent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: AnimeRepository,
    private val settings: SettingsDataStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState(seasonalSeason = currentSeason()))
    val state: StateFlow<HomeState> = _state.asStateFlow()

    /** User-chosen grid columns for expanded category grids (0 = Auto). */
    val gridColumns: StateFlow<Int> = settings.settings.map { it.gridColumns }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), 0)

    // The currently active Home layout (user-customised order of section keys).
    private var activeOrder: List<String> = SettingsDataStore.DEFAULT_HOME_ORDER

    // Which category rows are expanded into a grid. Held in the VM so the layout
    // survives navigating into a title and back.
    val expandedSections = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    // Scroll position, likewise held in the VM so it survives navigating into a title and back.
    val listState = androidx.compose.foundation.lazy.LazyListState()

    // Pagination state per sort-based section.
    private val rawPage = mutableMapOf<String, Int>()
    private val exhausted = mutableSetOf<String>()
    private val loadingKeys = mutableSetOf<String>()

    // Shared pool that powers the genre rows (avoids re-fetching per genre).
    private val pool = mutableListOf<Anime>()
    private var poolPage = 0
    private var poolExhausted = false

    private var recsJob: Job? = null

    init {
        observeContinueWatching()
        observeRecommendations()
        onIntent(HomeIntent.Refresh)
    }

    /** Recompute "Recommended for you" only when the favourite *set* actually changes.
     *  (favorites() re-emits on every content-table write, which would otherwise cancel
     *  the in-flight recommendation job over and over and never finish.) */
    private fun observeRecommendations() {
        repository.favorites()
            .map { favs -> favs.map { it.id }.toSet() }
            .distinctUntilChanged()
            .onEach { ids -> loadRecommendations(ids.size) }
            .catch { Timber.e(it, "recommendations stream error") }
            .launchIn(viewModelScope)
    }

    private fun loadRecommendations(favCount: Int) {
        recsJob?.cancel()
        if (favCount < RECOMMEND_MIN) {
            _state.value = _state.value.copy(sections = _state.value.sections.filterNot { it.key == RECOMMEND_KEY })
            return
        }
        recsJob = viewModelScope.launch {
            // Let the main rows paint first — recommendations do a lot of network work.
            kotlinx.coroutines.delay(700)
            val recs = runCatching { repository.recommendations() }
                .onFailure { Timber.e(it, "recommendations failed") }
                .getOrDefault(emptyList())
            if (recs.isNotEmpty()) upsertSection(HomeSection(RECOMMEND_KEY, "Рекомендуем вам", recs))
        }
    }

    fun onIntent(intent: HomeIntent) {
        when (intent) {
            HomeIntent.Refresh -> reload()
        }
    }

    fun pickRandom() {
        viewModelScope.launch {
            runCatching { repository.randomAnime() }.getOrNull()?.let {
                _state.value = _state.value.copy(randomTarget = it.id)
            }
        }
    }

    fun clearRandom() {
        _state.value = _state.value.copy(randomTarget = null)
    }

    /** Loads more for one row as the user scrolls it horizontally. */
    fun loadMoreSection(key: String) {
        if (!canLoad(key)) return
        loadingKeys += key
        viewModelScope.launch {
            try { loadMoreSuspend(key) } finally { loadingKeys -= key }
        }
    }

    /** Pulls several pages at once so an expanded category shows many titles. */
    fun expandSection(key: String) {
        if (!canLoad(key)) return
        loadingKeys += key
        viewModelScope.launch {
            try { repeat(3) { loadMoreSuspend(key) } } finally { loadingKeys -= key }
        }
    }

    private fun canLoad(key: String): Boolean = when {
        key in loadingKeys -> false
        key.startsWith(GENRE) -> !poolExhausted
        else -> key !in exhausted
    }

    private suspend fun loadMoreSuspend(key: String) {
        if (key.startsWith(GENRE)) {
            val before = pool.size
            extendPool(2)
            if (pool.size > before) {
                val genres = genreSections()
                _state.value = _state.value.copy(
                    sections = _state.value.sections.map { sec -> genres.firstOrNull { it.key == sec.key } ?: sec },
                )
            }
        } else {
            val more = pullMain(key, 12)
            if (more.isNotEmpty()) appendToSection(key, more)
        }
    }

    private fun observeContinueWatching() {
        repository.continueWatching()
            .onEach { items -> _state.value = _state.value.copy(continueWatching = items) }
            .catch { Timber.e(it, "continue-watching stream error") }
            .launchIn(viewModelScope)
    }

    private fun reload() {
        rawPage.clear(); exhausted.clear(); pool.clear(); poolPage = 0; poolExhausted = false
        _state.value = _state.value.copy(isLoading = true, error = null, sections = emptyList())

        viewModelScope.launch {
            // Honour the user's customised section list + order.
            activeOrder = runCatching { settings.settings.first().homeOrder }
                .getOrDefault(SettingsDataStore.DEFAULT_HOME_ORDER)

            // Load each section independently and reveal it as soon as it's ready,
            // so the screen fills progressively instead of freezing on a big await.
            val jobs = mutableListOf<Job>()
            activeOrder.forEach { key ->
                if (key == "genres") {
                    jobs += viewModelScope.launch {
                        extendPool(2)
                        genreSections().forEach { upsertSection(it) }
                    }
                } else {
                    val title = SECTION_TITLES[key] ?: return@forEach
                    jobs += viewModelScope.launch {
                        val items = pullMain(key, 12)
                        if (items.isNotEmpty()) upsertSection(HomeSection(key, title, items))
                    }
                }
            }
            jobs.joinAll()
            _state.value = _state.value.copy(
                isLoading = false,
                error = if (_state.value.sections.isEmpty()) "Не удалось загрузить каталог. Потяните, чтобы обновить." else null,
            )
            // Sections were cleared above; re-add recommendations for the current favourites.
            loadRecommendations(runCatching { repository.favorites().first().size }.getOrDefault(0))
        }
    }

    /** Switches the "Сезонное аниме" row to another season and reloads just that row. */
    fun selectSeason(season: Int) {
        if (season == _state.value.seasonalSeason || season !in 1..4) return
        _state.value = _state.value.copy(seasonalSeason = season)
        rawPage["seasonal"] = 0
        exhausted -= "seasonal"
        if ("seasonal" in loadingKeys) return
        loadingKeys += "seasonal"
        viewModelScope.launch {
            try {
                val items = pullMain("seasonal", 12)
                upsertSection(HomeSection("seasonal", SECTION_TITLES["seasonal"] ?: "Сезонное аниме", items))
            } finally {
                loadingKeys -= "seasonal"
            }
        }
    }

    private fun currentSeason(): Int = ((java.time.LocalDate.now().monthValue - 1) / 3) + 1
    private fun currentYear(): Int = java.time.LocalDate.now().year

    /** Inserts/updates a section keeping the canonical row order. */
    private fun upsertSection(section: HomeSection) {
        val order = listOf(RECOMMEND_KEY) +
            activeOrder.flatMap { if (it == "genres") GENRE_ROWS.map { g -> "$GENRE$g" } else listOf(it) }
        val current = _state.value.sections.toMutableList()
        val idx = current.indexOfFirst { it.key == section.key }
        if (idx >= 0) {
            current[idx] = section
        } else {
            val myPos = order.indexOf(section.key)
            val insertAt = current.indexOfFirst { order.indexOf(it.key).let { p -> p < 0 || p > myPos } }
            if (insertAt < 0) current.add(section) else current.add(insertAt, section)
        }
        _state.value = _state.value.copy(sections = current, isLoading = false)
    }

    private fun appendToSection(key: String, more: List<Anime>) {
        _state.value = _state.value.copy(
            sections = _state.value.sections.map { sec ->
                if (sec.key == key) sec.copy(items = (sec.items + more).distinctBy { it.id }) else sec
            },
        )
    }

    /** Pulls underlying pages until [target] items are gathered (or the row ends). */
    private suspend fun pullMain(key: String, target: Int): List<Anime> {
        if (key in exhausted) return emptyList()
        val added = mutableListOf<Anime>()
        var fetched = 0
        while (added.size < target && fetched < 4 && key !in exhausted) {
            val page = rawPage.getOrDefault(key, 0)
            val raw = runCatching { loadRaw(key, page) }.getOrDefault(emptyList())
            rawPage[key] = page + 1
            fetched++
            if (raw.isEmpty() || key == "ongoing") {
                exhausted += key
                if (raw.isEmpty()) break
            }
            added += raw
        }
        return added
    }

    private suspend fun loadRaw(key: String, page: Int): List<Anime> = when {
        key == "trending" -> repository.catalogPage(3, page)
        key == "recent" -> repository.catalogPage(2, page)
        key == "popular" -> repository.catalogPage(4, page)
        // Announces: pull several pages, then globally rank by popularity.
        key == "announce" -> if (page == 0) announces() else emptyList()
        key == "ongoing" -> if (page == 0) repository.catalog("ongoing") else emptyList()
        key == "seasonal" -> repository.seasonalCatalog(_state.value.seasonalSeason, currentYear(), page)
        key == "watching" -> repository.watchingNow(currentSeason(), currentYear(), page)
        else -> emptyList()
    }

    /** Most-anticipated unreleased titles, ranked by favourites (Chainsaw Man, OPM…). */
    private suspend fun announces(): List<Anime> {
        val pool = (0..3).flatMap { p ->
            runCatching { repository.catalogPage(6, p) }.getOrDefault(emptyList())
        }
        return pool
            .filter { it.rating < 1.0 && it.favoritesCount >= 500 }
            .distinctBy { it.id }
            .sortedByDescending { it.favoritesCount }
    }

    private suspend fun extendPool(pages: Int) {
        repeat(pages) {
            if (poolExhausted) return
            val items = runCatching { repository.catalogPage(4, poolPage) }.getOrDefault(emptyList())
            poolPage++
            if (items.isEmpty()) {
                poolExhausted = true
                return
            }
            pool += items.filter { p -> pool.none { it.id == p.id } }
        }
    }

    private fun genreSections(): List<HomeSection> = GENRE_ROWS.mapNotNull { genre ->
        val items = pool.filter { it.genres.contains(genre, ignoreCase = true) }
        if (items.size >= 4) HomeSection("$GENRE$genre", genre, items) else null
    }

    private companion object {
        const val GENRE = "genre:"
        const val POOL_KEY = "__pool__"
        const val RECOMMEND_KEY = "recommend"
        const val RECOMMEND_MIN = 3
        val SECTION_TITLES = SettingsDataStore.HOME_SECTIONS.toMap()
        val GENRE_ROWS = listOf(
            "Экшен", "Романтика", "Фэнтези", "Комедия", "Фантастика",
            "Драма", "Спорт", "Ужасы", "Психологическое", "Сёнен",
        )
    }
}
