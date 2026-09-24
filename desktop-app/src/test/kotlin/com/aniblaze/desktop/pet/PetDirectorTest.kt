package com.aniblaze.desktop.pet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Питомец говорит редко: обычные наблюдения — по кулдауну, важное — вне очереди. */
class PetDirectorTest {
    private val hour = 60 * 60_000L

    /** Тишина по существу: молчит либо просто поёрзал — это не реакция на событие. */
    private fun assertQuiet(decision: PetDirector.Decision, message: String = "ожидалась тишина") =
        assertTrue(decision.say == null || decision.say in PetPhrases.FIDGET, "$message, а было: ${decision.say}")

    private fun scene(
        position: Long = 5 * 60_000L,
        duration: Long = 24 * 60_000L,
        playing: Boolean = true,
        buffering: Boolean = false,
        available: Int = 12,
        total: Int = 12,
        episode: Int = 3,
        streak: Int = 1,
        session: Long = 10 * 60_000L,
        paused: Long = 0L,
        resumed: Long = 0L,
        now: Long = 1_000_000L,
        title: String = "",
        titleWatched: Long = 0L,
        watched: Int = 0,
        days: Int = -1,
        rewatch: Boolean = false,
        opened: Long = 0L,
        speed: Float = 1f,
        rewinds: Int = 0,
        forwards: Int = 0,
        voice: String = "",
        sourceSwitched: Boolean = false,
        qualityForced: Boolean = false,
        hour24: Int = -1,
        genres: String = "",
        rating: Double = 0.0,
        ratingMax: Double = 0.0,
        fullscreen: Boolean = false,
        completion: PetEvent? = null,
        measured: Long = 0L,
        hasNext: Boolean = false,
        endingConfirmed: Boolean = false,
        recent: List<String> = emptyList(),
    ) = PetDirector.Scene(
        episode = episode, positionMs = position, durationMs = duration, playing = playing,
        buffering = buffering, episodesAvailable = available, episodesTotal = total,
        streak = streak, sessionMs = session, pausedForMs = paused, resumedFromMs = resumed, now = now,
        titleName = title, titleWatchedMs = titleWatched, watchedEpisodes = watched, daysSinceLastWatch = days,
        rewatch = rewatch, openedAtMs = opened, speed = speed, rewinds = rewinds, forwards = forwards,
        voiceName = voice, sourceSwitched = sourceSwitched, qualityForced = qualityForced,
        hourOfDay = hour24, genres = genres, rating = rating, ratingMax = ratingMax, fullscreen = fullscreen,
        completion = completion, episodeWatchedMs = measured, hasNext = hasNext, endingConfirmed = endingConfirmed,
        recentGenres = recent,
    )

    @Test fun `обычный просмотр - молчит и спокоен`() {
        val d = PetDirector().decide(scene())
        assertNull(d.say)
        assertEquals(PetMood.IDLE, d.mood)
    }

    @Test fun `одно наблюдение и кулдаун на следующие`() {
        val director = PetDirector()
        val first = director.decide(scene(position = 41 * 60_000L, measured = 41 * 60_000L, duration = 60 * 60_000L, now = 0))
        assertEquals("Смотришь уже 41 мин", first.say)
        // Сразу следом — молчание, даже если повод есть.
        assertNull(director.decide(scene(position = 52 * 60_000L, duration = 60 * 60_000L, now = 60_000L)).say)
        // После кулдауна — можно снова.
        val later = director.decide(
            scene(position = 52 * 60_000L, duration = 60 * 60_000L, now = PetDirector.CHATTER_COOLDOWN_MS + 1),
        )
        assertEquals("Осталось 8 мин до конца серии", later.say)
    }

    @Test fun `марафон - устаёт и говорит об этом один раз`() {
        val director = PetDirector()
        val d = director.decide(scene(streak = 4, session = 3 * hour, now = 0))
        assertEquals("Это уже 4 серия подряд", d.say)
        assertEquals(PetMood.WAITING, d.mood)
        // В пределах кулдауна — молчок.
        assertNull(director.decide(scene(streak = 4, session = 3 * hour, now = 60_000)).say)
        // После кулдауна можно сказать ДРУГОЕ, но не повторить про серии подряд.
        val next = director.decide(scene(streak = 4, session = 3 * hour, now = PetDirector.CHATTER_COOLDOWN_MS + 1)).say
        assertTrue(next == null || !next.contains("подряд"), "повтор про серии подряд: $next")
    }

