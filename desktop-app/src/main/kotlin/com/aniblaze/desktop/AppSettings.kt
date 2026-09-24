package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class PersistedAnime(
    val id: String,
    val title: String,
    val poster: String,
    val year: Int = 0,
    val rating: Double = 0.0,
    val description: String = "",
    val status: String = "",
    val broadcast: Int = 0,
    // Для экрана статистики. Записи, сделанные до его появления, приходят без
    // жанров — экран дозаполняет их у источника один раз и складывает обратно.
    val genres: String = "",
    val studio: String = "",
    // Оценка MyAnimeList и распределение голосов — основа «Шлакометра». Приходят
    // не из каталога, а из Shikimori, поэтому у старых записей их нет: экран
    // статистики дозаполняет их тем же проходом, что и жанры.
    val malScore: Double = 0.0,
    val malVotes: Int = 0,
    val malLowVotes: Int = 0,
    // Популярность — вторая координата ранга на постере (см. TitleRank): оценка 4.8
    // от ста тысяч зрителей и от полусотни — разные вещи. Раньше эти поля терялись
    // при сохранении, и в избранном ранг считался по одной оценке.
    val favoritesCount: Int = 0,
    val watchingCount: Int = 0,
    // Шкала оценки — см. Anime.ratingMax. 0 = запись сделана до появления поля;
    // такие восстанавливаются по префиксу id (кино — десятибалльное, аниме — нет).
    val ratingMax: Double = 0.0,
    val ratingVotes: Int = 0,
    // Additive metadata; old saves remain unknown, never guessed as a 12-episode season.
    val episodesTotal: Int = 0,
    val episodesAvailable: Int = 0,
    val airingStatus: Int = 0,
    val contentType: String = "",
    val country: String = "",
)

/**
 * Шкала оценки у записи, сохранённой до появления [PersistedAnime.ratingMax].
 *
 * Угадывать по самому числу нельзя — 4.7 бывает и там, и там. Зато id говорит
 * однозначно: у «Кино» они с префиксом источника или вовсе ссылкой на сайт, у
 * Anixart — голые цифры.
 */
private fun legacyRatingMax(id: String): Double =
    if (id.startsWith("tmdb:") || id.startsWith("tmdbtv:") || id.startsWith("http")) 10.0 else 5.0

/**
 * Личная оценка тайтла — самый сильный след из всех, что есть у рекомендаций.
 *
 * Карточка хранится ЦЕЛИКОМ, а не одним идентификатором. Причина ровно та же, по
 * которой сюда пришлось добавлять карточку к отметке «просмотрено»: [Recommender]
 * умеет читать жанры, студию и год только с карточки, и набор из одних id для него
 * невидим. Оценка без карточки была бы числом, которое никуда не влияет.
 *
 * Времени оценки хватает и на свежесть: вкус со временем смещается, и позапрошлогодняя
 * пятёрка должна весить меньше сегодняшней.
 */
@Serializable
data class RatedTitle(
    val anime: PersistedAnime,
    /** 1..5 звёзд. Нуля не бывает: снятая оценка удаляет запись целиком. */
    val score: Int,
    /** Когда поставили, epoch ms. 0 = запись из сборки, где времени ещё не писали. */
    val at: Long = 0L,
)

@Serializable
data class FavoriteRemoval(val anime: PersistedAnime, val at: Long)

@Serializable
data class ProgressEntry(
    val anime: PersistedAnime,
    val segment: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    /** Confirmed forward movement of the native media clock. Seeks add zero. */
    val verifiedPlaybackMs: Long = 0L,
) {
    val fraction: Float get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
}

/**
 * Где пользователь остановился в тайтле — то, что рисуется прямо на постере.
 *
 * Собирается из трёх разных следов, которые поодиночке врут:
 *  • [PersistedState.progress] — позиция в БРОШЕННОЙ серии; досмотренная свою запись
 *    удаляет, поэтому по одному прогрессу «досмотрено» неотличимо от «не начато»;
 *  • [PersistedState.watched] — отметки досмотренных серий, но без общего числа серий
 *    по ним не понять, конец это или середина;
 *  • [PersistedState.episodeCounts] — сколько серий было у тайтла в последний раз.
 */
data class TitleWatch(
    /** Серия, на которой остановились (1-based). */
    val episode: Int,
    /** Сколько серий известно всего. 0 = неизвестно (или фильм). */
    val total: Int,
    /** Досмотрено всё, что известно. */
    val finished: Boolean,
    /** Доля брошенной серии, 0..1. 0 = серия досмотрена или ещё не начата. */
    val fraction: Float,
    /** Actual completed episodes, not the furthest episode number. */
    val completedCount: Int = 0,
    val currentAlreadyCompleted: Boolean = false,
) {
    /**
     * Доля тайтла целиком — для полоски под подписью.
     *
     * [episode] значит разное в двух случаях, и полоска обязана их различать:
     * при [fraction] больше нуля это БРОШЕННАЯ серия (позади `episode - 1` целых
     * плюс кусок текущей), а при нуле — последняя ДОСМОТРЕННАЯ (позади ровно
     * `episode`). Без этой развилки досмотренная вторая серия из двенадцати
     * показывала бы одну двенадцатую вместо двух.
     */
    val overall: Float
        get() = when {
            finished -> 1f
            total > 1 -> {
                val behind = completedCount + if (currentAlreadyCompleted) 0f else fraction
                (behind / total).coerceIn(0f, 1f)
            }
            else -> fraction
        }
}

@Serializable
data class PlayerPref(
    val voiceManual: Boolean = true, // legacy selections are preserved conservatively
    val voice: Int = -1,       // anime translationId last watched (-1 = none saved)
    val quality: String = "",  // last quality label, e.g. "1080p"
    val dub: String = "",      // cinema VLC audio-track label last chosen
    // Серия, которую в этом тайтле открывали последней. Отдельно от progress,
    // потому что тот живёт только у НЕДОсмотренных серий: досмотрел до конца —
    // запись удаляется, и «Продолжить» откатывалось на первую серию.
    val lastSegment: Int = 0,
)

