package com.aniblaze.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.PersonCredit
import com.aniblaze.aggregator.model.StudioCredit

/** Simple state-based navigation — no androidx.navigation on desktop, so this
 *  small back-stack does the same job for a single-window app. */
sealed interface Screen {
    data object Home : Screen
    data object Recommendations : Screen
    data object Cinema : Screen
    data object Cartoons : Screen
    data object NewEpisodes : Screen
    data object Schedule : Screen
    data object Favorites : Screen
    data object History : Screen
    data object Stats : Screen
    data object Settings : Screen
    data class Detail(val anime: Anime, val isCinema: Boolean) : Screen
    data class Studio(val studio: StudioCredit) : Screen
    data class Person(val person: PersonCredit) : Screen
    data class Player(val anime: Anime, val segmentNumber: Int) : Screen
}

enum class TopLevel(val label: String) {
    Home("Главная"), Recommendations("Рекомендации"), Cinema("Кино"), Cartoons("Мультфильмы"), NewEpisodes("Новые серии"),
    Schedule("Расписание"),
    Favorites("Избранное"), History("История"), Stats("Статистика"), Settings("Настройки"),
}

data class TitleTab(val anime: Anime, val isCinema: Boolean)

class NavController {
    /**
     * Приведение карточки к известному id перед открытием (см. AppSettings.canonical).
     * Ставится из App; по умолчанию — тождество, чтобы тесты навигации не зависели
     * от настроек.
     */
    var canonical: (Anime) -> Anime = { it }

    private val backStack = mutableListOf<Screen>(Screen.Home)
    // Browser-style forward history: populated by [back], cleared by a fresh [go].
    private val forwardStack = mutableListOf<Screen>()
    var current: Screen by androidx.compose.runtime.mutableStateOf(Screen.Home)
        private set
    val tabs = mutableStateListOf<TitleTab>()
    var activeTabId: String? by androidx.compose.runtime.mutableStateOf(null)
        private set
    var playerSession: Screen.Player? by androidx.compose.runtime.mutableStateOf(null)
        private set

    fun openTitle(requested: Anime, isCinema: Boolean) {
        val anime = if (isCinema) requested else canonical(requested)
        if (tabs.none { it.anime.id == anime.id }) tabs.add(TitleTab(anime, isCinema))
        activeTabId = anime.id
        go(Screen.Detail(anime, isCinema))
    }

    fun openPlayer(anime: Anime, segmentNumber: Int) {
        activeTabId = anime.id
        val screen = Screen.Player(anime, segmentNumber)
        playerSession = screen
        go(screen)
    }

    fun openStudio(studio: StudioCredit) = go(Screen.Studio(studio))

    fun openPerson(person: PersonCredit) = go(Screen.Person(person))

    /** Open a title straight into the player (авто-переход на случайное аниме).
     *  Creates the title's tab like [openTitle] would, so the tab bar, back
     *  navigation and close behaviour stay consistent with a manual open. */
    fun openTitlePlaying(requested: Anime, segmentNumber: Int) {
        val anime = canonical(requested)
        if (tabs.none { it.anime.id == anime.id }) tabs.add(TitleTab(anime, isCinema = false))
        openPlayer(anime, segmentNumber)
    }

    /**
     * Клик по вкладке открывает СТРАНИЦУ ТАЙТЛА, а не возвращает в плеер.
     *
     * Раньше вкладка играющего тайтла вела обратно в плеер, и попасть на его
     * страницу через вкладки было нельзя вовсе — приходилось искать тайтл заново.
     * Плеер при этом никуда не девается: [playerSession] живёт дальше, звук
     * продолжается, а кнопка «продолжить» на странице возвращает в него мгновенно,
     * без пересоздания libVLC.
     */
    fun selectTab(id: String) {
        val tab = tabs.firstOrNull { it.anime.id == id } ?: return
        activeTabId = id
        go(Screen.Detail(tab.anime, tab.isCinema))
    }

    /** «Закрыть остальные»: все вкладки, кроме этой. Играющая серия в другой вкладке — тоже. */
    fun closeOtherTabs(id: String) {
        tabs.map { it.anime.id }.filter { it != id }.forEach(::closeTab)
    }

    /** «Закрыть справа»: вкладки правее этой, как в браузере. */
    fun closeTabsToRight(id: String) {
        val index = tabs.indexOfFirst { it.anime.id == id }
        if (index < 0) return
        tabs.drop(index + 1).map { it.anime.id }.forEach(::closeTab)
    }

    fun closeTab(id: String) {
        val index = tabs.indexOfFirst { it.anime.id == id }
        if (index < 0) return
        val wasActive = activeTabId == id
        var currentOwner: String? = null
        backStack.forEach { screen ->
            currentOwner = when (screen) {
                is Screen.Detail -> screen.anime.id
                is Screen.Player -> screen.anime.id
                is Screen.Studio, is Screen.Person -> currentOwner
                else -> null
            }
        }
        val currentBelongsToClosedTab = currentOwner == id
        tabs.removeAt(index)
        if (playerSession?.anime?.id == id) playerSession = null
        // A studio/person page inherits the title context immediately before it.
        // Remove that whole context even when the closed tab is not active; keeping
        // Studio(A) between Home and Detail(B) made Back reopen an orphaned page of A.
        var owner: String? = null
        backStack.removeAll { screen ->
            owner = when (screen) {
                is Screen.Detail -> screen.anime.id
                is Screen.Player -> screen.anime.id
                is Screen.Studio, is Screen.Person -> owner
                else -> null
            }
            owner == id
        }
        forwardStack.clear()
        if (!wasActive && !currentBelongsToClosedTab) {
            if (backStack.isEmpty()) backStack.add(Screen.Home)
            return
        }
        val replacement = tabs.getOrNull(index.coerceAtMost(tabs.lastIndex))
        if (replacement == null) {
            goTopLevel(Screen.Home)
            return
        }
        activeTabId = replacement.anime.id
        val replacementScreen = Screen.Detail(replacement.anime, replacement.isCinema)
        if (backStack.lastOrNull() != replacementScreen) backStack.add(replacementScreen)
        current = replacementScreen
    }

    fun go(screen: Screen) {
        // Clicking the active tab (or receiving the same open request twice) is a
        // no-op. Keeping two identical adjacent entries made Back appear broken on
        // its first click because it navigated to the very same screen.
        if (current == screen) return
        forwardStack.clear()
        backStack.add(screen)
        current = screen
    }

    fun goTopLevel(screen: Screen) {
        backStack.clear()
        forwardStack.clear()
        backStack.add(screen)
        current = screen
        activeTabId = null
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        forwardStack.add(backStack.removeAt(backStack.lastIndex))
        current = backStack.last()
        syncActiveTab()
        return true
    }

    fun forward(): Boolean {
        val next = forwardStack.removeLastOrNull() ?: return false
        backStack.add(next)
        current = next
        syncActiveTab()
        return true
    }

    private fun syncActiveTab() {
        activeTabId = when (val screen = current) {
            is Screen.Detail -> screen.anime.id
            is Screen.Player -> screen.anime.id
            else -> null
        }
    }

    val canGoBack: Boolean get() = backStack.size > 1
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()
}