    @Test fun `буферизация и сон идут вне кулдауна`() {
        val director = PetDirector()
        director.decide(scene(position = 41 * 60_000L, duration = 60 * 60_000L, now = 0))
        val buffering = director.decide(scene(buffering = true, now = 1000))
        assertEquals("Видео загружается…", buffering.say)
        assertEquals(PetMood.WAITING, buffering.mood)
        val sleep = director.decide(scene(playing = false, paused = PetDirector.SLEEP_AFTER_MS, now = 2000))
        assertEquals(PetMood.SLEEPY, sleep.mood)
        assertNotNull(sleep.say)
    }

    @Test fun `возврат после паузы - продолжаем с позиции`() {
        val d = PetDirector().decide(scene(resumed = 14 * 60_000L + 32_000L))
        assertEquals("Продолжаем с 14:32", d.say)
        assertEquals(PetMood.GREETING, d.mood)
    }

    @Test fun `хвост без таймингов и отсутствие следующей серии не означают финал`() {
        val ordinary = PetDirector().decide(scene(episode = 3, position = 23 * 60_000L, duration = 24 * 60_000L))
        assertNull(ordinary.say)
        assertEquals(PetMood.IDLE, ordinary.mood)

        val last = PetDirector().decide(
            scene(episode = 5, available = 5, total = 12, position = 23 * 60_000L, duration = 24 * 60_000L),
        )
        assertNull(last.say)
        assertEquals(PetMood.IDLE, last.mood)
    }

    @Test fun `конец серии, догнали и финал сезона различаются`() {
        val end = { ep: Int, avail: Int, total: Int, event: PetEvent ->
            PetDirector().decide(scene(episode = ep, available = avail, total = total, position = 24 * 60_000L, duration = 24 * 60_000L, completion = event))
        }
        assertEquals(PetMood.HAPPY, end(3, 12, 12, PetEvent.EPISODE_DONE).mood)
        val caught = end(5, 5, 12, PetEvent.CAUGHT_UP)
        assertTrue(caught.say.orEmpty().contains("Ждём"), "догнали: ${caught.say}")
        assertEquals(PetMood.WAITING, caught.mood)
        val season = end(12, 12, 12, PetEvent.SEASON_DONE)
        assertEquals("Ещё один сезон в копилке", season.say)
        assertEquals(PetMood.CELEBRATING, season.mood)
    }

    @Test fun `перемотка не повторяет уже сказанное про серию`() {
        val director = PetDirector()
        val first = director.decide(scene(episode = 4, position = 24 * 60_000L, duration = 24 * 60_000L, now = 0, completion = PetEvent.EPISODE_DONE))
        assertNotNull(first.say)
        // Отмотали назад и снова доехали до конца — второй раз молчит.
        director.decide(scene(episode = 4, position = 10 * 60_000L, duration = 24 * 60_000L, now = 1000))
        val again = director.decide(
            scene(episode = 4, position = 24 * 60_000L, duration = 24 * 60_000L, now = PetDirector.CHATTER_COOLDOWN_MS * 2, completion = PetEvent.EPISODE_DONE),
        )
        assertNull(again.say)
        // Новая серия — счётчик сказанного обнуляется.
        val next = director.decide(
            scene(episode = 5, position = 24 * 60_000L, duration = 24 * 60_000L, now = PetDirector.CHATTER_COOLDOWN_MS * 3, completion = PetEvent.EPISODE_DONE),
        )
        assertNotNull(next.say)
    }

    @Test fun `пауза - садится молча, долгая пауза - спит`() {
        val director = PetDirector()
        val paused = director.decide(scene(playing = false, paused = 3_000))
        // Цифр про тайтл нет — откликается словами, и это одна из фраз паузы.
        assertEquals(PetDef.CLAUDE.personality.pause, paused.say)
        assertEquals(PetMood.PAUSED, paused.mood)
        assertEquals(PetAction.SIT, petActionFor(paused.mood))
        val asleep = director.decide(scene(playing = false, paused = PetDirector.SLEEP_AFTER_MS, now = 5000))
        assertEquals(PetMood.SLEEPY, asleep.mood)
        assertEquals(PetAction.SLEEP, petActionFor(asleep.mood))
    }