@Serializable
data class PersistedState(
    val favorites: List<PersistedAnime> = emptyList(),
    val history: List<PersistedAnime> = emptyList(),
    val progress: List<ProgressEntry> = emptyList(),
    /** Личные оценки тайтлов, свежие сверху. См. [RatedTitle]. */
    val ratings: List<RatedTitle> = emptyList(),
    val favoriteRemovals: List<FavoriteRemoval> = emptyList(),
    val gridColumns: Int = 0,
    val voicePriority: Boolean = false,
    // Active "Кино" catalog source (CinemaSource.key). Switching restarts the app.
    val cinemaSource: String = "lordfilm",
    // Lampa plugin: when on, cinema playback resolves through the plugin's balancers
    // (studio dubs, OK.ru streams) instead of the site's own player.
    val lampaEnabled: Boolean = false,
    val lampaPluginUrl: String = "https://nb557.github.io/plugins/online_mod.js",
    val lampaBalancer: String = "rezka2",
    // Auto-advance to the next episode when the current one finishes (anime only —
    // cinema is a single segment). Off = stop at the end of each episode.
    val autoplayNext: Boolean = true,
    // After the LAST available episode: hop to a random anime from «В тренде» /
    // «Сейчас смотрят» / fully random. Fires ONLY when there is no next episode —
    // ordinary episode autoplay is never touched. Toggled from the player HUD.
    val autoSwitchRandom: Boolean = false,
    // Крестик прячет приложение в трей (проверка новых серий продолжает работать в
    // фоне); выход — через меню иконки в трее. false = крестик завершает процесс.
    val closeToTray: Boolean = true,
    // Масштаб шрифтов всего интерфейса (1.0 = стандарт). Меняет только текст (sp);
    // постеры, сетки и отступы не трогает. Применяется мгновенно, без перезапуска.
    val fontScale: Float = 1.0f,
    // Размер текста комментариев, sp — отдельно от общего масштаба.
    val commentFontSize: Int = 13,
    // Пропускать опенинг автоматически, когда тайминги известны (AniLibria/AniSkip).
    // false = на старте опенинга всплывает HUD с кнопкой «Пропустить опенинг».
    val autoSkipOpening: Boolean = false,
    // То же для эндинга. Прыжок идёт на КОНЕЦ интервала, а не сразу к следующей
    // серии: после титров у части тайтлов есть сцена, и проглатывать её нельзя.
    // Когда эндинг и есть конец серии, оставшиеся секунды доигрывают штатно и
    // срабатывает обычный автопереход [autoplayNext] — отдельного пути к следующей
    // серии тут нет намеренно.
    val autoSkipEnding: Boolean = false,
    /** Opt-in: background analysis reads parts of the media stream using FFmpeg. */
    val autoDetectTimings: Boolean = false,
    // Как выводится картинка.
    //
    // true (по умолчанию) — кадры рисует Compose. Панель ложится ПОВЕРХ видео,
    //         поэтому картинка занимает ВСЁ окно: под управление ничего не
    //         отрезается, размер не скачет при открытии меню, а при перемотке на
    //         экране остаётся последний кадр вместо черноты.
    // false — рисует сама libVLC в нативное окно. Дешевле, но это heavyweight-окно:
    //         Windows рисует его поверх любой Compose-графики, наложение
    //         невозможно в принципе, и под панель приходится отрезать полосу.
    //
    // ЗАМЕРЕНО отдельным стендом на 1920x1088: libVLC отдаёт кадры в память 30.0/с,
    // конвертация в растр Skia держит те же 30.0/с без потерь. Первая попытка всё
    // равно вышла медленной — из-за FilterQuality.Medium на отрисовке, то есть
    // мипмапов на каждый кадр. С билинейной фильтрацией эта цена снята.
    val playerComposeVideo: Boolean = true,
    // «Закрепить панель управления» — кнопка-булавка в плеере. Панель перестаёт
    // прятаться по таймеру и остаётся на экране.
    val hudPinned: Boolean = false,
    // Улучшение картинки (VideoEnhance.Level.key): "off" / "light" / "strong".
    // Compose применяет CAS к слою кадра; нативный вывод применяет VLC sharpen.
    val videoEnhance: String = "off",
    // «Картинка в картинке»: свернул окно во время просмотра — видео продолжается в
    // маленьком окошке поверх остальных. Требует режима наложения: в нативном выводе
    // кадры до приложения не доходят, и второму окну рисовать нечего.
    val pictureInPicture: Boolean = true,
    /** Где стояло окошко «картинка в картинке»: «x,y,w,h» в dp; пусто — по умолчанию. */
    val pipBounds: String = "",
    /** Свои горячие клавиши плеера: действие (PlayerAction.key) → код клавиши. */
    val hotkeys: Map<String, Long> = emptyMap(),
    // Комментарии зрителей поверх видео (см. DanmakuOverlay). Берутся из обсуждения
    // ЭТОГО тайтла у источника; таймкодов у них нет, поэтому показываются изредка и
    // только по краям кадра.
    val commentsOverlay: Boolean = true,
    /** Как часто всплывают: "rare" | "normal" | "often". */
    val commentsRate: String = "normal",
    /** Непрозрачность плашки, 0.2..1.0. */
    val commentsOpacity: Float = 0.85f,
    /** Размер текста, sp. */
    val commentsFontSize: Int = 14,
    /** true — плывут поперёк кадра, false — просто всплывают на месте. */
    val commentsMoving: Boolean = true,
    /**
     * Только новые: сначала недавно написанные, а не лучшие по оценкам.
     *
     * По умолчанию выключено — у обсуждения сверху лежат самые заплюсованные реплики,
     * и они, как правило, интереснее свежего потока.
     */
    val commentsFreshOnly: Boolean = false,
    /**
     * Симулятор живого чата рядом с плеером (см. ChatEngine).
     *
     * Не «ещё один оверлей комментариев»: реплики поверх кадра — это НАСТОЯЩЕЕ
     * обсуждение тайтла, а чат изображает зал, который смотрит серию вместе с тобой.
     * Тексты он берёт и оттуда тоже, но большая часть — реакции виртуальных зрителей
     * на то, что происходит в кадре.
     */
    val chatEnabled: Boolean = true,
    /** Сколько виртуальных зрителей «в зале»: влияет на подпись и на темп ленты. */
    val chatViewers: Int = 150,
    /** Плотность ленты: "quiet" | "normal" | "lively" | "storm". */
    val chatIntensity: String = "normal",
    /** Multiplier of message frequency, independent from video playback speed. */
    val chatSpeed: Float = 1f,
    /**
     * «Всегда тихо»: чат идёт ровным темпом и не разгоняется на резких моментах.
     *
     * Настроение сцены при этом определяется по-прежнему и по-прежнему решает, ЧТО
     * пишут; отключается только разгон — ускорение ленты и всплески сообщений.
     */
    val chatAlwaysQuiet: Boolean = false,
    /**
     * «Только реальные зрители»: показывать в чате ТОЛЬКО настоящие реплики из
     * обсуждения тайтла, полностью скрыв выдуманных зрителей.
     *
     * Лента при этом становится заметно реже — настоящих реплик на серию десятки, а
     * не сотни, — и это ожидаемо: режим для тех, кому важно, что каждое слово
     * написал живой человек.
     */
    val chatRealOnly: Boolean = false,
    /** Подмешивать в чат обсуждение того же тайтла с YummyAnime (см. DesktopRepository.commentFeed). */
    val chatYummyComments: Boolean = true,
    /** Настоящие реплики в чате идут от самых заплюсованных к менее, а не вперемешку. */
    val chatPopularFirst: Boolean = false,
    /**
     * Только реплики, помеченные ИМЕННО текущей серией. Без пометки серии (Yummy, старые
     * обсуждения) — не показываются вовсе; по умолчанию выключено, иначе чат у
     * большинства тайтлов пустеет.
     */
    val chatEpisodeOnly: Boolean = false,
    /** Подгружать ветки ответов под популярными записями — в чате видно «@кому». */
    val chatReplies: Boolean = true,

    // --- Питомец-помощник (см. com.aniblaze.desktop.pet) ---
    val petEnabled: Boolean = true,
    /** id персонажа: "claude" | "eigenblob". */
    val petCharacter: String = "claude",
    /** Масштаб питомца, 0.7..1.6. */
    val petScale: Float = 1f,
    /** Показывать поверх плеера. */
    val petInPlayer: Boolean = true,
    /** Положение в плеере, доля ширины/высоты окна (левый верхний угол питомца). */
    val petPlayerX: Float = 0.94f,
    val petPlayerY: Float = 0.12f,
    /** Null keeps the previous position until fullscreen is arranged separately. */
    val petFullscreenX: Float? = null,
    val petFullscreenY: Float? = null,
    /** Не чаще одной необязательной реплики в столько минут (важное идёт вне очереди). */
    val petChatterMinutes: Int = 5,
    val petSpeechEnabled: Boolean = true,
    /** Короткие звуки на события питомца (предложение, просьба, новая серия); громкость 0..1. */
    val petSoundsEnabled: Boolean = true,
    val petSoundVolume: Float = 0.5f,
    val petQuietWatching: Boolean = true,
    val drizzActivity: String = "normal",
    val petDesktopX: Float = 1f,
    val petDesktopY: Float = 1f,
    /** Delivered companion events, independent of tray notifications. Bounded on write. */
    val petAnnouncedEvents: Set<String> = emptySet(),
    /**
     * Затемнять обложки ПОЛНОСТЬЮ просмотренных тайтлов во всех каталогах.
     *
     * Только полностью: тайтл, у которого просмотрена часть серий, гасить нельзя —
     * он как раз тот, к которому нужно вернуться. Признак берётся из
     * [TitleWatch.finished], а не из «есть хоть одна досмотренная серия».
     */
    val dimWatched: Boolean = true,
    /** Размер текста в боковой панели, sp. */
    val chatFontSize: Int = 13,
    /** Ширина боковой панели, dp. */
    val chatPanelWidth: Int = 330,
    /** Docked chat side; old saves stay on the right. */
    val chatSide: String = "right",
    /** true — вместо панели сообщения идут прозрачно поверх кадра. */
    val chatOverlayMode: Boolean = false,
    /** Угол прозрачного чата: "topLeft" | "topRight" | "bottomLeft" | "bottomRight". */
    val chatOverlayPosition: String = "bottomLeft",
    /** Сколько сообщений видно одновременно в прозрачном режиме. */
    val chatOverlayLines: Int = 8,
    val chatOverlayOpacity: Float = 0.85f,
    val chatOverlayFontSize: Int = 13,
    // Экран статистики. Время копится ПРИРОСТОМ позиции, а не длительностью серий:
    // отмотанное и брошенное на середине не должно попадать в «часы у экрана».
    val watchedMs: Long = 0,
    // То же время по календарным дням ("2026-08-04" → мс) — из него считаются
    // серии дней подряд и самый долгий день.
    val watchedByDay: Map<String, Long> = emptyMap(),
    /**
     * Сколько РЕАЛЬНО отсмотрено по каждому тайтлу, мс. Копится из того же
     * измеренного дельта-времени, что и [watchedMs] (паузы, буферизация и перемотки
     * в него не входят), но в разрезе тайтла — это нужно питомцу. Ключ — id карточки,
     * уже приведённый к каноническому (см. [canonical]).
     */
    val watchedMsByTitle: Map<String, Long> = emptyMap(),
    /** Measured playback time by local hour. Missing in legacy saves means unknown. */
    val watchedByHour: Map<Int, Long> = emptyMap(),
    // Геометрия окна между запусками. Без неё окно каждый раз открывалось 1280x800
    // в углу экрана, и развёрнутое состояние не переживало перезапуск.
    // 0 = ещё не сохранено, взять размер по умолчанию.
    val windowWidth: Int = 0,
    val windowHeight: Int = 0,
    val windowX: Int = Int.MIN_VALUE,
    val windowY: Int = Int.MIN_VALUE,
    val windowMaximized: Boolean = false,
    // Default to the two fast, reliable sources. Kodik's catalog API (kodikapi.com)
    // is dead and Shikimori's GraphQL often 522s — both only add multi-second
    // stalls to catalog merges — so they're off by default (toggle in Settings).
    // AnimeVost is deliberately NOT here: it is a *playback* alternative (progressive
    // MP4, instant seeking), not a catalog one. Merging it into every Home row added a
    // third source to ~16 parallel section loads and starved them — rows came back
    // empty and episode lists flickered. Pick it explicitly (source switcher / player)
    // and the whole app runs on it; leave it off and it never touches the merge.
    val enabledSources: Set<String> = setOf("Anixart", "AniLibria"),
    // Global anime source. "Все" = merge the enabled sources (default). A specific
    // source name = the whole app (Home categories, search, player) uses ONLY that
    // source — the anime equivalent of the "Кино" source switcher.
    val primarySource: String = "Все",
    // Global player volume. Kept outside per-title prefs so every new episode,
    // title and app restart continues at the user's last chosen level.
    val playerVolume: Int = 100,
    /** Пресет эквалайзера libVLC («flat», «headphones», «soft»…); пусто — выключен. */
    val audioPreset: String = "",
    // Per-title last-watched voice / quality / dub, keyed by the content id (without
    // the ":t" voice suffix) — so reopening a title restores what you were watching.
    val playerPrefs: Map<String, PlayerPref> = emptyMap(),
    // Episodes watched to the end, as "<contentId>#<segment>". Progress entries are
    // DELETED once an episode finishes (they only track resume points), so without
    // this set a finished episode looked exactly like one never started.
    val watched: Set<String> = emptySet(),
    /** An explicit whole-title mark, unlike completing a single episode. */
    val completedTitles: Set<String> = emptySet(),
    /**
     * КОГДА серия была досмотрена: `"<contentId>#<segment>"` → epoch ms.
     *
     * Без этой карты вопрос «что человек смотрел ПОСЛЕДНИМ» был неразрешим в
     * принципе, и отсюда росла жалоба: 23-я серия брошена на 10 %, потом досмотрены
     * 25-я и 28-я, а «Продолжить» упорно звало обратно на 23-ю. Отметка о просмотре
     * была бесстрастным множеством строк без единой метки времени, поэтому сравнивать
     * её с [ProgressEntry.updatedAt] было НЕ С ЧЕМ, и брошенная серия побеждала всегда
     * — независимо от того, сколько всего случилось после неё.
     *
     * Ключ тот же, что у [watched]; записи живут и умирают вместе с ним.
     */
    val watchedAt: Map<String, Long> = emptyMap(),
    /**
     * Проставлены ли метки времени существующим отметкам о просмотре (разовая правка).
     *
     * У всех, кто пользовался приложением до появления [watchedAt], отметки лежат без
     * времени. Что было раньше — брошенная 23-я или досмотренная 28-я — из таких
     * данных не узнать никак, и разбор этого случая описан у [backfillWatchedAt].
     */
    val watchedAtBackfilled: Boolean = false,
    // Episode count last seen per tracked title (favourites + history). A rise means a
    // new episode became PLAYABLE — counted at the source, so it fires when the dub is
    // actually up, not on the Japanese air date (subs/dubs land ~a day later).
    val episodeCounts: Map<String, Int> = emptyMap(),
    // Titles with a new episode since it was last opened — drives the "Новые серии"
    // Home row. Cleared for a title once the user opens it.
    val newEpisodes: Map<String, Int> = emptyMap(),
    /** Legacy undated flags are not evidence of a recent release. */
    val newEpisodeAt: Map<String, Long> = emptyMap(),
    // Про какую серию всплывашка УЖЕ показывалась, как "<contentId>#<count>".
    // Без этого пометка «новая серия» и сама всплывашка жили порознь: серия,
    // найденная при открытии тайтла или на первом обходе, поднимала пометку, но о
    // ней никто не сообщал, — а всё, о чём сообщили, сообщалось бы снова каждые
    // полчаса до конца дней.
    val announcedEpisodes: Set<String> = emptySet(),
    val notifyNewEpisodes: Boolean = true,
    // Playback engine: "direct" = the current path (source's own stream, usually HLS),
    // "balancer" = play the balancer's stream (Kodik & co, routed through the proxy).
    // Balancers serve from their own CDN, which is why seeking there doesn't stall.
    val playbackEngine: String = "direct",
    // One-shot flag: an earlier build shipped AnimeVost switched ON inside the catalog
    // merge, which starved the Home rows. Existing installs already have it saved, so
    // changing the default alone would not help them — it is removed once on load.
    val vostMergeMigrated: Boolean = false,
    /** «Посмотрел — не понравилось»: id тайтлов, которые больше не показываем. */
    val disliked: Set<String> = emptySet(),
    /** «Не показывать 30 дней»: id тайтла → до какого момента (мс) его прячем. */
    val snoozedUntil: Map<String, Long> = emptyMap(),
    /** «Не напоминать»: тайтлы, о новых сериях которых не сообщаем ни в трее, ни питомцем. */
    val releaseMuted: Set<String> = emptySet(),
    /** «Позже»: событие релиза («new:id:серия») → когда напомнить снова (мс). */
    val releaseLater: Map<String, Long> = emptyMap(),
    /** Последний фильтр каталога аниме — восстанавливается при запуске. */
    val lastAnimeFilter: PersistedFilter? = null,
    /** Сколько раз включали каждое условие фильтра («tag:fantasy», «year:2026»…). */
    val filterUsage: Map<String, Int> = emptyMap(),
    /** Сохранённые наборы фильтров («Вечернее фэнтези»), по разделам. */
    val filterPresets: List<FilterPreset> = emptyList(),
    /** Тема оформления (см. ui.AppTheme) и цвет акцента. */
    val theme: String = "classic",
    val accent: String = "orange",
)

/** Именованный набор условий фильтра; scope — раздел («anime» / «cinema»). */
@Serializable
data class FilterPreset(val name: String, val scope: String = "anime", val filter: PersistedFilter)

/**
 * Фильтр каталога в сохраняемом виде. Перечисления пишутся именами, чтобы старая
 * запись не ломала запуск: незнакомое имя превращается в «не задано».
 */
@Serializable
data class PersistedFilter(
    val tags: List<String> = emptyList(),
    val yearFrom: Int = 0,
    val yearTo: Int = 0,
    val status: String = "",
    val ageRating: String = "",
    val country: String = "",
    val episodes: String = "",
    val contentType: String = "",
    val minRating: Double = 0.0,
    val hideWatched: Boolean = false,
    val dubbing: String = "",
    val sourceMaterial: String = "",
    val hiddenGems: Boolean = false,
    val protagonist: String = "",
    val sort: String = "",
) {
    companion object {
        fun of(f: com.aniblaze.aggregator.model.CatalogFilter) = PersistedFilter(
            tags = f.tags.toList(), yearFrom = f.yearFrom, yearTo = f.yearTo,
            status = f.status?.name.orEmpty(), ageRating = f.ageRating?.name.orEmpty(), country = f.country,
            episodes = f.episodes?.name.orEmpty(), contentType = f.contentType?.name.orEmpty(),
            minRating = f.minRating, hideWatched = f.hideWatched, dubbing = f.dubbing,
            sourceMaterial = f.sourceMaterial, hiddenGems = f.hiddenGems, protagonist = f.protagonist, sort = f.sort.name,
        )
    }

    fun toFilter(): com.aniblaze.aggregator.model.CatalogFilter {
        return com.aniblaze.aggregator.model.CatalogFilter(
            tags = tags.toSet(), yearFrom = yearFrom, yearTo = yearTo,
            status = parse<com.aniblaze.aggregator.model.TitleStatus>(status),
            ageRating = parse<com.aniblaze.aggregator.model.AgeRating>(ageRating),
            country = country,
            episodes = parse<com.aniblaze.aggregator.model.EpisodeRange>(episodes),
            contentType = parse<com.aniblaze.aggregator.model.ContentType>(contentType),
            minRating = minRating, hideWatched = hideWatched, dubbing = dubbing,
            sourceMaterial = sourceMaterial, hiddenGems = hiddenGems, protagonist = protagonist,
            sort = parse<com.aniblaze.aggregator.model.CatalogSort>(sort) ?: com.aniblaze.aggregator.model.CatalogSort.POPULAR,
        )
    }
}

