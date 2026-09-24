package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.CatalogFilter
import com.aniblaze.aggregator.model.CatalogSort
import com.aniblaze.aggregator.model.SourceMaterial
import com.aniblaze.aggregator.model.TitleStatus
import com.aniblaze.aggregator.model.hiddenGem
import com.aniblaze.aggregator.model.isUnreleased
import com.aniblaze.aggregator.source.AniListCatalog
import com.aniblaze.desktop.ui.AppAccent
import com.aniblaze.desktop.ui.AppTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Фильтры: первоисточник, жемчужины, память фильтра и частые условия, «не понравилось», темы. */
class FilterFeaturesTest {
    private fun settings() = AppSettings(Files.createTempDirectory("aniblaze-filters").toFile().resolve("state.json"))
    private fun card(id: String, rating: Double = 0.0, max: Double = 10.0, views: Int = 0, votes: Int = 0, status: Int = 0, year: Int = 2024, eps: Int = 12) =
        Anime(id = id, title = "t", poster = "", rating = rating, ratingMax = max, watchingCount = views, ratingVotes = votes, airingStatus = status, year = year, episodesAvailable = eps, episodesTotal = eps)

    @Test fun `скрытая жемчужина - высокая оценка при малой аудитории, шкала по источнику`() {
        assertTrue(hiddenGem(card("ya:1", rating = 8.1, views = 12_000)))
        assertFalse(hiddenGem(card("ya:2", rating = 8.1, views = 400_000)), "популярное — не жемчужина")
        assertFalse(hiddenGem(card("ya:3", rating = 6.0, views = 12_000)), "низкая оценка")
        assertTrue(hiddenGem(card("ax:1", rating = 4.2, max = 5.0, votes = 800)))
        assertFalse(hiddenGem(card("ax:2", rating = 4.2, max = 5.0, votes = 5_000)))
        assertFalse(hiddenGem(card("ax:3", rating = 4.2, max = 5.0)), "аудитория неизвестна — не о чем говорить")
        val filter = CatalogFilter(hiddenGems = true)
        assertTrue(filter.matches(card("ya:1", rating = 8.1, views = 12_000)) { emptySet() })
        assertFalse(filter.matches(card("ya:2", rating = 8.1, views = 400_000)) { emptySet() })
        assertEquals("Скрытые жемчужины", filter.chips().single().label)
    }

    @Test fun `наборы фильтров - сохраняются по разделам, одноимённый заменяется, пустое имя игнорируется`() {
        val file = Files.createTempDirectory("aniblaze-presets").toFile().resolve("state.json")
        val s = AppSettings(file)
        val fantasy = CatalogFilter(tags = setOf("fantasy"), yearFrom = 2020)
        s.saveFilterPreset("  Вечернее фэнтези ", "anime", fantasy)
        s.saveFilterPreset("Кино", "cinema", CatalogFilter(minRating = 7.0))
        s.saveFilterPreset("", "anime", fantasy)
        s.saveFilterPreset("Пустой", "anime", CatalogFilter())
        assertEquals(listOf("Вечернее фэнтези"), s.filterPresets("anime").map { it.name })
        assertEquals(fantasy, s.filterPresets("anime").single().filter.toFilter())
        assertEquals(listOf("Кино"), s.filterPresets("cinema").map { it.name })

        s.saveFilterPreset("вечернее фэнтези", "anime", CatalogFilter(tags = setOf("fantasy", "isekai")))
        assertEquals(1, s.filterPresets("anime").size, "одноимённый заменён, а не добавлен")
        assertEquals(setOf("fantasy", "isekai"), s.filterPresets("anime").single().filter.toFilter().tags)

        assertTrue(s.flush())
        val reloaded = AppSettings(file)
        assertEquals(2, reloaded.state.value.filterPresets.size)
        reloaded.deleteFilterPreset("вечернее фэнтези", "anime")
        assertTrue(reloaded.filterPresets("anime").isEmpty())
        assertEquals(1, reloaded.filterPresets("cinema").size)
    }

    @Test fun `не показывать 30 дней - прячет на срок, вкус не трогает, истёкшее выбрасывается`() {
        val s = settings()
        val a = card("ya:snooze")
        s.snooze(a)
        assertTrue(s.isSnoozed(a.id))
        assertFalse(s.isDisliked(a.id))
        assertEquals(0, s.state.value.ratings.size, "оценка не ставится")
        assertTrue(s.state.value.snoozedUntil.getValue(a.id) > System.currentTimeMillis() + 29L * 86_400_000L)
        assertFalse(s.isSnoozed(a.id, now = System.currentTimeMillis() + 31L * 86_400_000L), "через месяц снова показываем")
        s.unsnooze(a)
        assertFalse(s.isSnoozed(a.id))

        val stale = PersistedState(snoozedUntil = mapOf("old" to 10L, "fresh" to 10_000L))
        assertEquals(setOf("fresh"), pruneSnoozed(stale, 100L).snoozedUntil.keys)
        val taste = Recommender.buildTaste(stale.copy(history = listOf(a.toPersisted().copy(id = "ya:h", title = "H"))), now = 100L)
        assertTrue("fresh" in taste.seenIds && "old" !in taste.seenIds)
    }

