package com.aniblaze.desktop.player

import com.aniblaze.aggregator.model.StreamVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.test.Test

/**
 * Восстановление после мёртвого раздающего.
 *
 * Повод — жалоба «при переключении серии обрыв потока» и журнал 18.08 за ней. Разбор
 * дал две отдельные поломки, и здесь сторожится каждая:
 *
 * 1. Список вариантов подменялся ДОКЛЕИВАНИЕМ 1080p от AniLibria через ~180 мс после
 *    старта, а на этом списке висел ключ remember для выбранного качества. Новый ключ —
 *    новый объект состояния; цикл опроса продолжал писать в старый, и решение «перехожу
 *    на 480p» уходило в никуда. Наружу — «Подготовка потока…» на 111 секунд, пока
 *    libVLC не сдавалась сама.
 *
 * 2. Перебор шёл по КАЧЕСТВАМ, а все качества Anixart лежат на одном solodcdn. Когда
 *    молчит хозяин, это девять секунд на каждое качество впустую — при том что в том же
 *    списке лежал живой адрес AniLibria.
 */
class DeadHostFallbackTest {

    private val anixart720 = StreamVariant("720p", "https://p12.solodcdn.com/s/m/abc:2026081902/720.mp4:hls:manifest.m3u8")
    private val anixart480 = StreamVariant("480p", "https://p12.solodcdn.com/s/m/abc:2026081902/480.mp4:hls:manifest.m3u8")
    private val anixart360 = StreamVariant("360p", "https://p12.solodcdn.com/s/m/abc:2026081902/360.mp4:hls:manifest.m3u8")
    private val libria1080 = StreamVariant("1080p · AniLibria", "https://cache.libria.fun/videos/media/ts/9223/5/1080/xyz.m3u8")

    private val anixartOnly = listOf(anixart720, anixart480, anixart360)
    private val withHiRes = anixartOnly + libria1080

    // ---- опознание потока ------------------------------------------------------

    @Test
    fun `доклеивание hi-res не считается сменой потока`() {
        // Ровно тот случай, что ломал плеер: список другой, поток тот же.
        assertEquals(variantsIdentity(anixartOnly), variantsIdentity(withHiRes))
    }

    @Test
    fun `смена источника считается сменой потока`() {
        // А тут выбор качества обязан сброситься: играем совсем другое.
        val fromLibria = listOf(libria1080, anixart720)
        assertNotEquals(variantsIdentity(anixartOnly), variantsIdentity(fromLibria))
    }

    @Test
    fun `пустой список опознаётся без падения`() {
        assertEquals("", variantsIdentity(emptyList()))
    }

    // ---- выбор следующей попытки ------------------------------------------------

    @Test
    fun `ноль кадров уводит на другой хост мимо качеств того же`() {
        val next = nextVariantAfterFailure(withHiRes, anixart720.url, neverStarted = true)
        assertEquals(
            "после молчания solodcdn перебирать его же качества — по девять секунд впустую",
            libria1080.url,
            next?.url,
        )
    }

    @Test
    fun `обычная ошибка перебирает качества по порядку`() {
        // Тут хозяин ответил, значит мертво КОНКРЕТНОЕ качество — сосед рядом обычно жив.
        val next = nextVariantAfterFailure(withHiRes, anixart720.url, neverStarted = false)
        assertEquals(anixart480.url, next?.url)
    }

    @Test
    fun `без чужого хоста берётся что есть`() {
        // Отказаться пробовать вовсе хуже, чем потратить девять секунд.
        val next = nextVariantAfterFailure(anixartOnly, anixart720.url, neverStarted = true)
        assertEquals(anixart480.url, next?.url)
    }

    @Test
    fun `последний вариант не даёт следующего`() {
        assertNull(nextVariantAfterFailure(withHiRes, libria1080.url, neverStarted = true))
        assertNull(nextVariantAfterFailure(anixartOnly, anixart360.url, neverStarted = false))
    }

    @Test
    fun `неизвестный адрес начинает перебор сначала`() {
        // Адрес мог прийти с маркером «#h=720» или из прошлого списка — в обоих случаях
        // ответ «пробовать нечего» был бы неверным.
        val next = nextVariantAfterFailure(withHiRes, "https://elsewhere.example/none.m3u8", neverStarted = false)
        assertEquals(anixart720.url, next?.url)
    }

    // ---- какое качество открывать ------------------------------------------------

    /** Высота из метки: «720p» → 720, «1080p · AniLibria» → 1080, «Auto» → 0. */
    private val heightOf: (String) -> Int = { Regex("""\d{3,4}""").find(it)?.value?.toIntOrNull() ?: 0 }