/** Имя перечисления → значение; пусто или незнакомое имя → null. */
private inline fun <reified E : Enum<E>> parse(name: String): E? =
    if (name.isBlank()) null else enumValues<E>().firstOrNull { it.name == name }

internal fun Anime.toPersisted() =
    PersistedAnime(
        id, title, poster, year, rating, description, status, broadcast, genres, studio,
        malScore = malScore, malVotes = malVotes, malLowVotes = malLowVotes,
        favoritesCount = favoritesCount, watchingCount = watchingCount,
        ratingMax = ratingMax, ratingVotes = ratingVotes,
        episodesTotal = episodesTotal, episodesAvailable = episodesAvailable,
        airingStatus = airingStatus, contentType = contentType, country = country,
    )
internal fun PersistedAnime.toAnime() = Anime(
    id = id, title = title, poster = poster, year = year, rating = rating,
    description = description, status = status, broadcast = broadcast,
    genres = genres, studio = studio,
    malScore = malScore, malVotes = malVotes, malLowVotes = malLowVotes,
    favoritesCount = favoritesCount, watchingCount = watchingCount,
    ratingMax = if (ratingMax > 0.0) ratingMax else legacyRatingMax(id),
    ratingVotes = ratingVotes,
    episodesTotal = episodesTotal, episodesAvailable = episodesAvailable,
    airingStatus = airingStatus, contentType = contentType, country = country,
)

/**
 * Потолок списка «Продолжить просмотр».
 *
 * Число взято по замеру живого state.json, а не на глаз: там 60 записей прогресса
 * весили 39 957 байт — 666 байт на запись, потому что каждая тянет за собой ВЕСЬ
 * [PersistedAnime] (описание, постер, жанры), а не ссылку на тайтл. 400 × 666 ≈ 266 КБ.
 *
 * Ограничение тут не диск, а цена ОДНОЙ перезаписи: файл сохраняется целиком, с
 * prettyPrint, каждые ~5 секунд просмотра. Сейчас он весит 126 КБ; с этим потолком
 * в худшем случае вырастет до ~350 КБ, то есть перезапись дорожает втрое, а не в
 * восемьдесят раз, как было бы с «поставим 5000».
 *
 * Старое значение 60 было бедой: на живом файле список стоял РОВНО на 60, то есть
 * заполнен под завязку, и каждая новая начатая серия молча выбрасывала самую старую
 * точку просмотра. Одна серия — одна запись, так что 400 это ~33 начатых и брошенных
 * сезона по 12 серий; досмотренные (>95 %) свою запись освобождают сами.
 */
internal const val PROGRESS_LIMIT = 400

/**
 * Обрезать список «Продолжить просмотр» до [PROGRESS_LIMIT]. Отдельно от [AppSettings]
 * по той же причине, что и [buildWatchIndex]: проверяется тестом без файла на диске.
 * Список хранится «самое свежее первым», поэтому режется хвост.
 */
internal fun capProgress(entries: List<ProgressEntry>): List<ProgressEntry> =
    entries.take(PROGRESS_LIMIT)

/** Ключ отметки о просмотре одной серии. Один на весь файл — форматов быть не должно двух. */
internal fun watchedKey(contentId: String, segment: Int) = "$contentId#$segment"

/**
 * Когда этот тайтл смотрели В ПОСЛЕДНИЙ РАЗ ДО КОНЦА. 0 — ни одной досмотренной серии.
 *
 * Это вторая половина ответа на вопрос «что человек делал позже»: первая — время у
 * записи о брошенной серии ([ProgressEntry.updatedAt]). Пока сравнивать было не с чем,
 * брошенная серия побеждала всегда, и «Продолжить» звало на 23-ю серию у человека,
 * который после неё досмотрел 25-ю и 28-ю.
 */
internal fun newestWatchedAt(state: PersistedState, contentId: String): Long {
    val prefix = "$contentId#"
    var newest = 0L
    for ((key, at) in state.watchedAt) {
        if (key.startsWith(prefix) && at > newest) newest = at
    }
    return newest
}

/**
 * С какой серии продолжать. Отдельно от [AppSettings] — чтобы проверялось тестом, без
 * файла на диске; сам метод только подставляет текущее состояние.
 */
internal fun resumeSegmentOf(
    state: PersistedState,
    contentId: String,
    prefKey: String,
    segments: List<Int>,
    playable: (Int) -> Boolean,
): Int? {
    if (segments.isEmpty()) return null
    // 1. Прерванная серия — самая свежая по времени, а не самая ранняя по номеру.
    //
    // И только если после неё тайтл не смотрели до конца. Этой оговорки тут не было, и
    // шаг 1 выигрывал БЕЗУСЛОВНО: любая уцелевшая запись о прогрессе била всё
    // остальное, сколько бы серий человек ни досмотрел позже. В интерфейсе это
    // выглядело так — 23-я серия брошена на 10 %, 25-я и 28-я просмотрены, а
    // «Продолжить · Серия 23». Сравнивать было НЕ С ЧЕМ: отметки о просмотре хранились
    // множеством строк без единой метки времени (см. [PersistedState.watchedAt]).
    val watchedAt = newestWatchedAt(state, contentId)
    state.progress
        .filter { it.anime.id == contentId && it.fraction < 0.95f && it.segment in segments }
        .filterNot { resumeOutdated(it, watchedAt) }
        .maxByOrNull { it.updatedAt }
        ?.let { if (playable(it.segment)) return it.segment }
    val watched = segments.filter { watchedKey(contentId, it) in state.watched }
    // 2. Открывали, но ни досмотреть, ни набрать порог прогресса не успели.
    val last = state.playerPrefs[prefKey]?.lastSegment ?: 0
    if (last in segments && last !in watched && playable(last)) return last
    // 3. Досмотрели — предлагаем следующую НЕПРОСМОТРЕННУЮ.
    //
    // Отсчёт от той серии, что смотрели ПОСЛЕДНЕЙ ПО ВРЕМЕНИ, а не от самой дальней по
    // номеру. Разовый прыжок вперёд иначе забирает «Продолжить» себе навсегда: в
    // настоящем состоянии 24.08 по «Наруто» просмотрены 141…150 подряд, а 23:11 разово
    // отмечена 220-я — и предложение упиралось в 220-ю, хотя человеку нужна 151-я.
    if (watched.isNotEmpty()) {
        val watchedSet = watched.toSet()
        fun nextUnwatchedAfter(after: Int): Int? =
            segments.filter { it > after && it !in watchedSet && playable(it) }.minOrNull()
        // Самая свежая по отметке; при равном (или отсутствующем) времени — старших
        // номеров, чтобы порядок не зависел от случайностей перебора.
        val recent = watched.maxWithOrNull(
            compareBy({ state.watchedAt[watchedKey(contentId, it)] ?: 0L }, { it }),
        )
        if (recent != null) {
            nextUnwatchedAfter(recent)?.let { return it }
            // За последней просмотренной смотреть нечего: это либо финал, либо тот
            // самый разовый прыжок вперёд. Берём РУБЕЖ — самую дальнюю просмотренную,
            // за которой ещё осталось непросмотренное.
            watched.filter { nextUnwatchedAfter(it) != null }.maxOrNull()
                ?.let { frontier -> nextUnwatchedAfter(frontier)?.let { return it } }
            // Непросмотренного не осталось вовсе: открываем последнюю, а не первую.
            if (playable(recent)) return recent
        }
    }
    return segments.firstOrNull { playable(it) }
}

/**
 * Оставлять ли точку возобновления после этого сохранения позиции.
 *
 * Три отказа, и каждый оплачен своей жалобой:
 *
 *  * меньше восьми секунд — случайное открытие, следа не оставляет;
 *  * от 90 % — серия засчитана просмотренной, а у просмотренной серии точки
 *    возобновления не бывает. Порог тут был 95 %, и в зазоре 90–94 % серия
 *    одновременно значилась просмотренной и брошенной: отсюда «Остановились: 15 серия»
 *    на досмотренной серии;
 *  * от 80 % у УЖЕ просмотренной — это хвост запоздалого автосохранения. Плеер пишет
 *    позицию каждые две секунды, и последние записи законно приходят после того, как
 *    серия засчитана, — а то и после того, как человек отметил другие серии. Такая
 *    запись заново создавала точку возобновления у законченной серии и откатывала
 *    «Продолжить» назад. Настоящий пересмотр начинается с начала и до 80 % проходит
 *    как обычно.
 */
internal fun keepResumePoint(positionMs: Long, durationMs: Long, alreadyWatched: Boolean): Boolean {
    if (positionMs <= 8_000) return false
    if (durationMs <= 0) return true
    val fraction = positionMs.toFloat() / durationMs
    if (fraction >= 0.90f) return false
    return !(alreadyWatched && fraction >= 0.80f)
}

/**
 * Removes late end-of-playback checkpoints left on episodes already marked watched.
 *
 * A replay below 80% is a real resume point and must survive. At 80% and above the
 * normal write path deliberately suppresses the point, so such an overlap can only
 * be stale state produced by the old callback ordering.
 */
internal fun removeStaleWatchedResumePoints(state: PersistedState): PersistedState {
    val progress = state.progress.filterNot { entry ->
        entry.durationMs > 0L && entry.fraction >= 0.80f &&
            watchedKey(entry.anime.id, entry.segment) in state.watched
    }
    return if (progress.size == state.progress.size) state else state.copy(progress = progress)
}

/**
 * The single state transition for a playback checkpoint.
 *
 * Button resolution, episode rows and history all read [PersistedState], so the
 * checkpoint must atomically update position, completion and watch-time in that one
 * state. Keeping this pure also lets restart and cross-title isolation be tested
 * without touching the user's real settings file.
 */
internal fun applyEpisodeProgress(
    state: PersistedState,
    card: PersistedAnime,
    segment: Int,
    positionMs: Long,
    durationMs: Long,
    now: Long,
    day: String,
    measuredWatchMs: Long? = null,
): PersistedState {
    val previous = state.progress.firstOrNull { it.anime.id == card.id && it.segment == segment }
    // Count only plausible forward playback between adjacent checkpoints. A seek or
    // reopening at another position must not turn into fabricated watch time.
    val advanced = positionMs - (previous?.positionMs ?: positionMs)
    val elapsed = previous?.let { (now - it.updatedAt).coerceIn(0, 60_000) } ?: 0L
    val watchedDelta = measuredWatchMs?.coerceIn(0, 60_000)
        ?: if (advanced in 1..60_000) minOf(advanced, elapsed) else 0L
    val others = state.progress.filterNot { it.anime.id == card.id && it.segment == segment }
    val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val key = watchedKey(card.id, segment)
    // Position alone is not evidence of watching. Dragging straight to the end used
    // to mark an episode on the next poll. The real player supplies measured watch
    // time, and the native media clock must have moved by a matching amount. Legacy
    // callers without measured samples retain the original 90% behaviour.
    val verifiedDelta = if (measuredWatchMs == null) 0L else if (
        watchedDelta > 0 && advanced > 0 && advanced <= watchedDelta * 4 + 2_000
    ) advanced else 0L
    val verifiedPlayback = ((previous?.verifiedPlaybackMs ?: 0L) + verifiedDelta)
        .coerceAtMost(durationMs.coerceAtLeast(0L))
    val evidenceNeeded = minOf(120_000L, maxOf(15_000L, durationMs / 10))
    val finishing = durationMs > 0 && fraction >= 0.90f &&
        (measuredWatchMs == null || verifiedPlayback >= evidenceNeeded)
    val alreadyWatched = key in state.watched
    // An unwatched seek near the end is kept as evidence of an unfinished episode,
    // but a late callback from an episode that is already complete must not recreate
    // its resume bar. Genuine rewatches below 80% are still retained by
    // [keepResumePoint].
    val keep = if (!finishing && positionMs > 8_000 && !alreadyWatched) true
        else keepResumePoint(positionMs, durationMs, alreadyWatched = alreadyWatched)
    val progress = if (keep) {
        listOf(ProgressEntry(card, segment, positionMs, durationMs, now, verifiedPlayback)) + others
    } else {
        others
    }.let(::capProgress)
    val watched = if (finishing) state.watched + key else state.watched
    val watchedAt = if (finishing) state.watchedAt + (key to now) else state.watchedAt
    val capped = watched.toList().takeLast(5000).toSet()
    return state.copy(
        progress = progress,
        watched = capped,
        watchedAt = watchedAt.filterKeys { it in capped },
        watchedMs = state.watchedMs + watchedDelta,
        watchedMsByTitle = if (watchedDelta > 0) {
            state.watchedMsByTitle + (card.id to ((state.watchedMsByTitle[card.id] ?: 0L) + watchedDelta))
        } else {
            state.watchedMsByTitle
        },
        watchedByHour = if (watchedDelta > 0) {
            val hour = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).hour
            state.watchedByHour + (hour to ((state.watchedByHour[hour] ?: 0L) + watchedDelta))
        } else state.watchedByHour,
        watchedByDay = if (watchedDelta > 0) {
            state.watchedByDay + (day to ((state.watchedByDay[day] ?: 0L) + watchedDelta))
        } else {
            state.watchedByDay
        },
    )
}

