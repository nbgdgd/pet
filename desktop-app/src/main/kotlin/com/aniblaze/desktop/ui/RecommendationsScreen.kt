package com.aniblaze.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.CatalogTag
import com.aniblaze.desktop.AppSettings
import com.aniblaze.desktop.DesktopRepository
import com.aniblaze.desktop.Recommender
import com.aniblaze.desktop.recommendationInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size

/**
 * «Рекомендации» — каталог, собранный под конкретного зрителя.
 *
 * Экран сам ничего не решает: вкус выводит [Recommender.buildTaste] из сохранённого
 * состояния (история, прогресс, досмотренные серии, избранное), кандидатов приносит
 * [DesktopRepository.recommendationPool], порядок задаёт [Recommender.recommend].
 * Здесь остаётся раскладка, состояния загрузки и объяснение, ОТКУДА это взялось —
 * без него подборка выглядит как ещё одна лента «популярного».
 *
 * Настройки только читаются: экран не пишет в [AppSettings] ничего.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecommendationsScreen(
    repository: DesktopRepository,
    settings: AppSettings,
    /** Подборка живёт в кэше окна: заход на вкладку её не пересобирает. */
    cache: com.aniblaze.desktop.RecommendationsCache = remember { com.aniblaze.desktop.RecommendationsCache() },
    onOpen: (Anime) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var taste by cache::taste
    var displayedTaste by cache::displayedTaste
    var allItems by cache::items
    var loading by remember { mutableStateOf(!cache.loaded) }
    // Сбой каталога и «вкуса ещё нет» — разные вещи, и сообщения у них разные:
    // во втором случае повторять нечего, надо просто что-нибудь посмотреть.
    var failed by cache::failed

    // Накопленный пул кандидатов и номер следующего захода за ним. Пул РАСТЁТ: каждая
    // догрузка добавляет следующие страницы лент, а не заменяет прежние — иначе
    // ранжирование считалось бы по разному набору и уже пролистанное перетасовывалось
    // бы под рукой.
    var pool by cache::pool
    var nextRound by cache::nextRound
    var exhausted by cache::exhausted
    var refreshNonce by remember { mutableStateOf(0) }
    var moreJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var semanticJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val input by remember(settings) {
        settings.state.map(::recommendationInput).distinctUntilChanged()
    }.collectAsState(recommendationInput(settings.state.value))
    val currentSource = "${input.primarySource}|${input.enabledSources.sorted()}"
    var sourceKey by cache::sourceKey

    /** Один заход за кандидатами. Возвращает, добавилось ли хоть что-то новое. */
    suspend fun fetchRound(fresh: Recommender.Taste): Boolean {
        val batch = try {
            repository.recommendationPool(tagKeysOf(fresh), nextRound)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            com.aniblaze.desktop.player.PlayerDiagnostics.failure("recommendations.pool", error)
            failed = true
            emptyList()
        }
        nextRound++
        val before = pool.size
        val merged = pool.associateByTo(LinkedHashMap()) { it.id }
        batch.forEach { merged[it.id] = it }
        pool = merged.values.take(2500)
        return pool.size > before
    }

    /**
     * Дописать порцию находок к уже показанному.
     *
     * Ранжирование считается по всему пулу заново, но НАВЕРХ ничего не всплывает:
     * берутся только те карточки, которых ещё не было. Иначе при догрузке список
     * пересобирался бы целиком, и тайтл, мимо которого только что прокрутили, прыгал
     * бы вверх — прокрутка превращалась бы в лотерею.
     */
    suspend fun appendRanked(fresh: Recommender.Taste) {
        val shown = allItems.mapTo(HashSet()) { it.anime.id }
        val ranked = repository.rankRecommendations(input, pool, online = false).items
        val visible = allItems.toMutableList()
        for (candidate in ranked) {
            if (candidate.anime.id in shown || visible.any { Recommender.isSameFranchise(it.anime.title, candidate.anime.title) }) continue
            visible.add(candidate)
        }
        allItems = visible
    }

    suspend fun load() {
        semanticJob?.cancelAndJoin()
        loading = true
        failed = false
        // Вкус считается ОДИН раз на загрузку, а не подпиской на настройки: во время
        // просмотра состояние переписывается каждые пять секунд, и пересборка вкуса
        // на каждую запись стоила бы кадров ни за что.
        val fresh = repository.rankRecommendations(input, emptyList(), online = false).taste
        taste = fresh
        if (sourceKey != currentSource) pool = emptyList()
        sourceKey = currentSource
        nextRound = 0
        exhausted = false
        try {
            // Keep the mounted grid/painters until the replacement is ready, even
            // on an explicit refresh. A favourite click never enters this path.
            fetchRound(fresh)
            val snapshot = input
            val poolSnapshot = pool
            val warm = repository.rankRecommendations(snapshot, poolSnapshot, online = false)
            allItems = warm.items
            taste = warm.taste
            displayedTaste = warm.taste
            semanticJob = scope.launch {
                val enriched = repository.rankRecommendations(snapshot, poolSnapshot, online = true)
                // Do not reshuffle after a mark, source change or pagination. The new
                // cache still benefits the next explicit refresh/visit. Stable anime IDs
                // preserve mounted posters and the grid's scroll anchor on AI reranking.
                if (snapshot == recommendationInput(settings.state.value) && pool == poolSnapshot) {
                    allItems = enriched.items
                    taste = enriched.taste
                    displayedTaste = enriched.taste
                }
            }
        } finally {
            loading = false
            cache.loaded = true
        }
    }

    /**
     * Догрузка при подходе к концу списка.
     *
     * Заходов на один шаг может понадобиться несколько: отбор выбрасывает уже
     * виденное, вторые сезоны той же франшизы и повторы между лентами, так что целая
     * страница каталога вполне способна не дать НИ ОДНОЙ новой карточки. Крутим до
     * [MAX_ROUNDS_PER_STEP], иначе одна такая страница остановила бы прокрутку
     * навсегда; если и после этого пусто — считаем каталог исчерпанным и больше не
     * дёргаем сеть.
     */
    fun loadMore() {
        val fresh = taste ?: return
        if (loading || exhausted) return
        loading = true
        moreJob = scope.launch {
            try {
                val before = allItems.size
                var rounds = 0
                while (allItems.size == before && rounds < MAX_ROUNDS_PER_STEP) {
                    rounds++
                    if (!fetchRound(fresh)) break
                    appendRanked(fresh)
                }
                if (allItems.size == before || pool.size >= 2500 || nextRound >= 20) exhausted = true
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(input) {
        // Feedback affects future picks. Already displayed cards remain in place:
        // their score/explanation is the snapshot that selected them, not a new
        // recommendation based on the card the user has just favourited itself.
        taste = repository.rankRecommendations(input, emptyList(), online = false).taste
    }
    // Загрузка — ТОЛЬКО по кнопке «Обновить», при первом заходе и при смене
    // источников. Повторный заход на вкладку подборку не трогает: она в кэше.
    LaunchedEffect(currentSource, refreshNonce) {
        if (refreshNonce == 0 && cache.loaded && sourceKey == currentSource) return@LaunchedEffect
        moreJob?.cancelAndJoin()
        load()
    }
    // Marks update inside PosterCard. Exclusion/reordering happens on refresh or
    // next visit, not under the pointer and not on a timer while reading the grid.
    // Исключение — «Не показывать 30 дней»: это прямая просьба убрать карточку, и
    // она уходит сразу, без перестановки остальных.
    val snoozed by remember(settings) {
        settings.state.map { state -> state.snoozedUntil.keys.filter { settings.isSnoozed(it, state) }.toSet() }.distinctUntilChanged()
    }.collectAsState(emptySet())
    val visible = remember(allItems, snoozed) { if (snoozed.isEmpty()) allItems else allItems.filter { it.anime.id !in snoozed } }
    // Настроение — сито ПОВЕРХ готовой подборки: порядок и объяснения те же, просто
    // остаются карточки с подходящими жанрами. Ничего не перезапрашивается.
    val mood = RecommendationMood.byKey(cache.mood)
    val items = remember(visible, mood) { if (mood == null) visible else visible.filter { mood.matches(it.anime) } }

    // The first favourite must not insert a new chips row and shift the entire grid.
    val current = displayedTaste
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Рекомендации", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(
                        subtitleFor(current, items.size),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Row(
                    Modifier.clip(Shapes.pill).background(GlassFill)
                        .clickable(enabled = !loading) { refreshNonce++ }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = AccentOrange, modifier = Modifier.size(18.dp))
                    Text("Обновить", color = AccentOrange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
                }
            }
            // На чём построена подборка. Показываем ровно то, что реально повлияло на
            // порядок, — иначе объяснение было бы украшением.
            if (current != null && !current.isEmpty) {
                val chips = remember(current) {
                    (current.topGenres(4).map { g -> g.replaceFirstChar { it.uppercase() } } +
                        current.topStudios(2))
                        .filter { it.isNotBlank() }
                }
                if (chips.isNotEmpty()) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        chips.forEach { TasteChip(it) }
                    }
                }
            }
        }

        // «Под настроение»: одно нажатие сужает подборку до жанров настроения, повторное
        // снимает. Всегда видно, сколько осталось, — чтобы пустое сито не выглядело сбоем.
        if (visible.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RecommendationMood.entries.forEach { option ->
                    val active = mood == option
                    val count = remember(visible, option) { visible.count { option.matches(it.anime) } }
                    Box(
                        Modifier.clip(Shapes.chip)
                            .background(if (active) AccentOrange.copy(alpha = 0.28f) else Surface2)
                            .clickable(enabled = count > 0 || active) { cache.mood = if (active) null else option.key }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            "${option.label} · $count",
                            color = if (active) AccentOrange else if (count > 0) TextPrimary else TextTertiary,
                            fontSize = 12.sp, maxLines = 1,
                        )
                    }
                }
            }
        }

        when {
            // Следов просмотра нет вовсе — подбирать не из чего, и честнее сказать это,
            // чем выдать «популярное» за персональную подборку.
            failed && items.isEmpty() -> LoadFailed(Modifier.weight(1f)) { refreshNonce++ }
            mood != null && items.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Под «${mood.label}» в подборке ничего нет — нажмите «Обновить» или снимите настроение.", color = TextSecondary, fontSize = 13.sp)
            }
            else -> {
                val explanations = remember(items) { items.mapNotNull { rec -> rec.explanation?.let { rec.anime.id to it } }.toMap() }
                PosterGrid(
                    items = remember(items) { items.map { it.anime } },
                    isLoading = loading,
                    modifier = Modifier.weight(1f),
                    gridState = cache.gridState,
                    onLoadMore = if (exhausted) null else ::loadMore,
                    explanations = explanations,
                    onClick = onOpen,
                )
            }
        }
    }
}

