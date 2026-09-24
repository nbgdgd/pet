package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.lampa.LampaExtractor
import com.aniblaze.aggregator.model.AgeRating
import com.aniblaze.aggregator.model.CatalogFilter
import com.aniblaze.aggregator.model.CatalogSort
import com.aniblaze.aggregator.model.CatalogTag
import com.aniblaze.aggregator.model.ContentType
import com.aniblaze.aggregator.model.EpisodeRange
import com.aniblaze.aggregator.model.TitleStatus
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * LIVE-проверка, что фильтры РЕАЛЬНО подключены к данным, а не рисуют кнопки.
 *
 * Каждый тест сверяет ответ источника с тем, что просили: пришедшие карточки должны
 * сами удовлетворять условию. Ходит в сеть намеренно — ломается тут контракт чужого
 * API, и падать надо громко.
 *
 * Сторожит и конкретные ловушки, найденные перебором при подключении:
 *  • у Anixart ключ статуса — `status_id`, а не `status`: на `status` сервер молча
 *    отдаёт завершённые, то есть фильтр выглядел бы рабочим и врал;
 *  • `episodes_to` в одиночку игнорируется, работает только с `episodes_from`;
 *  • жанры принимаются точными русскими именами, а не идентификаторами.
 */
class CatalogFilterSmokeTest {

    private val okHttp = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private val http = com.aniblaze.network.HttpClient(okHttp)

    private val anixart = AnixartSource(
        http = http,
        kodik = KodikExtractor(okHttp),
        settings = com.aniblaze.database.settings.SettingsDataStore(),
    )

    private val tmdb = LampaCatalogSource(
        http = http, lampa = LampaExtractor(okHttp), pluginUrl = { "" }, balancer = { "rezka2" },
    )

    // --- каталог аниме -------------------------------------------------------

    @Test
    fun `жанр сужает каталог аниме и подтверждается карточками`() = runBlocking {
        val items = anixart.filterPage(CatalogFilter(tags = setOf("isekai")), 0)
        assertTrue("фильтр по жанру ничего не вернул", items.isNotEmpty())
        val wrong = items.filterNot { CatalogTag.of(it).contains("isekai") }
        assertTrue("не в жанре: ${wrong.map { it.title }}", wrong.isEmpty())
    }

    @Test
    fun `два жанра сужают сильнее одного`() = runBlocking {
        val one = anixart.filterPage(CatalogFilter(tags = setOf("isekai")), 0).map { it.id }.toSet()
        val two = anixart.filterPage(CatalogFilter(tags = setOf("isekai", "reincarnation")), 0)
        assertTrue("сочетание жанров ничего не нашло", two.isNotEmpty())
        val wrong = two.filterNot { CatalogTag.of(it).containsAll(setOf("isekai", "reincarnation")) }
        assertTrue("нарушители: ${wrong.map { it.title }}", wrong.isEmpty())
        assertTrue("второй жанр ничего не изменил", two.map { it.id }.toSet() != one)
    }

    @Test
    fun `статус выходит возвращает только выходящее`() = runBlocking {
        // Тот самый случай, где неверный ключ (`status` вместо `status_id`) давал бы
        // молча завершённые тайтлы.
        val items = anixart.filterPage(CatalogFilter(status = TitleStatus.ONGOING), 0)
        assertTrue("статус ничего не вернул", items.isNotEmpty())
        val wrong = items.filterNot { it.airingStatus == TitleStatus.ONGOING.id }
        assertTrue("не выходят: ${wrong.map { "${it.title}=${it.airingStatus}" }}", wrong.isEmpty())
    }

    @Test
    fun `год, тип и количество серий подтверждаются карточками`() = runBlocking {
        val filter = CatalogFilter(
            yearFrom = 2020, yearTo = 2024,
            contentType = ContentType.SERIES,
            episodes = EpisodeRange.SEASON,
        )
        val items = anixart.filterPage(filter, 0)
        assertTrue("сочетание год+тип+серии ничего не нашло", items.isNotEmpty())
        val wrongYear = items.filter { it.year > 0 && it.year !in 2020..2024 }
        assertTrue("вне годов: ${wrongYear.map { "${it.title}=${it.year}" }}", wrongYear.isEmpty())
        val wrongType = items.filter { it.contentType.isNotBlank() && it.contentType != "Сериал" }
        assertTrue("не сериалы: ${wrongType.map { it.contentType }}", wrongType.isEmpty())
        val wrongEp = items.filter { it.episodesTotal > 0 && it.episodesTotal !in 13..26 }
        assertTrue("вне 13..26: ${wrongEp.map { "${it.title}=${it.episodesTotal}" }}", wrongEp.isEmpty())
    }

