package com.aniblaze.app.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.aniblaze.ui.components.EmptyState
import com.aniblaze.ui.components.posterUrl
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.AccentPink
import com.aniblaze.ui.theme.OledBlack
import com.aniblaze.ui.theme.Surface2
import com.aniblaze.ui.theme.Surface3
import com.aniblaze.ui.theme.TextPrimary
import com.aniblaze.ui.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * «Статистика» — что накопилось за всё время просмотра.
 *
 * Перенос с ПК, но НЕ копия вёрстки: там четыре плитки в ряд на широком окне, здесь
 * телефон шириной в 360 dp. Что изменено и почему — в комментариях у каждого места.
 * Считается всё в [StatsViewModel]; сюда приходят готовые числа.
 */

/**
 * Что уже проиграно за этот запуск.
 *
 * Экран пересоздаётся при каждом уходе на другую вкладку и возврате, поэтому
 * состояние входа обязано жить СНАРУЖИ композиции — иначе анимации набора и роста
 * заново отыгрываются при каждом заходе, хотя цифры те же самые.
 */
private object StatsSession {
    var introPlayed = false
}

@Composable
fun StatsScreen(
    onAnimeClick: (String) -> Unit = {},
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    // Первый заход показывает вход; дальше цифры просто стоят на месте.
    val intro = remember { !StatsSession.introPlayed }
    LaunchedEffect(Unit) { StatsSession.introPlayed = true }

    Box(Modifier.fillMaxSize()) {
        AuroraBackdrop(intro)
        val data = stats
        when {
            // Пока считаем — пусто, без крутилки: расчёт локальный и занимает
            // десятки миллисекунд, мигать спиннером ради этого не за чем.
            data == null -> Unit
            data.episodes == 0 -> EmptyState("Пока пусто — посмотри пару серий, и здесь появятся цифры.")
            else -> StatsContent(data, intro, onAnimeClick)
        }
    }
}

