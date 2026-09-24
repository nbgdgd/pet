package com.aniblaze.history

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
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Пометки прогресса для сетки постеров: какие тайтлы гасить (недосмотренные)
 * и на какие ставить галку (досмотренные до последней известной серии).
 *
 * Дублирует одноимённый тип из feature-favorites: модули не видят друг друга,
 * а тащить экранную мелочь в core-* ради семи строк смысла нет.
 */
data class WatchMarks(
    val unfinished: Set<String> = emptySet(),
    val completed: Set<String> = emptySet(),
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: AnimeRepository,
    store: SettingsDataStore,
    private val segmentDao: SegmentDao,
    private val watchProgressDao: WatchProgressDao,
) : ViewModel() {

    val history: StateFlow<List<Anime>> = repository.history()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Тот же признак, что и в избранном, и считается так же — иначе один и тот
     * же тайтл выглядел бы на двух экранах по-разному.
     *
     * Число серий берём из локального кэша Room — из того, что уже сложилось,
     * когда тайтл открывали (на ПК за это отвечал episodeCounts). Сеть ради
     * оформления списка не трогаем: это десятки запросов на один экран.
     * Тайтл без серий в кэше остаётся без пометки.
     */
    val watchMarks: StateFlow<WatchMarks> = history
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

    fun clear() {
        viewModelScope.launch { repository.clearHistory() }
    }
}
