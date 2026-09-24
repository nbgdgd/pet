package com.aniblaze.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.desktop.TitleWatch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Design tokens (AccentOrange / Surface2 / …) live in Theme.kt — the single
// source of truth for the desktop design system.

/**
 * Routes a poster through the weserv image CDN: it fetches the original on its own
 * fast, un-throttled backend and serves a small WebP from Cloudflare — the same
 * "small image from a fast CDN" trick that makes TMDB cinema posters load instantly.
 * Anixart's own CDN (s.anixmirai.com) serves full-size JPEGs that saturate a slow
 * connection and time out into grey cards; this fixes that at the root. TMDB URLs are
 * already sized/fast and pass through untouched.
 */
fun posterUrl(raw: String, width: Int = 320): String {
    if (raw.isBlank() || raw.contains("image.tmdb.org")) return raw
    val origin = when {
        raw.startsWith("https://") -> "ssl:" + raw.removePrefix("https://")
        raw.startsWith("http://") -> raw.removePrefix("http://")
        else -> return raw
    }
    val enc = java.net.URLEncoder.encode(origin, "UTF-8")
    return "https://images.weserv.nl/?url=$enc&w=${posterStep(width)}&output=webp&q=72&we"
}

/**
 * Ширина, которую реально просим у CDN, — округлённая вверх до ступени.
 *
 * Просить точный размер ячейки нельзя: он зависит от ширины окна, и каждое
 * перетаскивание рамки порождало бы новый адрес, то есть промах кэша и новую
 * загрузку. Ступени 160/240/320/480/640 покрывают всё от восьми колонок на
 * ноутбуке до трёх на большом мониторе, а адресов остаётся пять.
 */
internal fun posterStep(width: Int): Int =
    POSTER_STEPS.firstOrNull { it >= width } ?: POSTER_STEPS.last()

private val POSTER_STEPS = intArrayOf(160, 240, 320, 480, 640)

/**
 * Сколько строк отдать названию под постером.
 *
 * Длинные названия ломали сетку: у одной карточки подпись в четыре строки, у соседней
 * в одну — ряды выходили разной высоты и прыгали при прокрутке. Число строк привязано
 * к ШИРИНЕ ячейки, а не к длине текста, и именно оно потом ставится и как maxLines, и
 * как minLines: тогда высота подписи одинакова у всех карточек ряда независимо от
 * того, что в неё написали.
 *
 * Почему шире — значит больше строк: карточка растёт по обеим осям (постер жёстко
 * 2:3), и под узкой низкой карточкой три строки текста перевешивают сам постер, а под
 * широкой высокой двух строк не хватает, чтобы уравновесить картинку.
 *
 * Считаем в dp, а не в пикселях: на мониторе со 150% масштабом та же самая на вид
 * карточка занимает в полтора раза больше пикселей, и по пиксельным порогам она
 * попадала бы в другую ступень при неизменной картинке на экране.
 */
internal fun posterTitleLines(widthDp: Int): Int = when {
    widthDp < NARROW_CARD_DP -> 1
    widthDp < WIDE_CARD_DP -> 2
    else -> 3
}

/** Размеры текста и воздуха карточки, плавно привязанные к её реальной ширине. */
internal data class PosterCardMetrics(
    val titleSp: Float,
    val secondarySp: Float,
    val ratingSp: Float,
    val titleTopPaddingDp: Float,
)

internal fun posterCardMetrics(widthDp: Int): PosterCardMetrics {
    val scale = ((widthDp - 140f) / 360f).coerceIn(0f, 1f)
    return PosterCardMetrics(
        titleSp = 14f + 4f * scale,
        secondarySp = 11f + 3f * scale,
        ratingSp = 13f + 2f * scale,
        titleTopPaddingDp = 6f + 4f * scale,
    )
}

/** Восемь колонок в неширком окне: ячейка уже 150 dp — подписи хватит строки. */
private const val NARROW_CARD_DP = 140

/** Три-пять колонок на большом мониторе: постер высокий, подпись может занять три строки. */
private const val WIDE_CARD_DP = 260

