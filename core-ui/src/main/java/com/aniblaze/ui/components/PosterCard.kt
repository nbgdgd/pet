package com.aniblaze.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aniblaze.ui.theme.OledBlack
import com.aniblaze.ui.theme.TextPrimary
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.WatchedGreen

/** Poster + title + rating card used across Home, Search, Favorites and History. */
@Composable
fun PosterCard(
    title: String,
    posterUrl: String,
    rating: Double,
    /** Maximum of the source rating scale (Anixart 5, TMDB/Kodik/Shikimori 10). */
    ratingMax: Double = if (rating > 5.0) 10.0 else 5.0,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    /**
     * Ширина ячейки — под неё заказывается постер у CDN.
     *
     * Раньше карточка мерила себя сама, через BoxWithConstraints. Он удобен, но это
     * SubcomposeLayout: КАЖДАЯ карточка заводила отдельную под-композицию и мерилась в
     * два прохода вместо одного. На экране их два-три десятка, и каждая перерисовка
     * ряда прогоняла весь этот цикл заново. Экраны и так считают ширину ячейки один
     * раз на всю сетку — дешевле передать её сюда, чем мерить в каждой карточке.
     *
     * Не задана — берётся оценка по ширине экрана: карточка всё равно нарисуется
     * правильно, просто у CDN попросят ступень «на глаз».
     */
    cellWidth: Dp = Dp.Unspecified,
    /** Последняя известная серия ещё не досмотрена: карточка гасится до серой. */
    dimmed: Boolean = false,
    /** Все известные серии досмотрены: на постер ставится галка. */
    watched: Boolean = false,
    /** Метка качества используется только кино-каталогом. */
    qualityLabel: String? = null,
    /** TV: стартовый фокус пульта — передаёт экран для своей первой карточки. */
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val ratingRank = remember(rating, ratingMax) { posterRatingRank(rating, ratingMax) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    // D-pad focus (Smart TV): the card lights up exactly like a press, plus an
    // orange ring so the selected poster is always visible from the couch.
    var focused by remember { mutableStateOf(false) }
    // На телефоне «наведения» нет — роль курсора играет палец. Пока карточку держат
    // (а на планшете с мышью — пока над ней курсор), состояния снимаются: разглядывать
    // картинку ничто не мешает.
    val active = pressed || hovered || focused
    // Обесцвечивание — НЕ затемнение: тёмный постер на тёмном фоне просто теряется,
    // а серый читается как состояние.
    val saturation by animateFloatAsState(
        if (dimmed && !active) 0.18f else 1f,
        tween(260),
        label = "posterSaturation",
    )
    val fade by animateFloatAsState(
        if (dimmed && !active) 0.62f else 1f,
        tween(260),
        label = "posterFade",
    )
    // Фильтр обесцвечивания собирался заново на каждый кадр анимации: ColorMatrix —
    // это массив на двадцать чисел, и вместе с ним пересоздавался ColorFilter, то есть
    // менялся аргумент AsyncImage. Теперь объект живёт, пока не изменится насыщенность.
    val grayscale = remember(saturation) {
        if (saturation < 0.999f) {
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
        } else {
            null
        }
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val requestPx = remember(cellWidth, screenWidthDp, density) {
        val dp = if (cellWidth.isSpecified) cellWidth else (screenWidthDp / 3f).dp
        with(density) { dp.toPx() }.toInt().coerceAtLeast(1)
    }
    // Вызов по полному имени: параметр posterUrl перекрывает одноимённую функцию пакета.
    val model = remember(posterUrl, requestPx) {
        com.aniblaze.ui.components.posterUrl(posterUrl, requestPx)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.hasFocus }
            .scale(if (focused) 1.03f else 1f)
            .border(
                width = if (focused) 2.5.dp else 0.dp,
                color = if (focused) AccentOrange else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            )
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            AsyncImage(
                model = model,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                // graphicsLayer вместо Modifier.alpha: тот же результат, но прозрачность
                // меняется в фазе отрисовки, без перемера слоя на каждом кадре угасания.
                modifier = Modifier.matchParentSize().graphicsLayer { alpha = fade },
                colorFilter = grayscale,
            )
            // Subtle bottom scrim — grounds the poster and adds cinematic depth.
            Box(Modifier.matchParentSize().background(PosterScrim))
            if (rating > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .glass(10.dp)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = AccentOrange,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        " ${"%.1f".format(rating)}",
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (qualityLabel?.isNotBlank() == true || ratingRank != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                ) {
                    qualityLabel?.takeIf { it.isNotBlank() }?.let { label ->
                        Text(
                            text = "Качество: $label",
                            color = qualityLabelColor(label),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .glass(10.dp)
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                    ratingRank?.let { rank ->
                        Text(
                            text = "Рейтинг: ${rank.label}",
                            color = rank.color,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .glass(10.dp)
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            // Тонкая полоска понизу — нейтральный индикатор продолжения просмотра.
            if (dimmed && !active) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.12f)),
                )
            }
            // Галка «досмотрено до конца». Живёт в свободном верхнем левом углу:
            // справа вверху — числовой рейтинг, внизу слева — подписи качества
            // и ранга, так что здесь она ни с чем не пересекается.
            if (watched && !active) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(WatchedGreen)
                        .padding(4.dp),
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Просмотрено",
                        tint = OledBlack,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** Градиент собирается один раз на процесс: он одинаков у всех карточек. */
private val PosterScrim = Brush.verticalGradient(
    0.62f to Color.Transparent,
    1f to OledBlack.copy(alpha = 0.5f),
)

/**
 * Цвет подписи «Качество: …» по сырому значению источника ("WEB-DL 1080p" →
 * зелёный, "CAMRip" → красный). Старые категориальные значения
 * ("Плохое/Нормальное/Лучшее") и голое разрешение тоже классифицируются.
 */
private fun qualityLabelColor(label: String): Color {
    val words = label.lowercase(java.util.Locale.ROOT)
        .split(' ', '-', '.', '_', '/').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun has(vararg signals: String) = signals.any { it in words }
    return when {
        has("camrip", "cam", "hdcam", "hdts", "ts", "telesync", "hdtc", "screener", "scr", "webcam", "плохое", "плохой", "плох", "bad") ->
            Color(0xFFE04B4B)
        has("webrip", "webr", "hdtv", "hdrip", "dvdrip", "dvd", "tvrip", "sdtv", "нормальное", "норм", "normal") ->
            Color(0xFFE0A83A)
        has("webdl", "web", "dl", "bluray", "blu", "ray", "bdrip", "remux", "4k", "uhd", "1080p", "720p", "fullhd", "1080", "лучшее", "лучший", "best") ->
            WatchedGreen
        else -> Color(0xFFB0B0B8)
    }
}