/**
 * Confirms a native end-of-media event without trusting the final position alone.
 *
 * A short amount of verified forward playback is enough here because the player may
 * legitimately be resumed near the end. Seeking to the end contributes no verified
 * playback, so it cannot mark the episode as watched.
 */
internal fun applyEpisodeEnded(
    state: PersistedState,
    contentId: String,
    segment: Int,
    now: Long,
): PersistedState {
    val key = watchedKey(contentId, segment)
    if (key in state.watched) {
        // A delayed final checkpoint can race the watched mark. Finishing an already
        // watched episode is therefore also a cleanup boundary for any stale resume.
        val progress = state.progress.filterNot { it.anime.id == contentId && it.segment == segment }
        return if (progress.size == state.progress.size) state else state.copy(progress = progress)
    }
    val entry = state.progress.firstOrNull { it.anime.id == contentId && it.segment == segment }
        ?: return state
    val evidenceNeeded = minOf(30_000L, maxOf(5_000L, entry.durationMs / 50))
    if (entry.durationMs <= 0L || entry.fraction < 0.90f || entry.verifiedPlaybackMs < evidenceNeeded) {
        return state
    }
    val watched = (state.watched + key).toList().takeLast(5000).toSet()
    return state.copy(
        progress = state.progress.filterNot { it.anime.id == contentId && it.segment == segment },
        watched = watched,
        watchedAt = (state.watchedAt + (key to now)).filterKeys { it in watched },
    )
}

/**
 * Устарела ли запись о брошенной серии.
 *
 * Устарела, если ПОСЛЕ неё этот же тайтл смотрели до конца: значит человек ушёл
 * дальше, а недосмотренный кусок остался позади. Строгое «раньше» намеренно: при
 * равных метках (одна секунда, одинаковые часы, отсутствующее время) выигрывает
 * запись о прогрессе — она конкретнее, в ней есть куда возвращаться.
 */
internal fun resumeOutdated(entry: ProgressEntry, newestWatchedAt: Long): Boolean =
    newestWatchedAt > 0L && entry.updatedAt < newestWatchedAt

/**
 * Разовая простановка времени тем отметкам о просмотре, что достались из прошлого.
 *
 * Отметки хранились множеством строк без времени, и восстановить хронологию задним
 * числом НЕЛЬЗЯ — данных просто нет. Поэтому ставится не выдуманное время, а
 * единственное имеющееся свидетельство порядка:
 *
 *   * серия ДАЛЬШЕ брошенной, и брошенная у этого тайтла есть  → считаем, что её
 *     досмотрели ПОСЛЕ (на миллисекунду позже брошенной записи);
 *   * во всех остальных случаях              → 0, то есть «время неизвестно, спорить
 *     с записью о прогрессе не буду».
 *
 * Допущение честное и узкое: досмотреть 25-ю и 28-ю, а потом вернуться и бросить 23-ю
 * на десяти процентах — можно, но это редкость, а обратный порядок — обычная жизнь. У
 * кого вышло наоборот, тот один раз ткнёт в нужную серию списком; дальше время пишется
 * по-настоящему и гадать больше не придётся.
 */
internal fun backfillWatchedAt(state: PersistedState): PersistedState {
    if (state.watchedAtBackfilled) return state
    // Самая свежая брошенная запись каждого тайтла — та единственная точка отсчёта,
    // относительно которой вообще есть что сказать.
    val newestProgress = HashMap<String, ProgressEntry>()
    for (entry in state.progress) {
        val known = newestProgress[entry.anime.id]
        if (known == null || entry.updatedAt > known.updatedAt) newestProgress[entry.anime.id] = entry
    }
    val stamps = HashMap<String, Long>(state.watchedAt)
    for (key in state.watched) {
        if (key in stamps) continue
        val hash = key.lastIndexOf('#')
        if (hash <= 0) continue
        val id = key.substring(0, hash)
        val segment = key.substring(hash + 1).toIntOrNull() ?: continue
        val abandoned = newestProgress[id]
        stamps[key] = if (abandoned != null && segment > abandoned.segment) abandoned.updatedAt + 1 else 0L
    }
    return state.copy(watchedAt = stamps, watchedAtBackfilled = true)
}

/**
 * Собрать прогресс по всем тайтлам разом. Отдельно от [AppSettings], потому что от
 * настроек тут нужно только состояние — так это и проверяется тестом, без файла на диске.
 */
internal fun buildWatchIndex(state: PersistedState): Map<String, TitleWatch> {
    // Самая дальняя ДОСМОТРЕННАЯ серия каждого тайтла — и сколько их всего.
    val furthest = HashMap<String, Int>()
    val seenCount = HashMap<String, Int>()
    val latestCompleted = HashMap<String, Int>()
    for (key in state.watched) {
        val hash = key.lastIndexOf('#')
        if (hash <= 0) continue
        val id = key.substring(0, hash)
        val segment = key.substring(hash + 1).toIntOrNull() ?: continue
        if (segment <= 0) continue
        val known = furthest[id]
        if (known == null || segment > known) furthest[id] = segment
        if ((state.episodeCounts[id] ?: 0) <= 0 || segment <= state.episodeCounts.getValue(id)) {
            seenCount[id] = (seenCount[id] ?: 0) + 1
        }
        val recent = latestCompleted[id]
        if (recent == null || compareValuesBy(segment, recent,
                { state.watchedAt[watchedKey(id, it)] ?: 0L }, { it }) > 0) latestCompleted[id] = segment
    }
    // Брошенная серия — та, что обновлялась ПОСЛЕДНЕЙ, а не с наибольшим номером:
    // пересмотр первой серии после финала это тоже «остановились на первой».
    val resume = HashMap<String, ProgressEntry>()
    for (entry in state.progress) {
        if (entry.segment <= 0 || entry.positionMs <= 8_000L) continue
        if (entry.fraction >= 0.95f) continue
        // ДОСМОТРЕННАЯ СЕРИЯ НЕ БЫВАЕТ БРОШЕННОЙ.
        //
        // Серия отмечается просмотренной с 90 % (см. saveProgress), а здесь стояла
        // одна лишь отсечка по 95 %. В зазор попадает ровно то место, где идут титры:
        // человек досмотрел серию до эндинга, она уже записана в [watched] — и та же
        // серия одновременно значилась брошенной, потому что запись о прогрессе
        // перебивает досмотренные (см. развилку по `resume` ниже). Наружу это
        // «Остановились: 15 серия» на тайтле, у которого пятнадцатая серия досмотрена,
        // и подпись сбивала с толку ровно там, где должна помогать.
        //
        // Спрашиваем ДВА условия сразу, и оба нужны. Одной отметки в [watched] мало:
        // пересмотр досмотренной серии с начала — это по-прежнему «остановились
        // здесь», и по одной отметке метка уехала бы на самую дальнюю серию (см.
        // «брошенная серия важнее номера досмотренной»). Одной доли тоже мало: она
        // ничего не знает о том, засчитал ли плеер эту серию.
        if (entry.fraction >= 0.90f && watchedKey(entry.anime.id, entry.segment) in state.watched) continue
        // ПОСЛЕ этой записи тайтл смотрели до конца — значит человек ушёл дальше, и
        // возвращать его к недосмотренному куску незачем. Ровно эта проверка и
        // отсутствовала: 23-я серия на 10 %, после неё досмотрены 25-я и 28-я, а
        // «Продолжить» звало обратно на 23-ю (см. [resumeOutdated]).
        if (resumeOutdated(entry, newestWatchedAt(state, entry.anime.id))) continue
        val known = resume[entry.anime.id]
        if (known == null || entry.updatedAt > known.updatedAt) resume[entry.anime.id] = entry
    }
    val out = HashMap<String, TitleWatch>((furthest.size + resume.size) * 2)
    for (id in furthest.keys + resume.keys) {
        val total = state.episodeCounts[id] ?: 0
        val open = resume[id]
        val done = latestCompleted[id] ?: 0
        out[id] = if (open != null) {
            TitleWatch(episode = open.segment, total = total, finished = false, fraction = open.fraction,
                completedCount = seenCount[id] ?: 0,
                currentAlreadyCompleted = watchedKey(id, open.segment) in state.watched)
        } else {
            // Незаконченной серии нет — значит остановились на последней досмотренной.
            // И это конец, если серий больше не известно.
            // «Просмотрено» — это КОЛИЧЕСТВО, а не номер последней серии.
            //
            // Здесь стояло `done >= total`, и один прыжок в финал объявлял тайтл
            // пройденным целиком. Настоящее состояние 24.08 по «Наруто»: просмотрена
            // 51 серия из 220, но среди них 220-я — и карточка гордо писала
            // «Просмотрено», пока 169 серий стояли нетронутыми.
            TitleWatch(
                episode = done,
                total = total,
                finished = if (total > 0) (seenCount[id] ?: 0) >= total else done > 0,
                fraction = 0f,
                completedCount = seenCount[id] ?: 0,
            )
        }
    }
    return out
}

/**
 * Отметить весь тайтл просмотренным (или снять отметку). Чистый переход состояния —
 * так его можно проверить, не трогая файл настроек.
 *
 * Отмечаются ВСЕ серии, а не одна последняя. Соблазн поставить только последнюю велик:
 * [buildWatchIndex] считает `finished` по «дошли до последней», и одной отметки хватило
 * бы. Но тогда в списке серий одиннадцать из двенадцати остались бы неотмеченными, а
 * «продолжить» предложило бы первую — тайтл выглядел бы просмотренным на карточке и
 * непросмотренным везде, где его открывают.
 *
 * Записи о брошенной серии СНИМАЮТСЯ. Без этого отметка не сработала бы вовсе:
 * незаконченная серия в [PersistedState.progress] перебивает досмотренные, и `finished`
 * остался бы false (см. развилку по `resume` в [buildWatchIndex]).
 *
 * Число серий берётся из [PersistedState.episodeCounts]; ноль означает «неизвестно» —
 * тогда хватает одной отметки, потому что при неизвестном общем числе [buildWatchIndex]
 * считает досмотренным любой дошедший до конца.
 *
 * Чего эта отметка НЕ делает: не трогает [PersistedState.watchedMs] и статистику по
 * дням. Сколько времени человек провёл у экрана, мы не знаем и придумывать не будем —
 * вырастет только счётчик серий, и это ровно то, что он о себе заявил.
 */
internal fun markTitleWatched(
    state: PersistedState,
    card: PersistedAnime,
    watched: Boolean,
    /** Время отметки. Со значением по умолчанию, чтобы тесты могли не задавать часы. */
    now: Long = System.currentTimeMillis(),
): PersistedState {
    val contentId = card.id
    val prefix = "$contentId#"
    val withoutTitle = state.watched.filterNot { it.startsWith(prefix) }.toSet()
    val marks = if (watched) {
        val total = (state.episodeCounts[contentId] ?: 0).coerceAtLeast(1)
        withoutTitle + (1..total).map { "$contentId#$it" }
    } else {
        withoutTitle
    }
    // Карточка кладётся в «недавние». Это не украшение списка, а единственный способ
    // донести отметку до рекомендаций: [Recommender.buildTaste] знает тайтл только
    // если у него есть КАРТОЧКА — с названием, жанрами и студией, — а такие лежат в
    // избранном, в истории и в записях прогресса. Набор `watched` это одни строки
    // «id#серия», по ним ни жанра, ни студии не восстановить.
    //
    // Без этого отметка проваливалась в никуда: тайтл не попадал во вкус, не попадал
    // в «уже видел» — и продолжал выпадать в рекомендациях как ни в чём не бывало.
    val history = if (watched) {
        (listOf(card) + state.history.filterNot { it.id == contentId }).take(100)
    } else {
        // «Снять отметку» значит «я это не смотрел» — след убирается целиком, иначе
        // тайтл навсегда остался бы в «уже видел» и в рекомендации больше не вернулся.
        state.history.filterNot { it.id == contentId }
    }
    // Потолок тот же, что у отметок из плеера (см. saveProgress): режется самый старый
    // хвост, а не свежая отметка.
    val capped = marks.toList().takeLast(5000).toSet()
    // Время проставляется ТОЛЬКО новым отметкам этого тайтла; чужие метки не трогаем,
    // а при снятии отметки уходят вместе со своими строками.
    val stamps = state.watchedAt.filterKeys { it in capped } +
        if (watched) capped.filter { it.startsWith(prefix) }.associateWith { now } else emptyMap()
    return state.copy(
        watched = capped,
        watchedAt = stamps,
        completedTitles = if (watched) state.completedTitles + contentId else state.completedTitles - contentId,
        progress = state.progress.filterNot { it.anime.id == contentId },
        history = history,
    )
}

