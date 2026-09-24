package com.aniblaze.desktop.player

/**
 * Насколько «энергична» картинка прямо сейчас — по НАСТОЯЩИМ декодированным кадрам.
 *
 * Нужен симулятору чата: без этого его реакции были бы просто расписанием по
 * таймеру, одинаковым для драки и для разговора за столом. Здесь измеряется то, что
 * из кадра действительно видно:
 *
 *  • [motion] — насколько картинка изменилась с прошлого кадра;
 *  • [brightness] — насколько она светлая;
 *  • [cutsPerMinute] — как часто происходит СМЕНА ПЛАНА (кадр меняется целиком).
 *
 * ПОРОГИ ЗДЕСЬ ОТНОСИТЕЛЬНЫЕ, А НЕ АБСОЛЮТНЫЕ. «Движение больше 0.06» — это число,
 * которое пришлось бы подбирать под каждую студию: у динамичного экшена спокойная
 * сцена шумит сильнее, чем драка в разговорном тайтле. Поэтому рядом с быстрым
 * средним держится МЕДЛЕННОЕ ([motionBase], [brightnessBase]), и наружу отдаётся
 * отношение одного к другому: «сейчас вдвое живее обычного» — утверждение, верное
 * для любого тайтла и не требующее калибровки.
 *
 * Считается на потоке VLC внутри [VideoFrameSink.accept], поэтому дёшево по
 * построению: кадр опрашивается сеткой точек (336 значений на кадр, то есть около
 * восьми тысяч чтений байта в секунду при 24 к/с) — на фоне копирования самого кадра
 * в несколько мегабайт это ничто.
 *
 * ПОТОКОБЕЗОПАСНОСТЬ. Всё изменяемое состояние принадлежит ОДНОМУ потоку — тому, на
 * котором libVLC отдаёт кадры. Наружу торчат только `@Volatile`-числа для чтения и
 * два запроса-флага ([reset], [forgetLastFrame]), которые исполняются в начале
 * следующего [accept] — на своём потоке.
 *
 * Это не перестраховка, а исправление НАСТОЯЩЕЙ поломки. Сначала `reset()` чистил
 * состояние прямо с потока интерфейса, а очередь склеек была `ArrayDeque`:
 *
 *     while (cuts.isNotEmpty() && cuts.first() < window) cuts.removeFirst()
 *
 * Чужой `clear()` между проверкой и снятием — и на потоке VLC летит исключение прямо
 * из нативного колбэка. По журналам счёт однозначный: `commandThreadStuck` ни разу
 * до появления замера и семь раз за полчаса после. Поэтому очередь теперь —
 * кольцевой массив фиксированного размера: в нём нечему бросить исключение и нечего
 * выделять.
 */
internal class SceneEnergy {

    /** Быстрое среднее изменения кадра, 0..1. */
    @Volatile
    var motion: Float = 0f
        private set

    /** Медленное среднее того же — «обычный уровень» этого тайтла. */
    @Volatile
    var motionBase: Float = 0f
        private set

    /** Яркость кадра, 0..1. */
    @Volatile
    var brightness: Float = 0.5f
        private set

    /** Медленное среднее яркости. */
    @Volatile
    var brightnessBase: Float = 0.5f
        private set

    /** Смен плана в минуту (по стенным часам — доля кадров с полной сменой картинки). */
    @Volatile
    var cutsPerMinute: Float = 0f
        private set

    /** Сколько кадров уже измерено: до прогрева судить не по чему. */
    @Volatile
    var frames: Long = 0L
        private set

    /** Полная смена плана произошла на последнем кадре. */
    @Volatile
    var cutNow: Boolean = false
        private set

    private var previous: IntArray? = null

