package com.aniblaze.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.repository.ContinueWatchingItem
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import com.aniblaze.ui.components.PosterCard
import com.aniblaze.ui.components.SectionHeader
import com.aniblaze.ui.components.Skeleton
import com.aniblaze.ui.components.continueCardWidth
import com.aniblaze.ui.components.gridCellWidth
import com.aniblaze.ui.components.posterRowCardWidth
import com.aniblaze.ui.components.resolveGridColumns
import com.aniblaze.ui.components.rememberTvInitialFocus
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.AccentPurple
import com.aniblaze.ui.theme.GlassBorder
import com.aniblaze.ui.theme.OledBlack
import com.aniblaze.ui.theme.Surface2
import com.aniblaze.ui.theme.TextPrimary
import com.aniblaze.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAnimeClick: (String) -> Unit,
    onResume: (contentId: String, segmentId: String) -> Unit,
    refreshTick: Int = 0,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colSetting by viewModel.gridColumns.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val listState = viewModel.listState
    val expanded = viewModel.expandedSections
    val hazeState = remember { HazeState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.randomTarget) {
        state.randomTarget?.let { onAnimeClick(it); viewModel.clearRandom() }
    }

    // Re-tapping the Home tab reloads the catalog and scrolls back to the top.
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0) {
            viewModel.onIntent(HomeIntent.Refresh)
            listState.animateScrollToItem(0)
        }
    }
    // Smart TV: the remote needs a starting focus — first "continue" card,
    // otherwise the first poster of the first section. Touch screens unaffected.
    val tvFocus = rememberTvInitialFocus(
        state.continueWatching.isNotEmpty() || state.sections.isNotEmpty(),
    )
    val firstSectionKey = remember(state.sections) { state.sections.firstOrNull()?.key }

    // Everything sizes off the REAL available width the system reports for this
    // window — correct on every phone, tablet, foldable and split-screen.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gridColumns = resolveGridColumns(colSetting, maxWidth)
        val cardWidth = posterRowCardWidth(maxWidth, gridColumns)
        val continueW = continueCardWidth(maxWidth)
        // Ширина ОДНОЙ ячейки сетки. Считается здесь, один раз на ширину экрана, и
        // передаётся вниз: карточка перестала мерить себя сама (см. PosterCard.cellWidth).
        // 24.dp — боковые отступы ряда, 10.dp — просвет между колонками.
        val gridCell = gridCellWidth(maxWidth, gridColumns, horizontalPadding = 12.dp, spacing = 10.dp)
        // Размытие шапки поверх списка. RenderEffect появился в Android 12; ниже haze
        // всё равно рисует лишь полупрозрачную подложку, и вести ради неё учёт
        // содержимого всего списка незачем.
        val blurCapable = remember { android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S }

        // Разбиение секции на строки сетки считалось внутри content-лямбды LazyColumn,
        // то есть заново на КАЖДОМ её прогоне: у развёрнутой категории это сотни
        // подсписков за проход. Считаем один раз на (секции × колонки × набор
        // развёрнутых) — данные меняются только при подгрузке страницы или тапе.
        val gridRows = remember(state.sections, gridColumns, expanded.toMap()) {
            state.sections
                .filter { expanded[it.key] == true }
                .associate { it.key to it.items.chunked(gridColumns) }
        }

        PullToRefreshBox(
            isRefreshing = state.isLoading && state.sections.isNotEmpty(),
            onRefresh = { viewModel.onIntent(HomeIntent.Refresh) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurCapable) Modifier.haze(state = hazeState) else Modifier),
                contentPadding = PaddingValues(top = 60.dp, bottom = 24.dp),
            ) {
                if (state.continueWatching.isNotEmpty()) {
                    item(key = "cw_header") { SectionHeader("Продолжить просмотр") }
                    // Передаём только свой список, а не весь HomeState: иначе ряд
                    // пересобирался на каждой подгрузке любой секции каталога.
                    item(key = "cw_row") { ContinueRow(state.continueWatching, continueW, onResume, tvFocus) }
                }

                if (state.isLoading && state.sections.isEmpty()) {
                    items(2, key = { "sk_$it" }) {
                        Column {
                            SectionHeader("Загрузка…")
                            SkeletonRow()
                        }
                    }
                }

                state.sections.forEach { section ->
                    val isExpanded = expanded[section.key] == true
                    item(key = "h_${section.key}") {
                        CategoryHeader(section.title, isExpanded, Modifier.animateItem()) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val next = !isExpanded
                            expanded[section.key] = next
                            if (next) viewModel.expandSection(section.key)
                        }
                    }
                    if (section.key == "seasonal") {
                        item(key = "season_chips") {
                            SeasonChips(state.seasonalSeason, { viewModel.selectSeason(it) }, Modifier.animateItem())
                        }
                    }
                    if (isExpanded) {
                        val rows = gridRows[section.key].orEmpty()
                        items(rows.size, key = { "g_${section.key}_$it" }) { r ->
                            GridRow(rows[r], gridColumns, gridCell, onAnimeClick, Modifier.animateItem())
                        }
                        // Sentinel: keeps loading more while the bottom of the grid is in view.
                        item(key = "more_${section.key}") {
                            LaunchedEffect(section.items.size) { viewModel.loadMoreSection(section.key) }
                            Box(Modifier.fillMaxWidth().height(8.dp))
                        }
                    } else {
                        item(key = "r_${section.key}") {
                            AnimeRow(
                                section.items,
                                cardWidth,
                                onAnimeClick,
                                Modifier.animateItem(),
                                firstCardFocus = if (section.key == firstSectionKey && state.continueWatching.isEmpty()) tvFocus else null,
                            ) {
                                viewModel.loadMoreSection(section.key)
                            }

                        }
                    }
                }

                if (!state.isLoading && state.sections.isEmpty()) {
                    item(key = "empty") {
                        Text(state.error ?: "Пусто.", color = TextSecondary, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }

        // Прозрачность шапки — ЛЯМБДОЙ, а не значением. Раньше firstVisibleItemScrollOffset
        // читался прямо здесь, в области композиции HomeScreen: смещение меняется на каждом
        // кадре прокрутки, поэтому весь экран (BoxWithConstraints, PullToRefreshBox и
        // content-лямбда LazyColumn со всеми секциями) перекомпоновывался ~60 раз в секунду
        // при любом скролле. Теперь значение читается внутри drawBehind — фаза отрисовки,
        // без композиции и без перемера.
        StickyHeader(
            alpha = {
                if (listState.firstVisibleItemIndex > 0) 0.85f
                else (listState.firstVisibleItemScrollOffset / 240f).coerceIn(0f, 0.85f)
            },
            hazeState = hazeState.takeIf { blurCapable },
        )

        // derivedStateOf: сам индекс меняется постоянно, а нам нужен только момент
        // пересечения порога — без него кнопка «наверх» тоже дёргала весь экран.
        val showBackToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(20.dp),
            horizontalAlignment = Alignment.End,
        ) {
            // "Back to top" — shown once you've scrolled down (handy in expanded grids).
            if (showBackToTop) {
                SmallFloatingActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    modifier = Modifier.padding(bottom = 12.dp),
                    containerColor = Surface2,
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Наверх", tint = TextPrimary)
                }
            }
            FloatingActionButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.pickRandom()
                },
                containerColor = AccentPurple,
            ) {
                Icon(Icons.Filled.Shuffle, contentDescription = "Случайное аниме", tint = TextPrimary)
            }
        }
    }
}

