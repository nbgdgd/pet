package com.aniblaze.aggregator.repository

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StreamVariant
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Улучшение качества»: когда источник упёрся в 720p, плеер тихо докладывает 1080p от
 * AniLibria в пикер качества.
 *
 * Здесь проверяется САМА ЦЕПОЧКА в репозитории — поиск по названию, сверка названий,
 * гейт по году, отбор ≥1080p и подпись варианта. Названия не выдуманы: это настоящие
 * строки из каталога AniLibria (снято с живого API 18.08.2026) и те же тайтлы, как их
 * называет Anixart.
 */
class HiResVariantsTest {

    /** Подставной источник: отвечает заранее заданным каталогом и потоками. */
    private class FakeSource(
        override val name: String,
        private val catalog: List<Anime>,
        private val streams: Map<String, List<StreamVariant>>,
    ) : ContentAggregator {
        /** Какие id у него спрашивали — по ним видно, ЧТО именно выбрала сверка. */
        val asked = mutableListOf<String>()

        override suspend fun search(query: String): List<Anime> = catalog
        override suspend fun trending(): List<Anime> = catalog
        override suspend fun getContentSegments(contentId: String): List<Segment> = emptyList()
        override suspend fun validateSource(contentId: String): Boolean = true

        override suspend fun extractContent(contentId: String, segment: Int): ContentResult? {
            asked += contentId
            val variants = streams[contentId] ?: return null
            return ContentResult(
                location = variants.first().url,
                quality = variants.first().quality,
                source = name,
                variants = variants,
            )
        }
    }

    private fun repo(vararg sources: ContentAggregator) = AnimeRepositoryImpl(
        aggregators = sources.toSet(),
        resolver = mockk(relaxed = true),
        airDates = mockk(relaxed = true),
        aniskip = mockk(relaxed = true),
        settings = mockk(relaxed = true),
        contentDao = mockk(relaxed = true),
        segmentDao = mockk(relaxed = true),
        watchProgressDao = mockk(relaxed = true),
        favoriteDao = mockk(relaxed = true),
        historyDao = mockk(relaxed = true),
    )

    private fun libria(catalog: List<Anime>, streams: Map<String, List<StreamVariant>>) =
        FakeSource("AniLibria", catalog, streams)

    private fun anime(id: String, title: String, year: Int) =
        Anime(id = id, title = title, poster = "", year = year)

    private val fullLadder = listOf(
        StreamVariant("1080p", "https://libria/1080.m3u8"),
        StreamVariant("720p", "https://libria/720.m3u8"),
        StreamVariant("480p", "https://libria/480.m3u8"),
    )

    // ---- обычный случай --------------------------------------------------------

    @Test
    fun `1080p is found by title and labelled with its source`() = runTest {
        val src = libria(
            listOf(anime("kimetsu-no-yaiba", "Клинок, рассекающий демонов", 2019)),
            mapOf("kimetsu-no-yaiba" to fullLadder),
        )
        val out = repo(src).hiResVariants("Клинок, рассекающий демонов", segment = 3, year = 2019)
        assertEquals(listOf("1080p · AniLibria"), out.map { it.quality })
        assertEquals("https://libria/1080.m3u8", out.first().url)
    }

    @Test
    fun `anything below 1080 is not offered as an upgrade`() = runTest {
        val src = libria(
            listOf(anime("darling-in-the-franxx", "Любимый во Франкcе", 2018)),
            mapOf("darling-in-the-franxx" to listOf(StreamVariant("720p", "https://libria/720.m3u8"))),
        )
        assertTrue(repo(src).hiResVariants("Любимый во Франкcе", segment = 1, year = 2018).isEmpty())
    }

    @Test
    fun `case and punctuation do not break the title gate`() = runTest {
        // Anixart пишет «Мастера меча онлайн», AniLibria — «Мастера Меча Онлайн».
        val src = libria(
            listOf(anime("sword-art-online-i", "Мастера Меча Онлайн", 2012)),
            mapOf("sword-art-online-i" to fullLadder),
        )
        assertEquals(1, repo(src).hiResVariants("Мастера меча онлайн", segment = 5, year = 2012).size)
    }

