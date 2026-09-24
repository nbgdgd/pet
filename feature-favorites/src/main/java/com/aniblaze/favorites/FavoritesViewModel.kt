package com.aniblaze.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.repository.AnimeRepository
import com.aniblaze.database.dao.SegmentDao
import com.aniblaze.database.dao.WatchProgressDao
import com.aniblaze.database.settings.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Пометки прогресса для сетки постеров: какие тайтлы гасить (недосмотренные)
 * и на какие ставить галку (досмотренные до последней известной серии).
 */
data class WatchMarks(
    val unfinished: Set<String> = emptySet(),
    val completed: Set<String> = emptySet(),
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    repository: AnimeRepository,
    store: SettingsDataStore,
    private val segmentDao: SegmentDao,
    private val watchProgressDao: WatchProgressDao,
) : ViewModel() {

    val favorites: StateFlow<List<Anime>> = repository.favorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Гасим карточки, у которых последняя ИЗВЕСТНАЯ серия ещё не досмотрена,
     * и ставим галку тем, у кого досмотрена. Оба признака считаются из ОДНОГО
     * источника, поэтому «серая карточка» и «галка» никогда не противоречат
     * друг другу.
     *
     * Число серий берём из локального кэша Room — из того, что уже сложилось,
     * когда тайтл открывали (на ПК ровно за это отвечал episodeCounts). Лезть
     * за ним в сеть ради оформления списка нельзя: это десятки запросов на один
     * экран. Тайтл, который ни разу не открывали, серий в кэше не имеет и
     * остаётся вообще без пометки — лучше никакой, чем выдуманная.
     */
    val watchMarks: StateFlow<WatchMarks> = favorites
        .map { list -> watchMarksFor(list.map { it.id }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchMarks())

    private suspend fun watchMarksFor(contentIds: List<String>): WatchMarks {
        val unfinished = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        for (id in contentIds) {
            // forContent отдаёт серии уже отсортированными по номеру, так что
            // последняя известная серия — просто последний элемент.
            val lastKnown = segmentDao.forContent(id).lastOrNull() ?: continue
            val watched = watchProgressDao.watchedSegments(id)
            if (lastKnown.id in watched) completed += id else unfinished += id
        }
        return WatchMarks(unfinished, completed)
    }

    /** User-chosen grid columns (0 = Auto). */
    val gridColumns: StateFlow<Int> = store.settings.map { it.gridColumns }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
