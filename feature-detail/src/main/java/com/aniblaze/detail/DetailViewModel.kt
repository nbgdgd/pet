package com.aniblaze.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniblaze.aggregator.CinemaTorrentFallback
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.repository.AnimeRepository
import com.aniblaze.aggregator.repository.ResumePoint
import com.aniblaze.aggregator.repository.TitleWatchState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.URLDecoder
import javax.inject.Inject

data class DetailState(
    val isLoading: Boolean = true,
    /** Only the playback/episode block is still loading; title metadata is already visible. */
    val segmentsLoading: Boolean = true,
    val anime: Anime? = null,
    val segments: List<Segment> = emptyList(),
    val seasons: List<Anime> = emptyList(),
    val similar: List<Anime> = emptyList(),
    val watched: Set<String> = emptySet(),
    val resume: ResumePoint? = null,
    /** Прогресс каждого сезона франшизы; открытую карточку за просмотр не выдаёт. */
    val seasonProgress: Map<String, TitleWatchState> = emptyMap(),
    val isFavorite: Boolean = false,
    val error: String? = null,
    /** Air dates + release status + next-episode countdown; fills in after the list. */
    val schedule: com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule =
        com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule.EMPTY,
    /** Полное описание с релиза (каталожное — обрезок); null пока не догрузилось. */
    val fullDescription: String? = null,
    /** Комментарии сообщества, топовые первыми, докачиваются постранично. */
    val comments: List<com.aniblaze.aggregator.model.TitleComment> = emptyList(),
    val commentsBusy: Boolean = false,
    val commentsEnded: Boolean = false,
    /** Раскрытые ветки: id комментария → его ответы. */
    val openReplies: Map<Long, List<com.aniblaze.aggregator.model.TitleComment>> = emptyMap(),
)

