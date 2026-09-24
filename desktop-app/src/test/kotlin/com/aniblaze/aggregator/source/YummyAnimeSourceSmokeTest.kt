package com.aniblaze.aggregator.source

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * LIVE-проверка YummyAnime (api.yani.tv). Ходит в сеть намеренно: ломается тут
 * контракт чужого API, и падать при этом надо громко. Проверяется вся цепочка —
 * поиск, каталог, серии и НАСТОЯЩИЙ поток (Kodik или Sibnet), который отдаётся.
 */
class YummyAnimeSourceSmokeTest {

    private val okHttp = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private val http = com.aniblaze.network.HttpClient(okHttp)
    private val source = YummyAnimeSource(http, KodikExtractor(okHttp))

    @Test
    fun `поиск находит тайтл и даёт постер`() = runBlocking {
        val hits = source.search("Наруто")
        assertTrue("поиск ничего не вернул", hits.isNotEmpty())
        val first = hits.first()
        assertTrue("id без префикса: ${first.id}", first.id.startsWith("ya:"))
        assertTrue("постер не абсолютный: ${first.poster}", first.poster.startsWith("https://"))
        assertTrue("оценка не десятибалльная", first.ratingMax == 10.0)
    }

    @Test
    fun `каталог листается`() = runBlocking {
        val p0 = source.catalogPage(0, 0)
        val p1 = source.catalogPage(0, 1)
        assertTrue("первая страница пуста", p0.isNotEmpty())
        assertTrue("вторая страница пуста", p1.isNotEmpty())
        assertTrue("страницы совпадают", p0.map { it.id } != p1.map { it.id })
    }

    @Test
    fun `список серий приходит для известного тайтла`() = runBlocking {
        val segments = source.getContentSegments(NARUTO)
        assertTrue("серий не найдено", segments.isNotEmpty())
        assertTrue("нумерация не с единицы: ${segments.take(3).map { it.number }}", segments.first().number == 1)
        assertTrue("серии не отсортированы", segments.map { it.number } == segments.map { it.number }.sorted())
        assertTrue("у Наруто 220 серий, пришло ${segments.size}", segments.size >= 200)
    }

    @Test
    fun `эпизод резолвится в поток, и поток реально отдаётся`() = runBlocking {
        val result = source.extractContent(NARUTO, 1)
        assertTrue("поток не резолвится", result != null)
        val res = result!!
        assertTrue("нет вариантов качества", !res.variants.isNullOrEmpty())
        val dubs = res.translations.orEmpty()
        assertTrue("озвучек всего ${dubs.size}: ${dubs.map { it.name }}", dubs.size >= 3)
        assertTrue("выбранная озвучка вне списка", dubs.any { it.id == res.translationId })
        StreamCheck.assertStreamServed(okHttp, res)
    }

    @Test
    fun `явно выбранная озвучка уважается`() = runBlocking {
        val base = source.extractContent(NARUTO, 1) ?: error("нет потока")
        val other = base.translations.orEmpty().firstOrNull { it.id != base.translationId } ?: return@runBlocking
        val result = source.extractContent("$NARUTO:t${other.id}", 1)
        // Озвучка могла жить только на Alloha — тогда честный переход к следующей.
        assertTrue("нет потока для :t${other.id}", result != null)
        StreamCheck.assertStreamServed(okHttp, result!!)
    }

    @Test
    fun `season-эмбед Kodik (episode в query) резолвится`() = runBlocking {
        // У этого тайтла Yummy отдаёт `/season/72095/<hash>?episode=1`, а не `/seria/…`:
        // раньше /ftor отвечал 404 на все 13 озвучек, и тайтл уходил в Anixart.
        val result = source.extractContent(DUNGEON, 1)
        assertTrue("поток не резолвится", result != null)
        assertTrue("нет вариантов качества", !result!!.variants.isNullOrEmpty())
        StreamCheck.assertStreamServed(okHttp, result)
    }

