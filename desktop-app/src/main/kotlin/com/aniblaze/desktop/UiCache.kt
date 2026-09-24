package com.aniblaze.desktop

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.CatalogFilter

/**
 * Screen state hoisted to the app root so it survives navigating between tabs —
 * switching Главная ↔ Кино no longer re-fetches everything. A manual refresh
 * button reloads on demand. Held via `remember` in App(), so it lives as long as
 * the window.
 */
/**
 * Подборка рекомендаций, поднятая к корню приложения.
 *
 * Раньше состояние жило в самом экране и умирало при уходе с вкладки: каждый
 * заход на «Рекомендации» пересобирал подборку заново — выглядело как
 * самопроизвольное «автообновление». Теперь список переживает навигацию и
 * меняется только по кнопке «Обновить» (или при смене источника). Прокрутка
 * тоже сохраняется.
 */
class RecommendationsCache {
    var taste by mutableStateOf<Recommender.Taste?>(null)
    var displayedTaste by mutableStateOf<Recommender.Taste?>(null)
    var items by mutableStateOf<List<Recommender.Recommendation>>(emptyList())
    var pool by mutableStateOf<List<Anime>>(emptyList())
    var nextRound by mutableStateOf(0)
    var exhausted by mutableStateOf(false)
    var failed by mutableStateOf(false)
    /** Ключ источников, под который собрана подборка; сменился — пересобираем. */
    var sourceKey by mutableStateOf("")
    /** Подборка собрана хотя бы раз — на повторный заход ничего не грузим. */
    var loaded by mutableStateOf(false)
    /** Ключ настроения (см. RecommendationMood); null — вся подборка. */
    var mood by mutableStateOf<String?>(null)
    val gridState = LazyGridState()
}

class HomeCache {
    var query by mutableStateOf("")
    var searchResults by mutableStateOf<List<Anime>>(emptyList())
    /** Исправленный запрос при опечатке (см. DesktopRepository.lastTypoFix). */
    var typoFix by mutableStateOf<String?>(null)
    var sections by mutableStateOf<Map<String, List<Anime>>>(emptyMap())
    var loaded by mutableStateOf(false)
    // Per-section horizontal paging: which page each row has loaded + which are busy,
    // so scrolling a strip to its end keeps appending to that section.
    var rowPage by mutableStateOf<Map<String, Int>>(emptyMap())
    var rowBusy by mutableStateOf<Set<String>>(emptySet())
    // Endless tail under the fixed rows: a growing grid so scrolling the whole Home
    // feed down keeps loading more instead of hitting a bottom.
    var tail by mutableStateOf<List<Anime>>(emptyList())
    var tailPage by mutableStateOf(0)
    var tailBusy by mutableStateOf(false)
    var rowEnded by mutableStateOf<Set<String>>(emptySet())  // horizontal rows with no more pages
    // Секции, чья загрузка УПАЛА (а не вернула пусто). Разные вещи: пустая лента —
    // это факт, упавшая — повод показать «не удалось загрузить» и кнопку повтора.
    var failedSections by mutableStateOf<Set<String>>(emptySet())
    // "Развернуть" on a section → a grid that paginates THAT section's own feed.
    var expandedKey by mutableStateOf<String?>(null)
    var expandedTitle by mutableStateOf("")
    var expandedItems by mutableStateOf<List<Anime>>(emptyList())
    var expandedPage by mutableStateOf(0)
    var expandedBusy by mutableStateOf(false)
    var expandedEnded by mutableStateOf(false)   // feed exhausted → stop firing load-more
    // Фильтры каталога. Живут ЗДЕСЬ, а не в самом экране: кэш создан в App() и
    // переживает переход на страницу тайтла и обратно — именно поэтому выбранные
    // фильтры не сбрасываются, когда открываешь тайтл и возвращаешься.
    var filter by mutableStateOf(CatalogFilter())
    var filterOpen by mutableStateOf(false)
    /** Прошлый фильтр уже подставлен из настроек (делается один раз на окно). */
    var filterRestored by mutableStateOf(false)
    /**
     * Отпечаток запроса, которым набрана текущая сетка («категория|фильтр»).
     *
     * Нужен, чтобы ВОЗВРАТ С ТАЙТЛА не перезагружал ленту. Возврат — это новая
     * композиция экрана с тем же кэшем; без отпечатка эффект считал её изменением,
     * грузил всё заново и сбрасывал прокрутку в начало, хотя показать надо ровно то
     * же место, докуда долистали.
     */
    var browseFingerprint by mutableStateOf("")
    // Состояния прокрутки ЖИВУТ В КЭШЕ, а не в композиции: кэш создан в App() и
    // переживает уход на страницу тайтла — именно поэтому список возвращается на то
    // же место, а не в начало.
    val listState = LazyListState()
    val gridState = LazyGridState()

    val hasLocalNavigation: Boolean get() = filterOpen || !filter.isDefault || expandedKey != null || query.isNotEmpty()

    /** Explicit Home/Back, not a return from a title: retain loaded rows and their scroll. */
    fun returnToLanding(): Boolean {
        if (!hasLocalNavigation) return false
        query = ""
        filter = CatalogFilter()
        filterOpen = false
        expandedKey = null
        expandedTitle = ""
        browseFingerprint = ""
        return true
    }
}

internal fun NavController.backFromHome(cache: HomeCache): Boolean =
    if (current == Screen.Home && cache.returnToLanding()) true else back()

class CinemaCache {
    var path by mutableStateOf("films")   // paginated all-films feed (infinite scroll)
    var sort by mutableStateOf(0)         // 0=по списку, 1=рейтинг, 2=год, 3=имя (client-side)
    var minRating by mutableStateOf(0.0)  // filter: show only films with rating ≥ this
    var query by mutableStateOf("")
    var filtersOpen by mutableStateOf(false)
    // Rows landing (default view): first page of each category, lazy-loaded.
    // expandedCat != null → showing that one category as a full grid instead.
    var rows by mutableStateOf<Map<String, List<Anime>>>(emptyMap())
    var rowsNonce by mutableStateOf(0)    // bump to force the landing rows to reload
    // Per-category horizontal paging for the landing strips (infinite scroll right).
    var rowPage by mutableStateOf<Map<String, Int>>(emptyMap())
    var rowBusy by mutableStateOf<Set<String>>(emptySet())
    // Ряды, чья загрузка УПАЛА. Отдельно от «пусто»: у главной это различие уже
    // было, у «Кино» и «Мультфильмов» — нет, и недоступный TMDB выглядел как
    // «в категории ничего нет», без кнопки повтора.
    var rowFailed by mutableStateOf<Set<String>>(emptySet())
    var expandedCat by mutableStateOf<String?>(null)
    val landingState = LazyListState()
    var items by mutableStateOf<List<Anime>>(emptyList())
    var page by mutableStateOf(0)
    var canLoadMore by mutableStateOf(true)
    var loaded by mutableStateOf(false)
    var busy by mutableStateOf(false)
    // Общие фильтры каталога — см. HomeCache.filter. Поля sort/minRating выше остаются
    // для источников-скрейперов, у которых своего фильтра нет вовсе.
    var filter by mutableStateOf(CatalogFilter())
    var filterTotal by mutableStateOf(0)
    val gridState = LazyGridState()
}
