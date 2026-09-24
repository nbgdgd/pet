package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.CatalogTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Умный поиск на НАСТОЯЩИХ тайтлах и настоящих строках жанров.
 *
 * Каталог ниже — не выдумка: `title` это `title_ru` Anixart, `status` — его же
 * `title_original` (Anixart и AnimeOn кладут оригинальное название именно туда, см.
 * AnixartSource.animeFrom), а `genres` — строка жанров через запятую в тех самых
 * словах, которые перечислены в CatalogTag.anixart. Если сломать разбор этой строки,
 * тесты про настроение и жанры посыпятся первыми — и правильно.
 */
class SmartSearchTest {

    private fun anime(
        id: String,
        title: String,
        original: String = "",
        genres: String = "",
        episodes: Int = 0,
        year: Int = 0,
        age: Int = 0,
        rating: Double = 0.0,
        studio: String = "",
        description: String = "",
    ) = Anime(
        id = id,
        title = title,
        poster = "",
        description = description,
        rating = rating,
        status = original,
        genres = genres,
        year = year,
        studio = studio,
        episodesTotal = episodes,
        ageRating = age,
    )

    // ---- каталог -------------------------------------------------------------

    private val naruto = anime(
        "an:20", "Наруто", "Naruto",
        "экшен, приключения, боевые искусства, сёнен", episodes = 220, year = 2002, age = 4, rating = 4.3,
    )
    private val shippuuden = anime(
        "an:1735", "Наруто: Ураганные хроники", "Naruto: Shippuuden",
        "экшен, приключения, боевые искусства, сёнен", episodes = 500, year = 2007, age = 4, rating = 4.4,
    )
    private val boruto = anime(
        "an:34566", "Боруто: Новое поколение Наруто", "Boruto: Naruto Next Generations",
        "экшен, приключения, сёнен", episodes = 293, year = 2017, age = 4, rating = 3.8,
    )
    private val bleach = anime(
        "an:269", "Блич", "Bleach",
        "экшен, приключения, сверхъестественное, сёнен", episodes = 366, year = 2004, age = 4, rating = 4.2,
    )
    private val jujutsu = anime(
        "an:40748", "Магическая битва", "Jujutsu Kaisen",
        "экшен, сверхъестественное, школа, сёнен", episodes = 24, year = 2020, age = 4, rating = 4.6,
    )
    private val titans = anime(
        "an:16498", "Атака титанов", "Shingeki no Kyojin",
        "экшен, драма, фэнтези, военное, сёнен", episodes = 87, year = 2013, age = 5, rating = 4.7,
    )
    private val overlord = anime(
        "an:29803", "Overlord", "Overlord",
        "экшен, фэнтези, исэкай, приключения, супер сила", episodes = 52, year = 2015, age = 4, rating = 4.3,
    )
    private val shieldHero = anime(
        "an:35790", "Восхождение героя щита", "Tate no Yuusha no Nariagari",
        "экшен, фэнтези, исэкай, приключения, драма", episodes = 63, year = 2019, age = 4, rating = 4.1,
    )
    private val isekaiMaou = anime(
        "an:37105", "Как не вызывать демона-повелителя", "Isekai Maou to Shoukan Shoujo no Dorei Majutsu",
        "фэнтези, исэкай, гарем, этти, комедия", episodes = 22, year = 2018, age = 4, rating = 3.7,
    )
    private val rezero = anime(
        "an:31240", "Re:Zero. Жизнь с нуля в альтернативном мире", "Re:Zero kara Hajimeru Isekai Seikatsu",
        "драма, фэнтези, исэкай, психологическое, романтика", episodes = 50, year = 2016, age = 4, rating = 4.4,
    )
    private val abyss = anime(
        "an:34599", "Созданный в Бездне", "Made in Abyss",
        "приключения, фэнтези, драма, тайна, психологическое", episodes = 25, year = 2017, age = 5, rating = 4.5,
    )
    private val kino = anime(
        "an:2251", "Путешествие Кино", "Kino no Tabi",
        "приключения, драма, психологическое, фэнтези", episodes = 25, year = 2003, age = 3, rating = 4.0,
    )
    private val berserk = anime(
        "an:33", "Берсерк", "Berserk",
        "экшен, фэнтези, драма, ужасы, психологическое", episodes = 25, year = 1997, age = 5, rating = 4.4,
    )
    private val spice = anime(
        "an:2966", "Волчица и пряности", "Ookami to Koushinryou",
        "фэнтези, приключения, романтика", episodes = 25, year = 2008, age = 3, rating = 4.2,
    )
    private val onePunch = anime(
        "an:30276", "Ванпанчмен", "One Punch Man",
        "экшен, комедия, супер сила", episodes = 24, year = 2015, age = 4, rating = 4.5,
    )
    private val chainsaw = anime(
        "an:44511", "Человек-бензопила", "Chainsaw Man",
        "экшен, сверхъестественное, сёнен", episodes = 12, year = 2022, age = 5, rating = 4.3,
    )
    private val jormungand = anime(
        "an:12365", "Ёрмунганд", "Jormungand",
        "экшен, военное, сэйнэн", episodes = 12, year = 2012, age = 5, rating = 4.0,
    )

