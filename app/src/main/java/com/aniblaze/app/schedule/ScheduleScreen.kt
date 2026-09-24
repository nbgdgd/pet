package com.aniblaze.app.schedule

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.aniblaze.ui.components.AccentBar
import com.aniblaze.ui.components.EmptyState
import com.aniblaze.ui.components.ErrorState
import com.aniblaze.ui.components.LoadingState
import com.aniblaze.ui.components.glass
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.OledBlack
import com.aniblaze.ui.theme.TextPrimary
import com.aniblaze.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Расписание выхода серий. На ПК это семь горизонтальных постерных рядов; на
 * телефоне такой ряд превращается в слепое горизонтальное листание, поэтому здесь
 * один столбец компактных карточек с залипающим заголовком дня, а переключение
 * дней вынесено в горизонтальные вкладки над списком.
 */
@Composable
fun ScheduleScreen(
    onAnimeClick: (String) -> Unit,
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val current = state) {
        ScheduleUiState.Loading -> LoadingState()
        ScheduleUiState.Empty -> EmptyState("Расписание недоступно.")
        ScheduleUiState.Failed -> ErrorState(
            message = "Не удалось загрузить расписание.",
            onRetry = viewModel::refresh,
        )
        is ScheduleUiState.Ready -> ScheduleContent(current.days, onAnimeClick)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScheduleContent(days: List<ScheduleDay>, onAnimeClick: (String) -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Подсветка вкладки идёт за списком, а не наоборот: пролистал до среды — вкладка
    // сама переехала. derivedStateOf держит пересчёт на смене первого видимого
    // элемента, иначе поиск дня повторялся бы на каждом кадре скролла.
    val selected by remember(days) {
        derivedStateOf {
            days.indexOfLast { it.headerIndex <= listState.firstVisibleItemIndex }
                .coerceAtLeast(0)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = selected,
            containerColor = Color.Transparent,
            contentColor = AccentOrange,
            edgePadding = 12.dp,
            divider = {},
        ) {
            days.forEachIndexed { index, day ->
                Tab(
                    selected = index == selected,
                    // scrollToItem, а не animateScrollToItem: до дальнего дня недели
                    // анимация проматывает весь список и занимает секунды.
                    onClick = { scope.launch { listState.scrollToItem(day.headerIndex) } },
                    selectedContentColor = AccentOrange,
                    unselectedContentColor = TextSecondary,
                    text = {
                        Text(
                            day.tabLabel,
                            fontSize = 13.sp,
                            fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Medium,
                        )
                    },
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            days.forEach { day ->
                stickyHeader(key = "day-${day.weekday}") {
                    DayHeader(day.label, day.titles.size)
                }
                items(day.titles, key = { "${day.weekday}-${it.id}" }) { title ->
                    ScheduleTitleCard(title) { onAnimeClick(title.id) }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(label: String, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            // Залипающий заголовок едет ПОВЕРХ карточек, поэтому фон обязан быть
            // непрозрачным — иначе под ним просвечивают постеры.
            .background(OledBlack)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccentBar()
        Spacer(Modifier.width(10.dp))
        Text(label, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text("$count", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Ширина миниатюры: при 2:3 это 84dp высоты — строка списка остаётся строкой,
 *  а не занимает пол-экрана, как полноразмерный постер в одну колонку. */
private val ThumbWidth = 56.dp

@Composable
private fun ScheduleTitleCard(item: ScheduleTitle, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .glass(14.dp)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.poster,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(ThumbWidth)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.meta.isNotEmpty()) {
                Text(
                    item.meta,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (item.ratingLabel.isNotEmpty()) {
            Row(
                Modifier.padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = AccentOrange,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    " ${item.ratingLabel}",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
