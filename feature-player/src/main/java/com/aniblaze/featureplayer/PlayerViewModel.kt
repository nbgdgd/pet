package com.aniblaze.featureplayer

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniblaze.aggregator.model.Caption
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StreamVariant
import com.aniblaze.aggregator.model.StreamMode
import com.aniblaze.aggregator.model.Translation
import com.aniblaze.aggregator.repository.AnimeRepository
import com.aniblaze.database.settings.SettingsDataStore
import com.aniblaze.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.URLDecoder
import javax.inject.Inject

/** UI state for the player overlay (separate from the raw [com.aniblaze.player.PlaybackState]). */
data class PlayerUiState(
    val isResolving: Boolean = true,
    val title: String = "",
    val segmentNumber: Int = 0,
    val hasNext: Boolean = false,
    val qualities: List<StreamVariant> = emptyList(),
    val currentQuality: String = "",
    val translations: List<Translation> = emptyList(),
    val currentTranslationId: Int? = null,
    /** Requested transport while resolving; the exact provider appears in [currentStreamSource]. */
    val streamMode: StreamMode = StreamMode.AUTO,
    val currentStreamSource: String = "",
    val episodes: List<Int> = emptyList(),
    val error: String? = null,
    // «Источник» picker: null = авто (обычный резолвер по приоритету).
    val sources: List<String> = emptyList(),
    val activeSource: String? = null,
    // «Случайное аниме после последней серии» (shuffle toggle).
    val autoSwitchRandom: Boolean = false,
    /** Точные границы опенинга/эндинга (AniSkip); null = кнопки нет. */
    val opening: com.aniblaze.aggregator.model.OpeningRange? = null,
    val ending: com.aniblaze.aggregator.model.OpeningRange? = null,
    /** Доступные субтитры для текущего потока. */
    val availableSubtitles: List<Caption> = emptyList(),
    /** Индекс выбранного трека субтитров (-1 = выключены). */
    val selectedSubtitleIndex: Int = -1,
)

sealed interface PlayerIntent {
    data object PlayPause : PlayerIntent
    data class Seek(val deltaMs: Long) : PlayerIntent
    data class SeekTo(val positionMs: Long) : PlayerIntent
    data class Speed(val value: Float) : PlayerIntent
    data class SelectQuality(val variant: StreamVariant) : PlayerIntent
    data class SelectTranslation(val id: Int) : PlayerIntent
    data class SelectEpisode(val number: Int) : PlayerIntent
    data class SelectSource(val name: String?) : PlayerIntent
    data object ToggleAutoSwitch : PlayerIntent
    data class SelectSubtitle(val index: Int) : PlayerIntent
    data object Next : PlayerIntent
    data object Retry : PlayerIntent
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: AnimeRepository,
    private val settings: SettingsDataStore,
    val controller: PlaybackController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val contentId: String =
        URLDecoder.decode(savedStateHandle.get<String>("contentId").orEmpty(), "UTF-8")
    private val initialSegmentId: String =
        URLDecoder.decode(savedStateHandle.get<String>("segmentId").orEmpty(), "UTF-8")
    private val streamMode: StreamMode = StreamMode.fromRoute(savedStateHandle["streamMode"])
    private val isCinema: Boolean = contentId.startsWith("tmdb")

    /** Token that owns the singleton ExoPlayer for this navigation entry. */
    private val playbackSessionId: Long = controller.beginSession()