    private val catalog = listOf(
        naruto, shippuuden, boruto, bleach, jujutsu, titans, overlord, shieldHero,
        isekaiMaou, rezero, abyss, kino, berserk, spice, onePunch, chainsaw, jormungand,
    )

    private fun ids(list: List<TitleMatch>) = list.map { it.anime.id }
    private fun rankIds(list: List<IntentMatch>) = list.map { it.anime.id }

    // ==== ЧАСТЬ 1: поиск по названию =========================================

    @Test
    fun `точное название находится и стоит первым`() {
        val hits = searchTitles("Наруто", catalog)
        assertEquals(naruto.id, hits.first().anime.id)
        assertEquals(TitleMatchKind.EXACT, hits.first().kind)
    }

    @Test
    fun `точное выше вхождения выше исправленной опечатки`() {
        // Три ступени на одном запросе: сам «Наруто», «Наруто: Ураганные хроники»
        // (название начинается с запроса) и «Боруто: Новое поколение Наруто», где с
        // запроса начинается не название, а слово внутри него.
        val hits = searchTitles("наруто", catalog)
        assertEquals(listOf(naruto.id, shippuuden.id, boruto.id), ids(hits).take(3))
        assertEquals(TitleMatchKind.EXACT, hits[0].kind)
        assertEquals(TitleMatchKind.PREFIX, hits[1].kind)
        assertEquals(TitleMatchKind.WORD_PREFIX, hits[2].kind)
        // А исправленная опечатка обязана стоить дешевле САМОГО дешёвого честного
        // вхождения — иначе «нарута» перебивало бы реальные названия с «наруто».
        val typo = searchTitles("нарута", catalog).first { it.anime.id == naruto.id }
        assertEquals(TitleMatchKind.FUZZY, typo.kind)
        assertTrue(typo.score < hits[2].score, "опечатка ${typo.score} дороже вхождения ${hits[2].score}")
    }

    @Test
    fun `опечатка в русском названии всё равно находит тайтл`() {
        assertEquals(jujutsu.id, searchTitles("магичесая битва", catalog).first().anime.id)
        assertEquals(titans.id, searchTitles("аттака титанов", catalog).first().anime.id)
        assertEquals(naruto.id, searchTitles("нарута", catalog).first().anime.id)
        assertEquals(bleach.id, searchTitles("бличь", catalog).first().anime.id)
    }

    @Test
    fun `перестановка соседних букв стоит одной ошибки`() {
        // «наруот» — классический промах пальцев. По обычному Левенштейну это ДВЕ
        // правки и порог в 1 его бы отсёк; поэтому расстояние считается по Дамерау.
        val hits = searchTitles("наруот", catalog)
        assertEquals(naruto.id, hits.first().anime.id)
    }

    @Test
    fun `короткий запрос опечаток не прощает`() {
        // «Блич» — 4 буквы. Если бы порог рос с длиной линейно от нуля, «Блич» и
        // «Боруто» слиплись бы; на трёх буквах правок не разрешено вовсе.
        val hits = searchTitles("бли", catalog)
        assertTrue(hits.none { it.kind == TitleMatchKind.FUZZY }, "на трёх буквах опечаток быть не должно: ${ids(hits)}")
    }

    @Test
    fun `неправильная раскладка латиницей находит русское название`() {
        // Yfhenj = Наруто, ,kbx = Блич, vfubxtcrfz ,bndf = магическая битва.
        // (В задании пример «ямшер»→«блич»; на настоящей ЙЦУКЕН «блич» набирается
        // как ",kbx", а «bleach» — как "идуфср", проверяем именно их.)
        assertEquals(naruto.id, searchTitles("Yfhenj", catalog).first().anime.id)
        assertEquals(bleach.id, searchTitles(",kbx", catalog).first().anime.id)
        assertEquals(jujutsu.id, searchTitles("vfubxtcrfz ,bndf", catalog).first().anime.id)
    }