    @Test
    fun `возрастной рейтинг и страна подтверждаются карточками`() = runBlocking {
        val items = anixart.filterPage(CatalogFilter(ageRating = AgeRating.ADULT, country = "Япония"), 0)
        assertTrue("возраст+страна ничего не нашли", items.isNotEmpty())
        val wrongAge = items.filter { it.ageRating > 0 && it.ageRating != AgeRating.ADULT.id }
        assertTrue("не 18+: ${wrongAge.map { "${it.title}=${it.ageRating}" }}", wrongAge.isEmpty())
        val wrongCountry = items.filter { it.country.isNotBlank() && it.country != "Япония" }
        assertTrue("не Япония: ${wrongCountry.map { it.country }}", wrongCountry.isEmpty())
    }

    @Test
    fun `каждая сортировка даёт свой порядок`() = runBlocking {
        val heads = CatalogSort.entries.associateWith { sort ->
            anixart.filterPage(CatalogFilter(sort = sort), 0).firstOrNull()?.id
        }
        val missing = heads.filterValues { it == null }.keys
        assertTrue("сортировки без результата: $missing", missing.isEmpty())
        // Пять сортировок не обязаны давать пять разных вершин, но и одной на всех
        // быть не должно — это значило бы, что параметр не доходит.
        assertTrue("все сортировки дали один и тот же результат", heads.values.toSet().size >= 3)
    }

    @Test
    fun `фильтр листается дальше первой страницы`() = runBlocking {
        val filter = CatalogFilter(tags = setOf("isekai"))
        val first = anixart.filterPage(filter, 0).map { it.id }.toSet()
        val second = anixart.filterPage(filter, 1)
        assertTrue("вторая страница пуста", second.isNotEmpty())
        assertTrue("вторая страница повторяет первую", second.any { it.id !in first })
    }

    // --- кино и мультфильмы --------------------------------------------------

    @Test
    fun `кино отдаёт точное число найденного и сужается жанром`() = runBlocking {
        val wide = tmdb.browseFiltered(CatalogFilter(contentType = ContentType.MOVIE), 1, cartoons = false)
        val narrow = tmdb.browseFiltered(
            CatalogFilter(tags = setOf("action", "comedy"), contentType = ContentType.MOVIE), 1, cartoons = false,
        )
        assertTrue("нет карточек", narrow.items.isNotEmpty())
        assertTrue("нет точного счётчика", narrow.total > 0)
        assertTrue("жанры не сузили: ${narrow.total} против ${wide.total}", narrow.total < wide.total)
    }

    @Test
    fun `сочетание фасетов кино реально сужает выдачу`() = runBlocking {
        val filter = CatalogFilter(
            tags = setOf("action"),
            yearFrom = 2015, yearTo = 2024,
            country = "США",
            ageRating = AgeRating.TWELVE,
            minRating = 7.0,
            contentType = ContentType.MOVIE,
        )
        val page = tmdb.browseFiltered(filter, 1, cartoons = false)
        assertTrue("сочетание ничего не нашло", page.items.isNotEmpty())
        val wrongYear = page.items.filter { it.year > 0 && it.year !in 2015..2024 }
        assertTrue("вне годов: ${wrongYear.map { "${it.title}=${it.year}" }}", wrongYear.isEmpty())
        val wrongRating = page.items.filter { it.rating > 0 && it.rating < 7.0 }
        assertTrue("ниже порога оценки: ${wrongRating.map { it.rating }}", wrongRating.isEmpty())
    }

    @Test
    fun `статус сериала фильтрует, а сертификат сериалам не отправляется`() = runBlocking {
        val ongoing = tmdb.browseFiltered(
            CatalogFilter(status = TitleStatus.ONGOING, contentType = ContentType.SERIES), 1, cartoons = false,
        )
        val ended = tmdb.browseFiltered(
            CatalogFilter(status = TitleStatus.FINISHED, contentType = ContentType.SERIES), 1, cartoons = false,
        )
        assertTrue("выходящие сериалы не нашлись", ongoing.items.isNotEmpty())
        assertTrue("завершённые сериалы не нашлись", ended.items.isNotEmpty())
        assertTrue("статус не влияет на выдачу", ongoing.items.first().id != ended.items.first().id)
        // Сертификат у сериалов TMDB даёт ПУСТОЙ ответ, поэтому он им и не шлётся:
        // если бы слался — тут был бы ноль карточек.
        val withAge = tmdb.browseFiltered(
            CatalogFilter(ageRating = AgeRating.ADULT, contentType = ContentType.SERIES), 1, cartoons = false,
        )
        assertTrue("возрастной фильтр обнулил сериалы", withAge.items.isNotEmpty())
    }

    @Test
    fun `фильтр мультфильмов не выпускает из мультфильмов`() = runBlocking {
        val page = tmdb.browseFiltered(CatalogFilter(tags = setOf("action")), 1, cartoons = true)
        assertTrue("мультфильмы с фильтром не нашлись", page.items.isNotEmpty())
        // Жанр анимации добавляется к выбранному принудительно, а японское
        // отсеивается — иначе фильтр вывел бы человека из раздела в общее кино.
        val anime = setOf("tmdbtv:209867", "tmdbtv:37854", "tmdbtv:1429", "tmdb:129")
        assertTrue("в мультфильмы просочилось аниме", page.items.none { it.id in anime })
    }
}