/** Насколько погасить обложку: обесцветить и притушить. 1f/1f — не гасить вовсе. */
internal data class PosterDim(val saturation: Float, val alpha: Float)

/**
 * Два РАЗНЫХ повода погасить обложку, и путать их нельзя.
 *
 * [unfinishedLast] — «серия вышла, а вы её ещё не смотрели»: подсказка «Избранного»,
 * её считает экран по сохранённому числу серий и передаёт списком.
 * [watchedOut] — «тайтл досмотрен целиком»: настройка `dimWatched`, карточка считает
 * её сама по [com.aniblaze.desktop.TitleWatch.finished].
 *
 * Наполовину просмотренные не гасятся НИКОГДА — ни тем, ни другим: это ровно те
 * тайтлы, к которым возвращаются, и прятать их значило бы прятать самое нужное.
 *
 * Под курсором цвет возвращается полностью: разглядывать карточку ничего не мешает.
 */
internal fun posterDim(unfinishedLast: Boolean, watchedOut: Boolean, hovered: Boolean): PosterDim = when {
    hovered -> PosterDim(1f, 1f)
    // Порядок важен: если признаки разошлись (считаны из разных следов), сильнее
    // гасит первый — он про «сделать», а не про «уже сделано».
    unfinishedLast -> PosterDim(0.18f, 0.62f)
    // Досмотренное гасим слабее: карточка должна оставаться узнаваемой, это метка
    // истории, а не вычёркивание. Гасится ТОЛЬКО картинка — название, оценка и полоса
    // прогресса лежат выше по стеку и остаются в полную силу.
    watchedOut -> PosterDim(0.30f, 0.78f)
    else -> PosterDim(1f, 1f)
}

/**
 * Досмотрен ли тайтл настолько, чтобы его гасить.
 *
 * Отдельной функцией, потому что здесь единственное место, где решается «строго
 * finished, а не хоть что-то просмотрено» — требование, которое легко потерять при
 * следующей правке.
 */
internal fun watchedOut(dimWatchedEnabled: Boolean, watch: TitleWatch?): Boolean =
    dimWatchedEnabled && watch?.finished == true

/** Отступы сетки постеров: нужны и раскладке, и расчёту ширины ячейки. */
private val GRID_PADDING = 16.dp
private val GRID_SPACING = 12.dp

/** За сколько карточек до конца просить следующую страницу. */
private const val LOAD_MORE_LOOKAHEAD = 6

/**
 * Размер, который обязан расти вместе с настройкой «масштаб шрифта».
 *
 * Настройка множит `Density.fontScale` (см. App.kt), то есть сама собой растёт
 * ТОЛЬКО типографика: dp остаются прежними. Всё, что задано в dp и должно вместить
 * текст — ширина боковой навигации, витрина, карточки, — обязано умножаться на тот
 * же множитель, иначе на 1.4 подписи обрываются на полуслове.
 */
