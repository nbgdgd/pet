package com.aniblaze.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.CatalogTag
import com.aniblaze.desktop.CinemaCache
import com.aniblaze.desktop.DesktopRepository
import kotlinx.coroutines.launch

// Клиентские сортировка и порог оценки — ТОЛЬКО для источников-скрейперов, у которых
// своего фильтра нет вовсе. У TMDB вместо этого общая полоса фильтров (FilterBar).
private val SORTS = listOf("По списку", "Популярное", "По году", "По названию")
private val RATING_FILTERS = listOf(0.0 to "Все", 6.0 to "6+", 7.0 to "7+", 8.0 to "8+")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CinemaScreen(
    repository: DesktopRepository,
    cache: CinemaCache,
    // Cartoons tab: same screen, restricted to the animation categories (and it
    // forces the animation genre onto the filter panel's discover queries).
    cartoonMode: Boolean = false,
    /** Обработчик пункта «Открыть в новом окне» в меню карточки; null — пункта нет. */
    onOpenInNewWindow: ((Anime) -> Unit)? = { com.aniblaze.desktop.DetachedTitles.openTitle(it) },
    onOpen: (Anime) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val batch = 3
    // Cartoons always come from the TMDB-backed source (the only one with animation
    // categories), whatever the user picked for «Кино» in Settings.
    val allCategories = repository.cinemaCategories(cartoonMode)
    val categories = remember(allCategories, cartoonMode) {
        val base = if (cartoonMode) allCategories.filter { it.first.startsWith("cartoon:") }
        else allCategories.filterNot { it.first.startsWith("cartoon:") }
        // «Кино» opens with the same live rows as the anime home (сейчас смотрят /
        // новые серии / в тренде / поступления), TMDB-served whatever the chosen
        // source. distinctBy(label) drops the TMDB source's own duplicates when it
        // IS the active source.
        if (cartoonMode) base else (repository.cinemaSpecialRows() + base).distinctBy { it.second }
    }
    val paginates = repository.cinemaPaginates(cartoonMode)
    val supportsFilters = repository.cinemaSupportsFilters(cartoonMode)
    val genres = remember(supportsFilters, cartoonMode) { repository.cinemaGenres(cartoonMode) }

    // Общие фильтры каталога (TMDB). У источников-скрейперов их нет вовсе — им
    // остаётся прежний клиентский набор «сортировка + оценка» ниже по экрану.
    val facets = remember(cartoonMode, supportsFilters) { repository.cinemaFacets(cartoonMode) }
    val filtering = facets != null && !cache.filter.isDefault
    val base = cache.expandedCat ?: categories.firstOrNull()?.first ?: "popular"

    // Three modes: rows landing (default), a single category as a grid (with filters),
    // or search results. Search and grid share cache.items; rows use cache.rows.
    val mode = when {
        cache.query.length >= 2 -> "search"
        cache.expandedCat != null || filtering -> "grid"
        else -> "rows"
    }

    fun resetFilters() {
        cache.filter = cache.filter.cleared()
        cache.minRating = 0.0
    }

    /**
     * Страница сетки. Развилка та же, что на главной: пока категория не открыта,
     * фильтр САМ является лентой и целиком исполняется TMDB (оттуда и точное число
     * найденного); внутри открытой категории её порядок важнее, и репозиторий
     * просеивает страницы этой категории.
     */
    suspend fun browse(page: Int): List<Anime> = when {
        !filtering -> repository.cinemaBrowseBatch(base, page, batch, cartoonMode)
        else -> {
            val result = repository.cinemaFilterPage(cache.filter, cache.expandedCat, page, cartoonMode)
            cache.filterTotal = result.total
            result.items
        }
    }

    suspend fun loadFresh() {
        cache.page = 0; cache.canLoadMore = true; cache.loaded = false
        val fresh = if (cache.query.length >= 2) repository.cinemaSearch(cache.query, cartoonMode) else browse(1)
        cache.items = fresh
        cache.page = if (filtering) 1 else batch
        cache.canLoadMore = paginates && cache.query.length < 2 && fresh.isNotEmpty()
        cache.loaded = true
    }

    suspend fun loadMore() {
        if (cache.busy || !cache.canLoadMore || cache.query.length >= 2) return
        cache.busy = true
        try {
            val step = if (filtering) 1 else batch
            val fresh = browse(cache.page + 1)
            val add = fresh.filterNot { n -> cache.items.any { it.id == n.id } }
            if (add.isEmpty()) cache.canLoadMore = false else cache.items = cache.items + add
            cache.page += step
        } finally {
            cache.busy = false
        }
    }

    // Load the grid/search view when in those modes; rows load themselves lazily.
    LaunchedEffect(mode, base, cache.filter, cache.query) {
        if (mode == "rows") return@LaunchedEffect
        if (cache.query.length >= 2) kotlinx.coroutines.delay(400)
        loadFresh()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxWidth < COMPACT_WIDTH
    // Точное число найденного есть только у самого TMDB-запроса; внутри открытой
    // категории мы просеиваем её страницы у себя, и итог там неизвестен.
    val found = when {
        mode == "search" -> cache.items.size
        !filtering -> 0
        cache.expandedCat != null -> -cache.items.size
        else -> cache.filterTotal
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mode == "grid") {
                    IconButton(onClick = { cache.expandedCat = null; resetFilters(); cache.filtersOpen = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "К категориям", tint = AccentOrange)
                    }
                }
                OutlinedTextField(
                    value = cache.query,
                    onValueChange = { cache.query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Поиск фильмов и сериалов…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                )
                IconButton(onClick = { if (mode == "rows") { cache.rows = emptyMap(); cache.rowsNonce++ } else scope.launch { loadFresh() } }, modifier = Modifier.padding(start = 4.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = AccentOrange)
                }
            }
            // Категории — только когда открыта одна из них: на посадочной странице они и
            // так стоят заголовками рядов.
            if (mode == "grid") {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.forEach { (p, label) ->
                        Pill(label, p == base && cache.expandedCat != null) {
                            cache.expandedCat = p
                        }
                    }
                }
            }
            // Полоса фильтров — как на главной, и тоже везде: и над рядами, и внутри
            // открытой категории. У источников без своего фильтра (скрейперы) её нет,
            // и им остаётся прежний клиентский набор.
            if (facets != null) {
                FilterBar(
                    facets = facets,
                    filter = cache.filter,
                    found = found,
                    open = cache.filtersOpen,
                    onOpenChange = { cache.filtersOpen = it },
                    compact = compact,
                    onFilter = { cache.filter = it },
                    modifier = Modifier.padding(top = 10.dp),
                    presetScope = "cinema",
                )
            } else if (mode == "grid") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SORTS.forEachIndexed { i, label -> SoftPill(label, i == cache.sort) { cache.sort = i } }
                    Box(Modifier.width(16.dp))
                    Icon(Icons.Filled.FilterAlt, contentDescription = null, tint = TextSecondary)
                    RATING_FILTERS.forEach { (min, label) -> Pill(label, min == cache.minRating) { cache.minRating = min } }
                }
            }
        }

        when (mode) {
            "search" -> {
                // Поиск идёт по названию и сузить запросом его нельзя — выбранные теги
                // применяются к тому, что вернулось.
                val shown = remember(cache.items, cache.filter) {
                    if (cache.filter.isEmpty) cache.items
                    else cache.items.filter { cache.filter.matches(it, CatalogTag::of) }
                }
                PosterGrid(
                    shown,
                    isLoading = !cache.loaded,
                    Modifier.weight(1f),
                    cache.gridState,
                    onOpenInNewWindow = onOpenInNewWindow,
                    onClick = onOpen,
                )
            }
            "grid" -> {
                val displayItems = remember(cache.items, cache.sort, cache.minRating, supportsFilters) {
                    if (supportsFilters) cache.items
                    else {
                        val f = if (cache.minRating > 0.0) cache.items.filter { it.rating >= cache.minRating } else cache.items
                        when (cache.sort) {
                            1 -> f.sortedByDescending { it.rating }
                            2 -> f.sortedByDescending { it.year }
                            3 -> f.sortedBy { it.title }
                            else -> f
                        }
                    }
                }
                PosterGrid(
                    displayItems,
                    isLoading = !cache.loaded,
                    Modifier.weight(1f),
                    cache.gridState,
                    onLoadMore = { scope.launch { loadMore() } },
                    onOpenInNewWindow = onOpenInNewWindow,
                    onClick = onOpen,
                )
            }
            else -> CinemaRows(repository, cache, categories, cartoonMode, Modifier.weight(1f), onOpenInNewWindow, onOpen) { cat ->
                cache.expandedCat = cat
            }
        }
    }
    if (facets != null) {
        FilterSheet(
            facets = facets,
            filter = cache.filter,
            visible = compact && cache.filtersOpen,
            onClose = { cache.filtersOpen = false },
            onFilter = { cache.filter = it },
        )
    }
    }
}