    // ---- чужой тайтл не подсовывается --------------------------------------------

    @Test
    fun `an unrelated title in the search results is refused`() = runTest {
        val src = libria(
            listOf(anime("black-clover", "Чёрный Клевер", 2017)),
            mapOf("black-clover" to fullLadder),
        )
        assertTrue(repo(src).hiResVariants("Магическая битва", segment = 1, year = 2020).isEmpty())
    }

    @Test
    fun `the year keeps the sequel from answering for season one`() = runTest {
        // «Поднятие уровня в одиночку 2: Восстаньте из тени» и «Поднятие уровня в
        // одиночку» после выброса чисел и коротких слов совпадают по названиям
        // ПОЛНОСТЬЮ. Год — единственное, что их разделяет; из-за этого он и читается у
        // AniLibria обязательно.
        val src = libria(
            listOf(
                anime("ore-dake-level-up-na-ken-season-2", "Поднятие уровня в одиночку 2: Восстаньте из тени", 2025),
                anime("ore-dake-level-up-na-ken", "Поднятие уровня в одиночку", 2024),
            ),
            mapOf(
                "ore-dake-level-up-na-ken-season-2" to fullLadder,
                "ore-dake-level-up-na-ken" to fullLadder,
            ),
        )
        val out = repo(src).hiResVariants("Поднятие уровня в одиночку", segment = 4, year = 2024)
        assertEquals(1, out.size)
        // Спросили именно первый сезон, а не второй.
        assertEquals(listOf("ore-dake-level-up-na-ken"), src.asked.filter { it.isNotBlank() })
    }

    @Test
    fun `without a year the sequel can answer for season one`() = runTest {
        // Осознанно зафиксированная слабость: когда Anixart не отдал года, гейт по году
        // пропускает всё, и первым отвечает тот, кто первым нашёлся. Пусть это живёт в
        // тестах, а не в чьей-то памяти.
        val src = libria(
            listOf(
                anime("ore-dake-level-up-na-ken-season-2", "Поднятие уровня в одиночку 2: Восстаньте из тени", 2025),
                anime("ore-dake-level-up-na-ken", "Поднятие уровня в одиночку", 2024),
            ),
            mapOf(
                "ore-dake-level-up-na-ken-season-2" to fullLadder,
                "ore-dake-level-up-na-ken" to fullLadder,
            ),
        )
        val out = repo(src).hiResVariants("Поднятие уровня в одиночку", segment = 4, year = 0)
        assertEquals(1, out.size)
        assertEquals("ore-dake-level-up-na-ken-season-2", src.asked.first { it.isNotBlank() })
    }

    // ---- отказы -----------------------------------------------------------------

    @Test
    fun `no AniLibria among the sources means no upgrade`() = runTest {
        val other = FakeSource("Anixart", listOf(anime("ax:1", "Магическая битва", 2020)), mapOf("ax:1" to fullLadder))
        assertTrue(repo(other).hiResVariants("Магическая битва", segment = 1, year = 2020).isEmpty())
    }

    @Test
    fun `a blank title or a bad segment is refused before any network call`() = runTest {
        val src = libria(
            listOf(anime("jujutsu-kaisen", "Магическая битва", 2020)),
            mapOf("jujutsu-kaisen" to fullLadder),
        )
        val r = repo(src)
        assertTrue(r.hiResVariants("", segment = 1, year = 2020).isEmpty())
        assertTrue(r.hiResVariants("Магическая битва", segment = 0, year = 2020).isEmpty())
        assertTrue("к источнику не должны были обращаться", src.asked.isEmpty())
    }

    @Test
    fun `an episode AniLibria does not have yields nothing`() = runTest {
        val src = libria(
            listOf(anime("jujutsu-kaisen", "Магическая битва", 2020)),
            emptyMap(),
        )
        assertTrue(repo(src).hiResVariants("Магическая битва", segment = 99, year = 2020).isEmpty())
    }
}
