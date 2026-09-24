package com.aniblaze.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniblaze.aggregator.model.Anime
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val AUTO_POSTER_MAX_COLUMNS = 12
private const val AUTO_POSTER_MIN_WIDTH_DP = 132f
private const val POSTER_ROW_HORIZONTAL_PADDING_DP = 16f
private const val POSTER_ROW_SPACING_DP = 12f

/**
 * Реальное число карточек на доступной ширине.
 *
 * Явный выбор пользователя имеет приоритет и остаётся точным при любом resize.
 * `Авто` держит карточку не уже 132 dp и ограничивает плотность двенадцатью
 * колонками. Старый предел в восемь колонок делал широкое окно похожим на zoom:
 * свободное место оставалось пустым, а карточки становились непропорционально
 * крупными. На узких окнах число всё равно определяется доступной шириной.
 */
internal fun posterColumnCount(setting: Int, containerWidthDp: Float): Int {
    // 7/8 в текущем UI уже не предлагаются, но старое сохранённое значение уважаем.
    if (setting in 3..8) return setting
    val usable = (containerWidthDp - POSTER_ROW_HORIZONTAL_PADDING_DP * 2f).coerceAtLeast(1f)
    return ((usable + POSTER_ROW_SPACING_DP) / (AUTO_POSTER_MIN_WIDTH_DP + POSTER_ROW_SPACING_DP))
        .toInt()
        .coerceIn(1, AUTO_POSTER_MAX_COLUMNS)
}

/** Ширина одной карточки, при которой [columns] штук вместе с отступами ровно заполняют ряд. */
internal fun posterCardWidthDp(containerWidthDp: Float, columns: Int): Float {
    val safeColumns = columns.coerceAtLeast(1)
    val usable = containerWidthDp - POSTER_ROW_HORIZONTAL_PADDING_DP * 2f -
        POSTER_ROW_SPACING_DP * (safeColumns - 1)
    return (usable / safeColumns).coerceAtLeast(1f)
}

