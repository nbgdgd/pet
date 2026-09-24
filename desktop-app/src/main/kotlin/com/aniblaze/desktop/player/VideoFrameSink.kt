package com.aniblaze.desktop.player

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Кадры libVLC, отданные в оперативную память и превращённые в [Image] для Compose.
 *
 * Нужен режиму наложения: пока картинку рисует НАТИВНАЯ поверхность VLC, Windows
 * рисует её поверх любой Compose-графики, поэтому под панель управления приходится
 * отрезать полосу окна — видео живёт в остатке и прыгает в размере каждый раз,
 * когда открывается меню. Когда кадры рисует сам Compose, панель ложится сверху,
 * видео занимает окно целиком, а во время перемотки на экране остаётся последний
 * кадр вместо чёрного поля.
 *
 * Отдаём именно [Image], а не Compose-обёртку: рисовать нужно канвой Skia с фильтром
 * Catmull-Rom, до которого из DrawScope не добраться, — он даёт заметно более чёткую
 * картинку при растягивании (замер: PSNR 40.95 против 39.91 у кубического Mitchell и
 * 39.15 у билинейного).
 *
 * ОСВОБОЖДЕНИЕ КАДРОВ. Каждый опубликованный кадр имеет одну ссылку производителя и
 * отдельную lease у каждого окна, которое его рисует. Main и PiP отпускают свои lease
 * независимо, поэтому быстрый потребитель не может закрыть картинку под медленным.
 * Картинка закрывается ровно один раз — когда ушли производитель и все потребители.
 */
