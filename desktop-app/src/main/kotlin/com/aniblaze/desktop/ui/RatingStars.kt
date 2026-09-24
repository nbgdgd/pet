package com.aniblaze.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Личная оценка тайтла — пять звёзд.
 *
 * Почему пять, а не десять. Оценка нужна не для точности, а для решения: по
 * десятибалльной шкале человек мучается между семёркой и восьмёркой, и разница между
 * ними всё равно ничего не значит — обе означают «понравилось». Пять ступеней
 * различимы без раздумий, и каждая из них что-то меняет в подборе (см.
 * `Recommender.ratingAffinity`).
 *
 * Наведение показывает, что получится, — но НЕ меняет оценку. Без предпросмотра
 * попасть в нужную звезду можно только на глаз, а промах здесь дорогой: оценка это
 * самый тяжёлый след во всём вкусе.
 *
 * Повторное нажатие на ту же звезду СНИМАЕТ оценку. Отдельной кнопки «убрать» нет
 * намеренно: она нужна раз в сто нажатий и занимала бы место всегда.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun RatingStars(
    /** Текущая оценка, 1..5. Ноль — не оценивали. */
    rating: Int,
    onRate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    starSize: androidx.compose.ui.unit.Dp = 26.dp,
) {
    // Один обработчик на ВСЮ стабильную полосу звёзд. Пять независимых hoverable
    // давали промежуточный кадр между Exit старой звезды и Enter новой: подсветка
    // успевала вернуться к сохранённой оценке и визуально дёргалась.
    var hoveredStar by remember { mutableIntStateOf(0) }
    val cellWidthPx = with(LocalDensity.current) { (starSize + STAR_PADDING_DP.dp * 2).toPx() }
    val shown = if (hoveredStar > 0) hoveredStar else rating

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .onPointerEvent(PointerEventType.Move) { event ->
                    val x = event.changes.firstOrNull()?.position?.x ?: return@onPointerEvent
                    hoveredStar = starAtPointer(x, cellWidthPx)
                }
                .onPointerEvent(PointerEventType.Enter) { event ->
                    val x = event.changes.firstOrNull()?.position?.x ?: return@onPointerEvent
                    hoveredStar = starAtPointer(x, cellWidthPx)
                }
                .onPointerEvent(PointerEventType.Exit) { hoveredStar = 0 },
        ) {
            for (star in 1..MAX_STARS) {
                val clickInteraction = remember(star) { MutableInteractionSource() }
                Box(
                    Modifier
                        .clip(Shapes.chip)
                        .clickable(
                            interactionSource = clickInteraction,
                            indication = null,
                            // Та же звезда второй раз — снять оценку.
                            onClick = { onRate(if (rating == star) 0 else star) },
                        )
                        .padding(STAR_PADDING_DP.dp),
                ) {
                    Icon(
                        if (star <= shown) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = "Оценка $star из $MAX_STARS",
                        tint = if (star <= shown) RatingGold else TextSecondary,
                        modifier = Modifier.size(starSize),
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        // Геометрия подписи должна быть НЕИЗМЕННОЙ. В узкой боковой колонке исходная
        // фраза занимает четыре строки, а слово «Хорошо» — одну. Раньше Row от этого
        // становился ниже, вертикально центрированные звёзды уезжали из-под мыши,
        // получали Exit, возвращались обратно и запускали бесконечное дрожание.
        Box(
            modifier = Modifier.size(width = RATING_LABEL_WIDTH, height = RATING_LABEL_HEIGHT),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                // Подпись объясняет, что вообще происходит. Без неё пять звёзд
                // читаются как оценка сайта, а не как «поставь свою»: рядом, в фактах,
                // уже стоит звезда с числом от каталога.
                text = labelFor(rating, hoveredStar),
                color = if (rating > 0 || hoveredStar > 0) TextPrimary else TextSecondary,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = if (rating > 0) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 4,
            )
        }
    }
}

/** Индекс звезды под курсором при фиксированной ширине ячейки. */
internal fun starAtPointer(xPx: Float, cellWidthPx: Float): Int {
    if (!xPx.isFinite() || !cellWidthPx.isFinite() || cellWidthPx <= 0f || xPx < 0f) return 0
    return (xPx / cellWidthPx).toInt().plus(1).coerceIn(1, MAX_STARS)
}

/** Подпись справа от звёзд. */
internal fun labelFor(rating: Int, hovered: Int): String = when {
    hovered > 0 -> RATING_WORDS[hovered] ?: ""
    rating > 0 -> "Ваша оценка: ${RATING_WORDS[rating]?.lowercase() ?: rating.toString()}"
    else -> "Оцените — подборка соберётся под это"
}

/**
 * Слова вместо цифр.
 *
 * Цифра сама по себе ничего не сообщает: «3 из 5» каждый понимает по-своему, а от
 * этого зависит, попадёт тайтл в антивкус или нет. Слова говорят прямо, и они
 * согласованы с весами в `Recommender.ratingAffinity`: «нормально» — это уже почти
 * ноль, а не середина.
 */
private val RATING_WORDS = mapOf(
    1 to "Ужасно",
    2 to "Слабо",
    3 to "Нормально",
    4 to "Хорошо",
    5 to "Отлично",
)

internal const val MAX_STARS = 5
private const val STAR_PADDING_DP = 2
private val RATING_LABEL_WIDTH = 88.dp
private val RATING_LABEL_HEIGHT = 72.dp