/**
 * Поставить или снять личную оценку. Чистый переход состояния — проверяется без файла.
 *
 * [score] вне 1..5 означает «снять оценку»: у звёзд нет состояния «ноль звёзд», есть
 * «не оценено», и хранить его записью было бы враньём — ноль потом пришлось бы всюду
 * отличать от единицы.
 *
 * Снятие оценки НЕ трогает ни отметку «просмотрено», ни историю. «Передумал ставить
 * оценку» и «я это не смотрел» — разные заявления, и путать их нельзя: иначе снятая
 * пятёрка стирала бы и сам факт просмотра.
 *
 * Свежие сверху: [Recommender] берёт отсюда и вес, и возраст следа.
 */
internal fun applyRating(
    state: PersistedState,
    card: PersistedAnime,
    score: Int,
    now: Long,
): PersistedState {
    val others = state.ratings.filterNot { it.anime.id == card.id }
    val next = if (score in 1..5) {
        listOf(RatedTitle(card, score, now)) + others
    } else {
        others
    }
    // Потолок не нужен: оценка это осознанное действие, их за жизнь ставят сотни, а
    // не тысячи. Список всё равно ограничен здравым смыслом, а не кодом.
    return state.copy(ratings = next)
}

/**
 * Tiny JSON-file settings/favorites/history store — the desktop equivalent of
 * the mobile app's Room + DataStore persistence, without pulling in Android-only
 * dependencies. Lives at `%APPDATA%/AniBlaze/state.json` (falls back to the
 * user's home directory if APPDATA isn't set, e.g. on non-Windows).
 */
