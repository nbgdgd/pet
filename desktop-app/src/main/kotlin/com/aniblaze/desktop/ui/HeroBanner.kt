package com.aniblaze.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.aniblaze.aggregator.model.Anime
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

/**
 * Витрина в начале «Главной»: приветствие и один крупный тайтл вместо ряда мелких
 * постеров.
 *
 * Зачем она нужна, если ниже и так десяток лент: у ленты нет ответа на вопрос «что
 * поставить прямо сейчас». Витрина этот ответ даёт — одно название, описание и две
 * кнопки, без перебора. Тайтлы берутся из УЖЕ загруженной секции, своего запроса
 * витрина не делает.
 *
 * Фон — градиент, а НЕ растянутый постер: широкой афиши источники не отдают, а
 * обложка 2:3, натянутая на полосу 1000×340, превращается в мыло. Обложка стоит
 * справа в своей пропорции и в своём размере, поэтому остаётся резкой.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HeroBanner(
    items: List<Anime>,
    onOpen: (Anime) -> Unit,
    modifier: Modifier = Modifier,
    /** «Смотреть» — сразу в плеер; null — кнопка ведёт на страницу тайтла. */
    onPlay: ((Anime) -> Unit)? = null,
) {
    val shown = remember(items) { items.take(HERO_COUNT) }
    // Листается по кругу: и точки, и стрелка, и автосмена берут индекс по модулю.
    if (shown.isEmpty()) return
    var index by remember(shown) { mutableIntStateOf(0) }
    LaunchedEffect(shown) {
        while (shown.size > 1) {
            kotlinx.coroutines.delay(ROTATE_MS)
            index = (index + 1) % shown.size
        }
    }
    val anime = shown[index % shown.size]

    Box(
        // Высота — по содержимому, но не ниже минимума: у одних тайтлов описание в
        // две строки, у других его нет вовсе, и жёсткая высота обрезала кнопки.
        modifier.fillMaxWidth().heightIn(min = scaledForFont(HERO_MIN_HEIGHT)).clip(Shapes.cardLg)
            // Горизонтальный свайп тачпада (или Shift+колесо) листает витрину. Только
            // горизонтальный: вертикальное колесо над витриной должно листать страницу.
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val dx = event.changes.firstOrNull()?.scrollDelta?.x ?: 0f
                if (kotlin.math.abs(dx) >= 0.5f && shown.size > 1) {
                    index = ((index + if (dx > 0) 1 else -1) % shown.size + shown.size) % shown.size
                    event.changes.forEach { it.consume() }
                }
            }
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF17131C), Color(0xFF120F18), Color(0xFF1C1420)),
                ),
            )
            .border(BorderStroke(1.dp, GlassBorder), Shapes.cardLg),
    ) {
        // Тёплое свечение под обложкой — единственный «фон» витрины: он повторяет
        // акцент приложения и не зависит от того, что за картинка пришла.
        Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(HERO_GLOW_WIDTH)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, GlowOrange))),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f, fill = false).widthIn(max = scaledForFont(HERO_TEXT_WIDTH))
                    .padding(start = 28.dp, top = 22.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Добро пожаловать в AniBlaze",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    "Больше, чем просто аниме. Истории, которые остаются с тобой.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    Modifier.padding(top = 18.dp).clip(Shapes.chip)
                        .background(AccentOrange.copy(alpha = 0.16f))
                        .border(BorderStroke(1.dp, AccentOrange.copy(alpha = 0.45f)), Shapes.chip)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(13.dp))
                    Text(
                        heroBadge(anime),
                        color = AccentOrange,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
                Text(
                    anime.title,
                    color = TextPrimary,
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp),
                )
                heroSubtitle(anime)?.let { line ->
                    Text(
                        line,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(Shapes.pill).background(AccentOrange)
                            .clickable { (onPlay ?: onOpen)(anime) }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = OledBlack, modifier = Modifier.size(18.dp))
                        Text(
                            "Смотреть",
                            color = OledBlack,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    Box(
                        Modifier.padding(start = 10.dp).clip(Shapes.pill)
                            .background(GlassFill)
                            .border(BorderStroke(1.dp, GlassBorder), Shapes.pill)
                            .clickable { onOpen(anime) }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        Text("Подробнее", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (shown.size in 2..HERO_DOTS_MAX) {
                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        shown.forEachIndexed { i, _ ->
                            Box(
                                Modifier.padding(end = 6.dp)
                                    .size(width = if (i == index) 18.dp else 7.dp, height = 7.dp)
                                    .clip(CircleShape)
                                    .background(if (i == index) AccentOrange else TextTertiary)
                                    .clickable { index = i },
                            )
                        }
                    }
                } else if (shown.size > HERO_DOTS_MAX) {
                    // Сорок точек — шум; счётчик говорит то же и не занимает строку.
                    Text(
                        "${index + 1} / ${shown.size}",
                        color = TextTertiary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // Обложка — в своём размере и в своей пропорции: ничего не растягивается.
            Crossfade(anime.id, label = "heroArt", modifier = Modifier.padding(end = 28.dp)) { id ->
                val art = shown.firstOrNull { it.id == id } ?: anime
                AsyncImage(
                    model = posterUrl(art.poster, HERO_POSTER_REQUEST_PX),
                    contentDescription = art.title,
                    modifier = Modifier.height(scaledForFont(HERO_POSTER_HEIGHT))
                        .width(scaledForFont(HERO_POSTER_HEIGHT) * 2 / 3)
                        .clip(Shapes.card)
                        .border(BorderStroke(1.dp, GlassBorder), Shapes.card)
                        .clickable { onOpen(art) },
                    contentScale = ContentScale.Crop,
                )
            }
            // Что будет следующим: две обложки поменьше и потусклее. Они занимают
            // правую часть полосы, ради которой иначе пришлось бы растягивать одну
            // картинку — и снова получать мыло.
            if (shown.size > 2) {
                val upcoming = remember(shown, index) {
                    (1..2).map { shown[(index + it) % shown.size] }
                }
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    upcoming.forEachIndexed { offset, next ->
                        AsyncImage(
                            model = posterUrl(next.poster, HERO_POSTER_REQUEST_PX),
                            contentDescription = next.title,
                            modifier = Modifier.padding(start = if (offset == 0) 0.dp else 10.dp)
                                .height(scaledForFont(HERO_NEXT_HEIGHT))
                                .width(scaledForFont(HERO_NEXT_HEIGHT) * 2 / 3)
                                .clip(Shapes.card)
                                .alpha(if (offset == 0) 0.65f else 0.4f)
                                .border(BorderStroke(1.dp, GlassBorder), Shapes.card)
                                .clickable { index = (index + offset + 1) % shown.size },
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
            if (shown.size > 1) {
                Box(
                    Modifier.padding(start = 14.dp, end = 16.dp).size(38.dp).clip(CircleShape)
                        .background(GlassFill)
                        .clickable { index = (index + 1) % shown.size },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Следующий", tint = TextPrimary)
                }
            }
        }
    }
}

/** Плашка над названием: чем именно этот тайтл интересен прямо сейчас. */
internal fun heroBadge(anime: Anime): String = when {
    // Шкалы у источников разные (5 и 10 баллов) — сравниваем долю, а не число.
    anime.rating > 0 && anime.ratingMax > 0 && anime.rating / anime.ratingMax >= 0.8 ->
        "Высокая оценка · %.1f".format(anime.rating)
    anime.episodeEstimatedAt > 0 -> "Выходит сейчас"
    anime.year > 0 -> "Рекомендуем к просмотру · ${anime.year}"
    else -> "Рекомендуем к просмотру"
}

/** Подпись под названием: описание тайтла, а если его нет — жанры. */
internal fun heroSubtitle(anime: Anime): String? = anime.description
    .takeIf { it.isNotBlank() }
    ?.replace(Regex("\\s+"), " ")
    ?: anime.genres.takeIf { it.isNotBlank() }

/** Сколько тайтлов крутится в витрине (по кругу). */
private const val HERO_COUNT = 40

/** До скольких тайтлов рисуем точки; дальше — счётчик. */
private const val HERO_DOTS_MAX = 8

/** Пауза между автоматическими переключениями. */
private const val ROTATE_MS = 9_000L

private val HERO_MIN_HEIGHT = 300.dp
private val HERO_TEXT_WIDTH = 620.dp

/** Высота обложки; ширина — две трети от неё (обычная обложка 2:3). */
private val HERO_POSTER_HEIGHT = 230.dp

/** Высота обложек «дальше в очереди» — они заметно меньше главной. */
private val HERO_NEXT_HEIGHT = 170.dp

/** Обложку заказываем с запасом под ретину: 160 dp на экране — это до 320 пикселей. */
private const val HERO_POSTER_REQUEST_PX = 480

/** Ширина тёплого свечения под обложкой. */
private val HERO_GLOW_WIDTH = 420.dp