    @Test fun `начало серии - короткое приветствие, один раз`() {
        val director = PetDirector()
        val hello = director.decide(scene(position = 20_000, now = 0))
        assertNotNull(hello.say)
        assertEquals(PetMood.GREETING, hello.mood)
        assertNull(director.decide(scene(position = 40_000, now = 1000)).say)
    }

    @Test fun `кулдаун настраивается`() {
        val director = PetDirector(chatterCooldownMs = 2 * 60_000L)
        director.decide(scene(position = 41 * 60_000L, duration = 60 * 60_000L, now = 0))
        assertNull(director.decide(scene(position = 52 * 60_000L, duration = 60 * 60_000L, now = 60_000L)).say)
        assertNotNull(director.decide(scene(position = 52 * 60_000L, duration = 60 * 60_000L, now = 2 * 60_000L + 1)).say)
        assertEquals(5 * 60_000L, PetDirector.CHATTER_COOLDOWN_MS)
    }

    @Test fun `каждое настроение - своя анимация, и у обоих персонажей она есть`() {
        // Ключевые состояния различимы глазом: покой, радость, грусть, сон, пауза, прыжок, удивление.
        val key = listOf(PetMood.IDLE, PetMood.HAPPY, PetMood.SAD, PetMood.SLEEPY, PetMood.PAUSED, PetMood.EXCITED, PetMood.SURPRISED)
        assertEquals(key.size, key.map { petActionFor(it) }.toSet().size, "ключевые настроения делят одну анимацию")
        PetDef.ALL.forEach { pet ->
            PetMood.entries.forEach { mood ->
                val clip = pet.clip(petActionFor(mood))
                assertTrue(clip.frames.isNotEmpty(), "${pet.id}/$mood без кадров")
            }
            // Сон и грусть — разные ряды атласа, чтобы их было видно.
            assertTrue(pet.clip(PetAction.SLEEP).frames.toList() != pet.clip(PetAction.SAD).frames.toList(), "${pet.id}: сон = грусть")
        }
    }

    // ---- память ----------------------------------------------------------------

    @Test fun `приветствие помнит - пересмотр, долгий перерыв, недосмотренная серия`() {
        val rewatch = PetDirector().decide(scene(position = 20_000, title = "Наруто", rewatch = true))
        assertEquals("Снова «Наруто»? Устраиваюсь поудобнее", rewatch.say)
        val gap = PetDirector().decide(scene(position = 20_000, title = "Наруто", days = 12))
        assertEquals("Мы не смотрели «Наруто» 12 дней. Продолжаем!", gap.say)
        val gap1 = PetDirector().decide(scene(position = 20_000, title = "Наруто", days = 21))
        assertEquals("Мы не смотрели «Наруто» 21 день. Продолжаем!", gap1.say)
        // Открыли на 13-й минуте 24-минутной серии — «осталось 11 минут с прошлого просмотра».
        val resumed = PetDirector().decide(scene(position = 13 * 60_000L + 5_000, duration = 24 * 60_000L, opened = 13 * 60_000L))
        assertEquals("Осталось 11 мин с прошлого просмотра", resumed.say)
        // Вчера — не повод для «мы не смотрели», обычное приветствие.
        assertNotNull(PetDirector().decide(scene(position = 20_000, title = "Наруто", days = 1)).say)
    }

    @Test fun `финал сезона - с реальным временем, без времени - коротко`() {
        val withTime = PetDirector().decide(
            scene(episode = 12, available = 12, total = 12, position = 24 * 60_000L, duration = 24 * 60_000L,
                title = "Наруто", titleWatched = (4 * 60 + 18) * 60_000L, completion = PetEvent.SEASON_DONE),
        )
        assertEquals("Сезон досмотрен. Время за «Наруто»: 4 ч 18 мин", withTime.say)
        assertEquals(PetMood.CELEBRATING, withTime.mood)
        val noTime = PetDirector().decide(scene(episode = 12, available = 12, total = 12, position = 24 * 60_000L, duration = 24 * 60_000L, completion = PetEvent.SEASON_DONE))
        assertEquals("Ещё один сезон в копилке", noTime.say)
        val next = PetDirector().decide(scene(episode = 3, available = 12, total = 12, position = 24 * 60_000L, duration = 24 * 60_000L, completion = PetEvent.EPISODE_DONE))
        assertEquals("Серия 3 досмотрена", next.say)
        val caught = PetDirector().decide(scene(episode = 5, available = 5, total = 12, position = 24 * 60_000L, duration = 24 * 60_000L, completion = PetEvent.CAUGHT_UP))
        assertEquals("А следующую нам пока не дали. Ждём новую серию!", caught.say)
    }

