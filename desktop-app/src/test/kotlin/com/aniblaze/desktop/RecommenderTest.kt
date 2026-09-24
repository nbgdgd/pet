package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Отсев франшиз и порядок блока «Похожие».
 *
 * Все названия — настоящие, как они лежат в каталоге Anixart: именно из их написания
 * («Атака титанов: Финал. Часть 2», «Ре:Зеро. Жизнь с нуля…», «Overlord 2nd Season»)
 * и растут все правила в [Recommender.franchiseKey]. Выдуманные «foo/bar» тут были бы
 * бесполезны: ломается это ровно на живых способах называть продолжения.
 */
class RecommenderTest {

    private fun anime(
        id: String,
        title: String,
        genres: String = "",
        year: Int = 0,
        studio: String = "",
        rating: Double = 0.0,
        favorites: Int = 0,
        description: String = "",
        contentType: String = "",
    ) = Anime(
        id = id,
        title = title,
        poster = "p",
        description = description,
        rating = rating,
        genres = genres,
        year = year,
        favoritesCount = favorites,
        studio = studio,
        contentType = contentType,
    )

    // --- нормализация -----------------------------------------------------------

    @Test
    fun `номер сезона в хвосте не создаёт новой франшизы`() {
        val root = Recommender.franchiseKey("Магическая битва")
        listOf(
            "Магическая битва 2",
            "Магическая битва 0",
            "Магическая битва 2 сезон",
            "Магическая битва 2-й сезон",
            "Магическая битва 2nd Season",
        ).forEach { assertEquals(root, Recommender.franchiseKey(it), "«$it» должно свестись к тому же корню") }
    }

    @Test
    fun `римские номера сезонов тоже номера`() {
        assertEquals(Recommender.franchiseKey("Overlord"), Recommender.franchiseKey("Overlord II"))
        assertEquals(Recommender.franchiseKey("Overlord"), Recommender.franchiseKey("Overlord IV"))
        assertEquals(Recommender.franchiseKey("Overlord"), Recommender.franchiseKey("Overlord 2nd Season"))
        assertEquals(
            Recommender.franchiseKey("Мастера меча онлайн"),
            Recommender.franchiseKey("Мастера меча онлайн II"),
        )
    }

    @Test
    fun `подзаголовок после двоеточия отрезается`() {
        val root = Recommender.franchiseKey("Клинок, рассекающий демонов")
        listOf(
            "Клинок, рассекающий демонов: Поезд «Бесконечный»",
            "Клинок, рассекающий демонов: Квартал красных фонарей",
            "Клинок, рассекающий демонов 2 сезон",
        ).forEach { assertEquals(root, Recommender.franchiseKey(it), "«$it» — та же франшиза") }
        assertEquals(Recommender.franchiseKey("Наруто"), Recommender.franchiseKey("Наруто: Ураганные хроники"))
        assertEquals(
            Recommender.franchiseKey("Стальной алхимик"),
            Recommender.franchiseKey("Стальной алхимик: Братство"),
        )
        assertEquals(
            Recommender.franchiseKey("Атака титанов"),
            Recommender.franchiseKey("Атака титанов: Финал. Часть 2"),
        )
    }

    @Test
    fun `двоеточие внутри самого названия не режет его пополам`() {
        // «Ре:Зеро» — двоеточие ЧАСТЬ имени. Резать по нему нельзя: корень «ре»
        // собрал бы под себя весь каталог. Режется следующий разделитель — точка.
        val root = Recommender.franchiseKey("Ре:Зеро. Жизнь с нуля в альтернативном мире")
        assertTrue(root.startsWith("ре зеро"), "корнем «Ре:Зеро» стало «$root»")
        assertEquals(root, Recommender.franchiseKey("Ре:Зеро. Жизнь с нуля в альтернативном мире 2"))
    }