@Composable
private fun StatsContent(stats: Stats, intro: Boolean, onAnimeClick: (String) -> Unit) {
    val tiles = remember(stats) { tilesOf(stats) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Телефон — ОДИН столбец плиток. Четыре в ряд, как на ПК, дают по 80 dp на
        // плитку: цифра влезает, подпись — нет. На планшете и в развёрнутой
        // «книжке» места хватает на две.
        val columns = if (maxWidth >= 600.dp) 2 else 1
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.padding(bottom = 2.dp)) {
                    Text("Статистика", color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Всё, что накопилось за ${stats.daysActive} " +
                            "${plural(stats.daysActive, "день", "дня", "дней")} у экрана.",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            itemsIndexed(tiles.chunked(columns)) { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEachIndexed { index, tile ->
                        StatTile(
                            title = tile.title,
                            value = tile.value,
                            caption = tile.caption,
                            modifier = Modifier.weight(1f),
                            delayMs = (rowIndex * columns + index) * 80,
                            animate = intro,
                        )
                    }
                    // Нечётный хвост на двух колонках: пустое место, а не растянутая
                    // на всю ширину одинокая плитка.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            if (stats.genres.isNotEmpty()) {
                item {
                    StatsCard("Любимые жанры") {
                        stats.genres.forEachIndexed { index, (name, count) ->
                            BarRow(
                                label = name,
                                value = count,
                                fraction = count.toFloat() / stats.genres.first().second,
                                delayMs = index * 70,
                                animate = intro,
                                suffix = plural(count, "тайтл", "тайтла", "тайтлов"),
                            )
                        }
                    }
                }
            }

            item {
                StatsCard("Шлакометр") {
                    if (stats.trashJudged > 0) {
                        TrashMeter(stats, intro)
                    } else {
                        // Честно вместо пустой шкалы: на телефоне неоткуда взять
                        // оценки MyAnimeList, а на пятизвёздочной оценке Anixart
                        // такой подсчёт уже пробовали — он врёт (см. TrashAnime).
                        Text(
                            "Судить не по чему: приложение пока не сохраняет оценки MyAnimeList. " +
                                "Тайтл участвует в подсчёте от ${TrashAnime.MIN_VOTES} голосов — " +
                                "у меньшего числа доля низких оценок скачет от десятка человек.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            if (stats.hourly.any { it > 0 }) {
                item {
                    StatsCard("Когда смотришь") {
                        HourHistogram(stats.hourly, intro)
                        Text(
                            "Пик в ${stats.peakHour}:00 — " +
                                "${stats.hourly[stats.peakHour]} " +
                                plural(stats.hourly[stats.peakHour], "серия", "серии", "серий") + " оттуда.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }

            if (stats.topTitles.isNotEmpty()) {
                item {
                    StatsCard("Больше всего серий") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(scaled(10.dp))) {
                            items(stats.topTitles, key = { it.title.id }) { entry ->
                                TopTitleCard(entry) { onAnimeClick(entry.title.id) }
                            }
                        }
                    }
                }
            }

            if (stats.studios.isNotEmpty()) {
                item {
                    StatsCard("Студии") {
                        stats.studios.forEachIndexed { index, (name, count) ->
                            BarRow(
                                label = name,
                                value = count,
                                fraction = count.toFloat() / stats.studios.first().second,
                                delayMs = index * 70,
                                animate = intro,
                                suffix = plural(count, "тайтл", "тайтла", "тайтлов"),
                            )
                        }
                    }
                }
            }

            if (stats.decades.isNotEmpty()) {
                item {
                    StatsCard("По годам выпуска") {
                        val peak = stats.decades.maxOf { it.second }
                        stats.decades.forEachIndexed { index, (year, count) ->
                            BarRow(
                                label = year,
                                value = count,
                                fraction = count.toFloat() / peak,
                                delayMs = index * 60,
                                animate = intro,
                                suffix = plural(count, "тайтл", "тайтла", "тайтлов"),
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// --- плитки ---

private data class Tile(val title: String, val value: Int, val caption: String)

/** Плитки, у которых есть что показать: нули не занимают экран. */
private fun tilesOf(stats: Stats): List<Tile> = buildList {
    add(Tile("Тайтлов", stats.titles, "начато"))
    if (stats.finishedTitles > 0) add(Tile("Досмотрено", stats.finishedTitles, "тайтлов до конца"))
    add(Tile("Серий", stats.episodes, "просмотрено"))
    if (stats.hours > 0) add(Tile("Часов", stats.hours, "у экрана"))
    if (stats.streak > 0) add(Tile("Дней подряд", stats.streak, "серия не рвётся"))
    if (stats.busiestDayEpisodes > 0) add(Tile("Марафон", stats.busiestDayEpisodes, "серий за один день"))
    if (stats.longestDayMinutes > 0) add(Tile("Рекорд за день", stats.longestDayMinutes, "минут"))
    if (stats.averageMinutesPerDay > 0) add(Tile("В среднем", stats.averageMinutesPerDay, "минут в день"))
}

// --- оформление ---

/**
 * Размер, который растёт вместе с системным масштабом шрифта.
 *
 * Настройка размера текста в Android множит `Density.fontScale`, поэтому сам собой
 * масштабируется только текст (sp), а размеры в dp остаются прежними. Плитки и
 * отступы, заданные в dp, из-за этого не поспевали за подросшим текстом: название
 * переносилось на три строки, а строка «MAL 6,27 · 22% низких» рвалась пополам.
 * Здесь dp домножается на тот же коэффициент — плитка растёт ровно настолько же,
 * насколько буквы.
 */
@Composable
private fun scaled(base: Dp): Dp = base * LocalDensity.current.fontScale

/**
 * Сияние на фоне — ОДИН проход при открытии, дальше картинка стоит.
 *
 * На ПК здесь был бесконечный цикл, и он перерисовывал весь экран каждый кадр всё
 * время, пока раздел открыт: снаружи это выглядит как «анимация не прекращается», а
 * по факту это ещё и постоянная нагрузка на видеокарту ради фона, который никто не
 * разглядывает. На телефоне цена такого цикла — ещё и батарея.
 */
@Composable
private fun AuroraBackdrop(animate: Boolean = true) {
    val shift = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (animate) shift.animateTo(1f, tween(2_400, easing = LinearEasing)) }
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFF1A0E0A).copy(alpha = 0.9f),
                    Color(0xFF120C18).copy(alpha = 0.7f),
                    OledBlack,
                ),
                start = Offset(0f, 900f * shift.value),
                end = Offset(1400f * (1f - shift.value) + 400f, 1600f),
            ),
        ),
    )
}

/**
 * Крупная цифра, которая набирается от нуля.
 *
 * На ПК плитка вертикальная: подпись, число, пояснение. На телефоне столбец такой
 * же ширины, что и экран, и вертикальная плитка занимала бы четверть высоты на
 * четыре слова. Поэтому число слева, подписи справа: высота ~64 dp, а цифра при
 * этом КРУПНЕЕ ПКшной относительно ширины.
 */
@Composable
private fun StatTile(
    title: String,
    value: Int,
    caption: String,
    modifier: Modifier = Modifier,
    delayMs: Int = 0,
    animate: Boolean = true,
) {
    // Повторный заход в раздел показывает готовые цифры: начальное состояние сразу
    // «конечное», и переход просто не запускается.
    var start by remember(value) { mutableStateOf(!animate) }
    LaunchedEffect(value) { start = true }
    val transition = updateTransition(start, label = "count")
    val shown by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 900, delayMillis = delayMs) },
        label = "value",
    ) { if (it) value.toFloat() else 0f }
    val lift by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 500, delayMillis = delayMs) },
        label = "lift",
    ) { if (it) 0f else 14f }

    Row(
        modifier
            .padding(top = lift.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1B1B22).copy(alpha = 0.95f), Color(0xFF121218).copy(alpha = 0.95f)),
                ),
            )
            .padding(horizontal = scaled(14.dp), vertical = scaled(12.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            shown.roundToInt().toString(),
            color = AccentOrange,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            // Минимум под трёхзначное число: подписи соседних плиток начинаются на
            // одной вертикали, пока цифры не разъехались.
            modifier = Modifier.widthIn(min = scaled(46.dp)),
        )
        Column(Modifier.padding(start = scaled(10.dp))) {
            Text(
                title.uppercase(),
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                caption,
                color = TextPrimary,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

@Composable
private fun StatsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF15151B).copy(alpha = 0.95f))
            .padding(16.dp),
    ) {
        Text(
            title,
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        content()
    }
}