    // ---- действия пользователя ---------------------------------------------------

    @Test fun `перемотки назад - удивление и «Ещё разок?» один раз, вперёд - прыжок с репликой`() {
        val d = PetDirector()
        val back = d.decide(scene(rewinds = 3))
        assertEquals("Ещё разок?", back.say)
        assertEquals(PetMood.SURPRISED, back.mood)
        assertNull(d.decide(scene(rewinds = 4, now = 2_000_000L)).say)
        val fwd = PetDirector().decide(scene(forwards = 3))
        assertEquals("Перематываем вперёд", fwd.say)
        assertEquals(PetMood.EXCITED, fwd.mood)
        assertEquals(PetAction.JUMP, petActionFor(fwd.mood))
    }

    @Test fun `скорость x2 - «не успеваю моргать» и прыжок, x1 - тишина`() {
        val fast = PetDirector().decide(scene(speed = 2f))
        assertEquals("Смотрим быстрее", fast.say)
        assertEquals(PetMood.EXCITED, fast.mood)
        assertNull(PetDirector().decide(scene(speed = 1f)).say)
    }

    @Test fun `смена озвучки - прислушивается, первая озвучка серии - не событие`() {
        val d = PetDirector()
        assertNull(d.decide(scene(voice = "AniLibria", now = 0)).say)
        val switched = d.decide(scene(voice = "AniDUB", now = 1_000))
        assertEquals("Слушаю: AniDUB", switched.say)
        assertEquals(PetMood.LISTEN, switched.mood)
        assertNull(d.decide(scene(voice = "AniDUB", now = 2_000)).say)
    }

    @Test fun `источник переключён и качество снижено - по разу`() {
        val d = PetDirector()
        assertEquals("Переключил источник, продолжаем", d.decide(scene(sourceSwitched = true)).say)
        assertQuiet(d.decide(scene(sourceSwitched = true, now = 2_000_000L)))
        val q = PetDirector()
        assertEquals("Качество снижено из-за соединения", q.decide(scene(qualityForced = true)).say)
        assertQuiet(q.decide(scene(qualityForced = true, now = 2_000_000L)))
    }

    @Test fun `карточка на паузе - только из известного, и заново на каждую паузу`() {
        val d = PetDirector()
        val card = d.decide(scene(playing = false, paused = 3_000, total = 12, watched = 7, titleWatched = (2 * 60 + 41) * 60_000L))
        assertEquals("Просмотрено 7/12 · 2 ч 41 мин", card.say)
        assertNull(d.decide(scene(playing = false, paused = 4_000, total = 12, watched = 7, now = 1_001_000L)).say)
        // Поиграли — новая пауза показывает карточку снова.
        d.decide(scene(playing = true, now = 1_002_000L))
        assertNotNull(d.decide(scene(playing = false, paused = 3_000, total = 12, watched = 7, now = 1_010_000L)).say)
        // Ничего не известно — цифр нет, откликается словами и один раз на паузу.
        val quiet = PetDirector()
        assertEquals(PetDef.CLAUDE.personality.pause, quiet.decide(scene(playing = false, paused = 3_000, total = 0, watched = 0)).say)
        assertNull(quiet.decide(scene(playing = false, paused = 4_000, total = 0, watched = 0, now = 1_001_000L)).say)
    }