    @Test
    fun `ряды главной наполняются`() = runBlocking {
        val schedule = source.catalog("ongoing")
        assertTrue("расписание пусто", schedule.size >= 20)
        assertTrue("у расписания нет дней недели", schedule.count { it.broadcast in 1..7 } >= schedule.size / 2)
        assertTrue("расписание не помечено «выходит»", schedule.all { it.airingStatus == 2 })
        val recent = source.latestReleases(0)
        assertTrue("последние поступления пусты", recent.isNotEmpty())
        assertTrue("в поступлениях анонсы", recent.none { it.airingStatus == 3 })
        val announces = source.catalogPage(6, 0)
        assertTrue("анонсы пусты", announces.isNotEmpty())
        assertTrue("в анонсах не анонсы: ${announces.map { it.airingStatus }.distinct()}", announces.all { it.airingStatus == 3 })
        val year = java.time.LocalDate.now().year
        val season = ((java.time.LocalDate.now().monthValue - 1) / 3) + 1
        val watching = source.watchingNow(season, year, 0)
        assertTrue("«сейчас смотрят» пусто", watching.isNotEmpty())
        assertTrue("в сезоне чужой год", watching.all { it.year == year })
        assertTrue("random пуст", source.random() != null)
    }

    @Test
    fun `карточка калибрована под систему рангов`() = runBlocking {
        val top = source.trending()
        assertTrue("тренд пуст", top.isNotEmpty())
        // Оценка — Shikimori, а не завышенное среднее Yummy: у верха каталога не 9.4.
        assertTrue("оценки выглядят как среднее Yummy: ${top.take(5).map { it.rating }}", top.take(10).any { it.rating < 9.0 })
        assertTrue("нет аудитории (просмотров)", top.count { it.watchingCount > 0 } >= top.size - 2)
        assertTrue("нет статуса выхода", top.all { it.airingStatus != 0 })
    }

    @Test
    fun `франшиза и рекомендации`() = runBlocking {
        val related = source.related(NARUTO)
        assertTrue("франшиза Наруто короче двух: ${related.map { it.title }}", related.size >= 2)
        assertTrue("рекомендации пусты", source.recommended(NARUTO).isNotEmpty())
        val details = source.details(NARUTO) ?: error("нет карточки")
        assertTrue("нет жанров", details.genres.isNotBlank())
        assertTrue("нет оригинального названия: «${details.status}»", details.status.isNotBlank())
        assertTrue("нет числа серий", details.episodesTotal >= 200)
    }

    @Test
    fun `фильтр по жанру и статусу уходит на сервер`() = runBlocking {
        val filter = com.aniblaze.aggregator.model.CatalogFilter(
            tags = setOf("comedy"),
            status = com.aniblaze.aggregator.model.TitleStatus.ONGOING,
        )
        val page = source.filterPage(filter, 0)
        assertTrue("фильтр пуст", page.isNotEmpty())
        assertTrue("статус не «выходит»", page.all { it.airingStatus == 2 })
        // Жанров в ленте нет — проверяем по карточке первого.
        val first = source.details(page.first().id) ?: error("нет карточки")
        assertTrue("не комедия: ${first.genres}", first.genres.contains("Комедия"))
    }

    private companion object {
        /** «Тяжкий труд в подземелье», anime_id 6194 — Kodik только season-эмбедами. */
        const val DUNGEON = "ya:6194"

        /** «Наруто», anime_id 111, shikimori 20 — 1793 видео на момент проверки. */
        const val NARUTO = "ya:111"
    }

    @Test
    fun `фильтр по жанру с сортировкой по оценке отдаёт лучшие первыми`() = runBlocking {
        val filter = com.aniblaze.aggregator.model.CatalogFilter(
            tags = setOf("ecchi"),
            sort = com.aniblaze.aggregator.model.CatalogSort.RATING,
        )
        val page = source.filterPage(filter, 0)
        assertTrue("фильтр пуст", page.size >= 10)
        val grades = page.map { it.rating }
        // Порядок по убыванию (с допуском: Yummy сортирует по своему среднему, карточка
        // несёт Shikimori — они близки, но не тождественны).
        val inversions = grades.zipWithNext().count { (a, b) -> b > a + 0.5 }
        assertTrue("не по убыванию: $grades", inversions <= 2)
        assertTrue("лучшие этти ниже 7.5? $grades", grades.first() >= 7.5)
    }
}
