package com.aniblaze.desktop.player

import com.aniblaze.aggregator.model.OpeningRange
import com.aniblaze.aggregator.model.TitleComment
import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Поведение симулятора живого чата.
 *
 * Проверяется ровно то, что зритель может заметить: чат замирает на паузе, не
 * повторяет одну и ту же фразу, оживает на драке, не показывает реплики чужих серий
 * и не повторяет сам себя после перемотки назад. Разметка сюда не входит вовсе — вся
 * логика живёт вне композиции именно ради этого.
 */
class ChatEngineTest {

    private var nextId = 1L

    private fun comment(
        text: String,
        author: String = "Аноним",
        spoiler: Boolean = false,
        episode: Int = 0,
    ) = TitleComment(
        id = nextId++,
        author = author,
        avatar = "https://example.invalid/a.png",
        message = text,
        timestamp = nextId,
        votes = 0,
        isSpoiler = spoiler,
        episode = episode,
    )

    private fun engine(
        real: List<TitleComment> = emptyList(),
        viewers: Int = 150,
        intensity: ChatIntensity = ChatIntensity.NORMAL,
        episode: Int = 0,
        seed: Long = 42L,
        episodeKey: String = "title:1",
        alwaysQuiet: Boolean = false,
    ) = ChatEngine(
        episodeKey = episodeKey,
        sourceSize = real.size,
        viewers = viewers,
        intensity = intensity,
        alwaysQuiet = alwaysQuiet,
        real = danmakuPicks(real, episode, limit = 220),
        seed = seed,
    )

