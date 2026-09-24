package com.aniblaze.desktop.player

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * Стенд для «улучшения качества»: работает ли оно на самом деле и не стало ли хуже.
 *
 * Повод завести стенд: шейдер резкости однажды был ПОЛНЫМ ТОЖДЕСТВОМ — соседние
 * пиксели брались со смещением `1/ширина`, тогда как координаты в SkSL-фильтре
 * пиксельные, и все «соседи» оказывались тем же пикселем. На глаз это неотличимо от
 * работающего фильтра; поймал только замер, когда все силы дали побайтово равный
 * результат.
 *
 * Схема замера повторяет реальную работу плеера: эталон ужимается в 1.5 раза (как
 * 720p, попавшее в окно 1080p) и восстанавливается обратно тем самым фильтром
 * Catmull-Rom, которым рисует [ComposeVideoSurface]; затем поверх накладывается CAS.
 * Сравниваем с эталоном по PSNR и SSIM.
 */
class VideoEnhanceBenchTest {

    // ---- подготовка кадра ----

    /**
     * Кадр в духе аниме: плоские заливки, жёсткие тёмные контуры, тонкие линии и
     * пологий градиент неба. Именно на таком содержимом CAS и должен себя проявлять
     * — на случайном шуме любая резкость только вредит.
     */
    private fun referenceFrame(width: Int = 960, height: Int = 540): Image {
        val surface = Surface.makeRasterN32Premul(width, height)
        val canvas = surface.canvas
        canvas.clear(0xFF1B2A4A.toInt())
        val paint = Paint()
        // Небо: пологий градиент из горизонтальных полос.
        for (y in 0 until height / 2) {
            val t = y.toFloat() / (height / 2)
            val r = (60 + 120 * t).toInt()
            val g = (90 + 110 * t).toInt()
            val b = (170 + 60 * t).toInt()
            paint.color = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            canvas.drawRect(Rect.makeXYWH(0f, y.toFloat(), width.toFloat(), 1f), paint)
        }
        // Плоские заливки — то, что CAS обязан НЕ трогать.
        paint.color = 0xFFF2C14E.toInt()
        canvas.drawRect(Rect.makeXYWH(0f, height * 0.5f, width.toFloat(), height * 0.5f), paint)
        paint.color = 0xFFE85D3D.toInt()
        canvas.drawCircle(width * 0.72f, height * 0.34f, height * 0.16f, paint)
        // Контуры и тонкие линии — то, ради чего всё затевалось.
        paint.color = 0xFF14161C.toInt()
        for (i in 0 until 22) {
            val x = width * 0.04f + i * (width * 0.042f)
            canvas.drawRect(Rect.makeXYWH(x, height * 0.55f, 2f, height * 0.4f), paint)
        }
        for (i in 0 until 10) {
            val y = height * 0.06f + i * (height * 0.035f)
            canvas.drawRect(Rect.makeXYWH(width * 0.05f, y, width * 0.30f, 1f), paint)
        }
        paint.strokeWidth = 3f
        paint.color = 0xFF14161C.toInt()
        canvas.drawCircle(width * 0.72f, height * 0.34f, height * 0.16f, paint.also { it.mode = org.jetbrains.skia.PaintMode.STROKE })
        return surface.makeImageSnapshot()
    }

    /** Ужать и растянуть обратно заданным фильтром — как это делает плеер. */
    private fun roundTrip(source: Image, sampling: SamplingMode, scale: Float = 1.5f): Image {
        val smallW = (source.width / scale).roundToInt()
        val smallH = (source.height / scale).roundToInt()
        val small = Surface.makeRasterN32Premul(smallW, smallH)
        small.canvas.drawImageRect(
            source,
            Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
            Rect.makeWH(smallW.toFloat(), smallH.toFloat()),
            SamplingMode.LINEAR, null, true,
        )
        val shrunk = small.makeImageSnapshot()
        val big = Surface.makeRasterN32Premul(source.width, source.height)
        big.canvas.drawImageRect(
            shrunk,
            Rect.makeWH(smallW.toFloat(), smallH.toFloat()),
            Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
            sampling, null, true,
        )
        return big.makeImageSnapshot()
    }

