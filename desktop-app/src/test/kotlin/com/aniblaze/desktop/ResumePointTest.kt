package com.aniblaze.desktop

import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

/**
 * Точка продолжения тайтла: где человек НА САМОМ ДЕЛЕ остановился.
 *
 * Повод — жалоба с картинкой: 23-я серия брошена на 10 %, 25-я и 28-я просмотрены, а
 * «Продолжить» упорно звало обратно на 23-ю. Первопричина не в отображении: отметки о
 * просмотре хранились МНОЖЕСТВОМ СТРОК БЕЗ ВРЕМЕНИ, поэтому вопрос «что человек делал
 * позже» был неразрешим в принципе, и брошенная серия побеждала всегда — сколько бы
 * серий ни было досмотрено после неё.
 *
 * Здесь сторожатся обе половины лечения: время у отметки ([PersistedState.watchedAt]) и
 * правило сравнения ([resumeOutdated]).
 */
class ResumePointTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val duration = 1_420_000L

    private fun card(id: String) = PersistedAnime(id = id, title = id, poster = "")

    private fun progress(id: String, segment: Int, fraction: Float, at: Long) = ProgressEntry(
        anime = card(id),
        segment = segment,
        positionMs = (duration * fraction).toLong(),
        durationMs = duration,
        updatedAt = at,
    )

    /** Состояние «сериал на 28 серий», уже прошедшее простановку времени. */
    private fun state(
        progress: List<ProgressEntry> = emptyList(),
        watched: Set<String> = emptySet(),
        watchedAt: Map<String, Long> = emptyMap(),
        prefs: Map<String, PlayerPref> = emptyMap(),
    ) = PersistedState(
        progress = progress,
        watched = watched,
        watchedAt = watchedAt,
        watchedAtBackfilled = true,
        playerPrefs = prefs,
        episodeCounts = mapOf("a" to 28),
    )

    private val episodes = (1..28).toList()

    private fun resume(state: PersistedState) =
        resumeSegmentOf(state, contentId = "a", prefKey = "a", segments = episodes, playable = { true })

    // ---- сценарий из жалобы --------------------------------------------------------

    @Test
    fun `после просмотра 25 и 28 серий старая 23-я перестаёт быть точкой продолжения`() {
        val t0 = 1_700_000_000_000L
        val s = state(
            progress = listOf(progress("a", segment = 23, fraction = 0.10f, at = t0)),
            watched = setOf("a#25", "a#28"),
            watchedAt = mapOf("a#25" to t0 + hour, "a#28" to t0 + 2 * hour),
        )
        assertNotEquals("брошенная 23-я устарела: после неё досмотрены 25-я и 28-я", 23, resume(s))
        // За 28-й смотреть нечего, поэтому берётся РУБЕЖ — самая дальняя просмотренная,
        // за которой ещё есть непросмотренное. Это 25-я, значит предлагается 26-я.
        assertEquals(26, resume(s))
    }

    @Test
    fun `метка на карточке тоже перестаёт звать на устаревшую серию`() {
        val t0 = 1_700_000_000_000L
        val index = buildWatchIndex(
            state(
                progress = listOf(progress("a", segment = 23, fraction = 0.10f, at = t0)),
                watched = setOf("a#25", "a#28"),
                watchedAt = mapOf("a#25" to t0 + hour, "a#28" to t0 + 2 * hour),
            ),
        )
        val watch = index["a"]!!
        assertNotEquals("плашка не должна показывать «Остановились: 23 серия»", 23, watch.episode)
        assertEquals(28, watch.episode)
    }

    @Test
    fun `свежий недосмотр НЕ отменяется старыми отметками`() {
        // Оборотная сторона: 25-ю и 28-ю досмотрели вчера, а 23-ю бросили сегодня —
        // возвращаться надо именно на 23-ю.
        val t0 = 1_700_000_000_000L
        val s = state(
            progress = listOf(progress("a", segment = 23, fraction = 0.10f, at = t0 + 5 * hour)),
            watched = setOf("a#25", "a#28"),
            watchedAt = mapOf("a#25" to t0, "a#28" to t0 + hour),
        )
        assertEquals(23, resume(s))
    }

    // ---- запоздалое автосохранение --------------------------------------------------

    @Test
    fun `хвост автосохранения не воскрешает досмотренную серию`() {
        // Плеер пишет позицию каждые две секунды; последняя запись законно приходит уже
        // после того, как серию засчитали. Раньше она заново создавала точку
        // возобновления, и «Продолжить» откатывалось назад.
        assertFalse(keepResumePoint(positionMs = (duration * 0.93f).toLong(), durationMs = duration, alreadyWatched = true))
        assertFalse(keepResumePoint(positionMs = (duration * 0.85f).toLong(), durationMs = duration, alreadyWatched = true))
    }

    @Test
    fun `настоящий пересмотр досмотренной серии точку возобновления оставляет`() {
        // Пересмотр начинается с начала и растёт — до 80 % запись проходит как обычно.
        assertTrue(keepResumePoint(positionMs = (duration * 0.30f).toLong(), durationMs = duration, alreadyWatched = true))
    }

    @Test
    fun `досмотренная серия точки возобновления не имеет`() {
        // Порог был 95 %, и в зазоре 90–94 % серия одновременно значилась просмотренной
        // и брошенной. Отсюда «Остановились: N серия» на досмотренной серии.
        assertFalse(keepResumePoint(positionMs = (duration * 0.92f).toLong(), durationMs = duration, alreadyWatched = false))
        assertTrue(keepResumePoint(positionMs = (duration * 0.50f).toLong(), durationMs = duration, alreadyWatched = false))
    }

    @Test
    fun `случайное открытие следа не оставляет`() {
        assertFalse(keepResumePoint(positionMs = 5_000, durationMs = duration, alreadyWatched = false))
    }

    @Test
    fun `при неизвестной длительности судим только по позиции`() {
        assertTrue(keepResumePoint(positionMs = 60_000, durationMs = 0, alreadyWatched = false))
        assertFalse(keepResumePoint(positionMs = 3_000, durationMs = 0, alreadyWatched = false))
    }

    // ---- отметка снимает старый таймкод ---------------------------------------------

    @Test
    fun `ручная отметка «просмотрено» убирает старую позицию этой серии`() {
        val t0 = 1_700_000_000_000L
        val before = state(progress = listOf(progress("a", segment = 23, fraction = 0.10f, at = t0)))
        val after = markTitleWatched(before, card("a"), watched = true, now = t0 + hour)
        assertTrue("запись о прогрессе обязана уйти", after.progress.none { it.anime.id == "a" })
        assertTrue("время отметки обязано появиться", after.watchedAt.getValue("a#23") == t0 + hour)
    }

    // ---- порядок просмотра ------------------------------------------------------------

    @Test
    fun `просмотр вразнобой не возвращает к самой ранней брошенной`() {
        // Смотрели 5, 12, 2, 20 — вперемешку, и последней досмотрели 12-ю. Брошенная
        // 3-я старше всего этого, звать на неё незачем.
        val t0 = 1_700_000_000_000L
        val s = state(
            progress = listOf(progress("a", segment = 3, fraction = 0.4f, at = t0)),
            watched = setOf("a#5", "a#12", "a#2", "a#20"),
            watchedAt = mapOf(
                "a#5" to t0 + hour,
                "a#12" to t0 + 4 * hour,
                "a#2" to t0 + 2 * hour,
                "a#20" to t0 + 3 * hour,
            ),
        )
        assertNotEquals(3, resume(s))
        assertEquals("следующая непросмотренная после ПОСЛЕДНЕЙ ПО ВРЕМЕНИ (12-й)", 13, resume(s))
    }

    @Test
    fun `последняя открытая серия важнее номеров, когда прогресса нет`() {
        val t0 = 1_700_000_000_000L
        val s = state(
            watched = setOf("a#5"),
            watchedAt = mapOf("a#5" to t0),
            prefs = mapOf("a" to PlayerPref(lastSegment = 24)),
        )
        assertEquals(24, resume(s))
    }

    // ---- одинаковые и отсутствующие метки времени -------------------------------------

    @Test
    fun `при равных метках выигрывает запись о прогрессе`() {
        // Часы у файла настроек одни, и совпадение до миллисекунды возможно. Спорную
        // ничью отдаём тому, что конкретнее: у прогресса есть куда возвращаться.
        val t0 = 1_700_000_000_000L
        val s = state(
            progress = listOf(progress("a", segment = 23, fraction = 0.10f, at = t0)),
            watched = setOf("a#25"),
            watchedAt = mapOf("a#25" to t0),
        )
        assertEquals(23, resume(s))
    }

    @Test
    fun `отсутствующая метка времени спорить не берётся`() {
        // Ноль значит «время неизвестно» — так проставляются старые отметки, про
        // которые ничего не известно (см. backfillWatchedAt).
        val t0 = 1_700_000_000_000L
        assertFalse(resumeOutdated(progress("a", 23, 0.1f, at = t0), newestWatchedAt = 0L))
        val s = state(
            progress = listOf(progress("a", segment = 23, fraction = 0.10f, at = t0)),
            watched = setOf("a#25"),
            watchedAt = mapOf("a#25" to 0L),
        )
        assertEquals(23, resume(s))
    }

    // ---- перезагрузка и повторное чтение данных ---------------------------------------

    @Test
    fun `старым отметкам без времени оно проставляется при загрузке`() {
        val t0 = 1_700_000_000_000L
        val legacy = PersistedState(
            progress = listOf(progress("a", segment = 23, fraction = 0.10f, at = t0)),
            watched = setOf("a#25", "a#28"),
            episodeCounts = mapOf("a" to 28),
        )
        val stamped = backfillWatchedAt(legacy)
        assertTrue("серии ПОСЛЕ брошенной считаем досмотренными позже", stamped.watchedAt.getValue("a#25") > t0)
        assertTrue(stamped.watchedAt.getValue("a#28") > t0)
        assertNotEquals("и жалоба чинится уже на накопленных данных", 23, resume(stamped))
    }

    @Test
    fun `серии ДО брошенной время не выдумывают`() {
        // Про них порядок неизвестен, и врать нельзя: досмотренная первая серия ничего
        // не говорит о том, когда бросили третью.
        val t0 = 1_700_000_000_000L
        val legacy = PersistedState(
            progress = listOf(progress("a", segment = 3, fraction = 0.4f, at = t0)),
            watched = setOf("a#1", "a#2"),
            episodeCounts = mapOf("a" to 28),
        )
        val stamped = backfillWatchedAt(legacy)
        assertEquals(0L, stamped.watchedAt.getValue("a#1"))
        assertEquals("точка продолжения не сдвигается", 3, resume(stamped))
    }

    @Test
    fun `повторное чтение ничего не меняет`() {
        // Перезагрузка страницы, перезапуск приложения, второй заход подряд — простановка
        // времени обязана быть разовой, иначе каждый запуск сдвигал бы метки.
        val t0 = 1_700_000_000_000L
        val once = backfillWatchedAt(
            PersistedState(
                progress = listOf(progress("a", segment = 23, fraction = 0.10f, at = t0)),
                watched = setOf("a#25"),
                episodeCounts = mapOf("a" to 28),
            ),
        )
        val twice = backfillWatchedAt(once)
        assertTrue(twice.watchedAtBackfilled)
        assertEquals(once.watchedAt, twice.watchedAt)
        assertEquals(resume(once), resume(twice))
    }

    @Test
    fun `новые отметки простановка не трогает`() {
        val t0 = 1_700_000_000_000L
        val mixed = PersistedState(
            progress = listOf(progress("a", segment = 23, fraction = 0.10f, at = t0)),
            watched = setOf("a#25", "a#28"),
            watchedAt = mapOf("a#25" to t0 + 5 * hour),
            episodeCounts = mapOf("a" to 28),
        )
        val stamped = backfillWatchedAt(mixed)
        assertEquals("настоящее время не переписывается", t0 + 5 * hour, stamped.watchedAt.getValue("a#25"))
        assertTrue(stamped.watchedAt.containsKey("a#28"))
    }
    @Test
    fun `разовый прыжок в конец не забирает «Продолжить» себе`() {
        // Настоящее состояние 24.08 по «Наруто», ужатое до 28 серий: подряд просмотрены
        // 24-я и 25-я, а позже разово отмечена последняя. Предложение упиралось в неё,
        // хотя человеку нужна 26-я — та, на которой он в этом сериале стоит.
        val t0 = 1_700_000_000_000L
        val s = state(
            watched = setOf("a#24", "a#25", "a#28"),
            watchedAt = mapOf("a#24" to t0, "a#25" to t0 + hour, "a#28" to t0 + 5 * hour),
        )
        assertEquals(26, resume(s))
    }

    @Test
    fun `когда просмотрено всё, открывается последняя`() {
        val t0 = 1_700_000_000_000L
        val s = state(
            watched = episodes.map { "a#$it" }.toSet(),
            watchedAt = episodes.associate { "a#$it" to t0 + it * minute },
        )
        assertEquals(28, resume(s))
    }

    @Test
    fun `непроигрываемые серии не предлагаются`() {
        val t0 = 1_700_000_000_000L
        val s = state(watched = setOf("a#5"), watchedAt = mapOf("a#5" to t0))
        val pick = resumeSegmentOf(s, "a", "a", episodes, playable = { it != 6 && it != 7 })
        assertEquals("шестая и седьмая без потока — берём восьмую", 8, pick)
    }

}