@Composable
private fun ContinueRow(
    items: List<ContinueWatchingItem>,
    cardWidth: androidx.compose.ui.unit.Dp,
    onResume: (String, String) -> Unit,
    firstCardFocus: androidx.compose.ui.focus.FocusRequester? = null,
) {
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        itemsIndexed(items, key = { _, item -> item.anime.id + item.segment.id }, contentType = { _, _ -> "continue" }) { index, item ->
            Column(Modifier.width(cardWidth).padding(end = 12.dp)) {
                PosterCard(
                    title = item.anime.title,
                    posterUrl = item.anime.poster,
                    rating = item.anime.rating,
                    ratingMax = item.anime.ratingMax,
                    cellWidth = cardWidth,
                    focusRequester = if (index == 0) firstCardFocus else null,
                    onClick = { onResume(item.anime.id, item.segment.id) },
                )
                LinearProgressIndicator(
                    progress = { item.fraction },
                    color = AccentOrange,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun StickyHeader(alpha: () -> Float, hazeState: HazeState?) {
    // Фон и нижняя черта рисуются в drawBehind: alpha() читает позицию скролла в фазе
    // отрисовки, поэтому изменение прозрачности при прокрутке не вызывает ни одной
    // перекомпоновки. Раньше и заливка, и `if (alpha > 0.5f)` для черты жили в
    // композиции — на каждый кадр скролла.
    val stripe = remember { androidx.compose.ui.text.TextStyle(
        brush = com.aniblaze.ui.components.BrandGradient,
        fontSize = 24.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.5).sp,
    ) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(
                if (hazeState != null) {
                    Modifier.hazeChild(state = hazeState, style = HazeMaterials.ultraThin())
                } else {
                    Modifier
                },
            )
            .drawBehind {
                val a = alpha()
                drawRect(OledBlack.copy(alpha = a * 0.35f))
                if (a > 0.5f) {
                    drawRect(
                        color = GlassBorder,
                        topLeft = Offset(0f, size.height - 1f),
                        size = Size(size.width, 1f),
                    )
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text("AniBlaze", style = stripe, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun CategoryHeader(title: String, expanded: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.aniblaze.ui.components.AccentBar()
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
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

// Список сезонов — константа; раньше он пересоздавался на каждой пересборке чипов.
private val SEASONS = listOf(1 to "Зима", 2 to "Весна", 3 to "Лето", 4 to "Осень")

@Composable
private fun SeasonChips(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val seasons = SEASONS
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        seasons.forEach { (s, label) ->
            val active = s == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (active) AccentOrange else Surface2)
                    .clickable { onSelect(s) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (active) OledBlack else TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun GridRow(
    row: List<Anime>,
    columns: Int,
    cellWidth: androidx.compose.ui.unit.Dp,
    onAnimeClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
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
                    onClick = { onAnimeClick(anime.id) },
                )
            }
        }
        repeat(columns - row.size) { Box(Modifier.weight(1f)) }
    }
}

@Composable
private fun AnimeRow(
    items: List<Anime>,
    cardWidth: androidx.compose.ui.unit.Dp,
    onAnimeClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    firstCardFocus: androidx.compose.ui.focus.FocusRequester? = null,
    onLoadMore: () -> Unit,
) {
    val rowState = rememberLazyListState()
    // layoutInfo пересобирается на КАЖДОМ кадре прокрутки, и snapshotFlow по нему
    // просыпался столько же раз — шестьдесят пробуждений корутины в секунду на каждый
    // ряд экрана. derivedStateOf считает то же самое, но наружу отдаёт только смену
    // самого ответа «пора подгружать»: пока ответ не изменился, никто не просыпается.
    val needsMore by remember(items.size) {
        derivedStateOf {
            val last = rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            items.isNotEmpty() && last >= items.size - 3
        }
    }
    LaunchedEffect(needsMore) { if (needsMore) onLoadMore() }
    LazyRow(
        state = rowState,
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.id }, contentType = { _, _ -> "poster" }) { index, anime ->
            Box(Modifier.width(cardWidth).padding(end = 10.dp)) {
                PosterCard(
                    anime.title,
                    anime.poster,
                    anime.rating,
                    ratingMax = anime.ratingMax,
                    cellWidth = cardWidth,
                    focusRequester = if (index == 0) firstCardFocus else null,
                    onClick = { onAnimeClick(anime.id) },
                )
            }
        }
    }
}

@Composable
private fun SkeletonRow() {
    LazyRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        // items(count) вместо items(List(5){it}) — не создаём список ради счётчика.
        items(5) {
            Skeleton(Modifier.width(140.dp).height(210.dp).padding(end = 12.dp))
        }
    }
}