    @Test
    fun `тип издания — не отдельный тайтл`() {
        assertEquals(Recommender.franchiseKey("Класс убийц"), Recommender.franchiseKey("Класс убийц OVA"))
        assertEquals(Recommender.franchiseKey("Тетрадь смерти"), Recommender.franchiseKey("Тетрадь смерти Special"))
        assertEquals(Recommender.franchiseKey("Гинтама"), Recommender.franchiseKey("Гинтама'"))
        assertEquals(Recommender.franchiseKey("Гинтама"), Recommender.franchiseKey("Гинтама°"))
        assertEquals(Recommender.franchiseKey("Врата Штейна"), Recommender.franchiseKey("Врата Штейна 0"))
    }

    @Test
    fun `продолжение без разделителя ловится по префиксу`() {
        assertTrue(Recommender.isSameFranchise("Бездомный бог", "Бездомный бог ARAGOTO"))
        assertTrue(Recommender.isSameFranchise("Токийский гуль", "Токийский гуль √A"))
        assertTrue(Recommender.isSameFranchise("Токийский гуль", "Токийский гуль: Перерождение"))
        assertTrue(
            Recommender.isSameFranchise("Мастера меча онлайн", "Мастера меча онлайн: Алисизация"),
        )
    }

    @Test
    fun `разные тайтлы с общим началом не склеиваются`() {
        // «Врата» как корень короче порога, иначе «Врата: Там бьются наши воины»
        // утащили бы за собой «Врата Штейна».
        assertFalse(Recommender.isSameFranchise("Врата Штейна", "Врата: Там бьются наши воины"))
        assertFalse(Recommender.isSameFranchise("Наруто", "Боруто: Новое поколение Наруто"))
        assertFalse(Recommender.isSameFranchise("Тетрадь смерти", "Тетрадь дружбы Нацумэ"))
        assertFalse(Recommender.isSameFranchise("Клинок, рассекающий демонов", "Клинок ведьм"))
        assertFalse(Recommender.isSameFranchise("Стальной алхимик", "Стальная тревога!"))
        assertFalse(Recommender.isSameFranchise("Магическая битва", "Магическая революция"))
    }

    // --- отбор похожих ----------------------------------------------------------

    private val jujutsu = anime(
        id = "ax:9000",
        title = "Магическая битва",
        genres = "экшен, сверхъестественное, школа",
        year = 2020,
        studio = "MAPPA",
        rating = 4.7,
        favorites = 120_000,
        contentType = "Сериал",
        description = "Старшеклассник вступает в школу магов и сражается с проклятиями.",
    )

    @Test
    fun `собственные сезоны и части в похожие не попадают`() {
        val pool = listOf(
            jujutsu,
            anime("ax:9001", "Магическая битва 2", genres = "экшен, сверхъестественное, школа", year = 2023),
            anime("ax:9002", "Магическая битва 0", genres = "экшен, сверхъестественное", year = 2021, contentType = "Фильм"),
            anime("ax:9003", "Магическая битва 2nd Season", genres = "экшен", year = 2023),
            anime(
                "ax:9010", "Клинок, рассекающий демонов",
                genres = "экшен, сверхъестественное, историческое", year = 2019, studio = "ufotable",
                rating = 4.8, favorites = 200_000, contentType = "Сериал",
                description = "Юноша берёт в руки клинок и сражается с демонами.",
            ),
        )
        val out = Recommender.similar(jujutsu, pool)
        assertTrue(out.none { it.title.startsWith("Магическая битва") }, "в выдаче остались свои же сезоны: $out")
        assertTrue(out.any { it.id == "ax:9010" }, "«Клинок» обязан был остаться: $out")
    }

