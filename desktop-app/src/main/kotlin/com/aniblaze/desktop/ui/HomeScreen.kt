package com.aniblaze.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.CatalogTag
import androidx.compose.foundation.background
import com.aniblaze.desktop.AppSettings
import com.aniblaze.desktop.DesktopRepository
import com.aniblaze.desktop.HomeCache
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// Anime Home sections, order copied from the phone app (SettingsDataStore.HOME_SECTIONS).
private val SECTIONS = listOf(
    "newEpisodes" to "Вышли новые серии",
    "recent" to "Последние поступления",
    "trending" to "В тренде",
    "watching" to "Сейчас смотрят",
    "announce" to "Ожидаемые анонсы",
    "ongoing" to "Сейчас выходит",
    "seasonal" to "Сезонное аниме",
    "popular" to "Популярное за всё время",
)
private val GENRE_ROWS = listOf("Экшен", "Романтика", "Фэнтези", "Комедия", "Фантастика", "Драма", "Спорт", "Ужасы")

/** Сколько тайтлов крутит витрина: листается по кругу, поэтому много — не тяжело. */
private const val HERO_POOL = 40

/** Столько карточек набираем сразу, чтобы сетку было ЧЕМ прокручивать. */
private const val BROWSE_FILL_TARGET = 60

/** Больше этого за первичное наполнение не тянем — остальное доберёт прокрутка. */
private const val BROWSE_FILL_MAX_PAGES = 3

private const val HOME_TAIL_RETRY_ATTEMPTS = 3
private const val HOME_TAIL_RETRY_DELAY_MS = 400L

/** Keep an ordinary source failure inside the feed. Letting it escape from a
 * snapshotFlow collector permanently cancels infinite loading until the screen is
 * recreated; coroutine cancellation itself must still propagate. */
