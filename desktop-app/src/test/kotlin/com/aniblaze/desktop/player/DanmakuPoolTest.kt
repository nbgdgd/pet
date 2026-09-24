package com.aniblaze.desktop.player

import com.aniblaze.aggregator.model.TitleComment
import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Что вообще попадает на кадр.
 *
 * Обсуждения под тайтлами состоят далеко не только из реплик по делу: там «первый!!!»,
 * простыни на пол-экрана, ссылки, капс и один и тот же текст от разных людей. Поверх
 * видео такое не оживляет, а мешает, поэтому отбор стоит ДО показа, а не в оформлении.
 */
class DanmakuPoolTest {

    private var nextId = 1L

    private fun comment(
        text: String,
        spoiler: Boolean = false,
        episode: Int = 0,
        timestamp: Long = 0L,
    ) = TitleComment(
        id = nextId++,
        author = "кто-то",
        avatar = "",
        message = text,
        timestamp = timestamp,
        votes = 0,
        isSpoiler = spoiler,
        episode = episode,
    )

    @Test
    fun `реплики чужих серий не показываются никогда`() {
        // На первой серии комментарий с шестой — готовый спойлер.
        val pool = danmakuPool(
            listOf(
                comment("Вот тут на шестой всё и объяснилось наконец", episode = 6),
                comment("Первая серия затягивает с самого начала", episode = 1),
            ),
            episode = 1,
        )
        assertEquals(listOf("Первая серия затягивает с самого начала"), pool)
    }

    @Test
    fun `реплики без указания серии разрешены на любой`() {
        val pool = danmakuPool(
            listOf(comment("Рисовка у этой студии всегда отличная", episode = 0)),
            episode = 4,
        )
        assertEquals(1, pool.size)
    }

    @Test
    fun `когда про серию сказано мало, идёт добор из общих`() {
        // Ровно тот случай, ради которого добор и нужен: у давних тайтлов поле
        // «серия» пустое почти везде, и без добора не всплывало бы ничего.
        val own = listOf(comment("Эта серия вышла напряжённой очень", episode = 2))
        val general = (1..5).map { comment("Общее мнение о тайтле номер $it", episode = 0) }
        val pool = danmakuPool(own + general, episode = 2)
        assertTrue("добор не сработал: ${pool.size}", pool.size > 1)
    }

    @Test
    fun `соседние серии не показывают одни и те же общие реплики`() {
        // ПРИЧИНА ЖАЛОБЫ НА ДУБЛИКАТЫ: общий пул тайтла отдавался целиком каждой
        // серии, и при просмотре подряд человек видел те же фразы снова и снова.
        val general = (1..200).map { comment("Общее мнение о тайтле под номером $it", episode = 0) }
        val five = danmakuPool(general, episode = 5).toSet()
        val six = danmakuPool(general, episode = 6).toSet()
        val seven = danmakuPool(general, episode = 7).toSet()
        assertTrue("пятой серии досталось пусто", five.isNotEmpty())
        assertTrue("пересечение 5 и 6: ${(five intersect six).size}", (five intersect six).isEmpty())
        assertTrue("пересечение 6 и 7: ${(six intersect seven).size}", (six intersect seven).isEmpty())
    }

    @Test
    fun `свои реплики серии не режутся на доли`() {
        // У выходящих сейчас тайтлов поле «серия» заполнено — тогда серия забирает
        // ВСЕ свои реплики, а доля общего только дополняет.
        val own = (1..30).map { comment("Разбор третьей серии, мысль номер $it", episode = 3) }
        val general = (1..200).map { comment("Общее мнение о тайтле под номером $it", episode = 0) }
        val pool = danmakuPool(own + general, episode = 3)
        val ownShown = pool.count { it.startsWith("Разбор третьей серии") }
        assertEquals("все свои реплики обязаны попасть в очередь", 30, ownShown)
    }

    @Test
    fun `маленький общий пул не режется в ничто`() {
        // Восемь реплик на весь тайтл — резать нечего, серия берёт всё.
        val general = (1..8).map { comment("Небольшое общее мнение номер $it тут", episode = 0) }
        assertEquals(8, danmakuPool(general, episode = 4).size)
    }

    @Test
    fun `у фильма берутся только реплики без серии`() {
        val pool = danmakuPool(
            listOf(
                comment("Что-то про седьмую серию сериала", episode = 7),
                comment("Отличный фильм, посмотрел на одном дыхании", episode = 0),
            ),
            episode = 0,
        )
        assertEquals(listOf("Отличный фильм, посмотрел на одном дыхании"), pool)
    }

    @Test
    fun `только новые ставит свежие вперёд`() {
        val pool = danmakuPool(
            listOf(
                comment("Написано давным-давно про эту серию", timestamp = 100L),
                comment("Написано только что про эту серию", timestamp = 900L),
            ),
            freshFirst = true,
        )
        assertEquals("Написано только что про эту серию", pool.first())
    }

    @Test
    fun `без флага порядок источника не трогается`() {
        val pool = danmakuPool(
            listOf(
                comment("Самый заплюсованный комментарий тут", timestamp = 100L),
                comment("Свежий, но никем не оценённый пока", timestamp = 900L),
            ),
        )
        assertEquals("Самый заплюсованный комментарий тут", pool.first())
    }

