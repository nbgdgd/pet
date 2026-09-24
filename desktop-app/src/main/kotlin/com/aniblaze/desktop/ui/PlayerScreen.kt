package com.aniblaze.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.OpeningRange
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.aggregator.source.AniskipTimings
import com.aniblaze.desktop.AppSettings
import com.aniblaze.desktop.DesktopRepository
import com.aniblaze.desktop.toPersisted
import com.aniblaze.desktop.player.PlaybackCheckpoint
import com.aniblaze.desktop.player.PlayerDiagnostics
import com.aniblaze.desktop.player.RealCommentState
import com.aniblaze.desktop.player.VlcPlayerView
import com.aniblaze.desktop.player.motionDurationMillis
import com.aniblaze.desktop.player.systemPrefersReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp



@Composable
fun PlayerScreen(
    repository: DesktopRepository,
    settings: AppSettings,
    anime: Anime,
    segmentNumber: Int,
    fullscreen: Boolean,
    visible: Boolean = true,
    // Window hidden to tray → playback pauses.
    suspended: Boolean = false,
    onToggleFullscreen: () -> Unit,
    // Auto-switch after the last episode: the picked random title goes up to the
    // navigation layer, which opens it straight into the player.
    onSwitchTitle: (Anime) -> Unit = {},
) {
    VlcScreen(repository, settings, anime, segmentNumber, fullscreen, visible, suspended, onToggleFullscreen, onSwitchTitle)
}