    @Test
    fun `пока источник отдаёт своё лучшее, навязывать нечего`() {
        assertNull(forcedQualityFor(verifiedQuality = "720p", sourceVariants = anixartOnly, heightOf = heightOf))
    }

    @Test
    fun `отбракованное качество не воскрешается сохранённым выбором`() {
        // Проверка адресов признала живым только 480p, а сохранено «720p» — открывать
        // по сохранённому значит подарить мёртвому раздающему девять секунд.
        val reordered = listOf(anixart480, anixart720, anixart360)
        assertEquals("480p", forcedQualityFor(verifiedQuality = "480p", sourceVariants = reordered, heightOf = heightOf))
    }

    @Test
    fun `качества с РАЗНЫХ зеркал остаются одним источником`() {
        // Anixart раскладывает качества одной серии по p12/p14 — делить список по хосту
        // нельзя, иначе собственный максимум считается по половине вариантов и
        // отбраковка не опознаётся вовсе.
        val split = listOf(
            StreamVariant("480p", "https://p14.solodcdn.com/s/m/abc/480.mp4:hls:manifest.m3u8"),
            StreamVariant("720p", "https://p12.solodcdn.com/s/m/abc/720.mp4:hls:manifest.m3u8"),
        )
        assertEquals("480p", forcedQualityFor(verifiedQuality = "480p", sourceVariants = split, heightOf = heightOf))
    }

    @Test
    fun `нераспознанное качество не навязывается`() {
        // «Auto» высоты не даёт, и делать из этого вывод «нас понизили» нельзя.
        assertNull(forcedQualityFor(verifiedQuality = "Auto", sourceVariants = anixartOnly, heightOf = heightOf))
    }

    // ---- сторож «поток не начался» -------------------------------------------------

    private val nineSeconds = 9_000_000_000L
    private val overdue = nineSeconds + 1_000_000_000L

    @Test
    fun `продолжение с середины судится по движению, а не по величине позиции`() {
        // Первая поломка: серия продолжается с 13:44, libVLC применяет `:start-time` до
        // открытия входа и сразу рапортует 824718 — по проверке «позиция больше нуля»
        // это «уже играет», сторож молчит навсегда, лестница не запускается, а зритель
        // смотрит на «Подготовка потока…» и «Загрузка… 0 %».
        assertTrue(
            "поток, стоящий на точке возобновления, обязан считаться неначавшимся",
            playbackNeverStarted(overdue, position = 824_718, firstPosition = 824_718),
        )
    }

    @Test
    fun `поток, начавшийся НЕ ТАМ, где просили, живой`() {
        // Вторая поломка, и она дороже первой. libVLC не применила `:start-time` и пошла
        // с 1:27 вместо 13:35 — воспроизведение при этом идёт. Правило «позиция должна
        // уйти дальше ЗАПРОШЕННОЙ» объявляло его мёртвым, лестница шла перебирать
        // озвучки и роняла по экземпляру libVLC на каждую. Точка отсчёта — то, что
        // libVLC доложила о СЕБЕ, а не то, что у неё просили.
        assertFalse(
            "живое воспроизведение с чужого места — всё равно живое",
            playbackNeverStarted(overdue, position = 96_000, firstPosition = 87_000),
        )
    }

    @Test
    fun `сдвинувшаяся позиция снимает подозрение`() {
        assertFalse(playbackNeverStarted(overdue, position = 833_718, firstPosition = 824_718))
    }

    @Test
    fun `запуск с нуля работает как прежде`() {
        assertTrue(playbackNeverStarted(overdue, position = 0, firstPosition = 0))
        assertFalse(playbackNeverStarted(overdue, position = 1, firstPosition = 0))
    }

    @Test
    fun `без первого ответа libVLC приговора нет`() {
        // Опрос ещё ни разу не ответил: судить не по чему, этим занят другой сторож
        // (commandThreadStuck).
        assertFalse(playbackNeverStarted(overdue, position = 824_718, firstPosition = -1))
    }

    @Test
    fun `до истечения срока приговора нет`() {
        // Девять секунд — измеренный потолок честного открытия потока; раньше судить
        // нечего даже при полностью стоящей позиции.
        assertFalse(playbackNeverStarted(nineSeconds - 1, position = 824_718, firstPosition = 824_718))
    }

    @Test
    fun `хост читается и с маркером качества`() {
        // Плеер хранит адрес с хвостом «#h=720»; если бы разбор об него спотыкался,
        // хост выходил бы пустым и любой вариант казался бы «чужим».
        assertEquals("p12.solodcdn.com", hostOfUrl(anixart720.url + "#h=720"))
        assertEquals("cache.libria.fun", hostOfUrl(libria1080.url))
        assertEquals("", hostOfUrl("не адрес вовсе"))
    }
}