internal suspend fun <T> homePageAttempt(load: suspend () -> T): Result<T> = try {
    Result.success(load())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

/** A scroll position does not change while the user remains at the bottom, so
 * snapshotFlow will not emit again after a failed request. Retry the same page a
 * few times here; backoff avoids turning an outage into a request loop. */
internal suspend fun <T> homePageWithRetry(
    attempts: Int = HOME_TAIL_RETRY_ATTEMPTS,
    initialDelayMs: Long = HOME_TAIL_RETRY_DELAY_MS,
    load: suspend () -> T,
): Result<T> {
    require(attempts > 0)
    for (attempt in 0 until attempts) {
        val result = homePageAttempt(load)
        if (result.isSuccess || attempt == attempts - 1) return result
        if (initialDelayMs > 0) kotlinx.coroutines.delay(initialDelayMs * (attempt + 1L))
    }
    error("unreachable")
}

@Composable
fun HomeScreen(
    repository: DesktopRepository,
    cache: HomeCache,
    settings: AppSettings,
    onOpen: (Anime) -> Unit,
    /** «Смотреть» в витрине — сразу в плеер; null — витрина ведёт на страницу тайтла. */
    onPlay: ((Anime) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val settingsState by settings.state.collectAsState()
    val continueItems = remember(settingsState) { settings.continueWatching() }
    // Прошлый фильтр — при первом показе окна; дальше каждое изменение пишется в
    // настройки вместе со счётчиком частоты условий.
    LaunchedEffect(Unit) {
        if (!cache.filterRestored) {
            cache.filterRestored = true
            settings.lastAnimeFilter()?.let { cache.filter = it }
        }
    }
    LaunchedEffect(cache.filter) {
        if (cache.filterRestored) settings.saveAnimeFilter(cache.filter)
    }
    val frequentKeys = remember(settingsState.filterUsage) { settings.frequentFilterKeys(settingsState) }

    // Titles a followed anime just got a new episode for (flagged by the background
    // notifier). Shown as the first row so it's the first thing you see.

    suspend fun loadSections() {
        val keys = SECTIONS.map { it.first } + GENRE_ROWS.map { "genre:$it" }
        // Avoid the old 15-section startup burst overwhelming the same APIs/CDNs.
        // Four sections still fill the screen progressively without creating dozens
        // of simultaneous blocking OkHttp calls.
        val gate = Semaphore(4)
        // Успех и провал разделены: секция, чей запрос упал, попадает в failedSections
        // и рисует «не удалось загрузить» с повтором — раньше она была неотличима от
        // честно пустой категории и выглядела как «тут ничего нет».
        val results = coroutineScope {
            keys.map { k ->
                async {
                    val outcome = gate.withPermit {
                        try {
                            Result.success(repository.homeSection(k, 0))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            com.aniblaze.desktop.player.PlayerDiagnostics.failure("home.section.$k", error)
                            Result.failure(error)
                        }
                    }
                    k to outcome
                }
            }.map { it.await() }
        }
        cache.sections = results.mapNotNull { (k, r) -> r.getOrNull()?.let { k to it } }.toMap()
            .plus("newEpisodes" to repository.recentEpisodeTitles())
        cache.failedSections = results.filter { it.second.isFailure }.map { it.first }.toSet()
        cache.loaded = true
    }

    /** Перезагрузить ОДНУ секцию (кнопка «Повторить» в упавшем ряду). */
    suspend fun retrySection(key: String) {
        if (cache.rowBusy.contains(key)) return
        cache.rowBusy = cache.rowBusy + key
        try {
            val items = repository.homeSection(key, 0)
            cache.sections = cache.sections + (key to items)
            cache.failedSections = cache.failedSections - key
            cache.rowEnded = cache.rowEnded - key
            cache.rowPage = cache.rowPage - key
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            com.aniblaze.desktop.player.PlayerDiagnostics.failure("home.retry.$key", error)
        } finally {
            cache.rowBusy = cache.rowBusy - key
        }
    }

    suspend fun loadMoreRow(key: String) {
        if (cache.rowBusy.contains(key) || cache.rowEnded.contains(key)) return
        val nextPage = (cache.rowPage[key] ?: 0) + 1
        cache.rowBusy = cache.rowBusy + key
        try {
            val more = repository.homeSection(key, nextPage)
            if (more.isNotEmpty()) {
                cache.rowPage = cache.rowPage + (key to nextPage)
                cache.sections = cache.sections + (key to (cache.sections[key].orEmpty() + more).distinctBy { it.id })
            } else {
                cache.rowEnded = cache.rowEnded + key // no more pages — stop firing
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // ВАЖНО: сбой сети — это не конец ленты. Раньше исключение улетало наружу,
            // ряд оставался "занят"/битым, и подгрузка умирала до перезахода на экран.
            // Просто не двигаем страницу: следующий скролл попробует снова.
            com.aniblaze.desktop.player.PlayerDiagnostics.failure("home.loadMore.$key", error)
        } finally {
            cache.rowBusy = cache.rowBusy - key
        }
    }

    /**
     * Одна страница «развёрнутого» вида — им теперь стал и результат фильтра.
     *
     * Три случая в одном месте, потому что сетка и подгрузка у них общие:
     *  • фильтр пуст, категория открыта → лента категории как раньше;
     *  • фильтр задан, категория открыта → её же лента, просеянная (репозиторий сам
     *    дотягивает следующие страницы, пока не наберётся экран);
     *  • фильтр задан, категории нет → каталог, суженный на стороне источника.
     */
    suspend fun browsePage(page: Int): List<Anime> {
        val key = cache.expandedKey
        return if (cache.filter.isDefault) {
            if (key == null) emptyList() else repository.homeSection(key, page)
        } else {
            repository.animeFilterPage(cache.filter, key, page)
        }
    }

    /** Одна следующая страница. true — что-то добавилось. */
    suspend fun loadNextPage(): Boolean {
        if (cache.expandedEnded) return false
        if (cache.expandedKey == null && cache.filter.isDefault) return false
        val page = cache.expandedPage + 1
        val next = try {
            browsePage(page)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Сбой сети — НЕ конец ленты: страницу не двигаем, следующая попытка
            // прокрутки повторит её.
            com.aniblaze.desktop.player.PlayerDiagnostics.failure("home.browse.page$page", error)
            return false
        }
        val merged = (cache.expandedItems + next).distinctBy { it.id }
        val added = merged.size - cache.expandedItems.size
        com.aniblaze.desktop.player.PlayerDiagnostics.log(
            "home.browse.more",
            "key=${cache.expandedKey}; page=$page; came=${next.size}; added=$added; total=${merged.size}",
        )
        if (added <= 0) {
            cache.expandedEnded = true // лента действительно кончилась
            return false
        }
        cache.expandedPage = page
        cache.expandedItems = merged
        return true
    }

    suspend fun loadMoreExpanded() {
        if (cache.expandedBusy || cache.expandedEnded) return
        cache.expandedBusy = true
        try {
            loadNextPage()
        } finally {
            cache.expandedBusy = false
        }
    }

    /**
     * Сброс и первичное наполнение — на смену категории или фильтра.
     *
     * Страниц берётся столько, чтобы СЕТКА СТАЛА ПРОКРУЧИВАЕМОЙ, и это не украшение.
     * Догрузка висит на прокрутке, а у неё событийная природа: она срабатывает, когда
     * меняется индекс последней видимой карточки. Раньше первая же просьба догрузить
     * приходила, пока шла загрузка страницы 0, и её отбрасывала проверка занятости;
     * потом размер списка не менялся (те же 23 карточки из кэша ряда), всё влезало на
     * экран, прокрутки не было — и перевзвести триггер становилось некому. Лента
     * замирала на первой странице навсегда.
     *
     * Теперь первичное наполнение НЕ ЗАВИСИТ ОТ ПРОКРУТКИ ВООБЩЕ.
     */
    suspend fun loadBrowse() {
        cache.expandedPage = 0
        cache.expandedEnded = false
        cache.expandedBusy = true
        try {
            cache.expandedItems = browsePage(0)
            com.aniblaze.desktop.player.PlayerDiagnostics.log(
                "home.browse.first",
                "key=${cache.expandedKey}; filter=${cache.filter.activeCount}; got=${cache.expandedItems.size}",
            )
            var pages = 0
            while (cache.expandedItems.size < BROWSE_FILL_TARGET &&
                pages < BROWSE_FILL_MAX_PAGES &&
                !cache.expandedEnded
            ) {
                pages++
                if (!loadNextPage()) break
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            com.aniblaze.desktop.player.PlayerDiagnostics.failure("home.browse", error)
        } finally {
            cache.expandedBusy = false
        }
    }

    // Endless tail under the fixed rows. Uses the "popular" feed offset a few pages
    // past what the Популярное row itself shows, so the whole Home page keeps loading
    // as you scroll down instead of dead-ending after the last category.
    suspend fun loadTail() {
        if (cache.tailBusy) return
        cache.tailBusy = true
        try {
            val result = homePageWithRetry { repository.catalogPage(4, cache.tailPage + 3) }
            val more = result.getOrElse { error ->
                com.aniblaze.desktop.player.PlayerDiagnostics.failure("home.tail.page${cache.tailPage + 3}", error)
                return
            }
            if (more.isNotEmpty()) {
                cache.tailPage += 1
                cache.tail = (cache.tail + more).distinctBy { it.id }
            }
        } finally {
            cache.tailBusy = false
        }
    }

    LaunchedEffect(Unit) { if (!cache.loaded) loadSections() }
    // Expire old events even if Home stays open overnight, and refresh only this
    // section on return. The repository shares discovery requests with other rows.
    LaunchedEffect(repository) {
        while (true) {
            try { retrySection("newEpisodes") } catch (cancelled: CancellationException) { throw cancelled }
            kotlinx.coroutines.delay(5 * 60_000L)
        }
    }
    val episodeRevision by repository.episodeReleaseRevision.collectAsState()
    LaunchedEffect(episodeRevision) {
        cache.sections = cache.sections + ("newEpisodes" to repository.recentEpisodeTitles())
    }

    // Перезапрос — ТОЛЬКО на смену того, что реально меняет выдачу.
    //
    // Возврат с тайтла на главную — это новая композиция экрана с тем же кэшем, и
    // грузить всё заново тут нечего: список уже набран, а перезагрузка вдобавок сбила
    // бы прокрутку на начало. Отпечаток запроса запоминается в кэше, и эффект молчит,
    // пока запрос тот же.
    LaunchedEffect(cache.filter, cache.expandedKey) {
        if (cache.expandedKey == null && cache.filter.isDefault) return@LaunchedEffect
        val fingerprint = "${cache.expandedKey}|${cache.filter}"
        if (cache.browseFingerprint == fingerprint && cache.expandedItems.isNotEmpty()) {
            com.aniblaze.desktop.player.PlayerDiagnostics.log("home.browse.kept", "items=${cache.expandedItems.size}")
            return@LaunchedEffect
        }
        cache.browseFingerprint = fingerprint
        loadBrowse()
    }

    // Fire the tail loader whenever the vertical feed nears its bottom (rows view only).
    LaunchedEffect(cache.expandedKey, cache.query, cache.loaded) {
        if (cache.expandedKey != null || cache.query.length >= 2) return@LaunchedEffect
        snapshotFlow {
            val info = cache.listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.collect { (last, total) -> if (total > 0 && last >= total - 2) loadTail() }
    }

    LaunchedEffect(cache.query) {
        if (cache.query.length >= 2) {
            kotlinx.coroutines.delay(350)
            cache.searchResults = repository.search(cache.query)
            cache.typoFix = repository.lastTypoFix
        } else {
            cache.searchResults = emptyList()
            cache.typoFix = null
        }
    }

    // Узкое окно = телефонная ширина: панель фильтров выдвигается поверх ленты, а не
    // раскрывается под полосой (пять блоков в колонку всё равно заняли бы весь экран).
    androidx.compose.runtime.CompositionLocalProvider(LocalFrequentFilterKeys provides frequentKeys) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxWidth < COMPACT_WIDTH
    val facets = remember { repository.animeFacets() }
    // Поиск фильтруется на нашей стороне: он идёт по названию во все источники сразу,
    // и сузить его запросом нельзя — зато выбранные теги обязаны действовать и здесь.
    val searchShown = remember(cache.searchResults, cache.filter) {
        if (cache.filter.isEmpty) cache.searchResults
        else cache.searchResults.filter { cache.filter.matches(it, CatalogTag::of) }
    }
    // Сколько нашлось. Точного числа каталог аниме не отдаёт, поэтому пока лента не
    // кончилась — отрицательное, и полоса покажет «Найдено N+».
    val found = when {
        cache.query.length >= 2 -> searchShown.size
        cache.expandedKey == null && cache.filter.isDefault -> 0
        cache.expandedEnded -> cache.expandedItems.size
        else -> -cache.expandedItems.size
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (cache.hasLocalNavigation) {
                    IconButton(onClick = { cache.returnToLanding() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "На главную", tint = AccentOrange)
                    }
                }
                OutlinedTextField(
                    value = cache.query,
                    onValueChange = { cache.query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Поиск аниме, жанров, студий…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    // Подсказка о горячей клавише прямо в поле: раньше про Ctrl+K
                    // знал только тот, кто читал настройки.
                    trailingIcon = {
                        Box(
                            Modifier.padding(end = 6.dp).clip(Shapes.chip).background(GlassFill)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text("Ctrl + K", color = TextSecondary, fontSize = 11.sp)
                        }
                    },
                    shape = Shapes.chip,
                    singleLine = true,
                )
                IconButton(onClick = { scope.launch { loadSections() } }, modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = AccentOrange)
                }
                IconButton(onClick = { scope.launch { repository.random()?.let(onOpen) } }) {
                    Icon(Icons.Filled.Shuffle, contentDescription = "Случайное", tint = AccentOrange)
                }
                // Global source switch: changes which parser powers the WHOLE anime
                // side — Home categories, search and the player default — not just the
                // per-title player picker.
                SourceMenu(repository.animeSourceOptions(), settingsState.primarySource) { sel ->
                    settings.setPrimarySource(sel)
                    cache.loaded = false
                    cache.sections = emptyMap()
                    cache.rowPage = emptyMap(); cache.rowEnded = emptySet()
                    cache.tail = emptyList(); cache.tailPage = 0
                    cache.expandedKey = null; cache.query = ""
                    scope.launch { loadSections() }
                }
            }
            if (cache.expandedKey != null) {
                Text(cache.expandedTitle, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }
            // Опечатка исправлена по известным названиям — говорим, что искали на самом деле.
            cache.typoFix?.takeIf { cache.query.length >= 2 }?.let { fixed ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Возможно, вы имели в виду: ", color = TextSecondary, fontSize = 13.sp)
                    Text(
                        fixed, color = AccentOrange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { cache.query = fixed },
                    )
                }
            }
            // Полоса фильтров есть ВЕЗДЕ: и на ленте разделов, и внутри открытой
            // категории, и над результатами поиска — категория «Сейчас смотрят»
            // фильтруется ровно так же, как каталог целиком.
            FilterBar(
                facets = facets,
                filter = cache.filter,
                found = found,
                open = cache.filterOpen,
                onOpenChange = { cache.filterOpen = it },
                compact = compact,
                onFilter = { cache.filter = it },
                modifier = Modifier.padding(top = 10.dp),
            )
            // Быстрые жанры: то же поле фильтра, что и в панели, только в один клик.
            // Отдельного механизма под них нет намеренно — иначе «Фэнтези» из чипа и
            // «Фэнтези» из панели означали бы разное.
            GenreChips(
                selected = cache.filter.tags,
                onSelect = { key ->
                    cache.filter = cache.filter.copy(tags = if (key == null) emptySet() else setOf(key))
                },
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        when {
            cache.query.length >= 2 -> PosterGrid(searchShown, isLoading = false, Modifier.weight(1f), onClick = onOpen)
            cache.expandedKey != null || !cache.filter.isDefault -> PosterGrid(
                items = cache.expandedItems,
                isLoading = cache.expandedBusy && cache.expandedItems.isEmpty(),
                modifier = Modifier.weight(1f),
                gridState = cache.gridState,
                onLoadMore = { scope.launch { loadMoreExpanded() } },
                onClick = onOpen,
            )
            else -> SectionRows(
                cache, continueItems, settingsState.gridColumns, Modifier.weight(1f), onOpen, onPlay,
                onLoadMoreRow = { key -> scope.launch { loadMoreRow(key) } },
                onRetrySection = { key -> scope.launch { retrySection(key) } },
            ) { key, title ->
                cache.expandedKey = key
                cache.expandedTitle = title
                cache.expandedItems = cache.sections[key].orEmpty()
                cache.expandedPage = 0
                cache.expandedEnded = false
            }
        }
    }
    FilterSheet(
        facets = facets,
        filter = cache.filter,
        visible = compact && cache.filterOpen,
        onClose = { cache.filterOpen = false },
        onFilter = { cache.filter = it },
    )
    }
    }
}

/**
 * Жанры одной строкой: «Все» и несколько самых частых.
 *
 * Это не новый фильтр, а ярлык к существующему: чип кладёт ключ тега в тот же
 * [com.aniblaze.aggregator.model.CatalogFilter.tags], который заполняет панель
 * фильтров, поэтому выбор виден в обоих местах и снимается в обоих.
 */
@Composable
private fun GenreChips(selected: Set<String>, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val chips = remember { CatalogTag.entries.filter { it.key in QUICK_GENRES }.sortedBy { QUICK_GENRES.indexOf(it.key) } }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GenreChip("Все", selected.isEmpty()) { onSelect(null) }
        chips.forEach { tag ->
            GenreChip(tag.label, selected == setOf(tag.key)) { onSelect(tag.key) }
        }
    }
}

@Composable
private fun GenreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 8.dp).clip(Shapes.pill)
            .background(if (selected) AccentOrange else GlassFill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = if (selected) OledBlack else TextPrimary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** Жанры для быстрых чипов — порядок как на макете. */
private val QUICK_GENRES = listOf("fantasy", "action", "romance", "comedy", "adventure", "drama", "thriller")

/** Global anime-source dropdown shown in the Home top bar. */
@Composable
private fun SourceMenu(options: List<String>, current: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current, color = AccentOrange, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Источник", tint = AccentOrange)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(if (o == current) "● $o" else o) },
                    onClick = { open = false; if (o != current) onSelect(o) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionRows(
    cache: HomeCache,
    continueItems: List<Anime>,
    gridColumns: Int,
    modifier: Modifier,
    onOpen: (Anime) -> Unit,
    onPlay: ((Anime) -> Unit)?,
    onLoadMoreRow: (String) -> Unit,
    onRetrySection: (String) -> Unit,
    onExpand: (key: String, title: String) -> Unit,
) {
    // Named sections always show; genre rows only when they returned titles.
    val rows = remember(cache.sections, cache.loaded) {
        SECTIONS + GENRE_ROWS.map { "genre:$it" to it }.filter { (k, _) -> !cache.loaded || cache.sections[k].orEmpty().isNotEmpty() }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
    val columns = posterColumnCount(gridColumns, maxWidth.value)
    val cardWidth = posterCardWidthDp(maxWidth.value, columns).dp
    val cardPx = with(androidx.compose.ui.platform.LocalDensity.current) { cardWidth.roundToPx() }
    // Хвост разбит по РЕАЛЬНОМУ числу колонок: каждая lazy-item — ровно один ряд.
    // Так настройка 5 не оставляет четыре карточки и пустую ячейку на границе чанка.
    val tailRows = remember(cache.tail, columns) { cache.tail.chunked(columns) }
    // Витрина берёт УЖЕ загруженные ленты: своего запроса не делает. Собирает до
    // HERO_POOL карточек из нескольких секций, без повторов, и только то, чего зритель
    // ещё не смотрел (ни серии в истории), не отметил «не понравилось» и что уже вышло.
    val settings = LocalAppSettings.current
    val settingsSnapshot = settings?.state?.collectAsState()?.value
    val heroItems = remember(cache.sections, settingsSnapshot?.disliked, settingsSnapshot?.snoozedUntil, settingsSnapshot?.history?.size, settingsSnapshot?.progress?.size) {
        val preferred = listOf("trending", "popular", "recent", "watching", "seasonal", "ongoing")
        val pools = preferred.asSequence().mapNotNull { cache.sections[it] } +
            cache.sections.entries.asSequence().filterNot { it.key in preferred || it.key == "announce" }.map { it.value }
        val out = LinkedHashMap<String, Anime>()
        for (items in pools) {
            for (anime in items) {
                if (out.size >= HERO_POOL) break
                if (anime.poster.isBlank() || anime.id in out) continue
                if (com.aniblaze.aggregator.model.isUnreleased(anime)) continue
                if (settings != null && settingsSnapshot != null) {
                    val canonical = settings.canonical(anime)
                    if (settings.isDisliked(canonical.id) || settings.isSnoozed(canonical.id, settingsSnapshot)) continue
                    if (settings.watchOf(canonical.id) != null) continue
                    if (settingsSnapshot.history.any { it.id == canonical.id }) continue
                }
                out[anime.id] = anime
            }
        }
        out.values.toList()
    }
    LazyColumn(Modifier.fillMaxSize().browserAutoScroll(cache.listState), state = cache.listState) {
        if (heroItems.isNotEmpty()) {
            item(key = "hero") {
                HeroBanner(
                    heroItems,
                    onOpen = onOpen,
                    onPlay = onPlay,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
            }
        }
        if (continueItems.isNotEmpty()) {
            item(key = "continue") {
                PosterRow("Продолжить просмотр", continueItems, loading = false, gridColumns = gridColumns, onOpen = onOpen)
            }
        }
        items(rows, key = { it.first }) { (key, title) ->
            PosterRow(
                title, cache.sections[key].orEmpty(), loading = !cache.loaded,
                gridColumns = gridColumns,
                onExpandAll = { onExpand(key, title) },
                onLoadMore = { onLoadMoreRow(key) },
                failed = cache.failedSections.contains(key),
                onRetry = { onRetrySection(key) },
                onOpen = onOpen,
            )
        }
        if (tailRows.isNotEmpty()) {
            item(key = "tail-header") {
                Text("Ещё", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
            }
            items(tailRows, key = { it.first().id }) { row ->
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    row.forEach { anime ->
                        androidx.compose.runtime.key(anime.id) {
                            Box(Modifier.width(cardWidth)) {
                                PosterCard(anime, posterWidthPx = cardPx, onClick = { onOpen(anime) })
                            }
                        }
                    }
                }
            }
        }
        item { Box(Modifier.height(24.dp)) }
    }
    }
}
