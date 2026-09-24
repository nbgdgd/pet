package com.aniblaze.app.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniblaze.database.dao.ContentDao
import com.aniblaze.database.dao.SegmentDao
import com.aniblaze.database.dao.WatchProgressDao
import com.aniblaze.database.entity.ContentEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val progressDao: WatchProgressDao,
    private val contentDao: ContentDao,
    private val segmentDao: SegmentDao,
) : ViewModel() {

    /**
     * null — ещё считаем; иначе готовые цифры.
     *
     * Пересчёт «по отпечатку», а не на каждое изменение таблицы. Плеер пишет
     * позицию раз в 4 секунды (PlayerViewModel.startProgressTicker), Room на
     * каждую запись перевыдаёт весь список — без фильтра экран пересобирал бы
     * цифры и заново отыгрывал все анимации набора и роста каждые четыре секунды.
     * В отпечатке только то, что реально видно: число строк, число тайтлов и
     * время, огрублённое до десяти минут.
     *
     * Источник — allProgress, а НЕ continueWatching: у второго в запросе стоит
     * `position < duration`, то есть он отдаёт только недосмотренное. На нём
     * статистика считала бы всё наоборот — досмотренные серии в неё не попадали.
     */
    internal val stats: StateFlow<Stats?> = progressDao.allProgress(PROGRESS_LIMIT)
        .map { rows ->
            rows.map {
                WatchRow(
                    it.contentId,
                    it.segmentId,
                    it.position,
                    it.duration,
                    it.updatedAt,
                    it.verifiedPlaybackMs,
                    it.completed,
                )
            }
        }
        .distinctUntilChangedBy { rows ->
            listOf(
                rows.size,
                rows.distinctBy { it.contentId }.size,
                rows.count { it.completed },
                rows.sumOf { it.verifiedPlaybackMs } / 600_000L,
            ).joinToString("|")
        }
        .map { rows -> collect(rows) }
        // Считаем и ходим в базу вне главного потока: в композиции не должно
        // остаться ничего тяжелее отрисовки готовых чисел.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun collect(rows: List<WatchRow>): Stats {
        if (rows.isEmpty()) return Stats.EMPTY
        val ids = rows.map { it.contentId }.distinct()
        val titles = ids.mapNotNull { id -> contentDao.getById(id)?.let { id to it.toTitleInfo() } }.toMap()
        // Сколько серий у тайтла ВСЕГО — только для тех, чей список серий уже
        // выкачан и лежит в базе. Для остальных «досмотрено до конца» не считается
        // вовсе: лучше не показать цифру, чем показать неверную.
        val episodeCounts = ids.associateWith { segmentDao.forContent(it).size }
            .filterValues { it > 0 }
        return buildStats(rows, titles, episodeCounts)
    }

    private companion object {
        /**
         * Room-запрос требует LIMIT. 5000 строк — это порядка двухсот тайтлов по
         * 25 серий: потолок с большим запасом, а на экране всё равно видны только
         * верхние двенадцать.
         */
        const val PROGRESS_LIMIT = 5_000
    }
}

/**
 * Оценок MyAnimeList в таблице `content` нет — ни средней, ни распределения
 * голосов. Поле `rating` сюда НЕ подставляется: у Anixart это пятибалльная шкала,
 * у Shikimori/TMDB — десятибалльная, и одной цифрой они не складываются
 * (подробности в [TrashAnime]).
 */
private fun ContentEntity.toTitleInfo() = TitleInfo(
    id = id,
    title = title,
    poster = poster,
    genres = genres,
    studio = studio,
    year = year,
)