@Composable
fun scaledForFont(base: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    base * LocalDensity.current.fontScale

@Composable
fun PosterGrid(
    items: List<Anime>,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    onLoadMore: (() -> Unit)? = null,
    subtitles: Map<String, String> = emptyMap(),
    explanations: Map<String, com.aniblaze.desktop.Recommender.Explanation> = emptyMap(),
    /** Идентификаторы тайтлов, у которых последняя серия не досмотрена. */
    dimmedIds: Set<String> = emptySet(),
    /** Проброс пункта «Открыть в новом окне» до карточек; null — пункта нет. */
    onOpenInNewWindow: ((Anime) -> Unit)? = { com.aniblaze.desktop.DetachedTitles.openTitle(it) },
    onClick: (Anime) -> Unit,
) {
    // Догрузка при подходе к концу.
    //
    // В наблюдаемое входит и ОБЩЕЕ ЧИСЛО карточек, а не только индекс последней
    // видимой. Без него триггер был односторонним: сработал один раз, просьбу
    // отклонили (шла другая загрузка) — и перевзвестись уже нечем, если всё влезло
    // на экран и прокручивать нечего. Ровно так «Развернуть» замирало на первой
    // странице. Теперь любое пополнение списка — новое событие, и цепочка
    // продолжается сама.
    val latestLoadMore by androidx.compose.runtime.rememberUpdatedState(onLoadMore)
    if (onLoadMore != null) {
        LaunchedEffect(gridState) {
            snapshotFlow {
                val info = gridState.layoutInfo
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
            }
                .distinctUntilChanged()
                .collect { (last, total) -> if (total > 0 && last >= total - LOAD_MORE_LOOKAHEAD) latestLoadMore?.invoke() }
        }
    }
    // Honour the user's grid-density setting (0 = adaptive), read from the app-wide
    // settings local so callers don't have to thread it through everywhere.
    //
    // Подписка ТОЛЬКО на нужное поле: настройки пишутся во время просмотра постоянно
    // (позиция, прогресс), и подписка на весь объект пересобирала сетку на каждую
    // запись.
    val settings = LocalAppSettings.current
    val gridColumns = if (settings == null) {
        0
    } else {
        val columns by remember(settings) {
            settings.state.map { it.gridColumns }.distinctUntilChanged()
        }.collectAsState(settings.state.value.gridColumns)
        columns
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        // И фиксированный, и автоматический режим используют ОДИН расчёт. Adaptive
        // раньше сам округлял число ячеек, а posterWidthPx считался другой формулой —
        // на границе resize картинка и текст уже считали себя другого размера.
        val resolvedColumns = posterColumnCount(gridColumns, maxWidth.value)
        val columns = GridCells.Fixed(resolvedColumns)
        // Постер просим ПОД ЯЧЕЙКУ, а не всегда 320 px. На трёх колонках на большом
        // мониторе ячейка вдвое шире — раньше туда растягивалась картинка вполовину
        // нужного разрешения; на восьми колонках, наоборот, качали лишнее.
        val density = LocalDensity.current
        val cellPx = remember(maxWidth, resolvedColumns, density) {
            with(density) {
                posterCardWidthDp(maxWidth.value, resolvedColumns).dp.toPx()
            }.toInt().coerceAtLeast(1)
        }
        if (isLoading && items.isEmpty()) {
            // Shimmer skeleton grid while the first page loads.
            LazyVerticalGrid(
                columns = columns,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(GRID_PADDING),
                horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(12) { PosterSkeleton(Modifier.fillMaxWidth()) }
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = columns,
                modifier = Modifier.fillMaxSize().browserAutoScroll(gridState),
                contentPadding = PaddingValues(GRID_PADDING),
                horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items, key = { it.id }) { anime ->
                    PosterCard(
                        anime,
                        subtitle = subtitles[anime.id],
                        explanation = explanations[anime.id],
                        onOpenRecommendationSource = onClick,
                        dimmed = anime.id in dimmedIds,
                        posterWidthPx = cellPx,
                        onOpenInNewWindow = onOpenInNewWindow,
                        onClick = { onClick(anime) },
                    )
                }
            }
            if (!isLoading && items.isEmpty()) {
                Text(
                    "Ничего не найдено",
                    color = TextSecondary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
fun PosterCard(
    anime: Anime,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    explanation: com.aniblaze.desktop.Recommender.Explanation? = null,
    onOpenRecommendationSource: ((Anime) -> Unit)? = null,
    /** Последняя известная серия ещё не досмотрена: карточка гасится до серой. */
    dimmed: Boolean = false,
    /** Ширина ячейки в пикселях — под неё заказывается постер и по ней же
     *  подбирается число строк подписи (см. [posterTitleLines]). */
    posterWidthPx: Int = 320,
    /** Правый клик по постеру открывает меню «в избранное / открыть / в новом окне». */
    contextMenu: Boolean = true,
    /**
     * Пункт меню «Открыть в новом окне».
     *
     * ПО УМОЛЧАНИЮ ОН ЕСТЬ ВЕЗДЕ, и это осознанно. Пользователь просил именно этого:
     * действие обязано работать на любой странице, где встречается карточка, и не
     * требовать отдельной реализации на каждой. Если бы значением по умолчанию был
     * null, каждый новый экран молча оставался бы без пункта, пока кто-нибудь не
     * вспомнит протащить обработчик через всю цепочку.
     *
     * Сама карточка про окна по-прежнему ничего не знает: [DetachedTitles] — просто
     * список открытого, а окна по нему создаёт верхний уровень.
     */
    onOpenInNewWindow: ((Anime) -> Unit)? = { com.aniblaze.desktop.DetachedTitles.openTitle(it) },
    onClick: () -> Unit,
) {
    // Favourite state (drives the badge + the right-click label). Null settings =
    // no store wired (e.g. a preview) → no favourite affordance.
    //
    // Подписка на ОДИН признак вместо всего объекта настроек: иначе каждая карточка
    // на экране (а их три десятка) пересобиралась на каждую запись прогресса.
    val settings = LocalAppSettings.current
    val favorite = if (settings == null) {
        false
    } else {
        val isFavorite by remember(settings, anime.id) {
            settings.state.map { it.favorites.any { fav -> fav.id == anime.id } }.distinctUntilChanged()
        }.collectAsState(settings.isFavorite(anime.id))
        isFavorite
    }

    // Где остановились в этом тайтле. Карточка берёт это сама, а не получает списком
    // сверху: иначе каждому экрану пришлось бы считать одно и то же, и «Главная»
    // осталась бы без меток, как и было. Карта считается один раз на состояние
    // (AppSettings.watchIndex), так что тридцать карточек на экране обходятся одним
    // проходом, а не тридцатью.
    val watch = if (settings == null) {
        null
    } else {
        val current by remember(settings, anime.id) {
            settings.state.map { settings.watchOf(anime.id, it) }.distinctUntilChanged()
        }.collectAsState(settings.watchOf(anime.id))
        current
    }

    // Просмотрен ли тайтл целиком — то же состояние, что рисует полосу «Просмотрено»
    // внизу постера. Отдельного поля под ручную отметку нет намеренно: отметка и
    // досмотренный просмотр — одно и то же, а два хранилища рано или поздно разошлись
    // бы, и карточка спорила бы сама с собой.
    val titleWatched = watch?.finished == true

    // «Посмотрел — не понравилось». Подписка на одно поле, как у избранного.
    val disliked = if (settings == null) {
        false
    } else {
        val value by remember(settings, anime.id) {
            settings.state.map { anime.id in it.disliked }.distinctUntilChanged()
        }.collectAsState(settings.isDisliked(anime.id))
        value
    }

    // «Не показывать 30 дней»: спрятан ли сейчас (срок не вышел).
    val snoozed = if (settings == null) {
        false
    } else {
        val value by remember(settings, anime.id) {
            settings.state.map { settings.isSnoozed(anime.id, it) }.distinctUntilChanged()
        }.collectAsState(settings.isSnoozed(anime.id))
        value
    }

    // Настройка «гасить досмотренные». Подписка тоже на одно поле — переключают его
    // раз в жизни, а состояние настроек меняется на каждую запись прогресса.
    val dimWatchedEnabled = if (settings == null) {
        false
    } else {
        val enabled by remember(settings) {
            settings.state.map { it.dimWatched }.distinctUntilChanged()
        }.collectAsState(settings.state.value.dimWatched)
        enabled
    }

    // Число строк подписи считается по ширине ячейки в dp — см. posterTitleLines.
    val posterWidthDp = with(LocalDensity.current) { posterWidthPx.toDp().value.toInt() }
    val titleLines = posterTitleLines(posterWidthDp)
    val cardMetrics = posterCardMetrics(posterWidthDp)

    val body = @Composable {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        // Нажатие видно: карточка чуть подаётся внутрь. Без этого клик по постеру
        // ничем не отзывался до самого перехода на страницу тайтла.
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            when {
                pressed -> 0.985f
                hovered -> 1.045f
                else -> 1f
            },
            tween(if (pressed) 90 else 180, easing = MotionEasing),
            label = "posterScale",
        )
        // Обесцвечивание — НЕ затемнение: тёмный постер на тёмном фоне просто теряется,
        // а серый читается как состояние. Гасится только сама картинка; подпись, оценка
        // и полоса прогресса нарисованы отдельно и остаются в полную силу.
        val dim = posterDim(
            unfinishedLast = dimmed,
            watchedOut = watchedOut(dimWatchedEnabled, watch) || disliked,
            hovered = hovered,
        )
        val saturation by animateFloatAsState(dim.saturation, tween(260), label = "posterSaturation")
        val fade by animateFloatAsState(dim.alpha, tween(260), label = "posterFade")
        Column(
            modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(Shapes.card)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(Shapes.card).background(Surface2)
                    .then(if (hovered) Modifier.border(BorderStroke(1.5.dp, AccentOrange), Shapes.card) else Modifier),
            ) {
                AsyncImage(
                    model = posterUrl(anime.poster, posterWidthPx),
                    imageLoader = posterImageLoader(anime.id),
                    contentDescription = anime.title,
                    modifier = Modifier.fillMaxSize().alpha(fade),
                    contentScale = ContentScale.Crop,
                    colorFilter = if (saturation < 0.999f) {
                        androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                            androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(saturation) },
                        )
                    } else {
                        null
                    },
                )
                // Dim only on hover (so the play button reads clearly); no scrim at rest —
                // that was making every poster look too dark.
                if (hovered) Box(Modifier.fillMaxSize().background(Scrim.copy(alpha = 0.28f)))
                // Оценка заметнее: раньше это была плашка 10 sp в углу, которая
                // сливалась с постером. Теперь крупнее, с золотой окантовкой и
                // цифрой цвета звезды — читается, не глядя в упор.
                if (anime.rating > 0) {
                    androidx.compose.foundation.layout.Row(
                        // Отступ от угла — 10 dp, а не 7.
                        //
                        // Карточка скруглена на 16 dp, и плашка, подросшая ради
                        // читаемости, залезала прямо в этот радиус: правый край
                        // срезался вместе с обводкой, и выглядело так, будто оценка
                        // съезжает за постер. Десяти точек хватает, чтобы плашка
                        // целиком оказалась внутри прямого участка края.
                        Modifier.align(Alignment.TopEnd).padding(10.dp).clip(Shapes.chip)
                            .background(Scrim)
                            .border(BorderStroke(1.dp, RatingGold.copy(alpha = 0.55f)), Shapes.chip)
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = RatingGold, modifier = Modifier.size(13.dp))
                        Text(
                            "%.1f".format(anime.rating),
                            color = RatingGold,
                            fontSize = cardMetrics.ratingSp.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            // Пробел был частью строки и давал разный отступ на разных
                            // шрифтах — отступ задаётся раскладкой, а не текстом.
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                if ((anime.id.startsWith("tmdb") || anime.id.startsWith("http")) && anime.status in setOf("Плохое", "Нормальное", "Лучшее")) {
                    val qualityColor = when (anime.status) {
                        "Плохое" -> Color(0xFFE04B4B)
                        "Нормальное" -> Color(0xFFE0A83A)
                        else -> WatchedGreen
                    }
                    Text(
                        anime.status,
                        color = OledBlack,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                            .clip(Shapes.chip).background(qualityColor).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                // Низ постера: ранг, а под ним — где остановились. Оба живут в одной
                // колонке, потому что порознь они наезжали друг на друга: полоса
                // прогресса во всю ширину проходит ровно там, где раньше стоял значок
                // ранга.
                val rank = remember(anime.id, anime.rating, anime.ratingMax, anime.ratingVotes, anime.favoritesCount, anime.watchingCount) {
                    TitleRank.of(anime)
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    if (rank != TitleRank.UNKNOWN) {
                        RankChip(
                            rank,
                            Modifier.padding(start = 10.dp, end = 10.dp, bottom = if (watch != null) 6.dp else 10.dp),
                        )
                    }
                    if (watch != null) WatchBand(watch)
                }
                // Кнопки в левом верхнем углу: сердце и галочка «просмотрено».
                //
                // Обе появляются по наведению и обе ОСТАЮТСЯ, пока признак стоит:
                // иначе отметку было бы не видно, не наведя курсор, а её смысл ровно в
                // том, чтобы читаться с одного взгляда на сетку.
                //
                // Клик потребляется самой кнопкой, поэтому отметить тайтл, случайно
                // открыв его, нельзя.
                if (settings != null && (hovered || favorite || titleWatched || disliked)) {
                    Column(
                        Modifier.align(Alignment.TopStart).padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // «Не понравилось»: большой палец вниз. Стоит — виден всегда:
                        // такой тайтл из лент уходит, и в истории/избранном должно быть
                        // видно, почему его больше нигде нет.
                        if (hovered || disliked) {
                            Box(
                                Modifier.clip(Shapes.chip)
                                    .background(if (disliked) ErrorRed.copy(alpha = 0.85f) else Scrim)
                                    .clickable { settings.setDisliked(anime, !disliked) }
                                    .padding(6.dp),
                            ) {
                                Icon(
                                    if (disliked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                                    contentDescription = if (disliked) "Убрать «не понравилось»" else "Не понравилось",
                                    tint = if (disliked) OledBlack else ErrorRed,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                        if (hovered || favorite) {
                            Box(
                                Modifier.clip(Shapes.chip)
                                    .background(Scrim)
                                    .clickable { settings.toggleFavorite(anime) }
                                    .padding(6.dp),
                            ) {
                                Icon(
                                    if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    contentDescription = if (favorite) "Убрать из избранного" else "Добавить в избранное",
                                    tint = AccentOrange,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                        if (hovered || titleWatched) {
                            Box(
                                Modifier.clip(Shapes.chip)
                                    .background(if (titleWatched) WatchedGreen.copy(alpha = 0.9f) else Scrim)
                                    .clickable { settings.setTitleWatched(anime, !titleWatched) }
                                    .padding(6.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = if (titleWatched) {
                                        "Снять отметку «просмотрено»"
                                    } else {
                                        "Отметить просмотренным"
                                    },
                                    tint = if (titleWatched) OledBlack else WatchedGreen,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                    }
                }
                if (hovered) {
                    Box(
                        Modifier.align(Alignment.Center).size(46.dp).clip(Shapes.pill)
                            .background(AccentOrange.copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Смотреть", tint = OledBlack, modifier = Modifier.size(28.dp))
                    }
                }
            }
            Text(
                anime.title,
                // minLines == maxLines: строк ровно столько всегда, даже если название
                // короткое. Иначе высота подписи гуляет от карточки к карточке, и ряды
                // сетки скачут при прокрутке.
                maxLines = titleLines,
                minLines = titleLines,
                overflow = TextOverflow.Ellipsis,
                fontSize = cardMetrics.titleSp.sp,
                fontWeight = FontWeight.Medium,
                color = if (hovered) AccentOrange else TextPrimary,
                modifier = Modifier.padding(top = cardMetrics.titleTopPaddingDp.dp, start = 2.dp, end = 2.dp),
            )
            // Строка прогресса под названием: сколько серий позади из известных. На
            // постере то же состояние показывает полоса, но там оно читается только
            // при наведении — а вопрос «сколько я уже посмотрел» задают на весь ряд.
            val progressLine = watch?.takeIf { !it.finished && it.episode > 0 && it.total > 1 }
                ?.let { "Серия ${it.episode} из ${it.total}" }
            val caption = subtitle ?: if (anime.episodeEstimatedAt > 0)
                "Серия ${anime.episodesAvailable} · по расписанию" else null
            if (progressLine != null && explanation == null) {
                Text(
                    progressLine,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = TextSecondary,
                    fontSize = cardMetrics.secondarySp.sp,
                    modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
                )
            }
            if (explanation != null) {
                RecommendationReason(explanation, onOpenRecommendationSource)
            } else if (!caption.isNullOrBlank()) {
                Text(
                    caption,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = AccentOrange,
                    fontSize = cardMetrics.secondarySp.sp,
                    modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
                )
            }
        }
    }

    // Правый клик: избранное, открыть, открыть в новом окне.
    //
    // Меню снова доступно и внутри каруселей: ленту теперь тянут ЛЕВОЙ кнопкой
    // (см. [dragScroll]), и правая ничем не занята. Пункты собираются по тому, что
    // вообще подключено, — без хранилища нечего добавлять в избранное, без обработчика
    // нечего открывать в новом окне.
    val openInNewWindow = onOpenInNewWindow
    val menuItems = buildList {
        if (settings != null) {
            add(ContextMenuItem(if (favorite) "Убрать из избранного" else "В избранное") { settings.toggleFavorite(anime) })
            add(
                ContextMenuItem(if (disliked) "Убрать «не понравилось»" else "Посмотрел — не понравилось") {
                    settings.setDisliked(anime, !disliked)
                },
            )
            add(
                ContextMenuItem(
                    if (titleWatched) "Снять отметку «просмотрено»" else "Отметить просмотренным",
                ) { settings.setTitleWatched(anime, !titleWatched) },
            )
            // «Не сейчас»: прячется из лент и подборок на месяц, вкус не трогает.
            add(
                ContextMenuItem(if (snoozed) "Показывать снова" else "Не показывать ${com.aniblaze.desktop.SNOOZE_DAYS} дней") {
                    if (snoozed) settings.unsnooze(anime) else settings.snooze(anime)
                },
            )
        }
        add(ContextMenuItem("Открыть") { onClick() })
        if (openInNewWindow != null) {
            add(ContextMenuItem("Открыть в новом окне") { openInNewWindow(anime) })
        }
    }
    if (contextMenu) {
        ContextMenuArea(items = { menuItems }) { body() }
    } else {
        body()
    }
}

/**
 * Полоса понизу постера: докуда досмотрено и где именно остановились.
 *
 * Три вещи сразу, потому что поодиночке ни одна не отвечает на вопрос «смотреть
 * дальше или начинать заново»: полоска показывает, сколько тайтла позади, подпись
 * называет серию, а цвет отделяет законченное (зелёный) от брошенного (оранжевый).
 * Плашка не прячется под курсором: она под кнопкой воспроизведения и как раз в этот
 * момент нужнее всего.
 */
@Composable
private fun WatchBand(watch: TitleWatch) {
    val color = if (watch.finished) WatchedGreen else AccentOrange
    val label = when {
        watch.finished -> "Просмотрено"
        // Фильм: серий нет, поэтому осмысленно назвать только долю.
        watch.total <= 1 && watch.episode <= 1 ->
            if (watch.fraction > 0.01f) "Остановились: ${(watch.fraction * 100).toInt()}%" else "Начато"
        else -> "Остановились: ${watch.episode} серия"
    }
    // Затемнение снизу вверх: подпись обязана читаться и на светлом кадре, но
    // сплошная плашка выглядела бы наклейкой поверх картинки.
    Column(
        Modifier.fillMaxWidth().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to androidx.compose.ui.graphics.Color.Transparent,
                0.45f to Scrim,
                1f to androidx.compose.ui.graphics.Color(0xF00A0A10),
            ),
        ),
    ) {
        // Метрики поджаты СПЕЦИАЛЬНО, и вот на сколько: «Остановились: 4 серия»
        // помещалось, а «Остановились: 15 серия» обрывалось на «15 се…» — не хватало
        // буквально одного знака. Отступы 8→5, значок 13→12 и зазор 4→3 отдают подписи
        // около восьми точек, то есть тот самый недостающий знак с запасом.
        Row(
            Modifier.fillMaxWidth().padding(start = 5.dp, end = 5.dp, top = 14.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (watch.finished) Icons.Filled.Check else Icons.Filled.Bookmark,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp),
            )
            Text(
                label,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 3.dp),
            )
        }
        // Полоска — по самому краю постера, во всю ширину: край карточки и есть
        // шкала, отдельную рамку рисовать не нужно.
        Box(Modifier.fillMaxWidth().height(3.dp).background(TextSecondary.copy(alpha = 0.35f))) {
            Box(Modifier.fillMaxWidth(watch.overall.coerceIn(0.02f, 1f)).height(3.dp).background(color))
        }
    }
}
