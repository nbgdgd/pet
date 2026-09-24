package com.aniblaze.desktop

import androidx.compose.runtime.mutableStateListOf
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.PersonCredit
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StudioCredit

/** A navigation request emitted by a detached title window and handled by App. */
internal sealed interface DetachedMainWindowRequest {
    data class Play(val anime: Anime, val segment: Segment) : DetachedMainWindowRequest
    data class OpenStudio(val studio: StudioCredit) : DetachedMainWindowRequest
    data class OpenPerson(val person: PersonCredit) : DetachedMainWindowRequest
}

/**
 * Small testable bridge between OS-level title windows and the single main window.
 * It deliberately owns no UI state: [App] installs the handler while it is alive.
 */
internal class DetachedMainWindowBridge {
    var handler: ((DetachedMainWindowRequest) -> Unit)? = null

    fun request(request: DetachedMainWindowRequest): Boolean {
        val target = handler ?: return false
        target(request)
        return true
    }
}

/**
 * Тайтлы, открытые в ОТДЕЛЬНЫХ окнах.
 *
 * Держатель один на процесс — по той же причине, что и у окошка «картинка в
 * картинке»: окна операционной системы создаются на самом верху, в `application`, а
 * команда «открой это в новом окне» приходит из карточки, лежащей на десять уровней
 * ниже. Тащить обработчик через все экраны сквозным параметром пришлось бы в каждый
 * из них поимённо, и любой новый экран про него бы забыл.
 *
 * Здесь же лежит и защита от дубля: второе «открыть в новом окне» на тот же тайтл
 * просто ничего не делает, вместо того чтобы плодить одинаковые окна.
 */
object DetachedTitles {

    /** Что сейчас открыто. Читается на верхнем уровне, меняется из карточек. */
    val open = mutableStateListOf<Anime>()

    fun openTitle(anime: Anime) {
        if (open.any { it.id == anime.id }) return
        // Потолок: горсть окон — это уже неудобно, а сотня открытых по ошибке
        // означала бы сотню живых загрузок постеров и описаний.
        if (open.size >= MAX_WINDOWS) open.removeAt(0)
        open.add(anime)
    }

    fun close(anime: Anime) {
        open.removeAll { it.id == anime.id }
    }

    /** Requests are consumed by the one main-window navigation host. */
    internal val mainWindowBridge = DetachedMainWindowBridge()

    fun playInMainWindow(anime: Anime, segment: Segment) =
        mainWindowBridge.request(DetachedMainWindowRequest.Play(anime, segment))

    fun openStudioInMainWindow(studio: StudioCredit) =
        mainWindowBridge.request(DetachedMainWindowRequest.OpenStudio(studio))

    fun openPersonInMainWindow(person: PersonCredit) =
        mainWindowBridge.request(DetachedMainWindowRequest.OpenPerson(person))

    private const val MAX_WINDOWS = 8
}
