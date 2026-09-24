package com.aniblaze.search

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.ui.components.AccentBar
import com.aniblaze.ui.components.PosterCard
import com.aniblaze.ui.components.Skeleton
import com.aniblaze.ui.components.gridCellWidth
import com.aniblaze.ui.components.posterRowCardWidth
import com.aniblaze.ui.components.resolveGridColumns
import com.aniblaze.ui.components.rememberTvInitialFocus
import com.aniblaze.ui.components.tvClickable
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.OledBlack
import com.aniblaze.ui.theme.Surface2
import com.aniblaze.ui.theme.Surface3
import com.aniblaze.ui.theme.TextPrimary
import com.aniblaze.ui.theme.TextSecondary
import com.aniblaze.ui.theme.TextTertiary

/**
 * Кино повторяет механику мобильной главной аниме: каждый раздел сначала является
 * горизонтальной лентой, «Все» раскрывает его в сетку, а данные догружаются при
 * прокрутке. Отдельной кнопки «Показать ещё», которая только переставляла карточки,
 * здесь намеренно нет.
 */
@Composable
fun CinemaScreen(
    onAnimeClick: (String) -> Unit,
    refreshTick: Int = 0,
    viewModel: CinemaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val columnSetting by viewModel.gridColumns.collectAsStateWithLifecycle()
    val listState = viewModel.listState
    val expanded = viewModel.expandedSections
    val sections = remember(state.items) { cinemaSections(state.items) }
    // Smart TV: стартовать пульт с первого чипса жанра (поле поиска фокус
    // открывал бы клавиатуру сразу). На тачскринах — null, без эффекта.
    val tvFocus = rememberTvInitialFocus(state.items.isNotEmpty())

    LaunchedEffect(refreshTick) {
        if (refreshTick > 0) {
            viewModel.refresh()
            listState.animateScrollToItem(0)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = resolveGridColumns(columnSetting, maxWidth)
        val cardWidth = posterRowCardWidth(maxWidth, columns)
        val cellWidth = gridCellWidth(maxWidth, columns, horizontalPadding = 12.dp, spacing = 10.dp)
        val searchRows = remember(state.items, columns, state.query) {
            if (state.query.length >= 2) state.items.chunked(columns) else emptyList()
        }
        val expandedRows = remember(sections, columns, expanded.toMap()) {
            sections.filter { expanded[it.key] == true }
                .associate { it.key to it.items.chunked(columns) }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            item(key = "cinema_search") {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::onQuery,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        placeholder = { Text("Поиск фильмов и сериалов…", color = TextTertiary) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = AccentOrange) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { viewModel.submitSearch() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Surface2,
                            unfocusedContainerColor = Surface2,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                        ),
                    )
                    if (state.query.length < 2) {
                        LazyRow(
                            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(viewModel.kinds, key = { _, it -> it.key }) { index, kind ->
                                KindChip(
                                    kind.label,
                                    active = kind.key == state.kind,
                                    focusRequester = if (index == 0) tvFocus else null,
                                    onClick = { viewModel.setKind(kind.key) },
                                )
                            }
                        }
                    }
                }
            }

            if (state.isLoading && state.items.isEmpty()) {
                items(2, key = { "cinema_skeleton_$it" }) { CinemaSkeletonRow() }
            } else if (state.query.length >= 2) {
                items(searchRows.size, key = { "search_row_$it" }) { index ->
                    CinemaGridRow(searchRows[index], columns, cellWidth, onAnimeClick)
                }
            } else {
                sections.forEach { section ->
                    val isExpanded = expanded[section.key] == true
                    item(key = "cinema_header_${section.key}") {
                        CinemaCategoryHeader(section.title, isExpanded) {
                            val next = !isExpanded
                            expanded[section.key] = next
                            if (next) viewModel.expandSection()
                        }
                    }
                    if (isExpanded) {
                        val rows = expandedRows[section.key].orEmpty()
                        items(rows.size, key = { "cinema_grid_${section.key}_$it" }) { index ->
                            CinemaGridRow(rows[index], columns, cellWidth, onAnimeClick)
                        }
                        item(key = "cinema_more_${section.key}") {
                            LaunchedEffect(section.items.size) { viewModel.loadMore() }
                            Spacer(Modifier.fillMaxWidth().height(8.dp))
                        }
                    } else {
                        item(key = "cinema_row_${section.key}") {
                            CinemaPosterRow(section.items, cardWidth, onAnimeClick, viewModel::loadMore)
                        }
                    }
                }
            }

            if (state.isLoading && state.items.isNotEmpty()) {
                item(key = "cinema_loading") {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        color = AccentOrange,
                    )
                }
            }

            if (!state.isLoading && state.items.isEmpty()) {
                item(key = "cinema_empty") {
                    Text(
                        if (state.query.length >= 2) "Ничего не найдено по «${state.query}»"
                        else "Пусто. Проверь подключение.",
                        color = TextSecondary,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CinemaCategoryHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "cinemaChevron")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccentBar()
        Spacer(Modifier.width(10.dp))
        Text(title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(
            if (expanded) "Свернуть" else "Все",
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(end = 4.dp),
        )
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = TextSecondary, modifier = Modifier.rotate(rotation))
    }
}

@Composable
private fun CinemaPosterRow(
    items: List<Anime>,
    cardWidth: Dp,
    onAnimeClick: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    val rowState = rememberLazyListState()
    val needsMore by remember(items.size) {
        derivedStateOf {
            val last = rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            items.isNotEmpty() && last >= items.size - 3
        }
    }
    LaunchedEffect(needsMore) { if (needsMore) onLoadMore() }
    LazyRow(
        state = rowState,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(items, key = { it.id }) { anime ->
            Box(Modifier.width(cardWidth).padding(end = 10.dp)) {
                PosterCard(
                    anime.title,
                    anime.poster,
                    anime.rating,
                    ratingMax = anime.ratingMax,
                    cellWidth = cardWidth,
                    qualityLabel = cinemaQuality(anime.status),
                    onClick = { onAnimeClick(anime.id) },
                )
            }
        }
    }
}

@Composable
private fun CinemaGridRow(
    row: List<Anime>,
    columns: Int,
    cellWidth: Dp,
    onAnimeClick: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        row.forEach { anime ->
            Box(Modifier.weight(1f)) {
                PosterCard(
                    anime.title,
                    anime.poster,
                    anime.rating,
                    ratingMax = anime.ratingMax,
                    cellWidth = cellWidth,
                    qualityLabel = cinemaQuality(anime.status),
                    onClick = { onAnimeClick(anime.id) },
                )
            }
        }
        repeat(columns - row.size) { Box(Modifier.weight(1f)) }
    }
}

/**
 * Poster quality label, verbatim from the source ("WEB-DL 1080p", "Blu-ray",
 * "CAMRip", or a coarse "Плохое/Нормальное/Лучшее" fallback). Capped so the
 * badge never overflows a narrow grid cell.
 */
private fun cinemaQuality(status: String): String? =
    status.trim().takeIf { it.isNotBlank() }?.take(18)

@Composable
private fun CinemaSkeletonRow() {
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(5) { Skeleton(Modifier.width(140.dp).height(210.dp).padding(end = 12.dp)) }
    }
}

@Composable
private fun KindChip(
    label: String,
    active: Boolean,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (active) AccentOrange else Surface3)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvClickable(12.dp, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            color = if (active) OledBlack else TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
