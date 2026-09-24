package com.aniblaze.desktop.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SpeakerNotesOff
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.aniblaze.aggregator.model.OpeningRange
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.aggregator.model.TitleComment
import com.aniblaze.aggregator.model.Translation
import com.aniblaze.desktop.DisplayAwake
import com.aniblaze.desktop.ui.PlayerChoice
import com.aniblaze.desktop.ui.PlayerChoicePanel
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.LogoPosition
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.EventQueue
import java.awt.Font
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.awt.event.KeyEvent as AwtKeyEvent
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JPanel
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.GraphicEq

/**
 * Discovers libVLC once. The BUNDLED copy (a `vlc/` dir next to the packaged exe,
 * see the `bundleVlc` Gradle task) is offered to discovery via jna.library.path —
 * vlcj's JnaLibraryPath provider picks it up — so the app keeps playing on a
 * machine with no system VLC installed at all. A system install, when present,
 * still wins through the ordinary registry/Program Files strategies.
 */
object VlcSupport {
    /** jpackage layout: <root>/AniBlaze.exe, <root>/runtime (= java.home), <root>/vlc. */
    private fun bundledVlcDir(): java.io.File? {
        val javaHome = System.getProperty("java.home") ?: return null
        val root = java.io.File(javaHome).parentFile ?: return null
        return java.io.File(root, "vlc").takeIf { java.io.File(it, "libvlc.dll").exists() }
    }

    val available: Boolean by lazy {
        // SYSTEM VLC FIRST. Its plugins.dat cache matches its own install path, so
        // libVLC inits in ~200ms. Offering the bundle up-front (jna.library.path
        // outranks the system strategies) made libVLC load from a dir whose copied
        // cache it rejects (wrong paths) — a full ~100-DLL plugin rescan on EVERY
        // MediaPlayerFactory: the measured 17s freeze. Bundle = fallback only.
        val system = runCatching { NativeDiscovery().discover() }.getOrDefault(false)
        if (system) return@lazy true
        val bundled = bundledVlcDir() ?: return@lazy false
        System.setProperty("jna.library.path", bundled.absolutePath)
        PlayerDiagnostics.log("vlc.bundled", "dir=${bundled.absolutePath}")
        runCatching { NativeDiscovery().discover() }.getOrDefault(false)
    }
}

/**
 * One libVLC per process. Creating a MediaPlayerFactory initializes the whole
 * native module bank — 17s when the plugin cache is cold — and the old code did
 * it PER PLAYER SCREEN, inside composition, on the EDT: every entry into the
 * player froze the entire app for that long ("виснет намертво"), and the play
 * sequencing that raced the freeze left a dead stream afterwards. The factory is
 * created once, off the UI thread (warm-up thread at app start), shared by every
 * player instance and never released until the process exits.
 */
object VlcRuntime {
    @Volatile private var cached: MediaPlayerFactory? = null

    @Volatile private var attempted = false

    /** Non-blocking: the factory if it is already initialized, else null. */
    fun peek(): MediaPlayerFactory? = cached

    /** True once [acquire] ran and found no usable libVLC. */
    fun failed(): Boolean = attempted && cached == null

    /** Initializes libVLC on first call — NEVER call on the UI thread. */
    @Synchronized
    fun acquire(): MediaPlayerFactory? {
        if (attempted) return cached
        attempted = true
        cached = if (!VlcSupport.available) {
            null
        } else {
            runCatching {
                val t0 = System.nanoTime()
                MediaPlayerFactory("--no-metadata-network-access").also {
                    PlayerDiagnostics.log("vlc.factory.shared", "initMs=${(System.nanoTime() - t0) / 1_000_000}")
                }
            }.onFailure { PlayerDiagnostics.failure("vlc.factory.init", it) }.getOrNull()
        }
        return cached
    }
}

/** A desktop browser UA — the stream CDNs 403 non-browser clients. */
private const val STREAM_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private const val DOUBLE_CLICK_WINDOW_MS = 360L

/** Ниже этого порога возобновлять нечего — серия и так началась. */
private const val RESUME_MIN_MS = 3_000L

/** Задержка перед отправкой перемотки: склеивает серию быстрых нажатий в одну. */
private const val SEEK_COALESCE_MS = 180L

/** Native callback buffers can be rebuilt just after a seek is reported as landed. */

/** Насколько близко к цели должен подойти VLC, чтобы перемотка считалась завершённой. */
private const val SEEK_LANDED_TOLERANCE_MS = 2_000L

/** Предел ожидания перемотки. Дольше — принимаем то, что показывает VLC, чтобы
 *  недостижимая цель (обрыв потока) не заморозила ползунок навсегда. */
private const val SEEK_TIMEOUT_NANOS = 15_000_000_000L

/**
 * Сколько ждать признаков жизни после аварийного перезапуска потока.
 *
 * Было десять секунд, и это оказалось дольше человеческого терпения: по логам
 * пользователь хватался за ползунок через пять-шесть секунд, так и не дождавшись
 * восстановления. Пять — предел, после которого перезапуск той же ссылки всё
 * равно уже не оправдан.
 */
private const val RECOVERY_TIMEOUT_NANOS = 5_000_000_000L

/** Минимум между двумя срабатываниями клавиши-переключателя (пауза, звук, экран). */
private const val TOGGLE_KEY_GAP_NANOS = 260_000_000L

/** Сколько ждать первых признаков потока после play(), прежде чем считать ссылку мёртвой. */
private const val START_TIMEOUT_NANOS = 9_000_000_000L

/** Ближе этого к концу серии перематывать нельзя — там уже нечего буферизовать. */
private const val SEEK_END_GUARD_MS = 3_000L

/**
 * Приговор «поток так и не начался».
 *
 * Судим ПО ДВИЖЕНИЮ: [firstPosition] — то, что libVLC доложила о себе ПЕРВЫМ разом
 * после запуска, а не то, что мы у неё просили. Стоит на месте девять секунд — мёртв.
 *
 * Обе половины этого правила выстраданы, и каждая своей поломкой.
 *
 * Сравнивать с НУЛЁМ нельзя: возобновление уходит в libVLC опцией `:start-time`, а её
 * демультиплексор применяет ещё до открытия входа — `status().time()` отдаёт заданные
 * 13:44 сразу, не получив ни байта. По нулю сторож на любой серии, продолженной с
 * середины, молчал НАВСЕГДА, и лестница попыток не запускалась ни разу: «Подготовка
 * потока…» и «Загрузка… 0 %» до перезапуска приложения.
 *
 * Сравнивать с ЗАПРОШЕННОЙ точкой — тоже нельзя, и это стоило дороже. Журнал 19.08:
 *
 *     00:30:23.690  play.request       resumeMs=815815
 *     00:30:54.503  stall.started      position=87000     ← пошла с 1:27, а не с 13:35
 *     00:30:55.888  play.neverStarted  position=87000; startedFrom=815815
 *
 * Поток ИГРАЛ — просто libVLC не применила `:start-time` и начала с другого места, а
 * правило «позиция должна уйти дальше запрошенной» объявило живое воспроизведение
 * мёртвым. Дальше лестница пошла перебирать озвучки, роняя по экземпляру libVLC на
 * каждую (`commandThreadStuck`), — то есть сторож своими руками устроил ровно то
 * зависание, ради которого его писали.
 *
 * За [START_TIMEOUT_NANOS] честного воспроизведения позиция уходит на девять секунд
 * вперёд ОТКУДА БЫ ОНА НИ НАЧАЛАСЬ, так что «не сдвинулась» — это про мёртвый поток.
 */
internal fun playbackNeverStarted(elapsedNanos: Long, position: Long, firstPosition: Long): Boolean =
    elapsedNanos > START_TIMEOUT_NANOS && firstPosition >= 0 && position == firstPosition

/**
 * Хост адреса, либо пустая строка.
 *
 * Нужен, чтобы отличить «мертво это качество» от «мертв весь раздающий»: у Anixart все
 * качества одной серии лежат на одном solodcdn, и после нулевой отдачи перебирать их
 * бессмысленно, а вот доклеенная 1080p от AniLibria живёт на cache.libria.fun — другой
 * хост, другая судьба. Маркер «#h=720» на разбор не влияет: это фрагмент URI.
 */
internal fun hostOfUrl(url: String): String =
    runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")

/**
 * Какое качество открывать: сохранённое зрителем или то, что источник смог подтвердить.
 *
 * Обычно — сохранённое: человек выбрал 720p, пусть 720p и открывается. Но проверка
 * адресов перед плеером (withLiveVariantFirst) умеет отбраковать верхние качества и
 * поставить первым то, которое реально отвечает. Тогда сохранённое «720p» указывает
 * ровно на отбракованный адрес, а плеер честно открывает по метке — и дарит мёртвому
 * раздающему девять секунд ожидания на ровном месте.
 *
 * Признак отбраковки — «подтверждённое качество ниже собственного максимума ИСТОЧНИКА».
 * Список берётся ТОЛЬКО тот, что пришёл из резолва, до всякого доклеивания: иначе
 * появление чужой 1080p само по себе выглядело бы как отбраковка. Разделять по хосту
 * нельзя — Anixart раскладывает качества одной серии по разным зеркалам (19.08: 720p
 * на p12, 480p на p14), и фильтр по хосту отрезал бы половину собственного списка.
 *
 * Возвращает null, когда навязывать нечего: сохранённый выбор в силе.
 */
internal fun forcedQualityFor(
    verifiedQuality: String,
    sourceVariants: List<StreamVariant>,
    heightOf: (String) -> Int,
): String? {
    val ownBest = sourceVariants.maxOfOrNull { heightOf(it.quality) } ?: 0
    return if (heightOf(verifiedQuality) in 1 until ownBest) verifiedQuality else null
}

/**
 * Опознавательный знак потока: сменился он — сменится и знак.
 *
 * Служит ключом remember для выбранного качества. Сам список вариантов на эту роль не
 * годится, и это стоило двух минут замершего кадра: список подменяется ещё и когда к
 * нему ДОКЛЕИВАЕТСЯ 1080p от AniLibria (stream.hiRes), а новый ключ у remember создаёт
 * новый объект состояния — тогда как цикл опроса продолжает писать в старый. Разбор
 * с выдержками из журнала — у объявления currentUrl.
 *
 * Доклеивание ставит вариант В КОНЕЦ, значит первый адрес меняется тогда и только
 * тогда, когда сменилась серия, озвучка или источник.
 */
internal fun variantsIdentity(variants: List<StreamVariant>): String = variants.firstOrNull()?.url.orEmpty()

/**
 * Что пробовать после неудачного открытия, либо null — если пробовать нечего.
 *
 * Обычная ошибка (libVLC сказала error) — берём следующий вариант по списку: у
 * кинобалансеров ссылка на конкретное качество живёт своей жизнью, и мёртвая 1080p
 * ничего не говорит о 720p рядом.
 *
 * А вот [neverStarted] — «поток не отдал НИ ОДНОГО кадра» — это приговор ХОЗЯИНУ, а не
 * качеству: 720p, 480p и 360p у Anixart раздаёт один и тот же solodcdn, и перебирать их
 * по молчащему хосту значит подарить ему ещё по девять секунд на каждое. Поэтому сперва
 * ищется адрес на ДРУГОМ хосте — доклеенная 1080p от AniLibria как раз такой. Замерено
 * 18.08: solodcdn не отдал ни байта за две минуты, cache.libria.fun в тот же вечер
 * начинала играть через 60 мс. Если чужого хоста в списке нет, берём что есть: хуже, чем
 * стоять, не будет.
 */
internal fun nextVariantAfterFailure(
    variants: List<StreamVariant>,
    currentUrl: String,
    neverStarted: Boolean,
): StreamVariant? {
    val current = variants.indexOfFirst { it.url == currentUrl }
    val rest = variants.drop((current + 1).coerceAtLeast(0))
    if (!neverStarted) return rest.firstOrNull()
    return rest.firstOrNull { hostOfUrl(it.url) != hostOfUrl(currentUrl) } ?: rest.firstOrNull()
}

/** Сколько после перемотки сторож зависания молчит: пустой буфер — это не обрыв. */
private const val POST_SEEK_GRACE_NANOS = 9_000_000_000L

/** Предел ожидания ответа от потока команд libVLC на один опрос. */
private const val VLC_CALL_TIMEOUT_MS = 1_500L

/**
 * Сколько ждать свежую ссылку, прежде чем перезапускаться на мёртвой.
 *
 * По журналу перезапрос укладывается в 600 мс; берём вчетверо с запасом. Ожидание
 * отменяемое, поэтому обычный случай не платит ничего: пришла ссылка — поехали.
 */
private const val FRESH_URL_WAIT_MS = 2_500L

/** Столько подряд не ответивших опросов — и экземпляр считается зависшим (≈8 с). */
private const val WEDGED_TICKS_LIMIT = 5

/**
 * Сколько миллисекунд подряд декодер может не отдавать ни одного кадра, пока VLC
 * считает, что играет И ПОЗИЦИЯ СТОИТ.
 *
 * Здесь было 20 секунд, и ровно столько зритель и смотрел на замерший кадр
 * (журнал 17 августа): декодер встал к 00:42:33.9, первая реакция — 00:42:49.0.
 *
 * Двадцать стояло потому, что судили по ОДНОМУ признаку — кадрам, — а он сам по
 * себе врёт. Замерено: 00:24:42+00:24:47 и 00:37:18+00:37:23 — по два нулевых
 * замера подряд, то есть ≥10 с без единого кадра, оба раза вокруг app.hideToTray
 * и оба раза прошло само. Меньше 20 с по кадрам ставить было нельзя.
 *
 * Второй признак снимает этот запрет: ПОЗИЦИЯ. За 25 минут журнала все 419
 * записей stall.recovered — это 1 или 2 тика по 400 мс, то есть позиция нигде не
 * стояла дольше секунды; в обоих безобидных молчаниях декодера она шла. Значит
 * «кадров нет И позиция стоит» — признак, под который за весь журнал попало
 * только само происшествие, и терпеть его 20 секунд незачем.
 *
 * Шесть секунд выбраны по длине сегмента (6.0 с): дольше него, но замер идёт раз
 * в 2 с, поэтому от остановки декодера до вмешательства выходит 6–8 с.
 */
private const val NO_FRAMES_SILENCE_MS = 6_000L

/**
 * Как часто снимается статистика декодера.
 *
 * Было 5 с — при таком шаге шестисекундного порога просто не увидеть, а первый
 * нулевой замер приходит с опозданием до 5 с. Двух секунд хватает: за это время
 * живой поток 720p отдаёт десятки кадров (в журнале 12–150 к/с).
 */
private const val FRAMES_POLL_MS = 2_000L

/** Как часто СПОКОЙНАЯ статистика попадает в журнал. Тишина пишется всегда. */
private const val STATS_LOG_INTERVAL_NANOS = 5_000_000_000L

/** Насколько близко libVLC должна подойти к точке возобновления, чтобы считать её взятой. */
private const val RESUME_REACHED_TOLERANCE_MS = 2_000L

/**
 * Насколько ниже точки возобновления должен оказаться поток, чтобы досылать перемотку.
 *
 * Порог грубый нарочно. Отставание в секунды — это обычная жизнь адаптивного HLS, и
 * лезть туда с перемоткой значит платить целым сегментом ни за что. А вот полминуты и
 * больше значат, что `:start-time` не сработала вовсе и серия пошла с начала.
 */
private const val RESUME_RESCUE_GAP_MS = 30_000L

/**
 * С какой миллисекунды начинать воспроизведение.
 *
 * Вынесено из композиции отдельной функцией, потому что именно здесь жила ошибка
 * «нажал „следующая серия“ — открылась почти с конца», и проверить её иначе нечем.
 *
 * Правило одно и оно главнее прочих: НОВАЯ СЕРИЯ НАЧИНАЕТСЯ ТОЛЬКО СО СВОЕЙ ТОЧКИ.
 * Раньше первой в списке стояла ветка `resumeNext`, а этот флаг поднимают сторож
 * зависания, сторож «нет кадров», перебор качеств и восстановление поверхности —
 * все они срабатывают в том числе у конца серии. Стоило флагу дожить до смены
 * серии, и новая уходила на [position] предыдущей, то есть на её финал.
 *
 * [pendingStartMs] подмешивается только там, где серия ТА ЖЕ: это «мы ещё не доехали
 * до точки возобновления», и на чужую серию она не переносится.
 */
internal fun resumeTargetMs(
    firstLoad: Boolean,
    episodeChanged: Boolean,
    resumeNext: Boolean,
    position: Long,
    startPositionMs: Long,
    pendingStartMs: Long,
): Long = when {
    // Другая серия (или первая загрузка) — только её собственная сохранённая точка.
    // Ни position, ни pendingStartMs от прошлой серии тут не при чём.
    episodeChanged -> startPositionMs.coerceAtLeast(0L)
    firstLoad -> maxOf(startPositionMs, pendingStartMs).coerceAtLeast(0L)
    // Та же серия: перезапуск на месте (смена качества, оживление потока).
    resumeNext -> maxOf(position, pendingStartMs).coerceAtLeast(0L)
    else -> maxOf(position, pendingStartMs).coerceAtLeast(0L)
}

/**
 * Сколько залипших экземпляров libVLC ещё освобождается в фоне.
 *
 * Общий на всё приложение, а не на плеер: хоронит ПРЕДЫДУЩИЙ экземпляр, а судить
 * приходится НОВЫЙ — они разные объекты и разные композиции. Пока счётчик не ноль,
 * «поток не начался» ничего не значит: libVLC занята умирающим.
 */
private val reapersInFlight = java.util.concurrent.atomic.AtomicInteger(0)

/**
 * Столько тиков по 400 мс неподвижной позиции — и поток считается оборвавшимся.
 *
 * Обязано превышать длину сегмента раздачи (замерено: 6.0 с), иначе сторож
 * принимает обычную догрузку за обрыв. 30 тиков = 12 секунд, вдвое больше сегмента.
 */
private const val STALL_TICKS_LIMIT = 30

/**
 * A single repeated clock sample is expected when VLC's clock resolution is coarser
 * than the 400 ms UI poll. Log only pauses long enough to have emitted stall.started;
 * otherwise normal playback floods diagnostics with a false recovery every second.
 */
internal fun stallRecoveryIsDiagnostic(stallTicks: Int): Boolean = stallTicks >= 2


private enum class PlayerHudMenu { AUDIO, QUALITY, SOURCE, VOICE, EPISODE, SCALE, SPEED, SUBTITLES, EQUALIZER }

/** Скорости воспроизведения, как на мобильной версии. */
private val SPEED_STEPS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/**
 * Пресеты эквалайзера libVLC, которые есть смысл предлагать аниме-зрителю: ключ — имя
 * пресета в libVLC (см. `libvlc_audio_equalizer_get_preset_name`), подпись — наша.
 * Пустой ключ — эквалайзер снят. «Речь» — пресет «headphones»: он приподнимает
 * средние, и диалоги читаются лучше на ноутбучных динамиках.
 */
private val EQUALIZER_PRESETS = listOf(
    "" to "Выключен",
    "flat" to "Ровный",
    "headphones" to "Наушники · речь разборчивее",
    "soft" to "Мягкий · тише резкие звуки",
    "fullbass" to "Больше баса",
    "fulltreble" to "Больше высоких",
    "pop" to "Поп",
    "rock" to "Рок",
    "live" to "Концерт",
    "largehall" to "Большой зал",
)

/** Пункт «Открыть файл…» в меню субтитров (не id дорожки). */
private const val SUBTITLE_OPEN_FILE = -2
private val SUBTITLE_EXTENSIONS = listOf(".srt", ".ass", ".ssa", ".vtt", ".sub")
/** Сколько висит уведомление на HUD («Снимок сохранён»). */
private const val HUD_NOTICE_MS = 2_500L

/** One selectable episode for the HUD's episode picker. */
data class PlayerEpisode(val number: Int, val title: String, val playable: Boolean = true)

/**
 * A persisted position must carry the identity of the media that produced it.
 *
 * The VLC polling coroutine survives episode recompositions. Passing only position
 * and duration let that coroutine call an old episode lambda, which stored episode
 * 10's time under episode 1. Keeping the key in the event makes a late poll harmless.
 */
/** Питомец в плеере: кто, какого размера и где (доли окна). */
data class PetOverlayOptions(
    val petId: String,
    val scale: Float,
    val x: Float,
    val y: Float,
    val onMove: (Float, Float) -> Unit,
    val session: com.aniblaze.desktop.pet.PetPlaybackSession,
    val speechEnabled: Boolean = true,
    val quietWatching: Boolean = true,
    /** Кулдаун необязательных реплик, мс. */
    val chatterCooldownMs: Long = com.aniblaze.desktop.pet.PetDirector.CHATTER_COOLDOWN_MS,
    /** Память и контекст тайтла. */
    val context: com.aniblaze.desktop.pet.PetPlayerContext = com.aniblaze.desktop.pet.PetPlayerContext(),
)

data class PlaybackCheckpoint(
    val mediaKey: Any?,
    val positionMs: Long,
    val durationMs: Long,
    val watchedDeltaMs: Long = 0L,
)

/**
 * libVLC can consume pointer messages in the native child window it attaches to
 * [Canvas], before AWT creates a MouseEvent. Reading the Windows button state gives
 * the player a reliable fallback without installing a global hook.
 */
private object NativePointer {
    private interface User32 : Library {
        fun GetAsyncKeyState(virtualKey: Int): Short
        fun SystemParametersInfoW(action: Int, parameter: Int, value: IntByReference, flags: Int): Boolean
    }

    private val user32: User32? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) null
        else runCatching { Native.load("user32", User32::class.java) }.getOrNull()
    }

    fun leftButtonState(): Int =
        runCatching { user32?.GetAsyncKeyState(0x01)?.toInt()?.and(0xffff) ?: 0 }.getOrDefault(0)

    /**
     * True when the window hosting [component] is the active (foreground) one.
     *
     * [leftButtonState] reads the GLOBAL mouse state, so without this check a click
     * inside ANY other application that happens to sit over the video area counted as
     * a click on the video — two of them seeked -5s at random. Gate every polled click
     * on our window actually being the one the user is clicking in.
     */
    fun isWindowActive(component: java.awt.Component): Boolean = runCatching {
        val window = javax.swing.SwingUtilities.getWindowAncestor(component)
        window != null && window.isActive
    }.getOrDefault(false)

    val available: Boolean get() = user32 != null

    fun systemAnimationsEnabled(): Boolean? = runCatching {
        val value = IntByReference()
        if (user32?.SystemParametersInfoW(0x1042, 0, value, 0) == true) value.value != 0 else null
    }.getOrNull()
}