internal class VideoFrameSink(
    /** Номер экземпляра VLC, полезен в журнале и не даёт смешивать поколения. */
    val generation: Int = 0,
) : AutoCloseable {

    private var width = 0
    private var height = 0
    private var pixels = ByteArray(0)
    private var info: ImageInfo? = null

    /**
     * Часть кадра с настоящей картинкой: слева, сверху, ширина, высота.
     *
     * Читается интерфейсом, пишется потоком VLC. Пока рамка не измерена — весь кадр.
     */
    @Volatile
    var contentRect: IntArray = intArrayOf(0, 0, 0, 0)
        private set

    /** Сколько кадров ещё участвует в замере рамки. 0 — рамка зафиксирована. */
    private var borderSamplesLeft = 0
    private var borderTop = 0
    private var borderBottom = 0
    private var borderLeft = 0
    private var borderRight = 0
    private var borderSamplesTaken = 0

    private val callbackLock = Any()
    private val closed = AtomicBoolean(false)

    /**
     * Identifies the latest seek so an older completion cannot settle a newer one.
     * Capture keeps using the normal callback lock, validation and owned frame copy;
     * retaining the last frame also preserves paused seek landings.
     */
    private val seekPauseToken = AtomicLong(0L)


    private class Held(
        val revision: Long,
        val image: Image,
        val contentRect: IntArray,
    ) {
        /** Одна ссылка принадлежит sink, остальные — активным окнам. */
        private val references = AtomicInteger(1)

        fun tryAcquire(): FrameLease? {
            while (true) {
                val current = references.get()
                if (current <= 0) return null
                if (references.compareAndSet(current, current + 1)) {
                    return FrameLease(image, revision, contentRect, references)
                }
            }
        }

        fun releasePublisher() = releaseReference(image, references)
    }

    /** Удерживает нативный [Image], пока конкретное Compose-окно может его рисовать. */
    internal class FrameLease internal constructor(
        val image: Image,
        val revision: Long,
        val contentRect: IntArray,
        private val references: AtomicInteger,
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) releaseReference(image, references)
        }
    }

    /** Единственный опубликованный кадр; глубокая очередь для latest-only UI не нужна. */
    private val latest = AtomicReference<Held?>(null)

    /** Последний кадр, который хотя бы одно окно действительно забрало. */
    private val lastAcquiredRevision = AtomicLong(0L)

    /** Растёт на каждом новом кадре — по нему UI понимает, что пора перерисовать. */
    val revision = AtomicLong(0L)

    private val accepted = AtomicLong(0L)
    private var lastRateAt = 0L
    private var lastRateCount = 0L

    /** Плеер на неактивной вкладке: конвертировать кадры, которые никто не покажет,
     *  незачем — libVLC продолжает декодировать в любом случае. */
    @Volatile
    var enabled: Boolean = true

    /**
     * Что происходит в кадре — движение, яркость, смены плана.
     *
     * Считается прямо здесь, потому что здесь и только здесь пиксели уже лежат в
     * оперативной памяти: отдельный проход по кадру ради этого стоил бы столько же,
     * сколько сама конвертация. Читает симулятор чата (см. [ChatEngine]).
     */
    val energy = SceneEnergy()

    /** Переиспользуемая сетка яркостей: наружу не уходит, аллокаций на кадр нет. */
    private var lumaGrid = IntArray(ENERGY_GRID_W * ENERGY_GRID_H)

    /** Сколько кадров в секунду реально дошло до Compose с прошлого вызова. */
    fun decodedPerSecond(): Double {
        val now = System.nanoTime()
        val count = accepted.get()
        if (lastRateAt == 0L) {
            lastRateAt = now
            lastRateCount = count
            return 0.0
        }
        val seconds = (now - lastRateAt) / 1_000_000_000.0
        val rate = if (seconds > 0.0) (count - lastRateCount) / seconds else 0.0
        lastRateAt = now
        lastRateCount = count
        return rate
    }

    /** Вызывается ПОТОКОМ VLC на каждый декодированный кадр. */
    fun accept(buffer: ByteBuffer, frameWidth: Int, frameHeight: Int) {
        if (closed.get() || !enabled) return
        if (frameWidth <= 0 || frameHeight <= 0) return
        synchronized(callbackLock) {
            if (closed.get() || !enabled) return

            // Backpressure ДО копирования и Image.makeRaster: если UI ещё не забрал
            // опубликованный кадр, следующий ему не нужен. Это ограничивает нативные
            // аллокации реальной частотой отрисовки, а не частотой декодера.
            latest.get()?.let { pending ->
                if (lastAcquiredRevision.get() < pending.revision) return
            }

            val neededLong = frameWidth.toLong() * frameHeight.toLong() * 4L
            if (neededLong <= 0L || neededLong > Int.MAX_VALUE) return
            if (!WindowsCommitGuard.canAllocateFrame(neededLong)) return
            val needed = neededLong.toInt()
            if (frameWidth != width || frameHeight != height) {
                width = frameWidth
                height = frameHeight
                pixels = ByteArray(needed)
                // OPAQUE, а не PREMUL: в RV32 альфа-байт у VLC не заполнен.
                info = ImageInfo(frameWidth, frameHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                resetBorderSearch(frameWidth, frameHeight)
                PlayerDiagnostics.log("frames.format", "generation=$generation; ${frameWidth}x$frameHeight")
            }
            val format = info ?: return
            if (buffer.capacity() < needed) return
            buffer.rewind()
            buffer.get(pixels, 0, needed)
            // Декоративные измерения не должны вырваться из нативного callback.
            runCatching { sampleEnergy(frameWidth, frameHeight) }
            if (borderSamplesLeft > 0) runCatching { sampleBorders(frameWidth, frameHeight) }
            val image = runCatching { Image.makeRaster(format, pixels, frameWidth * 4) }
                .onFailure { PlayerDiagnostics.failure("frames.allocate", it) }
                .getOrNull() ?: return
            if (closed.get()) {
                runCatching { image.close() }
                return
            }
            val number = revision.incrementAndGet()
            val held = Held(number, image, contentRect.copyOf())
            latest.getAndSet(held)?.releasePublisher()
            accepted.incrementAndGet()
        }
    }

    /** Получить безопасную ссылку на последний кадр для одного окна. */
    fun acquireLatest(): FrameLease? {
        if (closed.get()) return null
        while (true) {
            val held = latest.get() ?: return null
            val lease = held.tryAcquire() ?: continue
            if (latest.get() === held && !closed.get()) {
                lastAcquiredRevision.accumulateAndGet(held.revision) { current, update -> maxOf(current, update) }
                return lease
            }
            lease.close()
            if (closed.get()) return null
        }
    }

    /**
     * Опрос кадра сеткой точек под [energy].
     *
     * Яркость считается упрощённо — сумма трёх каналов, делённая на четыре вместо
     * взвешенной формулы. Здесь нужна не колориметрия, а «стало светлее или темнее»
     * и «сильно ли поменялось», а для этого разница между приближением и точной
     * яркостью не значит ничего.
     */
    private fun sampleEnergy(frameWidth: Int, frameHeight: Int) {
        val grid = lumaGrid
        var index = 0
        var row = 0
        while (row < ENERGY_GRID_H) {
            val y = (frameHeight.toLong() * (2 * row + 1) / (2 * ENERGY_GRID_H)).toInt()
            var column = 0
            while (column < ENERGY_GRID_W) {
                val x = (frameWidth.toLong() * (2 * column + 1) / (2 * ENERGY_GRID_W)).toInt()
                val offset = (y * frameWidth + x) * 4
                val b = pixels[offset].toInt() and 0xFF
                val g = pixels[offset + 1].toInt() and 0xFF
                val r = pixels[offset + 2].toInt() and 0xFF
                grid[index++] = (b + g + g + r) shr 2
                column++
            }
            row++
        }
        energy.accept(grid, System.nanoTime())
    }

    // ---- чёрная рамка, вшитая в кадр ----
    //
    // Раздачи приходят с полосами внутри самой картинки, а не по краям области:
    // замерено на живых потоках — 864x482, 1280x738, 1920x1090, то есть ни один не
    // 16:9. Разницу видно как чёрную полосу сверху видео, и никакой режим «вписать»
    // её не уберёт: для проигрывателя эти строки — часть изображения.
    //
    // Поэтому рамка ИЩЕТСЯ, а рисуется потом только внутренняя часть кадра.
    // Осторожность здесь важнее полноты: лучше не обрезать ничего, чем срезать
    // макушку. Отсюда три правила — рамка считается по НЕСКОЛЬКИМ кадрам и берётся
    // самая узкая из найденных; кадр, у которого «рамка» больше [MAX_BORDER_SHARE],
    // в замер не идёт вовсе (это затемнение или заставка, а не полоса); и пока
    // не набралось [BORDER_SAMPLES] пригодных кадров, кадр рисуется целиком.

    private fun resetBorderSearch(frameWidth: Int, frameHeight: Int) {
        contentRect = intArrayOf(0, 0, frameWidth, frameHeight)
        borderSamplesLeft = BORDER_SAMPLE_WINDOW
        borderSamplesTaken = 0
        borderTop = Int.MAX_VALUE
        borderBottom = Int.MAX_VALUE
        borderLeft = Int.MAX_VALUE
        borderRight = Int.MAX_VALUE
    }

    private fun sampleBorders(frameWidth: Int, frameHeight: Int) {
        borderSamplesLeft--
        val maxRows = (frameHeight * MAX_BORDER_SHARE).toInt()
        val maxCols = (frameWidth * MAX_BORDER_SHARE).toInt()
        val top = darkRunFromTop(frameWidth, maxRows)
        val bottom = darkRunFromBottom(frameWidth, frameHeight, maxRows)
        val left = darkRunFromLeft(frameWidth, frameHeight, maxCols)
        val right = darkRunFromRight(frameWidth, frameHeight, maxCols)
        // Упёрлись в потолок — кадр тёмный целиком, судить по нему нельзя.
        if (top >= maxRows || bottom >= maxRows || left >= maxCols || right >= maxCols) return
        borderTop = minOf(borderTop, top)
        borderBottom = minOf(borderBottom, bottom)
        borderLeft = minOf(borderLeft, left)
        borderRight = minOf(borderRight, right)
        borderSamplesTaken++
        if (borderSamplesTaken < BORDER_SAMPLES && borderSamplesLeft > 0) return
        borderSamplesLeft = 0
        val x = borderLeft
        val y = borderTop
        val w = frameWidth - borderLeft - borderRight
        val h = frameHeight - borderTop - borderBottom
        if (w < frameWidth / 2 || h < frameHeight / 2) return
        contentRect = intArrayOf(x, y, w, h)
        if (borderTop + borderBottom + borderLeft + borderRight > 0) {
            PlayerDiagnostics.log(
                "frames.border",
                "top=$borderTop; bottom=$borderBottom; left=$borderLeft; right=$borderRight; " +
                    "content=${w}x$h of ${frameWidth}x$frameHeight",
            )
        }
    }

    /** Строка целиком тёмная? Считается по каждому восьмому пикселю — полосе
     *  всё равно, а работы в восемь раз меньше. */
    private fun rowIsDark(row: Int, frameWidth: Int): Boolean {
        var i = row * frameWidth * 4
        val step = PIXEL_STEP * 4
        val end = i + frameWidth * 4
        while (i < end) {
            if (!pixelIsDark(i)) return false
            i += step
        }
        return true
    }

    private fun columnIsDark(column: Int, frameWidth: Int, frameHeight: Int): Boolean {
        var row = 0
        while (row < frameHeight) {
            if (!pixelIsDark((row * frameWidth + column) * 4)) return false
            row += PIXEL_STEP
        }
        return true
    }

    /** BGRA: сравниваем все три канала, чтобы густо-синий фон не сошёл за чёрный. */
    private fun pixelIsDark(offset: Int): Boolean =
        (pixels[offset].toInt() and 0xFF) <= DARK_LEVEL &&
            (pixels[offset + 1].toInt() and 0xFF) <= DARK_LEVEL &&
            (pixels[offset + 2].toInt() and 0xFF) <= DARK_LEVEL

    private fun darkRunFromTop(frameWidth: Int, limit: Int): Int {
        var n = 0
        while (n < limit && rowIsDark(n, frameWidth)) n++
        return n
    }

    private fun darkRunFromBottom(frameWidth: Int, frameHeight: Int, limit: Int): Int {
        var n = 0
        while (n < limit && rowIsDark(frameHeight - 1 - n, frameWidth)) n++
        return n
    }

    private fun darkRunFromLeft(frameWidth: Int, frameHeight: Int, limit: Int): Int {
        var n = 0
        while (n < limit && columnIsDark(n, frameWidth, frameHeight)) n++
        return n
    }

    private fun darkRunFromRight(frameWidth: Int, frameHeight: Int, limit: Int): Int {
        var n = 0
        while (n < limit && columnIsDark(frameWidth - 1 - n, frameWidth, frameHeight)) n++
        return n
    }

    /**
     * Смена медиа: старый кадр не должен «просвечивать» в новой серии.
     *
     * [newScene] = false, когда кадр отпускают по другой причине — ушли на другую
     * вкладку, свернули окно. Серия при этом ТА ЖЕ, и сбрасывать вместе с кадром
     * накопленное представление о ней нельзя: по живому журналу видно, во что это
     * обходится — после возврата замер минуту пишет `known=false` и чат всё это время
     * судит по расписанию вместо кадра.
     */
    fun clear(newScene: Boolean = true) {
        synchronized(callbackLock) {
            latest.getAndSet(null)?.releasePublisher()
            revision.incrementAndGet()
            if (width > 0 && height > 0) resetBorderSearch(width, height)
            // Прошлый кадр к новой серии отношения не имеет: без сброса переход дал
            // бы ложную огромную смену плана.
            if (newScene) energy.reset() else energy.forgetLastFrame()
        }
    }

    /**
     * Track seek ownership without blanking the retained immutable raster image.
     * Each callback copies native pixels while holding callbackLock. The last
     * valid frame therefore stays safe during a vout rebuild, and the single
     * landing callback of a paused decoder must not be discarded.
     */
    fun pauseForSeek(): Long {
        val token = seekPauseToken.incrementAndGet()
        // Do not suspend publication: close()/generation ownership protects teardown.
        PlayerDiagnostics.log("frames.seekPause", "generation=$generation; token=$token")
        return token
    }

    /** Resume capture only when no newer seek superseded [token]. */
    fun resumeAfterSeek(token: Long): Boolean {
        if (closed.get() || seekPauseToken.get() != token) return false
        PlayerDiagnostics.log("frames.seekResume", "generation=$generation; token=$token")
        return true
    }

    /** Больше ни один callback этого поколения не публикует кадры. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        enabled = false
        synchronized(callbackLock) {
            latest.getAndSet(null)?.releasePublisher()
            revision.incrementAndGet()
            pixels = ByteArray(0)
            info = null
        }
        PlayerDiagnostics.log("frames.closed", "generation=$generation")
    }

    private companion object {
        /** Канал темнее этого считается чёрным (сжатие никогда не даёт ровный ноль). */
        const val DARK_LEVEL = 22

        /** Каждый N-й пиксель строки/столбца — этого хватает, чтобы поймать содержимое. */
        const val PIXEL_STEP = 8

        /** Сколько пригодных кадров нужно, чтобы зафиксировать рамку. */
        const val BORDER_SAMPLES = 8

        /** Сколько кадров подряд на это отводится (≈ секунда при 24 к/с). */
        const val BORDER_SAMPLE_WINDOW = 24

        /** Больше этой доли стороны — уже не полоса, а тёмная сцена. */
        const val MAX_BORDER_SHARE = 0.2

        fun releaseReference(image: Image, references: AtomicInteger) {
            val left = references.decrementAndGet()
            check(left >= 0) { "Video frame released more than once" }
            if (left == 0) runCatching { image.close() }
        }
    }
}