    /**
     * Моменты последних склеек — кольцо фиксированного размера.
     *
     * Не список и не очередь: этот код исполняется в нативном колбэке, где любое
     * исключение и любая пауза сборщика мусора бьют по воспроизведению. У кольца нет
     * ни того, ни другого. Размера хватает с запасом: в окно [CUT_WINDOW_NANOS] даже
     * у самого рваного монтажа столько склеек не помещается, а переполнение просто
     * затирает самую старую.
     */
    private val cutRing = LongArray(CUT_RING)
    private var cutCount = 0

    /** На каком кадре в последний раз картинка сменилась целиком. */
    private var lastCutFrame = Long.MIN_VALUE / 2

    /** Сколько кадров пошло в «обычное»: склейки в него не идут, счёт свой. */
    private var baseSamples = 0L

    /** Когда пришёл прошлый кадр — сглаживание считается по ВРЕМЕНИ, не по кадрам. */
    private var lastAtNanos = 0L

    /**
     * Была ли склейка с прошлого [snapshot].
     *
     * Флаг «прямо на этом кадре» читателю бесполезен: читают раз в 400 мс, а кадров
     * за это время проходит десяток — девять склеек из десяти просто не увидели бы.
     * Защёлка гарантирует, что ни одна не потеряется. Атомарная, потому что ставит её
     * поток VLC, а забирает поток интерфейса.
     */
    private val cutPending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Запрос с чужого потока: 0 — нет, 1 — забыть кадр, 2 — сбросить всё. */
    private val request = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Очередная сетка яркостей кадра (значения 0..255) и момент по стенным часам.
     *
     * Массив можно переиспользовать: он читается здесь же и наружу не уходит.
     */
    fun accept(luma: IntArray, atNanos: Long) {
        // Чужие запросы исполняются ЗДЕСЬ, на своём потоке, и только здесь.
        when (request.getAndSet(0)) {
            REQUEST_RESET -> applyReset()
            REQUEST_FORGET -> previous = null
        }
        val before = previous
        var sum = 0L
        for (value in luma) sum += value
        val mean = sum.toFloat() / luma.size / 255f

        if (before == null || before.size != luma.size) {
            previous = luma.copyOf()
            lastAtNanos = atNanos
            if (frames == 0L || (before != null && before.size != luma.size)) {
                // Совсем новая раздача: сравнивать не с чем и накоплено нечего.
                brightness = mean
                brightnessBase = mean
                frames = 1L
            } else {
                // Кадр просто потеряли (ушли на другую вкладку). Серия та же —
                // накопленное про неё остаётся, просто заново берём точку отсчёта,
                // чтобы разрыв не засчитался огромной сменой плана.
                frames++
            }
            return
        }
        // Шаг сглаживания считается по ВРЕМЕНИ, а не по числу кадров: поток отдаёт то
        // 24 кадра в секунду, то 15 (замерено: decoded=35.8, displayed=15.5), и
        // постоянный шаг «на кадр» означал бы разное окно усреднения на разных
        // раздачах. Разрыв зажат сверху — пауза и добуферизация не должны считаться
        // «прошло полминуты».
        val dt = (atNanos - lastAtNanos).coerceIn(1_000_000L, 250_000_000L)
        lastAtNanos = atNanos
        val fastAlpha = 1f - kotlin.math.exp(-dt.toFloat() / TAU_FAST_NANOS)
        var delta = 0L
        for (i in luma.indices) {
            val diff = luma[i] - before[i]
            delta += if (diff < 0) -diff else diff
        }
        val change = delta.toFloat() / luma.size / 255f
        System.arraycopy(luma, 0, before, 0, luma.size)

        motion = motion * (1f - fastAlpha) + change * fastAlpha
        brightness = brightness * (1f - fastAlpha) + mean * fastAlpha

        // Смена плана: картинка изменилась целиком за один кадр. Порог здесь как раз
        // может быть абсолютным — «половина всех точек уехала» не зависит от студии.
        //
        // Склейки не могут идти чаще, чем раз в [MIN_CUT_GAP] кадров: в смотрибельном
        // видео между планами проходят доли секунды, а вот декодер после перемотки или
        // на добуферизации выдаёт очередь несвязанных кадров подряд. Без этой отсечки
        // одна перемотка засчитывалась как пачка склеек и разгоняла чат на пустом месте.
        val jump = change >= CUT_CHANGE
        val cut = jump && frames - lastCutFrame >= MIN_CUT_GAP
        cutNow = cut
        if (cut) cutPending.set(true)
        if (jump) lastCutFrame = frames

        // Медленное среднее НАЧИНАЕТСЯ как обычное среднее и лишь потом становится
        // экспоненциальным.
        //
        // Без этого оно бесполезно с обеих сторон: с постоянным маленьким шагом ему
        // нужны минуты, чтобы уехать от нуля, а с большим оно догоняет текущую сцену
        // за десять секунд — и тихая сцена перестаёт быть тихой, потому что «обычным»
        // стала она сама. Ровно это и поймал тест: после двадцати секунд темноты
        // отношение показывало 0.78 вместо ожидаемых 0.62 и ниже.
        //
        // Кадры-склейки в «обычное» НЕ ИДУТ. Замерено на живом просмотре: старт и
        // перемотка дают несколько скачков по 0.3–0.5, и обычное среднее, посчитанное
        // вместе с ними, показывало 0.0351 там, где сцена шла на 0.0076, — драке
        // пришлось бы вшестеро превысить настоящий фон, чтобы её заметили.
        if (!jump) {
            val slow = kotlin.math.max(SLOW_ALPHA, 1f / (baseSamples + 1))
            motionBase = motionBase * (1f - slow) + change * slow
            brightnessBase = brightnessBase * (1f - slow) + mean * slow
            baseSamples++
        }

        if (cut) {
            if (cutCount == CUT_RING) {
                System.arraycopy(cutRing, 1, cutRing, 0, CUT_RING - 1)
                cutCount--
            }
            cutRing[cutCount++] = atNanos
        }
        // Устаревшие моменты выбрасываются сдвигом: их единицы, и сдвиг короткого
        // массива дешевле любой структуры с указателями.
        val window = atNanos - CUT_WINDOW_NANOS
        var stale = 0
        while (stale < cutCount && cutRing[stale] < window) stale++
        if (stale > 0) {
            System.arraycopy(cutRing, stale, cutRing, 0, cutCount - stale)
            cutCount -= stale
        }
        cutsPerMinute = cutCount * (60_000_000_000f / CUT_WINDOW_NANOS)

        frames++
    }