    /**
     * Сохранить кадр картинкой, чтобы посмотреть глазами.
     *
     * Числа отвечают на вопрос «работает ли», но не на вопрос «нравится ли». Дамп
     * включается свойством, чтобы обычный прогон тестов не сорил файлами:
     *
     *     gradlew :desktop-app:test --tests *VideoEnhanceBench* -Denhance.dump=<папка>
     */
    private fun dump(image: Image, name: String) {
        val dir = System.getProperty("enhance.dump")?.takeIf { it.isNotBlank() } ?: return
        val out = java.io.File(dir).also { it.mkdirs() }
        val data = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)
            ?: error("PNG не закодировался")
        java.io.File(out, "$name.png").writeBytes(data.bytes)
        println("дамп: ${java.io.File(out, "$name.png").absolutePath}")
    }

    /** Прогнать картинку через шейдер CAS выбранной силы. */
    private fun enhanced(source: Image, level: VideoEnhance.Level): Image {
        val filter = VideoEnhance.imageFilter(level) ?: return source
        val surface = Surface.makeRasterN32Premul(source.width, source.height)
        val paint = Paint().also { it.imageFilter = filter }
        surface.canvas.drawImage(source, 0f, 0f, paint)
        return surface.makeImageSnapshot()
    }

    // ---- метрики ----

    private fun pixels(image: Image): IntArray {
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
        val info = ImageInfo(image.width, image.height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
        val bytes = bitmap.readPixels(info, image.width * 4, 0, 0) ?: error("битмап не отдал пиксели")
        return IntArray(image.width * image.height) { i ->
            val b = bytes[i * 4].toInt() and 0xFF
            val g = bytes[i * 4 + 1].toInt() and 0xFF
            val r = bytes[i * 4 + 2].toInt() and 0xFF
            // Яркость по Rec.601 — метрики считаем по ней, как принято.
            ((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)
        }
    }

    private fun psnr(a: IntArray, b: IntArray): Double {
        var mse = 0.0
        for (i in a.indices) {
            val d = (a[i] - b[i]).toDouble()
            mse += d * d
        }
        mse /= a.size
        if (mse <= 0.0) return 99.0
        return 10.0 * log10(255.0 * 255.0 / mse)
    }

    /** SSIM по окнам 8x8 — тот же способ, каким мерили при подборе силы. */
    private fun ssim(a: IntArray, b: IntArray, width: Int, height: Int): Double {
        val c1 = (0.01 * 255) * (0.01 * 255)
        val c2 = (0.03 * 255) * (0.03 * 255)
        var total = 0.0
        var windows = 0
        var y = 0
        while (y + 8 <= height) {
            var x = 0
            while (x + 8 <= width) {
                var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
                for (dy in 0 until 8) for (dx in 0 until 8) {
                    val i = (y + dy) * width + (x + dx)
                    val va = a[i].toDouble(); val vb = b[i].toDouble()
                    sa += va; sb += vb; saa += va * va; sbb += vb * vb; sab += va * vb
                }
                val n = 64.0
                val ma = sa / n; val mb = sb / n
                val va = saa / n - ma * ma; val vb = sbb / n - mb * mb
                val cov = sab / n - ma * mb
                total += ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2))
                windows++
                x += 8
            }
            y += 8
        }
        return if (windows == 0) 0.0 else total / windows
    }

    /** Средний модуль градиента — прямая мера «насколько картинка резкая». */
    private fun sharpness(p: IntArray, width: Int, height: Int): Double {
        var sum = 0.0
        var n = 0
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            val gx = (p[i + 1] - p[i - 1]).toDouble()
            val gy = (p[i + width] - p[i - width]).toDouble()
            sum += sqrt(gx * gx + gy * gy)
            n++
        }
        return sum / n
    }

    // ---- собственно проверки ----

    @Test
    fun `шейдер резкости реально меняет пиксели, а не возвращает кадр как есть`() {
        val reference = referenceFrame()
        val soft = roundTrip(reference, SamplingMode.CATMULL_ROM)
        val base = pixels(soft)
        val light = pixels(enhanced(soft, VideoEnhance.Level.LIGHT))
        val strong = pixels(enhanced(soft, VideoEnhance.Level.STRONG))

        val changedLight = base.indices.count { base[it] != light[it] }
        val changedStrong = base.indices.count { base[it] != strong[it] }
        println("изменено пикселей: умеренно=$changedLight сильно=$changedStrong из ${base.size}")

        // Именно это и было сломано: фильтр возвращал кадр байт в байт.
        assertTrue("«Умеренно» ничего не меняет — шейдер снова тождество", changedLight > base.size / 100)
        assertTrue("«Сильно» ничего не меняет — шейдер снова тождество", changedStrong > base.size / 100)
        // И силы должны отличаться друг от друга, иначе uniform не доезжает.
        assertTrue("сила не влияет: light и strong совпадают", light.indices.any { light[it] != strong[it] })
    }

    @Test
    fun `Catmull-Rom при растягивании лучше билинейной`() {
        val reference = referenceFrame()
        val ref = pixels(reference)
        val w = reference.width
        val h = reference.height

        val results = listOf(
            "билинейная" to SamplingMode.LINEAR,
            "Mitchell" to SamplingMode.MITCHELL,
            "Catmull-Rom" to SamplingMode.CATMULL_ROM,
        ).map { (label, mode) ->
            val p = pixels(roundTrip(reference, mode))
            Triple(label, psnr(ref, p), ssim(ref, p, w, h))
        }
        results.forEach { (label, psnr, ssim) ->
            println("%-12s PSNR %.2f  SSIM %.4f".format(label, psnr, ssim))
        }
        val linear = results.first { it.first == "билинейная" }
        val catmull = results.first { it.first == "Catmull-Rom" }
        assertTrue(
            "Catmull-Rom не лучше билинейной: %.2f против %.2f".format(catmull.second, linear.second),
            catmull.second > linear.second,
        )
        assertTrue(
            "Catmull-Rom проигрывает по SSIM: %.4f против %.4f".format(catmull.third, linear.third),
            catmull.third > linear.third,
        )
    }

    @Test
    fun `CAS поднимает резкость и не заваливает её в перешарп`() {
        val reference = referenceFrame()
        val ref = pixels(reference)
        val w = reference.width
        val h = reference.height
        val soft = roundTrip(reference, SamplingMode.CATMULL_ROM)

        val refSharp = sharpness(ref, w, h)
        dump(reference, "0-эталон")
        val rows = VideoEnhance.Level.entries.map { level ->
            val image = enhanced(soft, level)
            dump(image, "${level.ordinal + 1}-${level.key}")
            val p = pixels(image)
            arrayOf<Any>(level.label, psnr(ref, p), ssim(ref, p, w, h), sharpness(p, w, h))
        }
        println("эталон: резкость %.3f".format(refSharp))
        rows.forEach { (label, psnr, ssim, sharp) ->
            println(
                "%-10s PSNR %.2f  SSIM %.4f  резкость %.3f (%.1f%% от эталона)".format(
                    label, psnr, ssim, sharp, (sharp as Double) / refSharp * 100,
                ),
            )
        }
        val off = rows.first { it[0] == VideoEnhance.Level.OFF.label }
        val strong = rows.first { it[0] == VideoEnhance.Level.STRONG.label }
        val light = rows.first { it[0] == VideoEnhance.Level.LIGHT.label }

        // Растянутый кадр всегда мягче эталона — резкость обязана расти к нему.
        assertTrue(
            "CAS не поднял резкость: %.3f против %.3f".format(strong[3] as Double, off[3] as Double),
            (strong[3] as Double) > (off[3] as Double),
        )
        assertTrue(
            "«Умеренно» не мягче «Сильно» — шкала перепутана",
            (light[3] as Double) < (strong[3] as Double),
        )
        // Перешарп — это резкость ВЫШЕ эталонной: линии обведены ореолом.
        assertTrue(
            "перешарп: %.3f при эталонной %.3f".format(strong[3] as Double, refSharp),
            (strong[3] as Double) <= refSharp * 1.05,
        )
    }
}
