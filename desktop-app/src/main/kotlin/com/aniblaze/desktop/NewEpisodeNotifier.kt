package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.SystemTray
import java.awt.TrayIcon

/**
 * Watches followed titles (favourites + recent history) for new episodes and raises a
 * native Windows notification when one appears.
 *
 * Deliberately counts EPISODES AT THE SOURCE rather than reading air dates: a Russian
 * dub/sub lands roughly a day after the Japanese broadcast, so an air-date alert fires
 * while there is still nothing to watch. An episode showing up in the source listing
 * means it is actually playable now.
 */
class NewEpisodeNotifier(
    private val repository: DesktopRepository,
    private val settings: AppSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sweepGate = NotificationSweepGate()

    // Wired by Main once the Compose application scope exists: the tray icon's
    // "Открыть" / double-click shows the hidden window, "Выход" really quits.
    // The tray is what keeps the app alive after the window is closed to it.
    @Volatile var onOpenWindow: (() -> Unit)? = null
    @Volatile var onQuit: (() -> Unit)? = null

    /** Open a SPECIFIC title (clicking the "new episode" balloon). */
    @Volatile var onOpenTitle: ((Anime) -> Unit)? = null

    // AWT fires the same ActionListener for a balloon click and an icon
    // double-click, with no way to tell which balloon (or that it was a balloon at
    // all). Heuristic: an action shortly after a notification means "show me THAT
    // title"; a later one is just "open the app".
    @Volatile private var lastNotified: Anime? = null
    @Volatile private var lastNotifiedAt: Long = 0L

    private fun handleTrayAction() {
        val recent = lastNotified?.takeIf {
            System.currentTimeMillis() - lastNotifiedAt < NOTIFICATION_CLICK_WINDOW_MS
        }
        if (recent != null) {
            com.aniblaze.desktop.player.PlayerDiagnostics.log("notifier.openTitle", "id=${recent.id}")
            lastNotified = null
            onOpenTitle?.invoke(recent) ?: onOpenWindow?.invoke()
        } else {
            onOpenWindow?.invoke()
        }
    }

    /**
     * The tray icon IS the notification channel on Windows — no icon, no balloon.
     *
     * It used to be loaded from `/aniblaze-tray.png`, a resource this module has
     * never shipped: `getResource` returned null, `Toolkit.createImage(null)` threw,
     * the runCatching swallowed it and left this null — so no notification ever
     * appeared, however many new episodes came out. The image is drawn here instead,
     * so there is nothing left to fail to find.
     */
    private val trayIcon: TrayIcon? by lazy {
        if (!SystemTray.isSupported()) {
            com.aniblaze.desktop.player.PlayerDiagnostics.log("notifier.tray", "supported=false")
            return@lazy null
        }
        runCatching {
            val tray = SystemTray.getSystemTray()
            val icon = TrayIcon(trayImage(tray.trayIconSize.width.coerceIn(16, 64)), "AniBlaze")
                .apply { isImageAutoSize = true }
            // Balloon click / double-click → open the notified title or the window;
            // right-click menu → open / quit.
            icon.addActionListener { handleTrayAction() }
            icon.popupMenu = java.awt.PopupMenu().apply {
                add(java.awt.MenuItem("Открыть AniBlaze").apply { addActionListener { onOpenWindow?.invoke() } })
                addSeparator()
                add(java.awt.MenuItem("Выход").apply { addActionListener { onQuit?.invoke() } })
            }
            tray.add(icon)
            icon
        }.onFailure {
            com.aniblaze.desktop.player.PlayerDiagnostics.failure("notifier.tray.add", it)
        }.getOrNull()
    }

    /** Force the tray icon to exist NOW (app start) — close-to-tray needs it before
     *  the first notification sweep would have lazily created it. */
    fun ensureTray(): Boolean = trayIcon != null

    fun start() {
        scope.launch {
            // Let the app settle before the first sweep, then poll periodically.
            delay(FIRST_DELAY_MS)
            com.aniblaze.desktop.player.PlayerDiagnostics.log(
                "notifier.start",
                "tray=${trayIcon != null}; enabled=${settings.state.value.notifyNewEpisodes}; tracked=${settings.trackedTitles().size}",
            )
            while (true) {
                runCatching { sweep(reason = "poll") }
                    .onFailure { com.aniblaze.desktop.player.PlayerDiagnostics.failure("notifier.sweep.failed", it) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Fires a sample balloon so "уведомления не работают" can be checked in a click.
     *
     * Возвращает, что именно произошло, — иначе кнопка «Проверить» молчит одинаково
     * и когда трея нет, и когда Windows проглотила всплывашку.
     */
    fun notifyTest(): String {
        val icon = trayIcon
        if (icon == null) {
            com.aniblaze.desktop.player.PlayerDiagnostics.log("notifier.test", "tray=null")
            return "Системный трей недоступен — Windows не покажет всплывающие уведомления."
        }
        val sent = runCatching {
            icon.displayMessage("AniBlaze", "Уведомления работают.", TrayIcon.MessageType.INFO)
        }
        com.aniblaze.desktop.player.PlayerDiagnostics.log("notifier.test", "sent=${sent.isSuccess}")
        sent.exceptionOrNull()?.let {
            com.aniblaze.desktop.player.PlayerDiagnostics.failure("notifier.test.failed", it)
            return "Windows отказалась показать уведомление: ${it.message}"
        }
        return "Уведомление отправлено. Не появилось — проверьте «Параметры → Система → " +
            "Уведомления»: там же лежит переключатель «Не беспокоить»."
    }

    /** Прогнать обход прямо сейчас (кнопка в настройках), не дожидаясь получаса. */
    fun sweepNow(onDone: (String) -> Unit) {
        scope.launch {
            val found = runCatching { sweep(reason = "manual") }.getOrElse { error ->
                com.aniblaze.desktop.player.PlayerDiagnostics.failure("notifier.sweep.failed", error)
                onDone("Проверка сорвалась: ${error.message}")
                return@launch
            }
            onDone(
                if (found > 0) "Найдено новых серий: $found — уведомления отправлены."
                else "Новых серий нет: у всех отслеживаемых тайтлов серий столько же, сколько было.",
            )
        }
    }

    /** True when the tray accepted our icon, i.e. balloons can actually be shown. */
    fun isAvailable(): Boolean = trayIcon != null

    /** @return сколько тайтлов оказались с новой серией. */
    private suspend fun sweep(reason: String): Int = sweepGate.serial {
        sweepLocked(reason)
    }

    private suspend fun sweepLocked(reason: String): Int {
        if (!settings.state.value.notifyNewEpisodes) {
            com.aniblaze.desktop.player.PlayerDiagnostics.log("notifier.sweep.skip", "reason=$reason; enabled=false")
            return 0
        }
        val tracked = settings.trackedTitles()
        com.aniblaze.desktop.player.PlayerDiagnostics.log(
            "notifier.sweep.begin",
            "reason=$reason; tracked=${tracked.size}; tray=${trayIcon != null}",
        )
        var found = 0
        var checked = 0
        var failed = 0
        // Всё, что уже помечено новой серией, но о чём ещё не сообщали: серия,
        // найденная при открытии тайтла, или найденная до того, как всплывашки
        // научились доводиться до конца. О таком сообщаем в начале обхода.
        settings.unannouncedEpisodes().forEach { (id, count) ->
            val anime = tracked.firstOrNull { it.id == id } ?: return@forEach
            notify(anime, count)
            found++
        }
        for (anime in tracked) {
            // Cinema entries are movies/series handled elsewhere; only follow anime.
            if (repository.isCinema(anime.id)) continue
            // Одна упавшая проверка не должна обрывать весь обход: сеть моргает, а
            // остальные сорок семь тайтлов ни при чём.
            val count = runCatching {
                val episodes = repository.segments(anime.id)
                repository.observeAvailableEpisodes(anime, episodes)
                episodes.filter { it.playable }.maxOfOrNull { it.number } ?: 0
            }
                .onFailure {
                    failed++
                    com.aniblaze.desktop.player.PlayerDiagnostics.failure("notifier.title.failed", it)
                }
                .getOrDefault(0)
            if (count <= 0) continue
            checked++
            val previous = settings.episodeCount(anime.id)
            val grew = settings.recordEpisodeCount(anime.id, count)
            when {
                grew -> {
                    com.aniblaze.desktop.player.PlayerDiagnostics.log(
                        "notifier.grew",
                        "id=${anime.id}; was=$previous; now=$count",
                    )
                    notify(anime, count)
                    found++
                }
            }
            delay(BETWEEN_TITLES_MS) // keep the sweep gentle on the sources
        }
        com.aniblaze.desktop.player.PlayerDiagnostics.log(
            "notifier.sweep.end",
            "reason=$reason; checked=$checked; failed=$failed; notified=$found",
        )
        return found
    }

    private fun notify(anime: Anime, episode: Int) {
        // «Не напоминать» для тайтла: серия считается объявленной, чтобы не копилась.
        if (settings.isReleaseMuted(anime.id)) {
            settings.markAnnounced(anime.id, episode)
            com.aniblaze.desktop.player.PlayerDiagnostics.log("notifier.notify.muted", "id=${anime.id}; episode=$episode")
            return
        }
        val icon = trayIcon
        if (icon == null) {
            com.aniblaze.desktop.player.PlayerDiagnostics.log("notifier.notify.skip", "id=${anime.id}; tray=null")
            return
        }
        lastNotified = anime
        lastNotifiedAt = System.currentTimeMillis()
        runCatching {
            icon.displayMessage(
                "Новая серия",
                "${anime.title} · серия $episode",
                TrayIcon.MessageType.INFO,
            )
        }.onSuccess {
            // Отмечаем ПОСЛЕ показа: сорвавшуюся всплывашку покажем в следующий раз.
            settings.markAnnounced(anime.id, episode)
            com.aniblaze.desktop.player.PlayerDiagnostics.log("notifier.notify", "id=${anime.id}; episode=$episode")
        }.onFailure {
            com.aniblaze.desktop.player.PlayerDiagnostics.failure("notifier.notify.failed", it)
        }
    }

    private companion object {
        const val FIRST_DELAY_MS = 8_000L
        const val POLL_INTERVAL_MS = 30 * 60 * 1000L // every 30 minutes
        const val BETWEEN_TITLES_MS = 1_500L

        /** How long after a balloon a tray action still means "open that title". */
        const val NOTIFICATION_CLICK_WINDOW_MS = 10 * 60 * 1000L
    }
}

/**
 * Ручная проверка и фоновый таймер используют одну очередь. Без этого оба обхода
 * успевали прочитать одну и ту же ещё не объявленную серию и показывали два balloon.
 */
internal class NotificationSweepGate {
    private val mutex = Mutex()

    suspend fun <T> serial(block: suspend () -> T): T = mutex.withLock { block() }
}

/** The brand tile (ember→magenta with a play mark), drawn at the tray's own size —
 *  same look as [AniBlazeWindowIcon], but as an AWT image and with no asset file. */
private fun trayImage(size: Int): java.awt.Image {
    val image = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
    g.paint = java.awt.GradientPaint(
        0f, 0f, java.awt.Color(0xFF, 0x7A, 0x3D),
        size.toFloat(), size.toFloat(), java.awt.Color(0xFF, 0x3D, 0x9D),
    )
    val radius = (size * 0.48f).toInt()
    g.fillRoundRect(0, 0, size, size, radius, radius)
    g.color = java.awt.Color.WHITE
    val cx = size * 0.40f
    val half = size * 0.16f
    val width = size * 0.28f
    g.fillPolygon(
        intArrayOf(cx.toInt(), (cx + width).toInt(), cx.toInt()),
        intArrayOf((size / 2f - half).toInt(), (size / 2f).toInt(), (size / 2f + half).toInt()),
        3,
    )
    g.dispose()
    return image
}
