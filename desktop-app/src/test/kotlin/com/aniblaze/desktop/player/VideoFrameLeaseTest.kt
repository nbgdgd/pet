package com.aniblaze.desktop.player

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VideoFrameLeaseTest {
    private fun frame(width: Int = 4, height: Int = 4): ByteBuffer =
        ByteBuffer.allocateDirect(width * height * 4).apply { position(capacity()); rewind() }

    @Test
    fun `два окна независимо удерживают один кадр`() {
        val sink = VideoFrameSink(7)
        sink.accept(frame(), 4, 4)
        val main = assertNotNull(sink.acquireLatest())
        val pip = assertNotNull(sink.acquireLatest())
        assertEquals(main.revision, pip.revision)

        main.close()
        assertEquals(4, pip.image.width)

        // После закрытия sink кадр остаётся валиден у PiP до конца его lease.
        sink.close()
        assertEquals(4, pip.image.width)
        assertNull(sink.acquireLatest())
        pip.close()
    }

    @Test
    fun `непрочитанный кадр создает backpressure`() {
        val sink = VideoFrameSink()
        sink.accept(frame(), 4, 4)
        val firstRevision = sink.revision.get()
        sink.accept(frame(), 4, 4)
        assertEquals(firstRevision, sink.revision.get())
        sink.close()
    }

    @Test
    fun `перемотка сохраняет кадр и принимает кадр приземления без таймера`() {
        val sink = VideoFrameSink()
        val firstSeek = sink.pauseForSeek()
        sink.accept(frame(), 4, 4)
        assertNotNull(sink.acquireLatest()).close()

        val secondSeek = sink.pauseForSeek()
        assertEquals(false, sink.resumeAfterSeek(firstSeek))
        sink.accept(frame(), 4, 4)
        assertNotNull(sink.acquireLatest()).close()

        assertEquals(true, sink.resumeAfterSeek(secondSeek))
        sink.accept(frame(), 4, 4)
        assertNotNull(sink.acquireLatest())?.close()
        sink.close()
    }

    @Test
    fun `публикация два потребителя и закрытие не гоняются за native image`() {
        val sink = VideoFrameSink(11)
        val start = CountDownLatch(1)
        val producerDone = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)

        val producer = Thread {
            runCatching {
                start.await()
                repeat(1_000) {
                    sink.accept(frame(16, 9), 16, 9)
                    Thread.yield()
                }
            }.onFailure { failure.compareAndSet(null, it) }
            producerDone.set(true)
            sink.close()
        }
        val consumers = List(2) {
            Thread {
                runCatching {
                    start.await()
                    while (!producerDone.get()) {
                        sink.acquireLatest()?.use { lease ->
                            assertEquals(16, lease.image.width)
                            assertEquals(9, lease.image.height)
                            Thread.yield()
                        }
                    }
                }.onFailure { failure.compareAndSet(null, it) }
            }
        }

        producer.start()
        consumers.forEach(Thread::start)
        start.countDown()
        producer.join()
        consumers.forEach(Thread::join)

        failure.get()?.let { throw it }
        assertNull(sink.acquireLatest())
    }
}
