package com.aniblaze.featureplayer.danmaku

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniblaze.aggregator.model.TitleComment
import kotlinx.coroutines.delay

/**
 * Реплики зрителей поверх видео — мобильное исполнение.
 *
 * Ограничений ровно столько, чтобы не мешать смотреть:
 *
 *  • живут только в ВЕРХНЕЙ ПОЛОСЕ кадра — центр (лица) и низ (субтитры, панель
 *    управления) не трогаются вообще;
 *  • дорожек всего [LANES], и каждая занята не больше чем одной репликой за раз;
 *  • появление и уход плавные, движение равномерное;
 *  • пока открыта панель управления, реплики скрыты: на шести дюймах панель и текст
 *    одновременно — каша.
 *
 * Чем отличается от настольной версии — и почему:
 *
 *  • дорожек ДВЕ, а не три. Телефон в альбомной ориентации это примерно 360 dp по
 *    высоте; три дорожки заняли бы верхнюю треть кадра сплошным текстом;
 *  • ширина реплики — доля кадра с нижней границей 180 dp, а не 240: в книжной
 *    ориентации кадр уже 240 dp целиком, и «минимум» уезжал бы за край;
 *  • размер шрифта считается ОТ ШИРИНЫ КАДРА, а не берётся из настроек. На ПК ползунок
 *    уместен — окно любого размера; на телефоне размеров ровно два (портрет и
 *    альбом), и правильный кегль для каждого известен заранее;
 *  • реплики не проматываются через весь экран за девять секунд — путь короче, потому
 *    что и экран короче ([TRAVEL_BASE_MS]).
 */
