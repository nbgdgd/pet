package com.aniblaze.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aniblaze.ui.components.PosterCard
import com.aniblaze.ui.components.Skeleton
import com.aniblaze.ui.components.gridCellWidth
import com.aniblaze.ui.components.resolveGridColumns
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogBrowse(
    onAnimeClick: (String) -> Unit,
    refreshTick: Int = 0,
    viewModel: CatalogViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = viewModel.gridState
    val colSetting by viewModel.gridColumns.collectAsStateWithLifecycle()
    var filtersOpen by remember { mutableStateOf(false) }

    if (filtersOpen) {
        CatalogFilterSheet(
            initial = state.filter,
            viewModel = viewModel,
            onDismiss = { filtersOpen = false },
            onApply = {
                filtersOpen = false
                viewModel.applyFilter(it)
            },
        )
    }

    // Re-tapping the Search tab refreshes the browse grid and jumps to the top.
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0) {
            viewModel.refresh()
            gridState.animateScrollToItem(0)
        }
    }

    // Подгрузка у нижнего края. derivedStateOf, а не snapshotFlow по layoutInfo:
    // layoutInfo пересобирается на КАЖДОМ кадре прокрутки, и подписка на него будила
    // корутину шестьдесят раз в секунду ради ответа, который меняется раз в страницу.
    val needsMore by remember(state.items.size, state.canLoadMore, state.isLoading) {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.items.size - 4 && state.canLoadMore && !state.isLoading
        }
    }
    LaunchedEffect(needsMore) { if (needsMore) viewModel.loadMore() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = resolveGridColumns(colSetting, maxWidth)
        val cell = gridCellWidth(maxWidth, columns, horizontalPadding = 12.dp, spacing = 10.dp)
        PullToRefreshBox(
            isRefreshing = state.isLoading && state.items.isNotEmpty(),
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
                    // Отдаём только сам фильтр, а не весь CatalogState: в нём лежит
                    // список items, который растёт на каждой подгрузке страницы, и
                    // шапка пересобиралась бы вместе с ним.
                    CatalogFilterBar(
                        filter = state.filter,
                        sorts = viewModel.sorts,
                        onSort = viewModel::setSort,
                        onOpenFilters = { filtersOpen = true },
                        onFilterChange = viewModel::applyFilter,
                    )
                }
                if (state.items.isEmpty() && state.isLoading) {
                    items(9) {
                        Skeleton(Modifier.fillMaxWidth().aspectRatio(2f / 3f), cornerRadius = 16.dp)
                    }
                }
                items(state.items, key = { it.id }, contentType = { "poster" }) { anime ->
                    PosterCard(
                        title = anime.title,
                        posterUrl = anime.poster,
                        rating = anime.rating,
                        ratingMax = anime.ratingMax,
                        cellWidth = cell,
                        onClick = { onAnimeClick(anime.id) },
                    )
                }
                if (state.isLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "spinner") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AccentOrange)
                        }
                    }
                }
                if (!state.isLoading && state.items.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        Text(
                            if (state.filter.isEmpty) {
                                "Каталог не загрузился. Потяните, чтобы обновить."
                            } else {
                                "Ничего не найдено. Снимите часть условий — они складываются по «И»."
                            },
                            color = TextSecondary,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}