/** The default landing: one horizontal [PosterRow] per category, each lazy-loaded. */
@Composable
private fun CinemaRows(
    repository: DesktopRepository,
    cache: CinemaCache,
    categories: List<Pair<String, String>>,
    cartoonMode: Boolean,
    modifier: Modifier,
    onOpenInNewWindow: ((Anime) -> Unit)?,
    onOpen: (Anime) -> Unit,
    onExpand: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val paginates = repository.cinemaPaginates(cartoonMode)
    suspend fun loadMoreRow(cat: String) {
        if (cache.rowBusy.contains(cat)) return
        val nextPage = (cache.rowPage[cat] ?: 1) + 1
        cache.rowBusy = cache.rowBusy + cat
        try {
            val more = repository.cinemaBrowseBatch(cat, nextPage, 1, cartoonMode)
            val add = more.filterNot { n -> cache.rows[cat].orEmpty().any { it.id == n.id } }
            if (add.isNotEmpty()) {
                cache.rowPage = cache.rowPage + (cat to nextPage)
                cache.rows = cache.rows + (cat to (cache.rows[cat].orEmpty() + add))
            }
        } finally {
            cache.rowBusy = cache.rowBusy - cat
        }
    }
    LazyColumn(modifier.fillMaxSize().browserAutoScroll(cache.landingState), state = cache.landingState) {
        items(categories, key = { it.first }) { (cat, label) ->
            // Fetch this category's first page the first time its row scrolls into
            // view; the nonce lets the refresh button force a reload.
            // Загрузка ряда, отличающая СБОЙ от пустой категории. Раньше исключение
            // просто улетало наружу из LaunchedEffect, ряд навсегда оставался в
            // состоянии «загружается», а пустой ответ рисовался прочерком — в обоих
            // случаях без единого способа повторить.
            suspend fun loadRow() {
                cache.rowFailed = cache.rowFailed - cat
                try {
                    cache.rows = cache.rows + (cat to repository.cinemaBrowseBatch(cat, 1, 1, cartoonMode))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    com.aniblaze.desktop.player.PlayerDiagnostics.failure("cinema.row.$cat", error)
                    cache.rows = cache.rows + (cat to emptyList())
                    cache.rowFailed = cache.rowFailed + cat
                }
            }
            LaunchedEffect(cat, cache.rowsNonce) { if (cache.rows[cat] == null) loadRow() }
            PosterRow(
                title = label,
                items = cache.rows[cat].orEmpty(),
                loading = cache.rows[cat] == null,
                onExpandAll = { onExpand(cat) },
                onLoadMore = if (paginates) ({ scope.launch { loadMoreRow(cat) } }) else null,
                failed = cat in cache.rowFailed,
                onRetry = { scope.launch { cache.rows = cache.rows - cat; loadRow() } },
                onOpenInNewWindow = onOpenInNewWindow,
                onOpen = onOpen,
            )
        }
        item { Box(Modifier.height(24.dp)) }
    }
}

/** A solid accent pill (selected = orange background). */
@Composable
private fun Pill(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (active) AccentOrange else Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (active) OledBlack else MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
    }
}

/** A subtle pill (selected = tinted accent text) for secondary filter chips. */
@Composable
private fun SoftPill(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (active) AccentOrange.copy(alpha = 0.28f) else Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (active) AccentOrange else MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
    }
}