sealed interface DetailIntent {
    data object ToggleFavorite : DetailIntent
    data class SelectSeason(val contentId: String) : DetailIntent
    data object LoadMoreComments : DetailIntent
    data class ToggleReplies(val commentId: Long) : DetailIntent
    data object Retry : DetailIntent
}

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: AnimeRepository,
    private val torrent: CinemaTorrentFallback,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Active title; changes when the user picks another season. */
    private var activeContentId: String =
        URLDecoder.decode(savedStateHandle.get<String>("contentId").orEmpty(), "UTF-8")

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    private var favoriteJob: Job? = null
    private var loadJob: Job? = null

    init {
        load()
    }

    /**
     * The page is closed (popped from the back stack — navigating to the
     * player keeps this ViewModel alive, so playback is never paused here).
     * A torrent of THIS title goes to pause; its 3-day auto-delete countdown
     * starts. Other titles are untouched.
     */
    override fun onCleared() {
        super.onCleared()
        val base = activeContentId.substringBefore(":t")
        if (base.startsWith("tmdb") || base.startsWith("http")) {
            val live = torrent.status.value
            if (live.contentId.substringBefore(":t") == base) {
                Timber.d("[Detail] page closed, pausing torrent for %s", base)
                torrent.pause()
            }
        }
    }

    fun onIntent(intent: DetailIntent) {
        when (intent) {
            DetailIntent.ToggleFavorite -> toggleFavorite()
            is DetailIntent.SelectSeason -> selectSeason(intent.contentId)
            DetailIntent.LoadMoreComments -> loadMoreComments()
            is DetailIntent.ToggleReplies -> toggleReplies(intent.commentId)
            DetailIntent.Retry -> load()
        }
    }

    private var commentsPage = 0

    private fun loadMoreComments() {
        val s = _state.value
        if (s.commentsBusy || s.commentsEnded) return
        val id = activeContentId
        _state.value = s.copy(commentsBusy = true)
        viewModelScope.launch {
            val fresh = runCatching { repository.titleComments(id, commentsPage) }.getOrDefault(emptyList())
            if (id != activeContentId) return@launch
            _state.value = if (fresh.isEmpty()) {
                _state.value.copy(commentsBusy = false, commentsEnded = true)
            } else {
                commentsPage += 1
                _state.value.copy(
                    commentsBusy = false,
                    comments = (_state.value.comments + fresh).distinctBy { it.id },
                )
            }
        }
    }

    private fun toggleReplies(commentId: Long) {
        val open = _state.value.openReplies
        if (open.containsKey(commentId)) {
            _state.value = _state.value.copy(openReplies = open - commentId)
            return
        }
        viewModelScope.launch {
            val thread = runCatching { repository.commentReplies(commentId) }.getOrDefault(emptyList())
            if (thread.isNotEmpty()) {
                _state.value = _state.value.copy(openReplies = _state.value.openReplies + (commentId to thread))
            }
        }
    }

    private fun selectSeason(contentId: String) {
        if (contentId == activeContentId) return
        activeContentId = contentId
        load()
    }

    private fun observeFavorite(contentId: String) {
        favoriteJob?.cancel()
        favoriteJob = repository.isFavorite(contentId)
            .onEach { fav -> _state.value = _state.value.copy(isFavorite = fav) }
            .launchIn(viewModelScope)
    }

    private fun load() {
        val id = activeContentId
        loadJob?.cancel()
        commentsPage = 0
        _state.value = DetailState(
            isLoading = true,
            segmentsLoading = true,
            error = null,
            fullDescription = null, comments = emptyList(),
            commentsBusy = false, commentsEnded = false, openReplies = emptyMap(),
        )
        observeFavorite(id)
        loadJob = viewModelScope.launch {
            val anime = repository.detail(id)
                ?: Anime(id = id, title = id.substringAfterLast('/'), poster = "")
            if (id != activeContentId) return@launch

            // A poster tap always points at an item already cached by its catalog. Show
            // that metadata immediately; network-bound episodes/seasons must not keep the
            // whole screen behind a skeleton for several seconds.
            _state.value = _state.value.copy(
                isLoading = false,
                anime = anime,
            )

            // Movie identity + Lampa's JS plugin used to be fetched only after the
            // Play tap. Warm them now, in parallel with optional detail sections.
            // A paused torrent of THIS title gets priority and resumes.
            if (id.startsWith("tmdb")) {
                launch {
                    runCatching { repository.prepareCinemaPlayback(id) }
                        .onFailure { Timber.w(it, "cinema playback warm-up failed") }
                }
                launch {
                    runCatching { torrent.prioritize(id) }
                        .onFailure { Timber.w(it, "torrent prioritize failed") }
                }
            }

            coroutineScope {
                // Playback data and local progress can be requested together. Publish it
                // as soon as it is ready; franchise seasons and comments are optional.
                launch {
                    val segmentsDeferred = async {
                        runCatching { repository.segments(id) }
                            .onFailure { Timber.e(it, "segments failed") }
                            .getOrDefault(emptyList())
                    }
                    val watchedDeferred = async {
                        runCatching { repository.watchedSegments(id) }.getOrDefault(emptySet())
                    }
                    val resumeDeferred = async { runCatching { repository.resumePoint(id) }.getOrNull() }
                    val segments = segmentsDeferred.await()
                    val watched = watchedDeferred.await()
                    val resume = resumeDeferred.await()
                    if (id != activeContentId) return@launch
                    _state.value = _state.value.copy(
                        segmentsLoading = false,
                        segments = segments,
                        watched = watched,
                        resume = resume,
                        error = if (segments.isEmpty()) "Серии не найдены для этого источника." else null,
                    )

                    // Air dates trail in after the actual episode list.
                    if (!id.startsWith("tmdb") && !id.startsWith("http") && segments.isNotEmpty()) {
                        val altTitle = if (id.startsWith("ax:")) anime.status else ""
                        val schedule = runCatching { repository.titleSchedule(anime.title, altTitle) }
                            .getOrDefault(com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule.EMPTY)
                        if (id == activeContentId) _state.value = _state.value.copy(schedule = schedule)
                    }
                }

                launch {
                    val seasons = runCatching { repository.seasons(id) }.getOrDefault(emptyList())
                    val seasonProgress = (seasons + anime).distinctBy { it.id }.associate { season ->
                        season.id to runCatching { repository.titleWatchState(season.id) }
                            .getOrDefault(TitleWatchState())
                    }
                    if (id == activeContentId) {
                        _state.value = _state.value.copy(seasons = seasons, seasonProgress = seasonProgress)
                    }
                }

                launch {
                    val similar = runCatching { repository.similarTitles(id) }.getOrDefault(emptyList())
                    if (id == activeContentId) _state.value = _state.value.copy(similar = similar)
                }

                launch {
                    val full = runCatching { repository.fullDescription(id) }.getOrNull()
                    if (id == activeContentId && full != null && full.length > anime.description.length) {
                        _state.value = _state.value.copy(fullDescription = full)
                    }
                }

                launch {
                    if (id == activeContentId) loadMoreComments()
                }
            }
        }
    }

    private fun toggleFavorite() {
        val anime = _state.value.anime ?: return
        viewModelScope.launch { repository.toggleFavorite(anime) }
    }
}