    private val _ui = MutableStateFlow(PlayerUiState(streamMode = streamMode))
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    // ПОЧЕМУ два отдельных потока вместо одного PlaybackState: этот объект целиком
    // перезаписывается каждые 4 секунды (startProgressTicker -> controller.syncPosition()
    // кладёт в него свежие positionMs/durationMs). Экран подписывался на весь объект и
    // из-за этого пересобирался раз в 4 с всю серию — на ПК тот же паттерн дал 3449
    // лишних пересборок за сеанс. Экрану нужны ровно два поля, и меняются они по
    // действию пользователя, а не по таймеру.
    val isPlaying: StateFlow<Boolean> = controller.state
        .map { it.isPlaying }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isBuffering: StateFlow<Boolean> = controller.state
        .map { it.isBuffering }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val speed: StateFlow<Float> = controller.state
        .map { it.speed }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)

    private var segments: List<Segment> = emptyList()
    private var currentSegment: Segment? = null
    private var currentResult: ContentResult? = null
    private var currentTitle: String = ""

    /** Clean anime title (no "· E4" suffix) for by-title source resolution. */
    private var animeTitle: String = ""
    private var animeYear: Int = 0

    /** Оригинальное (ромадзи) название — запасной ключ поиска MAL id. */
    private var animeAltTitle: String = ""

    /** Последний замер непрерывного движения медиачасов; ручной seek его сбрасывает. */
    private var evidenceSegmentId: String? = null
    private var evidencePositionMs: Long = 0L
    private var evidenceAtMs: Long = 0L
    private var evidenceWasPlaying: Boolean = false

    /** Content id used for stream resolution; carries the chosen voiceover (:t{id}). */
    private var activeContentId: String = contentId

    /** «Случайное аниме»: picked title's id — the screen navigates to its player. */
    private val _switchTitle = MutableStateFlow<String?>(null)
    val switchTitle: StateFlow<String?> = _switchTitle.asStateFlow()

    /**
     * Комментарии зрителей для показа поверх видео.
     *
     * Грузятся ОДИН раз на тайтл и переживают смену серии: у Anixart обсуждение общее
     * на весь тайтл, а нарезкой по сериям занимается [episodeScope]. Загрузка
     * откладывается: пока поток не пошёл, сеть нужна ему, а не комментариям.
     */
    private val _comments = MutableStateFlow<List<com.aniblaze.aggregator.model.TitleComment>>(emptyList())
    val comments: StateFlow<List<com.aniblaze.aggregator.model.TitleComment>> = _comments.asStateFlow()

    val danmakuEnabled: StateFlow<Boolean> = settings.settings
        .map { it.danmakuEnabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val danmakuRate: StateFlow<String> = settings.settings
        .map { it.danmakuRate }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "normal")

    val chatEnabled: StateFlow<Boolean> = settings.settings
        .map { it.chatEnabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val chatHideSpoilers: StateFlow<Boolean> = settings.settings
        .map { it.chatHideSpoilers }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val autoSkipOpening: StateFlow<Boolean> = settings.settings
        .map { it.autoSkipOpening }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val autoSkipEnding: StateFlow<Boolean> = settings.settings
        .map { it.autoSkipEnding }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        controller.setOnSegmentEnded(playbackSessionId) { onSegmentEnded() }
        _ui.value = _ui.value.copy(sources = if (isCinema) emptyList() else repository.playerSources())
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                autoSwitchRandom = runCatching { settings.settings.first().autoSwitchRandom }.getOrDefault(false),
            )
        }
        bootstrap()
        startProgressTicker()
        loadComments()
    }

    private fun loadComments() {
        viewModelScope.launch {
            // Если настройку включили уже после открытия плеера, прежний код успевал
            // выйти навсегда и комментарии не загружались до следующего запуска серии.
            // Теперь корутина ждёт первого включённого состояния и загружает их один раз.
            runCatching { settings.settings.first { it.danmakuEnabled || it.chatEnabled } }
                .getOrElse { return@launch }
            // Дать потоку стартовать: на мобильном канале две страницы комментариев,
            // выпущенные одновременно с резолвом ссылки, отбирают у него полосу ровно в
            // тот момент, когда он набирает буфер.
            kotlinx.coroutines.delay(COMMENTS_DELAY_MS)
            val loaded = (0 until COMMENT_PAGES).flatMap { page ->
                runCatching { repository.titleComments(contentId, page) }.getOrDefault(emptyList())
            }.distinctBy { it.id }
            Timber.d("[PlayerVM] danmaku comments loaded=%d", loaded.size)
            _comments.value = loaded
        }
    }

    fun onIntent(intent: PlayerIntent) {
        when (intent) {
            PlayerIntent.PlayPause -> controller.playPause()
            is PlayerIntent.Seek -> {
                val target = (controller.currentPositionMs() + intent.deltaMs)
                    .coerceIn(0L, controller.durationMs().coerceAtLeast(0L))
                controller.seekBy(intent.deltaMs)
                resetPlaybackEvidence(target)
            }
            is PlayerIntent.SeekTo -> {
                controller.seekTo(intent.positionMs)
                resetPlaybackEvidence(intent.positionMs)
            }
            is PlayerIntent.Speed -> controller.setSpeed(intent.value)
            is PlayerIntent.SelectQuality -> selectQuality(intent.variant)
            is PlayerIntent.SelectTranslation -> selectTranslation(intent.id)
            is PlayerIntent.SelectEpisode -> segments.firstOrNull { it.number == intent.number }?.let(::switchToSegment)
            is PlayerIntent.SelectSource -> selectSource(intent.name)
            PlayerIntent.ToggleAutoSwitch -> toggleAutoSwitch()
            is PlayerIntent.SelectSubtitle -> selectSubtitle(intent.index)
            PlayerIntent.Next -> playNext()
            PlayerIntent.Retry -> bootstrap()
        }
    }

    fun consumeSwitchTitle() {
        _switchTitle.value = null
    }

    private fun bootstrap() {
        Timber.d("[PlayerVM] bootstrap contentId='%s' segmentId='%s'", contentId, initialSegmentId)
        _ui.value = _ui.value.copy(isResolving = true, error = null)
        viewModelScope.launch {
            segments = runCatching { repository.segments(contentId) }.getOrDefault(emptyList())
            _ui.value = _ui.value.copy(episodes = segments.map { it.number }.sorted())
            Timber.d("[PlayerVM] segments loaded: %d", segments.size)
            if (segments.isNotEmpty()) {
                Timber.d("[PlayerVM] first=%s last=%s", segments.first().id, segments.last().id)
            }
            val target = segments.firstOrNull { it.id == initialSegmentId }
                ?: segments.firstOrNull()
            if (target == null) {
                Timber.w("[PlayerVM] no segments found for %s", contentId)
                _ui.value = _ui.value.copy(isResolving = false, error = "No playable episode found.")
                return@launch
            }
            Timber.d("[PlayerVM] playing target segment id=%s num=%d", target.id, target.number)
            play(target)
        }
    }

    private fun play(segment: Segment) {
        currentSegment = segment
        _ui.value = _ui.value.copy(
            isResolving = true,
            segmentNumber = segment.number,
            hasNext = segments.indexOf(segment) < segments.lastIndex,
            error = null,
        )
        viewModelScope.launch {
            val anime = repository.detail(contentId)
            animeTitle = anime?.title.orEmpty()
            animeYear = anime?.year ?: 0
            // Anixart кладёт оригинальное название в `status`.
            animeAltTitle = if (contentId.startsWith("ax:")) anime?.status.orEmpty() else ""
            val title = "${anime?.title ?: contentId} · E${segment.number}"
            Timber.d("[PlayerVM] play resolving contentId=%s segment=%d title='%s' via=%s", contentId, segment.number, title, _ui.value.activeSource ?: "auto")
            val resumePos = repository.progressFor(contentId, segment.id)
            Timber.d("[PlayerVM] resumePos=%dms", resumePos)
            runCatching {
                // Явно выбранный источник: резолвим через него (по id или по названию).
                // Пусто → честный фолбэк на обычный резолвер, чтобы серия всё же играла.
                _ui.value.activeSource?.let { src ->
                    repository.resolveStreamVia(src, activeContentId, segment.number, animeTitle, animeYear)
                } ?: repository.resolveStream(activeContentId, segment.number, streamMode)
            }
                .onSuccess { result ->
                    Timber.d("[PlayerVM] resolve OK quality='%s' source='%s' variants=%d voiceovers=%d", result.quality, result.source, result.variants?.size ?: 0, result.translations?.size ?: 0)
                    currentResult = result
                    currentTitle = title
                    // Honour the user's preferred quality if that rendition exists.
                    // Сверка по ВЫСОТЕ, а не по подписи целиком: подписи у источников
                    // разные, а человек выбирает 1080p (см. pickPreferred).
                    val prefQ = runCatching { settings.settings.first().preferredQuality }.getOrDefault("")
                    val chosen = pickPreferred(result.variants.orEmpty(), prefQ)
                    val toPlay = if (chosen != null) result.copy(location = chosen.url, quality = chosen.quality) else result
                    if (!controller.prepare(toPlay, title, resumePos, playbackSessionId)) {
                        Timber.d("[PlayerVM] stale resolve ignored for %s #%d", contentId, segment.number)
                        return@onSuccess
                    }
                    resetPlaybackEvidence(resumePos)
                    repository.recordHistory(contentId, segment.id)
                    _ui.value = _ui.value.copy(
                        isResolving = false,
                        title = title,
                        qualities = result.variants ?: emptyList(),
                        currentQuality = toPlay.quality,
                        currentStreamSource = result.source,
                        translations = result.translations ?: _ui.value.translations,
                        currentTranslationId = result.translationId ?: _ui.value.currentTranslationId,
                        availableSubtitles = result.captions ?: emptyList(),
                        selectedSubtitleIndex = -1,
                    )
                    maybeUpgradeQuality(result, segment.number)
                    loadSkipTimings(segment.number)
                }
                .onFailure {
                    Timber.e(it, "[PlayerVM] resolve failed for %s #%d", contentId, segment.number)
                    val message = when (streamMode) {
                        StreamMode.TORRENT -> it.message ?: "Торрент для этого видео не найден."
                        StreamMode.PARSER -> "Парсер не смог получить поток."
                        StreamMode.AUTO -> "Не удалось воспроизвести эту серию."
                    }
                    _ui.value = _ui.value.copy(
                        isResolving = false,
                        error = message,
                    )
                }
        }
    }

    /**
     * Точные тайминги опенинга/эндинга. Раньше кнопка «Пропустить опенинг» делала
     * СЛЕПОЙ прыжок на +85 секунд от текущей позиции и показывалась первые две
     * минуты у любого тайтла — то есть промахивалась примерно так же часто, как
     * попадала. Теперь границы берутся из AniSkip по MAL id, и кнопки появляются
     * только там, где интервал реально известен.
     */
    private fun loadSkipTimings(episode: Int) {
        _ui.value = _ui.value.copy(opening = null, ending = null)
        viewModelScope.launch {
            val t = runCatching { repository.skipTimings(animeTitle, episode, animeAltTitle) }
                .getOrNull() ?: return@launch
            if (currentSegment?.number != episode) return@launch
            Timber.d("[PlayerVM] skip timings op=%s ed=%s", t.opening, t.ending)
            _ui.value = _ui.value.copy(opening = t.opening, ending = t.ending)
        }
    }

    /** Источник упёрся в ≤720p → тихо доклеиваем 1080p от AniLibria в пикер качества.
     *  Текущий поток не трогается; выбор пункта осознанно переключает поток. */
    private fun maybeUpgradeQuality(result: ContentResult, segment: Int) {
        if (result.source.contains("libria", ignoreCase = true)) return
        // Отказ только при ТОЧНОМ знании, что 1080p уже есть. «auto» и пустой список —
        // это неизвестность, и раньше на них функция молча выходила (см. shouldTryHiRes).
        if (!shouldTryHiRes(result.variants.orEmpty())) return
        viewModelScope.launch {
            val extra = runCatching { repository.hiResVariants(animeTitle, segment, animeYear) }
                .getOrDefault(emptyList())
            if (extra.isEmpty()) {
                Timber.d("[PlayerVM] hi-res upgrade: AniLibria не дала 1080p для '%s' #%d", animeTitle, segment)
                return@launch
            }
            // Никто не перерезолвил стрим, пока мы ходили за AniLibria.
            if (currentResult === result && currentSegment?.number == segment) {
                val merged = result.copy(variants = mergeVariants(result.variants.orEmpty(), extra))
                Timber.d(
                    "[PlayerVM] hi-res upgrade: +%d variants, список: %s",
                    extra.size,
                    merged.variants.orEmpty().joinToString { it.quality },
                )
                currentResult = merged
                _ui.value = _ui.value.copy(qualities = merged.variants.orEmpty())
            }
        }
    }

    /** «Источник»: null = авто. Перерезолвит текущую серию с той же позиции. */
    private fun selectSource(name: String?) {
        if (name == _ui.value.activeSource) return
        val segment = currentSegment ?: return
        _ui.value = _ui.value.copy(activeSource = name)
        val pos = controller.currentPositionMs()
        Timber.d("[PlayerVM] switch source -> %s at %dms", name ?: "auto", pos)
        _ui.value = _ui.value.copy(isResolving = true, error = null)
        viewModelScope.launch {
            val result = runCatching {
                name?.let { repository.resolveStreamVia(it, activeContentId, segment.number, animeTitle, animeYear) }
                    ?: repository.resolveStream(activeContentId, segment.number)
            }.getOrNull()
            if (result == null) {
                // Источник не смог — остаёмся на текущем потоке, честно говорим.
                _ui.value = _ui.value.copy(
                    isResolving = false,
                    activeSource = null,
                    error = name?.let { "«$it» не отдал эту серию — играет прежний поток." },
                )
                return@launch
            }
            currentResult = result
            controller.prepare(result, currentTitle, pos, playbackSessionId)
            resetPlaybackEvidence(pos)
            _ui.value = _ui.value.copy(
                isResolving = false,
                qualities = result.variants ?: emptyList(),
                currentQuality = result.quality,
                currentStreamSource = result.source,
                translations = result.translations ?: emptyList(),
                currentTranslationId = result.translationId,
            )
            maybeUpgradeQuality(result, segment.number)
        }
    }

    private fun toggleAutoSwitch() {
        val next = !_ui.value.autoSwitchRandom
        _ui.value = _ui.value.copy(autoSwitchRandom = next)
        viewModelScope.launch { settings.setAutoSwitchRandom(next) }
    }

    /**
     * Select a subtitle track by index. Index -1 disables subtitles.
     * Uses Media3 track selection parameters to enable/disable subtitle rendering.
     */
    private fun selectSubtitle(index: Int) {
        _ui.value = _ui.value.copy(selectedSubtitleIndex = index)
        val subtitles = _ui.value.availableSubtitles
        if (index < 0 || index >= subtitles.size) {
            // Disable all subtitle tracks
            controller.player.trackSelectionParameters = controller.player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                .build()
        } else {
            // Enable subtitles and select the specific track
            val caption = subtitles[index]
            controller.player.trackSelectionParameters = controller.player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(caption.language)
                .build()
        }
    }

    private fun playNext() {
        val current = currentSegment ?: return
        val next = segments.getOrNull(segments.indexOf(current) + 1) ?: return
        switchToSegment(next)
    }

    /** Сохраняет именно покидаемую серию до смены [currentSegment]. */
    private fun switchToSegment(next: Segment) {
        if (next.id == currentSegment?.id) return
        viewModelScope.launch {
            persistProgress()
            play(next)
        }
    }

    /** Switches quality in place, preserving the current playback position. */
    private fun selectQuality(variant: StreamVariant) {
        val base = currentResult ?: return
        if (variant.quality == _ui.value.currentQuality) return
        val pos = controller.currentPositionMs()
        Timber.d("[PlayerVM] switch quality -> %s at %dms", variant.quality, pos)
        controller.prepare(
            base.copy(location = variant.url, quality = variant.quality),
            currentTitle,
            pos,
            playbackSessionId,
        )
        resetPlaybackEvidence(pos)
        _ui.value = _ui.value.copy(currentQuality = variant.quality)
        // В настройки — только высота: «1080p · AniLibria» это подпись одного источника,
        // а желание человека переживает и смену источника, и смену тайтла.
        viewModelScope.launch { settings.setPreferredQuality(preferredQualityKey(variant.quality)) }
    }

    /** Switches voiceover (озвучка): re-resolves the current episode under the new dub. */
    private fun selectTranslation(typeId: Int) {
        if (typeId == _ui.value.currentTranslationId) return
        val segment = currentSegment ?: return
        val base = contentId.substringBefore(":t")
        activeContentId = "$base:t$typeId"
        _ui.value.translations.firstOrNull { it.id == typeId }?.name?.takeIf { it.isNotBlank() }?.let { name ->
            viewModelScope.launch {
                settings.setPreferredVoiceover(name)
                settings.setManualVoiceover(contentId, name)
            }
        }
        val pos = controller.currentPositionMs()
        Timber.d("[PlayerVM] switch voiceover -> %d (%s) at %dms", typeId, activeContentId, pos)
        _ui.value = _ui.value.copy(isResolving = true, currentTranslationId = typeId, error = null)
        viewModelScope.launch {
            runCatching { repository.resolveStream(activeContentId, segment.number, streamMode) }
                .onSuccess { result ->
                    currentResult = result
                    controller.prepare(result, currentTitle, pos, playbackSessionId)
                    resetPlaybackEvidence(pos)
                    _ui.value = _ui.value.copy(
                        isResolving = false,
                        qualities = result.variants ?: emptyList(),
                        currentQuality = result.quality,
                        currentStreamSource = result.source,
                        translations = result.translations ?: _ui.value.translations,
                        currentTranslationId = result.translationId ?: typeId,
                    )
                }
                .onFailure {
                    Timber.w(it, "[PlayerVM] voiceover switch failed")
                    _ui.value = _ui.value.copy(isResolving = false, error = "Эта озвучка недоступна.")
                }
        }
    }

    private fun onSegmentEnded() {
        viewModelScope.launch {
            persistProgress(ended = true)
            val current = currentSegment
            val hasNext = current != null && segments.indexOf(current) < segments.lastIndex
            when {
                hasNext && settings.settings.first().autoNextSegment -> {
                    segments.getOrNull(segments.indexOf(current) + 1)?.let(::play)
                }
                // ПОСЛЕДНЯЯ серия кончилась и включён shuffle — прыжок на случайный
                // тайтл (тренды / сейчас смотрят / рандом, без уже просмотренного).
                // Пока есть следующая серия, этот путь не срабатывает никогда.
                !hasNext && _ui.value.autoSwitchRandom -> {
                    val pick = runCatching { repository.randomOngoingPick(contentId) }.getOrNull()
                    Timber.d("[PlayerVM] autoSwitch pick=%s", pick?.id ?: "none")
                    pick?.let { _switchTitle.value = it.id }
                }
            }
        }
    }

    private companion object {
        /**
         * Сколько ждать перед загрузкой комментариев. Две страницы по 25 штук — это
         * два запроса, и выпущенные одновременно с резолвом ссылки они отбирают у
         * потока полосу ровно в тот момент, когда он набирает буфер. Первая реплика
         * всплывает не раньше четвёртой секунды даже на «Часто», так что запас есть.
         */
        const val COMMENTS_DELAY_MS = 3_000L

        /** Страниц комментариев. 25 в странице; полусотни хватает на весь сезон. */
        const val COMMENT_PAGES = 2
    }

    /** Persists frame-level position every few seconds for precise resume. */
    private fun startProgressTicker() {
        viewModelScope.launch {
            while (isActive) {
                delay(4_000)
                controller.syncPosition()
                persistProgress()
            }
        }
    }

    private suspend fun persistProgress(ended: Boolean = false) {
        val segment = currentSegment ?: return
        val pos = controller.currentPositionMs()
        val dur = controller.durationMs()
        if (pos > 0 && dur > 0) {
            val measured = samplePlaybackEvidence(segment.id, pos)
            repository.saveProgress(contentId, segment.id, pos, dur, measured, ended)
        }
    }

    private fun resetPlaybackEvidence(positionMs: Long) {
        evidenceSegmentId = currentSegment?.id
        evidencePositionMs = positionMs.coerceAtLeast(0L)
        evidenceAtMs = SystemClock.elapsedRealtime()
        evidenceWasPlaying = controller.isPlaying()
    }

    /**
     * Возвращает реальное время непрерывного воспроизведения с прошлого замера.
     * Неправдоподобный скачок позиции (seek/смена потока) не становится просмотром.
     */
    private fun samplePlaybackEvidence(segmentId: String, positionMs: Long): Long {
        val now = SystemClock.elapsedRealtime()
        if (evidenceSegmentId != segmentId || evidenceAtMs <= 0L) {
            evidenceSegmentId = segmentId
            evidencePositionMs = positionMs
            evidenceAtMs = now
            evidenceWasPlaying = controller.isPlaying()
            return 0L
        }
        val elapsed = (now - evidenceAtMs).coerceIn(0L, 60_000L)
        val advanced = positionMs - evidencePositionMs
        val measured = if (evidenceWasPlaying && advanced > 0L && advanced <= elapsed * 4L + 2_000L) {
            elapsed
        } else {
            0L
        }
        evidencePositionMs = positionMs
        evidenceAtMs = now
        evidenceWasPlaying = controller.isPlaying()
        return measured
    }

    override fun onCleared() {
        // The 4s ticker is the source of truth for resume position; here we just
        // detach our callback and pause so audio doesn't leak past the screen.
        //
        // For cinema torrents: PAUSE instead of stop so the download continues
        // in the background. The torrent is paused and can be resumed from the
        // Downloads screen or when the user re-opens the movie page.
        if (isCinema) {
            controller.pauseTorrent()
        }
        controller.endSession(playbackSessionId)
        // Держатель сессии комментариев живёт на весь процесс — иначе пересбор экрана
        // сбрасывал бы «что уже показано». Плеер закрыт по-настоящему: следующий тайтл
        // обязан начать с чистого листа, а не донашивать чужую очередь.
        com.aniblaze.featureplayer.danmaku.DanmakuSessions.forget()
        super.onCleared()
    }
}