    @Test fun `первоисточник - известный чужой отсеивается, неизвестный пропускается`() {
        val manga = CatalogFilter(sourceMaterial = SourceMaterial.MANGA.key)
        assertTrue(manga.matches(card("ya:1").copy(sourceMaterial = "Манга")) { emptySet() })
        assertFalse(manga.matches(card("ya:1").copy(sourceMaterial = "Ранобэ")) { emptySet() })
        assertTrue(manga.matches(card("ya:1")) { emptySet() }, "поле пусто — список уже отобрал AniList")
        assertTrue(SourceMaterial.RANOBE.matches("Лайт-новел"))
        assertEquals("По манге", manga.chips().single().label)
        assertEquals(2, CatalogFilter(sourceMaterial = "manga", hiddenGems = true).activeCount)
    }

    @Test fun `AniList - записи с MAL id, годом и названиями`() {
        val json = """{"data":{"Page":{"media":[
            {"idMal":39535,"title":{"romaji":"Mushoku Tensei","english":"Jobless Reincarnation"},"seasonYear":2021,"startDate":{"year":2021},"format":"TV"},
            {"idMal":null,"title":{"romaji":"No mal"},"seasonYear":2020,"format":"TV"},
            {"idMal":5,"title":{"romaji":null,"english":null},"seasonYear":2020,"format":"OVA"}
        ]}}}"""
        val entries = AniListCatalog.parse(json)
        assertEquals(1, entries.size)
        assertEquals(AniListCatalog.Entry(39535, "Mushoku Tensei", "Jobless Reincarnation", 2021, "TV"), entries.single())
    }

    @Test fun `фильтр сохраняется и восстанавливается, частые условия считаются`() {
        val s = settings()
        assertNull(s.lastAnimeFilter())
        val f = CatalogFilter(tags = setOf("fantasy"), status = TitleStatus.ONGOING, yearFrom = 2026, yearTo = 2026, hideWatched = true, sort = CatalogSort.RATING)
        s.saveAnimeFilter(f)
        assertEquals(f, s.lastAnimeFilter())
        // Повтор того же фильтра счётчики не двигает; новое условие — двигает.
        s.saveAnimeFilter(f)
        s.saveAnimeFilter(f.copy(tags = setOf("fantasy", "action")))
        s.saveAnimeFilter(f.copy(tags = setOf("fantasy")))
        s.saveAnimeFilter(f.copy(tags = setOf("fantasy", "action")))
        s.saveAnimeFilter(CatalogFilter())
        s.saveAnimeFilter(f.copy(tags = setOf("fantasy", "action")))
        val usage = s.state.value.filterUsage
        assertEquals(3, usage["tag:action"])
        assertEquals(2, usage["tag:fantasy"])
        assertEquals(setOf("tag:action"), s.frequentFilterKeys(), "часто — от $FREQUENT_FILTER_MIN включений")
        assertEquals(setOf("tag:fantasy", "status:ONGOING", "year:2026-2026", "hideWatched"), filterUsageKeys(f))
    }

    @Test fun `не понравилось - уходит из подборок и ставит одну звезду, снятие убирает только её`() {
        val s = settings()
        val a = card("ya:7")
        s.setDisliked(a, true)
        assertTrue(s.isDisliked("ya:7"))
        assertEquals(1, s.ratingOf("ya:7"))
        assertTrue("ya:7" in Recommender.buildTaste(s.state.value).seenIds)
        s.setDisliked(a, false)
        assertFalse(s.isDisliked("ya:7"))
        assertEquals(0, s.ratingOf("ya:7"))
        // Своя оценка остаётся: отметка её не трогает ни при установке, ни при снятии.
        s.setRating(a, 4)
        s.setDisliked(a, true)
        assertEquals(4, s.ratingOf("ya:7"))
        s.setDisliked(a, false)
        assertEquals(4, s.ratingOf("ya:7"))
    }

    @Test fun `невышедшее - анонс, будущий год, пустая карточка, но не вышедшее без серий`() {
        assertTrue(isUnreleased(card("ya:1", status = 3), thisYear = 2026))
        assertTrue(isUnreleased(card("ya:2", year = 2027, eps = 0), thisYear = 2026))
        assertFalse(isUnreleased(card("ya:3", eps = 0), thisYear = 2026), "нет серий — ещё не анонс")
        assertFalse(isUnreleased(card("ya:4", status = 1, eps = 0), thisYear = 2026), "вышло, но у источника нет видео")
        assertFalse(isUnreleased(card("ya:5", rating = 7.0, eps = 0), thisYear = 2026), "оценка есть — тайтл выходил")
        assertFalse(isUnreleased(card("ya:6"), thisYear = 2026))
    }

    @Test fun `темы и акценты - по ключу, незнакомое - по умолчанию`() {
        assertEquals("amoled", AppTheme.of("amoled").key)
        assertEquals(AppTheme.CLASSIC, AppTheme.of("nope"))
        assertTrue(AppTheme.of("day").light)
        assertEquals(AppTheme.ALL.size, AppTheme.ALL.map { it.key }.toSet().size)
        assertEquals("blue", AppAccent.of("blue").key)
        assertEquals(AppAccent.ALL.first(), AppAccent.of("???"))
        assertEquals(androidx.compose.ui.graphics.Color(0xFF12AB34), AppAccent.of("#12ab34").color, "свой цвет из hex")
        assertNull(AppAccent.parseHex("#12ab3"))
        assertNull(AppAccent.parseHex("zzzzzz"))
    }
}