    @Test
    fun `сезоны из селектора исключаются даже под другим названием`() {
        // Селектор сезонов приносит франшизу целиком, и её названия бывают такими,
        // что по корню их не поймать («Токийский гуль √A»). Поэтому список сезонов
        // передаётся отбору явно.
        val ghoul = anime("ax:8000", "Токийский гуль", genres = "экшен, ужасы", year = 2014)
        val franchise = listOf(anime("ax:8001", "Токийский гуль √A", genres = "экшен, ужасы", year = 2015))
        val pool = franchise + anime(
            "ax:8100", "Паразит: Учение о жизни",
            genres = "экшен, ужасы", year = 2014, rating = 4.6, favorites = 60_000,
        )
        val out = Recommender.similar(ghoul, pool, exclude = franchise)
        assertTrue(out.none { it.id == "ax:8001" }, "сезон остался в похожих: $out")
        assertEquals(listOf("ax:8100"), out.map { it.id })
    }

    @Test
    fun `один и тот же тайтл из двух источников показывается один раз`() {
        val pool = listOf(
            anime("ax:7000", "Клинок, рассекающий демонов", genres = "экшен, сверхъестественное", year = 2019, rating = 4.8, favorites = 200_000),
            anime("libria-demon-slayer", "Клинок, рассекающий демонов", genres = "экшен, сверхъестественное", year = 2019),
        )
        val out = Recommender.similar(jujutsu, pool)
        assertEquals(1, out.size, "дубликат из второго источника не схлопнулся: $out")
    }

    @Test
    fun `порядок ведут жанры студия и тема а не просто популярность`() {
        val sameEverything = anime(
            "ax:1", "Синий экзорцист",
            genres = "экшен, сверхъестественное, школа", year = 2021, studio = "MAPPA",
            rating = 4.4, favorites = 30_000, contentType = "Сериал",
            description = "Школьник узнаёт, что он сын демона, и поступает в академию экзорцистов.",
        )
        val popularButUnrelated = anime(
            "ax:2", "Ходячий замок",
            genres = "приключения, романтика", year = 2004, studio = "Ghibli",
            rating = 4.9, favorites = 300_000, contentType = "Фильм",
            description = "Девушка, превращённая в старуху, находит приют в бродячем замке.",
        )
        val out = Recommender.similar(jujutsu, listOf(popularButUnrelated, sameEverything))
        assertEquals("ax:1", out.first().id, "наверх должно идти близкое, а не самое популярное: $out")
        assertTrue(
            Recommender.similarity(jujutsu, sameEverything) > Recommender.similarity(jujutsu, popularButUnrelated),
        )
    }

    @Test
    fun `рекомендация самого источника не выбрасывается порогом`() {
        // У источника свой сигнал — «это смотрят вместе», — и он сильнее нашей
        // эвристики. Карточка без единого общего жанра из-за порога исчезала бы.
        val odd = anime("ax:5", "Судзумэ, закрывающая двери", genres = "драма, романтика", year = 2022)
        val out = Recommender.similar(jujutsu, listOf(odd), sourceIds = setOf("ax:5"))
        assertEquals(listOf("ax:5"), out.map { it.id })
    }

    @Test
    fun `совсем чужое в похожие не лезет`() {
        val unrelated = anime("ax:6", "Ванпанчмен", genres = "комедия", year = 2015)
        // Ни одного общего жанра, другой год, ни студии, ни оценки — 12 из 100 такая
        // карточка не набирает, и это правильно: это не «похожее», а просто каталог.
        assertTrue(Recommender.similar(jujutsu, listOf(unrelated)).isEmpty())
    }

    @Test
    fun `тема достаётся из описания там где жанры молчат`() {
        val spaceOne = anime(
            "ax:11", "Космический пират Харлок", genres = "фантастика",
            description = "Капитан ведёт звездолёт через галактику, сражаясь с пришельцами.",
        )
        val schoolOne = anime(
            "ax:12", "Госпожа Кагуя", genres = "фантастика",
            description = "Школьники из совета учеников ведут войну признаний.",
        )
        val base = anime(
            "ax:10", "Ковбой Бибоп", genres = "фантастика",
            description = "Команда охотников за головами странствует по планетам на своём космическом корабле.",
        )
        assertTrue(
            Recommender.similarity(base, spaceOne) > Recommender.similarity(base, schoolOne),
            "при одинаковом жанре ближе должен быть тот, у кого та же тема",
        )
    }
}
