package com.aniblaze.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.desktop.ui.AniBlazeTheme
import com.aniblaze.desktop.ui.DetailScreen
import com.aniblaze.desktop.ui.Surface1

/**
 * Отдельное окно с одним тайтлом.
 *
 * ЧИТАТЕЛЬНОЕ, а не второй экземпляр приложения: здесь только карточка тайтла —
 * описание, серии, похожие. Смотреть отсюда нельзя намеренно, и причина техническая,
 * а не вкусовая: проигрыватель libVLC в приложении ОДИН, все его команды идут через
 * один поток, и второй плеер в другом окне дрался бы с первым за него. Кнопка
 * воспроизведения поэтому уводит в главное окно, к уже живому плееру.
 *
 * Зачем окно вообще: сравнить два тайтла рядом, не теряя место в каталоге, — то,
 * ради чего в браузере открывают ссылку в новой вкладке.
 */
@Composable
fun DetachedTitleWindow(
    anime: Anime,
    repository: DesktopRepository,
    settings: AppSettings,
    onClose: () -> Unit,
) {
    val state = rememberWindowState(size = DpSize(920.dp, 760.dp))
    Window(
        state = state,
        onCloseRequest = onClose,
        title = anime.title,
    ) {
        AniBlazeTheme {
            Box(Modifier.fillMaxSize().background(Surface1)) {
                DetailScreen(
                    anime = anime,
                    isCinema = repository.isCinema(anime.id),
                    repository = repository,
                    settings = settings,
                    // Смотреть — только в главном окне: плеер в приложении один, и
                    // второй экземпляр дрался бы с ним за единственный поток команд
                    // libVLC. Нажатие уводит серию в главное окно.
                    onPlay = { segment -> DetachedTitles.playInMainWindow(anime, segment) },
                    // Похожие и другие сезоны открываются РЯДОМ, ещё одним окном:
                    // иначе «сравнить два тайтла» превращалось бы в блуждание внутри
                    // одного окна, ради чего его и не открывали.
                    onOpenRelated = { DetachedTitles.openTitle(it) },
                    // Страницы студии и режиссёра используют общую навигацию приложения,
                    // поэтому, как и плеер, открываются в поднятом главном окне.
                    onOpenStudio = { DetachedTitles.openStudioInMainWindow(it) },
                    onOpenPerson = { DetachedTitles.openPersonInMainWindow(it) },
                )
            }
        }
    }
}