class AppSettings(stateFile: File? = null) {
    val petUiMemory = com.aniblaze.desktop.pet.PetUiMemory()
    private val file: File = stateFile ?: run {
        val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "AniBlaze")
        dir.mkdirs()
        File(dir, "state.json")
    }
    internal val dataDirectory: File get() = file.parentFile
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val pendingWrite = AtomicReference<PersistedState?>(null)
    private val writeScheduled = AtomicBoolean(false)
    private val _persistenceError = MutableStateFlow<String?>(null)
    val persistenceError: StateFlow<String?> = _persistenceError
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "aniblaze-settings-writer").apply { isDaemon = true }
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<PersistedState> = _state

    /** См. [watchIndex]: посчитанная карта прогресса и состояние, из которого она собрана. */
    private val watchIndexCache = AtomicReference<Pair<PersistedState, Map<String, TitleWatch>>?>(null)

    private fun readState(f: File): PersistedState? =
        runCatching { json.decodeFromString<PersistedState>(f.readText()) }.getOrNull()

    // --- Дневные резервные копии ---

    private val backupsDir: File get() = File(file.parentFile, "backups")

    /** Одна копия в день (`state-2026-09-21.json`), хранится [DAILY_BACKUPS] последних. */
    private fun rotateDailyBackup() {
        val dir = backupsDir
        dir.mkdirs()
        val today = java.time.LocalDate.now().toString()
        val target = File(dir, "state-$today.json")
        if (!target.exists() && file.exists() && file.length() > 0) file.copyTo(target, overwrite = false)
        backups().drop(DAILY_BACKUPS).forEach { runCatching { it.delete() } }
    }

    /** Копии, свежие сверху. */
    fun backups(): List<File> = backupsDir.listFiles { f -> f.isFile && f.name.startsWith("state-") && f.name.endsWith(".json") }
        ?.sortedByDescending { it.name }.orEmpty()

    /**
     * Восстановить состояние из копии. Копия сначала читается и проверяется — битый
     * файл ничего не заменит; текущее состояние перед заменой сохраняется как
     * `state-before-restore.json`, чтобы восстановление можно было отменить.
     */
    @Synchronized fun restoreFrom(backup: File): Boolean {
        val restored = readState(backup) ?: return false
        runCatching { file.copyTo(File(backupsDir.also { it.mkdirs() }, "state-before-restore.json"), overwrite = true) }
        save(restored)
        return flush()
    }

    /** Экспорт состояния целиком (тот же JSON, что и копии) в выбранный файл. */
    fun exportState(target: File): Boolean {
        if (!flush()) return false
        return runCatching { file.copyTo(target, overwrite = true) }.isSuccess
    }

    /** Библиотека в CSV: избранное, история, оценки, досмотренное — для таблиц и переноса. */
    fun exportCsv(target: File): Boolean = runCatching {
        target.writeText(libraryCsv(_state.value), Charsets.UTF_8)
    }.isSuccess

    private fun load(): PersistedState {
        val backup = File(file.parentFile, "${file.name}.bak")
        // Main file first; if it's corrupt or gone, fall back to the last-known-good
        // backup — favourites/history/progress all live in this one file, so without
        // this a single bad write (disk, AV, power cut) wiped everything silently.
        val fromMain = readState(file)
        val stored = fromMain
            ?: readState(backup)?.also {
                runCatching { backup.copyTo(file, overwrite = true) }
            }
            ?: PersistedState()
        // A healthy main file becomes the new backup — one good copy per launch.
        if (fromMain != null) runCatching { file.copyTo(backup, overwrite = true) }
        // И ежедневная копия с историей: `.bak` спасает от одной битой записи, а
        // семь дневных копий — от «вчера случайно очистил избранное».
        if (fromMain != null) runCatching { rotateDailyBackup() }
        // Отметкам о просмотре, доставшимся из прошлого, проставляется время — иначе
        // «что смотрели позже» остаётся неразрешимым на всех уже накопленных данных.
        // Разбор допущения — у [backfillWatchedAt].
        val cleaned = removeStaleWatchedResumePoints(stored)
        val stamped = pruneSnoozed(backfillWatchedAt(cleaned), now())
        if (stamped != stored) {
            runCatching { file.writeText(json.encodeToString(PersistedState.serializer(), stamped)) }
        }
        if (stamped.vostMergeMigrated) return stamped
        // See [PersistedState.vostMergeMigrated]: drop the catalog-merge entry once,
        // then never touch the user's choice again (they can re-enable it by hand).
        val migrated = stamped.copy(
            enabledSources = (stamped.enabledSources - "AnimeVost").ifEmpty { setOf("Anixart", "AniLibria") },
            vostMergeMigrated = true,
        )
        runCatching { file.writeText(json.encodeToString(PersistedState.serializer(), migrated)) }
        return migrated
    }

    private fun save(next: PersistedState) {
        _state.value = next
        pendingWrite.set(next)
        scheduleWrite()
    }

    private fun scheduleWrite() {
        if (!writeScheduled.compareAndSet(false, true)) return
        writer.execute {
            try {
                while (true) {
                    val snapshot = pendingWrite.getAndSet(null) ?: break
                    writeAtomically(snapshot)
                }
            } finally {
                writeScheduled.set(false)
                // A save can land between the final getAndSet and resetting the flag.
                if (pendingWrite.get() != null) scheduleWrite()
            }
        }
    }

    @Synchronized fun retrySave() = save(_state.value)

    private fun writeAtomically(state: PersistedState): Boolean {
        repeat(3) { attempt ->
        val result = runCatching {
            val temp = File(file.parentFile, "${file.name}.tmp")
            // ДАННЫЕ НА ДИСК — ДО ПЕРЕИМЕНОВАНИЯ.
            //
            // Здесь стоял просто writeText, и на внезапном выключении (кнопка Reset,
            // пропало питание, перезагрузка с зависшим приложением) это давало ровно
            // ту поломку, которой боятся: NTFS журналирует МЕТАДАННЫЕ, но не
            // содержимое. Переименование доезжало до диска, а сами байты оставались в
            // кэше страниц и пропадали — на месте настроек оказывался файл нулевой
            // длины. Разбор при загрузке ловит такое и поднимает `.bak`, но копия там
            // одна на запуск, то есть терялось всё, что человек посмотрел за сеанс.
            //
            // sync() возвращается только когда содержимое действительно записано.
            // Стоит это миллисекунды, а пишем мы не чаще раза в две секунды.
            java.io.FileOutputStream(temp).use { out ->
                out.write(json.encodeToString(PersistedState.serializer(), state).toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        if (result.isSuccess) {
            _persistenceError.value = null
            return true
        }
        _persistenceError.value = "История и настройки ещё не сохранены. Проверьте свободное место и доступ к папке AniBlaze."
        com.aniblaze.desktop.player.PlayerDiagnostics.failure("settings.write", result.exceptionOrNull()!!)
        if (pendingWrite.get() != null) return false // a newer snapshot gets priority
        if (attempt < 2) Thread.sleep(150L * (attempt + 1))
        }
        return false
    }

    /** Wait until every queued settings update is durable on disk. Used on app exit. */
    fun flush(): Boolean {
        val done = CountDownLatch(1)
        writer.execute {
            val snapshot = pendingWrite.getAndSet(null)
                ?: _state.value.takeIf { _persistenceError.value != null }
            snapshot?.let(::writeAtomically)
            done.countDown()
        }
        val completed = done.await(5, TimeUnit.SECONDS)
        if (!completed) _persistenceError.value = "Сохранение ещё выполняется. Не закрывайте приложение до завершения записи."
        return completed && _persistenceError.value == null
    }

    fun favorites(): List<Anime> = _state.value.favorites.map { it.toAnime() }
    fun isFavorite(id: String): Boolean = _state.value.favorites.any { it.id == id }

    /**
     * Тот же тайтл под ТЕМ id, под которым он уже есть у пользователя.
     *
     * Все следы — история, избранное, прогресс, «остановились на…», оценки — лежат по
     * id источника. Пока источник был один (Anixart), это было незаметно; с несколькими
     * «Тяжкий труд в подземелье» приходит как `ax:20257`, `ya:6194` и `am:929:…`, и у
     * каждого своя пустая история, хотя смотрели одно и то же. Поэтому карточка из
     * любого источника, совпадающая по названию (и не расходящаяся по году) с уже
     * известной, приводится к известному id ДО того, как попадёт на экран или в плеер.
     * Свежие поля (постер, описание, жанры) берутся у пришедшей карточки, если у
     * известной их нет.
     *
     * Кино (`tmdb:`, `http…`) не трогается: у него свои id и своя логика.
     */
    fun canonical(anime: Anime): Anime {
        val st = _state.value
        if (isKnownId(anime.id, st)) return anime
        if (anime.id.startsWith("tmdb") || anime.id.startsWith("http") || anime.id.startsWith("cartoon:")) return anime
        val key = titleKey(anime.title)
        if (key.isBlank()) return anime
        val known = knownTitles(st)[key]?.firstOrNull { k -> anime.year == 0 || k.year == 0 || k.year == anime.year }
            ?: return anime
        return anime.copy(
            id = known.id,
            poster = anime.poster.ifBlank { known.poster },
            description = anime.description.ifBlank { known.description },
        )
    }

    /** Есть ли у пользователя след под этим id. */
    fun isKnownId(id: String, st: PersistedState = _state.value): Boolean =
        st.history.any { it.id == id } || st.favorites.any { it.id == id } ||
            st.progress.any { it.anime.id == id } || st.ratings.any { it.anime.id == id }

    private data class KnownTitle(val id: String, val year: Int, val poster: String, val description: String)

    @Volatile private var knownTitlesCache: Pair<PersistedState, Map<String, List<KnownTitle>>>? = null

    /** Индекс «название → известные карточки»; пересобирается при смене состояния. */
    private fun knownTitles(st: PersistedState): Map<String, List<KnownTitle>> {
        knownTitlesCache?.let { (forState, map) -> if (forState === st) return map }
        val map = HashMap<String, MutableList<KnownTitle>>()
        fun add(a: PersistedAnime) {
            val key = titleKey(a.title)
            if (key.isBlank()) return
            val list = map.getOrPut(key) { mutableListOf() }
            if (list.none { it.id == a.id }) list.add(KnownTitle(a.id, a.year, a.poster, a.description))
        }
        // Порядок = приоритет: прогресс (там «остановились на…»), история, избранное, оценки.
        st.progress.forEach { add(it.anime) }
        st.history.forEach { add(it) }
        st.favorites.forEach { add(it) }
        st.ratings.forEach { add(it.anime) }
        knownTitlesCache = st to map
        return map
    }

    private fun titleKey(title: String): String =
        title.lowercase().replace('ё', 'е').replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    @Synchronized fun toggleFavorite(anime: Anime) {
        val cur = _state.value
        val next = if (cur.favorites.any { it.id == anime.id }) {
            cur.copy(favorites = cur.favorites.filterNot { it.id == anime.id },
                favoriteRemovals = (listOf(FavoriteRemoval(anime.toPersisted(), now())) +
                    cur.favoriteRemovals.filterNot { it.anime.id == anime.id }).take(200))
        } else {
            cur.copy(favorites = listOf(anime.toPersisted()) + cur.favorites,
                favoriteRemovals = cur.favoriteRemovals.filterNot { it.anime.id == anime.id })
        }
        save(next)
    }

    fun history(): List<Anime> = _state.value.history.map { it.toAnime() }

    @Synchronized fun recordHistory(anime: Anime) {
        val cur = _state.value
        // Потолок 100 оставлен намеренно, хотя запись тут тоже тяжёлая (замер живого
        // файла: 34 687 байт на 59 записей — 588 байт каждая, тот же целый
        // PersistedAnime). В отличие от progress выпадение хвоста здесь НЕ теряет точку
        // просмотра: у записи прогресса своя копия тайтла, и «Продолжить» её не читает.
        // Это список «недавние», забывать давнее — его прямая работа. Занято 59 из 100.
        val next = cur.copy(
            history = (listOf(anime.toPersisted()) + cur.history.filterNot { it.id == anime.id }).take(100),
        )
        save(next)
    }

    @Synchronized fun clearHistory() = save(_state.value.copy(history = emptyList()))

    @Synchronized fun clearFavorites() {
        val cur = _state.value
        save(cur.copy(favorites = emptyList(), favoriteRemovals =
            (cur.favorites.map { FavoriteRemoval(it, now()) } + cur.favoriteRemovals)
                .distinctBy { it.anime.id }.take(200)))
    }

    /** Wipe all saved playback positions (the "Продолжить просмотр" row + resume bars). */
    @Synchronized fun clearProgress() = save(_state.value.copy(progress = emptyList()))

    // --- Continue watching (resume playback position) ---

    /** Saved resume position for a title+segment, or 0 if none / already finished. */
    fun progressMs(contentId: String, segment: Int): Long =
        _state.value.progress.firstOrNull { it.anime.id == contentId && it.segment == segment }
            ?.takeIf { it.fraction < 0.95f }?.positionMs ?: 0L

    /** Watched fraction (0..1) for a title+segment, or 0 if none / already finished —
     *  drives the resume progress bar on episode rows. */
    fun progressFraction(contentId: String, segment: Int): Float =
        _state.value.progress.firstOrNull { it.anime.id == contentId && it.segment == segment }
            ?.fraction?.coerceIn(0f, 1f) ?: 0f

    /** Upsert playback progress and measured playback evidence. */
    @Synchronized fun saveProgress(anime: Anime, segment: Int, positionMs: Long, durationMs: Long,
        measuredWatchMs: Long? = null) {
        val timestamp = now()
        save(
            applyEpisodeProgress(
                state = _state.value,
                card = anime.toPersisted(),
                segment = segment,
                positionMs = positionMs,
                durationMs = durationMs,
                now = timestamp,
                day = java.time.LocalDate.now().toString(),
                measuredWatchMs = measuredWatchMs,
            ),
        )
    }

    private fun watchKey(contentId: String, segment: Int) = "$contentId#$segment"

    /** Whether this episode was watched to the end. */
    fun isWatched(contentId: String, segment: Int): Boolean =
        _state.value.watched.contains(watchKey(contentId, segment))

    /** Handle a reliable end-of-media event after its final progress checkpoint. */
    @Synchronized fun confirmEpisodeEnded(contentId: String, segment: Int): Boolean {
        val updated = applyEpisodeEnded(_state.value, contentId, segment, now())
        if (updated !== _state.value) save(updated)
        return watchKey(contentId, segment) in updated.watched
    }

    /** Manual toggle from the episode row's context menu. */
    @Synchronized fun setWatched(contentId: String, segment: Int, watched: Boolean) {
        val cur = _state.value
        val key = watchKey(contentId, segment)
        save(
            cur.copy(
                watched = if (watched) cur.watched + key else cur.watched - key,
                completedTitles = if (watched) cur.completedTitles else cur.completedTitles - contentId,
                // Время отметки — половина ответа на «что смотрели позже» (см. [watchedAt]).
                watchedAt = if (watched) cur.watchedAt + (key to now()) else cur.watchedAt - key,
                // Досмотренная серия точки возобновления не имеет: старая запись о
                // прогрессе сняла бы отметку обратно при первом же пересчёте.
                progress = if (watched) {
                    cur.progress.filterNot { it.anime.id == contentId && it.segment == segment }
                } else {
                    cur.progress
                },
            ),
        )
    }

    @Synchronized fun clearWatched() = save(_state.value.copy(watched = emptySet(), watchedAt = emptyMap(), completedTitles = emptySet()))

    /** Отметить весь тайтл просмотренным — галочка на карточке. См. [markTitleWatched]. */
    @Synchronized fun setTitleWatched(anime: Anime, watched: Boolean) =
        save(markTitleWatched(_state.value, anime.toPersisted(), watched))

    /** Считается ли тайтл просмотренным целиком — то, что показывает галочка. */
    fun isTitleWatched(contentId: String): Boolean = watchOf(contentId)?.finished == true

    // --- Личные оценки ---

    /** Оценка тайтла, 1..5. Ноль — не оценивали. */
    fun ratingOf(contentId: String, state: PersistedState = _state.value): Int =
        state.ratings.firstOrNull { it.anime.id == contentId }?.score ?: 0

    /** Поставить оценку (1..5) или снять её любым другим числом. См. [applyRating]. */
    @Synchronized fun setRating(anime: Anime, score: Int) =
        save(applyRating(_state.value, anime.toPersisted(), score, now()))

    @Synchronized fun clearRatings() = save(_state.value.copy(ratings = emptyList()))

    // --- «Не понравилось» ---

    fun isDisliked(contentId: String, state: PersistedState = _state.value): Boolean = contentId in state.disliked

    /**
     * Отметить «посмотрел — не понравилось». Тайтл уходит из лент и подборок, а в
     * рекомендациях считается за самую низкую оценку: если своей оценки ещё нет,
     * ставится одна звезда — тот же антивкус, что и у ручной единицы. Снятие отметки
     * убирает и эту звезду, но только её (свою оценку зритель ставил сам).
     */
    @Synchronized fun setDisliked(anime: Anime, disliked: Boolean) {
        val cur = _state.value
        val id = anime.id
        val ratings = when {
            disliked && ratingOf(id, cur) == 0 -> applyRating(cur, anime.toPersisted(), 1, now()).ratings
            !disliked && ratingOf(id, cur) == 1 && id in cur.disliked -> applyRating(cur, anime.toPersisted(), 0, now()).ratings
            else -> cur.ratings
        }
        save(cur.copy(disliked = if (disliked) cur.disliked + id else cur.disliked - id, ratings = ratings))
    }

    // --- Напоминания о сериях: «Позже» и «Не напоминать» ---

    fun isReleaseMuted(contentId: String, state: PersistedState = _state.value): Boolean = contentId in state.releaseMuted

    @Synchronized fun setReleaseMuted(contentId: String, muted: Boolean) {
        val cur = _state.value
        val next = if (muted) cur.releaseMuted + contentId else cur.releaseMuted - contentId
        if (next != cur.releaseMuted) save(cur.copy(releaseMuted = next))
    }

    /** Напомнить о событии релиза через [delayMs]; событие уже отмечено показанным. */
    @Synchronized fun remindLater(eventKey: String, delayMs: Long = RELEASE_LATER_MS) {
        val cur = _state.value
        save(cur.copy(releaseLater = (cur.releaseLater + (eventKey to now() + delayMs)).entries
            .sortedBy { it.value }.takeLast(50).associate { it.key to it.value }))
    }

    /** События «Позже», чей срок пришёл; забираются по одному и тут же снимаются. */
    @Synchronized fun claimDueReminders(): List<String> {
        val cur = _state.value
        val due = cur.releaseLater.filterValues { it <= now() }.keys.toList()
        if (due.isNotEmpty()) save(cur.copy(releaseLater = cur.releaseLater - due.toSet()))
        return due
    }

    // --- «Не показывать 30 дней» ---

    /** Спрятан ли тайтл на время: срок ещё не вышел. */
    fun isSnoozed(contentId: String, state: PersistedState = _state.value, now: Long = now()): Boolean =
        (state.snoozedUntil[contentId] ?: 0L) > now

    /**
     * Спрятать тайтл из лент, витрины и подборок на [days] дней. Это НЕ «не
     * понравилось»: вкус не трогается, оценка не ставится — просто «не сейчас».
     */
    @Synchronized fun snooze(anime: Anime, days: Int = SNOOZE_DAYS) {
        val cur = _state.value
        val until = now() + days * 86_400_000L
        save(cur.copy(snoozedUntil = pruneSnoozed(cur, now()).snoozedUntil + (anime.id to until)))
    }

    @Synchronized fun unsnooze(anime: Anime) {
        val cur = _state.value
        if (anime.id in cur.snoozedUntil) save(cur.copy(snoozedUntil = cur.snoozedUntil - anime.id))
    }

    // --- Фильтр каталога: последний и частота условий ---

    @Synchronized fun saveAnimeFilter(filter: com.aniblaze.aggregator.model.CatalogFilter) {
        val cur = _state.value
        val persisted = PersistedFilter.of(filter)
        if (cur.lastAnimeFilter == persisted) return
        // Считаем только ВКЛЮЧЁННЫЕ условия — то, чего не было в прошлом фильтре.
        val before = cur.lastAnimeFilter?.toFilter()?.let(::filterUsageKeys).orEmpty()
        val added = filterUsageKeys(filter) - before
        val usage = if (added.isEmpty()) cur.filterUsage else cur.filterUsage.toMutableMap().apply {
            added.forEach { key -> put(key, (get(key) ?: 0) + 1) }
        }
        save(cur.copy(lastAnimeFilter = persisted, filterUsage = usage))
    }

    fun lastAnimeFilter(): com.aniblaze.aggregator.model.CatalogFilter? = _state.value.lastAnimeFilter?.toFilter()

    /**
     * Часто используемые условия: включались не меньше [FREQUENT_FILTER_MIN] раз и
     * входят в первые [FREQUENT_FILTER_TOP] по частоте.
     */
    fun frequentFilterKeys(state: PersistedState = _state.value): Set<String> =
        state.filterUsage.entries.filter { it.value >= FREQUENT_FILTER_MIN }
            .sortedByDescending { it.value }.take(FREQUENT_FILTER_TOP).map { it.key }.toSet()

    // --- Наборы фильтров ---

    /** Сохранить набор; одноимённый в том же разделе заменяется. Пустое имя — ничего. */
    @Synchronized fun saveFilterPreset(name: String, scope: String, filter: com.aniblaze.aggregator.model.CatalogFilter) {
        val clean = name.trim().take(PRESET_NAME_MAX)
        if (clean.isEmpty() || filter.activeCount == 0) return
        val cur = _state.value
        val rest = cur.filterPresets.filterNot { it.scope == scope && it.name.equals(clean, ignoreCase = true) }
        val preset = FilterPreset(clean, scope, PersistedFilter.of(filter))
        save(cur.copy(filterPresets = (rest + preset).takeLast(FILTER_PRESETS_MAX)))
    }

    @Synchronized fun deleteFilterPreset(name: String, scope: String) {
        val cur = _state.value
        val rest = cur.filterPresets.filterNot { it.scope == scope && it.name == name }
        if (rest.size != cur.filterPresets.size) save(cur.copy(filterPresets = rest))
    }

    fun filterPresets(scope: String, state: PersistedState = _state.value): List<FilterPreset> =
        state.filterPresets.filter { it.scope == scope }

    @Synchronized fun setTheme(theme: String) = save(_state.value.copy(theme = theme))
    @Synchronized fun setAccent(accent: String) = save(_state.value.copy(accent = accent))

    // --- New-episode tracking ---

    fun episodeCount(contentId: String): Int? = _state.value.episodeCounts[contentId]

    /** Record the episode count seen for a title; returns true when it GREW (a new
     *  episode is out) and there was a previous count to compare against. */
    @Synchronized fun recordEpisodeCount(contentId: String, count: Int): Boolean {
        if (count <= 0) return false
        val cur = _state.value
        val previous = cur.episodeCounts[contentId]
        val grew = previous != null && count > previous
        if (previous != null && count <= previous) return false
        save(
            cur.copy(
                episodeCounts = cur.episodeCounts + (contentId to count),
                newEpisodes = if (grew) cur.newEpisodes + (contentId to count) else cur.newEpisodes,
                newEpisodeAt = if (grew) cur.newEpisodeAt + (contentId to now()) else cur.newEpisodeAt,
            ),
        )
        return grew
    }

    fun hasNewEpisode(contentId: String): Boolean {
        val at = _state.value.newEpisodeAt[contentId] ?: return false
        return contentId in _state.value.newEpisodes && at > 0 && now() - at in 0..NEW_EPISODE_WINDOW_MS
    }

    /** Highest episode number of this title the user actually watched (0 = none).
     *  Used to spot "there are episodes past the one you last saw" on the very first
     *  sweep, before any baseline count exists. */
    fun lastWatchedSegment(contentId: String): Int {
        val prefix = "$contentId#"
        val watched = _state.value.watched
            .filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).toIntOrNull() }
            .maxOrNull() ?: 0
        val inProgress = _state.value.progress
            .filter { it.anime.id == contentId }
            .maxOfOrNull { it.segment } ?: 0
        return maxOf(watched, inProgress)
    }

    /** Flag a title as having new episodes without touching the baseline count. */
    @Synchronized fun flagNewEpisode(contentId: String, count: Int) {
        val cur = _state.value
        if (cur.newEpisodes[contentId] == count) return
        save(cur.copy(newEpisodes = cur.newEpisodes + (contentId to count)))
    }

    /** Titles flagged with a new episode (for the "Новые серии" row). */
    fun newEpisodeTitles(): List<Anime> {
        val flagged = _state.value.newEpisodes.keys.filter { id ->
            val at = _state.value.newEpisodeAt[id] ?: 0L
            at > 0 && now() - at in 0..NEW_EPISODE_WINDOW_MS
        }.sortedByDescending { _state.value.newEpisodeAt[it] }
        if (flagged.isEmpty()) return emptyList()
        val known = (_state.value.favorites + _state.value.history).associateBy { it.id }
        return flagged.mapNotNull { known[it]?.toAnime() }
    }

    @Synchronized fun clearNewEpisode(contentId: String) {
        val cur = _state.value
        val count = cur.newEpisodes[contentId] ?: return
        // Открыли тайтл — считаем, что о серии уже знают: всплывашка про неё больше
        // не нужна, иначе обход объявит её при первой же проверке.
        save(
            cur.copy(
                newEpisodes = cur.newEpisodes - contentId,
                newEpisodeAt = cur.newEpisodeAt - contentId,
                announcedEpisodes = cur.announcedEpisodes + "$contentId#$count",
            ),
        )
    }

    /** Помеченные новой серией тайтлы, о которых ещё не сообщали: id к номеру серии. */
    fun unannouncedEpisodes(): Map<String, Int> {
        val cur = _state.value
        return cur.newEpisodes.filter { (id, count) ->
            val at = cur.newEpisodeAt[id] ?: 0L
            at > 0 && now() - at in 0..NEW_EPISODE_WINDOW_MS && "$id#$count" !in cur.announcedEpisodes
        }
    }

    /** Пометить, что об этой серии всплывашка уже была. */
    @Synchronized fun markAnnounced(contentId: String, count: Int) {
        val cur = _state.value
        val key = "$contentId#$count"
        if (key in cur.announcedEpisodes) return
        // Держим набор ограниченным: он растёт на каждую серию каждого тайтла.
        val trimmed = if (cur.announcedEpisodes.size >= ANNOUNCED_LIMIT) {
            cur.announcedEpisodes.drop(cur.announcedEpisodes.size - ANNOUNCED_LIMIT / 2).toSet()
        } else {
            cur.announcedEpisodes
        }
        save(cur.copy(announcedEpisodes = trimmed + key))
    }

    @Synchronized fun setNotifyNewEpisodes(enabled: Boolean) = save(_state.value.copy(notifyNewEpisodes = enabled))

    /** Titles worth polling for new episodes: favourites plus recent history. */
    fun trackedTitles(): List<Anime> =
        (_state.value.favorites + _state.value.history.take(40)).distinctBy { it.id }.map { it.toAnime() }

    /** In-progress titles, most-recent first (for the "Продолжить просмотр" row).
     *  De-duplicated by title: a series has one progress entry PER episode, so
     *  without this the same anime appears many times — and duplicate ids crash the
     *  LazyRow ("Key … was already used"). distinctBy keeps the most recent entry. */
    fun continueWatching(): List<Anime> {
        val snapshot = _state.value
        return snapshot.progress
            .filter { entry ->
                entry.positionMs > 8_000 && entry.fraction < 0.90f &&
                    !resumeOutdated(entry, newestWatchedAt(snapshot, entry.anime.id))
            }
            .sortedByDescending { it.updatedAt }
            .distinctBy { it.anime.id }
            .map { it.anime.toAnime() }
    }

    // --- Метка прогресса на постере ---

    /**
     * Прогресс по всем тайтлам сразу, посчитанный ОДИН раз на состояние.
     *
     * Считать это в каждой карточке нельзя: на экране их три десятка, а набор
     * досмотренных серий доходит до пяти тысяч строк — тридцать проходов по нему на
     * каждое сохранение позиции (раз в пять секунд во время просмотра) съедали бы
     * кадры. Кэш держится на ИДЕНТИЧНОСТИ состояния: [PersistedState] неизменяем,
     * поэтому новая ссылка означает реальное изменение, а повторный вызов с той же
     * ссылкой возвращает уже готовую карту.
     */
    fun watchIndex(state: PersistedState = _state.value): Map<String, TitleWatch> {
        watchIndexCache.get()?.let { (cached, index) -> if (cached === state) return index }
        val built = buildWatchIndex(state)
        watchIndexCache.set(state to built)
        return built
    }

    /** Прогресс одного тайтла, или null — если его ещё не открывали. */
    fun watchOf(contentId: String, state: PersistedState = _state.value): TitleWatch? =
        watchIndex(state)[contentId]

    private fun now(): Long = System.currentTimeMillis()

    @Synchronized fun setGridColumns(columns: Int) = save(_state.value.copy(gridColumns = columns))

    @Synchronized fun setAutoplayNext(enabled: Boolean) = save(_state.value.copy(autoplayNext = enabled))

    @Synchronized fun setAutoSwitchRandom(enabled: Boolean) = save(_state.value.copy(autoSwitchRandom = enabled))

    @Synchronized fun setCloseToTray(enabled: Boolean) = save(_state.value.copy(closeToTray = enabled))

    @Synchronized fun setFontScale(scale: Float) =
        save(_state.value.copy(fontScale = scale.coerceIn(0.75f, 1.6f)))

    @Synchronized fun setCommentFontSize(size: Int) =
        save(_state.value.copy(commentFontSize = size.coerceIn(11, 20)))

    @Synchronized fun setAutoSkipOpening(enabled: Boolean) =
        save(_state.value.copy(autoSkipOpening = enabled))

    @Synchronized fun setAutoSkipEnding(enabled: Boolean) =
        save(_state.value.copy(autoSkipEnding = enabled))

    @Synchronized fun setAutoDetectTimings(enabled: Boolean) =
        save(_state.value.copy(autoDetectTimings = enabled))

    @Synchronized fun setPlayerComposeVideo(enabled: Boolean) =
        save(_state.value.copy(playerComposeVideo = enabled))

    @Synchronized fun setHudPinned(pinned: Boolean) = save(_state.value.copy(hudPinned = pinned))

    @Synchronized fun setVideoEnhance(key: String) = save(_state.value.copy(videoEnhance = key))

    /**
     * Назначить клавишу действию; null — вернуть умолчание. Одна клавиша — одно
     * действие: с прежнего действия она снимается (то возвращается к умолчанию).
     */
    @Synchronized fun setHotkey(action: String, keyCode: Long?) {
        val cur = _state.value
        val next = cur.hotkeys.filterValues { it != keyCode }.toMutableMap()
        if (keyCode == null) next.remove(action) else next[action] = keyCode
        if (next != cur.hotkeys) save(cur.copy(hotkeys = next))
    }

    @Synchronized fun setAudioPreset(preset: String) {
        if (_state.value.audioPreset != preset) save(_state.value.copy(audioPreset = preset))
    }

    @Synchronized fun setPipBounds(bounds: String) {
        if (_state.value.pipBounds != bounds) save(_state.value.copy(pipBounds = bounds))
    }

    @Synchronized fun setPictureInPicture(enabled: Boolean) =
        save(_state.value.copy(pictureInPicture = enabled))

    @Synchronized fun setCommentsOverlay(enabled: Boolean) =
        save(_state.value.copy(commentsOverlay = enabled))

    @Synchronized fun setCommentsRate(rate: String) =
        save(_state.value.copy(commentsRate = rate))

    @Synchronized fun setCommentsOpacity(value: Float) =
        save(_state.value.copy(commentsOpacity = value.coerceIn(0.2f, 1.0f)))

    @Synchronized fun setCommentsFontSize(size: Int) =
        save(_state.value.copy(commentsFontSize = size.coerceIn(11, 22)))

    @Synchronized fun setCommentsMoving(moving: Boolean) =
        save(_state.value.copy(commentsMoving = moving))

    @Synchronized fun setCommentsFreshOnly(fresh: Boolean) =
        save(_state.value.copy(commentsFreshOnly = fresh))

    @Synchronized fun setChatEnabled(enabled: Boolean) =
        save(_state.value.copy(chatEnabled = enabled))

    /** Потолок в две тысячи — не вкусовщина: выше растёт только надпись, темп упирается
     *  в потолок читаемости (см. ChatEngine.viewerFactor). */
    @Synchronized fun setChatViewers(count: Int) =
        save(_state.value.copy(chatViewers = count.coerceIn(5, 2000)))

    @Synchronized fun setChatIntensity(key: String) =
        save(_state.value.copy(chatIntensity = key))

    @Synchronized fun setChatSpeed(speed: Float) =
        save(_state.value.copy(chatSpeed = com.aniblaze.desktop.player.normalizeChatSpeed(speed)))

    @Synchronized fun setChatAlwaysQuiet(enabled: Boolean) =
        save(_state.value.copy(chatAlwaysQuiet = enabled))

    @Synchronized fun setChatRealOnly(enabled: Boolean) =
        save(_state.value.copy(chatRealOnly = enabled))

    @Synchronized fun setChatYummyComments(enabled: Boolean) =
        save(_state.value.copy(chatYummyComments = enabled))

    @Synchronized fun setChatPopularFirst(enabled: Boolean) =
        save(_state.value.copy(chatPopularFirst = enabled))

    @Synchronized fun setChatEpisodeOnly(enabled: Boolean) =
        save(_state.value.copy(chatEpisodeOnly = enabled))

    @Synchronized fun setChatReplies(enabled: Boolean) =
        save(_state.value.copy(chatReplies = enabled))

    @Synchronized fun setPetEnabled(enabled: Boolean) = save(_state.value.copy(petEnabled = enabled))

    @Synchronized fun setPetSpeechEnabled(enabled: Boolean) = save(_state.value.copy(petSpeechEnabled = enabled))
    @Synchronized fun setPetSoundsEnabled(enabled: Boolean) = save(_state.value.copy(petSoundsEnabled = enabled))
    @Synchronized fun setPetSoundVolume(volume: Float) =
        save(_state.value.copy(petSoundVolume = volume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f))

    @Synchronized fun setPetQuietWatching(enabled: Boolean) = save(_state.value.copy(petQuietWatching = enabled))
    @Synchronized fun setDrizzActivity(value: String) = save(_state.value.copy(drizzActivity =
        value.takeIf { it in setOf("calm", "normal", "active") } ?: "normal"))
    @Synchronized fun setPetDesktopPosition(x: Float, y: Float) = save(_state.value.copy(
        petDesktopX = x.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f,
        petDesktopY = y.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f))

    @Synchronized fun claimPetEvent(key: String): Boolean {
        val state = _state.value
        if (key in state.petAnnouncedEvents) return false
        save(state.copy(petAnnouncedEvents = (state.petAnnouncedEvents.toList().takeLast(499) + key).toSet()))
        return true
    }

    @Synchronized fun setPetCharacter(id: String) = save(_state.value.copy(petCharacter = id))

    @Synchronized fun setPetScale(scale: Float) =
        save(_state.value.copy(petScale = scale.coerceIn(0.7f, 1.6f)))

    @Synchronized fun setPetInPlayer(enabled: Boolean) = save(_state.value.copy(petInPlayer = enabled))

    @Synchronized fun setPetChatterMinutes(minutes: Int) =
        save(_state.value.copy(petChatterMinutes = minutes.coerceIn(1, 30)))

    /** Положение питомца в плеере — доли окна, чтобы пережить смену размера и фуллскрин. */
    @Synchronized fun setPetPlayerPosition(x: Float, y: Float) =
        save(_state.value.copy(petPlayerX = x.coerceIn(0f, 1f), petPlayerY = y.coerceIn(0f, 1f)))

    @Synchronized fun setPetFullscreenPosition(x: Float, y: Float) =
        save(_state.value.copy(petFullscreenX = x.coerceIn(0f, 1f), petFullscreenY = y.coerceIn(0f, 1f)))

    /** Сколько реально отсмотрено по этому тайтлу, мс. 0 = истории нет. */
    fun watchedMsOf(contentId: String): Long = _state.value.watchedMsByTitle[contentId] ?: 0L

    /**
     * Когда тайтл смотрели в последний раз, epoch ms: самая поздняя отметка серии
     * или брошенная позиция. 0 — следа нет.
     */
    fun lastWatchedAt(contentId: String): Long {
        val st = _state.value
        val prefix = "$contentId#"
        val marks = st.watchedAt.filterKeys { it.startsWith(prefix) }.values.maxOrNull() ?: 0L
        val progress = st.progress.filter { it.anime.id == contentId }.maxOfOrNull { it.updatedAt } ?: 0L
        return maxOf(marks, progress)
    }

    /** Полных дней с прошлого просмотра; -1 — не смотрели. Считается на момент вызова. */
    fun daysSinceLastWatch(contentId: String, now: Long = now()): Int {
        val at = lastWatchedAt(contentId)
        if (at <= 0L) return -1
        return ((now - at) / (24 * 60 * 60_000L)).toInt().coerceAtLeast(0)
    }

    @Synchronized fun setDimWatched(enabled: Boolean) =
        save(_state.value.copy(dimWatched = enabled))

    @Synchronized fun setChatFontSize(size: Int) =
        save(_state.value.copy(chatFontSize = size.coerceIn(10, 20)))

    @Synchronized fun setChatPanelWidth(width: Int) =
        save(_state.value.copy(chatPanelWidth = width.coerceIn(240, 560)))

    @Synchronized fun setChatSide(side: String) =
        save(_state.value.copy(chatSide = if (side == "left") "left" else "right"))

    @Synchronized fun setChatOverlayMode(enabled: Boolean) =
        save(_state.value.copy(chatOverlayMode = enabled))

    @Synchronized fun setChatOverlayPosition(key: String) =
        save(_state.value.copy(chatOverlayPosition = key))

    @Synchronized fun setChatOverlayLines(lines: Int) =
        save(_state.value.copy(chatOverlayLines = lines.coerceIn(3, 20)))

    @Synchronized fun setChatOverlayOpacity(value: Float) =
        save(_state.value.copy(chatOverlayOpacity = value.coerceIn(0.2f, 1.0f)))

    @Synchronized fun setChatOverlayFontSize(size: Int) =
        save(_state.value.copy(chatOverlayFontSize = size.coerceIn(10, 22)))

    /**
     * Дозаполнение жанров у записей истории, сделанных до появления статистики.
     * Обновляет только пустые поля — свежие данные затирать нечем.
     */
    @Synchronized fun enrichHistoryMeta(meta: Map<String, Anime>) {
        if (meta.isEmpty()) return
        val cur = _state.value
        fun patch(a: PersistedAnime): PersistedAnime {
            val fresh = meta[a.id] ?: return a
            return a.copy(
                genres = a.genres.ifBlank { fresh.genres },
                studio = a.studio.ifBlank { fresh.studio },
                airingStatus = fresh.airingStatus.takeIf { it > 0 } ?: a.airingStatus,
                episodesTotal = fresh.episodesTotal.takeIf { it > 0 } ?: a.episodesTotal,
                episodesAvailable = maxOf(a.episodesAvailable, fresh.episodesAvailable),
                // Голоса дозаполняем всегда, когда их ещё нет: без них тайтл просто
                // не участвует в «Шлакометре», а не считается хорошим.
                malScore = if (a.malVotes > 0) a.malScore else fresh.malScore,
                malVotes = if (a.malVotes > 0) a.malVotes else fresh.malVotes,
                malLowVotes = if (a.malVotes > 0) a.malLowVotes else fresh.malLowVotes,
                rating = if (a.rating > 0.0) a.rating else fresh.rating,
                favoritesCount = if (a.favoritesCount > 0) a.favoritesCount else fresh.favoritesCount,
                watchingCount = if (a.watchingCount > 0) a.watchingCount else fresh.watchingCount,
            )
        }
        save(
            cur.copy(
                history = cur.history.map(::patch),
                favorites = cur.favorites.map(::patch),
                progress = cur.progress.map { it.copy(anime = patch(it.anime)) },
            ),
        )
    }

    /** Геометрия окна. Пишется по факту изменения, а не на каждый пиксель перетаскивания. */
    @Synchronized fun saveWindowBounds(width: Int, height: Int, x: Int, y: Int, maximized: Boolean) {
        val cur = _state.value
        if (cur.windowWidth == width && cur.windowHeight == height &&
            cur.windowX == x && cur.windowY == y && cur.windowMaximized == maximized
        ) {
            return
        }
        save(
            cur.copy(
                // Развёрнутое окно сообщает размер экрана — запоминать его как
                // «обычный» нельзя, иначе после сворачивания окно займёт весь экран
                // без рамок. Размер сохраняем только в обычном состоянии.
                windowWidth = if (maximized) cur.windowWidth else width,
                windowHeight = if (maximized) cur.windowHeight else height,
                windowX = if (maximized) cur.windowX else x,
                windowY = if (maximized) cur.windowY else y,
                windowMaximized = maximized,
            ),
        )
    }

    @Synchronized fun setCinemaSource(key: String) = save(_state.value.copy(cinemaSource = key))

    @Synchronized fun setLampaEnabled(enabled: Boolean) = save(_state.value.copy(lampaEnabled = enabled))
    @Synchronized fun setLampaBalancer(balancer: String) = save(_state.value.copy(lampaBalancer = balancer))
    @Synchronized fun setLampaPluginUrl(url: String) = save(_state.value.copy(lampaPluginUrl = url.trim()))

    @Synchronized fun setPrimarySource(name: String) = save(_state.value.copy(primarySource = name))

    @Synchronized fun setPlaybackEngine(engine: String) = save(_state.value.copy(playbackEngine = engine))

    @Synchronized fun setPlayerVolume(volume: Int) =
        save(_state.value.copy(playerVolume = volume.coerceIn(0, 100)))

    /** Last-watched voice/quality/dub for a title (key = content id without ":t"). */
    fun playerPref(key: String): PlayerPref? = _state.value.playerPrefs[key]

    @Synchronized private fun upsertPref(key: String, block: (PlayerPref) -> PlayerPref) {
        if (key.isBlank()) return
        val cur = _state.value
        val next = block(cur.playerPrefs[key] ?: PlayerPref())
        // Cap the map so it can't grow unbounded over a long history.
        val trimmed = (cur.playerPrefs + (key to next)).entries.toList().takeLast(500).associate { it.toPair() }
        save(cur.copy(playerPrefs = trimmed))
    }

    fun savePlayerVoice(key: String, voice: Int, manual: Boolean = true) = upsertPref(key) {
        if (!manual && it.voiceManual && it.voice >= 0) it else it.copy(voice = voice, voiceManual = manual)
    }
    @Synchronized fun setVoicePriority(enabled: Boolean) = save(_state.value.copy(voicePriority = enabled))
    fun savePlayerQuality(key: String, quality: String) = upsertPref(key) { it.copy(quality = quality) }
    fun savePlayerDub(key: String, dub: String) = upsertPref(key) { it.copy(dub = dub) }
    fun savePlayerSegment(key: String, segment: Int) =
        if (segment > 0) upsertPref(key) { it.copy(lastSegment = segment) } else Unit

    /**
     * С какой серии продолжать этот тайтл.
     *
     * Раньше здесь была одна строка — «первая серия, у которой есть прогресс», — и
     * она ошибалась в двух случаях сразу: досмотренная серия свой прогресс УДАЛЯЕТ
     * (остаётся только отметка «просмотрено»), поэтому после финала предлагалась
     * первая серия; а если недосмотренных было несколько, бралась самая ранняя, а
     * не последняя открытая.
     *
     * [playable] отсеивает серии без потока — предлагать то, что не запускается,
     * бессмысленно.
     */
    fun resumeSegment(contentId: String, prefKey: String, segments: List<Int>, playable: (Int) -> Boolean): Int? =
        resumeSegmentOf(_state.value, contentId, prefKey, segments, playable)

    @Synchronized fun setSourceEnabled(name: String, enabled: Boolean) {
        val cur = _state.value
        val next = if (enabled) cur.enabledSources + name else cur.enabledSources - name
        save(cur.copy(enabledSources = next.ifEmpty { cur.enabledSources }))
    }

    private companion object {
        /** Потолок набора «о чём уже сообщили»; при переполнении режется вдвое. */
        const val ANNOUNCED_LIMIT = 600
    }
}

