package com.aniblaze.desktop.player

import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

/**
 * Замер кадра — от пикселей до вывода «что происходит».
 *
 * Кадры здесь синтетические, и это осознанно: проверяется ЦЕПОЧКА (сетка яркостей →
 * скользящие средние → отношение к медленному среднему → настроение), а не то, как
 * конкретная студия рисует драки. Пороги в [measuredMood] относительные именно
 * поэтому — «вдвое живее обычного» проверяемо без единого гигабайта видео.
 *
 * Каждая сцена сначала «прогревается» разговорным режимом: медленное среднее — это
 * и есть представление о том, что для тайтла обычно, и без прогрева сравнивать не с
 * чем.
 */
class SceneEnergyTest {

    private val size = ENERGY_GRID_W * ENERGY_GRID_H
    private var clock = 0L

    /** Один кадр при 24 к/с. */
    private fun tick(): Long {
        clock += 41_666_667L
        return clock
    }

    private fun frame(base: Int, jitter: Int, seed: Int): IntArray {
        val random = java.util.Random(seed.toLong())
        return IntArray(size) { (base + random.nextInt(jitter + 1) - jitter / 2).coerceIn(0, 255) }
    }

    /** Разговорная сцена: картинка почти не меняется, планы не режутся. */
    private fun dialogue(energy: SceneEnergy, seconds: Int, base: Int = 120) {
        repeat(seconds * 24) { n ->
            // Небольшая рябь вокруг одного и того же изображения — как в статичной
            // сцене с говорящими персонажами.
            energy.accept(frame(base, jitter = 6, seed = n % 3), tick())
        }
    }

    @Test
    fun `разговорная сцена не считается ни дракой, ни грустью`() {
        val energy = SceneEnergy()
        dialogue(energy, seconds = 40)
        val sample = energy.snapshot()
        assertTrue("кадры должны были дойти", sample.known)
        assertEquals(null, measuredMood(300_000L, null, null, sample, 30_000L, 0L, 0L))
        assertTrue("дракой это не признают", !fightNow(sample, SceneMood.CALM))
        assertTrue("грустью тоже", !darkNow(sample, SceneMood.CALM))
    }

    @Test
    fun `быстрая нарезка после разговора читается как драка`() {
        val energy = SceneEnergy()
        dialogue(energy, seconds = 40)
        val calmRatio = energy.snapshot().motionRatio
        // Экшен: каждый кадр заметно другой, а каждый восьмой — новый план целиком.
        repeat(5 * 24) { n ->
            val cut = n % 8 == 0
            energy.accept(frame(base = if (cut) 40 + (n % 5) * 40 else 120, jitter = 90, seed = 1000 + n), tick())
        }
        val sample = energy.snapshot()
        assertTrue("движение обязано вырасти: было $calmRatio, стало ${sample.motionRatio}", sample.motionRatio > 1.75f)
        assertTrue("смены плана обязаны сосчитаться: ${sample.cutsPerMinute}", sample.cutsPerMinute >= 18f)
        assertTrue("признак экшена обязан держаться", fightNow(sample, SceneMood.CALM))
        assertEquals(SceneMood.FIGHT, measuredMood(300_000L, null, null, sample, 0L, 4_000L, 0L))
    }

    @Test
    fun `тёмная неподвижная сцена читается как тихая`() {
        val energy = SceneEnergy()
        dialogue(energy, seconds = 40, base = 130)
        // Свет ушёл, движение почти остановилось.
        repeat(20 * 24) { n -> energy.accept(frame(base = 30, jitter = 2, seed = n % 2), tick()) }
        val sample = energy.snapshot()
        assertTrue("должно потемнеть: ${sample.brightnessRatio}", sample.brightnessRatio <= 0.74f)
        assertTrue("движение должно упасть: ${sample.motionRatio}", sample.motionRatio <= 0.62f)
        assertTrue(darkNow(sample, SceneMood.CALM))
        assertEquals(SceneMood.SAD, measuredMood(300_000L, null, null, sample, 0L, 0L, 9_000L))
    }

    @Test
    fun `одиночная склейка после затишья — поворот, а не драка`() {
        val energy = SceneEnergy()
        dialogue(energy, seconds = 40)
        // Один-единственный кадр, на котором картинка сменилась целиком.
        energy.accept(frame(base = 240, jitter = 10, seed = 77), tick())
        val sample = energy.snapshot()
        assertTrue("склейка обязана опознаться", sample.cutSeen)
        assertEquals(SceneMood.TWIST, measuredMood(300_000L, null, null, sample, 25_000L, 0L, 0L))
        // Ровно та же склейка посреди экшена поворотом не считается — там режут
        // постоянно, и каждый монтажный стык не событие.
        assertNotEquals(SceneMood.TWIST, measuredMood(300_000L, null, null, sample, 0L, 0L, 0L))
    }