@Composable
fun DanmakuOverlay(
    /** Устойчивый ключ серии — «тайтл:серия». ЕДИНСТВЕННОЕ, что пересоздаёт показ. */
    episodeKey: String,
    comments: List<TitleComment>,
    /** Серия, которую смотрят: реплики других серий не показываются (спойлеры). */
    episode: Int,
    /** Приблизительная длина серии — только подсказка шага. Дрожит — и пусть. */
    lengthMs: Long,
    playing: Boolean,
    rate: DanmakuRate,
    modifier: Modifier = Modifier,
) {
    if (comments.isEmpty()) return
    // ВСЁ СОСТОЯНИЕ ПОКАЗА — В СЕССИИ, ВНЕ КОМПОЗИЦИИ (см. DanmakuSession). Здесь
    // только отрисовка и один цикл-водитель.
    //
    // remember — лишь кэш обращения к держателю; пересоздание composable вернёт ТУ ЖЕ
    // сессию с тем же «показано». comments.size в ключе — не identity: пересбор экрана
    // даёт новый список тех же 75 штук, и сессия обязана пережить его.
    val session = remember(episodeKey, comments.size, rate) {
        DanmakuSessions.obtain(episodeKey, comments, episode, freshFirst = false, rate = rate)
    }
    if (session.usable == 0) return
    // Живые плашки привязаны к сессии: смена серии убирает с экрана чужие реплики.
    val live = remember(session) { mutableStateListOf<DanmakuShot>() }
    var issued by remember(session) { mutableIntStateOf(0) }

    // playing и lengthMs читаются ВНУТРИ цикла через rememberUpdatedState — ключами
    // эффекта им быть нельзя: длина HLS дрожит от замера к замеру, и как ключ она
    // перезапускала бы цикл чаще, чем истекает начальная пауза.
    val playingNow by rememberUpdatedState(playing)
    val lengthNow by rememberUpdatedState(lengthMs)
    LaunchedEffect(session) {
        session.schedulerEnter()
        try {
            while (session.hasMore()) {
                delay(TICK_MS)
                // Пауза: часы сессии стоят, ничего не сгорает и не сбрасывается.
                if (!playingNow) continue
                session.accumulate(TICK_MS)
                // Свободная дорожка: занятых не трогаем — наложений не бывает.
                val busy = live.map { it.lane }.toSet()
                val free = (0 until LANES).firstOrNull { it !in busy }
                // Протокол «выбрали → отдали на экран → подтвердили»: peek ничего не
                // тратит; реплика считается показанной только после live.add. Отмена
                // цикла между ними не сжигает её.
                val text = session.peekDue(laneFree = free != null) ?: continue
                live.add(DanmakuShot(id = issued++, lane = free!!, text = text))
                session.commitShown(lengthNow)
            }
        } finally {
            session.schedulerExit()
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Полоса, в которой вообще разрешено рисовать: сверху вниз до трети кадра.
        // Ниже — лица и субтитры, туда реплики не заходят никогда.
        val bandHeight = maxHeight * BAND_SHARE
        val laneHeight = bandHeight / LANES
        // Ширина реплики — ДОЛЯ КАДРА, а не жёсткие dp: в книжной ориентации кадр
        // втрое уже альбомного, и фиксированное число уезжало бы за край.
        val lineWidth = (maxWidth * WIDTH_SHARE).coerceIn(MIN_WIDTH, MAX_WIDTH)
        // Кегль от ширины кадра: 13 sp на узком телефоне, 16 на альбомном, 18 на
        // планшете. Меньше 13 — не прочитать на ходу, больше 18 — закрывает кадр.
        val fontSize = (maxWidth.value / 45f).coerceIn(13f, 18f)
        live.forEach { shot ->
            key(shot.id) {
                DanmakuLine(
                    shot = shot,
                    laneTop = LANE_TOP_INSET + laneHeight * shot.lane,
                    travel = maxWidth,
                    lineWidth = lineWidth,
                    fontSize = fontSize,
                    onFinished = { live.remove(shot) },
                )
            }
        }
    }
}

@Composable
private fun DanmakuLine(
    shot: DanmakuShot,
    laneTop: Dp,
    travel: Dp,
    lineWidth: Dp,
    fontSize: Float,
    onFinished: () -> Unit,
) {
    var visible by remember(shot.id) { mutableStateOf(false) }
    val fade by animateFloatAsState(
        if (visible) 1f else 0f,
        tween(FADE_MS),
        label = "danmakuFade",
    )
    val progress by animateFloatAsState(
        if (visible) 1f else 0f,
        tween(shot.travelMs, easing = LinearEasing),
        label = "danmakuTravel",
    )
    LaunchedEffect(shot.id) {
        visible = true
        delay(shot.travelMs.toLong())
        visible = false
        delay(FADE_MS.toLong())
        onFinished()
    }
    // offset, а НЕ padding: реплика обязана уезжать за левый край, а отрицательный
    // padding в Compose — исключение на этапе раскладки.
    val offsetX = travel - (travel + lineWidth) * progress
    Box(
        Modifier
            .offset(x = offsetX, y = laneTop)
            .widthIn(max = lineWidth)
            // graphicsLayer, а не Modifier.alpha: прозрачность меняется в фазе
            // отрисовки, поэтому угасание не перемеряет слой на каждом кадре — а
            // кадров тут по одному на каждую живую реплику.
            .graphicsLayer { alpha = fade }
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x66000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // ДВЕ строки, а не одна: с одной длинная реплика обрезается многоточием почти
        // сразу, и на экране висит огрызок фразы, который нечего и читать.
        Text(
            shot.text,
            color = Color.White,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = (fontSize * 1.25f).sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Одна реплика на экране. */
private class DanmakuShot(val id: Int, val lane: Int, val text: String) {
    /** Дольше для длинных: короткая фраза не должна ползти через весь кадр минуту. */
    val travelMs: Int = (TRAVEL_BASE_MS + text.length * TRAVEL_PER_CHAR_MS).coerceAtMost(TRAVEL_MAX_MS)
}

/** Дорожек по вертикали — столько же и максимум реплик на экране одновременно. */
private const val LANES = 2

/** Шаг часов цикла показа. */
private const val TICK_MS = 500L

/** Какую долю высоты кадра занимает полоса с репликами (сверху). */
private const val BAND_SHARE = 0.30f

private val LANE_TOP_INSET = 10.dp

/** Доля ширины кадра под одну реплику. */
private const val WIDTH_SHARE = 0.55f

/** Границы, между которыми доля зажимается. */
private val MIN_WIDTH = 180.dp
private val MAX_WIDTH = 420.dp

private const val FADE_MS = 500
private const val TRAVEL_BASE_MS = 7_000
private const val TRAVEL_PER_CHAR_MS = 45
private const val TRAVEL_MAX_MS = 16_000
