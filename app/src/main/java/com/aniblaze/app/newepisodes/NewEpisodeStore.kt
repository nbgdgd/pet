package com.aniblaze.app.newepisodes

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Пометки «у этого тайтла вышла новая серия» для экрана «Новые серии».
 *
 * Признак поднимается по РОСТУ ЧИСЛА СЕРИЙ У ИСТОЧНИКА, а не по дню эфира: русская
 * озвучка приезжает примерно через сутки после японского показа, и оповещение по
 * дате эфира срабатывает, когда смотреть ещё нечего. Серия, появившаяся в списке
 * источника, уже играбельна.
 *
 * Базовый счётчик хранится отдельно от пометки и только РАСТЁТ. Ответ пришедший от
 * чужого источника (меньше настоящего) иначе уронил бы базу, и следующее корректное
 * чтение выглядело бы новой серией — то есть пометка загоралась бы на каждом обходе.
 */
@Singleton
class NewEpisodeStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _flagged = MutableStateFlow(readFlagged())

    /** id тайтла → номер серии, из-за которой он помечен. */
    val flagged: StateFlow<Map<String, Int>> = _flagged.asStateFlow()

    /**
     * Записать текущее число серий тайтла.
     *
     * [lastWatched] — максимальный номер серии, которую пользователь уже начинал
     * смотреть (0 = ни одной). Нужен только для ПЕРВОЙ встречи с тайтлом: базы для
     * сравнения ещё нет, и без этого экран молчал бы до следующего релиза, хотя
     * серии сверх досмотренной новы именно для этого пользователя.
     */
    @Synchronized
    fun record(contentId: String, count: Int, lastWatched: Int) {
        if (count <= 0) return
        val baselineKey = BASELINE_PREFIX + contentId
        val hadBaseline = prefs.contains(baselineKey)
        val previous = prefs.getInt(baselineKey, 0)

        val isNew = when {
            hadBaseline -> count > previous
            // Первый обход этого тайтла: сравниваем с тем, что реально досмотрено.
            else -> lastWatched > 0 && count > lastWatched
        }

        val editor = prefs.edit()
        if (count > previous) editor.putInt(baselineKey, count)
        if (isNew) editor.putInt(FLAG_PREFIX + contentId, count)
        editor.apply()

        if (isNew) _flagged.value = _flagged.value + (contentId to count)
    }

    /** Тайтл открыли (или отметили прочитанным) — пометка больше не нужна. */
    @Synchronized
    fun clear(contentId: String) {
        if (!prefs.contains(FLAG_PREFIX + contentId)) return
        prefs.edit().remove(FLAG_PREFIX + contentId).apply()
        _flagged.value = _flagged.value - contentId
    }

    @Synchronized
    fun clearAll() {
        val ids = _flagged.value.keys
        if (ids.isEmpty()) return
        val editor = prefs.edit()
        ids.forEach { editor.remove(FLAG_PREFIX + it) }
        editor.apply()
        _flagged.value = emptyMap()
    }

    /** Когда последний раз обходили отслеживаемые тайтлы (epoch ms, 0 = никогда). */
    fun lastSweepAt(): Long = prefs.getLong(KEY_LAST_SWEEP, 0L)

    fun markSwept() {
        prefs.edit().putLong(KEY_LAST_SWEEP, System.currentTimeMillis()).apply()
    }

    private fun readFlagged(): Map<String, Int> =
        prefs.all
            .asSequence()
            .filter { it.key.startsWith(FLAG_PREFIX) }
            .mapNotNull { entry ->
                val count = entry.value as? Int ?: return@mapNotNull null
                entry.key.removePrefix(FLAG_PREFIX) to count
            }
            .toMap()

    private companion object {
        // Отдельный файл, а не "episode_counts" от AggregatorSyncWorker: тот при
        // отправке уведомления сразу двигает свою базу вперёд, так что «непрочитанное»
        // по нему не восстановить.
        const val PREFS = "new_episodes"
        const val BASELINE_PREFIX = "c:"
        const val FLAG_PREFIX = "f:"
        const val KEY_LAST_SWEEP = "last_sweep_at"
    }
}
