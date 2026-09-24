package com.aniblaze.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniblaze.aggregator.model.Anime

/**
 * Ранг тайтла — «легенда / прекрасное / норм / ужас» одним значком на постере.
 *
 * Считается по ОЦЕНКЕ и ПОПУЛЯРНОСТИ, а не по жанру, и ОТДЕЛЬНО ДЛЯ КАЖДОЙ ШКАЛЫ.
 *
 * Про шкалы. Anixart меряет от нуля до пяти, а TMDB, Shikimori, Kodik, КиноПоиск и
 * AnimeVost — от нуля до десяти, и всё это приходит в одно поле [Anime.rating].
 * Пороги ниже сначала были только пятибалльные, поэтому в «Кино» ЛЮБАЯ семёрка
 * перепрыгивала планку легенды 4.80 — и весь раздел оказывался легендарным. Теперь
 * шкалу называет сам источник ([Anime.ratingMax]), и для каждой взят свой замер.
 *
 * Пятибалльная (каталог Anixart, 467 тайтлов по пяти сортировкам, у каждого не
 * меньше 50 голосов):
 *
 *     медиана 4.64 | верхняя четверть выше 4.80 | нижняя четверть ниже 4.24
 *
 * Десятибалльная (TMDB, 887 тайтлов из ОДИННАДЦАТИ лент, которые листает
 * пользователь, по пять страниц каждая; «топ рейтинга» намеренно исключён — он
 * лучший по построению и утянул бы отсчёт вверх):
 *
 *     медиана 7.54 | верхняя четверть выше 8.20 | нижняя четверть ниже 6.69
 *     голоса: медиана 273 | верхняя четверть выше 2429
 *
 * Популярность — вторая координата: пятёрка от полусотни человек и пятёрка от ста
 * тысяч это разные вещи. Поэтому «Легенда» требует и высокой оценки, и большой
 * аудитории; если аудитория неизвестна (в сохранённых записях её может не быть),
 * планка по оценке поднимается — так ранг не выдаётся авансом.
 */
enum class TitleRank(
    val label: String,
    val color: Color,
    val hint: String,
) {
    /** Верхняя четверть каталога И большая аудитория. */
    LEGEND("Легенда", Color(0xFFFFC24B), "Верхняя четверть каталога, смотрят все"),

    /** Выше медианы каталога. */
    GREAT("Прекрасное", Color(0xFF4ADE80), "Оценка выше медианы каталога"),

    /** Середина. */
    OKAY("Норм", Color(0xFFBFC4D4), "Крепкая середина"),

    /** Нижняя четверть каталога. */
    AWFUL("Ужас", Color(0xFFC2703A), "Нижняя четверть каталога"),

    /** Оценок нет — судить не по чему. Значок не рисуется вовсе. */
    UNKNOWN("", Color.Transparent, ""),
    ;

    companion object {
        /** Пороги одной шкалы: легенда / легенда без данных об аудитории / медиана /
         *  нижняя четверть / что считать большой аудиторией. */
        private data class Scale(
            val legend: Double,
            val legendSolo: Double,
            val great: Double,
            val okay: Double,
            val audience: Int,
        )

        /** Пятибалльная — Anixart. Аудитория меряется избранным/смотрящими. */
        private val FIVE = Scale(legend = 4.80, legendSolo = 4.86, great = 4.64, okay = 4.24, audience = 20_000)

        /** Десятибалльная — TMDB / Shikimori / Kodik / КиноПоиск / AnimeVost.
         *  Аудитория — число проголосовавших; 3000 это верхние ~10% ленты. */
        private val TEN = Scale(legend = 8.20, legendSolo = 8.54, great = 7.54, okay = 6.69, audience = 3_000)

        /**
         * YummyAnime — своя шкала, хоть и десятибалльная. Карточки несут оценку Shikimori,
         * а её распределение по каталогу Yummy заметно ниже TMDB-шного: замер 18.09.2026,
         * 121 случайный тайтл (`sort=random`) с не меньше чем 50 голосами:
         *
         *     медиана 7.27 | верхняя четверть выше 7.75 | нижняя четверть ниже 6.81
         *     просмотры: медиана 55 тыс. | верхняя четверть выше 173 тыс.
         *
         * По TMDB-порогам почти весь Yummy уходил в «норм/ужас»: у 13+ серий с жанром
         * «этти» ВСЕ карточки были «Ужас», хотя у Anixart те же тайтлы — «Норм».
         * Аудитория здесь — просмотры ([Anime.watchingCount]), не голоса.
         */
        private val YUMMY = Scale(legend = 7.75, legendSolo = 8.10, great = 7.27, okay = 6.81, audience = 170_000)

        /** Всё, что мерят по шкале выше этого, — десятибалльное. */
        private const val TEN_POINT_THRESHOLD = 7.0

        fun of(anime: Anime): TitleRank = of(
            grade = anime.rating,
            ratingMax = anime.ratingMax,
            // Аудитория: у аниме это избранное/смотрящие, у кино — проголосовавшие.
            // Берём наибольшее из известного, ноль означает «источник не сказал».
            audience = maxOf(anime.favoritesCount, anime.watchingCount, anime.ratingVotes),
            // Каталог узнаётся по префиксу id: у Yummy свой замер (см. YUMMY).
            yummy = anime.id.startsWith("ya:"),
        )

        fun of(grade: Double, ratingMax: Double, audience: Int, yummy: Boolean = false): TitleRank {
            if (grade <= 0.0) return UNKNOWN
            val scale = when {
                yummy -> YUMMY
                ratingMax >= TEN_POINT_THRESHOLD -> TEN
                else -> FIVE
            }
            // Оценка выше верха шкалы — источник соврал о шкале; судить не по чему.
            if (grade > ratingMax * 1.02) return UNKNOWN
            val legendary = if (audience > 0) {
                grade >= scale.legend && audience >= scale.audience
            } else {
                grade >= scale.legendSolo
            }
            return when {
                legendary -> LEGEND
                grade >= scale.great -> GREAT
                grade >= scale.okay -> OKAY
                else -> AWFUL
            }
        }
    }
}