    /**
     * Кадры прервались, но серия та же (другая вкладка, свёрнутое окно).
     *
     * Забывается ТОЛЬКО точка отсчёта — иначе первый кадр по возвращении посчитался
     * бы сменой плана. Накопленное про серию остаётся: без этого замер после каждого
     * переключения вкладки минуту отвечал бы «не знаю».
     */
    fun forgetLastFrame() {
        // Сброс сильнее забывания: если он уже заказан, не ослабляем его.
        request.compareAndSet(0, REQUEST_FORGET)
    }

    /** Новая серия или новый поток: прежняя картинка к нынешней отношения не имеет. */
    fun reset() {
        request.set(REQUEST_RESET)
        // Показатели гасятся сразу, не дожидаясь кадра: читатель не должен ни секунды
        // считать по прошлой серии. Это ЗАПИСЬ ОТДЕЛЬНЫХ ЧИСЕЛ — гонки за ней нет,
        // худшее возможное — один кадр со смешанными значениями, и он всё равно
        // отсеется, потому что `frames` обнулён и замер объявляет себя непригодным.
        motion = 0f
        motionBase = 0f
        brightness = 0.5f
        brightnessBase = 0.5f
        cutsPerMinute = 0f
        cutNow = false
        cutPending.set(false)
        frames = 0L
    }