/**
 * Полоса, вырастающая слева направо с задержкой — ряды заполняются волной.
 *
 * На ПК всё в одну строку: подпись 150 dp, полоса, число 96 dp. На телефоне от 360
 * dp минус поля карточки под полосу оставалось бы около сотни точек, и она читалась
 * бы как чёрточка. Поэтому подпись и число сверху, полоса — во всю ширину под ними.
 */
@Composable
private fun BarRow(
    label: String,
    value: Int,
    fraction: Float,
    delayMs: Int,
    suffix: String,
    animate: Boolean = true,
) {
    var start by remember(label, value) { mutableStateOf(!animate) }
    LaunchedEffect(label, value) { start = true }
    val grown by animateFloatAsState(
        targetValue = if (start) fraction.coerceIn(0.02f, 1f) else 0f,
        animationSpec = tween(durationMillis = 700, delayMillis = delayMs),
        label = "bar",
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                label,
                color = TextPrimary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$value $suffix",
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 5.dp)
                .height(scaled(10.dp))
                .clip(RoundedCornerShape(5.dp))
                .background(Surface3),
        ) {
            Box(
                Modifier.fillMaxWidth(grown).fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(Brush.horizontalGradient(listOf(AccentOrange, Color(0xFFFFA24D)))),
            )
        }
    }
}

/**
 * Сутки в 24 столбика — сразу видно, что смотришь ночью.
 *
 * Отличия от ПК ради узкого экрана: зазор 2 dp вместо 4 (иначе на 360 dp от столбика
 * остаётся волосок) и подписи через шесть часов вместо трёх — восемь цифр по 9 sp в
 * ряд уже сливаются.
 */