    @Test
    fun `спойлеры поверх видео заменяются нейтральным текстом`() {
        val pool = danmakuPool(
            listOf(
                comment("Тут в конце он оказывается братом, представляете", spoiler = true),
                comment("Рисовка в этой серии просто отличная"),
            ),
        )
        assertEquals(listOf(NEW_MESSAGE_TEXT, "Рисовка в этой серии просто отличная"), pool)
    }

    @Test
    fun `слишком короткие и слишком длинные отсеиваются`() {
        val pool = danmakuPool(
            listOf(
                comment("первый!!!"),
                comment("+"),
                comment("а".repeat(400)),
                comment("Опенинг у этого сезона лучше предыдущего"),
            ),
        )
        assertEquals(listOf("Опенинг у этого сезона лучше предыдущего"), pool)
    }

    @Test
    fun `повторы схлопываются`() {
        val pool = danmakuPool(
            listOf(
                comment("Лучшее аниме сезона однозначно"),
                comment("Лучшее аниме сезона однозначно"),
                comment("лучшее аниме сезона однозначно!!!"),
            ),
        )
        assertEquals("повтор должен остаться один", 1, pool.size)
    }

    @Test
    fun `почти одинаковые тексты — один ключ`() {
        // Ровно те вариации, что давали «снова дубликаты»: регистр, лишние пробелы,
        // повторная пунктуация, эмодзи-хвост. Нормализация обязана их слепить.
        val variants = listOf(
            "Лучшее аниме сезона однозначно",
            "ЛУЧШЕЕ аниме сезона однозначно",
            "лучшее   аниме  сезона   однозначно",
            "Лучшее аниме сезона однозначно!!!!!!",
            "Лучшее аниме сезона однозначно 😀😀",
            "Лучшее, аниме — сезона; однозначно...",
        )
        assertEquals(1, variants.map { danmakuKey(it) }.toSet().size)
        val pool = danmakuPool(variants.map { comment(it) })
        assertEquals("в очереди из шести написаний — одна реплика", 1, pool.size)
    }

    @Test
    fun `пятьдесят комментариев с дублями — все ключи уникальны`() {
        val base = (1..25).map { "Развёрнутое мнение о серии номер $it, вполне осмысленное" }
        val noisy = base + base.map { it.uppercase() } // те же, но капсом
        val pool = danmakuPool(noisy.map { comment(it) })
        val keys = pool.map { danmakuKey(it) }
        assertEquals("ни один нормализованный текст не встречается дважды", keys.size, keys.toSet().size)
        assertEquals("дубли схлопнулись до базы", 25, pool.size)
    }

    @Test
    fun `капс и ссылки не проходят`() {
        val pool = danmakuPool(
            listOf(
                comment("СМОТРИТЕ ВСЕ ЭТО ЛУЧШЕЕ АНИМЕ ГОДА"),
                comment("качайте тут http://example.com/beton"),
                comment("Музыка в концовке цепляет очень сильно"),
                comment("Музыка в финале звучит прекрасно"),
            ),
        )
        assertEquals(
            listOf("Музыка в концовке цепляет очень сильно", "Музыка в финале звучит прекрасно"),
            pool,
        )
    }

    @Test
    fun `переносы строк схлопываются в одну строку`() {
        val pool = danmakuPool(listOf(comment("Серия\n\n  вышла    отличная,\nсмотрю дальше")))
        assertEquals(listOf("Серия вышла отличная, смотрю дальше"), pool)
    }

    @Test
    fun `очередь ограничена сверху`() {
        val many = (1..500).map { comment("Комментарий про эту серию номер $it") }
        assertTrue("очередь не должна расти без предела", danmakuPool(many).size <= 80)
    }

    @Test
    fun `пустой список не ломает отбор`() {
        assertTrue(danmakuPool(emptyList()).isEmpty())
        assertFalse(danmakuPool(listOf(comment("   "))).isNotEmpty())
    }
    // ---- чьи реплики достаются серии ------------------------------------------------

    @Test
    fun `реплики пройденных серий доступны, будущих — никогда`() {
        // Замерено 23.08 на «Наруто», 141-я серия: из полутораста свежих записей НИ ОДНОЙ
        // без пометки серии и ни одной со 141-й — пишут те, кто смотрит конец. Правило
        // «только беспометочные и свои» оставляло экран пустым.
        //
        // Пройденное спойлером не является по определению — зритель это видел. Спойлер —
        // то, что впереди, и оно не показывается никогда.
        val pool = listOf(
            comment("что было в самом начале", episode = 3),
            comment("вот это поворот в сотой", episode = 100),
            comment("а в двухсотой вообще", episode = 200),
            comment("без пометки серии вовсе", episode = 0),
        )
        val shown = episodeScope(pool, episode = 141).map { it.episode }
        assertTrue("реплика третьей серии зрителю 141-й не спойлер", 3 in shown)
        assertTrue("сотой — тоже", 100 in shown)
        assertTrue("беспометочные как и раньше", 0 in shown)
        assertFalse("двухсотая серия впереди — это спойлер", 200 in shown)
    }

    @Test
    fun `у первой серии пул только беспометочный`() {
        val pool = listOf(
            comment("без пометки", episode = 0),
            comment("про пятую", episode = 5),
        )
        assertEquals(listOf(0), episodeScope(pool, episode = 1).map { it.episode })
    }

    @Test
    fun `когда серия неизвестна, берём только беспометочные`() {
        val pool = listOf(comment("без пометки", episode = 0), comment("про пятую", episode = 5))
        assertEquals(listOf(0), episodeScope(pool, episode = 0).map { it.episode })
    }

}