    @Test fun `в покое иногда ёрзает молча, не чаще раза в четыре минуты`() {
        val d = PetDirector()
        // Приветствие выключаем позицией за окном.
        assertEquals(PetMood.IDLE, d.decide(scene(position = 5 * 60_000L, now = 0)).mood, "первый взгляд — ещё не ёрзает")
        assertEquals(PetMood.IDLE, d.decide(scene(position = 5 * 60_000L, now = PetDirector.FIDGET_EVERY_MS - 1)).mood)
        val fidget = d.decide(scene(position = 5 * 60_000L, now = PetDirector.FIDGET_EVERY_MS))
        assertEquals(PetMood.FIDGET, fidget.mood)
        assertNull(fidget.say, "тихое движение не обходит настройку частоты комментариев")
        assertEquals(PetMood.IDLE, d.decide(scene(position = 5 * 60_000L, now = PetDirector.FIDGET_EVERY_MS + PetDirector.FIDGET_MS + 1)).mood)
    }

    // ---- финал сезона: просьба оценить ------------------------------------------

    @Test fun `финал сезона просит оценку, конец обычной серии - нет`() {
        val end = scene(episode = 12, available = 12, total = 12, position = 24 * 60_000L, duration = 24 * 60_000L, completion = PetEvent.SEASON_DONE)
        val season = PetDirector().decide(end)
        assertEquals(PetAsk.RATE_SEASON, season.ask)
        assertEquals(PetMood.CELEBRATING, season.mood)
        val ordinary = PetDirector().decide(
            scene(episode = 3, available = 12, total = 12, position = 24 * 60_000L, duration = 24 * 60_000L, completion = PetEvent.EPISODE_DONE),
        )
        assertNull(ordinary.ask)
        // Хост удерживает карточку; повторное событие не открывает её заново.
        val director = PetDirector()
        director.decide(end)
        val again = director.decide(end.copy(now = end.now + 1_000))
        assertNull(again.ask)
        assertNull(again.say, "реплика про финал звучит один раз")
    }

    // ---- новые реакции на действия ------------------------------------------------

    @Test fun `мотнули опенинг и титры - по разу за серию`() {
        // Позиция за окном приветствия: иначе первым звучит «Поехали!».
        val opening = PetDirector().decide(scene(position = 2 * 60_000L, forwards = 1))
        assertEquals("Перемотали вперёд", opening.say)
        assertEquals(PetMood.EXCITED, opening.mood)
        val credits = PetDirector().decide(
            scene(position = 23 * 60_000L, duration = 24 * 60_000L, forwards = 1),
        )
        assertEquals("Перемотали ближе к концу", credits.say)
        // Середина серии — ни то, ни другое: молчит (одиночная перемотка не событие).
        assertNull(PetDirector().decide(scene(position = 8 * 60_000L, forwards = 1)).say)
    }

    @Test fun `замедление - прислушивается, один раз`() {
        val director = PetDirector()
        val slow = director.decide(scene(speed = 0.75f))
        assertEquals("Помедленнее? Мне так даже спокойнее", slow.say)
        assertEquals(PetMood.LISTEN, slow.mood)
        assertQuiet(director.decide(scene(speed = 0.75f, now = 2_000_000L)))
        assertNull(PetDirector().decide(scene(speed = 1f)).say)
    }

    @Test fun `ночью намекает на сон, днём - нет`() {
        val night = PetDirector().decide(scene(position = 5 * 60_000L, hour24 = 3))
        assertEquals("Уже 3:00. Ещё серия — и спать?", night.say)
        val day = PetDirector().decide(scene(position = 5 * 60_000L, hour24 = 15))
        assertTrue(day.say == null || !day.say!!.contains("спать"))
    }

    @Test fun `середина серии отмечается один раз`() {
        val director = PetDirector()
        val half = director.decide(scene(position = 13 * 60_000L, duration = 24 * 60_000L, hour24 = 15))
        assertEquals("Половина серии позади", half.say)
        // В пределах кулдауна питомец молчит вовсе — и про половину тоже.
        assertNull(director.decide(scene(position = 14 * 60_000L, duration = 24 * 60_000L, now = 1_010_000L)).say)
    }

    @Test fun `первая серия незнакомого тайтла - отдельное приветствие`() {
        val first = PetDirector().decide(
            scene(episode = 1, position = 20_000, title = "Наруто", watched = 0, days = -1),
        )
        assertEquals("Первая серия «Наруто». Посмотрим, что это", first.say)
        // Уже смотрели — приветствие обычное, не «первая серия».
        val known = PetDirector().decide(
            scene(episode = 1, position = 20_000, title = "Наруто", watched = 3, days = -1),
        )
        assertTrue(known.say?.contains("Первая серия") != true)
    }