/** Ключи условий фильтра — для подсчёта частоты и подсветки в панели. */
fun filterUsageKeys(f: com.aniblaze.aggregator.model.CatalogFilter): Set<String> = buildSet {
    f.tags.forEach { add("tag:$it") }
    if (f.yearFrom > 0 || f.yearTo > 0) add("year:${f.yearFrom}-${f.yearTo}")
    f.status?.let { add("status:${it.name}") }
    f.ageRating?.let { add("age:${it.name}") }
    if (f.country.isNotBlank()) add("country:${f.country}")
    f.episodes?.let { add("episodes:${it.name}") }
    f.contentType?.let { add("type:${it.name}") }
    if (f.minRating > 0.0) add("minRating:${f.minRating}")
    if (f.hideWatched) add("hideWatched")
    if (f.dubbing.isNotBlank()) add("dub:${f.dubbing}")
    if (f.sourceMaterial.isNotBlank()) add("material:${f.sourceMaterial}")
    if (f.hiddenGems) add("gems")
    if (f.protagonist.isNotBlank()) add("hero:${f.protagonist}")
}

const val FREQUENT_FILTER_MIN = 3
const val FREQUENT_FILTER_TOP = 8
/** Наборов фильтров на раздел — не больше; имя набора — не длиннее. */
const val FILTER_PRESETS_MAX = 12
const val PRESET_NAME_MAX = 40