private fun openInBrowser(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

/** Decode the 1-based episode from the exact media identity emitted by VLC. */
internal fun episodeFromMediaKey(contentId: String, mediaKey: Any?): Int? {
    val encoded = mediaKey as? String ?: return null
    val prefix = "$contentId:"
    if (!encoded.startsWith(prefix)) return null
    return encoded.removePrefix(prefix).toIntOrNull()?.takeIf { it > 0 }
}

/**
 * Timings arrive asynchronously, while the previous episode can still be playing.
 * Never hand episode N's OP/ED bounds to episode N - 1 during that overlap.
 */
internal fun skipTimingsForEpisode(
    timingsByEpisode: Map<Int, AniskipTimings.SkipTimings>,
    episode: Int,
): AniskipTimings.SkipTimings =
    timingsByEpisode[episode] ?: AniskipTimings.SkipTimings.EMPTY

@Composable
private fun VlcScreen(
    repository: DesktopRepository,
    settings: AppSettings,
    anime: Anime,
    segmentNumber: Int,
    fullscreen: Boolean,
    visible: Boolean,
    suspended: Boolean = false,
    onToggleFullscreen: () -> Unit,
    onSwitchTitle: (Anime) -> Unit = {},
) {
    val contentId = anime.id
    val scope = rememberCoroutineScope()
    // Раньше здесь стояла запись в журнал на КАЖДУЮ пересборку: 3448 строк за сеанс,
    // и каждая — конкатенация и очередь на UI-потоке. Смена состава этих полей и есть
    // всё, что тут стоило знать, — эффект пишет ровно её.
    LaunchedEffect(contentId, segmentNumber, visible, fullscreen) {
        PlayerDiagnostics.log(
            "playerScreen.state",
            "contentId=$contentId; segment=$segmentNumber; visible=$visible; fullscreen=$fullscreen",
        )
    }
    val title = anime.title
    val settingsState by settings.state.collectAsState()
    val petSession = remember(contentId) { com.aniblaze.desktop.pet.PetPlaybackSession() }
    val petX = if (fullscreen) settingsState.petFullscreenX ?: settingsState.petPlayerX else settingsState.petPlayerX
    val petY = if (fullscreen) settingsState.petFullscreenY ?: settingsState.petPlayerY else settingsState.petPlayerY
    val movePet: (Float, Float) -> Unit = if (fullscreen) settings::setPetFullscreenPosition else settings::setPetPlayerPosition
    val petPreviousDays = remember(contentId) { settings.daysSinceLastWatch(contentId) }
    val petRewatch = remember(contentId) {
        com.aniblaze.desktop.recommendationCompletion(settings.state.value, anime.toPersisted()) ==
            com.aniblaze.desktop.RecommendationCompletion.COMPLETED
    }
    val isCinema = remember(contentId) { repository.isCinema(contentId).also { PlayerDiagnostics.log("playerScreen.isCinema", "value=$it; contentId=$contentId") } }
    val animeSources = remember { repository.animeSources().also { PlayerDiagnostics.log("playerScreen.animeSources", "count=${it.size}") } }
    val prefKey = remember(contentId) { contentId.substringBefore(":t") }
    val savedPref = settingsState.playerPrefs[prefKey]
    val reducedMotion = remember { systemPrefersReducedMotion().also { PlayerDiagnostics.log("playerScreen.reducedMotion", "value=$it") } }

    val initialSource = remember(contentId) {
        settingsState.primarySource.takeIf { it.isNotBlank() && it != "Все" }
    }
    val initialVoice = remember(contentId, initialSource) {
        settings.playerPref(sourceVoiceKey(prefKey, initialSource))?.voice
            ?: settings.playerPref(prefKey)?.voice
            ?: -1
    }

    var result by remember(contentId) { mutableStateOf<ContentResult?>(null) }
    LaunchedEffect(result) { PlayerDiagnostics.log("playerScreen.result", "hasResult=${result != null}; source=${result?.source ?: "null"}; variants=${result?.variants?.size ?: 0}") }
    // Timings are loaded independently of the video URL and can arrive after a new
    // episode was requested. Keep them keyed by the 1-based episode number, then pick
    // only the entry belonging to the media actually committed to VLC below.
    var exactTimingsByEpisode by remember(contentId) {
        mutableStateOf<Map<Int, AniskipTimings.SkipTimings>>(emptyMap())
    }
    var activeSource by remember(contentId) { mutableStateOf(initialSource) }
    LaunchedEffect(activeSource) { PlayerDiagnostics.log("playerScreen.activeSource", "value=${activeSource ?: "null"}") }
    var activeId by remember(contentId) {
        mutableStateOf(
            when {
                contentId.contains(":t") -> contentId
                initialVoice >= 0 -> "$prefKey:t$initialVoice"
                else -> prefKey
            },
        )
    }

    var requestedSegment by remember(contentId) { mutableStateOf(segmentNumber) }
    /** Сколько раз для текущей серии уже просили у источника свежую ссылку. */
    var deadRetries by remember(contentId) { mutableStateOf(0) }
    /** Озвучки, которые уже пробовали на этой серии, — чтобы не вернуться к той же. */
    var triedVoices by remember(contentId) { mutableStateOf<Set<Int>>(emptySet()) }
    /** Источники, которые уже пробовали на этой серии. */
    var triedSources by remember(contentId) { mutableStateOf<Set<String>>(emptySet()) }
    /**
     * Качество, которое сохранённому выбору переопределять НЕЛЬЗЯ.
     *
     * Обычно плеер открывает то, что человек выбрал в прошлый раз. Но когда проверка
     * адресов отбраковала верхние качества (см. withLiveVariantFirst), сохранённое
     * «720p» указывает ровно на отбракованное — и плеер честно открыл бы его снова,
     * подарив мёртвому раздающему ещё девять секунд. Ставится только на такой случай и
     * живёт до следующего резолва.
     */
    var forcedQuality by remember(contentId) { mutableStateOf<String?>(null) }
    /**
     * Сколько озвучек перебрать, прежде чем менять источник целиком.
     *
     * У популярного тайтла озвучек четырнадцать, и все они лежат на ОДНОМ раздающем:
     * когда молчит он, перебор — это четырнадцать заходов по девять секунд, две
     * минуты замершего кадра, и только потом черёд источника, который бы заиграл
     * сразу. Замерено 18.08: solodcdn не отдал ни байта, а AniLibria в тот же вечер
     * начинала играть через 60 мс. Три попытки покрывают настоящий случай «умер файл
     * этой озвучки» и не превращают мёртвый CDN в двухминутное ожидание.
     */
    val voiceBudget = 3
    LaunchedEffect(requestedSegment) {
        PlayerDiagnostics.log("playerScreen.requestedSegment", "value=$requestedSegment")
        // Новая серия — новый счёт попыток. Списки перебранного тоже: «эта озвучка
        // мертва» сказано было про ПРОШЛУЮ серию, и переносить приговор на следующую
        // нельзя — иначе через две-три серии перебирать становится нечего и лестница
        // сразу прыгает на смену источника.
        deadRetries = 0
        triedVoices = emptySet()
        triedSources = emptySet()
    }
    var playingSegment by remember(contentId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(playingSegment) { PlayerDiagnostics.log("playerScreen.playingSegment", "value=$playingSegment") }
    // Единый пул обсуждения сезонного релиза. Репозиторий отдаёт кэш сразу и затем
    // спокойно проходит пагинацию в фоне; серия из ключа эффекта намеренно исключена.
    var overlayComments by remember(contentId) {
        mutableStateOf<List<com.aniblaze.aggregator.model.TitleComment>>(emptyList())
    }
    var commentsState by remember(contentId) { mutableStateOf(RealCommentState.LOADING) }
    // Обсуждение нужно ДВУМ потребителям: репликам поверх кадра и симулятору чата.
    // Поэтому условие загрузки — «нужно хоть кому-то», а не только оверлею: иначе
    // выключенные реплики оставляли бы чат без единого настоящего голоса.
    val commentsWanted = settingsState.commentsOverlay || settingsState.chatEnabled
    LaunchedEffect(contentId, commentsWanted) {
        // TMDB reviews are shown on a cinema title page, but are never safe to
        // replay as timed chat/danmaku over a film: they have no episode context
        // or spoiler metadata.
        if (!commentsWanted || isCinema) {
            commentsState = RealCommentState.EMPTY
            return@LaunchedEffect
        }
        commentsState = if (overlayComments.isEmpty()) {
            RealCommentState.LOADING
        } else {
            RealCommentState.CACHED_REFRESHING
        }
        // Не конкурируем с первым запросом видео на том же хосте. Эффект привязан к
        // сезону, а не к серии: ждёт первого успешно разрешённого потока один раз и
        // затем продолжает общий обход при переключении 1 → 2 → 5 → 10.
        while (playingSegment == null) kotlinx.coroutines.delay(100L)
        // Порции приходят ПО МЕРЕ ЗАГРУЗКИ: первая страница уже на экране, пока
        // догружаются остальные. Отбор повторов, кэш и знание о конце обсуждения — в
        // репозитории (DesktopRepository.commentFeed), здесь только присвоение.
        //
        // Ключ эффекта — тайтл, поэтому смена тайтла отменяет обход, и запоздалый
        // ответ прошлого тайтла в чужое состояние уже не попадёт.
        try {
            repository.commentFeed(anime).collect { batch ->
                // Compose получает редкие снимки 25/50/100…, а не копию огромного
                // списка после каждой страницы. Чат больше не перезапускается от этих
                // обновлений: он сливает новые id в живую очередь; редкость снимков
                // нужна оверлею и самой стоимости копирования.
                val grewEnough = batch.comments.size >= overlayComments.size * 2
                if (overlayComments.isEmpty() || batch.complete || grewEnough) {
                    overlayComments = batch.comments
                }
                commentsState = when {
                    batch.stop == com.aniblaze.aggregator.source.CommentStop.FAILED -> RealCommentState.ERROR
                    batch.complete && batch.comments.isEmpty() -> RealCommentState.EMPTY
                    batch.complete -> RealCommentState.READY
                    batch.fromCache && batch.refreshing -> RealCommentState.CACHED_REFRESHING
                    else -> RealCommentState.LOADING
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PlayerDiagnostics.failure("player.comments", error)
            commentsState = RealCommentState.ERROR
        }
        PlayerDiagnostics.log(
            "player.comments",
            "loaded=${overlayComments.size}; title=${anime.title.take(40)}",
        )
    }

    var playingId by remember(contentId) { mutableStateOf<String?>(null) }
    LaunchedEffect(playingId) { PlayerDiagnostics.log("playerScreen.playingId", "value=$playingId") }
    var playingSourcePreference by remember(contentId) { mutableStateOf<String?>(initialSource) }
    LaunchedEffect(playingSourcePreference) { PlayerDiagnostics.log("playerScreen.playingSource", "value=$playingSourcePreference") }
    var segments by remember(contentId) { mutableStateOf<List<Segment>>(emptyList()) }
    LaunchedEffect(segments) { PlayerDiagnostics.log("playerScreen.segments", "count=${segments.size}") }
    var loading by remember(contentId) { mutableStateOf(true) }
    LaunchedEffect(loading) { PlayerDiagnostics.log("playerScreen.loading", "value=$loading") }
    var error by remember(contentId) { mutableStateOf<String?>(null) }
    LaunchedEffect(error) { if (error != null) PlayerDiagnostics.log("playerScreen.error", "message=${error?.take(100)}") }
    // Счётчик принудительного перезапроса потока: растёт, когда ссылка протухла.
    var resolveNonce by remember(contentId) { mutableStateOf(0) }
    var resolvedNonce by remember(contentId) { mutableStateOf(0) }
    var embedUrl by remember(contentId) { mutableStateOf<String?>(null) }
    LaunchedEffect(embedUrl) { PlayerDiagnostics.log("playerScreen.embedUrl", "value=${embedUrl?.take(60) ?: "null"}") }

    fun selectSource(source: String?) {
        PlayerDiagnostics.log("source.select", "source=${source ?: "auto"}")
        activeSource = source
        val voice = settings.playerPref(sourceVoiceKey(prefKey, source))?.voice ?: -1
        activeId = if (voice >= 0) "$prefKey:t$voice" else prefKey
        settings.setPrimarySource(source ?: "Все")
    }

    LaunchedEffect(fullscreen) {
        PlayerDiagnostics.log("player.fullscreen", "enabled=$fullscreen")
    }

    // App keeps this player alive between browser-like tabs. A second open of the
    // same title with another episode therefore has to update explicit state.
    LaunchedEffect(contentId, segmentNumber) {
        PlayerDiagnostics.log("player.request", "content=$contentId; segment=$segmentNumber; visible=$visible")
        if (segmentNumber != requestedSegment) requestedSegment = segmentNumber
    }

    LaunchedEffect(contentId) {
        segments = try {
            repository.segments(contentId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    if (isCinema) {
        LaunchedEffect(contentId) {
            embedUrl = try {
                repository.cinemaEmbedUrl(contentId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    // Per-dub view stats for this title (Anixart) — percentage fallback for the
    // HUD's озвучка picker when the playing source has no counts of its own.
    var voiceShares by remember(contentId) { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(title, isCinema) {
        voiceShares = if (isCinema) emptyMap() else try {
            repository.voiceoverShares(title)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // Апгрейд качества: источник упёрся в 720p → тихо спрашиваем AniLibria и
    // ДОКЛЕИВАЕМ её 1080p в конец пикера качества. Пока источник отдаёт своё лучшее,
    // текущий поток не трогается (вариант в конце списка ничего не перезапускает), и
    // выбор «1080p · AniLibria» остаётся осознанным — он меняет поток вместе с его
    // озвучкой.
    //
    // А вот когда источник СВОЁ ЛУЧШЕЕ ОТДАТЬ НЕ СМОГ — доклеенный вариант выходит
    // вперёд и играет сразу. Замерено 19.08 на «Доме теней 2», серия 5:
    //
    //     p12.solodcdn.com/720.mp4  → 302 → p14.solodcdn.com/720.mp4 → тишина 15 с
    //     p14.solodcdn.com/480.mp4  → 302 → p12.solodcdn.com/480.mp4 → 200 за 61 мс
    //     cache.libria.fun/1080     → 200
    //
    // На соседней серии 4 всё наоборот: 720p отвечает за 70 мс, а молчит 480p. То есть
    // раздающий болеет пофайлово, и «оставить зрителю 480p» — это отдать ему худшее из
    // возможного, когда рядом лежит работающая 1080p. Ровно на это и жаловались:
    // «почини 720p».
    LaunchedEffect(playingId, playingSegment) {
        val res = result ?: return@LaunchedEffect
        val segment = playingSegment ?: return@LaunchedEffect
        if (isCinema) return@LaunchedEffect
        if (res.source.contains("libria", ignoreCase = true)) return@LaunchedEffect
        val own = res.variants.orEmpty()
        val sourceBest = own.maxOfOrNull { repository.qualityHeight(it.quality) } ?: 0
        if (sourceBest == 0) return@LaunchedEffect
        // res.quality — то качество, чей адрес репозиторий ПРОВЕРИЛ и признал живым
        // (см. withLiveVariantFirst). Ниже собственного максимума оно опускается
        // только потому, что верхние адреса не ответили.
        val playingHeight = repository.qualityHeight(res.quality)
        val downgraded = playingHeight in 1 until sourceBest
        val lost = own.filter { repository.qualityHeight(it.quality) > playingHeight }
            .maxByOrNull { repository.qualityHeight(it.quality) }
        // Молча подсунуть качество ниже — это и есть «висит на 0 %» с точки зрения
        // зрителя: он выбрал 720p, а получил непонятно что и без объяснений.
        if (downgraded && lost != null) {
            error = "${lost.quality} у «${res.source}» не отвечает — играю ${res.quality}"
        }
        if (playingHeight >= 1080) return@LaunchedEffect
        val extra = try {
            repository.hiResVariants(title, segment, anime.year)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        if (extra.isEmpty()) return@LaunchedEffect
        val best = extra.maxByOrNull { repository.qualityHeight(it.quality) }
        val promote = downgraded && best != null && repository.qualityHeight(best.quality) > playingHeight
        PlayerDiagnostics.log(
            "stream.hiRes",
            "added=${extra.size}; playing=$playingHeight; sourceBest=$sourceBest; promote=$promote",
        )
        // Ничего не резолвилось заново с тех пор — доклеиваем к тому же результату.
        if (result === res) {
            val baseVariants = res.variants ?: listOf(StreamVariant(res.quality.ifBlank { "Auto" }, res.location))
            result = if (promote && best != null) {
                forcedQuality = best.quality
                if (lost != null) error = "${lost.quality} у «${res.source}» не отвечает — играю ${best.quality}"
                res.copy(variants = listOf(best) + baseVariants + extra.filter { it !== best })
            } else {
                res.copy(variants = baseVariants + extra)
            }
        }
    }

    LaunchedEffect(title, requestedSegment, isCinema) {
        val timingSegment = requestedSegment
        repeat(3) { attempt ->
        if (attempt > 0) kotlinx.coroutines.delay(31_000)
        val timings = if (isCinema) AniskipTimings.SkipTimings.EMPTY else try {
            // У Anixart в status лежит оригинальное (ромадзи) название — запасной
            // ключ поиска MAL id, когда русское на Shikimori не находится.
            val altTitle = if (contentId.startsWith("ax:")) anime.status else ""
            repository.exactTimings(title, timingSegment, anime.year, altTitle)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PlayerDiagnostics.failure("timings.resolve.exception", error)
            AniskipTimings.SkipTimings.EMPTY.copy(retryable = true)
        }
        exactTimingsByEpisode = exactTimingsByEpisode + (timingSegment to timings)
        PlayerDiagnostics.log(
            "playerScreen.timings.loaded",
            "episode=$timingSegment; op=${timings.opening?.startMs}-${timings.opening?.endMs}; " +
                "ed=${timings.ending?.startMs}-${timings.ending?.endMs}; recap=${timings.recap?.startMs}-${timings.recap?.endMs}",
        )
        if (!timings.retryable) return@LaunchedEffect
        }
    }

    LaunchedEffect(activeId, requestedSegment, activeSource, resolveNonce) {
        // A failed request rolls controls back to these values. That key change must
        // not reload the stream which is already committed and playing.
        //
        // Принудительный перезапрос (протухшая ссылка) — исключение: там всё
        // совпадает с уже играющим, и без проверки счётчика мы бы вышли отсюда,
        // не сходив за новой ссылкой.
        if (
            resolveNonce == resolvedNonce &&
            result != null && activeId == playingId &&
            requestedSegment == playingSegment && activeSource == playingSourcePreference
        ) {
            loading = false
            return@LaunchedEffect
        }
        resolvedNonce = resolveNonce

        val requestId = activeId
        val requestSegment = requestedSegment
        val requestSource = activeSource
        PlayerDiagnostics.log("stream.resolve.start", "content=$requestId; segment=$requestSegment; source=${requestSource ?: "auto"}")
        loading = true
        error = null

        val resolved = try {
            val original = repository.resolveStream(requestId, requestSegment, title, requestSource, anime.year)
            val currentSettings = settings.state.value
            val preference = currentSettings.playerPrefs[sourceVoiceKey(prefKey, requestSource)]
                ?: currentSettings.playerPrefs[prefKey]
            com.aniblaze.desktop.applyVoicePriority(original, !isCinema && currentSettings.voicePriority, preference) { voiceId ->
                repository.resolveStream("$prefKey:t$voiceId", requestSegment, title, requestSource, anime.year)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PlayerDiagnostics.failure("stream.resolve.exception", error)
            null
        }

        if (resolved == null) {
            PlayerDiagnostics.log("stream.resolve.failed", "content=$requestId; segment=$requestSegment; retries=$deadRetries")
            // Дальше по лестнице, а НЕ откат к предыдущему потоку.
            //
            // Теперь ссылки проверяются до плеера (см. DesktopRepository.resolveStream),
            // и null здесь чаще всего значит «у этой озвучки все адреса мертвы». Откат
            // в такой ситуации оставлял зрителя перед замершим кадром: играть нечего,
            // а следующая озвучка так и не пробовалась.
            val voices = result?.translations.orEmpty()
            val voiceIndex = voices.indexOfFirst { it.id == result?.translationId }
            // Берём первую ЕЩЁ НЕ ПРОБОВАННУЮ — по той же причине, что и в лестнице
            // мёртвого потока ниже: «следующая за текущей» повторялась, потому что
            // result к моменту второго захода ещё указывал на прежнюю озвучку.
            val nextVoice = voices.drop((voiceIndex + 1).coerceAtLeast(0))
                .plus(voices)
                .firstOrNull { it.id !in triedVoices }
            val nextSource = animeSources.firstOrNull {
                it != playingSourcePreference && it !in triedSources
            }
            when {
                // Черёд озвучек ограничен: все они лежат на одном раздающем, и когда
                // молчит он, перебор четырнадцати штук — это две минуты впустую.
                nextVoice != null && deadRetries < minOf(voices.size, voiceBudget) -> {
                    deadRetries++
                    triedVoices = triedVoices + nextVoice.id
                    PlayerDiagnostics.log("stream.resolve.nextVoice", "next=${nextVoice.id}; name=${nextVoice.name}")
                    error = "Ссылки этой озвучки мертвы — пробую «${nextVoice.name}»…"
                    activeId = "$prefKey:t${nextVoice.id}"
                }
                nextSource != null -> {
                    deadRetries++
                    triedSources = triedSources + nextSource
                    PlayerDiagnostics.log("stream.resolve.nextSource", "next=$nextSource")
                    error = "Ссылки мертвы — пробую источник «$nextSource»…"
                    selectSource(nextSource)
                }
                else -> {
                    error = "Серия недоступна: источник отдаёт мёртвые ссылки во всех озвучках."
                    loading = false
                    playingId?.let { activeId = it }
                    playingSegment?.let { requestedSegment = it }
                    activeSource = playingSourcePreference
                }
            }
            return@LaunchedEffect
        }

        result = resolved
        // Свежий резолв — свежие адреса: прошлая отбраковка к ним отношения не имеет.
        // Отметка ставится заново и ЗДЕСЬ ЖЕ, пока список вариантов чисто источниковый:
        // после доклеивания 1080p от AniLibria отличить «нас понизили» от «появился
        // сосед повыше» было бы уже не по чему.
        forcedQuality = com.aniblaze.desktop.player.forcedQualityFor(
            verifiedQuality = resolved.quality,
            sourceVariants = resolved.variants.orEmpty(),
            heightOf = repository::qualityHeight,
        )
        if (forcedQuality != null) {
            PlayerDiagnostics.log(
                "stream.qualityForced",
                "value=$forcedQuality; reason=верхние адреса не ответили на проверку",
            )
        }
        PlayerDiagnostics.log(
            "stream.resolve.success",
            "source=${resolved.source}; variants=${resolved.variants?.size ?: 0}; hasLocation=${resolved.location.isNotBlank()}; translation=${resolved.translationId}",
        )
        playingSegment = requestSegment

        var committedSource = requestSource
        if (!isCinema && requestSource != null) {
            val actual = resolvedSourcePreference(requestSource, resolved.source, animeSources)
            if (actual != null && !actual.equals(requestSource, ignoreCase = true)) {
                // Never show the contradictory "AniLibria / играет: Anixart" pair.
                // If the resolver had to fall back, commit and persist what is real —
                // and say so, otherwise the picker looks broken ("источник не
                // выбирается") because it snaps back with no explanation.
                committedSource = actual
                activeSource = actual
                settings.setPrimarySource(actual)
                error = "Источник «$requestSource» не смог отдать эту серию — играю через «$actual»."
            }
        }
        var committedId = resolvedPlayerId(prefKey, requestId, if (isCinema) null else resolved.translationId)
        if (!isCinema) {
            resolved.translationId?.let { actualVoice ->
                val actualId = committedId
                if (actualId != requestId) {
                    // Keep the button, request state and persistence aligned with
                    // the translation the resolver really returned.
                    committedId = actualId
                    activeId = actualId
                    settings.savePlayerVoice(sourceVoiceKey(prefKey, committedSource), actualVoice, manual = false)
                    // An explicit voice pick (":t" in the request) that came back as
                    // another dub means the chosen one lacks this episode — surface
                    // that instead of silently snapping the picker back.
                    if (requestId.contains(":t") && settings.playerPref(sourceVoiceKey(prefKey, requestSource))?.voiceManual == true) {
                        val actualName = resolved.translations?.firstOrNull { it.id == actualVoice }?.name
                        error = "У выбранной озвучки нет этой серии — играет «${actualName ?: "другая"}»."
                    }
                }
            }
        }
        playingId = committedId
        playingSourcePreference = committedSource
        loading = false
    }

    val shownSegment = playingSegment ?: requestedSegment
    // Соседние серии считает сама панель управления; здесь остались только те
    // значения, что нужны разметке.
    val enterMs = motionDurationMillis(200, reducedMotion)
    val exitMs = motionDurationMillis(160, reducedMotion)

    // SwingPanel and every Compose control share one fixed Box. With interop
    // blending, controls draw above VLC without ever changing native Canvas bounds.
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        result?.let { res ->
            val variants = res.variants?.takeIf { it.isNotEmpty() }
                ?: listOf(StreamVariant(res.quality.ifBlank { "Auto" }, res.location))
            val committedSegment = playingSegment ?: requestedSegment
            val exactTimings = skipTimingsForEpisode(exactTimingsByEpisode, committedSegment)
            // Какую серию открывали последней. progress этого не покрывает: он
            // живёт только у недосмотренных серий и стирается на финале.
            LaunchedEffect(prefKey, committedSegment) {
                settings.savePlayerSegment(prefKey, committedSegment)
            }

            VlcPlayerView(
                variants = variants,
                referer = res.referer,
                startPositionMs = remember(playingId, committedSegment) {
                    settings.progressMs(contentId, committedSegment)
                },
                preferredQuality = forcedQuality ?: savedPref?.quality?.ifBlank { null },
                preferredDub = savedPref?.dub?.ifBlank { null },
                initialVolume = settingsState.playerVolume,
                audioPreset = settingsState.audioPreset,
                onAudioPresetChange = settings::setAudioPreset,
                openingRange = res.opening?.takeIf { it.isValid } ?: exactTimings.opening,
                endingRange = exactTimings.ending,
                recapRange = exactTimings.recap,
                autoSkipOpening = settingsState.autoSkipOpening,
                autoSkipEnding = settingsState.autoSkipEnding,
                autoDetectTimings = settingsState.autoDetectTimings,
                autoTimingContext = if (isCinema || anime.contentType == "Фильм") null else
                    com.aniblaze.desktop.player.AutoTimingContext(contentId, committedSegment,
                        res.source, res.translationId?.toString() ?: playingId.orEmpty()),
                composeVideo = settingsState.playerComposeVideo,
                enhanceLevel = settingsState.videoEnhance,
                hudPinned = settingsState.hudPinned,
                onToggleHudPin = { settings.setHudPinned(!settingsState.hudPinned) },
                // Подпись в окошке «картинка в картинке»: у фильма серии нет, у аниме
                // без номера непонятно, что именно идёт в углу экрана.
                pipTitle = if (isCinema) anime.title else "${anime.title} · Серия $committedSegment",
                comments = overlayComments,
                commentsState = commentsState,
                commentsEnabled = settingsState.commentsOverlay,
                commentsRate = settingsState.commentsRate,
                commentsOpacity = settingsState.commentsOpacity,
                commentsFontSize = settingsState.commentsFontSize,
                commentsMoving = settingsState.commentsMoving,
                commentsEpisode = if (isCinema) 0 else committedSegment,
                commentsFreshOnly = settingsState.commentsFreshOnly,
                // Симулятор живого чата. Тексты он берёт из тех же комментариев, что
                // и реплики поверх кадра, поэтому отдельной загрузки ему не нужно —
                // но и без них он работает: остальное пишут виртуальные зрители.
                chat = com.aniblaze.desktop.player.ChatOptions(
                    enabled = settingsState.chatEnabled,
                    viewers = settingsState.chatViewers,
                    intensity = com.aniblaze.desktop.player.ChatIntensity.of(settingsState.chatIntensity),
                    speed = settingsState.chatSpeed,
                    alwaysQuiet = settingsState.chatAlwaysQuiet,
                    realOnly = settingsState.chatRealOnly,
                    popularFirst = settingsState.chatPopularFirst,
                    episodeOnly = settingsState.chatEpisodeOnly,
                    fontSize = settingsState.chatFontSize,
                    panelWidth = settingsState.chatPanelWidth,
                    side = settingsState.chatSide,
                    overlayMode = settingsState.chatOverlayMode,
                    overlayPosition = com.aniblaze.desktop.player.ChatOverlayPosition.of(settingsState.chatOverlayPosition),
                    overlayLines = settingsState.chatOverlayLines,
                    overlayOpacity = settingsState.chatOverlayOpacity,
                    overlayFontSize = settingsState.chatOverlayFontSize,
                ),
                // Поток умер. Лестница попыток — от самой дешёвой к самой грубой.
                //
                // ПРОВЕРЕНО по логу: одного перезапроса мало. Anixart на каждый заход
                // отдаёт ОДНУ И ТУ ЖЕ ссылку, а она уже отвечает 404 при любых
                // заголовках — файл этой озвучки просто умер на стороне CDN. Крутить
                // перезапрос в такой ситуации бессмысленно, и качества перебирать тоже:
                // они все живут на том же хосте. Спасает только смена того, ЧТО играем,
                // — другая озвучка (другой файл) или другой источник (другой каталог).
                onStreamDead = {
                    val voices = res.translations.orEmpty()
                    val voiceIndex = voices.indexOfFirst { it.id == res.translationId }
                    PlayerDiagnostics.log(
                        "playerScreen.streamDead",
                        "content=$contentId; segment=$committedSegment; retries=$deadRetries; voices=${voices.size}",
                    )
                    when {
                        // 1. Разовый сбой — просто просим ссылку заново.
                        deadRetries == 0 -> {
                            deadRetries++
                            error = "Поток оборвался — перезапрашиваю ссылку…"
                            resolveNonce++
                        }
                        // 2. Ссылка не воскресла: у другой озвучки другой файл.
                        voices.size > 1 && deadRetries <= minOf(voices.size, voiceBudget) -> {
                            // Берём первую ЕЩЁ НЕ ПРОБОВАННУЮ, а не «следующую за
                            // текущей». По логу видно, зачем: при двух сбоях подряд
                            // выбиралась одна и та же «Flarrow Films» дважды —
                            // res.translationId к моменту второго вызова ещё указывал
                            // на прежнюю озвучку, и «следующая за ней» повторялась.
                            val next = voices.drop((voiceIndex + 1).coerceAtLeast(0))
                                .plus(voices)
                                .firstOrNull { it.id !in triedVoices }
                            if (next == null) {
                                error = "Серия недоступна ни в одной озвучке."
                            } else {
                                deadRetries++
                                triedVoices = triedVoices + next.id
                                PlayerDiagnostics.log(
                                    "playerScreen.streamDead.voice",
                                    "next=${next.id}; name=${next.name}; tried=${triedVoices.size}",
                                )
                                error = "Поток не отвечает — переключаю на озвучку «${next.name}»…"
                                activeId = "$prefKey:t${next.id}"
                            }
                        }
                        // 3. Озвучки кончились (или их черёд вышел) — меняем источник
                        //    целиком: другой каталог, другой раздающий.
                        else -> {
                            val next = animeSources.firstOrNull {
                                it != playingSourcePreference && it !in triedSources
                            }
                            if (next != null) {
                                deadRetries++
                                triedSources = triedSources + next
                                PlayerDiagnostics.log(
                                    "playerScreen.streamDead.source",
                                    "next=$next; tried=${triedSources.size}",
                                )
                                error = "Поток не отвечает — пробую источник «$next»…"
                                selectSource(next)
                            } else {
                                error = "Серия недоступна ни в одной озвучке и ни в одном источнике."
                            }
                        }
                    }
                },
                onQualityChange = { settings.savePlayerQuality(prefKey, it) },
                onDubChange = { settings.savePlayerDub(prefKey, it) },
                onVolumeChange = settings::setPlayerVolume,
                onProgress = progress@ { checkpoint: PlaybackCheckpoint ->
                    val checkpointSegment = episodeFromMediaKey(contentId, checkpoint.mediaKey)
                    if (checkpointSegment == null) {
                        PlayerDiagnostics.log(
                            "playerScreen.progress.ignored",
                            "content=$contentId; mediaKey=${checkpoint.mediaKey}",
                        )
                        return@progress
                    }
                    settings.saveProgress(
                        anime,
                        checkpointSegment,
                        checkpoint.positionMs,
                        checkpoint.durationMs,
                        checkpoint.watchedDeltaMs,
                    )
                    petSession.recordWatch(checkpointSegment, checkpoint.watchedDeltaMs)
                },
                onEnded = ended@ { endedMediaKey ->
                    val endedSegment = episodeFromMediaKey(contentId, endedMediaKey)
                    if (endedSegment == null) {
                        PlayerDiagnostics.log(
                            "playerScreen.onEnded.ignored",
                            "content=$contentId; mediaKey=$endedMediaKey",
                        )
                        return@ended
                    }
                    PlayerDiagnostics.log("playerScreen.onEnded", "autoplay=${settingsState.autoplayNext}; autoSwitch=${settingsState.autoSwitchRandom}; isCinema=$isCinema; segment=$endedSegment")
                    // The final native checkpoint is saved immediately before this
                    // callback. The end event may complete a genuine near-end resume,
                    // but a seek straight to the end has no verified playback evidence.
                    val completed = settings.confirmEpisodeEnded(contentId, endedSegment)
                    if (completed && !isCinema) {
                        val classification = com.aniblaze.desktop.recommendationCompletion(settings.state.value, anime.toPersisted())
                        val event = when (classification) {
                            com.aniblaze.desktop.RecommendationCompletion.COMPLETED -> com.aniblaze.desktop.pet.PetEvent.SEASON_DONE
                            com.aniblaze.desktop.RecommendationCompletion.CAUGHT_UP -> com.aniblaze.desktop.pet.PetEvent.CAUGHT_UP
                            else -> com.aniblaze.desktop.pet.PetEvent.EPISODE_DONE
                        }
                        petSession.recordCompletion(endedSegment, event,
                            announce = settings.state.value.petEnabled && settings.state.value.petInPlayer &&
                                settings.claimPetEvent("done:$contentId:$endedSegment:$event"))
                    }
                    PlayerDiagnostics.log(
                        "playerScreen.onEnded.progress",
                        "segment=$endedSegment; watched=$completed",
                    )
                    if (!isCinema) {
                        val next = playableNeighbors(segments, endedSegment).next
                        PlayerDiagnostics.log("playerScreen.autoplay", "nextSegment=$next")
                        when {
                            settingsState.autoplayNext && next != null -> requestedSegment = next
                            // The LAST episode just finished and the «случайное
                            // аниме» toggle is on — hop to a random title from
                            // trending / watching-now / true random. Never fires
                            // while more episodes exist.
                            next == null && settingsState.autoSwitchRandom -> scope.launch {
                                val pick = try {
                                    repository.randomOngoingPick(contentId)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                }
                                PlayerDiagnostics.log("playerScreen.autoSwitch", "pick=${pick?.id ?: "none"}")
                                pick?.let {
                                    settings.recordHistory(it)
                                    onSwitchTitle(it)
                                }
                            }
                        }
                    }
                },
                fullscreen = fullscreen,
                videoVisible = visible,
                suspended = suspended,
                mediaKey = "$contentId:$committedSegment",
                onToggleFullscreen = onToggleFullscreen,
                // HUD copies of the top-panel pickers (the top panel is painted
                // over by the heavyweight video; the HUD stays visible).
                sources = if (isCinema) emptyList() else animeSources,
                activeSource = activeSource,
                onSourceChange = { source -> selectSource(source) },
                voices = res.translations.orEmpty(),
                activeVoiceId = res.translationId,
                voiceShares = voiceShares,
                autoSwitchRandom = if (isCinema) false else settingsState.autoSwitchRandom,
                onToggleAutoSwitch = if (isCinema) null else ({
                    settings.setAutoSwitchRandom(!settingsState.autoSwitchRandom)
                }),
                onVoiceChange = { id ->
                    PlayerDiagnostics.log("playerScreen.hudVoice.select", "id=$id; oldActiveId=$activeId")
                    activeId = activeId.substringBefore(":t") + ":t$id"
                    settings.savePlayerVoice(sourceVoiceKey(prefKey, activeSource), id)
                },
                // Episode navigation on the HUD (prev / picker / next).
                episodes = remember(segments) {
                    segments.map {
                        com.aniblaze.desktop.player.PlayerEpisode(
                            number = it.number,
                            title = it.title.ifBlank { "Серия ${it.number}" },
                            playable = it.playable,
                        )
                    }
                },
                activeEpisode = committedSegment,
                episodeTotal = if (isCinema) 0 else anime.episodesTotal,
                // Питомец поверх кадра: своя позиция в долях окна, перетаскивание.
                pet = if (settingsState.petEnabled && settingsState.petInPlayer && !isCinema) {
                    com.aniblaze.desktop.player.PetOverlayOptions(
                        petId = settingsState.petCharacter,
                        scale = settingsState.petScale,
                        x = petX,
                        y = petY,
                        onMove = movePet,
                        session = petSession,
                        speechEnabled = settingsState.petSpeechEnabled,
                        quietWatching = settingsState.petQuietWatching,
                        chatterCooldownMs = settingsState.petChatterMinutes * 60_000L,
                        context = com.aniblaze.desktop.pet.PetPlayerContext(
                            activity = settingsState.drizzActivity,
                            titleName = anime.title,
                            titleWatchedMs = settings.watchedMsOf(anime.id),
                            watchedEpisodes = settings.watchOf(anime.id)?.completedCount ?: 0,
                            daysSinceLastWatch = petPreviousDays,
                            rewatch = petRewatch,
                            voiceName = res.translations?.firstOrNull { it.id == res.translationId }?.name.orEmpty(),
                            sourceName = res.source,
                            qualityForced = forcedQuality != null,
                            poster = anime.poster,
                            // Оценка — та же, что на странице тайтла: одно хранилище,
                            // одно значение. Питомец только спрашивает.
                            rating = settings.ratingOf(anime.id, settingsState),
                            onRate = { score -> settings.setRating(anime, score) },
                            genres = anime.genres,
                            // Память жанров: три предыдущих тайтла из истории, без текущего.
                            recentGenres = settingsState.history.filter { it.id != anime.id }.take(3).map { it.genres },
                            catalogRating = anime.rating,
                            ratingMax = anime.ratingMax,
                            fullscreen = fullscreen,
                        ),
                    )
                } else {
                    null
                },
                onEpisodeChange = { number ->
                    PlayerDiagnostics.log("playerScreen.hudEpisode.select", "number=$number")
                    requestedSegment = number
                },
                // Rollback/fallback notices surface inside the HUD (the top banner
                // used to sit under the heavyweight video and was never seen).
                streamNotice = error,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (loading) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)).zIndex(2f)) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    // Причина, а не «идёт подготовка».
                    //
                    // Лестница восстановления всё это время писала в `error`, зачем
                    // ждём и что пробуем следующим, а видел зритель одно и то же
                    // «Подготовка потока…» — то есть ровно ничего. Раз причина есть,
                    // показываем её.
                    Text(
                        error ?: "Подготовка потока…",
                        color = Color.White,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        } else if (result == null && error != null) {
            // Initial-load failure: no video yet, so a centered message is visible.
            // Mid-playback failures reach the user via the HUD notice instead —
            // a banner here overlaps the heavyweight video and is never painted.
            Text(error!!, color = Color.White, modifier = Modifier.align(Alignment.Center).zIndex(2f))
        }

        // До создания VLC питомец тоже получает настоящее состояние загрузки/ошибки.
        if (result == null && visible && !suspended && !isCinema && settingsState.petEnabled && settingsState.petInPlayer) {
            com.aniblaze.desktop.pet.PetPlayerHost(
                pet = com.aniblaze.desktop.pet.PetDef.of(settingsState.petCharacter),
                scale = settingsState.petScale,
                xFraction = petX,
                yFraction = petY,
                onMove = movePet,
                episode = requestedSegment,
                episodesAvailable = anime.episodesAvailable,
                episodesTotal = anime.episodesTotal,
                playing = false,
                buffering = loading,
                playbackError = !loading && error != null,
                positionMs = 0L,
                durationMs = 0L,
                session = petSession,
                speechEnabled = settingsState.petSpeechEnabled,
                chatterCooldownMs = settingsState.petChatterMinutes * 60_000L,
                animate = !com.aniblaze.desktop.player.systemPrefersReducedMotion(),
                modifier = Modifier.fillMaxSize().zIndex(3f),
            )
        }

        // Верхней панели здесь больше нет. Название дублировало вкладку, а выбор и
        // перелистывание серий — те же кнопки на самой панели управления, которая
        // теперь лежит поверх видео и никуда не прячется. Осталась единственная
        // кнопка, которой нет больше нигде, и только у «Кино».
        if (!fullscreen && visible && isCinema &&
            (embedUrl != null || contentId.substringBefore(":t").startsWith("http"))
        ) {
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).zIndex(5f)) {
                OutlinedButton(onClick = {
                    PlayerDiagnostics.log("playerScreen.openBrowser", "url=${(embedUrl ?: contentId.substringBefore(":t")).take(60)}")
                    openInBrowser(embedUrl ?: contentId.substringBefore(":t"))
                }) {
                    Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("Плеер в браузере")
                }
            }
        }
    }
}

internal data class EpisodeNeighbors(val previous: Int?, val next: Int?)

/**
 * Сколько страниц обсуждения тянуть под оверлей комментариев (25 штук на страницу).
 *
 * Двенадцать, а не три: общий пул тайтла режется на непересекающиеся доли по сериям
 * (см. episodeScope), и число долей упирается в размер пула. ЗАМЕРЕНО: шести страниц
 * хватало лишь на 64 реплики после отбора, то есть на ДВЕ доли — и серии 1, 3, 5, 7
 * получали одинаковый набор. Лента уходит вглубь минимум на двенадцать страниц (294
 * комментария у «Деревни кузнецов»), что даёт вчетверо больше долей.
 *
 * Запросы идут в фоне и один раз на тайтл, а первая реплика всплывает не раньше
 * восьмой секунды — на просмотр это не влияет.
 */
internal fun playableNeighbors(segments: List<Segment>, current: Int): EpisodeNeighbors {
    val playable = segments.asSequence()
        .filter { it.playable }
        .map { it.number }
        .distinct()
        .sorted()
        .toList()
    if (playable.isEmpty()) return EpisodeNeighbors(null, null)
    val exact = playable.indexOf(current)
    return if (exact >= 0) {
        EpisodeNeighbors(playable.getOrNull(exact - 1), playable.getOrNull(exact + 1))
    } else {
        EpisodeNeighbors(playable.lastOrNull { it < current }, playable.firstOrNull { it > current })
    }
}

internal fun episodeLabel(segments: List<Segment>, current: Int, cinema: Boolean): String =
    if (cinema) segments.firstOrNull { it.number == current }?.title ?: "Серия $current"
    else "Серия $current"

internal fun resolvedSourcePreference(
    requested: String?,
    actual: String,
    knownSources: List<String>,
): String? {
    if (requested == null) return null
    return knownSources.firstOrNull { it.equals(actual, ignoreCase = true) } ?: requested
}

internal fun resolvedPlayerId(prefKey: String, requestedId: String, translationId: Int?): String =
    translationId?.let { "$prefKey:t$it" } ?: requestedId

private fun sourceVoiceKey(contentKey: String, source: String?): String =
    "$contentKey@source=${source ?: "auto"}"