/**
 * Значок ВМЕСТЕ С НАЗВАНИЕМ ранга — то, что стоит на постере.
 *
 * Одного значка мало: корону и искру ещё можно угадать, а «ровную черту» и завиток
 * без подписи не прочитает никто. Слово снимает догадки сразу — оно же и рисуется
 * путями, потому что эмодзи на разных системах выглядят по-разному и не подчиняются
 * цвету, а все четыре значка обязаны читаться как один набор.
 */
@Composable
fun RankChip(rank: TitleRank, modifier: Modifier = Modifier) {
    if (rank == TitleRank.UNKNOWN) return
    // ТОТ ЖЕ ПОКРОЙ, ЧТО У ОЦЕНКИ: тёмная подложка, тонкая цветная обводка, значок и
    // слово цветом ранга.
    //
    // Пробовал сплошную заливку цветом — на постере получалась светлая клякса,
    // спорящая с картинкой. А оценка в противоположном углу уже решает ровно ту же
    // задачу «читаться поверх пестроты» и решает её спокойно: пусть обе плашки
    // выглядят роднёй, а не двумя разными идеями на одном постере.
    Row(
        modifier
            .clip(Shapes.chip)
            .background(Scrim)
            .border(BorderStroke(1.dp, rank.color.copy(alpha = 0.65f)), Shapes.chip)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(12.dp)) {
            when (rank) {
                TitleRank.LEGEND -> drawCrown(rank.color)
                TitleRank.GREAT -> drawSparkle(rank.color)
                TitleRank.OKAY -> drawSteady(rank.color)
                TitleRank.AWFUL -> drawSwirl(rank.color)
                TitleRank.UNKNOWN -> Unit
            }
        }
        Text(
            rank.label,
            color = rank.color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

// ---- сами значки ----

/** Корона с ободом — «Легенда». */
private fun DrawScope.drawCrown(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.06f, h * 0.74f)
        lineTo(w * 0.02f, h * 0.26f)
        lineTo(w * 0.29f, h * 0.50f)
        lineTo(w * 0.50f, h * 0.16f)
        lineTo(w * 0.71f, h * 0.50f)
        lineTo(w * 0.98f, h * 0.26f)
        lineTo(w * 0.94f, h * 0.74f)
        close()
    }
    drawPath(path, color)
    // Обод короны отдельной полосой — иначе на мелком размере зубцы слипаются.
    drawRect(
        color,
        topLeft = Offset(w * 0.08f, h * 0.80f),
        size = Size(w * 0.84f, h * 0.14f),
    )
}

/** Четырёхлучевая искра — «Прекрасное». */
private fun DrawScope.drawSparkle(color: Color) {
    val w = size.width
    val h = size.height
    // Вогнутые стороны: у прямой звезды на 14 px лучи выглядят обрубками.
    val path = Path().apply {
        moveTo(w * 0.50f, 0f)
        quadraticTo(w * 0.57f, h * 0.43f, w, h * 0.50f)
        quadraticTo(w * 0.57f, h * 0.57f, w * 0.50f, h)
        quadraticTo(w * 0.43f, h * 0.57f, 0f, h * 0.50f)
        quadraticTo(w * 0.43f, h * 0.43f, w * 0.50f, 0f)
        close()
    }
    drawPath(path, color)
}

/** Ровная черта в кольце — «Норм»: ни вверх, ни вниз. */
private fun DrawScope.drawSteady(color: Color) {
    val w = size.width
    val h = size.height
    drawCircle(color, radius = w * 0.46f, center = Offset(w / 2, h / 2), style = Stroke(width = w * 0.11f))
    drawRect(
        color,
        topLeft = Offset(w * 0.27f, h * 0.44f),
        size = Size(w * 0.46f, h * 0.12f),
    )
}

/**
 * «Ужас» — та самая кучка, но нарисованная, а не эмодзи: три завитка от широкого
 * основания к макушке. На постере читается мгновенно и не выбивается из набора.
 */
private fun DrawScope.drawSwirl(color: Color) {
    val w = size.width
    val h = size.height
    // Основание
    drawArc(
        color,
        startAngle = 180f, sweepAngle = 180f, useCenter = true,
        topLeft = Offset(0f, h * 0.56f), size = Size(w, h * 0.44f * 2),
    )
    drawRect(color, topLeft = Offset(0f, h * 0.78f), size = Size(w, h * 0.22f))
    // Средний виток
    drawArc(
        color,
        startAngle = 180f, sweepAngle = 180f, useCenter = true,
        topLeft = Offset(w * 0.16f, h * 0.30f), size = Size(w * 0.68f, h * 0.52f),
    )
    // Макушка
    drawArc(
        color,
        startAngle = 180f, sweepAngle = 180f, useCenter = true,
        topLeft = Offset(w * 0.33f, h * 0.06f), size = Size(w * 0.34f, h * 0.40f),
    )
}