@Composable
private fun HourHistogram(hourly: List<Int>, animate: Boolean = true) {
    val peak = (hourly.maxOrNull() ?: 1).coerceAtLeast(1)
    val barMax = scaled(76.dp)
    Row(
        Modifier.fillMaxWidth().height(scaled(96.dp)),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        hourly.forEachIndexed { hour, count ->
            var start by remember(hourly) { mutableStateOf(!animate) }
            LaunchedEffect(hourly) { start = true }
            val height by animateFloatAsState(
                targetValue = if (start) count.toFloat() / peak else 0f,
                animationSpec = tween(durationMillis = 650, delayMillis = hour * 22),
                label = "hour",
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth()
                        .height(barMax * height.coerceAtLeast(0.03f))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(
                            if (count == peak) {
                                Brush.verticalGradient(listOf(Color(0xFFFFA24D), AccentOrange))
                            } else {
                                Brush.verticalGradient(listOf(Color(0xFF3A3A48), Color(0xFF23232C)))
                            },
                        ),
                )
                if (hour % 6 == 0) {
                    Text("$hour", color = TextSecondary, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

/**
 * Шкала «шлачности» с приговором и списком главных обвиняемых.
 *
 * Шкала намеренно не красная: это шутка про вкус, а не претензия. Заполнение растёт
 * от оранжевого к малиновому — чем дальше, тем «горячее».
 */
@Composable
private fun TrashMeter(stats: Stats, animate: Boolean) {
    val fill by animateFloatAsState(
        stats.trashPercent / 100f,
        tween(900, delayMillis = if (animate) 120 else 0, easing = FastOutSlowInEasing),
        label = "trashFill",
    )
    // На ПК число и приговор стоят в одну строку. «Половина — то, что ругают» рядом
    // с крупной цифрой на 360 dp не помещается, поэтому приговор — отдельной строкой.
    Row(verticalAlignment = Alignment.Bottom) {
        Text("${stats.trashPercent}", fontSize = 42.sp, fontWeight = FontWeight.Bold, color = AccentOrange)
        Text(
            "%",
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            color = AccentOrange,
            modifier = Modifier.padding(bottom = 5.dp),
        )
    }
    Text(
        TrashAnime.verdict(stats.trashPercent),
        color = TextPrimary,
        fontSize = 15.sp,
        modifier = Modifier.padding(top = 2.dp),
    )
    Text(
        "${stats.trashTitles} из ${stats.trashJudged} " +
            plural(stats.trashJudged, "тайтла", "тайтлов", "тайтлов") +
            " — в нижней четверти каталога MyAnimeList (ниже 6.38) либо каждая седьмая " +
            "оценка «4 и ниже». Считается по голосам людей, а не по жанру.",
        color = TextSecondary,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
    )
    if (stats.trashAverageScore > 0.0) {
        Text(
            "Твоя средняя оценка по MAL: %.2f — %s (медиана %.2f).".format(
                stats.trashAverageScore,
                TrashAnime.tasteVerdict(stats.trashAverageScore),
                TrashAnime.CATALOG_MEDIAN,
            ),
            color = TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
    }
    Box(
        Modifier.fillMaxWidth().height(scaled(12.dp)).clip(RoundedCornerShape(6.dp)).background(Surface3),
    ) {
        Box(
            Modifier.fillMaxWidth(fill.coerceIn(0f, 1f)).fillMaxHeight()
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.horizontalGradient(listOf(AccentOrange, AccentPink))),
        )
    }
    if (stats.trashTop.isNotEmpty()) {
        Text(
            "Больше всего примет",
            color = TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(scaled(10.dp))) {
            items(stats.trashTop, key = { it.id }) { anime ->
                val cardWidth = scaled(108.dp)
                Column(Modifier.width(cardWidth)) {
                    PosterImage(anime.poster, anime.title, cardWidth, scaled(152.dp))
                    Text(
                        anime.title,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    // Голоса ПОКАЗЫВАЕМ: плашка без цифры выглядит как вкусовщина,
                    // а с цифрой видно, что это чужое мнение, а не моё суждение.
                    Text(
                        "MAL %.2f · %d%% низких".format(
                            anime.malScore,
                            TrashAnime.lowShare(anime.malLowVotes, anime.malVotes).roundToInt(),
                        ),
                        fontSize = 11.sp,
                        color = AccentOrange,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TopTitleCard(entry: TitleCount, onClick: () -> Unit) {
    val cardWidth = scaled(112.dp)
    Column(
        Modifier.width(cardWidth).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    ) {
        Box {
            PosterImage(entry.title.poster, entry.title.title, cardWidth, scaled(158.dp))
            Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentOrange)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text("${entry.episodes}", color = OledBlack, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            entry.title.title,
            color = TextPrimary,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Постер ЧЕРЕЗ posterUrl, как везде в приложении: адрес идёт через тот же
 * пережимающий прокси, и картинка заказывается под реальную ширину плитки, а не
 * в оригинале на полтора мегабайта.
 */
@Composable
private fun PosterImage(poster: String, title: String, width: Dp, height: Dp) {
    val widthPx = with(LocalDensity.current) { width.roundToPx() }
    AsyncImage(
        model = posterUrl(poster, widthPx),
        contentDescription = title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().height(height)
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2),
    )
}