/**
 * Сколько заходов за кандидатами прощаем одному шагу прокрутки, прежде чем признать
 * каталог исчерпанным. Четыре — это примерно сотня карточек, из которых после отбора
 * не осталось ни одной новой; дальше ходить смысла нет.
 */
private const val MAX_ROUNDS_PER_STEP = 4

/** Любимые жанры зрителя в виде ключей [CatalogTag] — ими каталог умеет фильтровать. */
private fun tagKeysOf(taste: Recommender.Taste): List<String> =
    taste.topGenres(6).mapNotNull { genre ->
        CatalogTag.ANIME.firstOrNull { it.anixart == genre }?.key
    }

/**
 * Подпись под заголовком.
 *
 * «Из вашей истории» здесь стояло по недосмотру и вводило в заблуждение: вкус
 * собирается не только по истории, а по избранному (самый тяжёлый след), по
 * досмотренным сериям, по брошенному и по отметкам «просмотрено» с карточек. Человек
 * читал «по истории» и справедливо не понимал, почему отметка ничего не меняет.
 */
private fun subtitleFor(taste: Recommender.Taste?, shown: Int): String {
    if (taste == null) return "Собираем подборку…"
    if (taste.isEmpty) return "Для знакомства: рейтинговое, популярное и актуальное · пока без личного вкуса"
    // Оценки называются ОТДЕЛЬНО и первыми, потому что они и весят больше всего
    // остального вместе взятого. Человеку должно быть видно, что его оценки идут в
    // дело, — иначе непонятно, зачем их ставить.
    val basis = if (taste.ratedSamples > 0) {
        "по ${taste.ratedSamples} ${ratingWord(taste.ratedSamples)} и ещё " +
            "${taste.samples - taste.ratedSamples} ${titleWord(taste.samples - taste.ratedSamples)}"
    } else {
        "по ${taste.samples} ${titleWord(taste.samples)}, которые вы смотрели или отметили"
    }
    return if (shown == 0) basis.replaceFirstChar { it.uppercase() } else "$shown ${pickWord(shown)} · $basis"
}

