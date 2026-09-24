package com.aniblaze.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.desktop.AppSettings
import com.aniblaze.desktop.DesktopRepository
import com.aniblaze.desktop.toAnime
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.aniblaze.aggregator.model.EpisodeSchedule
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Порядок карточек в избранном. */
enum class FavoritesOrder(val label: String) {
    ADDED("По дате добавления"),
    TITLE("По названию"),
    RATING("По оценке"),
}

/** Выпадашка порядка — справа от вкладок, как в остальных списках приложения. */
@Composable
private fun FavoritesOrderMenu(order: FavoritesOrder, onSelect: (FavoritesOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        Row(
            Modifier.clip(Shapes.pill).background(GlassFill).clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(order.label, color = TextPrimary, fontSize = 13.sp)
            androidx.compose.material3.Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = "Порядок",
                tint = TextSecondary,
            )
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FavoritesOrder.entries.forEach { entry ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(if (entry == order) "● ${entry.label}" else entry.label) },
                    onClick = { open = false; onSelect(entry) },
                )
            }
        }
    }
}

/** Состояние тайтла в избранном. Считается из истории, отдельного статуса нет. */
enum class FavoritesTab(val label: String) {
    WATCHING("Смотрю"),
    PLANNED("Запланировано"),
    DONE("Завершено"),
    ALL("Все"),
}

/** Вкладка-чип с числом тайтлов: активная — в акценте, как остальные фильтры. */
@Composable
private fun FavoritesTabChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(Shapes.pill)
            .background(if (selected) AccentOrange else GlassFill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) OledBlack else TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            count.toString(),
            color = if (selected) OledBlack.copy(alpha = 0.7f) else TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
fun FavoritesScreen(settings: AppSettings, repository: DesktopRepository, onOpen: (Anime) -> Unit) {
    val saved by remember(settings) { settings.state.map { it.favorites }.distinctUntilChanged() }
        .collectAsState(settings.state.value.favorites)
    val items = remember(saved) { saved.map { it.toAnime() } }
    val marks by remember(settings) { settings.state.map { it.episodeCounts to it.watched }.distinctUntilChanged() }
        .collectAsState(settings.state.value.episodeCounts to settings.state.value.watched)
    var schedules by remember { mutableStateOf<Map<String, EpisodeSchedule?>>(emptyMap()) }
    LaunchedEffect(items.map { it.id }) {
        val gate = Semaphore(4)
        coroutineScope {
            items.filterNot { schedules.containsKey(it.id) }.map { anime ->
                async {
                    val schedule = gate.withPermit { repository.nextEpisodeSchedule(anime) }
                    schedules = schedules + (anime.id to schedule)
                }
            }.forEach { it.await() }
        }
    }
    val subtitles = remember(schedules) { schedules.mapValues { it.value?.let(::scheduleLabel).orEmpty() } }
    // Гасим карточки, у которых последняя ИЗВЕСТНАЯ серия ещё не досмотрена.
    // Число серий берём из того, что уже подсчитано при открытии тайтла: лезть за
    // ним в сеть ради оформления списка нельзя — это десятки запросов на экран.
    val unfinished = remember(items, marks) {
        items.mapNotNull { anime ->
            val total = marks.first[anime.id] ?: return@mapNotNull null
            if (total > 0 && "${anime.id}#$total" !in marks.second) anime.id else null
        }.toSet()
    }
    // «Просмотрено» и «остановились на N серии» карточка теперь пишет сама — см.
    // AppSettings.watchIndex; отдельный список досмотренных тут больше не нужен.
    //
    // Раскладка по состоянию просмотра: тридцать постеров подряд не отвечали на
    // вопрос «что я не досмотрел». Состояние берётся из той же истории, что рисует
    // полосу на карточке, — отдельного «статуса тайтла» не заводим.
    var tab by remember { mutableStateOf(FavoritesTab.ALL) }
    val buckets = remember(items, marks) {
        items.groupBy { anime ->
            val watch = settings.watchOf(anime.id)
            when {
                watch == null -> FavoritesTab.PLANNED
                watch.finished -> FavoritesTab.DONE
                else -> FavoritesTab.WATCHING
            }
        }
    }
    var order by remember { mutableStateOf(FavoritesOrder.ADDED) }
    val shown = remember(tab, order, buckets, items, marks) {
        val base = if (tab == FavoritesTab.ALL) items else buckets[tab].orEmpty()
        when (order) {
            // «По дате добавления» — порядок хранения: новые записи в конце списка.
            FavoritesOrder.ADDED -> base.reversed()
            FavoritesOrder.TITLE -> base.sortedBy { it.title.lowercase() }
            FavoritesOrder.RATING -> base.sortedByDescending { it.rating / it.ratingMax.coerceAtLeast(1.0) }
        }
    }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        Text(
            "Избранное",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
        Text(
            "Твои любимые тайтлы всегда под рукой.",
            color = TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        )
        if (items.isNotEmpty()) {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FavoritesTab.entries.forEach { entry ->
                    val count = if (entry == FavoritesTab.ALL) items.size else buckets[entry]?.size ?: 0
                    // Пустая вкладка не показывается вовсе: «Завершено 0» — не фильтр,
                    // а лишняя кнопка.
                    if (count > 0 || entry == FavoritesTab.ALL) {
                        FavoritesTabChip(entry.label, count, entry == tab) { tab = entry }
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                FavoritesOrderMenu(order) { order = it }
            }
        }
        if (items.isEmpty()) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Пока пусто. Добавляйте тайтлы кнопкой ♥ или правым кликом по постеру.",
                    color = TextSecondary, modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            PosterGrid(
                shown,
                isLoading = false,
                subtitles = subtitles,
                dimmedIds = unfinished,
                onClick = onOpen,
            )
        }
    }
}

private fun scheduleLabel(schedule: EpisodeSchedule): String {
    val date = runCatching { LocalDate.parse(schedule.airDate) }.getOrNull() ?: return ""
    val days = ChronoUnit.DAYS.between(LocalDate.now(), date).toInt()
    val whenText = when {
        days < 0 -> "Новая серия уже вышла"
        days == 0 -> "Новая серия сегодня"
        days == 1 -> "Новая серия завтра"
        else -> "Новая серия через $days дн."
    }
    val episode = if (schedule.season > 0 && schedule.episode > 0) " · S${schedule.season}E${schedule.episode}" else ""
    return whenText + episode
}

@Composable
fun HistoryScreen(settings: AppSettings, onOpen: (Anime) -> Unit) {
    val saved by remember(settings) { settings.state.map { it.history }.distinctUntilChanged() }
        .collectAsState(settings.state.value.history)
    val items = remember(saved) { saved.map { it.toAnime() } }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("История", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { settings.clearHistory() }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Очистить")
            }
        }
        PosterGrid(items, isLoading = false, onClick = onOpen)
    }
}
