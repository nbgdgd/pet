package com.aniblaze.desktop.player

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Поиск чёрной рамки, вшитой в кадр.
 *
 * Живые раздачи приходят в 864x482, 1280x738, 1920x1090 — ни один размер не 16:9,
 * и лишние строки видны как полоса над видео. Здесь проверяется, что рамка находится
 * ровно там, где она есть, и НЕ находится там, где её нет.
 */
class VideoFrameSinkBorderTest {

    /** Кадр BGRA: [barTop]/[barBottom] строк чёрных, остальное — заданный цвет. */
    private fun frame(
        width: Int,
        height: Int,
        barTop: Int = 0,
        barBottom: Int = 0,
        barLeft: Int = 0,
        barRight: Int = 0,
        luma: Int = 200,
    ): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until height) {
            val darkRow = y < barTop || y >= height - barBottom
            for (x in 0 until width) {
                val dark = darkRow || x < barLeft || x >= width - barRight
                val value = (if (dark) 0 else luma).toByte()
                buffer.put(value); buffer.put(value); buffer.put(value); buffer.put(0)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun feed(sink: VideoFrameSink, width: Int, height: Int, times: Int, build: () -> ByteBuffer) {
        repeat(times) {
            sink.accept(build(), width, height)
            // Имитируем окно, забравшее latest кадр. Lease закрывается, но ссылка
            // производителя живёт до публикации следующего.
            sink.acquireLatest()?.close()
        }
    }

    @Test
    fun `вшитая полоса сверху находится и обрезается`() {
        val sink = VideoFrameSink()
        feed(sink, 1280, 738, 10) { frame(1280, 738, barTop = 18) }
        assertEquals(intArrayOf(0, 18, 1280, 720).toList(), sink.contentRect.toList())
    }

    @Test
    fun `полосы сверху и снизу обрезаются обе`() {
        val sink = VideoFrameSink()
        feed(sink, 1920, 1090, 10) { frame(1920, 1090, barTop = 5, barBottom = 5) }
        assertEquals(intArrayOf(0, 5, 1920, 1080).toList(), sink.contentRect.toList())
    }

    @Test
    fun `кадр без рамки остаётся целым`() {
        val sink = VideoFrameSink()
        feed(sink, 1280, 720, 10) { frame(1280, 720) }
        assertEquals(intArrayOf(0, 0, 1280, 720).toList(), sink.contentRect.toList())
    }

    @Test
    fun `затемнение в начале серии не считается рамкой`() {
        val sink = VideoFrameSink()
        // Первые кадры чёрные целиком (fade in), потом картинка без рамки.
        feed(sink, 1280, 720, 6) { frame(1280, 720, luma = 0) }
        feed(sink, 1280, 720, 10) { frame(1280, 720) }
        assertEquals(intArrayOf(0, 0, 1280, 720).toList(), sink.contentRect.toList())
    }

    @Test
    fun `тёмная сцена посреди замера не расширяет рамку`() {
        val sink = VideoFrameSink()
        // Полоса 18 строк есть всегда; в одном кадре тёмными оказались ещё сто строк.
        feed(sink, 1280, 738, 3) { frame(1280, 738, barTop = 18) }
        feed(sink, 1280, 738, 1) { frame(1280, 738, barTop = 118) }
        feed(sink, 1280, 738, 8) { frame(1280, 738, barTop = 18) }
        assertEquals(intArrayOf(0, 18, 1280, 720).toList(), sink.contentRect.toList())
    }

    @Test
    fun `боковые полосы тоже обрезаются`() {
        val sink = VideoFrameSink()
        feed(sink, 1440, 1080, 10) { frame(1440, 1080, barLeft = 180, barRight = 180) }
        assertEquals(intArrayOf(180, 0, 1080, 1080).toList(), sink.contentRect.toList())
    }

    @Test
    fun `смена размера кадра запускает поиск заново`() {
        val sink = VideoFrameSink()
        feed(sink, 1280, 738, 10) { frame(1280, 738, barTop = 18) }
        assertEquals(18, sink.contentRect[1])
        feed(sink, 1920, 1080, 10) { frame(1920, 1080) }
        assertEquals(intArrayOf(0, 0, 1920, 1080).toList(), sink.contentRect.toList())
    }
}