    /** Исполняется ТОЛЬКО на потоке кадров. */
    private fun applyReset() {
        previous = null
        cutCount = 0
        lastCutFrame = Long.MIN_VALUE / 2
        baseSamples = 0L
        lastAtNanos = 0L
        motion = 0f
        motionBase = 0f
        brightness = 0.5f
        brightnessBase = 0.5f
        cutsPerMinute = 0f
        cutNow = false
        cutPending.set(false)
        frames = 0L
    }

    /**
     * Снимок для читателя. ЗАБИРАЕТ защёлку склейки: после вызова [SceneSample.cutSeen]
     * снова false, пока не произойдёт новая. Читатель ровно один — цикл чата.
     */
    fun snapshot(): SceneSample {
        val seen = cutPending.getAndSet(false)
        return SceneSample(
            motion = motion,
            motionBase = motionBase,
            brightness = brightness,
            brightnessBase = brightnessBase,
            cutsPerMinute = cutsPerMinute,
            cutSeen = seen,
            frames = frames,
        )
    }

    private companion object {
        /**
         * Окно усреднения движения — две секунды.
         *
         * Было 0.19 с, и ЖИВОЙ ЖУРНАЛ показал, почему это не работает: аниме держит
         * один рисунок по два-три кадра, между всплесками разница ровно ноль, и
         * короткое окно проваливается туда же. Замеры прыгали 0.0002 → 0.0898, то
         * есть в четыреста раз, и попадание такта чата на всплеск решало всё —
         * отсюда «драка» посреди разговора. Две секунды накрывают несколько всплесков
         * сразу, и остаётся настоящая разница между сценами.
         */
        const val TAU_FAST_NANOS = 2_000_000_000f

        /**
         * Нижняя граница шага медленного среднего: 1/2000 ≈ полторы минуты при 24 к/с.
         *
         * Оно обязано описывать ТАЙТЛ, а не текущую сцену: если оно догоняет драку за
         * десять секунд, драка перестаёт отличаться от разговора ровно тогда, когда
         * начинает быть интересной.
         */
        const val SLOW_ALPHA = 0.0005f
        const val CUT_CHANGE = 0.20f
        const val CUT_WINDOW_NANOS = 20_000_000_000L

        /** Минимум кадров между засчитанными склейками (≈0.25 с при 24 к/с). */
        const val MIN_CUT_GAP = 6L

        /** Ёмкость кольца склеек: 64 за двадцать секунд — потолок с большим запасом. */
        const val CUT_RING = 64

        const val REQUEST_FORGET = 1
        const val REQUEST_RESET = 2
    }
}

/** Мгновенный снимок [SceneEnergy] — чтобы читатель видел согласованные значения. */
internal data class SceneSample(
    val motion: Float,
    val motionBase: Float,
    val brightness: Float,
    val brightnessBase: Float,
    val cutsPerMinute: Float,
    /** Была ли смена плана с прошлого снимка (см. [SceneEnergy.snapshot]). */
    val cutSeen: Boolean,
    val frames: Long,
) {
    /** Кадров ещё слишком мало, чтобы медленное среднее что-то значило. */
    val known: Boolean get() = frames >= WARMUP_FRAMES

    /** Во сколько раз сейчас живее обычного для этого тайтла. */
    val motionRatio: Float
        get() = if (motionBase > EPS) motion / motionBase else 1f

    /** Во сколько раз сейчас темнее обычного (меньше единицы — темнее). */
    val brightnessRatio: Float
        get() = if (brightnessBase > EPS) brightness / brightnessBase else 1f

    companion object {
        val UNKNOWN = SceneSample(0f, 0f, 0.5f, 0.5f, 0f, false, 0L)

        /** ≈ пять секунд при 24 к/с: до этого медленное среднее ещё едет от нуля. */
        const val WARMUP_FRAMES = 120L
        private const val EPS = 1e-4f
    }
}

/** Ширина сетки опроса кадра. */
internal const val ENERGY_GRID_W = 24

/** Высота сетки опроса кадра. */
internal const val ENERGY_GRID_H = 14