    @Test
    fun `неправильная раскладка кириллицей находит оригинальное название`() {
        // «идуфср» — это "bleach", набранное с включённой русской раскладкой.
        val hit = searchTitles("идуфср", catalog).first()
        assertEquals(bleach.id, hit.anime.id)
        assertTrue(hit.layoutFixed, "совпадение должно быть помечено как исправление раскладки")
    }

    @Test
    fun `регистр ё дефисы и лишние пробелы не мешают`() {
        assertEquals(chainsaw.id, searchTitles("  ЧЕЛОВЕК   БЕНЗОПИЛА ", catalog).first().anime.id)
        assertEquals(jormungand.id, searchTitles("ермунганд", catalog).first().anime.id)
        assertEquals(TitleMatchKind.EXACT, searchTitles("человек бензопила", catalog).first().kind)
    }

    @Test
    fun `совпадение по началу слова`() {
        assertEquals(titans.id, searchTitles("титан", catalog).first().anime.id)
        assertEquals(overlord.id, searchTitles("overl", catalog).first().anime.id)
    }

    @Test
    fun `поиск идёт и по оригинальному названию`() {
        assertEquals(titans.id, searchTitles("shingeki", catalog).first().anime.id)
        assertEquals(jujutsu.id, searchTitles("jujutsu kaisen", catalog).first().anime.id)
        assertEquals(abyss.id, searchTitles("Made in Abyss", catalog).first().anime.id)
    }

    @Test
    fun `пустой запрос ничего не находит`() {
        assertTrue(searchTitles("   ", catalog).isEmpty())
    }

    // ==== ЧАСТЬ 2: разбор естественного языка ================================

    @Test
    fun `аниме как Overlord но без гарема`() {
        val intent = parseNaturalQuery("аниме как Overlord, но без гарема")
        assertEquals("Overlord", intent.similarTo)
        assertTrue(CatalogTag.HAREM.key in intent.excludeTags, "гарем должен быть исключён: $intent")
        assertTrue(intent.includeTags.isEmpty(), "жанров-требований тут нет: ${intent.includeTags}")
    }

    @Test
    fun `короткое мрачное фэнтези`() {
        val intent = parseNaturalQuery("короткое мрачное фэнтези")
        assertEquals(LengthWish.SHORT, intent.length)
        assertTrue(Mood.DARK in intent.moods)
        assertTrue(CatalogTag.FANTASY.key in intent.includeTags)
        assertNull(intent.similarTo)
    }

    @Test
    fun `исекай без романтики`() {
        val intent = parseNaturalQuery("исекай без романтики")
        assertTrue(CatalogTag.ISEKAI.key in intent.includeTags)
        assertTrue(CatalogTag.ROMANCE.key in intent.excludeTags)
        assertTrue(CatalogTag.ROMANCE.key !in intent.includeTags)
    }

    @Test
    fun `аниме про магию с сильным главным героем`() {
        val intent = parseNaturalQuery("аниме про магию с сильным главным героем")
        // Жанра «Магия» в каталоге Anixart нет (2 тайтла на весь каталог, см.
        // комментарий в CatalogFilter) — ближайшее живое это «Фэнтези».
        assertTrue(CatalogTag.FANTASY.key in intent.includeTags, "магия → фэнтези: $intent")
        assertTrue(Trait.STRONG_LEAD in intent.traits)
        assertNull(intent.similarTo)
    }

    @Test
    fun `что-то похожее на Made in Abyss но менее жестокое`() {
        val intent = parseNaturalQuery("что-то похожее на Made in Abyss, но менее жестокое")
        assertEquals("Made in Abyss", intent.similarTo)
        assertTrue(Mood.VIOLENT in intent.excludedMoods, "«менее жестокое» — нежелательное настроение: $intent")
        assertTrue(Mood.VIOLENT !in intent.moods)
    }

    @Test
    fun `не мрачное это исключённое настроение а доброе желаемое`() {
        val intent = parseNaturalQuery("доброе фэнтези, не мрачное")
        assertTrue(Mood.LIGHT in intent.moods)
        assertTrue(Mood.DARK in intent.excludedMoods)
        assertTrue(CatalogTag.FANTASY.key in intent.includeTags)
    }

    @Test
    fun `длинное и короткое разводятся по числу серий`() {
        assertEquals(LengthWish.LONG, parseNaturalQuery("длинное аниме про космос").length)
        assertEquals(LengthWish.SHORT, parseNaturalQuery("что-то на один вечер").length)
        assertEquals(LengthWish.LONG, parseNaturalQuery("хочу много серий").length)
    }