    @Test
    fun `панорама двигается, но дракой не считается`() {
        // Медленный проезд камеры или падающий снег дают движение без монтажа.
        val energy = SceneEnergy()
        dialogue(energy, seconds = 40)
        repeat(10 * 24) { n ->
            // Плавный сдвиг яркости по всей сетке: изменение есть, скачка нет.
            energy.accept(IntArray(size) { i -> (100 + ((i + n) % 40)) }, tick())
        }
        val sample = energy.snapshot()
        assertTrue("панорама признаком экшена не является", !fightNow(sample, SceneMood.CALM))
    }

    @Test
    fun `первые кадры ничего не утверждают`() {
        // Пока медленное среднее едет от нуля, любое отношение — мусор. Поэтому до
        // прогрева замер честно объявляет себя непригодным.
        val energy = SceneEnergy()
        dialogue(energy, seconds = 2)
        assertTrue(!energy.snapshot().known)
        assertEquals(null, measuredMood(300_000L, null, null, energy.snapshot(), 30_000L, 9_000L, 9_000L))
    }

    @Test
    fun `смена серии стирает прошлую картинку`() {
        // Иначе первый кадр новой серии выглядел бы как гигантская смена плана и
        // давал бы всплеск чата на пустом месте.
        val energy = SceneEnergy()
        dialogue(energy, seconds = 40)
        energy.reset()
        val sample = energy.snapshot()
        assertEquals(0L, sample.frames)
        assertTrue(!sample.cutSeen)
        assertEquals(0f, sample.cutsPerMinute, 0.001f)
    }

    @Test
    fun `уход на другую вкладку не стирает накопленное о серии`() {
        // Кадры прерываются, серия — нет. Полный сброс означал бы, что после каждого
        // переключения вкладки замер минуту отвечает «не знаю», и чат всё это время
        // судит по расписанию.
        val energy = SceneEnergy()
        dialogue(energy, seconds = 40)
        val base = energy.snapshot().motionBase
        energy.forgetLastFrame()
        // Возвращаемся на совершенно другой картинке — она НЕ должна засчитаться
        // сменой плана: между ними просто ничего не показывали.
        energy.accept(frame(base = 230, jitter = 5, seed = 999), tick())
        val sample = energy.snapshot()
        assertTrue("накопленное обязано пережить паузу", sample.known)
        assertEquals("и остаться прежним", base, sample.motionBase, 1e-6f)
        assertTrue("разрыв — не склейка", !sample.cutSeen)
    }

    @Test
    fun `чужой поток не может уронить замер`() {
        // ПОЧЕМУ ЭТОТ ТЕСТ СУЩЕСТВУЕТ. Первая версия чистила состояние прямо с потока
        // интерфейса, а очередь склеек была ArrayDeque: чужой clear() между
        // isNotEmpty() и removeFirst() ронял исключение НА ПОТОКЕ VLC, прямо из
        // нативного колбэка. По журналам счёт был однозначный — commandThreadStuck ни
        // разу до появления замера и семь раз за полчаса после.
        //
        // Здесь один поток льёт кадры, второй в это же время дёргает сброс. Любое
        // исключение на «потоке кадров» valит тест.
        val energy = SceneEnergy()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val frames = Thread {
            try {
                var n = 0
                while (!stop.get()) {
                    // Каждый третий кадр — полная смена картинки, чтобы очередь склеек
                    // всё время наполнялась и опустошалась.
                    energy.accept(frame(base = if (n % 3 == 0) 20 else 220, jitter = 10, seed = n), tick())
                    n++
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        frames.start()
        repeat(4_000) {
            energy.reset()
            energy.forgetLastFrame()
            energy.snapshot()
        }
        stop.set(true)
        frames.join(10_000)
        failure.get()?.let { throw AssertionError("на потоке кадров упало: $it", it) }
    }

    @Test
    fun `смены плана перестают считаться, когда сцена успокоилась`() {
        // Окно счёта — двадцать секунд по стенным часам: после длинной спокойной
        // сцены прежние склейки не должны держать чат в режиме драки.
        val energy = SceneEnergy()
        dialogue(energy, seconds = 40)
        repeat(3 * 24) { n -> energy.accept(frame(base = 60 + (n % 4) * 50, jitter = 80, seed = 500 + n), tick()) }
        assertTrue("склейки должны были насчитаться", energy.snapshot().cutsPerMinute > 0f)
        dialogue(energy, seconds = 25)
        assertEquals("окно обязано опустеть", 0f, energy.snapshot().cutsPerMinute, 0.001f)
    }
}
