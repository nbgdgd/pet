package com.aniblaze.app.newepisodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.repository.AnimeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/** Карточка «новой серии»: подпись серии уже готова строкой, чтобы список не
 *  форматировал её на каждом кадре. */
data class NewEpisodeItem(
    val id: String,
    val title: String,
    val poster: String,
    /** «Серия 12» — номер, из-за которого тайтл помечен. */
    val episodeLabel: String,
)

/**
 * Тайтлы, за которыми пользователь следит и у которых прибавилась серия.
 *
 * На ПК пометки расставляет фоновый уведомитель, а экран их только показывает. На
 * Android фоновый AggregatorSyncWorker считает серии в свои настройки и двигает базу
 * сразу при отправке уведомления, поэтому «непрочитанное» по ним не восстановить —
 * обход здесь свой, со своим хранилищем ([NewEpisodeStore]).
 */
@HiltViewModel
class NewEpisodesViewModel @Inject constructor(
    private val repository: AnimeRepository,
    private val store: NewEpisodeStore,
) : ViewModel() {

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * Пересечение помеченных id с тем, что пользователь реально отслеживает.
     * Пометка без карточки бесполезна: тайтл могли убрать из избранного, а имя и
     * постер живут только в избранном/истории.
     */
    val items: StateFlow<List<NewEpisodeItem>> = combine(
        repository.favorites(),
        repository.history(HISTORY_TRACKED),
        store.flagged,
    ) { favorites, history, flagged ->
        trackedTitles(favorites, history)
            .mapNotNull { anime ->
                val episode = flagged[anime.id] ?: return@mapNotNull null
                NewEpisodeItem(
                    id = anime.id,
                    title = anime.title,
                    poster = anime.poster,
                    episodeLabel = "Серия $episode",
                )
            }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { sweep(force = false) }
    }

    /** Ручное обновление — считает серии, даже если обход был только что. */
    fun refresh() {
        viewModelScope.launch { sweep(force = true) }
    }

    /** Открыли тайтл — считаем, что о серии уже знают. */
    fun markRead(contentId: String) = store.clear(contentId)

    fun markAllRead() = store.clearAll()

    /**
     * Обход отслеживаемых тайтлов: число серий у источника против сохранённой базы.
     *
     * Обход платный — по сетевому запросу на тайтл, — поэтому сам собой запускается
     * не чаще, чем раз в [SWEEP_INTERVAL_MS] (тот же период, что у уведомителя на ПК).
     */
    private suspend fun sweep(force: Boolean) {
        if (_refreshing.value) return
        if (!force && System.currentTimeMillis() - store.lastSweepAt() < SWEEP_INTERVAL_MS) return

        val tracked = attempt {
            trackedTitles(
                repository.favorites().first(),
                repository.history(HISTORY_TRACKED).first(),
            )
        }.orEmpty()
        if (tracked.isEmpty()) return

        _refreshing.value = true
        try {
            withContext(Dispatchers.IO) {
                // Четыре одновременных проверки: столько же держит экран избранного
                // на ПК — быстрее заметно грузит источники, а они на это отвечают
                // отказами и обход становится бесполезным.
                val gate = Semaphore(SWEEP_PARALLELISM)
                coroutineScope {
                    tracked.map { anime -> async { gate.withPermit { check(anime) } } }.awaitAll()
                }
            }
            store.markSwept()
        } finally {
            _refreshing.value = false
        }
    }

    /** Одна упавшая проверка не должна обрывать весь обход: сеть моргает, а
     *  остальные отслеживаемые тайтлы ни при чём. */
    private suspend fun check(anime: Anime) {
        val segments = attempt { repository.segments(anime.id) }.orEmpty()
        if (segments.isEmpty()) return
        val watched = attempt { repository.watchedSegments(anime.id) }.orEmpty()
        val lastWatched = segments.filter { it.id in watched }.maxOfOrNull { it.number } ?: 0
        store.record(anime.id, segments.size, lastWatched)
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "[NewEpisodes] check failed")
            null
        }

    private companion object {
        /** Сколько записей истории считать «отслеживаемыми» — как на ПК. */
        const val HISTORY_TRACKED = 40
        const val SWEEP_PARALLELISM = 4
        const val SWEEP_INTERVAL_MS = 30 * 60 * 1000L // раз в 30 минут
    }
}

/** Избранное плюс недавняя история, без повторов: за этим пользователь следит. */
private fun trackedTitles(favorites: List<Anime>, history: List<Anime>): List<Anime> =
    (favorites + history).distinctBy { it.id }