/**
 * A horizontal poster row like Netflix/Lampa: a titled strip of [PosterCard]s.
 * Because a mouse wheel can't scroll it sideways, mouse users get three ways to reach
 * the overflow — dragging the strip with the LEFT button held ([dragScroll]), chevron
 * arrows that appear on hover and page it, and a "Развернуть" toggle that reflows the
 * whole row into a wrapping grid.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PosterRow(
    title: String,
    items: List<Anime>,
    loading: Boolean,
    modifier: Modifier = Modifier,
    /** null = прочитать глобальную настройку; 0 = «Авто»; 3..6 = точное число. */
    gridColumns: Int? = null,
    onExpandAll: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    // Загрузка ряда упала (не «пусто», а именно не смогли). Показываем причину и
    // кнопку повтора вместо прочерка, который читался как «в категории пусто».
    failed: Boolean = false,
    onRetry: (() -> Unit)? = null,
    /** Проброс пункта «Открыть в новом окне» до карточек ленты; null — пункта нет. */
    onOpenInNewWindow: ((Anime) -> Unit)? = { com.aniblaze.desktop.DetachedTitles.openTitle(it) },
    /** Приписка справа от названия ряда — например «4 релиза» в расписании. */
    badge: String? = null,
    onOpen: (Anime) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Если экран не пробросил уже собранное поле, читаем только gridColumns из
    // глобальных настроек. Запись позиции плеера не пересобирает все карусели.
    val appSettings = LocalAppSettings.current
    val storedColumns = if (gridColumns == null && appSettings != null) {
        val value by remember(appSettings) {
            appSettings.state.map { it.gridColumns }.distinctUntilChanged()
        }.collectAsState(appSettings.state.value.gridColumns)
        value
    } else {
        0
    }
    val requestedColumns = gridColumns ?: storedColumns
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()

    // Infinite horizontal scroll: pull the next page as the strip nears its end, so
    // scrolling right (chevrons or trackpad) keeps loading more of this category.
    if (onLoadMore != null) {
        LaunchedEffect(listState, items.size) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .collect { last -> if (items.isNotEmpty() && last >= items.size - 4) onLoadMore() }
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
    val columns = posterColumnCount(requestedColumns, maxWidth.value)
    val cardWidth = posterCardWidthDp(maxWidth.value, columns).dp
    // Ширина карточки в пикселях: PosterCard по ней и постер у CDN заказывает, и число
    // строк/размер подписи подбирает. Она пересчитывается сразу при настройке и resize.
    val cardPx = with(LocalDensity.current) { cardWidth.roundToPx() }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            badge?.takeIf { it.isNotBlank() }?.let { text ->
                Text(text, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
            }
            when {
                // Cinema: "Развернуть" opens the whole category as an infinite-scroll grid.
                onExpandAll != null -> HeaderAction("Смотреть все →") { onExpandAll() }
                // Anime (no grid target): reflow the row into a wrapping grid inline.
                items.size > 3 -> HeaderIcon(
                    if (expanded) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore,
                    if (expanded) "Свернуть" else "Развернуть",
                ) { expanded = !expanded }
            }
        }

        if (expanded && onExpandAll == null) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items.forEach { a ->
                    androidx.compose.runtime.key(a.id) {
                        Box(Modifier.width(cardWidth)) {
                            PosterCard(
                                a,
                                posterWidthPx = cardPx,
                                onOpenInNewWindow = onOpenInNewWindow,
                                onClick = { onOpen(a) },
                            )
                        }
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().hoverable(hoverSource)) {
                if (loading && items.isEmpty()) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(maxOf(columns, 6)) { Box(Modifier.width(cardWidth)) { PosterSkeleton(Modifier.fillMaxWidth()) } }
                    }
                } else if (failed && items.isEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Не удалось загрузить — источник не ответил.", color = TextSecondary, fontSize = 13.sp)
                        if (onRetry != null) {
                            Box(
                                Modifier.padding(start = 12.dp).clip(RoundedCornerShape(8.dp))
                                    .clickable(onClick = onRetry).padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text("Повторить", color = AccentOrange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else if (items.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { Text("—", color = TextSecondary) }
                } else {
                    LazyRow(
                        state = listState,
                        // Левая кнопка тянет ленту (см. dragScroll). Модификатор
                        // стоит СНАРУЖИ прокрутки самого LazyRow, иначе он не увидит
                        // нажатие первым и его перехватят карточки.
                        modifier = Modifier.dragScroll(listState),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(items, key = { it.id }) { a ->
                            Box(Modifier.width(cardWidth)) {
                                // contextMenu снова включено: правую кнопку больше
                                // никто не перехватывает, лента тянется левой.
                                PosterCard(
                                    a,
                                    posterWidthPx = cardPx,
                                    onOpenInNewWindow = onOpenInNewWindow,
                                    onClick = { onOpen(a) },
                                )
                            }
                        }
                    }
                    // Hover chevrons page the strip by ~one viewport (mouse-friendly).
                    val viewport = (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset).toFloat().takeIf { it > 0 } ?: 900f
                    if (hovered && listState.canScrollBackward) {
                        ScrollChevron(Alignment.CenterStart, left = true) { scope.launch { listState.animateScrollBy(-viewport * 0.9f) } }
                    }
                    if (hovered && listState.canScrollForward) {
                        ScrollChevron(Alignment.CenterEnd, left = false) { scope.launch { listState.animateScrollBy(viewport * 0.9f) } }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun HeaderAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, color = AccentOrange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HeaderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(6.dp),
    ) {
        Icon(icon, contentDescription = desc, tint = TextSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ScrollChevron(align: Alignment, left: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.align(align).padding(horizontal = 6.dp).size(40.dp).clip(CircleShape)
            .background(Color(0xCC101018)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (left) Icons.Filled.KeyboardArrowLeft else Icons.Filled.KeyboardArrowRight,
            contentDescription = if (left) "Назад" else "Вперёд",
            tint = Color.White,
            modifier = Modifier.size(26.dp),
        )
    }
}