private fun ratingWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "вашим оценкам"
        mod10 == 1 -> "вашей оценке"
        else -> "вашим оценкам"
    }
}

private fun titleWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "тайтлам"
        mod10 == 1 -> "тайтлу"
        else -> "тайтлам"
    }
}

private fun pickWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "находок"
        mod10 == 1 -> "находка"
        mod10 in 2..4 -> "находки"
        else -> "находок"
    }
}

/** Плашка «на чём построено» — тот же вид, что у фактов на странице тайтла. */
@Composable
private fun TasteChip(label: String) {
    Box(
        Modifier.clip(Shapes.chip).background(Surface2).padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, color = TextPrimary, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun EmptyTaste(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Подбирать пока не по чему", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Посмотрите несколько тайтлов, отметьте галочкой уже виденное " +
                    "или добавьте что-нибудь в избранное —\n" +
                    "чем больше следов, тем точнее подборка.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun LoadFailed(modifier: Modifier, onRetry: () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Каталог не ответил", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Это сбой связи или источника, а не отсутствие рекомендаций.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = AccentOrange, contentColor = OledBlack),
                modifier = Modifier.padding(top = 12.dp),
            ) { Text("Повторить", fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * Настроение — набор жанров, которым должна соответствовать карточка (хотя бы один).
 * Жанры — в написании каталога (см. CatalogTag.anixart, в нижнем регистре).
 */
enum class RecommendationMood(val key: String, val label: String, val genres: Set<String>) {
    LIGHT("light", "Лёгкое", setOf("комедия", "повседневность", "гурман", "музыка")),
    DARK("dark", "Мрачное", setOf("триллер", "ужасы", "психологическое", "тайна", "детектив", "выживание")),
    ROMANCE("romance", "Романтика", setOf("романтика", "сёдзё", "гарем", "школа")),
    ACTION("action", "Экшен", setOf("экшен", "боевые искусства", "супер сила", "меха", "самураи", "военное")),
    FANTASY("fantasy", "Другие миры", setOf("фэнтези", "исэкай", "реинкарнация", "магия", "городское фэнтези", "фантастика", "космос")),
    DRAMA("drama", "Драма", setOf("драма", "сэйнэн", "исторический", "психологическое"));

    fun matches(anime: com.aniblaze.aggregator.model.Anime): Boolean =
        com.aniblaze.desktop.Recommender.genresOf(anime).any { it in genres }

    companion object {
        fun byKey(key: String?): RecommendationMood? = entries.firstOrNull { it.key == key }
    }
}