/**
 * Video player with two ways to get the picture on screen, chosen by
 * [composeVideo] (Настройки → Воспроизведение).
 *
 * NATIVE (`composeVideo = false`): libVLC renders into a native AWT surface. Cheap
 * — decoding and presentation stay on VLC's own path with no per-frame JVM work —
 * but the surface is a heavyweight window that Windows paints ABOVE all Compose
 * content. The controls therefore cannot overlay it: a strip of the window has to
 * be carved out for them, the video lives in what is left (measured: canvas 863 px
 * tall inside a ~1040 px area), and opening a picker shrinks it again to 616.
 *
 * COMPOSE (`composeVideo = true`): frames come back through [VideoFrameSink] and
 * Compose draws them. The controls overlay the video, so the picture fills the
 * whole window and never resizes, and a rebuffer leaves the last frame on screen
 * instead of black. An earlier attempt at this path was measured at 4–6 new
 * frames/s and abandoned; see [VideoFrameSink] for what made it expensive and what
 * this one does instead. The rate is logged as `player.render.rate` so the choice
 * stays a measurement rather than a belief.
 *
 * A single player instance is reused for the whole screen; changing the url
 * (episode or dub switch) just stops and re-plays it OFF the UI thread, so
 * switching voiceover no longer freezes the window.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VlcPlayerView(
    variants: List<StreamVariant>,
    referer: String?,
    modifier: Modifier = Modifier,
    startPositionMs: Long = 0L,
    preferredQuality: String? = null,
    preferredDub: String? = null,
    initialVolume: Int = 100,
    /** Пресет эквалайзера libVLC (см. EQUALIZER_PRESETS); пусто — без эквалайзера. */
    audioPreset: String = "",
    onAudioPresetChange: (String) -> Unit = {},
    openingRange: OpeningRange? = null,
    // Эндинг (AniSkip). Кнопка «Пропустить эндинг» ведёт себя как у опенинга.
    endingRange: OpeningRange? = null,
    /** Рекап (AniSkip `recap`): кнопка «Пропустить рекап», как у опенинга; только точные тайминги. */
    recapRange: OpeningRange? = null,
    // Автопропуск опенинга (Настройки → Воспроизведение). false = на старте
    // опенинга всплывает HUD с кнопкой.
    autoSkipOpening: Boolean = false,
    // То же для эндинга. Прыжок идёт на КОНЕЦ интервала титров, а не сразу к
    // следующей серии: после титров у части тайтлов есть сцена. Если титры и есть
    // конец серии, остаток доигрывает штатно и срабатывает обычный автопереход.
    autoSkipEnding: Boolean = false,
    autoDetectTimings: Boolean = false,
    autoTimingContext: AutoTimingContext? = null,
    // Рисовать кадры средствами Compose (панель поверх видео) вместо нативной
    // поверхности VLC. См. комментарий к функции.
    composeVideo: Boolean = true,
    // Улучшение картинки (VideoEnhance.Level.key): GPU CAS в Compose-режиме и
    // штатный `sharpen` video-filter в нативном выводе libVLC.
    enhanceLevel: String = "off",
    // «Закрепить панель» — не прятать её по таймеру.
    hudPinned: Boolean = false,
    onToggleHudPin: () -> Unit = {},
    // Что показать подписью в окошке «картинка в картинке» (название и серия).
    pipTitle: String = "",
    // Комментарии зрителей поверх кадра (см. DanmakuOverlay). Пустой список = нечего
    // показывать; выключенная настройка = не показывать вовсе.
    comments: List<com.aniblaze.aggregator.model.TitleComment> = emptyList(),
    commentsState: RealCommentState = RealCommentState.READY,
    commentsEnabled: Boolean = false,
    commentsRate: String = "normal",
    commentsOpacity: Float = 0.85f,
    commentsFontSize: Int = 14,
    commentsMoving: Boolean = true,
    /** Серия для отбора комментариев (0 = фильм, серий нет). */
    commentsEpisode: Int = 0,
    commentsFreshOnly: Boolean = false,
    // Симулятор живого чата справа от плеера (см. ChatPanel и ChatEngine). Берёт
    // тексты из тех же [comments] и из своих наборов реплик.
    chat: ChatOptions = ChatOptions(),
    // Поток умер и перезапуск той же ссылки не помог: у балансеров ссылка
    // подписана и протухает, поэтому нужна НОВАЯ, а не повтор старой.
    onStreamDead: () -> Unit = {},
    onQualityChange: (String) -> Unit = {},
    onDubChange: (String) -> Unit = {},
    onVolumeChange: (Int) -> Unit = {},
    onProgress: (PlaybackCheckpoint) -> Unit = {},
    onEnded: (mediaKey: Any?) -> Unit = {},
    fullscreen: Boolean = false,
    videoVisible: Boolean = true,
    // Window hidden to tray: pause — audio must not keep playing "from nowhere".
    // Deliberately does NOT auto-resume on reopen; the user presses play.
    suspended: Boolean = false,
    mediaKey: Any? = variants,
    onToggleFullscreen: () -> Unit = {},
    // Stream-level source / voice pickers surfaced on the HUD. The same pickers
    // exist on the player's top panel, but heavyweight video paints above any
    // overlapping Compose (interop blending is off), so the HUD below the video
    // is the only place where these buttons are guaranteed to be visible.
    sources: List<String> = emptyList(),
    activeSource: String? = null,
    onSourceChange: (String?) -> Unit = {},
    voices: List<Translation> = emptyList(),
    activeVoiceId: Int? = null,
    onVoiceChange: (Int) -> Unit = {},
    // Per-dub view counts for THIS title from Anixart, keyed by dub name — the
    // percentage fallback when the playing source (Kodik, balancer, cinema…)
    // reports its translations without any view statistics of its own.
    voiceShares: Map<String, Long> = emptyMap(),
    // «Случайное аниме после последней серии»: HUD toggle; null hides the button
    // (cinema — there is no "next anime" to hop to).
    autoSwitchRandom: Boolean = false,
    onToggleAutoSwitch: (() -> Unit)? = null,
    // Episode navigation on the HUD: which episode is playing, the full list to pick
    // from, and prev/next. The top panel carries the same controls but is painted over
    // by the heavyweight video, so the HUD is where they are actually usable.
    episodes: List<PlayerEpisode> = emptyList(),
    activeEpisode: Int? = null,
    /** Питомец поверх кадра; null — выключен в настройках или это кино. */
    pet: PetOverlayOptions? = null,
    /** Сколько серий в тайтле всего по каталогу (0 = неизвестно); см. [episodeCounterLabel]. */
    episodeTotal: Int = 0,
    onEpisodeChange: (Int) -> Unit = {},
    // Rollback / fallback message from the stream resolver (a source or voice pick
    // that could not be honoured). Rendered inside the HUD — the only Compose area
    // guaranteed to stay visible below the heavyweight video surface.
    streamNotice: String? = null,
) {
    // The shared factory (initialized on the warm-up thread at app start). If the
    // player was opened before warm-up finished, initialize HERE — but on IO, never
    // on the UI thread: this call is the measured 17s freeze when the cache is cold.
    var sharedFactory by remember { mutableStateOf(VlcRuntime.peek()) }
    var vlcFailed by remember { mutableStateOf(VlcRuntime.failed()) }
    if (sharedFactory == null && !vlcFailed) {
        LaunchedEffect(Unit) {
            val got = withContext(kotlinx.coroutines.Dispatchers.IO) { VlcRuntime.acquire() }
            sharedFactory = got
            vlcFailed = got == null
        }
    }
    if (vlcFailed) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Не найден VLC media player.", color = Color.White)
                Text("Установите бесплатный VLC (videolan.org) и перезапустите AniBlaze.", color = Color.Gray)
            }
        }
        return
    }
    val readyFactory = sharedFactory
    if (readyFactory == null) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("Запуск плеера…", color = Color.White, modifier = Modifier.padding(top = 10.dp))
            }
        }
        return
    }

    // Режим фиксируется на время жизни экрана: переключение на лету означало бы
    // пересоздание видеовыхода прямо посреди воспроизведения.
    val overlay = remember { composeVideo }
    val requestedEnhance = VideoEnhance.Level.of(enhanceLevel)
    // В Compose эффект меняется прямо у слоя и не должен перезапускать поток. Нативный
    // filter является опцией media, поэтому только там уровень входит в ключ play.
    val nativeEnhance = if (overlay) VideoEnhance.Level.OFF else requestedEnhance
    /** Номер живого экземпляра проигрывателя и всех принадлежащих ему ресурсов. */
    var playerGeneration by remember { mutableStateOf(0) }
    var appliedNativeEnhance by remember(playerGeneration) {
        mutableStateOf<VideoEnhance.Level?>(null)
    }
    val frameSink = remember(playerGeneration) { VideoFrameSink(playerGeneration) }
    DisposableEffect(frameSink) {
        onDispose { frameSink.close() }
    }

    val videoCanvas = remember {
        Canvas().apply {
            background = java.awt.Color.BLACK
            isFocusable = false
        }
    }
    // vlcj attaches the native video output immediately before media.play().
    // SwingPanel inserts its AWT peer asynchronously, so starting earlier can
    // leave audio playing while the video surface failed to attach.
    //
    // В режиме наложения ждать нечего: буфер в памяти готов сразу, нативного
    // окна нет вовсе — и вместе с ним нет ни потери поверхности, ни парковки,
    // ни повторного подключения.
    var surfaceEverReady by remember { mutableStateOf(overlay) }
    var surfaceReadyGeneration by remember { mutableStateOf(if (overlay) 1 else 0) }
    // Set when the canvas loses its native peer (SwingPanel removed on a tab
    // switch). libVLC keeps rendering into the dead HWND, so on return a plain
    // surface re-attach is NOT enough — the media must be re-played at the same
    // position to rebuild the video output. Minimize/restore does NOT destroy
    // the peer (displayable stays true) and therefore never sets this.
    var surfaceWasLost by remember { mutableStateOf(false) }
    DisposableEffect(videoCanvas, overlay) {
        if (overlay) return@DisposableEffect onDispose { }
        val wasReady = AtomicBoolean(false)
        var lastSurfaceState = ""
        fun refreshSurfaceState() {
            // vlcj needs both a native peer and a component that is actually on
            // screen. Starting when it is merely displayable makes VLC decode and
            // play audio into a Canvas that has not been attached to SwingPanel's
            // visible hierarchy yet, leaving that Canvas permanently black.
            if (!videoCanvas.isDisplayable) surfaceWasLost = true
            val ready = videoCanvas.isDisplayable && videoCanvas.isShowing
            val state = "displayable=${videoCanvas.isDisplayable}; showing=${videoCanvas.isShowing}; visible=${videoCanvas.isVisible}; size=${videoCanvas.width}x${videoCanvas.height}; ready=$ready"
            if (state != lastSurfaceState) {
                lastSurfaceState = state
                PlayerDiagnostics.log("surface.state", state)
            }
            if (ready && wasReady.compareAndSet(false, true)) {
                surfaceEverReady = true
                surfaceReadyGeneration += 1
            } else if (!ready) {
                wasReady.set(false)
            }
        }
        // DISPLAYABILITY_CHANGED is emitted first and SHOWING_CHANGED follows
        // after SwingPanel becomes visible. The old listener watched only the
        // first event, evaluated isShowing=false and never tried again.
        val relevantChanges = (
            HierarchyEvent.DISPLAYABILITY_CHANGED or HierarchyEvent.SHOWING_CHANGED
        ).toLong()
        val listener = java.awt.event.HierarchyListener { event ->
            if (event.changeFlags and relevantChanges != 0L) refreshSurfaceState()
        }
        videoCanvas.addHierarchyListener(listener)
        PlayerDiagnostics.log("surface.listener.added")
        // Runs after SwingPanel's pending AWT insertion; this is an event-order
        // guarantee, not a timing delay.
        EventQueue.invokeLater(::refreshSurfaceState)
        onDispose {
            PlayerDiagnostics.log("surface.listener.removed")
            videoCanvas.removeHierarchyListener(listener)
        }
    }
    // Process-shared factory (see VlcRuntime) — nothing heavyweight happens here.
    val factory = readyFactory
    val videoSurface = remember(factory, videoCanvas, overlay, frameSink) {
        if (overlay) {
            factory.videoSurfaces().newVideoSurface(
                object : BufferFormatCallback {
                    // Формат исходника один в один: масштабирование делает Compose
                    // при отрисовке, и делает его на GPU.
                    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat =
                        RV32BufferFormat(sourceWidth, sourceHeight)

                    override fun allocatedBuffers(buffers: Array<out java.nio.ByteBuffer>) = Unit
                },
                RenderCallback { _, buffers, format ->
                    frameSink.accept(buffers[0], format.width, format.height)
                },
                true,
            ).also { PlayerDiagnostics.log("vlc.surface", "created=callback; mode=compose") }
        } else {
            factory.videoSurfaces().newVideoSurface(videoCanvas).also {
                PlayerDiagnostics.log("vlc.surface", "created=${it.javaClass.simpleName}; canvas=${videoCanvas.javaClass.simpleName}")
            }
        }
    }
    val player = remember(playerGeneration) {
        val p = factory.mediaPlayers().newEmbeddedMediaPlayer()
        p.videoSurface().set(videoSurface)
        PlayerDiagnostics.log("player.init", "generation=$playerGeneration; key=$mediaKey; variants=${variants.size}")
        p
    }
    // The media requested by Compose and the media currently owned by libVLC are
    // briefly different during an episode switch. Long-lived listeners/pollers must
    // persist against the latter, never against whichever episode the UI now shows.
    val activeMediaKey = remember(playerGeneration) { AtomicReference<Any?>(null) }
    val playbackEpochs = remember(playerGeneration) { PlaybackEpochGuard() }
    var confirmedEpoch by remember(playerGeneration) { mutableStateOf<PlaybackEpoch?>(null) }
    val latestMediaKey by rememberUpdatedState(mediaKey)
    var timelineMediaKey by remember { mutableStateOf<Any?>(null) }
    val confirmedProgress = remember { ConfirmedPlaybackProgress() }
    val latestOnEnded by rememberUpdatedState(onEnded)
    val latestOnProgress by rememberUpdatedState(onProgress)

    // БЕЗ КЛЮЧА, и это принципиально: сбрасываются они эффектом ниже.
    //
    // Ключ `remember(mediaKey)` тут выглядит очевидным решением и ЛОМАЕТ плеер.
    // remember с новым ключом создаёт НОВЫЙ объект состояния, а цикл опроса и
    // слушатели событий libVLC живут дольше композиции и продолжают писать в
    // СТАРЫЙ. Экран читает новый — и видит нули. Проверено на живом плеере:
    // длительность переставала приходить вовсе, `slider.scrubEnd | lengthMs=0`, а
    // раз длина ноль, то любое перетаскивание ползунка складывалось в `play.seek |
    // targetMs=0` — перемотка «прыгала в начало».
    //
    // Поэтому объект состояния один на всю жизнь плеера, а смена серии просто
    // обнуляет его значения.
    var position by remember { mutableStateOf(0L) }
    var length by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    // Observational state for the companion; does not change VLC recovery policy.
    var petUserPaused by remember(mediaKey) { mutableStateOf(false) }
    var petStreamFailed by remember(mediaKey) { mutableStateOf(false) }
    var petPauseStartedAt by remember(mediaKey) { mutableStateOf(0L) }
    LaunchedEffect(mediaKey, petUserPaused, playing, ended) {
        if (petUserPaused && !playing && !ended) {
            if (petPauseStartedAt == 0L) petPauseStartedAt = System.currentTimeMillis()
        } else petPauseStartedAt = 0L
    }
    var controlsVisible by remember { mutableStateOf(true) }
    var activeHudMenu by remember { mutableStateOf<PlayerHudMenu?>(null) }
    // Keep menu content alive during the exit transition. The old implementation
    // cleared both booleans first, so AnimatedVisibility had nothing left to fade.
    var displayedHudMenu by remember { mutableStateOf<PlayerHudMenu?>(null) }
    var hudHovered by remember { mutableStateOf(false) }
    var hudPressed by remember { mutableStateOf(false) }
    val lastControlsActivity = remember { AtomicLong(System.nanoTime()) }
    val lastVideoClick = remember { AtomicLong(0L) }
    val videoClickSequence = remember { AtomicLong(0L) }
    val skipFeedbackSequence = remember { AtomicLong(0L) }
    val skipBackImage = remember { createSkipFeedbackImage(-5) }
    val skipForwardImage = remember { createSkipFeedbackImage(5) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableStateOf(0f) }
    /** Измеренная высота панели управления, px. Столько окна под неё и резервируем. */
    var controlBarHeight by remember { mutableStateOf(0) }
    // Цель незавершённой перемотки, либо null.
    //
    // libVLC применяет setTime АСИНХРОННО, и на адаптивном HLS это занимает секунды:
    // всё это время status().time() продолжает возвращать СТАРОЕ значение. Опрос
    // ниже затирал им position, и следующее нажатие «+5 с» отсчитывалось от старой
    // точки — со стороны это выглядело как «перемотка возвращается на тот же
    // тайминг» (в логе видно серию play.seek с почти одинаковым targetMs подряд).
    // Пока цель не достигнута, position показывает ЕЁ, а не то, что успел догнать VLC.
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    val seekIssuedAt = remember { AtomicLong(0L) }
    // Token from VideoFrameSink.pauseForSeek(). A delayed completion of seek A
    // must never re-enable frame capture while seek B is still reconfiguring VLC.
    var frameSeekToken by remember { mutableStateOf(0L) }
    /**
     * Последняя позиция, которую libVLC подтвердила своими глазами.
     *
     * Переживает замену экземпляра НАМЕРЕННО (remember без ключа): именно её теряли,
     * когда плеер бросали с resumeAt=0, потому что свежий ещё не успел доложить,
     * где он. Плеер сменить не жалко, точку просмотра — жалко.
     */
    val lastGoodPosition = remember { AtomicLong(0L) }
    /** Когда в последний раз отдавали libVLC команду играть. */
    val lastPlayIssuedAt = remember { AtomicLong(0L) }
    /** Этот запуск дошёл до настоящего воспроизведения (позиция поехала). */
    var playbackEverStarted by remember { mutableStateOf(false) }
    var pausedForAnalysis by remember { mutableStateOf(false) }
    var nativeSeekable by remember { mutableStateOf(false) }
    // Серия быстрых нажатий склеивается в ОДНУ перемотку: каждый setTime на HLS
    // заставляет демультиплексор заново тянуть и декодировать сегмент, поэтому три
    // подряд были втрое медленнее одной.
    val pendingSeekJob = remember { AtomicReference<Job?>(null) }
    var volume by remember { mutableStateOf(initialVolume.coerceIn(0, 100).toFloat()) }
    var muted by remember { mutableStateOf(false) }
    var volumeApply by remember { mutableStateOf(0) }
    LaunchedEffect(position) { if (position > 0 && position % 10_000 < 400) PlayerDiagnostics.log("state.position", "ms=$position; length=$length") }
    LaunchedEffect(length) { if (length > 0) PlayerDiagnostics.log("state.length", "ms=$length") }
    LaunchedEffect(playing) { PlayerDiagnostics.log("state.playing", "value=$playing") }
    LaunchedEffect(ended) { PlayerDiagnostics.log("state.ended", "value=$ended") }
    LaunchedEffect(controlsVisible) { PlayerDiagnostics.log("state.controlsVisible", "value=$controlsVisible") }
    LaunchedEffect(activeHudMenu) { PlayerDiagnostics.log("state.activeHudMenu", "value=${activeHudMenu ?: "null"}") }
    LaunchedEffect(hudHovered) { if (hudHovered) PlayerDiagnostics.log("state.hudHovered", "value=true") }
    LaunchedEffect(hudPressed) { if (hudPressed) PlayerDiagnostics.log("state.hudPressed", "value=true") }
    LaunchedEffect(scrubbing) { PlayerDiagnostics.log("state.scrubbing", "value=$scrubbing") }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // libVLC is stateful native code. Every call (including getters) must run on the
    // same thread: a Mutex around only mutations still allowed polling, controls and
    // release to race each other. The UI only submits commands to this executor.
    val playerExecutor = remember(playerGeneration) {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "aniblaze-vlc-$playerGeneration").apply { isDaemon = true }
        }
    }
    val playerDispatcher = remember(playerGeneration) { playerExecutor.asCoroutineDispatcher() }
    // Эквалайзер — свойство экземпляра плеера: ставится при создании и при смене
    // пресета, переживает смену серии. Пустой пресет — снять эквалайзер.
    LaunchedEffect(player, audioPreset) {
        withContext(playerDispatcher) {
            runCatching {
                val eq = audioPreset.takeIf { it.isNotBlank() && it in factory.equalizer().presets() }
                    ?.let { factory.equalizer().newEqualizer(it) }
                player.audio().setEqualizer(eq)
                PlayerDiagnostics.log("audio.equalizer", "preset=${audioPreset.ifBlank { "off" }}; applied=${eq != null || audioPreset.isBlank()}")
            }.onFailure { PlayerDiagnostics.failure("audio.equalizer", it) }
        }
    }
    /** Единственный владелец stop/release для этого поколения. */
    val releaseStarted = remember(playerGeneration) { AtomicBoolean(false) }
    val volumeCommand = remember(playerGeneration) {
        ConflatedIntCommand(playerExecutor) { value -> player.audio().setVolume(value) }
    }
    // Перемотка склеивается ТАМ ЖЕ, где громкость, — на потоке команд. Отмены
    // отложенной задачи мало: доехавший до потока setTime отменить уже нечем, а на
    // HLS он занимает поток на секунды (см. ConflatedLongCommand).
    val seekCommand = remember(playerGeneration) {
        ConflatedLongCommand(playerExecutor) { target -> player.controls().setTime(target) }
    }

    // The VLC Canvas is a native heavyweight window. The Swing host is hidden
    // while a browser tab is inactive; when it becomes visible again explicitly
    // attach its current native peer before resuming video output.
    // Leaving the player (back button / tab switch) keeps this composable alive, so
    // onDispose never runs and the resume point was whatever the 5s poll last wrote.
    // Persist immediately the moment the screen stops being shown.
    LaunchedEffect(videoVisible) {
        if (!videoVisible) {
            confirmedProgress.drain()?.let(latestOnProgress)
        }
    }

    LaunchedEffect(surfaceReadyGeneration, videoVisible) {
        // Наложение: буфер в памяти никуда не «отваливается», подключать нечего.
        if (overlay) return@LaunchedEffect
        PlayerDiagnostics.log("surface.visibility", "generation=$surfaceReadyGeneration; playerVisible=$videoVisible")
        if (videoVisible && surfaceReadyGeneration > 0) withContext(playerDispatcher) {
            runCatching {
                player.videoSurface().set(videoSurface)
                player.videoSurface().attachVideoSurface()
                PlayerDiagnostics.log("surface.attached", "generation=$surfaceReadyGeneration")
            }.onFailure {
                PlayerDiagnostics.failure("surface.attach.failed", it)
                System.err.println("AniBlaze: VLC surface visibility change failed: ${it.message}")
            }
        }
    }
    LaunchedEffect(initialVolume) {
        val saved = initialVolume.coerceIn(0, 100)
        if (volume.toInt() != saved) volume = saved.toFloat()
        if (!muted) {
            volumeApply = 4
            volumeCommand.submit(saved)
        }
    }
    // Selected quality url. Defaults to the saved quality for this title (else best).
    // Switching re-plays at the same position.
    //
    // КЛЮЧ — ПЕРВЫЙ АДРЕС СПИСКА, А НЕ САМ СПИСОК. Это та же болезнь, что разобрана
    // выше у position/length, и она пережила тот разбор здесь.
    //
    // Список вариантов подменяется не только сменой серии или озвучки: примерно через
    // 180 мс после старта к нему ДОКЛЕИВАЕТСЯ 1080p от AniLibria (stream.hiRes в
    // PlayerScreen). remember с новым ключом создаёт НОВЫЙ объект состояния, а цикл
    // опроса живёт дольше композиции и продолжает писать в СТАРЫЙ — то есть в никуда.
    // Журнал 18.08 показывает цену посекундно:
    //
    //     23:13:46.808  play.request        720p, p12.solodcdn.com
    //     23:13:46.988  stream.hiRes        added=1        ← список подменён
    //     23:13:55.826  play.neverStarted   afterMs=9017
    //     23:13:55.827  play.errorFallback  attempt=1; nextQuality=480p
    //     ← ни одной state.currentUrl, ни одной play.request: 111 секунд тишины
    //     23:15:47.033  vlc.error                          ← libVLC сдалась сама
    //
    // Наружу это ровно «при переключении серии обрыв потока»: «Подготовка потока…» на
    // две минуты, хотя лестница попыток отработала и решение приняла — просто записать
    // его было некуда. AniLibria тем же вечером играла без единого сбоя, и не по
    // случайности: у неё hi-res не доклеивается (источник и так 1080p), список не
    // подменялся, состояние не терялось.
    //
    // Доклеивание ставит новый вариант В КОНЕЦ, значит первый адрес меняется тогда и
    // только тогда, когда сменился сам поток. Ровно это и должно сбрасывать выбор.
    val variantsKey = variantsIdentity(variants)
    val startVariant = remember(variantsKey) { variants.firstOrNull { it.quality == preferredQuality } ?: variants.firstOrNull() }
    var currentUrl by remember(variantsKey) { mutableStateOf(startVariant?.url.orEmpty()) }
    LaunchedEffect(currentUrl) { PlayerDiagnostics.log("state.currentUrl", "value=$currentUrl") }
    var currentQuality by remember(variantsKey) { mutableStateOf(startVariant?.quality.orEmpty()) }
    LaunchedEffect(currentQuality) { PlayerDiagnostics.log("state.currentQuality", "value=$currentQuality") }
    // The master without the "#h=" quality marker — dub audio tracks live in this
    // master, so the dub selection must survive a quality change (which only swaps
    // the marker, not the master).
    val baseUrl = currentUrl.substringBefore("#h=")
    // Set true right before switching quality so playback resumes at the same
    // spot; a new episode/dub (new [variants]) starts from the beginning.
    //
    // Тоже без ключа и по той же причине, что position выше: в него пишут сторожа,
    // которые живут дольше композиции. Сбрасывается эффектом смены серии.
    var resumeNext by remember { mutableStateOf(false) }
    // Точка, с которой ДОЛЖНА начаться эта серия. Живёт, пока воспроизведение до
    // неё не дошло, и поэтому переживает повторный запуск эффекта.
    //
    // Эффект play срабатывает дважды подряд при открытии серии (пересоздание
    // нативной поверхности между двумя проходами композиции — в логе видно два
    // play.request с разницей 9 мс). Второй проход считал «серия та же, значит
    // продолжаем с текущей позиции», а позиция в этот момент ещё 0 — и «продолжить
    // с 8:57» превращалось в «сначала» при каждом открытии.
    // Stable holder: the polling coroutine must not keep an old episode's MutableState.
    var pendingStartMs by remember { mutableStateOf(startPositionMs) }
    // ВАЖНО: «доехали до точки» проверяется НЕ ЗДЕСЬ, а в цикле опроса, и по позиции
    // САМОЙ libVLC.
    //
    // Здесь стоял эффект, следивший за `position` — а это переменная ИНТЕРФЕЙСА, и в
    // неё пишут авансом: запуск сразу ставит ползунок на точку возобновления, чтобы
    // он не показывал 0:00 лишнюю секунду. Получался замкнутый круг: поставили
    // ползунок на 18:59 — сторож увидел «position равна точке» — стёр точку. Дальше
    // новый экземпляр докладывал свою настоящую позицию (ноль), и продолжать было уже
    // неоткуда. В журнале это выглядело так:
    //
    //     23:07:50.768  play.decision  resumeAt=1139388; pendingStart=1139388
    //     23:07:51.668  play.decision  resumeAt=0;       pendingStart=0
    //
    // Настоящим доказательством приезда может быть только позиция от libVLC.
    // «Скрыть комментарии» из панели плеера. Живёт до конца сеанса и НЕ пишется в
    // настройки: это «сейчас не мешай», а не «выключить функцию» — для второго есть
    // галка в настройках.
    var commentsHidden by remember { mutableStateOf(false) }
    // «Скрыть чат» — тоже до конца сеанса и тоже мимо настроек, по той же причине.
    var chatHidden by remember { mutableStateOf(false) }
    /**
     * Курсор стоит в поле ввода чата.
     *
     * Пока он там, горячие клавиши плеера обязаны молчать. Обработчик клавиш висит
     * на КОРНЕ кадра и работает на предварительном проходе, то есть получает нажатие
     * РАНЬШЕ поля ввода, — без этого флага пробел в слове ставил бы видео на паузу,
     * «f» разворачивал на весь экран, а стрелки перематывали серию.
     */
    var chatTyping by remember { mutableStateOf(false) }
    var replayNonce by remember { mutableStateOf(0) }
    /**
     * Адрес, на котором поток умер, — пока хозяин не пришлёт другой, играть нечем.
     *
     * Подпись у ссылки живёт временным окном (хвост `…:2026080516`), и когда окно
     * закрылось, повторный запуск НА ТОМ ЖЕ адресе обречён. По журналу это стоило
     * дороже всего: `play.request` со старым адресом уходил через 2 мс после начала
     * перезапроса, свежая ссылка приезжала на 580 мс позже, а девять секунд отсечки
     * «поток не стартовал» уже тикали на заведомо мёртвом адресе. И так на каждой
     * озвучке и каждом качестве — под минуту замершего кадра.
     */
    var deadUrl by remember(mediaKey) { mutableStateOf<String?>(null) }
    /** Сколько вариантов уже отбраковано после ошибки открытия этой серии. */
    var errorFallbacks by remember(mediaKey) { mutableStateOf(0) }
    /** Просили ли уже свежую ссылку для этой серии (см. handlePlaybackError). */
    var freshUrlTried by remember(mediaKey) { mutableStateOf(false) }
    /** Когда отдали ссылку в libVLC. 0 = ждать нечего. */
    var playStartedAt by remember { mutableStateOf(0L) }
    /**
     * Позиция, о которой libVLC доложила ПЕРВОЙ после запуска. -1 — ещё не докладывала.
     *
     * Именно доложила, а не та, что мы просили: libVLC умеет начать не там, где её
     * просили, и живое воспроизведение с чужого места — всё равно живое. Разбор с
     * выдержками из журнала — у [playbackNeverStarted].
     *
     * Без ключа и рядом с [playStartedAt] намеренно: обе живут дольше композиции и
     * должны меняться одной парой.
     */
    var playStartedFrom by remember { mutableStateOf(-1L) }
    /** Досылали ли уже перемотку на потерянную точку возобновления (см. resume.rescue). */
    var resumeRescued by remember { mutableStateOf(false) }
    // 0..100 cache fill reported by libVLC. Below 100 means the picture is still
    // being buffered (the black-screen-with-sound state after a long seek).
    var bufferPercent by remember { mutableStateOf(100f) }
    // How far past the playhead is actually downloaded, in ms. Derived from libVLC's
    // byte counters (bytes pulled from the network minus bytes already fed to the
    // demuxer, over the demux byte-rate) — the `buffering` event alone only fires
    // while the cache fills, so a bar based on it never moved during playback.
    // Video scaling: FIT keeps libVLC's letterbox, FILL crops the picture to the
    // canvas aspect ratio so a wide window shows no side bars (user-requested
    // zoom for 4:3 / pillarboxed content, especially in fullscreen).
    var fillMode by remember { mutableStateOf(false) }
    // Zoom multiplier over the fitted size: 1f = "вписать" (libVLC's setScale(0)),
    // 1.25f = the picture 25% larger than fit, cropped by the surface edges — the
    // web-player style масштаб the user asked for. Mutually exclusive with FILL.
    var videoZoom by remember { mutableStateOf(1f) }
    // Скорость воспроизведения. Сбрасывается на 1x при смене медиа — «ускорил одну
    // серию» не должно молча распространяться на следующую.
    var playbackSpeed by remember(mediaKey) { mutableStateOf(1f) }
    // Keyed on `length` too: a fresh play() resets libVLC's rate to 1x, and length
    // becoming known is the signal that the new media has actually been parsed.
    LaunchedEffect(playbackSpeed, length) {
        if (length <= 0) return@LaunchedEffect
        withContext(playerDispatcher) {
            runCatching { player.controls().setRate(playbackSpeed) }
                .onFailure { PlayerDiagnostics.failure("speed.apply", it) }
        }
        PlayerDiagnostics.log("player.speed", "rate=$playbackSpeed")
    }
    LaunchedEffect(replayNonce) { if (replayNonce > 0) PlayerDiagnostics.log("state.replayNonce", "value=$replayNonce") }
    var loadedMediaKey by remember { mutableStateOf<Any?>(null) }

    // Must precede play/skip effects. On 2026-09-03 the old late reset ran AFTER
    // ending.autoSkip and saved 23:40, while episode 9 was actually at 22:14.
    LaunchedEffect(mediaKey) {
        val previous = timelineMediaKey
        if (previous == mediaKey) return@LaunchedEffect
        confirmedProgress.drain()?.let(latestOnProgress)
        confirmedProgress.reset()
        PlayerDiagnostics.log("player.episodeReset", "from=$previous; to=$mediaKey; savedPosition=$position; length=$length")
        playbackEpochs.invalidate()
        confirmedEpoch = null
        playbackEverStarted = false
        pausedForAnalysis = false
        nativeSeekable = false
        pendingSeekJob.getAndSet(null)?.cancel()
        seekCommand.clear()
        if (overlay && frameSeekToken > 0L) frameSink.resumeAfterSeek(frameSeekToken)
        frameSeekToken = 0L
        timelineMediaKey = mediaKey
        position = startPositionMs.coerceAtLeast(0)
        length = 0L
        lastGoodPosition.set(0L)
        pendingStartMs = startPositionMs.coerceAtLeast(0)
        seekTarget = null
        scrubbing = false
        scrubValue = 0f
        ended = false
        resumeNext = false
    }

    /**
     * Бросает зависший проигрыватель и поднимает новый с того же места.
     *
     * Зачем это нужно. Все команды libVLC идут через ОДИН поток, и он умеет вставать
     * намертво: `stop()` ждёт, пока завершится оборванное сетевое чтение, а оно может
     * не завершиться. В логе это выглядит однозначно — опрос позиции и статистика
     * замолкают одновременно, перемотка пишется в лог, но ничего не делает, а живым
     * остаётся единственный цикл, который к VLC не обращается вовсе. Все прежние
     * сторожа зависания сидели ЗА этим же потоком и потому не срабатывали никогда:
     * они ждали ответа от того, кто уже не отвечал.
     *
     * Разбудить застрявший вызов нельзя — это нативный код. Единственный выход:
     * оставить его умирать в фоне и продолжить на новом экземпляре.
     */
    fun abandonWedgedPlayer(reason: String, resumeAt: Long) {
        if (!releaseStarted.compareAndSet(false, true)) {
            PlayerDiagnostics.log("vlc.abandon.skip", "reason=$reason; generation=$playerGeneration; releaseAlreadyStarted=true")
            return
        }
        val doomed = player
        val doomedExecutor = playerExecutor
        PlayerDiagnostics.log("vlc.abandon", "reason=$reason; generation=$playerGeneration; resumeAt=$resumeAt")
        // Callback старого VLC может ещё приходить, но после close он больше не
        // публикует и не трогает кадры нового поколения.
        frameSink.close()
        confirmedProgress.drain()?.let(latestOnProgress)
        confirmedProgress.interrupt()
        playbackEpochs.invalidate()
        confirmedEpoch = null
        nativeSeekable = false
        pendingSeekJob.getAndSet(null)?.cancel()
        seekCommand.clear()
        // Точка возобновления переживает подмену: новый экземпляр начнёт оттуда же.
        pendingStartMs = resumeAt
        // И РОВНО ЗДЕСЬ ЖЕ position обнуляется — иначе точку тут же стирает сторож
        // «доехали до места возобновления».
        //
        // Тот сторож сравнивает position с pendingStartMs, а после броска они РАВНЫ:
        // оба взяты у покойника. Он честно решал «доехали» и обнулял точку, после
        // чего новый экземпляр стартовал с нуля. В журнале это выглядело так:
        //
        //     23:01:05  vlc.abandon    resumeAt=989614      (16:29)
        //     23:01:06  play.decision  resumeAt=0
        //
        // То есть поток встал на 16:29, а продолжился с начала серии. Позиция
        // брошенного экземпляра ничего не значит: точка теперь живёт только в
        // pendingStartMs, а на ползунок её вернёт сам запуск.
        position = 0L
        loadedMediaKey = null
        // Незавершённая перемотка принадлежала прошлому экземпляру — иначе ползунок
        // остался бы приколот к её цели, а в логе висело бы «expired».
        seekTarget = null
        // ОТСЧЁТ «поток не начался» ТОЖЕ ПРИНАДЛЕЖАЛ ПРОШЛОМУ ЭКЗЕМПЛЯРУ.
        //
        // Без этой строки новый проигрыватель судили по часам покойника: сторож
        // зависания перезапускал поток, вызов застревал, через 10 с мы бросали
        // экземпляр — и на ПЕРВОМ ЖЕ опросе нового `playStartedAt` был уже
        // девятисекундной давности. В логе это видно посекундно:
        //
        //     12:09:52.176  vlc.abandon        reason=commandThreadStuck
        //     12:09:52.176  streamDead         retries=0   → перезапросить ссылку
        //     12:09:52.258  play.neverStarted  afterMs=9712   ← через 82 мс
        //     12:09:52.258  streamDead         retries=1   → сменить озвучку
        //
        // То есть первая ступень лестинцы («просто перезапросить ссылку, та же
        // озвучка, то же место») отменялась через 82 мс после старта и не успевала
        // ничего попробовать НИ РАЗУ. Наружу это выглядело как «серия сама
        // переключилась на другую озвучку и началась сначала».
        playStartedAt = 0L
        playerGeneration++
        // Поток команд встаёт не сам по себе, а потому что оборвалось сетевое чтение.
        // Значит ссылка мертва, и поднимать новый экземпляр на неё же бессмысленно —
        // ровно это и произошло: новый проигрыватель стартовал и не получил ни байта.
        deadUrl = currentUrl
        onStreamDead()
        // Освобождение — в отдельном потоке: он, скорее всего, застрянет там же, и
        // ждать его нельзя ни секунды.
        //
        // Счётчик нужен НЕ для ожидания, а чтобы не судить новый экземпляр, пока
        // старый ещё жив. ЗАМЕРЕНО по журналу: между «бросаем» и «освободили» прошло
        //
        //     12:56:44.576  vlc.abandon
        //     12:57:05.221  vlc.abandon.released     — 20.6 секунды
        //
        // и всё это время новый проигрыватель не мог начать: libVLC ещё занята
        // умирающим. А отсечка «поток не начался» срабатывает через девять секунд —
        // то есть новый ГАРАНТИРОВАННО объявлялся мёртвым, хотя с ним всё в порядке.
        // Отсюда и шли подряд «подготовка потока»: перезапрос ссылки → та же участь →
        // смена озвучки → ещё заход. См. использование в опросе ниже.
        reapersInFlight.incrementAndGet()
        val queued = runCatching {
            // stop/release ставятся В ТОТ ЖЕ поток после зависшего вызова. Вызывать
            // release параллельно с ним из reaper-thread небезопасно: libVLC не
            // допускает два одновременных владельца одного MediaPlayer.
            doomedExecutor.execute {
                try {
                    runCatching { doomed.controls().stop() }
                    runCatching { doomed.release() }
                } finally {
                    val left = reapersInFlight.decrementAndGet()
                    PlayerDiagnostics.log("vlc.abandon.released", "reason=$reason; reapersLeft=$left")
                }
            }
            doomedExecutor.shutdown()
            true
        }.getOrElse { error ->
            PlayerDiagnostics.failure("vlc.abandon.queueFailed", error)
            false
        }
        if (!queued) {
            val left = reapersInFlight.decrementAndGet()
            runCatching { doomedExecutor.shutdownNow() }
            PlayerDiagnostics.log("vlc.abandon.unreleased", "reason=$reason; reapersLeft=$left")
        }
    }

    // Dub / audio tracks (озвучка) libVLC reports once the media is parsed — the
    // cinema dubs are audio groups inside the one HLS master, so switching озвучка
    // is a libVLC audio-track switch. Reset on [baseUrl], with a stable state holder
    // for the long-lived polling coroutine; a quality change keeps the same master.
    var audioTracks by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    LaunchedEffect(audioTracks) { PlayerDiagnostics.log("state.audioTracks", "count=${audioTracks.size}; tracks=${audioTracks.map { it.second }.joinToString(",")}") }
    var selectedDub by remember { mutableStateOf<String?>(preferredDub?.ifBlank { null }) }
    LaunchedEffect(baseUrl) {
        audioTracks = emptyList()
        selectedDub = preferredDub?.ifBlank { null }
    }
    // Long-lived watchdogs must recover the current URL/episode callback, not the first one they captured.
    val latestAbandon by rememberUpdatedState<(String, Long) -> Unit> { reason, at -> abandonWedgedPlayer(reason, at) }
    LaunchedEffect(selectedDub) { PlayerDiagnostics.log("state.selectedDub", "value=${selectedDub ?: "null"}") }
    var dubApply by remember { mutableStateOf(0) }
    // Субтитры: дорожки из потока (у аниме с «raw» их нет) плюс внешний файл
    // (.srt/.ass/.vtt), подключённый через диалог. −1 — выключены. subtitleApply
    // держит опрос дорожек ещё несколько тиков после выбора, чтобы состояние
    // сошлось с libVLC (внешний файл появляется в списке не мгновенно).
    var subtitleTracks by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var selectedSubtitle by remember { mutableStateOf(-1) }
    var subtitleApply by remember { mutableStateOf(0) }
    LaunchedEffect(baseUrl) { subtitleTracks = emptyList(); selectedSubtitle = -1; subtitleApply = 6 }
    LaunchedEffect(selectedSubtitle, subtitleTracks) {
        PlayerDiagnostics.log("state.subtitles", "selected=$selectedSubtitle; tracks=${subtitleTracks.map { it.second }.joinToString(",")}")
    }
    // Уведомление на HUD («Снимок сохранён», «Субтитры: …») — гаснет само.
    var hudNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hudNotice) { if (hudNotice != null) { delay(HUD_NOTICE_MS); hudNotice = null } }

    // Periodic native VLC statistics make freezes and "audio only" failures
    // diagnosable from the user's real stream without recording video frames.
    LaunchedEffect(playerGeneration) {
        run {
            var previousDecoded = 0
            var previousDisplayed = 0
            var previousLost = 0
            var previousPosition = -1L
            var framelessSilenceMs = 0L
            var previousNs = System.nanoTime()
            var lastStatsLogAt = 0L
            var everDecoded = false
            var statisticsEpoch: PlaybackEpoch? = null
            while (true) {
                delay(FRAMES_POLL_MS)
                val now = System.nanoTime()
                val measurement = withContext(playerDispatcher) {
                    playbackEpochs.activeEpoch() to runCatching { player.media().info().statistics() }.getOrNull()
                }
                if (!playbackEpochs.accepts(measurement.first, latestMediaKey)) continue
                val stats = measurement.second ?: continue
                if (statisticsEpoch != measurement.first) {
                    statisticsEpoch = measurement.first
                    previousDecoded = 0
                    previousDisplayed = 0
                    previousLost = 0
                    previousPosition = -1L
                    framelessSilenceMs = 0L
                    everDecoded = false
                    previousNs = now - FRAMES_POLL_MS * 1_000_000
                }
                val seconds = (now - previousNs) / 1_000_000_000.0
                fun fps(value: Int, old: Int) = (value - old) / seconds
                // ТОЛЬКО в файл диагностики. Здесь же стоял System.err.printf — и
                // именно он вешал приложение НАМЕРТВО: у собранного jpackage-ом GUI-
                // приложения stderr уходит в пайп, который никто не читает. Через
                // ~40 минут буфер пайпа заполнялся, очередная запись блокировалась
                // навсегда — а этот цикл живёт на потоке Compose, то есть вставал
                // весь UI. Дамп потоков показал EDT в FileOutputStream.writeBytes.
                // Замер участился с пяти секунд до двух — иначе сторож ниже не
                // успевает уложиться в шесть секунд тишины. А вот ЗАПИСЬ осталась
                // редкой: строки статистики и так самые многочисленные в журнале
                // (около трёхсот за двадцать пять минут), и учащение раздуло бы его
                // втрое без всякой пользы. Пишем раз в пять секунд, но НЕПРЕМЕННО
                // пишем тишину — ради неё журнал и читают.
                val decodedFps = fps(stats.decodedVideo(), previousDecoded)
                val silent = stats.decodedVideo() == previousDecoded
                if (silent || now - lastStatsLogAt >= STATS_LOG_INTERVAL_NANOS) {
                    lastStatsLogAt = now
                    PlayerDiagnostics.log(
                        "vlc.statistics",
                        "decoded=${"%.2f".format(decodedFps)}; displayed=${"%.2f".format(fps(stats.picturesDisplayed(), previousDisplayed))}; lost=${stats.picturesLost() - previousLost}",
                    )
                }
                // Сторож «играет, но кадров нет».
                //
                // Тот сторож, что выше по файлу, следит за ПОЗИЦИЕЙ и требует
                // `position > 0`. Ровно поэтому он проспал самый длинный простой,
                // который вообще был в журнале: после подмены озвучки libVLC сказал
                // vlc.playing и buffering 0 %, позиция осталась на нуле — и две
                // минуты подряд шло только
                //
                //     12:09:57 … 12:11:59  decoded=0,00; displayed=0,00  (25 записей)
                //
                // Ни одной строки сторожа за это время нет: следить было некому.
                // Кончилось само, «Invalid memory access» от брошенного экземпляра.
                // На экране это и есть «Загрузка… 0 %», которая висит минутами.
                //
                // Здесь считается ровно то, что тогда И БЫЛО видно в журнале: сколько
                // подряд замеров декодер отдал ноль кадров, пока VLC считает, что
                // играет. Четыре замера по пять секунд — двадцать секунд тишины;
                // обычная догрузка сегмента (6 с) столько не длится.
                val decodedNow = stats.decodedVideo()
                // «Отдавал кадры хоть раз» — с открытия ЭТОГО потока. Один экземпляр
                // VLC переиспользуется; на новом playback epoch baseline сбрасывается выше.
                if (decodedNow != previousDecoded && previousDecoded >= 0) {
                    everDecoded = true
                    // Кадры пошли — сторожу «поток не начался» больше нечего ждать.
                    //
                    // Позиции для этого мало. Живой прогон 19.08 (см.
                    // AnixartPlaybackLiveTest): libVLC отдала 44 кадра за 791 мс, а
                    // status().time() всё ещё стояла на заданной `:start-time`. Кадры —
                    // самое прямое доказательство жизни потока, какое вообще есть, и
                    // приговор без них выносить незачем.
                    playStartedAt = 0L
                }
                val tick = framelessWatchdogTick(
                    playing = playing,
                    decodedGrew = decodedNow != previousDecoded,
                    positionMoved = position != previousPosition,
                    seekInFlight = seekTarget != null,
                    silenceMs = framelessSilenceMs,
                    sincePreviousTickMs = (now - previousNs) / 1_000_000L,
                    everStarted = everDecoded,
                    // ПО ЧАСАМ, а не по seekTarget: тот снимается на приземлении
                    // позиции, а кадры после перемотки идут ещё несколько секунд.
                    sinceSeekNanos = now - seekIssuedAt.get(),
                )
                framelessSilenceMs = tick.silenceMs
                // Тишина копится и у экземпляра, который ещё ни разу не декодировал:
                // сторож кадров его не судит (и правильно), а судить надо — иначе
                // «играет, позиция идёт, кадров нет» висит бесконечно. См.
                // framesNeverArrived.
                if (!everDecoded && playing && seekTarget == null) {
                    framelessSilenceMs += (now - previousNs) / 1_000_000L
                }
                if (framesNeverArrived(playing, everDecoded, seekTarget != null, framelessSilenceMs)) {
                    PlayerDiagnostics.log(
                        "frames.never",
                        "afterSec=${framelessSilenceMs / 1000}; position=$position; length=$length",
                    )
                    latestAbandon("framesNever", maxOf(position, lastGoodPosition.get()))
                    return@LaunchedEffect
                }
                if (tick.replay) {
                    PlayerDiagnostics.log(
                        "frames.missing",
                        "afterSec=${framelessSilenceMs / 1000}; position=$position; length=$length",
                    )
                    // ЭКЗЕМПЛЯР ЗАМЕНЯЕТСЯ, А НЕ ПЕРЕЗАПУСКАЕТСЯ НА МЕСТЕ.
                    //
                    // Здесь стояло «сыграть тот же адрес заново» (resumeNext +
                    // replayNonce), и журнал 17 августа показывает цену посекундно:
                    //
                    //     00:42:49.003  play.request     тот же url, тот же экземпляр
                    //     00:42:50.871  vlc.callTimeout  через 1.9 с
                    //     00:43:02.484  vlc.abandon      reason=commandThreadStuck
                    //
                    // Поток команд к этому моменту уже висел на сетевом чтении
                    // мёртвого сегмента, а перезапуск начинается с controls().stop(),
                    // то есть с ещё одного вызова в тот же нативный код. Экземпляр
                    // всё равно пришлось бросить — только на 13 секунд позже, и всё
                    // это время зритель смотрел на тот же замерший кадр.
                    //
                    // Ровно этот вывод сторож зависшей позиции сделал раньше и на
                    // трёх случаях (см. комментарий у stallNoProgress). Приходим к
                    // тому же итогу сразу: точка сохраняется, ссылка помечается
                    // мёртвой, экран просит свежую, старый экземпляр дохоранивается
                    // в фоне.
                    //
                    // Потолок повторов (был NO_FRAMES_REPLAY_LIMIT=3, на случай
                    // дорожки БЕЗ ВИДЕО) больше не нужен: у играющей дорожки без
                    // видео позиция ИДЁТ, а сторож теперь требует, чтобы она стояла.
                    // Точка берётся НЕ ХУЖЕ последней достоверной. Тот же журнал
                    // 01:09:37.8 показывает цену: экземпляр бросили с resumeAt=0,
                    // потому что новый плеер ещё не доложил позицию, — и живая точка
                    // 503878 просто исчезла. Плеер сменить не жалко, точку — жалко.
                    latestAbandon("noFrames", maxOf(position, lastGoodPosition.get()))
                    return@LaunchedEffect
                }
                previousDecoded = decodedNow
                previousPosition = position
                previousDisplayed = stats.picturesDisplayed()
                previousLost = stats.picturesLost()
                previousNs = now
            }
        }
    }

    /**
     * libVLC не открыл поток.
     *
     * У кинобалансеров ссылка на конкретное качество живёт своей жизнью: подписана,
     * привязана к IP, и мёртвая ссылка на 1080p ничего не говорит о 720p рядом. В
     * логе это выглядело как три захода подряд «resolve.success → vlc.error», а на
     * экране — вечное «Загрузка… 0 %». Перебираем оставшиеся варианты молча и лишь
     * исчерпав их, просим источник о новой ссылке.
     */
    fun handlePlaybackError(neverStarted: Boolean = false) {
        petStreamFailed = true
        // Поток не отдал НИ ОДНОГО кадра — это про хост, а не про качество: ссылки
        // всех качеств выдаёт один и тот же сервер. Проверено по логу — перебор
        // 1080p→720p→480p упирался в ту же мёртвую отдачу. Сначала просим новую
        // ссылку и только если и она молчит, идём перебирать варианты.
        if (neverStarted && !freshUrlTried) {
            freshUrlTried = true
            PlayerDiagnostics.log("play.errorFallback", "neverStarted=true; askingForFreshUrl")
            deadUrl = currentUrl
            onStreamDead()
            return
        }
        val next = nextVariantAfterFailure(variants, currentUrl, neverStarted)
        if (next != null && errorFallbacks < variants.size) {
            errorFallbacks++
            PlayerDiagnostics.log(
                "play.errorFallback",
                "attempt=$errorFallbacks; nextQuality=${next.quality}; nextHost=${hostOfUrl(next.url)}",
            )
            resumeNext = position > RESUME_MIN_MS
            currentQuality = next.quality
            currentUrl = next.url
        } else {
            PlayerDiagnostics.log("play.errorFallback", "exhausted=${variants.size}; askingForFreshUrl")
            errorFallbacks = 0
            onStreamDead()
        }
    }
    // Успешный старт снимает счётчик: следующая поломка снова получит полный перебор.
    LaunchedEffect(playing) { if (playing && errorFallbacks > 0) errorFallbacks = 0 }

    /**
     * Всегда свежий обработчик поломки — для тех, кто живёт дольше композиции.
     *
     * Цикл опроса и слушатели событий libVLC заводятся один раз на поколение плеера и
     * держат ТУ функцию, что была на момент их запуска, вместе со всем, что она
     * замкнула. Список вариантов она замыкает тоже — а он подменяется на каждом
     * доклеивании hi-res, и замкнутый оказывается БЕЗ 1080p от AniLibria, то есть без
     * единственного адреса на живом хосте. Видно это было и по журналу: `vlc.error`
     * печатал адрес ПРЕДЫДУЩЕЙ серии, потому что слушатель читал устаревшее значение.
     *
     * Тот же приём, что у latestOnEnded/latestOnProgress ниже.
     */
    val latestPlaybackError by rememberUpdatedState<(Boolean) -> Unit> { neverStarted ->
        handlePlaybackError(neverStarted)
    }
    /** Адрес, который играем ПРЯМО СЕЙЧАС, — для тех же долгожителей (см. выше). */
    val latestUrl by rememberUpdatedState(currentUrl)

    DisposableEffect(playerGeneration) {
        // Natural end-of-media → autoplay next. `finished` fires ONLY on a real end,
        // never on our stop()/play() during a quality or dub switch, so it can't
        // false-advance mid-episode.
        val endListener = object : uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter() {
            override fun opening(mediaPlayer: MediaPlayer) {
                if (!releaseStarted.get()) PlayerDiagnostics.log("vlc.opening")
            }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                if (releaseStarted.get()) return
                // Drives the HUD buffering indicator: a long seek refills the cache
                // from scratch and the picture stays black until it completes, so the
                // user needs to SEE that it is loading rather than frozen.
                bufferPercent = newCache
                if (newCache == 0f || newCache >= 100f) PlayerDiagnostics.log("vlc.buffering", "percent=$newCache")
            }
            override fun playing(mediaPlayer: MediaPlayer) { if (!releaseStarted.get()) PlayerDiagnostics.log("vlc.playing") }
            override fun paused(mediaPlayer: MediaPlayer) { if (!releaseStarted.get()) PlayerDiagnostics.log("vlc.paused") }
            override fun stopped(mediaPlayer: MediaPlayer) { if (!releaseStarted.get()) PlayerDiagnostics.log("vlc.stopped") }
            override fun videoOutput(mediaPlayer: MediaPlayer, count: Int) {
                if (!releaseStarted.get()) PlayerDiagnostics.log("vlc.videoOutput", "count=$count")
            }
            override fun error(mediaPlayer: MediaPlayer) {
                if (releaseStarted.get()) return
                val errorEpoch = playbackEpochs.activeEpoch()
                PlayerDiagnostics.log("vlc.error", "url=${latestUrl.take(160)}")
                scope.launch {
                    if (playbackEpochs.accepts(errorEpoch, latestMediaKey)) latestPlaybackError(false)
                }
            }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                if (!releaseStarted.get()) PlayerDiagnostics.log("vlc.length", "ms=$newLength")
            }
            override fun finished(mediaPlayer: MediaPlayer) {
                if (releaseStarted.get()) return
                val finishedKey = activeMediaKey.get()
                val finishedEpoch = playbackEpochs.activeEpoch()
                PlayerDiagnostics.log("vlc.finished", "mediaKey=$finishedKey")
                scope.launch {
                    if (!playbackEpochs.accepts(finishedEpoch, latestMediaKey)) return@launch
                    ended = true
                    playing = false
                    if (finishedKey != null && length > 0) {
                        confirmedProgress.drain()?.let(latestOnProgress)
                        latestOnProgress(PlaybackCheckpoint(finishedKey, length, length))
                    }
                    latestOnEnded(finishedKey)
                }
            }
        }
        playerExecutor.execute {
            if (!releaseStarted.get()) player.events().addMediaPlayerEventListener(endListener)
        }
        onDispose {
            // Snapshot the current sink on the Compose thread; the queued native
            // teardown runs later on VLC's executor and must not read Compose state.
            // Save synchronously before native teardown; a late old executor must
            // not overwrite the checkpoint of a newly opened player.
            confirmedProgress.drain()?.let(latestOnProgress)
            PlayerDiagnostics.log("player.dispose", "reason=screenTransition; position=$position; length=$length; playing=$playing; menu=${activeHudMenu}; dub=$selectedDub")
            DisplayAwake.release()
            // Unmap the native video window in the same frame as the Compose
            // teardown: stop()/release() below run asynchronously on the player
            // thread, and a still-mapped native window could outlive its AWT
            // parent and linger as a blank light rectangle over the next screen.
            runCatching { if (!overlay) videoCanvas.isVisible = false }
            frameSink.close()
            if (releaseStarted.compareAndSet(false, true)) {
                // Queue close after every previously submitted operation. This preserves
                // native ownership and prevents release() racing play/status/setTrack.
                val queued = runCatching {
                    playerExecutor.execute {
                        val removed = runCatching { player.events().removeMediaPlayerEventListener(endListener) }.isSuccess
                        val progress = "confirmedBeforeDispose"
                        val stopped = runCatching { player.controls().stop() }.isSuccess
                        val released = runCatching { player.release() }.isSuccess
                        PlayerDiagnostics.log("player.release", "listenerRemoved=$removed; progress=$progress; stopped=$stopped; playerReleased=$released")
                    }
                    playerExecutor.shutdown()
                    true
                }.getOrElse { error ->
                    PlayerDiagnostics.failure("player.release.queueFailed", error)
                    false
                }
                if (!queued) runCatching { playerExecutor.shutdownNow() }
            } else {
                PlayerDiagnostics.log("player.release.skip", "generation=$playerGeneration; owner=abandon")
            }
        }
    }

    // A tab switch removes the SwingPanel and destroys the canvas HWND while
    // libVLC keeps decoding into it — coming back shows a black rectangle until
    // the app is restarted. Once the surface is re-attached (effect above runs
    // first on the same player thread), rebuild the video output by re-playing
    // the current media at the current position.
    LaunchedEffect(surfaceReadyGeneration) {
        if (overlay) return@LaunchedEffect
        if (surfaceReadyGeneration > 0 && videoVisible && surfaceWasLost && loadedMediaKey != null) {
            PlayerDiagnostics.log("surface.replayAfterLoss", "generation=$surfaceReadyGeneration; position=$position")
            surfaceWasLost = false
            resumeNext = true
            replayNonce++
        }
    }

    // The one place video geometry is computed: crop (FILL) + scale (fit / zoom %).
    // Reads the Compose state at CALL time (snapshot reads are thread-safe), so the
    // resize listener and the player thread can both call it without stale captures.
    // Zoom > 1 needs the native video size to turn "percent over fit" into libVLC's
    // absolute scale factor; when the dimension isn't known yet (media still
    // opening) it falls back to plain fit and the length-keyed effect below
    // re-applies the zoom once the media is parsed.
    fun applyVideoScale(reason: String) {
        // Наложение: вписать/заполнить/зум считает сам Compose при отрисовке, и
        // считает от РЕАЛЬНОГО размера области, а не от нативного окна.
        if (overlay) return
        runCatching {
            val w = videoCanvas.width.coerceAtLeast(1)
            val h = videoCanvas.height.coerceAtLeast(1)
            if (fillMode) player.video().setCropGeometry("$w:$h") else player.video().setCropGeometry(null)
            val zoom = videoZoom
            if (zoom <= 1.001f) {
                // Fit to the current surface. Without this the picture keeps whatever
                // scale the output was born with and leaves black margins.
                player.video().setScale(0f)
            } else {
                val dim = runCatching { player.video().videoDimension() }.getOrNull()
                val fit = if (dim != null && dim.width > 0 && dim.height > 0) {
                    minOf(w.toFloat() / dim.width, h.toFloat() / dim.height)
                } else 0f
                player.video().setScale(if (fit > 0f) fit * zoom else 0f)
            }
            PlayerDiagnostics.log("video.scale", "reason=$reason; fillMode=$fillMode; zoom=$zoom; canvas=${w}x$h")
        }.onFailure { PlayerDiagnostics.failure("video.scale.failed", it) }
    }
    // Hidden to tray → pause. No auto-resume: reopening finds the video paused
    // exactly where it was.
    LaunchedEffect(suspended) {
        if (suspended && playing) {
            PlayerDiagnostics.log("player.suspend", "reason=windowHidden")
            withContext(playerDispatcher) { runCatching { player.controls().setPause(true) } }
        }
    }

    // Re-applied on surface re-attach (generation) because a rebuilt video output
    // drops both the crop and the scale.
    LaunchedEffect(fillMode, videoZoom, surfaceReadyGeneration) {
        if (!surfaceEverReady) return@LaunchedEffect
        withContext(playerDispatcher) { applyVideoScale("modeChange") }
    }
    // New media parsed (length becomes known) → the native dimension is finally
    // available, so a zoom > 1 chosen earlier can now be honoured.
    LaunchedEffect(length) {
        if (length > 0 && (videoZoom > 1.001f || fillMode)) {
            withContext(playerDispatcher) { applyVideoScale("mediaParsed") }
        }
    }
    // Keep the video output in step with the canvas on EVERY resize, not just while
    // FILL is on. libVLC sizes its vout child window when the output is created and
    // does not re-fit on its own, so after the window changed size (maximise, exit
    // fullscreen, tab layout shift) the picture kept the old, smaller scale and sat in
    // the corner with black space around it.
    DisposableEffect(videoCanvas, overlay) {
        if (overlay) return@DisposableEffect onDispose { }
        val listener = object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                playerExecutor.execute { applyVideoScale("resize") }
            }
        }
        videoCanvas.addComponentListener(listener)
        onDispose { videoCanvas.removeComponentListener(listener) }
    }

    // (Re)play whenever the chosen url changes — off the UI thread so a dub /
    // season / quality switch never blocks composition. When switching quality
    // mid-playback we resume at the same position.
    // playerGeneration в ключах обязателен: после подмены зависшего экземпляра новый
    // проигрыватель пуст, и без перезапуска этого эффекта он бы просто ничего не играл.
    LaunchedEffect(currentUrl, replayNonce, mediaKey, surfaceEverReady, playerGeneration, nativeEnhance) {
        val previousKey = loadedMediaKey
        val nativeEnhanceChanged = !overlay && appliedNativeEnhance != null && appliedNativeEnhance != nativeEnhance
        // Смена фильтра не должна превращать паузу в воспроизведение. Сам поток
        // переоткрывается на той же точке, но после открытия возвращается на паузу.
        val preservePause = nativeEnhanceChanged && !playing
        PlayerDiagnostics.log("play.effect", "currentUrl=${currentUrl.take(80)}; replayNonce=$replayNonce; mediaKey=$mediaKey; surfaceEverReady=$surfaceEverReady; resumeNext=$resumeNext; loadedMediaKey=$previousKey; nativeEnhance=${nativeEnhance.key}; enhanceChanged=$nativeEnhanceChanged")
        // Адрес, на котором уже умерли, второй раз не запускаем — сначала даём хозяину
        // домолчать перезапрос. Ждём отменяемо: как только придёт другая ссылка,
        // эффект перезапустится с ней (currentUrl в ключах), и ожидание оборвётся на
        // полуслове. Не дождались — играем что есть: лучше девять секунд отсечки,
        // чем застыть навсегда, если источник вообще перестал отвечать.
        if (currentUrl.isNotBlank() && currentUrl == deadUrl) {
            PlayerDiagnostics.log("play.awaitFreshUrl", "waitMs=$FRESH_URL_WAIT_MS")
            delay(FRESH_URL_WAIT_MS)
            PlayerDiagnostics.log("play.awaitFreshUrl.timeout", "playing the same url as a last resort")
            deadUrl = null
        }
        // Прошлый запуск ещё открывается — не рушим его ради смены зеркала.
        // Ждём ОТМЕНЯЕМО: придёт третий адрес — эффект перезапустится и ожидание
        // оборвётся; откроется поток — следующая смена адреса пройдёт без задержки.
        if (previousKey == mediaKey && !playbackEverStarted) {
            val since = System.nanoTime() - lastPlayIssuedAt.get()
            if (!shouldRestartPlayback(sameMediaKey = true, sincePreviousPlayNanos = since, previousStarted = false)) {
                val waitMs = (PLAY_SETTLE_MS - since / 1_000_000L).coerceAtLeast(0L)
                PlayerDiagnostics.log("play.settleWait", "waitMs=$waitMs; sinceMs=${since / 1_000_000L}")
                delay(waitMs)
            }
        }
        if (surfaceEverReady && currentUrl.isNotBlank()) {
            val firstLoad = previousKey == null
            val episodeChanged = !firstLoad && previousKey != mediaKey
            val resumeAt = resumeTargetMs(
                firstLoad = firstLoad,
                episodeChanged = episodeChanged,
                resumeNext = resumeNext,
                position = position,
                startPositionMs = startPositionMs,
                pendingStartMs = pendingStartMs,
            )
            val playEpoch = playbackEpochs.prepare(mediaKey)
            confirmedProgress.interrupt()
            confirmedEpoch = null
            pausedForAnalysis = false
            nativeSeekable = false
            playbackEverStarted = false
            pendingSeekJob.getAndSet(null)?.cancel()
            seekCommand.clear()
            PlayerDiagnostics.log("play.decision", "firstLoad=$firstLoad; episodeChanged=$episodeChanged; resumeAt=$resumeAt; resumeNext=$resumeNext; pendingStart=$pendingStartMs")
            resumeNext = false
            loadedMediaKey = mediaKey
            ended = false
            // A "…m3u8#h=720" quality marker caps the adaptive height (keeps the
            // dub audio tracks intact); the bare master streams fully adaptive.
            val maxHeight = currentUrl.substringAfter("#h=", "").toIntOrNull()
            val playUrl = currentUrl.substringBefore("#h=")
            // Адрес пишется ЦЕЛИКОМ. Обрезка на 200 символах стоила дорого: ссылки
            // Kodik длиной 231 символ теряли хвост `…:2026080516/720.mp4:hls:manifest.m3u8`,
            // а по такому огрызку любая проверка возвращает 404 — и разбор упирался
            // в вывод «CDN мёртв» там, где мёртв был только журнал.
            PlayerDiagnostics.log("play.request", "mediaKey=$mediaKey; resumeMs=$resumeAt; visible=$videoVisible; surfaceReady=$surfaceEverReady; url=$playUrl")
            // Отметка нужна защите от двойного запуска (см. shouldRestartPlayback).
            lastPlayIssuedAt.set(System.nanoTime())
            // Отсечка «поток вообще не начался». Мёртвая ссылка кинобалансера не
            // всегда даёт ошибку: в логе бывает vlc.opening, buffering 0 % — и
            // тишина навсегда, decoded=0. Ждать события, которого не будет, нельзя.
            playStartedAt = System.nanoTime()
            // Точку отсчёта возьмём с ПЕРВОГО ответа самой libVLC (см. опрос ниже), а
            // не отсюда: `resumeAt` — это лишь то, о чём мы просим, и просьбу libVLC
            // умеет не выполнить.
            playStartedFrom = -1L
            resumeRescued = false
            // Hide the native window BEFORE the vout is torn down: a freshly
            // created output window paints white for a frame. The poll loop
            // shows it again once the new stream is actually playing.
            runCatching { if (!overlay) videoCanvas.isVisible = false }
            // Ползунок сразу встаёт на точку возобновления: иначе он секунду-две
            // показывает 0:00, и кажется, что серия опять началась сначала.
            if (resumeAt > RESUME_MIN_MS) {
                position = resumeAt
                seekTarget = resumeAt
                seekIssuedAt.set(System.nanoTime())
            }
            withContext(playerDispatcher) {
                    if (!playbackEpochs.isRequested(playEpoch)) return@withContext
                    runCatching { player.controls().stop() }
                    // stop() above belongs to the old key; everything submitted after
                    // this assignment belongs to the newly committed episode.
                    if (!playbackEpochs.activate(playEpoch)) return@withContext
                    activeMediaKey.set(mediaKey)
                    val restoredVolume = if (muted) 0 else volume.toInt()
                    // Set the saved level before play as well: this prevents the
                    // first decoded audio buffer from being emitted at VLC's 100%.
                    runCatching { player.audio().setVolume(restoredVolume) }
                    // The HLS CDNs sign their m3u8 and 403 requests that arrive without a
                    // browser User-Agent (and the balancer Referer) — VLC must echo both
                    // or the stream never starts.
                    val options = buildList {
                        add(":http-user-agent=$STREAM_USER_AGENT")
                        referer?.let { add(":http-referrer=$it") }
                        maxHeight?.let { add(":adaptive-maxheight=$it") }
                        // Возобновление ДО первого кадра. controls().setTime() сразу
                        // после play() молча теряется — вход ещё не открыт: в логе
                        // стоит «resumeMs=6500», а серия при этом пошла с нуля.
                        // :start-time libVLC применяет внутри демультиплексора, ещё
                        // до старта декодирования, поэтому он срабатывает всегда.
                        // Locale.ROOT обязателен: с русской локалью вышла бы запятая,
                        // и libVLC отбросил бы опцию целиком.
                        if (resumeAt > RESUME_MIN_MS) {
                            add(String.format(java.util.Locale.ROOT, ":start-time=%.3f", resumeAt / 1000.0))
                        }
                        // Буфер должен вмещать БОЛЬШЕ ОДНОГО СЕГМЕНТА. Здесь стояло
                        // 800 мс, а сегменты у раздачи — по 6.0 с (замерено по
                        // манифесту: 239 сегментов на 23.9 минуты). Буфер не вмещал
                        // и одного, поэтому поток жил в одной догрузке от опустошения:
                        // в журнале это сплошные «stall.recovered ... 1ticks», а
                        // изредка — полная остановка.
                        //
                        // Прежнее объяснение («2–3 с кэша тормозят перемотку») этим
                        // же комментарием ниже и опровергается: медленная перемотка —
                        // от того, что адаптивный демультиплексор заново качает и
                        // декодирует сегмент, а не от размера кэша. Цена буфера
                        // измерена на живой раздаче: CDN отдаёт 12–33 Мбит/с, то есть
                        // 24 секунды видео приходят за 0.8 с — запас 29-кратный, и
                        // пять секунд буфера набираются примерно за 0.2 с.
                        add(":network-caching=5000")
                        add(":live-caching=1000")
                        add(":http-reconnect")
                        // В callback/Compose режиме резкость применяется GPU-слоем к
                        // самому кадру. В нативном режиме кадр принадлежит libVLC —
                        // здесь подключается его собственный video-filter.
                        addAll(VideoEnhance.vlcOptions(nativeEnhance))
                        // NEVER add :avcodec-hw here. Hardware decoding cannot bind to
                        // the embedded Canvas surface: the video output was created and
                        // torn down in a loop (videoOutput 1→0→1 in the diagnostics log)
                        // and playback ran with sound over a black picture.
                        //
                        // Nothing else belongs here either. Bigger caches, :input-fast-seek,
                        // :clock-jitter/:clock-synchro and :adaptive-logic were all tried
                        // against the slow-seek complaint and measured: libVLC reports the
                        // buffer full ~150ms after a seek while the picture takes 1.4-6s,
                        // because the adaptive demuxer refetches and decodes the segment.
                        // None of these options touch that, and larger caches made it worse.
                    }.toTypedArray()
                    val playAttempt = runCatching { player.media().play(playUrl, *options) }
                    if (playAttempt.getOrDefault(false).not()) {
                        PlayerDiagnostics.failure("play.rejected", playAttempt.exceptionOrNull())
                        System.err.println(
                            "AniBlaze: VLC rejected playback: " +
                                playAttempt.exceptionOrNull()?.message.orEmpty(),
                        )
                    }
                    // play() creates the real audio output asynchronously and that
                    // output may replace the value set above. Apply once now, then
                    // verify it for a few poll ticks after the output exists.
                    runCatching { player.audio().setVolume(restoredVolume) }
                    if (preservePause) runCatching { player.controls().setPause(true) }
                    // A fresh output starts at its own scale and drops the crop —
                    // re-apply the user's fit/fill/zoom, otherwise a new episode or
                    // quality plays in a small corner window.
                    applyVideoScale("postPlay")
                    // Второй заход на случай, если медиа уже открыто (смена качества
                    // или озвучки внутри той же серии) — там :start-time не помогает,
                    // потому что вход не пересоздаётся.
                    if (resumeAt > RESUME_MIN_MS) runCatching { player.controls().setTime(resumeAt) }
            }
            volumeApply = 12
            appliedNativeEnhance = nativeEnhance
            // A fresh play() starts on VLC's default audio track; if the user had
            // chosen a dub, open a short window for the poll loop to re-apply it once
            // the new media has parsed its tracks (bounded — never fights playback).
            if (selectedDub != null) dubApply = 10
        }
        lastControlsActivity.set(System.nanoTime())
        controlsVisible = true
    }

    // Poll playback state for the seek bar / play button.
    LaunchedEffect(playerGeneration) {
        // Момент последней записи точки просмотра. По ЧАСАМ, а не по числу тиков:
        // тик может задержаться на полторы секунды ожидания ответа libVLC, и счёт
        // тиками растягивал окно потерь непредсказуемо.
        var lastProgressSaveAt = 0L
        var lastPos = 0L
        var stallTicks = 0
        var wasPlaying = false
        // Срок, до которого перезапуск обязан ожить. 0 = ничего не ждём.
        var recoveryDeadline = 0L
        // Подряд идущие тики, на которые поток команд VLC не ответил.
        var wedgedTicks = 0
        // Сколько всего отсрочено новому экземпляру, пока хоронится прошлый.
        var reaperGrantedNanos = 0L
        var pollEpoch: PlaybackEpoch? = null
        while (true) {
            val needTracks = audioTracks.isEmpty() || dubApply > 0 || subtitleApply > 0
            val desiredVolume = if (muted) 0 else volume.toInt()
            val shouldApplyVolume = volumeApply > 0
            // Опрос ОТПРАВЛЯЕТСЯ заданием и ждётся по часам, а не через withContext.
            //
            // Здесь стоял withTimeoutOrNull вокруг withContext — и он не работал. Пока
            // блок уже выполняется в нативном коде, отмена ему не доставляется:
            // withContext ждёт завершения блока, а оно не наступает, поэтому и таймаут
            // не срабатывал никогда. В логе это было видно прямо — опрос замолкал
            // после перезапуска потока, и ни одной строки vlc.callTimeout за ним.
            //
            // Future можно просто БРОСИТЬ. Ожидание идёт через delay, то есть ничего
            // не блокирует, и цикл остаётся живым, что бы ни творилось внутри libVLC.
            val request = runCatching {
                playerExecutor.submit(
                    java.util.concurrent.Callable {
                        if (shouldApplyVolume) runCatching {
                            if (player.audio().volume() != desiredVolume) {
                                player.audio().setVolume(desiredVolume)
                            }
                        }
                        val p = player.status().time().coerceAtLeast(0)
                        val l = player.status().length().coerceAtLeast(0)
                        val isPlaying = player.status().isPlaying
                        val rawTracks = if (needTracks) runCatching {
                            player.audio().trackDescriptions()?.filter { it.id() >= 0 }
                                ?.map { it.id() to it.description().orEmpty() }
                        }.getOrNull().orEmpty() else emptyList()
                        val currentTrack = if (needTracks) runCatching { player.audio().track() }.getOrDefault(-1) else -1
                        val subTracks = if (needTracks) runCatching {
                            player.subpictures().trackDescriptions()?.filter { it.id() >= 0 }
                                ?.map { it.id() to it.description().orEmpty() }
                        }.getOrNull().orEmpty() else emptyList()
                        val currentSub = if (needTracks) runCatching { player.subpictures().track() }.getOrDefault(-1) else -1
                        NativeSnapshot(p, l, isPlaying, rawTracks, currentTrack, playbackEpochs.activeEpoch(),
                            player.status().state().name == "PAUSED", player.status().isSeekable,
                            subTracks, currentSub)
                    },
                )
            }.getOrNull()
            val deadline = System.nanoTime() + VLC_CALL_TIMEOUT_MS * 1_000_000L
            while (request != null && !request.isDone && System.nanoTime() < deadline) delay(60)
            val native = if (request != null && request.isDone) {
                runCatching { request.get() }.getOrNull()
            } else {
                request?.cancel(false)
                null
            }
            if (native == null) {
                // Поток команд не ответил. Один-два раза — просто долгая операция,
                // но если молчит секундами, значит застрял внутри libVLC и сам уже
                // не выберется: бросаем экземпляр и продолжаем на новом.
                //
                // ВО ВРЕМЯ ПЕРЕМОТКИ ТЕРПЕНИЯ БОЛЬШЕ. setTime на HLS законно занимает
                // поток команд на секунды, и прежний общий предел бросал живой плеер
                // ровно тогда, когда зритель активно таскал ползунок.
                // Признак «идёт перемотка» берётся ПО ЧАСАМ, а не по seekTarget.
                // seekTarget снимается только в ветке удачного опроса — а её-то сейчас
                // и нет, так что по нему терпение стало бы бесконечным и зависший
                // внутри setTime плеер не бросили бы никогда.
                val seeking = System.nanoTime() - seekIssuedAt.get() < SEEK_GRACE_NANOS
                val limit = wedgeTickLimit(seekInFlight = seeking)
                wedgedTicks++
                if (wedgedTicks == 1) {
                    PlayerDiagnostics.log(
                        "vlc.callTimeout",
                        "position=$position; seeking=${seekTarget != null}; limit=$limit",
                    )
                }
                if (wedgedTicks >= limit) {
                    // Та же защита точки, что и у сторожа кадров: свежий экземпляр
                    // мог ещё не доложить позицию, и брать её как есть — значит
                    // отправить зрителя в начало серии.
                    latestAbandon("commandThreadStuck", maxOf(position, lastGoodPosition.get()))
                    return@LaunchedEffect
                }
                // Точка просмотра пишется ДАЖЕ СЕЙЧАС. Позиция интерфейса известна и
                // без ответа libVLC, а зависание — единственный момент, когда её
                // потерять по-настоящему обидно: зритель закроет застрявшее окно и
                // вернётся туда, где был до перемотки.
                if (shouldPersistProgress(position, length, System.nanoTime() - lastProgressSaveAt, false)) {
                    lastProgressSaveAt = System.nanoTime()
                    PlayerDiagnostics.log("progress.saveWhileStuck", "positionMs=$position; lengthMs=$length")
                    confirmedProgress.drain()?.let(latestOnProgress)
                }
                delay(400)
                continue
            }
            wedgedTicks = 0
            if (!playbackEpochs.accepts(native.epoch, latestMediaKey)) {
                delay(400)
                continue
            }
            confirmedEpoch = native.epoch
            pausedForAnalysis = native.paused
            nativeSeekable = native.seekable
            if (pollEpoch != native.epoch) {
                pollEpoch = native.epoch
                lastPos = native.position
                stallTicks = 0
                wasPlaying = false
                recoveryDeadline = 0L
            }
            if (volumeApply > 0) volumeApply--
            // Пока перемотка не приземлилась, native.position — устаревшее эхо
            // прежней точки. Принимаем его обратно, только когда VLC действительно
            // доехал до цели (или явно не доедет — тогда по таймауту, чтобы
            // «залипшая» цель не заморозила ползунок навсегда).
            val target = seekTarget
            confirmedProgress.observe(native.epoch?.mediaKey, native.position, native.length,
                native.playing, System.nanoTime(),
                seekInFlight = target != null)
            if (target != null) {
                val landed = kotlin.math.abs(native.position - target) < SEEK_LANDED_TOLERANCE_MS
                val expired = System.nanoTime() - seekIssuedAt.get() > SEEK_TIMEOUT_NANOS
                if (landed || expired) {
                    PlayerDiagnostics.log(
                        "seek.settled",
                        "target=$target; native=${native.position}; landed=$landed; expired=$expired",
                    )
                    seekTarget = null
                    if (!scrubbing) position = native.position
                    // Frame ownership is protected by leases, not a timed blackout.
                    val settledFrameToken = frameSeekToken
                    if (overlay && settledFrameToken > 0L) {
                        frameSink.resumeAfterSeek(settledFrameToken)
                    }
                } else if (!scrubbing) {
                    position = target
                }
            } else if (!scrubbing) {
                position = native.position
            }
            length = native.length
            playing = native.playing
            if (native.playing && native.position != lastPos && native.position > 0) petStreamFailed = false
            if (native.position > 0) lastGoodPosition.set(native.position)
            if (playStartedFrom < 0) {
                // Первый ответ libVLC об этом запуске — он и есть точка отсчёта.
                playStartedFrom = native.position
            } else if (native.position != playStartedFrom) {
                // Поток ПОШЁЛ: позиция сдвинулась с того места, где libVLC себя
                // застала. С этого мгновения смена адреса (качество, озвучка) законна
                // и ждать ей нечего — защита от двойного запуска снимается, а сторожу
                // «поток не начался» больше нечего ждать.
                //
                // Ни с нулём, ни с ЗАПРОШЕННОЙ точкой сравнивать нельзя: разбор обеих
                // поломок — у playbackNeverStarted. Коротко: `:start-time` libVLC
                // рапортует ещё до первого байта, а иногда не применяет вовсе и играет
                // с другого места — и то и другое живое воспроизведение.
                playbackEverStarted = true
                playStartedAt = 0L
            }
            // Точка возобновления снимается ТОЛЬКО по позиции самой libVLC: она
            // означает «плеер действительно доиграл дотуда», в отличие от переменной
            // интерфейса, которую мы ставим авансом (см. комментарий у pendingStartMs).
            if (pendingStartMs > 0 && native.position > pendingStartMs - RESUME_REACHED_TOLERANCE_MS) {
                PlayerDiagnostics.log("resume.reached", "pendingStartMs=$pendingStartMs; native=${native.position}")
                pendingStartMs = 0
            } else if (
                pendingStartMs > 0 && !resumeRescued && playbackEverStarted && seekTarget == null && !scrubbing &&
                native.playing && native.position in 1 until (pendingStartMs - RESUME_RESCUE_GAP_MS)
            ) {
                // ВОЗОБНОВЛЕНИЕ ПОТЕРЯЛОСЬ — досылаем перемотку один раз.
                //
                // Обычно точку ставит `:start-time`, и этого хватает. Но 19.08 после
                // замены зависшего экземпляра libVLC её просто не применила:
                //
                //     00:30:23.690  play.request    resumeMs=815815   (13:35)
                //     00:30:54.503  stall.started   position=87000    (1:27)
                //
                // Поток при этом живой, идёт — просто с начала серии. Молча оставить
                // так значит потерять двенадцать минут просмотра, а `setTime` сразу
                // после play() тут не поможет: он уже отработал вхолостую, пока вход
                // ещё не был открыт.
                //
                // Один раз за запуск и через тот же conflated-командник, что и обычная
                // перемотка: очередь к libVLC не должна расти (см. ConflatedLongCommand).
                resumeRescued = true
                PlayerDiagnostics.log("resume.rescue", "pendingStartMs=$pendingStartMs; native=${native.position}")
                position = pendingStartMs
                seekTarget = pendingStartMs
                seekIssuedAt.set(System.nanoTime())
                seekCommand.submit(pendingStartMs)
            }
            // The canvas is hidden while the video output rebuilds (any re-play);
            // show it again only once frames are actually flowing, so the fresh
            // vout window's brief white flash can never reach the screen.
            if (!overlay && native.playing && videoVisible && !videoCanvas.isVisible) videoCanvas.isVisible = true
            // Stall watchdog: VLC still says "playing" but the position hasn't moved for
            // ~8s and we're not at the end → the stream froze. Re-play it at the same
            // spot automatically (the manual "switch quality and back" the user did).
            // seekTarget != null — позиция намеренно «стоит» на цели перемотки;
            // это не зависание, и перезапускать поток на ней нельзя.
            //
            // Мало этого: даже ПОСЛЕ приземления перемотки буфер пуст по
            // определению, и позиция ещё какое-то время не двигается, пока VLC
            // дотягивает сегмент. Без этой отсрочки сторож принимал нормальную
            // догрузку за обрыв и перезапускал поток целиком — а полный перезапуск
            // HLS занимает секунды, то есть «лечение» было втрое дольше болезни.
            // В логе это выглядело как «перемотал — и всё встало намертво».
            val sinceSeek = System.nanoTime() - seekIssuedAt.get()
            if (playing && !scrubbing && seekTarget == null && sinceSeek > POST_SEEK_GRACE_NANOS && position > 0) {
                if (position == lastPos) {
                    if (stallTicks == 1) PlayerDiagnostics.log("stall.started", "ticks=1; position=$position; length=$length")
                    // Порог обязан быть БОЛЬШЕ длины сегмента, иначе сторож ловит
                    // не обрыв, а обычную догрузку.
                    //
                    // Вся прежняя настройка (20 тиков → 12 → 15) велась вслепую: длину
                    // сегмента никто не измерял. Она равна 6.0 с — ровно столько же,
                    // сколько составляли те 15 тиков по 400 мс. То есть сторож был
                    // настроен срабатывать точно на границе нормальной работы.
                    //
                    // Чем это кончалось, видно в журнале: позиция замерла на 6 с →
                    // сторож перезапустил поток → через 2 с vlc.callTimeout → через
                    // 10 с vlc.abandon reason=commandThreadStuck. Лечение убивало
                    // больного, хотя рядом в том же журнале десятки записей о том,
                    // что поток восстанавливался сам, в том числе после 8 тиков.
                    //
                    // 30 тиков = 12 с: вдвое больше сегмента, и настоящий обрыв всё
                    // ещё ловится до того, как зритель успеет заскучать.
                    if (++stallTicks >= STALL_TICKS_LIMIT && (length <= 0 || position < length - 3000)) {
                        PlayerDiagnostics.log("stall.watchdog", "triggered after ${stallTicks}ticks; position=$position; length=$length")
                        stallTicks = 0
                        // ЗАМЕНЯЕМ ЭКЗЕМПЛЯР, А НЕ ПЕРЕЗАПУСКАЕМ ЕГО.
                        //
                        // Здесь стояло «сыграть тот же адрес заново» — и по журналу
                        // это ровно то, что убивало плеер. Связь стопроцентная на
                        // всех трёх случаях, что попали в логи:
                        //
                        //     12:09:42 stall.watchdog → 12:09:52 commandThreadStuck
                        //     12:56:34 stall.watchdog → 12:56:44 commandThreadStuck
                        //     23:00:55 stall.watchdog → 23:01:05 commandThreadStuck
                        //
                        // Причина понятна: перезапуск начинается с controls().stop(),
                        // а сетевое чтение в этот момент уже висит — вызов уходит в
                        // нативный код и не возвращается. Мы десять секунд ждём, потом
                        // всё равно бросаем экземпляр, но уже с застрявшим потоком
                        // команд позади.
                        //
                        // Раз итог всё равно один, приходим к нему сразу и без
                        // повисшего вызова: экземпляр заменяется, точка сохраняется,
                        // старый дохоранивается в фоне.
                        latestAbandon("stallNoProgress", position)
                        return@LaunchedEffect
                    }
                } else {
                    if (stallRecoveryIsDiagnostic(stallTicks)) PlayerDiagnostics.log("stall.recovered", "stalledFor=${stallTicks}ticks; oldPos=$lastPos; newPos=$position")
                    stallTicks = 0
                    lastPos = position
                }
            } else {
                if (stallRecoveryIsDiagnostic(stallTicks)) PlayerDiagnostics.log("stall.cancelled", "stalledFor=${stallTicks}ticks; playing=$playing; scrubbing=$scrubbing; pos=$position")
                stallTicks = 0
            }
            // Поток не начался вовсе: длительность так и не пришла, кадров ноль.
            // Именно так выглядит мёртвая ссылка кинобалансера — без единой ошибки
            // от libVLC, поэтому обычный перебор вариантов по событию error её не
            // ловит и на экране навсегда остаётся «Загрузка… 0 %».
            //
            // ПОКА ХОРОНИТСЯ ПРЕДЫДУЩИЙ ЭКЗЕМПЛЯР — НЕ СУДИМ ВОВСЕ. Освобождение
            // залипшего libVLC занимает до двадцати секунд (замерено), и всё это
            // время новый честно не может начать. Отсчёт просто заводится заново:
            // приговор вынесем, когда судить будет по чему.
            //
            // НО ОТСРОЧКА ОГРАНИЧЕНА. Здесь стояло безусловное продление, и это был
            // ТУПИК, из которого приложение не выходило вовсе: хоронят экземпляр
            // вызовом release() на зависшей libVLC, а он на ней как раз и не
            // возвращается — никогда. Счётчик похорон оставался ненулевым, отсчёт
            // сбрасывался на каждом тике, и приговор «поток не начался» не мог быть
            // вынесен ни при каких условиях. Наружу это ровно то самое «Загрузка…
            // 0 %», которое висит, пока приложение не убьют:
            //
            //     01:09:37.8  vlc.abandon  generation=1
            //     01:09:47.4  vlc.abandon  generation=2
            //     дальше — тишина, ни одной play.neverStarted, лестница попыток
            //     (свежая ссылка → другая озвучка → другой источник) не тронулась
            //
            // Двадцать секунд — измеренный потолок честных похорон. Дольше значит
            // «не вернётся уже никогда», и судить новый экземпляр надо без оглядки
            // на покойника: пусть лучше зря сменим озвучку, чем висим бесконечно.
            if (playStartedAt != 0L && extendStartDeadline(reapersInFlight.get() > 0, reaperGrantedNanos)) {
                reaperGrantedNanos += POLL_TICK_NANOS
                playStartedAt = System.nanoTime()
            } else if (playStartedAt != 0L) {
                // Длительность в приговор не входит: libVLC узнаёт её из заголовка
                // контейнера и сообщает даже тогда, когда данные так и не пошли — по
                // ней проверка снимала слежение с мёртвой ссылки, и второй вариант не
                // перебирался. Почему сравниваем с точкой запуска, а не с нулём —
                // см. playbackNeverStarted.
                val elapsed = System.nanoTime() - playStartedAt
                if (playbackNeverStarted(elapsed, native.position, playStartedFrom)) {
                    PlayerDiagnostics.log(
                        "play.neverStarted",
                        "afterMs=${elapsed / 1_000_000}; position=${native.position}; firstPosition=$playStartedFrom",
                    )
                    playStartedAt = 0L
                    latestPlaybackError(true)
                } else if (elapsed > START_TIMEOUT_NANOS) {
                    playStartedAt = 0L
                }
            }
            // Второй уровень: перезапуск не ожил в срок — значит дело не в потоке,
            // а в самой ссылке. Просим экран переспросить источник; та же серия
            // резолвится заново и продолжается с текущего места.
            if (recoveryDeadline != 0L) {
                if (native.playing && native.position != lastPos && native.position > 0) {
                    PlayerDiagnostics.log("stream.recovered", "position=${native.position}")
                    recoveryDeadline = 0L
                } else if (System.nanoTime() > recoveryDeadline) {
                    recoveryDeadline = 0L
                    PlayerDiagnostics.log("stream.dead", "position=$position; playing=${native.playing}; askingForFreshUrl")
                    deadUrl = currentUrl
                    onStreamDead()
                }
            }
            // Stop the monitor sleeping while a frame is actually playing (VLC's
            // callback surface is invisible to Windows' idle timer).
            if (playing && videoVisible) DisplayAwake.keep() else DisplayAwake.release()
            // Точка возобновления пишется каждые ~2 с, а не 5.
            //
            // Насильное закрытие (диспетчер задач) не даёт выполниться НИКАКОМУ коду
            // приложения — ни обработчику выхода, ни сохранению при уходе с экрана.
            // Единственное, что переживает такое завершение, — то, что уже лежит на
            // диске, поэтому окно потерь важнее экономии на записи. Файл настроек
            // около 100 КБ и пишется отдельным потоком, атомарной заменой.
            // Отсчёт по ЧАСАМ, а не по числу тиков опроса: тик может задержаться на
            // полторы секунды (столько ждём ответа libVLC), и «раз в пять тиков»
            // растягивалось в непредсказуемое окно потерь.
            if (playing && shouldPersistProgress(position, length, System.nanoTime() - lastProgressSaveAt, true)) {
                lastProgressSaveAt = System.nanoTime()
                confirmedProgress.drain()?.let(latestOnProgress)
            }
            // Also persist the moment playback PAUSES: the periodic save only runs
            // while playing, so pausing and leaving lost up to 5s (or the whole
            // position if the last tick had not landed yet).
            if (!playing && wasPlaying && length > 0 && position > 0) {
                PlayerDiagnostics.log("progress.saveOnPause", "positionMs=$position; lengthMs=$length")
                confirmedProgress.drain()?.let(latestOnProgress)
            }
            wasPlaying = playing
            // Refresh the dub list (cheap; only re-assign when it actually changes so
            // we don't thrash recomposition every tick).
            if (needTracks) {
              if (native.subtitleTracks != subtitleTracks) subtitleTracks = native.subtitleTracks
              if (native.currentSubtitle != selectedSubtitle) selectedSubtitle = native.currentSubtitle
              if (subtitleApply > 0) subtitleApply--
              val tracks = buildDubList(native.audioTracks)
              if (tracks != audioTracks) audioTracks = tracks
              if (tracks.isNotEmpty()) {
                val availableDub = chooseAvailableDub(selectedDub, tracks, native.currentTrack)
                if (availableDub != selectedDub) {
                    // A saved label may not exist in a new episode. Commit VLC's
                    // real track instead of continuing to show a stale voiceover.
                    selectedDub = availableDub
                    availableDub?.let(onDubChange)
                }
                if (availableDub != null && dubApply > 0) {
                    val wantId = tracks.firstOrNull { it.second == availableDub }?.first ?: -1
                    if (wantId >= 0 && native.currentTrack != wantId) {
                        PlayerDiagnostics.log("dub.apply", "label=$availableDub; wantTrackId=$wantId; currentTrackId=${native.currentTrack}; dubApplyRemaining=$dubApply")
                        withContext(playerDispatcher) { runCatching { player.audio().setTrack(wantId) } }
                    } else if (wantId >= 0 && native.currentTrack == wantId) {
                        if (dubApply <= 2) PlayerDiagnostics.log("dub.alreadyApplied", "label=$availableDub; trackId=$wantId; remaining=$dubApply")
                    }
                    dubApply--
                }
              }
            }
            delay(400)
        }
    }
    // Purely additive: does not touch the VLC recovery/play/seek state machine.
    var detectedTimings by remember(mediaKey, currentUrl, autoTimingContext) {
        mutableStateOf(com.aniblaze.aggregator.source.AniskipTimings.SkipTimings.EMPTY)
    }
    val detectOpening = needsAutoTiming(openingRange)
    val detectEnding = needsAutoTiming(endingRange)
    LaunchedEffect(mediaKey, currentUrl, autoTimingContext, autoDetectTimings, detectOpening, detectEnding, suspended) {
        detectedTimings = com.aniblaze.aggregator.source.AniskipTimings.SkipTimings.EMPTY
        val context = autoTimingContext ?: return@LaunchedEffect
        if (!autoDetectTimings || suspended || (!detectOpening && !detectEnding) || currentUrl.isBlank()) return@LaunchedEffect
        snapshotFlow { if (playbackEpochs.accepts(confirmedEpoch, mediaKey)) length else 0L }.first { it >= 90_000 }
        val duration = length
        detectedTimings = AutoTimingService.shared.resolve(context, currentUrl, referer, duration,
            detectOpening, detectEnding, cachedOnly = true)
        if ((!detectOpening || detectedTimings.opening != null) && (!detectEnding || detectedTimings.ending != null)) return@LaunchedEffect
        // A second decoder downloading most of the episode competes with live HLS.
        // Heavy extraction is permitted only during an actual user pause, never buffering.
        snapshotFlow { canAnalyzeTimings(pausedForAnalysis, playing, ended, playbackEpochs.accepts(confirmedEpoch, mediaKey)) }
            .collectLatest { paused ->
                if (!paused) return@collectLatest
                delay(3_000)
                detectedTimings = AutoTimingService.shared.resolve(context, currentUrl, referer, duration, detectOpening, detectEnding)
            }
    }
    val validOpening = preferredSkipRange(openingRange, detectedTimings.opening.takeIf { autoDetectTimings })
    val openingButtonVisible = shouldShowOpeningButton(validOpening, position)
    val validEnding = preferredSkipRange(endingRange, detectedTimings.ending.takeIf { autoDetectTimings })
    val endingButtonVisible = shouldShowOpeningButton(validEnding, position)
    val validRecap = recapRange?.takeIf { it.isValid }
    val recapButtonVisible = shouldShowOpeningButton(validRecap, position)

    /**
     * Пропускает нажатие, только если с прошлого прошло достаточно времени.
     *
     * Клавиши-переключатели (пауза, звук, полный экран) обязаны срабатывать РОВНО
     * один раз на нажатие. Автоповтор при удержании и «дребезг» быстрых клавиатур
     * (магнитные с rapid trigger отдают несколько событий на одно физическое
     * нажатие) давали два-три переключения подряд: пауза тут же снималась, и со
     * стороны это выглядит как «пробел то работает, то нет».
     */
    val keyGuard = remember { HashMap<Key, Long>() }
    fun keyAccepted(key: Key): Boolean {
        val now = System.nanoTime()
        val previous = keyGuard[key] ?: 0L
        if (now - previous < TOGGLE_KEY_GAP_NANOS) return false
        keyGuard[key] = now
        return true
    }

    fun showControls() {
        lastControlsActivity.set(System.nanoTime())
        if (!controlsVisible) PlayerDiagnostics.log("hud.show", "reason=showControls")
        controlsVisible = true
    }

    fun hideControls() {
        // Закреплённая панель не прячется ничем — ни таймером, ни кликом по видео.
        if (hudPinned) return
        PlayerDiagnostics.log("hud.hide", "reason=timeout-or-toggle; scrubbing=$scrubbing; hudHovered=$hudHovered; activeMenu=${activeHudMenu}")
        controlsVisible = false
        activeHudMenu = null
        hudPressed = false
    }

    fun setControlsFromVideo(visible: Boolean) {
        PlayerDiagnostics.log("hud.setFromVideo", "targetVisible=$visible; currentVisible=$controlsVisible")
        if (visible) showControls() else hideControls()
    }

    fun toggleHudMenu(menu: PlayerHudMenu) {
        if (activeHudMenu == menu) {
            activeHudMenu = null
        } else {
            // Assign the payload first so the very first animated frame is styled.
            displayedHudMenu = menu
            activeHudMenu = menu
        }
        PlayerDiagnostics.log("hud.menu", "menu=$menu; open=${activeHudMenu != null}")
        showControls()
    }

    // The opening action is part of the HUD and must remain reachable throughout
    // its exact timing window even when the mouse is idle.
    val skipButtonVisible = openingButtonVisible || endingButtonVisible
    LaunchedEffect(skipButtonVisible) {
        if (skipButtonVisible) showControls()
    }

    // Auto-hide after inactivity. Moving the pointer only refreshes the activity
    // timestamp; it does not flip visibility and therefore cannot restart motion.
    val hudInteractionActive = hudHovered || hudPressed || scrubbing || activeHudMenu != null
    LaunchedEffect(hudPinned) { if (hudPinned) showControls() }
    LaunchedEffect(controlsVisible, playing, hudInteractionActive, skipButtonVisible, hudPinned) {
        val autoHideCondition =
            shouldAutoHideHud(controlsVisible, playing, hudInteractionActive, skipButtonVisible, hudPinned)
        PlayerDiagnostics.log("hud.autoHide.effect", "controlsVisible=$controlsVisible; playing=$playing; hudInteractionActive=$hudInteractionActive; skipButtonVisible=$skipButtonVisible; pinned=$hudPinned; autoHide=$autoHideCondition")
        if (autoHideCondition) {
            val hideAfterNanos = 3_500_000_000L
            var lastLogTick = 0L
            while (true) {
                val remaining = hideAfterNanos - (System.nanoTime() - lastControlsActivity.get())
                if (remaining <= 0L) {
                    PlayerDiagnostics.log("hud.autoHide.fired", "reason=timeout; remaining=${remaining}ns")
                    hideControls()
                    break
                }
                val remainingMs = remaining / 1_000_000L
                if (remainingMs / 500 != lastLogTick) {
                    lastLogTick = remainingMs / 500
                    if (remainingMs <= 2000 || remainingMs % 1000 < 500) PlayerDiagnostics.log("hud.autoHide.tick", "remainingMs=$remainingMs")
                }
                delay(minOf(remaining / 1_000_000L + 1L, 500L))
            }
        }
    }

    fun togglePlay() {
        val pause = playing
        val restart = ended
        petUserPaused = pause && !restart
        if (restart) ended = false
        scope.launch(playerDispatcher) {
            if (restart) {
                player.controls().play()
                player.controls().setTime(0)
            } else if (pause) player.controls().pause() else player.controls().play()
        }
        playing = if (restart) true else !playing
        // Пауза от зрителя снимает сторожа «поток не начался»: позиция теперь стоит
        // потому, что её остановили, а не потому, что раздающий молчит. Без этого
        // пауза на точке возобновления через девять секунд объявляла бы живую ссылку
        // мёртвой и перезапрашивала её на ровном месте.
        if (pause && !restart) playStartedAt = 0L
        PlayerDiagnostics.log("play.toggle", "nowPlaying=$playing; restart=$restart")
        showControls()
    }

    // Окошко «картинка в картинке» рисует из ТОГО ЖЕ стока и жмёт на ту же паузу —
    // копии кадров и второго проигрывателя нет, есть второе окно над одним потоком.
    //
    // Только в режиме наложения: когда кадры выводит сама libVLC в нативное окно,
    // до них из Compose не добраться в принципе, и рисовать в окошке нечего.
    if (overlay) {
        DisposableEffect(frameSink) {
            PipBridge.attach(frameSink, title = pipTitle, onTogglePlay = ::togglePlay)
            onDispose { PipBridge.detach(frameSink) }
        }
        LaunchedEffect(playing, fillMode) { PipBridge.update(playing, fillMode) }
    }
    fun seekTo(targetMs: Long) {
        if (!playbackEpochs.accepts(confirmedEpoch, latestMediaKey) || length <= 0) return
        // Держимся ПОДАЛЬШЕ от самого конца. Интервал эндинга у AniSkip нередко
        // длиннее конкретной серии (а заимствованный у соседней — тем более), и
        // «Пропустить эндинг» приземлялся ровно на последний кадр: поток там уже
        // кончился, буфер не набирался никогда, и на экране навсегда повисало
        // «Загрузка… 0 %». Три секунды хвоста хватает, чтобы серия доиграла
        // штатно и сработал автопереход к следующей.
        val target = guardedSeekTarget(targetMs, length)
        confirmedProgress.interrupt()
        val restart = ended
        ended = false
        position = target
        seekTarget = target
        seekIssuedAt.set(System.nanoTime())
        if (overlay) frameSeekToken = frameSink.pauseForSeek()
        PlayerDiagnostics.log("play.seek", "targetMs=$target; ended=$restart")
        // Отменяем ещё не отправленную предыдущую перемотку: пользователь уже
        // передумал, а лишний setTime стоит целого сегмента HLS.
        //
        // Отмена — только ПЕРВЫЙ рубеж, и сам по себе он дырявый: перемотку, уже
        // доехавшую до потока команд, отменять нечем. Второй рубеж — сам
        // [seekCommand]: в очереди к libVLC не может стоять больше одной перемотки,
        // сколько бы их ни нащёлкали.
        pendingSeekJob.getAndSet(
            scope.launch {
                delay(SEEK_COALESCE_MS)
                if (restart) withContext(playerDispatcher) { player.controls().play() }
                seekCommand.submit(target)
            },
        )?.cancel()
    }
    // Отсчёт от ЦЕЛИ, а не от того, что успел показать VLC: иначе пять быстрых
    // нажатий «+5 с» давали суммарно +5 с вместо +25 с.
    fun skipBy(deltaMs: Long) = seekTo((seekTarget ?: position) + deltaMs)

    // «Пропускать опенинг автоматически»: один раз за серию, как только позиция
    // входит в известный интервал. Порог по концу (-1с) не даёт зациклиться, если
    // seek приземлился на миллисекунду раньше endMs.
    var openingAutoSkipped by remember(mediaKey) { mutableStateOf(false) }
    val autoSkipReady = canAutoSkipMedia(playbackEpochs.accepts(confirmedEpoch, mediaKey),
        length, nativeSeekable, playing, pausedForAnalysis)
    LaunchedEffect(position, validOpening, autoSkipOpening, confirmedEpoch, mediaKey, autoSkipReady) {
        if (!autoSkipReady) return@LaunchedEffect
        if (!shouldAutoSkipOpening(validOpening, position, openingAutoSkipped, autoSkipOpening)) {
            return@LaunchedEffect
        }
        val range = validOpening ?: return@LaunchedEffect
        openingAutoSkipped = true
        PlayerDiagnostics.log("opening.autoSkip", "from=$position; to=${range.endMs}")
        seekTo(range.endMs)
    }
    // «Пропускать эндинг автоматически» — то же правило, что и у опенинга, включая
    // отказ от ЗАНЯТОГО интервала: у одолженных таймингов промах в десятки секунд, а
    // прыжок по титрам, которых ещё нет, срезал бы конец настоящей серии.
    //
    // Прыгаем на конец интервала титров, а не «сразу к следующей серии»: у части
    // тайтлов после титров идёт сцена, и проглатывать её нельзя. Когда титры и есть
    // конец серии, seekTo сам придержится в трёх секундах от финала (SEEK_END_GUARD_MS),
    // остаток доиграет и сработает обычный автопереход. Отдельного пути к следующей
    // серии тут нет намеренно: он бы дублировал автоплей и расходился с ним.
    var endingAutoSkipped by remember(mediaKey) { mutableStateOf(false) }
    LaunchedEffect(position, validEnding, autoSkipEnding, confirmedEpoch, mediaKey, autoSkipReady) {
        if (!autoSkipReady) return@LaunchedEffect
        if (!shouldAutoSkipOpening(validEnding, position, endingAutoSkipped, autoSkipEnding)) {
            return@LaunchedEffect
        }
        val range = validEnding ?: return@LaunchedEffect
        endingAutoSkipped = true
        PlayerDiagnostics.log("ending.autoSkip", "from=$position; to=${range.endMs}")
        seekTo(range.endMs)
    }
    // Почему автопропуск промолчал — вопрос, который иначе не выяснить: настройка
    // включена, интервал есть, а прыжка нет. Строка пишется ОДИН раз на серию.
    LaunchedEffect(mediaKey, validOpening, autoSkipOpening) {
        val range = validOpening
        if (autoSkipOpening && range != null && range.approximate) {
            PlayerDiagnostics.log(
                "opening.autoSkip.declined",
                "reason=approximate; range=${range.startMs}-${range.endMs}",
            )
        }
    }
    LaunchedEffect(mediaKey, validEnding, autoSkipEnding) {
        val range = validEnding
        if (autoSkipEnding && range != null && range.approximate) {
            PlayerDiagnostics.log(
                "ending.autoSkip.declined",
                "reason=approximate; range=${range.startMs}-${range.endMs}",
            )
        }
    }
    /** Включить дорожку субтитров (−1 — выключить); состояние сверяется опросом. */
    fun selectSubtitle(id: Int) {
        PlayerDiagnostics.log("subtitles.select", "id=$id")
        selectedSubtitle = id
        subtitleApply = 10
        scope.launch(playerDispatcher) { runCatching { player.subpictures().setTrack(id) } }
        hudNotice = if (id < 0) "Субтитры выключены" else "Субтитры: ${subtitleTracks.firstOrNull { it.first == id }?.second ?: id}"
    }
    /** Подключить внешний файл субтитров через системный диалог (AWT, поток событий). */
    fun openSubtitleFile() {
        java.awt.EventQueue.invokeLater {
            val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Файл субтитров", java.awt.FileDialog.LOAD)
            dialog.setFilenameFilter { _, name -> SUBTITLE_EXTENSIONS.any { name.lowercase().endsWith(it) } }
            dialog.isVisible = true
            val file = dialog.file?.let { java.io.File(dialog.directory, it) } ?: return@invokeLater
            PlayerDiagnostics.log("subtitles.file", "path=${file.absolutePath}")
            subtitleApply = 20
            scope.launch(playerDispatcher) {
                val ok = runCatching { player.subpictures().setSubTitleFile(file) }.getOrDefault(false)
                PlayerDiagnostics.log("subtitles.file.result", "ok=$ok")
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    hudNotice = if (ok) "Субтитры: ${file.name}" else "Не удалось открыть субтитры"
                }
            }
        }
    }
    /** Снимок кадра в «Изображения/AniBlaze» (клавиша S). */
    fun takeScreenshot() {
        val dir = java.io.File(System.getProperty("user.home"), "Pictures/AniBlaze")
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val name = pipTitle.replace(Regex("""[\\/:*?"<>|]+"""), " ").trim().take(60).ifBlank { "AniBlaze" }
        val episodePart = activeEpisode?.let { "-s$it" } ?: ""
        val file = java.io.File(dir, "$name$episodePart-$stamp.png")
        scope.launch(playerDispatcher) {
            val ok = runCatching { dir.mkdirs(); player.snapshots().save(file) }.getOrDefault(false)
            PlayerDiagnostics.log("screenshot", "ok=$ok; path=${file.absolutePath}")
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                hudNotice = if (ok) "Снимок сохранён: ${file.name}" else "Не удалось сохранить снимок"
            }
        }
    }
    fun showSkipFeedback(seconds: Int) {
        val sequence = skipFeedbackSequence.incrementAndGet()
        PlayerDiagnostics.log("skipFeedback.start", "seconds=$seconds; sequence=$sequence")
        scope.launch(playerDispatcher) {
            val logo = player.logo()
            val configured = runCatching {
                logo.setImage(if (seconds < 0) skipBackImage else skipForwardImage)
                logo.setPosition(LogoPosition.CENTRE)
                logo.setOpacity(0)
                logo.enable(true)
            }.isSuccess
            if (!configured) { PlayerDiagnostics.log("skipFeedback.configFailed"); return@launch }

            for (opacity in intArrayOf(40, 80, 120, 160, 200, 230)) {
                if (skipFeedbackSequence.get() != sequence) return@launch
                runCatching { logo.setOpacity(opacity) }
                delay(20)
            }
            delay(360)
            for (opacity in intArrayOf(190, 150, 110, 70, 30, 0)) {
                if (skipFeedbackSequence.get() != sequence) return@launch
                runCatching { logo.setOpacity(opacity) }
                delay(24)
            }
            val disabled = skipFeedbackSequence.get() == sequence
            if (disabled) {
                runCatching { logo.enable(false) }
            }
            PlayerDiagnostics.log("skipFeedback.finish", "disabled=$disabled; logoConfigured=$configured")
        }
    }
    fun handleVideoClick(relativeX: Int, surfaceWidth: Int) {
        val now = System.nanoTime()
        val previous = lastVideoClick.getAndSet(now)
        val clickSequence = videoClickSequence.incrementAndGet()
        val isDoubleClick = previous != 0L && now - previous <= DOUBLE_CLICK_WINDOW_MS * 1_000_000L
        PlayerDiagnostics.log("video.click", "x=$relativeX; width=$surfaceWidth; isDoubleClick=$isDoubleClick; sincePrevious=${if (previous == 0L) "first" else "${(now - previous) / 1_000_000}ms"}")
        if (isDoubleClick) {
            lastVideoClick.set(0L)
            val seconds = if (relativeX < surfaceWidth / 2) -5 else 5
            skipBy(seconds * 1_000L)
            showSkipFeedback(seconds)
            PlayerDiagnostics.log("video.doubleClick", "skipSeconds=$seconds")
        } else {
            // Do not toggle on the first press immediately: it may be the first
            // half of a double-click seek. Waiting prevents the HUD from flashing
            // open and closed around every seek gesture.
            val targetVisible = singleClickHudTarget(controlsVisible)
            scope.launch {
                delay(DOUBLE_CLICK_WINDOW_MS)
                if (
                    videoClickSequence.get() == clickSequence &&
                    lastVideoClick.compareAndSet(now, 0L)
                ) {
                    // Apply the intent captured on press. Pointer movement during
                    // the double-click window must not invert what this click does.
                    setControlsFromVideo(targetVisible)
                    PlayerDiagnostics.log("video.singleClick", "hudVisible=$targetVisible")
                }
            }
        }
    }
    fun setVol(v: Float) {
        val oldVolume = volume
        volume = v.coerceIn(0f, 100f); muted = false
        val target = volume.toInt()
        volumeApply = 4
        volumeCommand.submit(target)
        onVolumeChange(target)
        PlayerDiagnostics.log("volume.set", "old=$oldVolume; new=$target; muted=false")
        showControls()
    }

    LaunchedEffect(controlsVisible, activeHudMenu, videoVisible) {
        PlayerDiagnostics.log(
            "hud.state",
            "visible=$controlsVisible; menu=${activeHudMenu ?: "none"}; playerVisible=$videoVisible",
        )
    }

    // AWT owns pointer input over the native surface. Forward clicks back to the
    // latest Compose actions; keeping this lambda fresh avoids stale seek/ended
    // state in a listener registered only once.
    val latestCanvasClick = rememberUpdatedState<(MouseEvent) -> Unit> { event ->
        runCatching { focusRequester.requestFocus() }
        handleVideoClick(event.x, event.component.width)
    }
    DisposableEffect(videoCanvas, overlay) {
        // Наложение: клики приходят обычным путём Compose, слушать AWT незачем.
        if (overlay) return@DisposableEffect onDispose { }
        val listener = object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                PlayerDiagnostics.log("canvas.mouseEvent", "type=click; x=${event.x}; y=${event.y}; button=${event.button}; clickCount=${event.clickCount}; nativePointerAvailable=${NativePointer.available}")
                if (!NativePointer.available) latestCanvasClick.value(event)
            }
        }
        videoCanvas.addMouseListener(listener)
        onDispose {
            PlayerDiagnostics.log("canvas.mouseListener.removed")
            videoCanvas.removeMouseListener(listener)
        }
    }

    // VLC's native child HWND may swallow AWT mouse events completely. Poll the
    // pointer as a fallback and refresh HUD activity only when it actually moves.
    // Assigning `true` while already visible does not restart AnimatedVisibility.
    LaunchedEffect(videoCanvas, videoVisible, overlay) {
        // Опрос указателя существует только потому, что нативное окно VLC глотает
        // события мыши. В режиме наложения глотать нечего — Compose получает их сам.
        if (overlay) return@LaunchedEffect
        var leftWasDown = false
        var wasInside = false
        var windowWasInactive = false
        var lastPointerX: Int? = null
        var lastPointerY: Int? = null
        var clicksSinceLastLog = 0
        var logTransition = true
        while (true) {
            if (!videoVisible || !videoCanvas.isShowing) {
                if (wasInside) PlayerDiagnostics.log("pointer.canvasLeave", "reason=videoHiddenOrNotShowing")
                wasInside = false
                lastPointerX = null
                lastPointerY = null
                delay(250)
                continue
            }

            val pointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
            val origin = runCatching { videoCanvas.locationOnScreen }.getOrNull()
            val inside = pointer != null && origin != null &&
                pointer.x >= origin.x && pointer.y >= origin.y &&
                pointer.x < origin.x + videoCanvas.width &&
                pointer.y < origin.y + videoCanvas.height
            // The polled button state is GLOBAL: without this gate a click inside any
            // other window that overlaps the video area registered as a video click
            // (random -5s seeks). Only react while our window is the active one, and
            // swallow the click that merely re-activates it.
            val windowActive = NativePointer.isWindowActive(videoCanvas)
            // Логируем ТОЛЬКО при активном окне. Иначе ветка ниже сбрасывает
            // wasInside в false на каждом обороте, и «inside изменился» писалось
            // десять раз в секунду до конца сеанса — лог рос впустую.
            if (windowActive && inside != wasInside) {
                PlayerDiagnostics.log("pointer.insideChanged", "inside=$inside; pos=${if (inside) "${pointer!!.x},${pointer.y}" else "N/A"}")
            }
            if (!windowActive) {
                if (wasInside) PlayerDiagnostics.log("pointer.ignored", "reason=windowInactive")
                wasInside = false
                lastPointerX = null
                lastPointerY = null
                leftWasDown = NativePointer.leftButtonState() and 0x8000 != 0
                windowWasInactive = true
                delay(100)
                continue
            }
            val buttonState = NativePointer.leftButtonState()
            val leftDown = buttonState and 0x8000 != 0
            var pressedSinceLastCheck = buttonState and 0x0001 != 0
            if (windowWasInactive) {
                // First poll after regaining focus: discard the queued press bit so the
                // activating click never seeks.
                windowWasInactive = false
                pressedSinceLastCheck = false
                leftWasDown = leftDown
                PlayerDiagnostics.log("pointer.focusClickSwallowed")
            }
            if (inside && !hudHovered) {
                val x = pointer!!.x
                val y = pointer.y
                val moved = !wasInside || lastPointerX == null || lastPointerY == null ||
                    kotlin.math.abs(x - lastPointerX!!) > 1 || kotlin.math.abs(y - lastPointerY!!) > 1
                if (moved) showControls()
                lastPointerX = x
                lastPointerY = y
            } else {
                lastPointerX = null
                lastPointerY = null
            }
            if (inside && !hudHovered && (pressedSinceLastCheck || leftDown && !leftWasDown)) {
                val relativeX = pointer!!.x - origin!!.x
                clicksSinceLastLog++
                if (clicksSinceLastLog <= 3) PlayerDiagnostics.log("pointer.clickDetected", "relativeX=$relativeX; canvasWidth=${videoCanvas.width}; leftDown=$leftDown; pressedSinceLast=$pressedSinceLastCheck")
                handleVideoClick(relativeX, videoCanvas.width)
                runCatching { focusRequester.requestFocus() }
            }
            wasInside = inside
            leftWasDown = leftDown
            delay(50)
        }
    }

    // The native Canvas owns focus while the video is playing, so Compose key
    // handlers cannot reliably see Escape. A keyboard-focus-manager dispatcher
    // observes it before whichever AWT/Swing component currently has focus.
    val latestFullscreen = rememberUpdatedState(fullscreen)
    val latestToggleFullscreen = rememberUpdatedState(onToggleFullscreen)
    val latestVideoVisible = rememberUpdatedState(videoVisible)
    val latestSkipBy = rememberUpdatedState<(Long) -> Unit> { delta ->
        skipBy(delta)
        showSkipFeedback((delta / 1_000L).toInt())
    }
    DisposableEffect(Unit) {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = KeyEventDispatcher { event ->
            if (event.id != AwtKeyEvent.KEY_PRESSED || !latestVideoVisible.value) {
                if (event.id == AwtKeyEvent.KEY_PRESSED && !latestVideoVisible.value) PlayerDiagnostics.log("kbf.dispatch.skipped", "reason=videoNotVisible")
                false
            } else when (event.keyCode) {
                AwtKeyEvent.VK_LEFT -> {
                    PlayerDiagnostics.log("kbf.dispatch", "key=LEFT")
                    latestSkipBy.value(-5_000L)
                    true
                }
                AwtKeyEvent.VK_RIGHT -> {
                    PlayerDiagnostics.log("kbf.dispatch", "key=RIGHT")
                    latestSkipBy.value(5_000L)
                    true
                }
                AwtKeyEvent.VK_ESCAPE -> if (latestFullscreen.value) {
                    PlayerDiagnostics.log("kbf.dispatch", "key=ESCAPE; action=exitFullscreen")
                    latestToggleFullscreen.value()
                    true
                } else {
                    PlayerDiagnostics.log("kbf.dispatch.ignored", "key=ESCAPE; notFullscreen=true")
                    false
                }
                else -> false
            }
        }
        manager.addKeyEventDispatcher(dispatcher)
        onDispose {
            PlayerDiagnostics.log("kbf.dispatcher.removed")
            manager.removeKeyEventDispatcher(dispatcher)
        }
    }

    // Grab keyboard focus so the player responds to shortcuts immediately.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    val reducedMotion = remember { systemPrefersReducedMotion().also { PlayerDiagnostics.log("system.reducedMotion", "value=$it") } }
    val hudEnterMs = motionDurationMillis(220, reducedMotion)
    val hudExitMs = motionDurationMillis(180, reducedMotion)
    val menuEnterMs = motionDurationMillis(190, reducedMotion)
    val menuExitMs = motionDurationMillis(150, reducedMotion)

    LaunchedEffect(videoVisible) {
        PlayerDiagnostics.log("surface.park", "videoVisible=$videoVisible; mode=${if (overlay) "compose" else "size-1dp-offscreen"}")
        if (!overlay && videoVisible) videoCanvas.isVisible = true
    }
    // Новая серия не должна секунду показывать последний кадр предыдущей.
    LaunchedEffect(mediaKey) { if (overlay) frameSink.clear() }

    // Кадры готовятся, только если их ЕСТЬ КОМУ ПОКАЗАТЬ.
    //
    // Одного videoVisible мало: он знает про вкладку, но не про окно. Свёрнутое окно
    // без окошка «картинка в картинке» продолжало превращать кадр в картинку Skia
    // двадцать четыре раза в секунду — по восемь мегабайт нативной памяти в никуда.
    // libVLC при этом декодирует как декодировала, звук не прерывается, а вернувшись
    // в окно, первый же кадр рисуется снова.
    LaunchedEffect(videoVisible, PipBridge.anyoneDrawing) {
        frameSink.enabled = videoVisible && PipBridge.anyoneDrawing
        // Уходя с плеера, кадр надо ОТПУСТИТЬ: иначе он продолжает висеть под
        // остальными экранами и проступает как «фон из аниме».
        // Серия та же — отпускаем только кадр, накопленное о ней сохраняется.
        if (!videoVisible) frameSink.clear(newScene = false)
    }

    // Лента чата заводится, пока функция включена, — даже когда её спрятали кнопкой:
    // вернувшись, зритель должен увидеть ЖИВОЙ чат, а не начатый с нуля.
    //
    // Настроение считается по кадрам ([SceneEnergy]), а кадры доходят только в режиме
    // наложения. В нативном выводе картинку рисует сама libVLC, замерять нечего — и
    // тогда чат честно переходит на расписание (см. arcMood).
    // Когда включены и боковой чат, и всплывающие комментарии, настоящие реплики
    // делятся между ними детерминированно. Так оба режима остаются живыми, но одна и
    // та же реплика не показывается одновременно в двух местах.
    val commentSurfaces = remember(comments, chat.enabled, commentsEnabled) {
        splitCommentsForSurfaces(comments, chat.enabled, commentsEnabled)
    }
    val chatFeed = if (chat.enabled) {
        rememberChatFeed(
            episodeKey = mediaKey?.toString().orEmpty(),
            episode = commentsEpisode,
            comments = commentSurfaces.chat,
            commentState = commentsState,
            freshFirst = commentsFreshOnly,
            viewers = chat.viewers,
            intensity = chat.intensity,
            speed = chat.speed,
            alwaysQuiet = chat.alwaysQuiet,
            popularFirst = chat.popularFirst,
            episodeOnly = chat.episodeOnly,
            // Проброс, а не только LocalAppSettings: лента не должна зависеть от того,
            // добрался ли CompositionLocal до этого места в дереве.
            realOnly = chat.realOnly,
            playing = playing,
            positionMs = position,
            lengthMs = length,
            opening = validOpening,
            ending = validEnding,
            energy = { if (overlay) frameSink.energy.snapshot() else SceneSample.UNKNOWN },
        )
    } else {
        null
    }
    // videoVisible ОБЯЗАТЕЛЕН в условии. Экран плеера не разбирается при уходе на
    // «Главную» — он живёт дальше, как вкладка браузера, и узнаёт об уходе только по
    // этому флагу. Без него панель чата продолжала рисоваться поверх каталога.
    val chatDocked = chatFeed != null && !chatHidden && !chat.overlayMode && videoVisible
    val chatPanelPx = with(LocalDensity.current) {
        if (chatDocked) chat.panelWidth.dp.roundToPx() else 0
    }

    PlayerFrame(
        overlay = overlay,
        chatWidthPx = chatPanelPx,
        chatSide = chat.side,
        modifier = modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { ke ->
                // Пишут в чат — плеер клавиш не слышит вовсе.
                if (chatTyping) return@onPreviewKeyEvent false
                if (ke.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Esc — всегда выход из полного экрана; остальное — по привязкам
                // (умолчания или свои из настроек, см. Hotkeys).
                if (ke.key == Key.Escape) {
                    return@onPreviewKeyEvent if (fullscreen) { PlayerDiagnostics.log("key.shortcut", "key=ESCAPE; action=exitFullscreen"); onToggleFullscreen(); true } else { PlayerDiagnostics.log("key.shortcut.ignored", "key=ESCAPE; notFullscreen=$fullscreen"); false }
                }
                val action = Hotkeys.actionOf(ke.key) ?: return@onPreviewKeyEvent false
                return@onPreviewKeyEvent when (action) {
                    PlayerAction.TOGGLE_PLAY -> {
                        // Переключатели гасим по времени: автоповтор удержания и
                        // «дребезг» быстрых клавиатур (магнитные с rapid trigger
                        // отдают несколько нажатий на одно физическое) превращали
                        // одну паузу в две-три подряд — со стороны это выглядит как
                        // «то срабатывает, то нет».
                        if (!keyAccepted(ke.key)) {
                            PlayerDiagnostics.log("key.shortcut.debounced", "key=${ke.key}")
                            true
                        } else {
                            PlayerDiagnostics.log("key.shortcut", "key=${ke.key}; action=togglePlay; playing=$playing")
                            togglePlay()
                            true
                        }
                    }
                    PlayerAction.SEEK_BACK -> { PlayerDiagnostics.log("key.shortcut", "key=${ke.key}; action=skipBack5"); skipBy(-5_000); showSkipFeedback(-5); true }
                    PlayerAction.SEEK_FORWARD -> { PlayerDiagnostics.log("key.shortcut", "key=${ke.key}; action=skipForward5"); skipBy(5_000); showSkipFeedback(5); true }
                    PlayerAction.VOLUME_UP -> { PlayerDiagnostics.log("key.shortcut", "key=${ke.key}; action=volUp; oldVol=$volume"); setVol(volume + 5); true }
                    PlayerAction.VOLUME_DOWN -> { PlayerDiagnostics.log("key.shortcut", "key=${ke.key}; action=volDown; oldVol=$volume"); setVol(volume - 5); true }
                    PlayerAction.MUTE -> {
                        if (!keyAccepted(ke.key)) return@onPreviewKeyEvent true
                        val newMuted = !muted
                        PlayerDiagnostics.log("key.shortcut", "key=${ke.key}; action=toggleMute; wasMuted=$muted; nowMuted=$newMuted")
                        muted = newMuted
                        val target = if (muted) 0 else volume.toInt()
                        volumeApply = 4
                        volumeCommand.submit(target)
                        showControls()
                        true
                    }
                    PlayerAction.FULLSCREEN -> {
                        if (!keyAccepted(ke.key)) return@onPreviewKeyEvent true
                        PlayerDiagnostics.log("key.shortcut", "key=${ke.key}; action=toggleFullscreen; current=$fullscreen")
                        onToggleFullscreen()
                        true
                    }
                    PlayerAction.SUBTITLES -> {
                        if (!keyAccepted(ke.key)) return@onPreviewKeyEvent true
                        PlayerDiagnostics.log("key.shortcut", "key=${ke.key}; action=toggleSubtitles; selected=$selectedSubtitle; tracks=${subtitleTracks.size}")
                        when {
                            selectedSubtitle >= 0 -> selectSubtitle(-1)
                            subtitleTracks.isNotEmpty() -> selectSubtitle(subtitleTracks.first().first)
                            else -> hudNotice = "Субтитров в потоке нет — файл можно открыть в меню"
                        }
                        showControls()
                        true
                    }
                    PlayerAction.SCREENSHOT -> {
                        if (!keyAccepted(ke.key)) return@onPreviewKeyEvent true
                        PlayerDiagnostics.log("key.shortcut", "key=${ke.key}; action=screenshot")
                        takeScreenshot()
                        true
                    }
                    PlayerAction.NEXT_EPISODE -> {
                        if (!keyAccepted(ke.key)) return@onPreviewKeyEvent true
                        val currentIndex = episodes.indexOfFirst { it.number == activeEpisode }
                        val next = if (currentIndex >= 0) episodes.drop(currentIndex + 1).firstOrNull { it.playable } else null
                        PlayerDiagnostics.log("key.shortcut", "key=${ke.key}; action=nextEpisode; next=${next?.number}")
                        if (next != null) onEpisodeChange(next.number) else hudNotice = "Это последняя доступная серия"
                        true
                    }
                }
            },
    video = {
        if (overlay) {
            ComposeVideoSurface(
                sink = frameSink,
                fillMode = fillMode,
                zoom = videoZoom,
                enhance = requestedEnhance,
                modifier = Modifier.fillMaxSize().clipToBounds()
                    // Указатель приходит напрямую: ни нативного окна, ни опроса
                    // мыши раз в 50 мс, ни глобального состояния кнопки.
                    .onPointerEvent(PointerEventType.Move) { if (!hudHovered) showControls() }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            runCatching { focusRequester.requestFocus() }
                            handleVideoClick(offset.x.roundToInt(), size.width)
                        }
                    },
            )
            // Комментарии — НАД кадром, но ПОД панелью управления и без единого
            // обработчика ввода: клик по видео должен доходить до видео, даже если
            // реплика оказалась ровно под курсором.
            if (shouldShowDanmakuOverlay(
                    commentsEnabled,
                    commentsHidden,
                    chatVisible = chatFeed != null && !chatHidden,
                    videoVisible = videoVisible,
                )
            ) {
                DanmakuOverlay(
                    // Ключ сессии показа — «тайтл:серия». Смена качества и озвучки
                    // его не меняет, значит и показ не пересоздают.
                    episodeKey = mediaKey?.toString().orEmpty(),
                    comments = commentSurfaces.overlay,
                    episode = commentsEpisode,
                    freshFirst = commentsFreshOnly,
                    lengthMs = length,
                    playing = playing,
                    rate = DanmakuRate.of(commentsRate),
                    opacity = commentsOpacity,
                    fontSize = commentsFontSize,
                    moving = commentsMoving,
                )
            }
            // Питомец поверх кадра: только когда видео реально на экране. Позиция —
            // доли окна, поэтому переживает смену размера и полноэкранный режим.
            // Настроение простое и честное: пауза дольше минуты — дремлет.
            pet?.takeIf { videoVisible && !suspended }?.let { options ->
                com.aniblaze.desktop.pet.PetPlayerHost(
                    pet = com.aniblaze.desktop.pet.PetDef.of(options.petId),
                    scale = options.scale,
                    xFraction = options.x,
                    yFraction = options.y,
                    onMove = options.onMove,
                    episode = activeEpisode ?: 0,
                    episodesAvailable = episodes.filter { it.playable }.maxOfOrNull { it.number } ?: 0,
                    episodesTotal = episodeTotal,
                    playing = playing,
                    buffering = !petUserPaused && !ended &&
                        (bufferPercent < 100f || !playbackEpochs.accepts(confirmedEpoch, mediaKey)),
                    userPaused = petUserPaused && !playing && !ended,
                    pauseStartedAtMs = petPauseStartedAt,
                    ended = ended,
                    playbackError = petStreamFailed && !petUserPaused && !ended,
                    endingConfirmed = endingButtonVisible && autoSkipReady,
                    session = options.session,
                    speechEnabled = options.speechEnabled,
                    quietWatching = options.quietWatching,
                    openedPositionMs = startPositionMs,
                    animate = !reducedMotion,
                    avoidRight = chat.overlayMode && !chatHidden && chat.overlayPosition in setOf(ChatOverlayPosition.TOP_RIGHT, ChatOverlayPosition.BOTTOM_RIGHT),
                    avoidLeft = chat.overlayMode && !chatHidden && chat.overlayPosition in setOf(ChatOverlayPosition.TOP_LEFT, ChatOverlayPosition.BOTTOM_LEFT),
                    positionMs = position,
                    durationMs = length,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp),
                    onSeek = { target -> PlayerDiagnostics.log("pet.seekBack", "to=$target"); seekTo(target) },
                    chatterCooldownMs = options.chatterCooldownMs,
                    context = options.context.copy(
                        muted = muted,
                        qualityName = currentQuality.orEmpty(),
                        subtitlesName = if (selectedSubtitle < 0) "" else subtitleTracks.firstOrNull { it.first == selectedSubtitle }?.second.orEmpty().ifBlank { "дорожка $selectedSubtitle" },
                    ),
                    speed = playbackSpeed,
                    // Следующая серия — та же, что у кнопки «дальше» на HUD: ближайшая
                    // проигрываемая после текущей. Нет её — питомец и не предлагает.
                    onNextEpisode = run {
                        val currentIndex = episodes.indexOfFirst { it.number == activeEpisode }
                        val next = if (currentIndex >= 0) episodes.drop(currentIndex + 1).firstOrNull { it.playable } else null
                        next?.let { target ->
                            { PlayerDiagnostics.log("pet.skipEnding", "to=${target.number}"); onEpisodeChange(target.number) }
                        }
                    },
                )
            }
            // Прозрачный чат — там же, НАД кадром и ПОД панелью управления, и так же
            // без единого обработчика указателя.
            // videoVisible — по той же причине, что и у пристыкованной панели: экран
            // плеера переживает уход на другую страницу.
            val overlayChatFeed = chatFeed
            if (shouldShowChatOverlay(overlayChatFeed != null, chatHidden, chat.overlayMode, videoVisible) &&
                overlayChatFeed != null
            ) {
                ChatOverlay(
                    feed = overlayChatFeed,
                    position = chat.overlayPosition,
                    lines = chat.overlayLines,
                    opacity = chat.overlayOpacity,
                    fontSize = chat.overlayFontSize,
                )
            }
        } else {
            // VLC's Canvas is a native heavyweight window and must not overlap Compose
            // controls: Windows always paints it above them. Keep a dedicated Compose
            // HUD region below the surface, so video and controls are both visible.
            //
            // On an inactive tab the heavyweight view is PARKED, not removed — the
            // peer (HWND) stays alive, libVLC keeps decoding into it, and returning
            // to the tab is instant (no stop→play restart). The park has to work by
            // SIZE, not visibility: libVLC's vout window ignores the AWT visible
            // flag and kept painting over the Home screen, so the wrapper is moved
            // off-screen at 1x1 dp instead — the vout child follows its parent.
            SwingPanel(
                // Compose wraps the component in its own JPanel container. Give it a
                // black background (its default is Swing's light gray) so neither a
                // 1px layout seam nor a pre-attach frame can paint a light line or
                // rectangle inside the dark player.
                background = Color.Black,
                factory = {
                    JPanel(BorderLayout()).apply {
                        background = java.awt.Color.BLACK
                        isOpaque = true
                        videoCanvas.background = java.awt.Color.BLACK
                        add(videoCanvas, BorderLayout.CENTER)
                    }
                },
                update = { panel ->
                    panel.background = java.awt.Color.BLACK
                    panel.isOpaque = true
                    panel.revalidate()
                    panel.repaint()
                },
                modifier = if (videoVisible) {
                    Modifier.fillMaxSize()
                } else {
                    // Parked: fully clipped by the window root at negative coords.
                    Modifier.size(1.dp).offset(x = (-4).dp, y = (-4).dp)
                },
            )
        }
    }, hud = {
        // Полоса под панель резервируется РОВНО по её измеренной высоте.
        //
        // Здесь стоял грубый минимум 176 dp — заметно больше, чем занимает сама
        // панель (замерено ~88 dp), и разница просто оставалась чёрной: на полном
        // экране видео получало 878 px там, где помещалось 975. Минимум нужен не
        // сам по себе, а чтобы картинка не прыгала, когда панель прячется; для
        // этого достаточно запомнить её собственную высоту.
        val controlBarMin = with(LocalDensity.current) {
            controlBarHeight.takeIf { it > 0 }?.toDp() ?: 88.dp
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth().heightIn(min = controlBarMin).onGloballyPositioned { coords ->
                val h = coords.size.height
                PlayerDiagnostics.log("hud.box.measure", "height=${h}px; menu=${activeHudMenu ?: "none"}; controlsVisible=$controlsVisible; reserved=$controlBarMin")
            },
        ) {
        // Reserve the vertical space the seek slider, the transport row and the
        // picker panel chrome always need (~184dp); the picker list may use only
        // what remains. Without this cap a short window made the HUD taller than
        // the player itself: the overflow pushed the slider past the bottom
        // window edge ("progress bar sinks") and the menu top slid under the
        // heavyweight video surface, which always paints above Compose
        // ("quality menu is clipped").
        val pickerListMaxHeight = (maxHeight - 184.dp).coerceAtLeast(96.dp).coerceAtMost(240.dp)
        androidx.compose.animation.AnimatedVisibility(
            visible = controlsVisible && videoVisible,
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).zIndex(2f)
                .onPointerEvent(PointerEventType.Enter) {
                    PlayerDiagnostics.log("hud.pointerEvent", "type=ENTER")
                    hudHovered = true
                    showControls()
                }
                .onPointerEvent(PointerEventType.Exit) {
                    PlayerDiagnostics.log("hud.pointerEvent", "type=EXIT")
                    hudHovered = false
                    hudPressed = false
                    showControls()
                }
                .onPointerEvent(PointerEventType.Move) { showControls() }
                .onPointerEvent(PointerEventType.Press) {
                    PlayerDiagnostics.log("hud.pointerEvent", "type=PRESS")
                    hudPressed = true
                    showControls()
                }
                .onPointerEvent(PointerEventType.Release) {
                    PlayerDiagnostics.log("hud.pointerEvent", "type=RELEASE")
                    hudPressed = false
                    showControls()
                },
            // A placement transform preserves the HUD's measured height. Unlike
            // expand/shrink, it does not resize the native VLC surface per frame.
            enter = slideInVertically(tween(hudEnterMs)) { it } + fadeIn(tween(hudEnterMs)),
            exit = slideOutVertically(tween(hudExitMs)) { it } + fadeOut(tween(hudExitMs)),
        ) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xF2131318))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    // Высота именно ПАНЕЛИ, без раскрытого меню — она и есть та
                    // полоса, которую нужно держать зарезервированной, чтобы
                    // картинка не меняла размер при показе/скрытии управления.
                    .onGloballyPositioned { coords ->
                        if (activeHudMenu == null && coords.size.height > 0) {
                            controlBarHeight = coords.size.height
                        }
                    },
            ) {
                    AnimatedVisibility(
                        visible = activeHudMenu != null,
                        enter = slideInVertically(tween(menuEnterMs)) { it / 3 } + fadeIn(tween(menuEnterMs)),
                        exit = slideOutVertically(tween(menuExitMs)) { it / 3 } + fadeOut(tween(menuExitMs)),
                    ) {
                        PlayerDiagnostics.log("hud.menu.visible", "menu=${activeHudMenu ?: "closing:${displayedHudMenu}"}; boxMin=176dp")
                        when (displayedHudMenu) {
                            PlayerHudMenu.AUDIO -> PlayerChoicePanel(
                                title = "Аудиодорожка",
                                choices = audioTracks.map { (id, label) -> PlayerChoice(id, label, label == selectedDub) },
                                onClose = { activeHudMenu = null; showControls() },
                                onSelect = { id ->
                                    // Hide BEFORE the menu-close relayout: resizing
                                    // the native video window flashes a white edge
                                    // for a frame. Poll restores it when playing.
                                    runCatching { if (!overlay) videoCanvas.isVisible = false }
                                    activeHudMenu = null
                                    val track = audioTracks.firstOrNull { it.first == id }
                                    if (track != null) {
                                        val label = track.second
                                        selectedDub = label
                                        dubApply = 10
                                        showControls()
                                        onDubChange(label)
                                        scope.launch(playerDispatcher) { runCatching { player.audio().setTrack(id) } }
                                    }
                                },
                                modifier = Modifier.padding(bottom = 6.dp),
                                listMaxHeight = pickerListMaxHeight,
                            )
                            PlayerHudMenu.QUALITY -> PlayerChoicePanel(
                                title = "Качество",
                                choices = variants.map { PlayerChoice(it, it.quality, it.quality == currentQuality) },
                                onClose = { activeHudMenu = null; showControls() },
                                onSelect = { variant ->
                                    runCatching { if (!overlay) videoCanvas.isVisible = false }
                                    activeHudMenu = null
                                    showControls()
                                    if (variant.url != currentUrl) {
                                        resumeNext = true
                                        currentQuality = variant.quality
                                        currentUrl = variant.url
                                        onQualityChange(variant.quality)
                                    }
                                },
                                modifier = Modifier.padding(bottom = 6.dp),
                                listMaxHeight = pickerListMaxHeight,
                            )
                            PlayerHudMenu.SOURCE -> PlayerChoicePanel(
                                title = "Источник",
                                choices = buildList<PlayerChoice<String?>> {
                                    add(PlayerChoice(null, "Авто", activeSource == null))
                                    sources.forEach { source ->
                                        add(PlayerChoice(source, source, source == activeSource))
                                    }
                                },
                                onClose = { activeHudMenu = null; showControls() },
                                onSelect = { source ->
                                    runCatching { if (!overlay) videoCanvas.isVisible = false }
                                    activeHudMenu = null
                                    showControls()
                                    PlayerDiagnostics.log("hud.source.select", "source=${source ?: "auto"}")
                                    onSourceChange(source)
                                },
                                modifier = Modifier.padding(bottom = 6.dp),
                                listMaxHeight = pickerListMaxHeight,
                            )
                            PlayerHudMenu.VOICE -> PlayerChoicePanel(
                                title = "Озвучка",
                                // Share of this title's views per dub — which one people
                                // actually watch THIS anime in. Anixart carries the
                                // count on its own translations; any other source
                                // (Kodik, balancer…) reports none, so those fall back
                                // to Anixart's stats for the same title matched by
                                // dub name (voiceShares). Still-unknown dubs leave
                                // the column blank.
                                choices = remember(voices, activeVoiceId, voiceShares) {
                                    // Одна шкала на весь список. Раньше озвучки со своими
                                    // просмотрами (Yummy) и без них (подставленные из Anixart
                                    // по имени) складывались в один знаменатель — проценты
                                    // выходили бессмысленные. Есть свои цифры хоть у одной —
                                    // считаем только по своим; нет ни у одной — все по Anixart.
                                    val withViews = voiceSharesOnOneScale(voices, voiceShares)
                                    val total = withViews.sumOf { it.second }
                                    withViews.map { (v, views) ->
                                        PlayerChoice(
                                            value = v.id,
                                            label = v.name,
                                            selected = v.id == activeVoiceId,
                                            trailing = if (total > 0 && views > 0) {
                                                "${(views * 100.0 / total).roundToInt().coerceAtLeast(1)}%"
                                            } else {
                                                null
                                            },
                                        )
                                    }
                                },
                                onClose = { activeHudMenu = null; showControls() },
                                onSelect = { id ->
                                    runCatching { if (!overlay) videoCanvas.isVisible = false }
                                    activeHudMenu = null
                                    showControls()
                                    PlayerDiagnostics.log("hud.voice.select", "id=$id")
                                    onVoiceChange(id)
                                },
                                modifier = Modifier.padding(bottom = 6.dp),
                                listMaxHeight = pickerListMaxHeight,
                            )
                            PlayerHudMenu.EPISODE -> PlayerChoicePanel(
                                title = "Серия",
                                choices = episodes.map {
                                    PlayerChoice(
                                        value = it.number,
                                        label = if (it.playable) it.title else "${it.title} · недоступно",
                                        selected = it.number == activeEpisode,
                                        enabled = it.playable,
                                    )
                                },
                                onClose = { activeHudMenu = null; showControls() },
                                onSelect = { number ->
                                    runCatching { if (!overlay) videoCanvas.isVisible = false }
                                    activeHudMenu = null
                                    showControls()
                                    PlayerDiagnostics.log("hud.episode.select", "number=$number")
                                    onEpisodeChange(number)
                                },
                                modifier = Modifier.padding(bottom = 6.dp),
                                listMaxHeight = pickerListMaxHeight,
                            )
                            PlayerHudMenu.SUBTITLES -> PlayerChoicePanel(
                                title = "Субтитры",
                                choices = buildList {
                                    add(PlayerChoice(-1, "Выключены", selectedSubtitle < 0))
                                    subtitleTracks.forEach { (id, label) -> add(PlayerChoice(id, label.ifBlank { "Дорожка $id" }, id == selectedSubtitle)) }
                                    add(PlayerChoice(SUBTITLE_OPEN_FILE, "Открыть файл (.srt, .ass, .vtt)…"))
                                },
                                onClose = { activeHudMenu = null; showControls() },
                                onSelect = { id ->
                                    activeHudMenu = null
                                    showControls()
                                    if (id == SUBTITLE_OPEN_FILE) openSubtitleFile() else selectSubtitle(id)
                                },
                                modifier = Modifier.padding(bottom = 6.dp),
                                listMaxHeight = pickerListMaxHeight,
                            )
                            PlayerHudMenu.EQUALIZER -> PlayerChoicePanel(
                                title = "Эквалайзер",
                                choices = EQUALIZER_PRESETS.map { (key, label) -> PlayerChoice(key, label, key == audioPreset) },
                                onClose = { activeHudMenu = null; showControls() },
                                onSelect = { key ->
                                    activeHudMenu = null
                                    showControls()
                                    PlayerDiagnostics.log("controls.equalizer", "preset=$key")
                                    onAudioPresetChange(key)
                                },
                                modifier = Modifier.padding(bottom = 6.dp),
                                listMaxHeight = pickerListMaxHeight,
                            )
                            PlayerHudMenu.SPEED -> PlayerChoicePanel(
                                title = "Скорость",
                                choices = SPEED_STEPS.map {
                                    PlayerChoice(it, if (it == 1f) "Обычная (1x)" else "${it}x", kotlin.math.abs(playbackSpeed - it) < 0.01f)
                                },
                                onClose = { activeHudMenu = null; showControls() },
                                onSelect = { rate ->
                                    activeHudMenu = null
                                    showControls()
                                    playbackSpeed = rate
                                },
                                modifier = Modifier.padding(bottom = 6.dp),
                                listMaxHeight = pickerListMaxHeight,
                            )
                            PlayerHudMenu.SCALE -> PlayerChoicePanel(
                                title = "Масштаб видео",
                                choices = buildList {
                                    add(PlayerChoice("fit", "Вписать в окно", !fillMode && videoZoom <= 1.001f))
                                    add(PlayerChoice("fill", "Заполнить экран (обрезает края)", fillMode))
                                    ZOOM_STEPS.forEach { p ->
                                        add(PlayerChoice("z$p", "$p%", !fillMode && (videoZoom * 100).roundToInt() == p))
                                    }
                                },
                                onClose = { activeHudMenu = null; showControls() },
                                onSelect = { key ->
                                    activeHudMenu = null
                                    showControls()
                                    when {
                                        key == "fit" -> { fillMode = false; videoZoom = 1f }
                                        key == "fill" -> { fillMode = true; videoZoom = 1f }
                                        else -> {
                                            fillMode = false
                                            videoZoom = (key.removePrefix("z").toIntOrNull() ?: 100) / 100f
                                        }
                                    }
                                    PlayerDiagnostics.log("hud.scale.select", "key=$key")
                                },
                                modifier = Modifier.padding(bottom = 6.dp),
                                listMaxHeight = pickerListMaxHeight,
                            )
                            null -> Unit
                        }
                    }
                    // Loading state. `buffering` hits 100% ~150ms after a seek, but the
                    // adaptive demuxer then spends another 1.5-6s fetching and decoding
                    // the segment before a picture appears — measured. Tying the
                    // indicator to the buffer alone made it vanish while the screen was
                    // still black, which read as a frozen player. Keep it up until
                    // playback has actually resumed at the seek target.
                    if (bufferPercent < 100f) {
                        Text(
                            "Загрузка… ${bufferPercent.toInt()}%",
                            color = Color(0xFFFFB4A2),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                        )
                    }
                    if (streamNotice != null) {
                        Text(
                            streamNotice,
                            color = Color(0xFFFFB4A2),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                        )
                    }
                    hudNotice?.let { notice ->
                        Text(
                            notice,
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                        )
                    }
                    val shown = if (scrubbing) scrubValue else position.toFloat()
                    // NO buffered-ahead bar here on purpose. libVLC does not expose a
                    // buffered time range, and its byte counters are useless for HLS:
                    // the adaptive demuxer fetches segments on its own threads, so
                    // inputBytesRead stays at ~10 KB while demuxBytesRead climbs into
                    // the megabytes (verified in the diagnostics log). Anything drawn
                    // from those numbers would be decoration, not real progress.
                    // Buffering state is surfaced by the indicator below instead.
                    // Подсказка над дорожкой: время под курсором и «Опенинг» / «Эндинг»,
                    // если курсор над интервалом. Слушаем движение на РОДИТЕЛЕ — событие
                    // доходит и до Slider, перемотке это не мешает.
                    var hoverX by remember { mutableStateOf(-1f) }
                    var trackWidthPx by remember { mutableStateOf(0) }
                    Box(
                        Modifier.fillMaxWidth()
                            .onSizeChanged { trackWidthPx = it.width }
                            .onPointerEvent(PointerEventType.Move) { hoverX = it.changes.firstOrNull()?.position?.x ?: -1f }
                            .onPointerEvent(PointerEventType.Exit) { hoverX = -1f },
                    ) {
                        if (hoverX >= 0f && trackWidthPx > 0 && length > 0 && !scrubbing) {
                            val density = LocalDensity.current
                            val inset = with(density) { 2.dp.toPx() }
                            val hoverMs = ((hoverX - inset) / (trackWidthPx - inset * 2).coerceAtLeast(1f) * length).toLong().coerceIn(0L, length)
                            val inRange = when {
                                validOpening != null && hoverMs in validOpening.startMs..validOpening.endMs -> "Опенинг"
                                validEnding != null && hoverMs in validEnding.startMs..validEnding.endMs -> "Эндинг"
                                else -> null
                            }
                            val label = fmtTime(hoverMs) + (inRange?.let { " · $it" } ?: "")
                            var labelWidth by remember { mutableStateOf(0) }
                            Text(
                                label,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                modifier = Modifier
                                    .onSizeChanged { labelWidth = it.width }
                                    .offset {
                                        val x = (hoverX - labelWidth / 2f).roundToInt().coerceIn(0, (trackWidthPx - labelWidth).coerceAtLeast(0))
                                        IntOffset(x, with(density) { -22.dp.roundToPx() })
                                    }
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.Black.copy(alpha = 0.72f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Slider(
                        value = shown.coerceIn(0f, length.toFloat().coerceAtLeast(1f)),
                        valueRange = 0f..length.toFloat().coerceAtLeast(1f),
                        onValueChange = { if (!scrubbing) PlayerDiagnostics.log("slider.scrubStart", "positionMs=$position; lengthMs=$length"); scrubbing = true; scrubValue = it; showControls() },
                        onValueChangeFinished = {
                            PlayerDiagnostics.log("slider.scrubEnd", "scrubValueMs=${scrubValue.toLong()}; lengthMs=$length; scrubbing=false")
                            seekTo(scrubValue.toLong())
                            scrubbing = false
                            showControls()
                        },
                        // Inactive track is transparent so the buffered (grey) bar
                        // painted underneath shows through, like a web player.
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFF4D00),
                            activeTrackColor = Color(0xFFFF4D00),
                            inactiveTrackColor = Color(0xFF34343C),
                        ),
                    )
                    // Опенинг и эндинг — полосками поверх дорожки, ровно по таймингам
                    // (AniSkip / Yummy / своё распознавание — те же validOpening и
                    // validEnding, что у кнопок «Пропустить»). Заимствованный у соседней
                    // серии интервал (approximate) рисуется бледнее: он примерный.
                    // Canvas не ловит указатель, перемотке не мешает.
                    SkipRangeMarkers(
                        opening = validOpening,
                        ending = validEnding,
                        lengthMs = length,
                        modifier = Modifier.matchParentSize(),
                    )
                    }
                    if (endingButtonVisible && validEnding != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                enabled = autoSkipReady,
                                onClick = {
                                    // Эндинг — та же болезнь и то же лекарство.
                                    openingSkipTarget(validEnding, position)?.let(::seekTo)
                                    showControls()
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF6336),
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text(if (validEnding.approximate) "Пропустить эндинг ≈" else "Пропустить эндинг", fontSize = 13.sp)
                            }
                        }
                    }
                    if (recapButtonVisible && validRecap != null && !openingButtonVisible) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.End) {
                            Button(
                                enabled = autoSkipReady,
                                onClick = { openingSkipTarget(validRecap, position)?.let(::seekTo); showControls() },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6336), contentColor = Color.White),
                            ) { Text("Пропустить рекап", fontSize = 13.sp) }
                        }
                    }
                    if (openingButtonVisible && validOpening != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                enabled = autoSkipReady,
                                onClick = {
                                    // Только вперёд: см. openingSkipTarget.
                                    openingSkipTarget(validOpening, position)?.let(::seekTo)
                                    showControls()
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF6336),
                                    contentColor = Color.White,
                                ),
                            ) {
                                // «≈» — интервал занят у другой серии этого сезона и
                                // может промахнуться на несколько секунд. Молча
                                // делать вид, что он точный, нечестно: зритель хотя бы
                                // понимает, почему прыжок вышел неровным.
                                Text(
                                    if (validOpening.approximate) "Пропустить опенинг ≈" else "Пропустить опенинг",
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val compact = maxWidth < 900.dp
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${fmtTime(shown.toLong())} / ${fmtTime(length)}",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                                // Episode navigation: prev / current (opens the picker) /
                                // next, right where playback is being watched.
                                if (episodes.size > 1) {
                                    val currentIndex = episodes.indexOfFirst { it.number == activeEpisode }
                                    val prev = episodes.take(currentIndex.coerceAtLeast(0)).lastOrNull { it.playable }
                                    val next = if (currentIndex >= 0) episodes.drop(currentIndex + 1).firstOrNull { it.playable } else null
                                    IconButton(
                                        onClick = { prev?.let { PlayerDiagnostics.log("controls.prevEpisode", "to=${it.number}"); onEpisodeChange(it.number) }; showControls() },
                                        enabled = prev != null,
                                        modifier = Modifier.size(34.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.SkipPrevious,
                                            contentDescription = "Предыдущая серия",
                                            tint = if (prev != null) Color.White else Color.White.copy(alpha = 0.3f),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    Row(
                                        Modifier.clip(RoundedCornerShape(8.dp))
                                            .clickable { PlayerDiagnostics.log("controls.toggleMenu", "menu=EPISODE"); toggleHudMenu(PlayerHudMenu.EPISODE) }
                                            .padding(horizontal = 8.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            episodeCounterLabel(activeEpisode, episodes.size, episodeTotal),
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                        )
                                    }
                                    IconButton(
                                        onClick = { next?.let { PlayerDiagnostics.log("controls.nextEpisode", "to=${it.number}"); onEpisodeChange(it.number) }; showControls() },
                                        enabled = next != null,
                                        modifier = Modifier.size(34.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.SkipNext,
                                            contentDescription = "Следующая серия",
                                            tint = if (next != null) Color.White else Color.White.copy(alpha = 0.3f),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }

                            // Equal layout thirds keep every click target separate;
                            // long voice labels can no longer cover the centre row.
                            Row(
                                Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                            IconButton(onClick = { PlayerDiagnostics.log("controls.skipBack", "clicked=true"); skipBy(-5_000); showSkipFeedback(-5); showControls() }, modifier = Modifier.size(42.dp)) {
                                Icon(Icons.Filled.Replay5, contentDescription = "-5 сек", tint = Color.White, modifier = Modifier.size(26.dp))
                            }
                            IconButton(onClick = { PlayerDiagnostics.log("controls.playPause", "wasPlaying=$playing; ended=$ended"); togglePlay() }, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = Color.White,
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                            IconButton(onClick = { PlayerDiagnostics.log("controls.skipForward", "clicked=true"); skipBy(5_000); showSkipFeedback(5); showControls() }, modifier = Modifier.size(42.dp)) {
                                Icon(Icons.Filled.Forward5, contentDescription = "+5 сек", tint = Color.White, modifier = Modifier.size(26.dp))
                            }
                            }

                            Row(
                                Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End,
                            ) {
                            // The icon is a real mute control, not decoration.
                            IconButton(
                                onClick = {
                                    val wasMuted = muted
                                    muted = !muted
                                    val target = if (muted) 0 else volume.toInt()
                                    PlayerDiagnostics.log("volume.muteToggle", "wasMuted=$wasMuted; nowMuted=$muted; targetVol=$target; currentVol=${volume.toInt()}")
                                    volumeApply = 4
                                    volumeCommand.submit(target)
                                    showControls()
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    if (muted || volume <= 0f) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                                    contentDescription = if (muted) "Включить звук" else "Выключить звук",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            if (!compact) Slider(
                                value = volume,
                                valueRange = 0f..100f,
                                onValueChange = {
                                    val oldVol = volume
                                    volume = it
                                    muted = false
                                    val target = it.toInt()
                                    PlayerDiagnostics.log("volume.sliderDrag", "old=$oldVol; new=$it; target=$target")
                                    volumeApply = 4
                                    volumeCommand.submit(target)
                                    // Persist each logical value immediately. The
                                    // settings writer is conflated, so dragging
                                    // cannot queue unbounded disk writes.
                                    onVolumeChange(target)
                                    showControls()
                                },
                                onValueChangeFinished = { PlayerDiagnostics.log("volume.sliderFinished"); showControls() },
                                modifier = Modifier.width(110.dp).padding(horizontal = 6.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color.White,
                                    inactiveTrackColor = Color(0xFF4A4A52),
                                ),
                            )
                            // Источник потока — the same picker as the top panel, but the
                            // HUD is below the heavyweight video, so this button is always
                            // visible (the top panel is painted over by the video).
                            if (sources.isNotEmpty()) {
                                Row(
                                    Modifier.clip(RoundedCornerShape(8.dp))
                                        .clickable { PlayerDiagnostics.log("controls.toggleMenu", "menu=SOURCE; current=${activeHudMenu}"); toggleHudMenu(PlayerHudMenu.SOURCE) }
                                        .widthIn(max = if (compact) 110.dp else 170.dp)
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Cloud, contentDescription = "Источник", tint = Color.White, modifier = Modifier.size(18.dp))
                                    Text(
                                        activeSource ?: "Авто",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                            }
                            // Озвучка (translation) — switch the stream's voiceover; same
                            // reasoning as the source button for living on the HUD.
                            if (voices.size > 1) {
                                Row(
                                    Modifier.clip(RoundedCornerShape(8.dp))
                                        .clickable { PlayerDiagnostics.log("controls.toggleMenu", "menu=VOICE; current=${activeHudMenu}"); toggleHudMenu(PlayerHudMenu.VOICE) }
                                        .widthIn(max = if (compact) 110.dp else 170.dp)
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.RecordVoiceOver, contentDescription = "Озвучка", tint = Color.White, modifier = Modifier.size(18.dp))
                                    Text(
                                        voices.firstOrNull { it.id == activeVoiceId }?.name ?: "Авто",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                            }
                            // Озвучка (dub) — a libVLC audio-track switch; shown only
                            // when the stream actually carries more than one.
                            if (audioTracks.size > 1) {
                                val currentDub = selectedDub ?: audioTracks.first().second
                                Row(
                                    Modifier.clip(RoundedCornerShape(8.dp))
                                        .clickable { PlayerDiagnostics.log("controls.toggleMenu", "menu=AUDIO; current=${activeHudMenu}"); toggleHudMenu(PlayerHudMenu.AUDIO) }
                                        .widthIn(max = if (compact) 110.dp else 170.dp)
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Translate, contentDescription = "Озвучка", tint = Color.White, modifier = Modifier.size(18.dp))
                                    Text(
                                        currentDub,
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                            }
                            // Quality (only when there's a real choice)
                            if (variants.size > 1) {
                                Row(
                                    Modifier.clip(RoundedCornerShape(8.dp))
                                        .clickable { PlayerDiagnostics.log("controls.toggleMenu", "menu=QUALITY; current=${activeHudMenu}"); toggleHudMenu(PlayerHudMenu.QUALITY) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.HighQuality, contentDescription = "Качество", tint = Color.White, modifier = Modifier.size(18.dp))
                                    if (!compact) Text(
                                        currentQuality.ifBlank { "Auto" },
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                            }
                            // «Случайное аниме после последней серии» — the shuffle
                            // toggle: when the finished episode has no next one, the
                            // player hops to a random trending/watching/random title.
                            if (onToggleAutoSwitch != null) {
                                IconButton(onClick = {
                                    PlayerDiagnostics.log("controls.autoSwitch", "now=${!autoSwitchRandom}")
                                    onToggleAutoSwitch()
                                    showControls()
                                }, modifier = Modifier.size(36.dp)) {
                                    Icon(
                                        Icons.Filled.Shuffle,
                                        contentDescription = if (autoSwitchRandom) {
                                            "Случайное аниме после последней серии: вкл"
                                        } else {
                                            "Случайное аниме после последней серии: выкл"
                                        },
                                        tint = if (autoSwitchRandom) Color(0xFFFF6336) else Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            // Субтитры: дорожки потока и внешний файл. Кнопка есть всегда —
                            // у «raw» без дорожек как раз и нужен файл.
                            Row(
                                Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable { PlayerDiagnostics.log("controls.toggleMenu", "menu=SUBTITLES"); toggleHudMenu(PlayerHudMenu.SUBTITLES) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Subtitles,
                                    contentDescription = "Субтитры",
                                    tint = if (selectedSubtitle >= 0) Color(0xFFFF6336) else Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            // Эквалайзер: пресеты libVLC («Наушники», «Мягко», «Речь»…).
                            Row(
                                Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable { PlayerDiagnostics.log("controls.toggleMenu", "menu=EQUALIZER"); toggleHudMenu(PlayerHudMenu.EQUALIZER) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.GraphicEq,
                                    contentDescription = "Эквалайзер",
                                    tint = if (audioPreset.isNotBlank()) Color(0xFFFF6336) else Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            // Скорость воспроизведения (как на мобильной версии).
                            Row(
                                Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable { PlayerDiagnostics.log("controls.toggleMenu", "menu=SPEED"); toggleHudMenu(PlayerHudMenu.SPEED) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val sped = kotlin.math.abs(playbackSpeed - 1f) > 0.01f
                                Icon(
                                    Icons.Filled.Speed,
                                    contentDescription = "Скорость",
                                    tint = if (sped) Color(0xFFFF6336) else Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                                if (!compact) Text(
                                    "${playbackSpeed}x",
                                    color = if (sped) Color(0xFFFF6336) else Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                            // Масштаб: вписать / заполнить (crop) / зум в процентах.
                            Row(
                                Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable { PlayerDiagnostics.log("controls.toggleMenu", "menu=SCALE"); toggleHudMenu(PlayerHudMenu.SCALE) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val zoomed = fillMode || videoZoom > 1.001f
                                Icon(
                                    Icons.Filled.AspectRatio,
                                    contentDescription = "Масштаб видео",
                                    tint = if (zoomed) Color(0xFFFF6336) else Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                                if (!compact) Text(
                                    when {
                                        fillMode -> "Заполнить"
                                        videoZoom > 1.001f -> "${(videoZoom * 100).roundToInt()}%"
                                        else -> "Вписать"
                                    },
                                    color = if (zoomed) Color(0xFFFF6336) else Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                            // Скрыть комментарии — временно, до конца сеанса. Кнопка
                            // есть только когда им вообще есть что показывать.
                            if (commentsEnabled && comments.isNotEmpty()) {
                                IconButton(onClick = {
                                    commentsHidden = !commentsHidden
                                    PlayerDiagnostics.log("controls.comments", "hidden=$commentsHidden")
                                    showControls()
                                }) {
                                    Icon(
                                        if (commentsHidden) Icons.Filled.SpeakerNotesOff else Icons.Filled.Forum,
                                        contentDescription = if (commentsHidden) "Показать комментарии" else "Скрыть комментарии",
                                        tint = if (commentsHidden) Color.White else Color(0xFFFF6336),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            // «Скрыть чат». Кнопка есть, только пока чат включён в
                            // настройках, — иначе она обещала бы то, чего нет.
                            if (chat.enabled) {
                                IconButton(onClick = {
                                    chatHidden = !chatHidden
                                    PlayerDiagnostics.log("controls.chat", "hidden=$chatHidden")
                                    showControls()
                                }) {
                                    Icon(
                                        if (chatHidden) Icons.Filled.SpeakerNotesOff else ChatIcon,
                                        contentDescription = if (chatHidden) "Показать чат" else "Скрыть чат",
                                        tint = if (chatHidden) Color.White else Color(0xFFFF6336),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            // Закрепить панель: перестаёт прятаться по таймеру.
                            IconButton(onClick = {
                                PlayerDiagnostics.log("controls.pinHud", "now=${!hudPinned}")
                                onToggleHudPin()
                                showControls()
                            }) {
                                Icon(
                                    if (hudPinned) Icons.Filled.PushPin else Icons.Filled.PushPin,
                                    contentDescription = if (hudPinned) "Открепить панель" else "Закрепить панель",
                                    tint = if (hudPinned) Color(0xFFFF6336) else Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(onClick = { PlayerDiagnostics.log("controls.fullscreen", "current=$fullscreen; action=toggle"); onToggleFullscreen(); showControls() }) {
                                Icon(
                                    if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                    contentDescription = "Полный экран",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            }
                        }
                    }
        }
        }
    }
}, chat = {
    // Пристыкованная панель. Пустая лямбда, когда чата нет, — тогда раскладка не
    // отдаёт ему ни пикселя и не собирает его дерево вовсе.
    if (chatDocked && chatFeed != null) {
        ChatSidePanel(
            feed = chatFeed,
            fontSize = chat.fontSize,
            onHide = { chatHidden = true },
            onTypingChange = { chatTyping = it },
            modifier = Modifier.fillMaxSize(),
        )
    }
})
}

internal data class CommentSurfaceSplit(
    val chat: List<TitleComment>,
    val overlay: List<TitleComment>,
)

/**
 * Разводит настоящие реплики по двум включённым поверхностям без потери общего
 * пула. Деление устойчиво по id, поэтому пересбор экрана не перебрасывает сообщения.
 */
internal fun splitCommentsForSurfaces(
    comments: List<TitleComment>,
    chatEnabled: Boolean,
    overlayEnabled: Boolean,
): CommentSurfaceSplit {
    if (!chatEnabled) return CommentSurfaceSplit(emptyList(), if (overlayEnabled) comments else emptyList())
    if (!overlayEnabled) return CommentSurfaceSplit(comments, emptyList())
    if (comments.size == 1) return CommentSurfaceSplit(emptyList(), comments)

    val chat = ArrayList<TitleComment>(comments.size / 2)
    val overlay = ArrayList<TitleComment>(comments.size / 2)
    comments.forEach { comment ->
        val identity = if (comment.id != 0L) comment.id else comment.message.hashCode().toLong()
        if ((identity and 1L) == 0L) overlay += comment else chat += comment
    }
    // Источник теоретически может выдать id одной чётности. Оставляем обе
    // поверхности живыми и в этом случае, не создавая дубликатов.
    if (chat.isEmpty() && overlay.size > 1) chat += overlay.removeAt(overlay.lastIndex)
    if (overlay.isEmpty() && chat.size > 1) overlay += chat.removeAt(chat.lastIndex)
    return CommentSurfaceSplit(chat, overlay)
}

/** Единственный источник истины для видимости danmaku. */
internal fun shouldShowDanmakuOverlay(
    commentsEnabled: Boolean,
    commentsHidden: Boolean,
    chatVisible: Boolean,
    videoVisible: Boolean,
): Boolean = commentsEnabled && !commentsHidden && videoVisible

internal fun shouldShowChatOverlay(
    chatFeedAvailable: Boolean,
    chatHidden: Boolean,
    overlayMode: Boolean,
    videoVisible: Boolean,
): Boolean = chatFeedAvailable && !chatHidden && overlayMode && videoVisible

/**
 * Ставит панель управления ПОВЕРХ видео ([overlay] = true) или ПОД ним.
 *
 * Нативная поверхность VLC — heavyweight-окно, и Windows рисует его поверх любой
 * Compose-графики. В этом режиме панели приходится отдавать полосу окна, а видео
 * получает остаток: замерено — при области ~1040 px картинке доставалось 863, а
 * стоило открыть меню, как она ужималась до 616 и прыгала обратно при закрытии.
 * Когда кадры рисует Compose, оба слоя живут в одних и тех же границах.
 */
@Composable
private fun PlayerFrame(
    overlay: Boolean,
    modifier: Modifier,
    /**
     * Сколько пикселей справа отдано чату. 0 — чата нет, и раскладка ровно та же,
     * что была до его появления.
     */
    chatWidthPx: Int,
    chatSide: String,
    video: @Composable () -> Unit,
    hud: @Composable () -> Unit,
    chat: @Composable () -> Unit,
) {
    Layout(contents = listOf(video, hud, chat), modifier = modifier) { measurables, constraints ->
        val (videoMeasurables, hudMeasurables, chatMeasurables) = measurables
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // Панель не имеет права съесть окно: на узком экране половина — уже предел,
        // иначе видео превращается в марку на конверте.
        val dock = chatDockGeometry(width, chatWidthPx, chatSide)
        val chatWidth = dock.chatWidth
        val mainWidth = dock.mainWidth
        // Панель управления живёт над ВИДЕО, а не над всем окном: заезжая под чат,
        // она прятала бы его нижние сообщения и поле ввода.
        val hudPlaceables = hudMeasurables.map {
            it.measure(Constraints(minWidth = mainWidth, maxWidth = mainWidth, maxHeight = height))
        }
        val hudHeight = hudPlaceables.maxOfOrNull { it.height } ?: 0
        val videoHeight = if (overlay) height else (height - hudHeight).coerceAtLeast(0)
        // minHeight намеренно 0: в нативном режиме спрятанная поверхность
        // паркуется размером 1 dp, и жёсткая высота сломала бы парковку.
        val videoConstraints = Constraints(maxWidth = mainWidth, maxHeight = videoHeight)
        val videoPlaceables = videoMeasurables.map { it.measure(videoConstraints) }
        val chatPlaceables = if (chatWidth > 0) {
            chatMeasurables.map {
                it.measure(Constraints(minWidth = chatWidth, maxWidth = chatWidth, minHeight = height, maxHeight = height))
            }
        } else {
            emptyList()
        }
        layout(width, height) {
            videoPlaceables.forEach { it.place(dock.mainX, 0) }
            hudPlaceables.forEach { it.place(dock.mainX, height - it.height) }
            chatPlaceables.forEach { it.place(dock.chatX, 0) }
        }
    }
}

/** Больше этой доли окна чат не занимает никогда. */
internal data class ChatDockGeometry(val mainWidth: Int, val chatWidth: Int, val mainX: Int, val chatX: Int)

/** Same content tree for both sides: only placement changes, never the video/session identity. */
internal fun chatDockGeometry(width: Int, requestedWidth: Int, side: String): ChatDockGeometry {
    val available = width.coerceAtLeast(0)
    val chatWidth = requestedWidth.coerceIn(0, available / 2)
    val mainWidth = available - chatWidth
    return ChatDockGeometry(mainWidth, chatWidth, if (side == "left") chatWidth else 0,
        if (side == "left") 0 else mainWidth)
}

/**
 * Рисует кадры из [sink] средствами Compose.
 *
 * Кадр читается ВНУТРИ лямбды отрисовки: так изменение состояния перезапускает
 * только фазу рисования, а не композицию всего плеера — иначе двадцать четыре раза
 * в секунду пересобиралась бы вся панель управления.
 */
@Composable
internal fun ComposeVideoSurface(
    sink: VideoFrameSink,
    fillMode: Boolean,
    zoom: Float,
    enhance: VideoEnhance.Level,
    modifier: Modifier = Modifier,
) {
    val frame = remember(sink) { mutableStateOf<VideoFrameSink.FrameLease?>(null) }
    DisposableEffect(sink) {
        onDispose {
            frame.value?.close()
            frame.value = null
        }
    }
    LaunchedEffect(sink) {
        var seen = -1L
        var lastReport = System.nanoTime()
        while (true) {
            // Привязка к кадрам окна: обновляемся ровно так часто, как экран
            // способен показать, и ни разу лишний.
            withFrameNanos { }
            val revision = sink.revision.get()
            if (revision != seen) {
                val next = sink.acquireLatest()
                if (next == null) {
                    frame.value?.close()
                    frame.value = null
                    seen = revision
                } else if (next.revision != seen) {
                    val previous = frame.value
                    frame.value = next
                    seen = next.revision
                    // Смена происходит на UI-потоке между кадрами: предыдущая
                    // отрисовка завершена, а другие окна держат собственные lease.
                    previous?.close()
                } else {
                    next.close()
                }
            }
            val now = System.nanoTime()
            if (now - lastReport > 5_000_000_000L) {
                lastReport = now
                PlayerDiagnostics.log("player.render.rate", "fps=%.1f".format(sink.decodedPerSecond()))
            }
        }
    }
    val effect = remember(enhance) { VideoEnhance.renderEffect(enhance) }
    androidx.compose.foundation.Canvas(
        // Слой нужен ОТДЕЛЬНЫЙ: эффект должен применяться к кадру, а не ко
        // всему экрану вместе с панелью управления поверх него.
        modifier.graphicsLayer { renderEffect = effect },
    ) {
        val lease = frame.value ?: return@Canvas
        val image = lease.image
        // Рисуем не весь кадр, а его содержательную часть: раздачи приходят с вшитой
        // чёрной рамкой (см. VideoFrameSink), и без обрезки она попадает на экран как
        // полоса над видео, которую не убрать ни «вписать», ни «заполнить».
        val content = lease.contentRect
        val srcX = content[0].toFloat()
        val srcY = content[1].toFloat()
        val imageWidth = content[2].toFloat().takeIf { it > 0f } ?: image.width.toFloat()
        val imageHeight = content[3].toFloat().takeIf { it > 0f } ?: image.height.toFloat()
        if (imageWidth <= 0f || imageHeight <= 0f || size.width <= 0f || size.height <= 0f) return@Canvas
        // «Вписать» — по меньшей стороне, «заполнить» — по большей (края срезаются).
        val base = if (fillMode) {
            maxOf(size.width / imageWidth, size.height / imageHeight)
        } else {
            minOf(size.width / imageWidth, size.height / imageHeight)
        }
        val scale = base * zoom
        val drawWidth = imageWidth * scale
        val drawHeight = imageHeight * scale
        // Растягиваем фильтром Catmull-Rom.
        //
        // ЗАМЕРЕНО на настоящих кадрах аниме (эталон ужат в 1.5 раза — как 720p в
        // окне 1080p — и восстановлен обратно):
        //     билинейная        PSNR 39.15  SSIM 0.9546
        //     Mitchell          PSNR 39.91  SSIM 0.9563
        //     Catmull-Rom       PSNR 40.95  SSIM 0.9606
        // Смена фильтра даёт больше, чем сам шейдер резкости. Из DrawScope доступны
        // только билинейная, мипмапы и Mitchell, поэтому рисуем канвой Skia.
        //
        // Осторожно: Medium в FilterQuality — это НЕ «между Low и High», а мипмапы;
        // они строятся на каждый кадр и нужны при уменьшении, а не увеличении.
        // Именно из-за них режим наложения в первой версии оказался медленнее
        // нативного вывода.
        val left = (size.width - drawWidth) / 2f
        val top = (size.height - drawHeight) / 2f
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawImageRect(
                image,
                org.jetbrains.skia.Rect.makeXYWH(srcX, srcY, imageWidth, imageHeight),
                org.jetbrains.skia.Rect.makeXYWH(left, top, drawWidth, drawHeight),
                org.jetbrains.skia.SamplingMode.CATMULL_ROM,
                null,
                true,
            )
        }
    }
}

/** Zoom steps offered by the «Масштаб видео» menu, in percent over the fitted size. */
private val ZOOM_STEPS = listOf(110, 125, 150, 175, 200)

/**
 * Отметки опенинга и эндинга на дорожке перемотки.
 *
 * Геометрия — как у дорожки Material 3: ползунок шириной 4 dp, дорожка между его
 * половинами. Полоска — половина высоты дорожки по центру, чтобы читалась и на
 * оранжевой (пройденной), и на серой части.
 */
@Composable
private fun SkipRangeMarkers(
    opening: OpeningRange?,
    ending: OpeningRange?,
    lengthMs: Long,
    modifier: Modifier = Modifier,
) {
    if (lengthMs <= 0 || (opening == null && ending == null)) return
    androidx.compose.foundation.Canvas(modifier) {
        val inset = 2.dp.toPx()
        val trackWidth = (size.width - inset * 2).coerceAtLeast(1f)
        val stripeHeight = 8.dp.toPx()
        val top = (size.height - stripeHeight) / 2f
        val radius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
        for (range in listOfNotNull(opening, ending)) {
            val (x0, x1) = skipRangeSpan(range, lengthMs, trackWidth)
            if (x1 <= x0) continue
            drawRoundRect(
                color = Color.White.copy(alpha = if (range.approximate) 0.16f else 0.32f),
                topLeft = androidx.compose.ui.geometry.Offset(inset + x0, top),
                size = androidx.compose.ui.geometry.Size(x1 - x0, stripeHeight),
                cornerRadius = radius,
            )
        }
    }
}

/** Пиксели интервала на дорожке шириной [trackWidth]; обрезано границами серии. */
internal fun skipRangeSpan(range: OpeningRange, lengthMs: Long, trackWidth: Float): Pair<Float, Float> {
    if (lengthMs <= 0 || trackWidth <= 0f) return 0f to 0f
    val start = range.startMs.coerceIn(0L, lengthMs)
    val end = range.endMs.coerceIn(0L, lengthMs)
    val x0 = trackWidth * start / lengthMs
    val x1 = trackWidth * end / lengthMs
    // Совсем короткий интервал всё равно виден: минимум два пикселя.
    return x0 to maxOf(x1, x0 + 2f)
}

/**
 * Пары «озвучка → просмотры» в одной шкале: свои цифры источника, если они есть хоть у
 * одной озвучки (у остальных тогда 0 — процент не рисуется), иначе Anixart по имени.
 */
internal fun voiceSharesOnOneScale(
    voices: List<com.aniblaze.aggregator.model.Translation>,
    fallback: Map<String, Long>,
): List<Pair<com.aniblaze.aggregator.model.Translation, Long>> {
    val ownCounts = voices.any { it.views > 0 }
    return voices.map { v -> v to if (ownCounts) v.views.coerceAtLeast(0) else matchedShare(v.name, fallback) }
}

/**
 * Views for a dub, matched by name against Anixart's per-title stats. Names differ
 * slightly across sources ("AniLibria.TV" vs "AniLibria"), so besides exact equality
 * a containment match is accepted — but only for reasonably long names, so a short
 * tag like "CR" can't swallow every studio.
 */
internal fun matchedShare(name: String, shares: Map<String, Long>): Long {
    if (shares.isEmpty()) return 0L
    fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
    val n = norm(name)
    if (n.isBlank()) return 0L
    var best = 0L
    shares.forEach { (candidate, views) ->
        val cn = norm(candidate)
        val matches = cn == n ||
            (cn.length >= 4 && n.length >= 4 && (cn.contains(n) || n.contains(cn)))
        if (matches && views > best) best = views
    }
    return best
}

/**
 * Turns libVLC's raw audio-track descriptions ("audio0 rus0 - [Русский]",
 * "failover-audio-0 rus0 - [Русский]", "audio0 rus1 - [Русский]" …) into a clean
 * dub picker: de-duplicates the failover copy of each track by its code (rus0 /
 * rus1 / ukr2 …) and numbers repeated languages ("Русский", "Русский 2").
 */
private data class NativeSnapshot(
    val position: Long,
    val length: Long,
    val playing: Boolean,
    val audioTracks: List<Pair<Int, String>>,
    val currentTrack: Int,
    val epoch: PlaybackEpoch?,
    val paused: Boolean,
    val seekable: Boolean,
    /** Дорожки субтитров (id ≥ 0) и текущая (−1 — выключены). */
    val subtitleTracks: List<Pair<Int, String>> = emptyList(),
    val currentSubtitle: Int = -1,
)

/** Keeps at most one queued volume command; dragging the slider cannot flood the
 * single libVLC owner thread with dozens of stale setVolume calls. */
/**
 * Перемотка, склеенная НА УРОВНЕ ПОТОКА КОМАНД.
 *
 * Отмены отложенной задачи мало. Все команды libVLC идут через один поток, и стоит
 * `setTime` до него доехать, отменять уже нечего: вызов исполняется до конца, а на
 * адаптивном HLS это секунды — демультиплексор заново тянет и декодирует сегмент.
 * Резкое таскание ползунка ставило в эту очередь десяток таких вызовов подряд, опрос
 * позиции вставал за ними и переставал отвечать, а сторож зависания считал молчание
 * опроса доказательством того, что libVLC встала, и бросал живой экземпляр. Со
 * стороны это и есть «резко скипнул полоску — поток встал».
 *
 * Здесь в очереди не больше одной заявки: новая просто затирает не начатую. До
 * libVLC доходит текущая и ПОСЛЕДНЯЯ — промежуточные точки зрителю не нужны.
 */
internal class ConflatedLongCommand(
    private val executor: Executor,
    private val action: (Long) -> Unit,
) {
    private val pending = java.util.concurrent.atomic.AtomicReference<Long?>(null)
    private val scheduled = AtomicBoolean(false)

    fun submit(value: Long) {
        pending.set(value)
        if (scheduled.compareAndSet(false, true)) runCatching { executor.execute(::drain) }
    }

    /** Drop a queued seek at a media boundary; an already running command stays on its old media. */
    fun clear() { pending.set(null) }

    private fun drain() {
        while (true) {
            val value = pending.getAndSet(null)
            if (value != null) {
                // Падение одной заявки не имеет права остановить очередь: следующая
                // перемотка должна дойти в любом случае.
                runCatching { action(value) }
                continue
            }
            scheduled.set(false)
            if (pending.get() == null || !scheduled.compareAndSet(false, true)) return
        }
    }
}

/**
 * Сколько неотвеченных опросов подряд считать зависанием.
 *
 * Во время перемотки — БОЛЬШЕ. `setTime` на HLS законно занимает поток команд на
 * секунды, и принимать это за зависание значит бросать живой плеер ровно тогда,
 * когда зритель им активно пользуется. Совсем сторож не отключается: если libVLC
 * встала внутри `setTime`, сама она оттуда не выйдет.
 */
/**
 * Продлевать ли новому экземпляру срок «поток не начался», пока хоронится прошлый.
 *
 * Ограничение по СУММЕ, а не по одному признаку «хоронят». Похороны — это release()
 * на зависшей libVLC, и на ней он не возвращается НИКОГДА; безусловное продление
 * означало, что приговор не может быть вынесен ни при каких условиях, а лестница
 * попыток (свежая ссылка → другая озвучка → другой источник) не тронется вовсе.
 * Так «Загрузка… 0 %» и висела до убийства приложения.
 */
internal fun extendStartDeadline(reaperBusy: Boolean, grantedNanos: Long): Boolean =
    reaperBusy && grantedNanos < REAPER_GRACE_MAX_NANOS

/**
 * Можно ли запускать поток прямо сейчас — или прошлый запуск ещё открывается.
 *
 * ВТОРОЙ ЗАПУСК ПОВЕРХ ПЕРВОГО И ЕСТЬ ТО, ЧТО КЛИНИТ ПОТОК КОМАНД. Бросок зависшего
 * экземпляра делает две вещи разом: сам поднимает новый плеер и просит экран за
 * свежей ссылкой. Ссылка приезжает через полсекунды, адрес входит в ключи эффекта
 * запуска — и эффект перезапускается поверх ещё не открывшегося потока. Журнал
 * 17 августа:
 *
 *     01:28:58.098  play.request  url=p14…f8b7e8fd
 *     01:28:58.271  vlc.playing                      ← ещё открывается
 *     01:28:58.532  play.request  url=p12…f8b7e8fd   ← через 0.43 с
 *     01:29:00.078  vlc.callTimeout                  ← заклинило
 *
 * У этих адресов ОДИН файл и одна подпись, разный только хост раздачи: ради смены
 * зеркала рушили живой запуск.
 *
 * Отсрочка КОНЕЧНА и снимается двумя способами: поток открылся (тогда смена адреса
 * законна — это качество или озвучка) либо истекли [PLAY_SETTLE_NANOS]. Иначе мёртвая
 * ссылка заперла бы серию навсегда.
 */
internal fun shouldRestartPlayback(
    sameMediaKey: Boolean,
    sincePreviousPlayNanos: Long,
    previousStarted: Boolean,
): Boolean = !sameMediaKey || previousStarted || sincePreviousPlayNanos >= PLAY_SETTLE_NANOS

/** Сколько прошлому запуску даётся на открытие потока, прежде чем его можно сменить. */
private const val PLAY_SETTLE_MS = 3_000L
private const val PLAY_SETTLE_NANOS = PLAY_SETTLE_MS * 1_000_000L

/** Измеренный потолок честных похорон залипшего экземпляра. Дольше — уже не вернётся. */
private const val REAPER_GRACE_MAX_NANOS = 20_000_000_000L

/** Шаг опроса позиции: по нему копится отсрочка похорон. */
private const val POLL_TICK_NANOS = 400_000_000L

internal fun wedgeTickLimit(seekInFlight: Boolean): Int =
    if (seekInFlight) WEDGED_TICKS_LIMIT_SEEKING else WEDGED_TICKS_LIMIT

/** Итог одного замера статистики для сторожа «нет кадров». */
internal data class FramelessTick(
    /** Сколько миллисекунд подряд декодер не отдал ни одного кадра. */
    val silenceMs: Long,
    /** Пора перезапускать поток на месте. */
    val replay: Boolean,
)

/**
 * Решение сторожа «нет кадров» на одном замере статистики.
 *
 * СЧЁТ ТИШИНЫ ИДЁТ ОТ ОСТАНОВКИ ДЕКОДЕРА, А НЕ ОТ ОСТАНОВКИ ПОЗИЦИИ. Позиция
 * замирает позже кадров — аудиобуфер доигрывает: в журнале 17 августа кадры
 * кончились к 00:42:33.9, а stall.started пришёл в 00:42:36.4, то есть на пять
 * секунд позже. [positionMoved] поэтому не обнуляет счётчик, а только запрещает
 * приговор: пока позиция идёт, поток жив, сколько бы кадров ни потерялось.
 *
 * [seekInFlight] обнуляет: пока перемотка не приземлилась, позиция приколота к
 * цели, а декодер законно молчит — судить не по чему.
 */
/**
 * Экземпляр ОТКРЫЛСЯ, но не отдал НИ ОДНОГО кадра.
 *
 * Дыра ровно между двумя сторожами, и она стоила зрителю двух минут неподвижной
 * картинки. Журнал 24.08:
 *
 *     04:39:11.479  play.request    resumeMs=641608
 *     04:39:11.687  vlc.playing                          ← libVLC говорит «играю»
 *     04:39:11.673  resume.reached  native=641608        ← позиция доложена
 *     04:39:12.2 … 04:41:14.2       decoded=0,00         (шестьдесят замеров подряд)
 *     ни одного stall.started                            ← ЗНАЧИТ ПОЗИЦИЯ ИДЁТ
 *     ни одного приговора                                ← и никто ничего не делает
 *
 * Почему промолчали оба:
 *
 *  * сторож кадров не судит экземпляр, который ни разу не декодировал (`everStarted`),
 *    — и правильно делает: иначе он убивает каждый свежий плеер через шесть секунд
 *    после рождения, это разобрано у самого признака;
 *  * сторож «поток не начался» снимается с дежурства, как только позиция сдвинулась.
 *    А она СДВИНУЛАСЬ: libVLC на застрявшем HLS двигает свои часы, не получая данных.
 *
 * Отсюда правило: часы врут, кадры — нет. Экземпляр, который за [FRAMES_NEVER_MS]
 * «воспроизведения» не выдал ни единого кадра, мёртв, куда бы ни ползла его позиция.
 *
 * Двадцать секунд, а не шесть. Шесть — это порог ОБРЫВА у потока, который уже шёл; тут
 * же решается вопрос «начался ли он вообще», и на открытие адаптивного HLS с холодным
 * буфером законно уходит до десятка секунд. Двадцать — тот же срок, который в этом
 * файле назван достаточным для суждения по одним кадрам.
 */
internal fun framesNeverArrived(
    playing: Boolean,
    everDecoded: Boolean,
    seekInFlight: Boolean,
    silenceMs: Long,
): Boolean = playing && !everDecoded && !seekInFlight && silenceMs >= FRAMES_NEVER_MS

/**
 * Сколько «воспроизведения» без единого кадра считать мёртвым экземпляром.
 *
 * Не путать с [NO_FRAMES_SILENCE_MS]: тот про обрыв уже идущего потока и потому короток.
 */
private const val FRAMES_NEVER_MS = 20_000L

internal fun framelessWatchdogTick(
    playing: Boolean,
    decodedGrew: Boolean,
    positionMoved: Boolean,
    seekInFlight: Boolean,
    silenceMs: Long,
    sincePreviousTickMs: Long,
    /**
     * Этот экземпляр УЖЕ отдавал кадры хоть раз.
     *
     * Без этого условия сторож жрёт сам себя, и 17 августа в 01:09 он это сделал:
     * бросив зависший экземпляр, он тут же взялся за свежий, который ещё открывал
     * поток и по определению не отдал ни кадра. «Кадров нет и позиция стоит» у не
     * стартовавшего плеера выполняется ВСЕГДА:
     *
     *     01:09:17.8  frames.missing afterSec=6  → abandon resumeAt=503878
     *     01:09:18.5  play.request   resumeMs=503878
     *     01:09:19.8 … 01:09:37.8    decoded=0 десять замеров подряд
     *     01:09:37.8  frames.missing afterSec=6; position=0
     *     01:09:37.8  vlc.abandon    resumeAt=0   ← точка просмотра потеряна
     *     01:09:47.4  vlc.abandon    generation=2
     *
     * За то, что плеер не стартовал вовсе, отвечает отдельная отсечка
     * (play.neverStarted) — она умеет ждать столько, сколько нужно на открытие
     * потока, и не путает старт с обрывом.
     */
    everStarted: Boolean,
    /**
     * Сколько прошло с ПОСЛЕДНЕЙ ВЫДАННОЙ перемотки, по часам.
     *
     * Здесь была дыра, и она стоила зрителю живых экземпляров плеера. Сторож молчал
     * только пока перемотка «в полёте» (`seekTarget != null`), а этот признак снимается
     * в тот миг, когда libVLC доложила о приземлении позиции. Но приземление позиции —
     * это ещё не кадры: адаптивный демультиплексор после перемотки заново тянет и
     * декодирует сегмент, и по замеру из этого же файла на это уходит от полутора до
     * ШЕСТИ секунд — ровно столько же, сколько сторож ждёт до приговора.
     *
     * Журнал 24.08, зритель дважды дёрнул ползунок подряд:
     *
     *     04:27:08.323  play.seek       targetMs=265376
     *     04:27:08.741  seek.settled    landed=true      ← «в полёте» снято
     *     04:27:10.5 … 04:27:14.5       decoded=0,00     (три замера)
     *     04:27:14.522  frames.missing  afterSec=6
     *     04:27:14.524  vlc.abandon     reason=noFrames  ← живой поток брошен
     *
     * Сторож ЗАВИСШЕЙ ПОЗИЦИИ этой ошибки не делает: у него отсчёт идёт ПО ЧАСАМ от
     * выдачи перемотки и длится [POST_SEEK_GRACE_NANOS]. Здесь ровно та же оговорка —
     * иначе два сторожа судят одно и то же событие по разным правилам, и более
     * нетерпеливый выигрывает.
     *
     * Сам порог [NO_FRAMES_SILENCE_MS] не тронут: он про обрыв, а не про перемотку.
     */
    sinceSeekNanos: Long,
): FramelessTick {
    if (!playing || decodedGrew || seekInFlight || !everStarted) return FramelessTick(0L, false)
    val silence = silenceMs + sincePreviousTickMs
    // Тишину копим и во время отсрочки: как только она кончится, приговор выносится
    // сразу, а не отсчитывается заново.
    if (sinceSeekNanos <= POST_SEEK_GRACE_NANOS) return FramelessTick(silence, false)
    return FramelessTick(silence, !positionMoved && silence >= NO_FRAMES_SILENCE_MS)
}

/**
 * Пора ли записать точку просмотра.
 *
 * НЕ ЗАВИСИТ ОТ ТОГО, ОТВЕЧАЕТ ЛИ ОПРОС. Раньше сохранение стояло после успешного
 * опроса, и во время зависания не выполнялось ни разу: на диске оставалась точка,
 * бывшая там ДО перемотки. Зритель закрывал застрявшее приложение и возвращался на
 * полчаса назад — это и есть «после закрытия не запоминается тайминг».
 *
 * Позиция интерфейса известна и без опроса: её ставит сама перемотка, и она же
 * остаётся от последнего удачного опроса.
 */
internal fun shouldPersistProgress(
    positionMs: Long,
    lengthMs: Long,
    sinceLastSaveNanos: Long,
    @Suppress("UNUSED_PARAMETER") pollAnswering: Boolean,
): Boolean {
    if (positionMs <= 0L || lengthMs <= 0L) return false
    return sinceLastSaveNanos >= PROGRESS_SAVE_INTERVAL_NANOS
}

/**
 * Столько подряд не ответивших опросов ВО ВРЕМЯ ПЕРЕМОТКИ — и это уже зависание.
 *
 * Двенадцать тиков — около двадцати трёх секунд ожидания. Дольше терпеть нельзя:
 * если libVLC встала ВНУТРИ setTime, сама она оттуда не выйдет, и всё это время
 * зритель смотрит на замерший кадр.
 */
private const val WEDGED_TICKS_LIMIT_SEEKING = 12

/**
 * Сколько после перемотки сторож зависания считает молчание libVLC законным.
 *
 * Отсчёт ПО ЧАСАМ, а не по `seekTarget`: тот снимается только в ветке удачного
 * опроса, а её при зависании и нет — по нему терпение стало бы бесконечным. Пока
 * зритель продолжает таскать ползунок, отсрочка продлевается сама; как только
 * перестал — истекает за двенадцать секунд.
 */
private const val SEEK_GRACE_NANOS = 12_000_000_000L

/** Как часто точка просмотра уходит на диск. Это же и окно потерь при убийстве процесса. */
private const val PROGRESS_SAVE_INTERVAL_NANOS = 2_000_000_000L

private class ConflatedIntCommand(
    private val executor: Executor,
    private val action: (Int) -> Unit,
) {
    private val pending = AtomicInteger(NONE)
    private val scheduled = AtomicBoolean(false)

    fun submit(value: Int) {
        pending.set(value)
        if (scheduled.compareAndSet(false, true)) runCatching { executor.execute(::drain) }
    }

    private fun drain() {
        while (true) {
            val value = pending.getAndSet(NONE)
            if (value != NONE) {
                runCatching { action(value) }
                continue
            }
            scheduled.set(false)
            if (pending.get() == NONE || !scheduled.compareAndSet(false, true)) return
        }
    }

    private companion object { const val NONE = Int.MIN_VALUE }
}

private fun buildDubList(raw: List<Pair<Int, String>>): List<Pair<Int, String>> {
    // code -> (trackId, language, isFailover). libVLC exposes each dub twice: the
    // real "audio0 rus0" track and a "failover-audio-0 rus0" copy. Keep the real
    // one — setTrack on a failover id doesn't reliably swap the audio.
    val byCode = LinkedHashMap<String, Triple<Int, String, Boolean>>()
    raw.forEach { (id, desc) ->
        val code = desc.substringBefore(" - ").trim().substringAfterLast(' ')
        val lang = Regex("""\[([^\]]+)]""").find(desc)?.groupValues?.get(1)
            ?: desc.substringAfterLast(" - ").ifBlank { desc }.trim()
        val failover = desc.contains("failover", ignoreCase = true)
        val existing = byCode[code]
        if (existing == null || (existing.third && !failover)) byCode[code] = Triple(id, lang, failover)
    }
    val seen = HashMap<String, Int>()
    return byCode.values.map { (id, lang, _) ->
        val n = (seen[lang] ?: 0) + 1
        seen[lang] = n
        id to if (n == 1) lang else "$lang $n"
    }
}

internal fun chooseAvailableDub(
    selected: String?,
    tracks: List<Pair<Int, String>>,
    currentTrackId: Int,
): String? {
    if (tracks.isEmpty()) return null
    if (selected != null && tracks.any { it.second == selected }) return selected
    return tracks.firstOrNull { it.first == currentTrackId }?.second ?: tracks.first().second
}

internal fun shouldAutoHideHud(
    visible: Boolean,
    playing: Boolean,
    interactionActive: Boolean,
    openingButtonVisible: Boolean,
    /** «Закрепить панель» — она остаётся на экране, пока её не открепят. */
    pinned: Boolean = false,
): Boolean = visible && playing && !interactionActive && !openingButtonVisible && !pinned

internal fun singleClickHudTarget(wasVisible: Boolean): Boolean = !wasVisible

internal fun motionDurationMillis(normalDuration: Int, reducedMotion: Boolean): Int =
    if (reducedMotion) 0 else normalDuration.coerceAtLeast(0)

internal fun parseReducedMotion(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
    "1", "true", "yes", "reduce" -> true
    "0", "false", "no", "no-preference" -> false
    else -> null
}

internal fun systemPrefersReducedMotion(): Boolean {
    val configured = parseReducedMotion(
        System.getProperty("aniblaze.prefersReducedMotion")
            ?: System.getProperty("prefers-reduced-motion"),
    )
    if (configured != null) return configured
    NativePointer.systemAnimationsEnabled()?.let { return !it }
    return runCatching {
        (Toolkit.getDefaultToolkit().getDesktopProperty("win.animation") as? Boolean) == false
    }.getOrDefault(false)
}

internal fun shouldShowOpeningButton(
    range: OpeningRange?,
    positionMs: Long,
    leadMs: Long = 5_000L,
): Boolean {
    if (range == null || !range.isValid) return false
    // Интервал, взятый у соседней серии, может «уехать»: холодное открытие в этой
    // серии длиннее или короче. Запас спереди расширяем, иначе кнопка мигнёт мимо
    // опенинга и пользы от неё не будет.
    //
    // ЗАПАС ТОЛЬКО СПЕРЕДИ. Хвост за концом интервала был прямой ошибкой, и в журнале
    // она видна дословно: кнопка оставалась на экране ПОСЛЕ удачного пропуска, а цель
    // у неё фиксированная — endMs. Зритель нажимал, прыгал на 119242, смотрел
    // одиннадцать секунд, нажимал снова — и его отбрасывало обратно на 119242.
    //
    //     00:35:23  play.seek targetMs=119242
    //     00:35:34  play.seek targetMs=119242   ← назад на одиннадцать секунд
    //
    // Прежние 45 секунд спереди тоже были костылём — под плохого донора, который брал
    // интервал у ПЕРВОЙ серии и промахивался на сорок секунд (см. seasonProbeOrder).
    val lead = if (range.autoDetected) 0L else if (range.approximate) maxOf(leadMs, APPROX_MARGIN_MS) else leadMs.coerceAtLeast(0L)
    val showFrom = (range.startMs - lead).coerceAtLeast(0L)
    return positionMs in showFrom until range.endMs
}

/**
 * Куда уводит нажатие «Пропустить опенинг». null — нажимать незачем.
 *
 * ВТОРОЙ РУБЕЖ против той же ошибки: даже если кнопку кто-то покажет не вовремя,
 * нажатие не имеет права увести НАЗАД. «Пропустить» — это всегда вперёд, иначе
 * кнопка работает как «вернуться к опенингу».
 */
internal fun openingSkipTarget(range: OpeningRange?, positionMs: Long): Long? {
    if (range == null || !range.isValid) return null
    return range.endMs.takeIf { it > positionMs }
}

/**
 * Keep a short playable tail when a skip target reaches the nominal end of media.
 *
 * Exact ED data can end at the final frame, while a borrowed range may extend past
 * the real duration. Leaving this tail lets VLC emit its normal `finished` event,
 * so watched/completed/next stay on the same path as an unskipped episode. A target
 * before the tail is kept intact, preserving a known post-credit scene.
 */
internal fun guardedSeekTarget(
    requestedMs: Long,
    durationMs: Long,
    endGuardMs: Long = SEEK_END_GUARD_MS,
): Long {
    val safeDuration = durationMs.coerceAtLeast(0L)
    val ceiling = if (safeDuration > endGuardMs * 2) safeDuration - endGuardMs else safeDuration
    return requestedMs.coerceIn(0L, ceiling.coerceAtLeast(0L))
}

/**
 * Пора ли пропустить интервал САМ, без нажатия. Служит и опенингу, и эндингу.
 *
 * ЗАНЯТЫЙ ИНТЕРВАЛ СЮДА НЕ ПУСКАЕТСЯ. Он промахивается по старту на десятки секунд —
 * замерено на «Чёрной кошке и классе ведьм»: первая серия начинает опенинг на 2.1 с,
 * вторая на 42.5 с, восьмая на 29.3 с. Автопропуск по такому интервалу молча срезает
 * зрителю полминуты НАСТОЯЩЕЙ серии, и понять, что произошло, невозможно: только что
 * шла сцена — и вдруг другая. Кнопку по занятому интервалу показать можно, решение
 * тогда принимает человек и видит, куда попал.
 *
 * Порог по концу (−1 с) не даёт зациклиться, если перемотка приземлилась на
 * миллисекунду раньше конца интервала.
 */
internal fun shouldAutoSkipOpening(
    range: OpeningRange?,
    positionMs: Long,
    alreadySkipped: Boolean,
    enabled: Boolean,
): Boolean {
    if (!enabled || alreadySkipped) return false
    if (range == null || !range.isValid || range.approximate) return false
    if (range.autoDetected && (!range.confidence.isFinite() || range.confidence < AUTO_TIMING_HIGH)) return false
    return positionMs in range.startMs until (range.endMs - 1_000)
}

/**
 * Запас окна показа для интервала, занятого у другой серии.
 *
 * Двадцать секунд — не круглое число «на глаз», а разброс доноров по замеру: у
 * «Чёрной кошки и класса ведьм» опенинг начинается на 29.3 и 42.5 секунде, то есть
 * серединный донор ошибается на тринадцать секунд в одну сторону. Двадцать
 * покрывают это с запасом и при этом не держат кнопку всю первую минуту.
 */
private const val APPROX_MARGIN_MS = 20_000L

/** Rendered once and handed to libVLC as a transparent logo overlay. This keeps
 * the native video surface fast while giving skip feedback the same modern font
 * and dark treatment as the Compose UI. */
private fun createSkipFeedbackImage(seconds: Int): BufferedImage {
    val text = if (seconds < 0) "‹  5 сек" else "5 сек  ›"
    val font = Font("Segoe UI Semibold", Font.PLAIN, 30)
    val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    val probeGraphics = probe.createGraphics()
    probeGraphics.font = font
    val probeMetrics = probeGraphics.fontMetrics
    val width = probeMetrics.stringWidth(text) + 48
    val height = 64
    probeGraphics.dispose()

    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.color = java.awt.Color(18, 18, 24, 220)
        graphics.fillRoundRect(0, 0, width, height, 24, 24)
        graphics.font = font
        graphics.color = java.awt.Color.WHITE
        val metrics = graphics.fontMetrics
        val x = (width - metrics.stringWidth(text)) / 2
        val y = (height - metrics.height) / 2 + metrics.ascent
        graphics.drawString(text, x, y)
        graphics.dispose()
    }
}

private fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * Подпись серии в HUD: «Серия 3 / 12». Знаменатель — сколько серий всего по
 * каталогу; если каталог не знает, — сколько доступно к просмотру. Когда
 * доступно меньше, чем всего (онгоинг), это видно: «Серия 3 / 5 из 12».
 */
internal fun episodeCounterLabel(current: Int?, available: Int, total: Int): String {
    current ?: return "Серии"
    return when {
        total > 0 && available in 1 until total -> "Серия $current / $available из $total"
        total > 0 -> "Серия $current / $total"
        available > 0 -> "Серия $current / $available"
        else -> "Серия $current"
    }
}