    @Test
    fun `имя тайтла не путается с жанром`() {
        // «что-то вроде комедии» — здесь после «вроде» стоит ЖАНР, а не название,
        // и подставлять его в поиск по названию нельзя.
        val intent = parseNaturalQuery("что-то вроде комедии")
        assertNull(intent.similarTo)
        assertTrue(CatalogTag.COMEDY.key in intent.includeTags)
    }

    @Test
    fun `простое название разбирается в пустой замысел`() {
        val intent = parseNaturalQuery("Наруто")
        assertTrue(intent.isEmpty, "у чистого названия условий нет: $intent")
        assertEquals("наруто", intent.freeText)
    }

    @Test
    fun `разбор переводится в фильтр каталога`() {
        val filter = parseNaturalQuery("короткое фэнтези про исекай").toCatalogFilter()
        assertTrue(CatalogTag.FANTASY.key in filter.tags)
        assertTrue(CatalogTag.ISEKAI.key in filter.tags)
        assertNotNull(filter.episodes)
    }

    // ==== ЧАСТЬ 3: ранжирование по разбору ===================================

    @Test
    fun `как Overlord но без гарема опускает гаремный исекай`() {
        val intent = parseNaturalQuery("аниме как Overlord, но без гарема")
        val ranked = rankByIntent(intent, catalog, similarTo = overlord)
        assertEquals(shieldHero.id, ranked.first().anime.id)
        assertTrue(overlord.id !in rankIds(ranked), "сам эталон в выдаче не нужен")
        val maou = ranked.first { it.anime.id == isekaiMaou.id }
        assertTrue(maou.score < 0, "гаремный тайтл обязан уйти в минус: ${maou.score}")
    }

    @Test
    fun `короткое мрачное фэнтези поднимает Берсерка`() {
        val ranked = rankByIntent(parseNaturalQuery("короткое мрачное фэнтези"), catalog)
        assertEquals(berserk.id, ranked.first().anime.id)
        // «Атака титанов» тоже мрачное фэнтези, но 87 серий — это не «короткое».
        assertTrue(rankIds(ranked).indexOf(berserk.id) < rankIds(ranked).indexOf(titans.id))
        assertTrue(rankIds(ranked).indexOf(berserk.id) < rankIds(ranked).indexOf(spice.id))
    }

    @Test
    fun `исекай без романтики опускает Re Zero`() {
        val ranked = rankByIntent(parseNaturalQuery("исекай без романтики"), catalog)
        val order = rankIds(ranked)
        assertTrue(order.take(3).containsAll(listOf(overlord.id, shieldHero.id)), "сверху исекай без романтики: $order")
        assertTrue(order.indexOf(overlord.id) < order.indexOf(rezero.id))
        assertTrue(ranked.first { it.anime.id == rezero.id }.score < 0)
    }

    @Test
    fun `магия и сильный главный герой поднимают Overlord`() {
        val ranked = rankByIntent(parseNaturalQuery("аниме про магию с сильным главным героем"), catalog)
        assertEquals(overlord.id, ranked.first().anime.id)
        // «Ванпанчмен» — эталонный сильный герой, но не фэнтези: обязан быть ниже.
        val order = rankIds(ranked)
        assertTrue(order.indexOf(overlord.id) < order.indexOf(onePunch.id))
    }

    @Test
    fun `похожее на Made in Abyss но менее жестокое ставит мягкое выше`() {
        val intent = parseNaturalQuery("что-то похожее на Made in Abyss, но менее жестокое")
        val ranked = rankByIntent(intent, catalog, similarTo = abyss)
        assertEquals(kino.id, ranked.first().anime.id)
        assertTrue(abyss.id !in rankIds(ranked))
        val bers = ranked.first { it.anime.id == berserk.id }
        assertTrue(bers.score < 0, "жестокий сосед по жанрам обязан уйти вниз: ${bers.score}")
    }

    @Test
    fun `имя из разбора находится поиском по названию`() {
        // Три публичные функции в связке: разобрали → нашли эталон → отранжировали.
        val intent = parseNaturalQuery("что-то похожее на Made in Abyss, но менее жестокое")
        val reference = searchTitles(intent.similarTo!!, catalog).first().anime
        assertEquals(abyss.id, reference.id)
        val ranked = rankByIntent(intent, catalog, similarTo = reference)
        assertEquals(kino.id, ranked.first().anime.id)
    }
}
