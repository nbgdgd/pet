package com.aniblaze.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.runtime.key
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniblaze.aggregator.model.Anime

/**
 * «Расписание» — the ongoing calendar: one poster row per weekday, today first,
 * so "когда что выходит" is one glance instead of scanning the ongoing feed.
 * Note the dates are JAPANESE air days; dubs land ~a day later (the notifier
 * counts real episodes at the source for exactly that reason).
 */
@Composable
fun ScheduleScreen(
    repository: com.aniblaze.desktop.DesktopRepository,
    onOpen: (Anime) -> Unit,
) {
    var schedule by remember { mutableStateOf<Map<Int, List<Anime>>?>(null) }
    LaunchedEffect(Unit) {
        schedule = runCatching { repository.weekSchedule() }.getOrDefault(emptyMap())
    }

    val days = schedule
    when {
        days == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AccentOrange)
        }
        days.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Расписание недоступно.", color = TextSecondary)
        }
        else -> {
            val today = java.time.LocalDate.now().dayOfWeek.value // 1=Mon..7=Sun
            // Today first, then the rest of the week in airing order.
            val order = (0..6).map { ((today - 1 + it) % 7) + 1 }
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "header") {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                        Text("Расписание", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Не пропусти новые серии. Время указано по твоему часовому поясу.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                order.forEach { day ->
                    val titles = days[day].orEmpty()
                    if (titles.isEmpty()) return@forEach
                    item(key = "day$day") {
                        ScheduleDay(
                            title = dayLabel(day, today),
                            badge = "${titles.size} ${releaseWord(titles.size)}",
                            titles = titles,
                            onOpen = onOpen,
                        )
                    }
                }
                item { Box(Modifier.height(24.dp)) }
            }
        }
    }
}

/**
 * День расписания: заголовок и плитки релизов в ряд.
 *
 * Плитка, а не постер: в расписании важны НЕ обложки, а «что и во сколько». Постер
 * тут работает значком, а строку занимают название, номер серии и время выхода —
 * ровно то, ради чего на этот экран заходят.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleDay(title: String, badge: String, titles: List<Anime>, onOpen: (Anime) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(badge, color = TextSecondary, fontSize = 13.sp)
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            titles.forEach { anime ->
                key(anime.id) { ScheduleTile(anime) { onOpen(anime) } }
            }
        }
    }
}

@Composable
private fun ScheduleTile(anime: Anime, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        // Высота плитки ФИКСИРОВАННАЯ, и это главное: у части тайтлов номер серии
        // известен, у части нет, и плитки «по содержимому» давали рваные ряды разной
        // высоты. Одна высота — ровная сетка, как в макете.
        Modifier.width(scaledForFont(SCHEDULE_TILE_WIDTH))
            .height(scaledForFont(SCHEDULE_TILE_HEIGHT))
            .clip(Shapes.card)
            .background(if (hovered) Surface3 else Surface2)
            .border(BorderStroke(1.dp, if (hovered) AccentOrange.copy(alpha = 0.5f) else GlassBorder), Shapes.card)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = posterUrl(anime.poster, 240),
            contentDescription = null,
            modifier = Modifier.size(width = scaledForFont(SCHEDULE_POSTER_HEIGHT) * 2 / 3, height = scaledForFont(SCHEDULE_POSTER_HEIGHT))
                .clip(Shapes.chip),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                anime.title,
                color = if (hovered) AccentOrange else TextPrimary,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Нижняя строка одинаковая у всех: время слева, серия справа. Чего не
            // знаем — просто не рисуем, но строка на месте, и плитки не скачут.
            Row(verticalAlignment = Alignment.CenterVertically) {
                airTimeLabel(anime)?.let { time ->
                    Row(
                        Modifier.clip(Shapes.chip)
                            .background(AccentOrange.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Schedule, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(12.dp))
                        Text(
                            time,
                            color = AccentOrange,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                }
                anime.episodesAvailable.takeIf { it > 0 }?.let { episode ->
                    Text(
                        "Серия $episode",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}

/** Время выхода серии в зоне пользователя; null — источник времени не назвал. */
private fun airTimeLabel(anime: Anime): String? {
    val at = anime.episodeReleasedAt.takeIf { it > 0 } ?: anime.episodeEstimatedAt.takeIf { it > 0 } ?: return null
    val local = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
    return "%02d:%02d".format(local.hour, local.minute)
}

/** Ширина плитки релиза: две строки названия и нижняя строка помещаются без обрыва. */
private val SCHEDULE_TILE_WIDTH = 330.dp

/** И её высота — одна на все плитки, чтобы ряды были ровными. */
private val SCHEDULE_TILE_HEIGHT = 108.dp

/** Высота обложки внутри плитки; ширина — две трети от неё. */
private val SCHEDULE_POSTER_HEIGHT = 84.dp

/** «4 релиза» — приписка справа от дня недели. */
private fun releaseWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "релизов"
        mod10 == 1 -> "релиз"
        mod10 in 2..4 -> "релиза"
        else -> "релизов"
    }
}

private val RU_DAYS = listOf(
    "Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье",
)

private fun dayLabel(day: Int, today: Int): String {
    val name = RU_DAYS[day - 1]
    return when (day) {
        today -> "$name · сегодня"
        (today % 7) + 1 -> "$name · завтра"
        else -> name
    }
}
