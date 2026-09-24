package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.OpeningRange
import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Откуда берётся опенинг у серии, которой нет в базе AniSkip.
 *
 * Покрытие AniSkip дырявое — у «Чёрной кошки и класса ведьм» (mal 62171) из двенадцати
 * серий заполнены три, — поэтому интервал приходится ЗАНИМАТЬ у другой серии сезона.
 * Ролик один и тот же, так что занимать можно; вопрос в том, У КОГО.
 *
 * ЗАМЕРЕНО живым запросом к AniSkip по этому тайтлу:
 *
 *     серия  1: 2.1 – 92.1
 *     серия  2: 42.5 – 131.5
 *     серия  8: 29.3 – 119.2
 *     остальные: нет данных
 *
 * Первая серия — ВЫБРОС: она начинается с опенинга сразу, без холодного открытия,
 * которое есть во всех прочих. Разница со второй — сорок секунд. Ровно она и была
 * причиной жалобы: пробы шли в порядке [1, 2, сосед, сосед], первая находилась
 * первой, и на любой незаполненной серии «Пропустить опенинг» уводил на 92-ю секунду,
 * где опенинг только-только начался и играл ещё полминуты.
 */
class SeasonOpeningTest {

    private fun range(startSec: Double, endSec: Double) =
        OpeningRange((startSec * 1000).toLong(), (endSec * 1000).toLong())

    // ---- у кого занимать ----

    @Test
    fun `первая серия опрашивается последней`() {
        // Она всё ещё нужна: у тайтлов, где заполнена ТОЛЬКО она, других доноров нет.
        // Но идти к ней первой — значит систематически брать самый непохожий интервал.
        val order = seasonProbeOrder(episode = 12, limit = 5)
        assertTrue("первая серия обязана остаться в пробах: $order", 1 in order)
        assertEquals("но только последней", 1, order.last())
    }

    @Test
    fun `сначала опрашиваются соседи`() {
        // У соседних серий одинаковая структура: то же холодное открытие, тот же
        // опенинг на том же месте.
        val order = seasonProbeOrder(episode = 12, limit = 5)
        assertEquals(listOf(11, 13, 10, 14), order.take(4))
    }

    @Test
    fun `сама серия в пробы не идёт`() {
        assertTrue(seasonProbeOrder(episode = 7, limit = 5).none { it == 7 })
    }

    @Test
    fun `у первой серии соседей слева нет`() {
        val order = seasonProbeOrder(episode = 1, limit = 5)
        assertTrue("номеров меньше единицы быть не может: $order", order.all { it >= 1 })
        assertTrue("сама себе донором не бывает: $order", order.none { it == 1 })
        assertEquals(2, order.first())
    }

    @Test
    fun `проб ровно столько, сколько заказано`() {
        assertEquals(5, seasonProbeOrder(episode = 12, limit = 5).size)
        assertEquals(3, seasonProbeOrder(episode = 12, limit = 3).size)
    }

    @Test
    fun `список проб дотягивается через дыру в покрытии`() {
        // ЖИВАЯ ПРОВЕРКА ПОКАЗАЛА, ЧТО ПЕРВОЙ ПРАВКИ БЫЛО МАЛО. У замеренного тайтла
        // заполнены серии 1, 2 и 8; на двенадцатой ВСЕ ближние соседи (10, 11, 13, 14)
        // пусты, и с коротким списком проб единственной находкой снова оказывалась
        // первая серия — тот самый интервал, от которого мы уходим.
        val order = seasonProbeOrder(episode = 12, limit = SEASON_PROBES)
        assertTrue("до восьмой серии список обязан дотянуться: $order", 8 in order)
        assertTrue("и первая всё ещё последняя: $order", order.last() == 1)
    }

    @Test
    fun `с реальным бюджетом донором становится восьмая серия, а не первая`() {
        // Тот же набор, что отдаёт живой AniSkip: 1, 2 и 8 заполнены, прочие нет.
        val real = mapOf(1 to range(2.1, 92.1), 2 to range(42.5, 131.5), 8 to range(29.3, 119.2))
        val found = seasonProbeOrder(episode = 12, limit = SEASON_PROBES).mapNotNull { real[it] }
        val picked = pickSeasonRange(found)
        assertTrue("донор не найден вовсе: $found", picked != null)
        assertTrue(
            "снова занят интервал первой серии: ${picked!!.startMs}",
            picked.startMs > 10_000L,
        )
    }

    // ---- что выбрать из найденного ----

    @Test
    fun `из нескольких доноров берётся серединный, а не первый`() {
        // РОВНО ЗАМЕРЕННЫЙ СЛУЧАЙ. Первый попавшийся — это 2.1 (серия 1, выброс).
        // Серединный — 29.3, и он на сорок секунд ближе к правде.
        val found = listOf(range(2.1, 92.1), range(42.5, 131.5), range(29.3, 119.2))
        assertEquals(range(29.3, 119.2), pickSeasonRange(found))
    }

    @Test
    fun `выброс не перетягивает выбор`() {
        val withOutlier = listOf(range(2.0, 92.0), range(40.0, 130.0), range(41.0, 131.0))
        assertEquals(range(40.0, 130.0), pickSeasonRange(withOutlier))
    }

    @Test
    fun `из двух доноров берётся поздний`() {
        // Занять слишком РАННИЙ интервал хуже, чем слишком поздний: кнопка уводит в
        // середину опенинга, и он продолжает играть. Поздний в худшем случае
        // перепрыгнет несколько секунд серии, что заметно меньше раздражает.
        val found = listOf(range(2.1, 92.1), range(42.5, 131.5))
        assertEquals(range(42.5, 131.5), pickSeasonRange(found))
    }

    @Test
    fun `единственный донор берётся как есть`() {
        assertEquals(range(2.1, 92.1), pickSeasonRange(listOf(range(2.1, 92.1))))
    }

    @Test
    fun `без доноров занимать нечего`() {
        assertNull(pickSeasonRange(emptyList()))
    }
}