    /** Прогоняет [seconds] секунд просмотра и возвращает всё, что чат написал. */
    private fun run(
        engine: ChatEngine,
        seconds: Int,
        lengthMs: Long = 24 * 60_000L,
        startMs: Long = 0L,
        playing: Boolean = true,
        opening: OpeningRange? = null,
        ending: OpeningRange? = null,
        energy: SceneSample = SceneSample.UNKNOWN,
    ): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        val ticks = seconds * 1000 / CHAT_TICK_MS.toInt()
        repeat(ticks) { tick ->
            if (!playing) return@repeat
            engine.accumulate(CHAT_TICK_MS)
            out += engine.poll(
                positionMs = startMs + tick * CHAT_TICK_MS,
                lengthMs = lengthMs,
                opening = opening,
                ending = ending,
                energy = energy,
                tickMs = CHAT_TICK_MS,
            )
        }
        return out
    }

    // ---- часы и пауза ----

    @Test
    fun `на паузе новых сообщений нет`() {
        // Пауза выражается ровно тем, что часы не двигают: accumulate не зовут.
        val engine = engine()
        run(engine, seconds = 60)
        val before = engine.messagesIssued
        assertTrue("за минуту чат обязан что-то написать", before > 0)
        repeat(200) {
            engine.poll(60_000L, 24 * 60_000L, null, null, SceneSample.UNKNOWN, CHAT_TICK_MS)
        }
        assertEquals("на паузе лента стоит", before, engine.messagesIssued)
    }

    @Test
    fun `чат начинается почти сразу`() {
        // Пустая панель первые полминуты выглядит как сломанная функция.
        assertTrue(run(engine(), seconds = 5).isNotEmpty())
    }

    // ---- темп ----

    @Test
    fun `плотность меняет темп`() {
        val quiet = run(engine(intensity = ChatIntensity.QUIET), seconds = 300).size
        val normal = run(engine(intensity = ChatIntensity.NORMAL), seconds = 300).size
        val storm = run(engine(intensity = ChatIntensity.STORM), seconds = 300).size
        assertTrue("тихо=$quiet обычно=$normal", quiet < normal)
        assertTrue("обычно=$normal шторм=$storm", normal < storm)
    }

    @Test
    fun `зрителей больше — лента быстрее, но не пропорционально`() {
        val few = run(engine(viewers = 10), seconds = 300).size
        val many = run(engine(viewers = 1000), seconds = 300).size
        assertTrue("мало=$few много=$many", few < many)
        // Логарифм, а не пропорция: сотня зрителей против десяти не даёт десятикратной
        // ленты, иначе читать её было бы невозможно.
        assertTrue("рост должен быть сдержанным: $few → $many", many < few * 4)
    }

    @Test
    fun `на драке чат ускоряется`() {
        val fight = SceneSample(
            motion = 0.10f, motionBase = 0.03f, brightness = 0.5f, brightnessBase = 0.5f,
            cutsPerMinute = 30f, cutSeen = false, frames = 5_000L,
        )
        val calm = run(engine(seed = 7L), seconds = 240).size
        val hot = run(engine(seed = 7L), seconds = 240, energy = fight).size
        assertTrue("спокойно=$calm драка=$hot", hot > calm * 1.5)
    }

    @Test
    fun `всегда тихо — темп не меняется ни на чём`() {
        val fight = SceneSample(
            motion = 0.10f, motionBase = 0.03f, brightness = 0.5f, brightnessBase = 0.5f,
            cutsPerMinute = 30f, cutSeen = false, frames = 5_000L,
        )
        val quietCalm = run(engine(seed = 3L, alwaysQuiet = true), seconds = 240).size
        val quietFight = run(engine(seed = 3L, alwaysQuiet = true), seconds = 240, energy = fight).size
        assertEquals("режим на то и «всегда тихо»: разгона нет", quietCalm, quietFight)
        // Настроение при этом ПО-ПРЕЖНЕМУ определяется — молчит только темп.
        val engine = engine(seed = 3L, alwaysQuiet = true)
        run(engine, seconds = 60, energy = fight)
        assertEquals(SceneMood.FIGHT, engine.moodNow)
    }

    @Test
    fun `всегда тихо не мешает плотности`() {
        val quiet = run(engine(intensity = ChatIntensity.QUIET, alwaysQuiet = true), seconds = 300).size
        val storm = run(engine(intensity = ChatIntensity.STORM, alwaysQuiet = true), seconds = 300).size
        assertTrue("ползунок обязан работать и здесь: $quiet против $storm", quiet < storm)
    }

    // ---- повторы ----

    @Test
    fun `чат не повторяет одну и ту же фразу подряд`() {
        // Главная претензия к любому такому генератору — «пишет одно и то же».
        val messages = run(engine(intensity = ChatIntensity.STORM), seconds = 600)
        assertTrue("сообщений слишком мало для проверки: ${messages.size}", messages.size > 100)
        val window = 25
        messages.forEachIndexed { index, message ->
            val recent = messages.subList(maxOf(0, index - window), index).map { it.text }
            assertTrue(
                "«${message.text}» повторилась внутри окна из $window сообщений",
                message.text !in recent,
            )
        }
    }

    @Test
    fun `набор реплик действительно большой`() {
        // Защита от тихой деградации: если кто-то вычистит наборы, тест это заметит
        // раньше, чем пользователь.
        assertTrue("фраз всего ${ChatLines.totalPhrases()}", ChatLines.totalPhrases() >= 300)
    }

    @Test
    fun `за длинную серию используется много разных фраз`() {
        val texts = run(engine(intensity = ChatIntensity.LIVELY), seconds = 20 * 60).map { it.text }
        val unique = texts.toSet().size
        assertTrue("уникальных $unique из ${texts.size}", unique >= 80)
    }

    // ---- перемотка ----

    @Test
    fun `после перемотки назад чат пишет другое`() {
        val first = engine(seed = 11L)
        val opening = run(first, seconds = 120)
        first.onSeek()
        val again = run(first, seconds = 120, startMs = 0L)
        val overlap = opening.map { it.text }.toSet() intersect again.map { it.text }.toSet()
        assertNotEquals(
            "после возврата назад последовательность обязана отличаться",
            opening.map { it.text },
            again.map { it.text },
        )
        assertTrue(
            "пересечение $overlap слишком велико: ${overlap.size} из ${again.size}",
            overlap.size < again.size,
        )
    }

    @Test
    fun `перемотка меняет и расписание настроений`() {
        val before = arcMood(300_000L, 1_400_000L, "title:5", seekEpoch = 0)
        val moods = (1..8).map { arcMood(300_000L, 1_400_000L, "title:5", seekEpoch = it) }
        assertTrue("одна и та же секунда после перемоток обязана давать разное", moods.any { it != before })
    }

    @Test
    fun `расписание повторяемо, пока не перематывали`() {
        assertEquals(
            arcMood(420_000L, 1_400_000L, "title:3", 0),
            arcMood(420_000L, 1_400_000L, "title:3", 0),
        )
    }

    @Test
    fun `у разных серий расписание разное`() {
        val one = (0..30).map { arcMood(it * 35_000L, 1_400_000L, "title:1", 0) }
        val two = (0..30).map { arcMood(it * 35_000L, 1_400_000L, "title:2", 0) }
        assertNotEquals(one, two)
    }

    // ---- настроение по кадру ----

    private fun mood(
        energy: SceneSample,
        calmForMs: Long = 0L,
        fightForMs: Long = 0L,
        darkForMs: Long = 0L,
        positionMs: Long = 400_000L,
        opening: OpeningRange? = null,
        ending: OpeningRange? = null,
    ) = measuredMood(positionMs, opening, ending, energy, calmForMs, fightForMs, darkForMs)

    private fun sample(
        motion: Float = 0.03f,
        motionBase: Float = 0.03f,
        brightness: Float = 0.5f,
        brightnessBase: Float = 0.5f,
        cutsPerMinute: Float = 6f,
        cutSeen: Boolean = false,
        frames: Long = 3_000L,
    ) = SceneSample(motion, motionBase, brightness, brightnessBase, cutsPerMinute, cutSeen, frames)

    @Test
    fun `опенинг и эндинг определяются точно`() {
        val opening = OpeningRange(60_000L, 150_000L)
        val ending = OpeningRange(1_300_000L, 1_390_000L)
        assertEquals(SceneMood.OPENING, mood(SceneSample.UNKNOWN, positionMs = 90_000L, opening = opening, ending = ending))
        assertEquals(SceneMood.ENDING, mood(SceneSample.UNKNOWN, positionMs = 1_350_000L, opening = opening, ending = ending))
        assertNull(
            "между ними доказывать нечем",
            mood(SceneSample.UNKNOWN, positionMs = 600_000L, opening = opening, ending = ending),
        )
    }

    @Test
    fun `драка опознаётся по движению и смене планов`() {
        val fight = sample(motion = 0.09f, cutsPerMinute = 24f)
        assertTrue("признак обязан сработать", fightNow(fight, SceneMood.CALM))
        assertEquals(SceneMood.FIGHT, mood(fight, fightForMs = 4_000L))
    }

    @Test
    fun `одиночный всплеск дракой не объявляется`() {
        // ЭТО И БЫЛО ГЛАВНОЙ ОШИБКОЙ. По живому журналу замер прыгал от x0,03 до
        // x15,18 — аниме держит рисунок по два-три кадра, и между всплесками разница
        // ровно ноль. Одного превышения хватало, чтобы объявить драку в разговоре.
        val spike = sample(motion = 0.09f, cutsPerMinute = 24f)
        assertNull("полсекунды — это ещё не драка", mood(spike, fightForMs = 500L))
        assertEquals(SceneMood.FIGHT, mood(spike, fightForMs = 3_500L))
    }

    @Test
    fun `у драки разные пороги на вход и на выход`() {
        // Без гистерезиса значение, гуляющее вокруг единственного порога, щёлкало бы
        // настроение туда-сюда по нескольку раз в секунду.
        val middling = sample(motion = 0.045f, cutsPerMinute = 12f)
        assertTrue("в драку с такого не входят", !fightNow(middling, SceneMood.CALM))
        assertTrue("но из неё с такого и не выходят", fightNow(middling, SceneMood.FIGHT))
    }

    @Test
    fun `быстрая, но однопланная сцена дракой не считается`() {
        // Панорама или падающий снег двигаются, но планы не режутся — это не бой.
        // Разговорный монтаж на живом просмотре даёт 12–15 склеек в минуту, и порог
        // обязан такое пропускать мимо.
        val panorama = sample(motion = 0.09f, cutsPerMinute = 14f)
        assertTrue(!fightNow(panorama, SceneMood.CALM))
    }

    @Test
    fun `тёмная и неподвижная сцена читается как тихая`() {
        val quiet = sample(motion = 0.008f, brightness = 0.18f, brightnessBase = 0.42f, cutsPerMinute = 1f)
        assertTrue(darkNow(quiet, SceneMood.CALM))
        assertEquals(SceneMood.SAD, mood(quiet, darkForMs = 9_000L))
        assertNull("двух секунд темноты мало", mood(quiet, darkForMs = 2_000L))
    }

    @Test
    fun `резкая склейка после затишья — поворот`() {
        val cut = sample(cutSeen = true, cutsPerMinute = 3f)
        assertEquals(SceneMood.TWIST, mood(cut, calmForMs = 20_000L))
        assertNull("склейка сразу после прошлого события поворотом не является", mood(cut, calmForMs = 0L))
    }

    @Test
    fun `склейка в разговоре поворотом не считается`() {
        // В диалоге режут между собеседниками постоянно — 12–15 раз в минуту
        // (замерено). Без порога на редкость склеек поворотом объявлялся бы каждый
        // второй ответ.
        val talk = sample(cutSeen = true, cutsPerMinute = 14f)
        assertNull(mood(talk, calmForMs = 30_000L))
    }

    @Test
    fun `без кадров ничего не выдумывается`() {
        // Нативный вывод VLC кадры до нас не доводит. Тогда честный ответ — «не знаю»,
        // и решение принимает расписание, а не выдуманный замер.
        val cold = sample(motion = 0.5f, motionBase = 0.01f, brightness = 0.05f, cutsPerMinute = 90f, cutSeen = true, frames = 3L)
        assertNull(mood(cold, calmForMs = 30_000L, fightForMs = 30_000L, darkForMs = 30_000L))
        assertTrue(!fightNow(cold, SceneMood.CALM))
        assertTrue(!darkNow(cold, SceneMood.CALM))
    }

    @Test
    fun `расписание не объявляет весёлым тёмный застывший кадр`() {
        // Смешное по кадру не определяется, но кадр может догадку ОПРОВЕРГНУТЬ:
        // «АХАХА» над тихой тёмной сценой — ровно то, что читается как «оно
        // неправильно определяет».
        val gloom = sample(motion = 0.002f, brightness = 0.2f, brightnessBase = 0.5f)
        val bright = sample(motion = 0.03f, brightness = 0.55f, brightnessBase = 0.5f)
        var suppressed = 0
        var allowed = 0
        // Длина 0 = неизвестна: тогда подъём к развязке не включается и мешать
        // выборке не может.
        for (bucket in 0 until 400) {
            val at = bucket * 35_000L
            if (arcMood(at, 0L, "title:7", 0, SceneSample.UNKNOWN) != SceneMood.FUNNY) continue
            if (arcMood(at, 0L, "title:7", 0, gloom) == SceneMood.FUNNY) allowed++ else suppressed++
            assertEquals(SceneMood.FUNNY, arcMood(at, 0L, "title:7", 0, bright))
        }
        assertTrue("проверять нечего: догадка ни разу не выпала", suppressed + allowed > 10)
        assertEquals("на тёмном застывшем кадре весёлого быть не должно", 0, allowed)
    }

    // ---- настоящие комментарии ----

    @Test
    fun `настоящие реплики попадают в чат от своих авторов`() {
        val real = (1..40).map { comment("Развёрнутое мнение о серии номер $it, вполне осмысленное", author = "Зритель$it") }
        val messages = run(engine(real = real, intensity = ChatIntensity.STORM), seconds = 600)
        val fromReal = messages.filter { it.real }
        assertTrue("настоящих реплик не оказалось вовсе", fromReal.isNotEmpty())
        assertTrue("автор обязан быть настоящим", fromReal.all { it.nick.startsWith("Зритель") })
        assertTrue("аватарка обязана дойти", fromReal.all { it.avatar.isNotBlank() })
    }

    @Test
    fun `настоящая реплика не показывается дважды`() {
        val real = (1..40).map { comment("Развёрнутое мнение о серии номер $it, вполне осмысленное") }
        val shown = run(engine(real = real, intensity = ChatIntensity.STORM), seconds = 900).filter { it.real }
        assertEquals("повторов быть не должно", shown.size, shown.map { it.text }.toSet().size)
    }

    @Test
    fun `спойлеры скрываются а реплики чужих серий в чат не попадают`() {
        // Будущая серия удаляется целиком. Спойлер текущей серии остаётся в чате,
        // но панель получает явный флаг и не раскрывает текст без клика.
        val real = listOf(
            comment("Вот тут на шестой серии всё и объяснилось наконец", episode = 6),
            comment("В конце он оказывается братом, я в шоке был", spoiler = true),
            comment("Первая серия затягивает с самого начала", episode = 1),
        )
        val messages = run(engine(real = real, episode = 1, intensity = ChatIntensity.STORM), seconds = 600)
        val realMessages = messages.filter { it.real }
        val realTexts = realMessages.map { it.text }
        assertTrue("чужая серия просочилась: $realTexts", realTexts.none { it.contains("шестой") })
        assertTrue(
            "спойлер должен остаться скрываемым сообщением: $realMessages",
            realMessages.any { it.text.contains("братом") && it.potentialSpoiler },
        )
    }

    @Test
    fun `без настоящих комментариев чат всё равно живой`() {
        // Обсуждение может не загрузиться или быть пустым — это не повод показывать
        // пустую панель.
        val messages = run(engine(real = emptyList()), seconds = 300)
        assertTrue(messages.isNotEmpty())
        assertTrue("настоящих взяться неоткуда", messages.none { it.real })
    }

    // ---- живость ----

    @Test
    fun `зрители отвечают друг другу`() {
        val messages = run(engine(intensity = ChatIntensity.STORM), seconds = 900)
        val replies = messages.filter { it.replyTo != null }
        assertTrue("ответов не оказалось вовсе", replies.isNotEmpty())
        // Адресат обязан быть тем, кто ДЕЙСТВИТЕЛЬНО недавно писал: «@никто» ломает
        // ощущение живого чата сильнее, чем отсутствие ответов.
        replies.forEach { reply ->
            val index = messages.indexOf(reply)
            val before = messages.subList(maxOf(0, index - 20), index).map { it.nick }
            assertTrue("@${reply.replyTo} никому не адресован", reply.replyTo in before)
        }
    }

    @Test
    fun `никто не отвечает сам себе`() {
        val messages = run(engine(intensity = ChatIntensity.STORM), seconds = 900)
        assertTrue(messages.none { it.replyTo != null && it.replyTo == it.nick })
    }

    @Test
    fun `в зале встречаются разные характеры`() {
        val messages = run(engine(intensity = ChatIntensity.STORM), seconds = 900)
        val badges = messages.mapNotNull { it.badge }.toSet()
        assertTrue("значков персон не видно вовсе: $badges", badges.size >= 2)
        val nicks = messages.filter { !it.real }.map { it.nick }.toSet()
        assertTrue("ников слишком мало: ${nicks.size}", nicks.size >= 30)
    }

    @Test
    fun `эмоции доходят до сообщений`() {
        val messages = run(engine(intensity = ChatIntensity.STORM), seconds = 900)
        val withEmote = messages.count { message ->
            message.text.split(' ').any { ChatEmotes.find(it) != null }
        }
        assertTrue("ни одной эмоции за пятнадцать минут", withEmote > 0)
    }

    @Test
    fun `цвет ника постоянен у одного и того же зрителя`() {
        val messages = run(engine(intensity = ChatIntensity.STORM), seconds = 900).filter { !it.real }
        val colors = messages.groupBy { it.nick }.mapValues { (_, list) -> list.map { it.color }.toSet() }
        colors.forEach { (nick, set) ->
            assertEquals("у «$nick» цвет прыгает", 1, set.size)
        }
    }

    @Test
    fun `число зрителей дышит, но остаётся рядом с настройкой`() {
        val engine = engine(viewers = 500)
        val seen = mutableSetOf<Int>()
        repeat(60) {
            run(engine, seconds = 10)
            seen += engine.viewersNow()
        }
        assertTrue("счётчик замер", seen.size > 1)
        assertTrue("уехал слишком далеко: ${seen.min()}..${seen.max()}", seen.all { it in 400..600 })
    }

    // ---- смена серии ----

    @Test
    fun `новая серия — новый чат`() {
        val real = listOf(comment("Общее мнение о тайтле, вполне осмысленное"))
        val first = ChatSessions.obtain("title:1", real, 150, ChatIntensity.NORMAL, false, seed = 1L)
        val same = ChatSessions.obtain("title:1", real, 150, ChatIntensity.NORMAL, false, seed = 1L)
        val next = ChatSessions.obtain("title:2", real, 150, ChatIntensity.NORMAL, false, seed = 1L)
        assertTrue("пересбор экрана обязан вернуть ТУ ЖЕ сессию", same === first)
        assertTrue("смена серии обязана дать новую", next !== first)
    }

    @Test
    fun `подкрутка настроек не сбрасывает чат`() {
        // ЖИВОЙ ЖУРНАЛ: пока ползунок «зрителей» тащили от 150 к 320, чат
        // пересоздавался восемьдесят пять раз подряд. Ни плотность, ни число зрителей
        // не меняют ни очередь, ни отбор — только темп и подпись.
        val real = listOf(comment("Общее мнение о тайтле, вполне осмысленное"))
        val first = ChatSessions.obtain("title:9", real, 150, ChatIntensity.QUIET, false, seed = 1L)
        val louder = ChatSessions.obtain("title:9", real, 150, ChatIntensity.STORM, false, seed = 1L)
        val crowded = ChatSessions.obtain("title:9", real, 900, ChatIntensity.STORM, false, seed = 1L)
        assertTrue("плотность — не повод начинать заново", louder === first)
        assertTrue("число зрителей — тоже не повод", crowded === first)
        assertEquals("но применяться настройка обязана сразу", 900, first.viewers)
    }

    @Test
    fun `догрузка сезонного пула не сбрасывает чат`() {
        val firstPage = listOf(comment("Первая настоящая реплика сезона"))
        val secondPage = firstPage + comment("Вторая настоящая реплика сезона")
        val first = ChatSessions.obtain("season:episode-5", firstPage, 150, ChatIntensity.NORMAL, false, seed = 3L)
        val grown = ChatSessions.obtain("season:episode-5", secondPage, 150, ChatIntensity.NORMAL, false, seed = 3L)
        assertTrue("дозагрузка не должна очищать ленту", grown === first)
        assertEquals(2, grown.realUsable)
    }

    @Test
    fun `режим только реальные не создаёт шаблонные реплики`() {
        val real = (1..8).map { comment("Настоящая реплика сезона номер $it") }
        val engine = engine(real = real, intensity = ChatIntensity.STORM)
        engine.setRealOnly(true)
        val shown = run(engine, seconds = 60)
        assertTrue("настоящие реплики не появились", shown.isNotEmpty())
        assertTrue("в строгий режим попала генерация", shown.all { it.real })
    }

    // ---- мелочи, которые видит глаз ----

    @Test
    fun `счётчик зрителей склоняется по-русски`() {
        assertEquals("1 зритель", formatViewers(1))
        assertEquals("2 зрителя", formatViewers(2))
        assertEquals("5 зрителей", formatViewers(5))
        assertEquals("11 зрителей", formatViewers(11))
        assertEquals("14 зрителей", formatViewers(14))
        assertEquals("21 зритель", formatViewers(21))
        assertEquals("1${NBSP}204 зрителя", formatViewers(1204))
    }

    @Test
    fun `у обычного хода серии подписи настроения нет`() {
        // Плашка «ЭКШЕН» имеет смысл только тогда, когда происходит что-то особенное.
        assertNull(moodLabel(SceneMood.CALM))
        assertEquals("ОПЕНИНГ", moodLabel(SceneMood.OPENING))
    }
}
