package com.aniblaze.app.navigation

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.ui.graphics.vector.ImageVector

/** Centralised route table. Content/segment ids are URL-encoded in paths. */
object Routes {
    const val HOME = "home"
    const val CINEMA = "cinema"
    const val SEARCH = "search"
    const val FAVORITES = "favorites"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val SCHEDULE = "schedule"
    const val NEW_EPISODES = "newepisodes"
    const val STATS = "stats"
    const val DOWNLOADS = "downloads"

    const val DETAIL = "detail/{contentId}"
    const val PLAYER = "player/{contentId}/{segmentId}?mode={streamMode}"
    const val CINEMA_WEB = "cinemaweb/{pageUrl}"

    fun detail(contentId: String) = "detail/${Uri.encode(contentId)}"
    fun player(contentId: String, segmentId: String, streamMode: String = "auto") =
        "player/${Uri.encode(contentId)}/${Uri.encode(segmentId)}?mode=${Uri.encode(streamMode)}"
    fun cinemaWeb(pageUrl: String) = "cinemaweb/${Uri.encode(pageUrl)}"
}

/**
 * Пункты нижней панели. Их РОВНО ПЯТЬ, и это предел.
 *
 * С переносом расписания, новых серий и статистики набралось девять экранов верхнего
 * уровня. Девять кнопок в нижней панели телефона — это по 40 dp на каждую: подпись
 * не влезает, попасть пальцем трудно. Material прямо ограничивает панель пятью.
 * Поэтому часто нужное осталось внизу, а остальное ушло за «Ещё» ([MoreDestination]).
 */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home(Routes.HOME, "Главная", Icons.Filled.Home),
    Cinema(Routes.CINEMA, "Кино", Icons.Filled.Movie),
    Search(Routes.SEARCH, "Поиск", Icons.Filled.Search),
    Favorites(Routes.FAVORITES, "Избранное", Icons.Filled.Favorite),
    ;

    companion object {
        /** Маршруты, при которых нижняя панель видна (включая экраны из «Ещё»). */
        val barVisibleRoutes: Set<String> =
            entries.map { it.route }.toSet() + MoreDestination.entries.map { it.route }
    }
}

/** Экраны за кнопкой «Ещё» — те, куда ходят реже, чем в первые четыре. */
enum class MoreDestination(
    val route: String,
    val label: String,
    val hint: String,
    val icon: ImageVector,
) {
    Downloads(Routes.DOWNLOADS, "Загрузки", "Скорость и управление торрентами", Icons.Filled.Downloading),
    NewEpisodes(Routes.NEW_EPISODES, "Новые серии", "Что вышло у отслеживаемых", Icons.Filled.NewReleases),
    Schedule(Routes.SCHEDULE, "Расписание", "Когда выйдут следующие", Icons.Filled.CalendarMonth),
    History(Routes.HISTORY, "История", "Что уже смотрел", Icons.Filled.History),
    Stats(Routes.STATS, "Статистика", "Часы, жанры и Шлакометр", Icons.Filled.Insights),
    Settings(Routes.SETTINGS, "Настройки", "Источники и оформление", Icons.Filled.Settings),
}
