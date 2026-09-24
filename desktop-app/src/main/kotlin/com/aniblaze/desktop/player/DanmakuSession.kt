package com.aniblaze.desktop.player

import com.aniblaze.aggregator.model.TitleComment
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Состояние показа комментариев ОДНОЙ серии — вне Compose.
 *
 * Это ответ на главный дефект прежней реализации: колода, счётчики и «что уже
 * показано» жили внутри композиции (`remember`) с летучими ключами. Любая мелочь —
 * пересбор экрана (в журнале `player.comments loaded=75` за вечер приходил трижды),
 * подрагивание длины HLS, пауза — создавала колоду заново или перезапускала цикл с
 * начальной паузой. Отсюда и качели: сброс «показанного» давал дубликаты, а вечный
 * перезапуск паузы — пустой экран.
 *
 * Здесь всё наоборот. Объект создаётся ОДИН раз на пару «тайтл + серия»
 * ([episodeKey]) и живёт, пока её не сменили:
 *
 *  • очередь: нормализована, дедуплицирована, перетасована один раз ([queue]);
 *  • показанное — просто индекс [next]: показанные никогда не возвращаются;
 *  • часы — НАКОПЛЕННОЕ ВРЕМЯ ПРОСМОТРА [watchedMs], которое двигает только
 *    внешний тик при играющем видео. Пауза не двигает часы; перемотка и буферизация
 *    на них не влияют вовсе — позиция и длительность VLC сюда не входят;
 *  • длительность серии — только ПОДСКАЗКА для расчёта шага ([gapMs]): её дрожание
 *    меняет будущие интервалы, но не может ничего перезапустить или сбросить.
 *
 * Реплика считается показанной по протоколу «выбрали → отдали на экран →
 * подтвердили»: [peekDue] ничего не тратит, индекс двигает только [commitShown].
 * Отменившийся между ними цикл не сжигает реплику.
 */
internal class DanmakuSession(
    val episodeKey: String,
    /** Сколько сырых комментариев легло в основу — по нему [DanmakuSessions] узнаёт дозагрузку. */
    val sourceSize: Int,
    val freshFirst: Boolean,
    private val rate: DanmakuRate,
    texts: List<String>,
    seed: Long,
) {
    val id: Long = IDS.incrementAndGet()
    val rateKey: String get() = rate.key

    private val random = java.util.Random(seed)
    private val queue: List<String> = texts.shuffled(java.util.Random(seed))

    /** Всё до этого индекса УЖЕ показано и не вернётся никогда. */
    private var next = 0

    /** Накопленное время просмотра, мс. Двигается только [accumulate]. */
    private var watchedMs = 0L

    /** Когда пора показать следующую реплику (в часах [watchedMs]). */
    private var dueAtMs = randomBetween(rate.firstMinMs, rate.firstMaxMs)

    private val activeSchedulers = AtomicInteger(0)

    val usable: Int get() = queue.size
    val shownCount: Int get() = next

    fun hasMore(): Boolean = next < queue.size

    /** Тик просмотра: зовётся только пока видео играет. Пауза — просто не зовут. */
    fun accumulate(deltaMs: Long) {
        watchedMs += deltaMs
    }

    /**
     * Реплика, которую ПОРА показать, — или null. НИЧЕГО не тратит: сколько ни
     * зови, вернёт одну и ту же, пока её не подтвердят [commitShown].
     */
    fun peekDue(laneFree: Boolean): String? {
        if (!laneFree || next >= queue.size || watchedMs < dueAtMs) return null
        return queue[next]
    }

    /**
     * Реплика реально ушла на экран: двигаем индекс и назначаем следующий срок.
     *
     * [durationHintMs] — приблизительная длина серии, только для шага. Запас
     * растягивается на серию (маленький — реже, большой — чаще), но шаг всегда
     * зажат в границы настройки, а случайный множитель ±25% убирает механическую
     * равномерность.
     */
    fun commitShown(durationHintMs: Long) {
        if (next >= queue.size) return
        next++
        dueAtMs = watchedMs + gapMs(durationHintMs)
    }

    private fun gapMs(durationHintMs: Long): Long {
        val base = if (durationHintMs > 0) {
            ((durationHintMs * rate.spreadShare).toLong() / (usable + 1))
                .coerceIn(rate.minGapMs, rate.maxGapMs)
        } else {
            (rate.minGapMs + rate.maxGapMs) / 2
        }
        val jitter = 0.75 + random.nextDouble() * 0.5
        return (base * jitter).toLong()
    }

    private fun randomBetween(from: Long, to: Long): Long =
        from + (random.nextDouble() * (to - from)).toLong()

    /** Счётчик живых циклов показа — для гарантии «ровно один» и её проверки логом. */
    fun schedulerEnter(): Int = activeSchedulers.incrementAndGet()
    fun schedulerExit(): Int = activeSchedulers.decrementAndGet()

    private companion object {
        val IDS = AtomicLong(0L)
    }
}

/**
 * Держатель текущей сессии. Один на процесс: плеер в приложении один.
 *
 * [obtain] возвращает ТУ ЖЕ сессию, пока не изменилось что-то из настоящих причин
 * пересоздать её: другая серия, дозагрузившиеся комментарии (пусто → пришли),
 * смена настроек показа. Пересбор экрана, новая identity того же списка,
 * длительность, качество, озвучка, состояние VLC сессию НЕ трогают.
 */
internal object DanmakuSessions {

    private var current: DanmakuSession? = null

    @Synchronized
    fun obtain(
        episodeKey: String,
        comments: List<TitleComment>,
        episode: Int,
        freshFirst: Boolean,
        rate: DanmakuRate,
        seed: Long = System.nanoTime(),
    ): DanmakuSession {
        val cached = current
        if (cached != null &&
            cached.episodeKey == episodeKey &&
            cached.sourceSize == comments.size &&
            cached.freshFirst == freshFirst &&
            cached.rateKey == rate.key
        ) {
            return cached
        }
        // Будущие серии отсекаются до этого места. Обычные текущие/беспометочные
        // реакции показываются как есть, а метки источника и явные сюжетные раскрытия
        // превращаются в нейтральное уведомление. Одинаковые строки схлопываются.
        val pool = danmakuPicks(comments, episode, freshFirst)
            .map { danmakuPreviewText(it, episode) }
            .distinct()
        val unique = comments.asSequence().map { danmakuKey(it.message) }.filter { it.isNotBlank() }.distinct().count()
        val session = DanmakuSession(episodeKey, comments.size, freshFirst, rate, pool, seed)
        // Диагностика воронки: сразу видно, ГДЕ пропали реплики — их не прислали,
        // они одинаковые или их съел отбор.
        PlayerDiagnostics.log(
            "danmaku.fetch",
            "episode=$episodeKey; total=${comments.size}; unique=$unique; usable=${pool.size}; session=${session.id}",
        )
        current = session
        return session
    }
}