    @Test fun `полный экран - одна реакция за серию`() {
        val director = PetDirector()
        val first = director.decide(scene(position = 5 * 60_000L, fullscreen = true))
        assertEquals("Во весь экран — вот так правильно", first.say)
        assertEquals(PetMood.EXCITED, first.mood)
        assertQuiet(director.decide(scene(position = 5 * 60_000L, fullscreen = true, now = 2_000_000L)))
    }

    @Test fun `про оценку каталога говорит только на краях шкалы`() {
        val high = PetDirector().decide(scene(position = 5 * 60_000L, rating = 9.2, ratingMax = 10.0, hour24 = 15))
        assertEquals("У этого оценка 9,2. Надеюсь, заслуженно", high.say)
        val low = PetDirector().decide(scene(position = 5 * 60_000L, rating = 2.0, ratingMax = 5.0, hour24 = 15))
        assertEquals("Оценка тут 2,0. Смотрим всё равно, мы не гордые", low.say)
        // Середина шкалы — не новость, молчит.
        assertNull(PetDirector().decide(scene(position = 5 * 60_000L, rating = 6.0, ratingMax = 10.0, hour24 = 15)).say)
        // Без оценки — тоже молчит.
        assertNull(PetDirector().decide(scene(position = 5 * 60_000L, hour24 = 15)).say)
    }

    @Test fun `про жанр - узнаваемые, по одному разу`() {
        val isekai = PetDirector().decide(scene(position = 5 * 60_000L, genres = "Исэкай, Фэнтези", hour24 = 15))
        assertEquals("Опять другой мир. Ну ладно, я не против", isekai.say)
        val detective = PetDirector().decide(scene(position = 5 * 60_000L, genres = "Детектив", hour24 = 15))
        assertEquals("Детектив. Буду внимательнее", detective.say)
        assertNull(PetDirector().decide(scene(position = 5 * 60_000L, genres = "Повседневность", hour24 = 15)).say)
    }

    @Test fun `пропуск эндинга требует настоящего тайминга и следующей серии`() {
        val ask = PetDirector().decide(scene(position = 22 * 60_000L + 30_000, duration = 24 * 60_000L, episode = 3, available = 12, hasNext = true, endingConfirmed = true))
        assertEquals(PetAsk.SKIP_ENDING, ask.ask)
        assertEquals(PetMood.PAUSED, ask.mood)
        // Следующей нет — не предлагает и не утверждает, что сезон окончен.
        val last = PetDirector().decide(scene(position = 23 * 60_000L, duration = 24 * 60_000L, episode = 12, available = 12))
        assertNull(last.ask)
        assertEquals(PetMood.IDLE, last.mood)
        // На паузе не предлагает: серия не идёт.
        assertNull(PetDirector().decide(scene(position = 23 * 60_000L, duration = 24 * 60_000L, playing = false, episode = 3)).ask)
        // За три минуты до конца — рано.
        assertNull(PetDirector().decide(scene(position = 21 * 60_000L, duration = 24 * 60_000L, episode = 3)).ask)
        // Текст-кнопка влезает в пузырь: две строки по ~32 знака.
        assertTrue(SKIP_ENDING_TEXT.length <= 40, SKIP_ENDING_TEXT)
    }

    @Test fun `память жанров - «третий исекай подряд», а частый жанр не считается`() {
        assertEquals("исекай" to 3, genreStreakOf("Исекай, Фэнтези", listOf("Экшен, Исекай", "Исекай", "Комедия")))
        assertEquals("исекай" to 2, genreStreakOf("Исекай", listOf("Исекай", "Драма")))
        assertNull(genreStreakOf("Исекай", listOf("Драма", "Исекай")), "подряд — значит без разрыва")
        assertNull(genreStreakOf("Сёнен, Приключения", listOf("Сёнен", "Сёнен")), "сёнен есть у всего — не тема")
        assertNull(genreStreakOf("", listOf("Исекай")))
        val d = PetDirector()
        val say = d.decide(scene(position = 5 * 60_000L, hour24 = 15, genres = "Исекай", recent = listOf("Исекай", "Исекай")))
        assertEquals("Уже третий исекай подряд. Тема на этой неделе?", say.say)
    }
}