/** Сколько дневных копий состояния держим. */
const val DAILY_BACKUPS = 7

/**
 * Библиотека одной таблицей. Колонки: раздел, id, название, год, оценка (1..5, 0 —
 * нет), просмотрено серий, последний просмотр (дата). Один тайтл может встречаться
 * в нескольких разделах — это честнее, чем сливать «избранное» и «историю» в одну
 * строку с половиной пустых колонок. Разделитель — точка с запятой: так Excel в
 * русской локали открывает файл без мастера импорта. Первая строка — BOM, чтобы
 * кириллица читалась.
 */
fun libraryCsv(state: PersistedState): String {
    fun cell(value: Any?): String {
        val text = value?.toString().orEmpty()
        return if (text.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }
    fun watchedCount(id: String) = state.watched.count { it.substringBeforeLast('#') == id }
    fun lastWatched(id: String): String {
        val at = state.watchedAt.filterKeys { it.substringBeforeLast('#') == id }.values.maxOrNull() ?: 0L
        return if (at <= 0L) "" else java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    }
    val ratings = state.ratings.associate { it.anime.id to it.score }
    val rows = buildList {
        state.favorites.forEach { add("избранное" to it) }
        state.history.forEach { add("история" to it) }
        state.ratings.forEach { add("оценка" to it.anime) }
    }
    val header = listOf("раздел", "id", "название", "год", "оценка", "просмотрено серий", "последний просмотр")
    return buildString {
        append('\uFEFF')
        append(header.joinToString(";")).append("\r\n")
        rows.forEach { (section, anime) ->
            append(listOf(section, anime.id, anime.title, anime.year.takeIf { it > 0 } ?: "", ratings[anime.id] ?: 0,
                watchedCount(anime.id), lastWatched(anime.id)).joinToString(";") { cell(it) }).append("\r\n")
        }
    }
}

/** «Не показывать» — на столько дней. */
const val SNOOZE_DAYS = 30

/** «Позже» у напоминания о серии — через столько напомнить снова. */
const val RELEASE_LATER_MS = 3 * 60 * 60_000L

/** Истёкшие «не показывать» выбрасываются — при загрузке и при каждой новой отметке. */
internal fun pruneSnoozed(state: PersistedState, now: Long): PersistedState {
    if (state.snoozedUntil.values.all { it > now }) return state
    return state.copy(snoozedUntil = state.snoozedUntil.filterValues { it > now })
}
