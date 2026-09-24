package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.desktop.ui.seasonMenu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Сезоны с любого сезона: список франшизы приходит от другого источника, а текущая
 * карточка должна остаться собой (тот же id) и выделиться в меню — без дублей.
 * Названия и годы — из живого `viewing_order` YummyAnime для «Реинкарнации
 * безработного» (19.09.2026).
 */
class SeasonMergeTest {
    private fun ya(id: Int, title: String, year: Int, type: String = "Сериал") =
        Anime(id = "ya:$id", title = title, poster = "https://p/$id.jpg", year = year, contentType = type)

    private val franchise = listOf(
        ya(1255, "Реинкарнация безработного: История о приключениях в другом мире", 2021),
        ya(1256, "Реинкарнация безработного: История о приключениях в другом мире | Часть 2", 2021),
        ya(5768, "Реинкарнация безработного: История о приключениях в другом мире. Эрис — убийца гоблинов", 2022, "Спешл"),
        ya(10227, "Реинкарнация безработного: История о приключениях в другом мире 2", 2023),
        ya(10708, "Реинкарнация безработного: История о приключениях в другом мире 2 | Часть 2", 2024),
        ya(13271, "Реинкарнация безработного: История о приключениях в другом мире 3", 2026),
    )

    @Test fun `карточка второго сезона с чужим id подменяет свою запись франшизы`() {
        // Из истории пришёл Anixart-id того же второго сезона.
        val current = Anime(id = "ax:777", title = "Реинкарнация безработного: История о приключениях в другом мире 2", poster = "ax.jpg", year = 2023)
        val merged = mergeCurrentSeason(current, franchise)
        assertEquals(franchise.size, merged.size, "ни дублей, ни потерь")
        assertEquals("ax:777", merged[3].id, "запись второго сезона стала текущей карточкой")
        assertEquals("ax.jpg", merged[3].poster)
        assertFalse(merged.any { it.id == "ya:10227" })
        // Меню выделяет именно текущий id и не добавляет его второй раз.
        val rows = seasonMenu(current, merged, PersistedState())
        assertEquals(1, rows.count { it.anime.id == "ax:777" })
        assertEquals(franchise.size, rows.size)
    }

    @Test fun `ослабленное совпадение - другое написание, но тот же год и номер сезона`() {
        val current = Anime(id = "ax:1", title = "Реинкарнация безработного 2 сезон", poster = "", year = 2023)
        val merged = mergeCurrentSeason(current, franchise)
        assertEquals("ax:1", merged[3].id)
        assertEquals(2023, merged[3].year)
    }

    @Test fun `третий сезон открыли напрямую - список тот же, текущий на своём месте`() {
        val current = Anime(id = "ax:3", title = "Реинкарнация безработного: История о приключениях в другом мире 3", poster = "", year = 2026)
        val merged = mergeCurrentSeason(current, franchise)
        assertEquals("ax:3", merged.last().id)
        assertEquals(franchise.map { it.title }, merged.map { it.title })
    }

    @Test fun `похожее название - не тот же сезон - фильм, спешл, другой номер, другой год`() {
        val film = Anime(id = "ax:f", title = "Реинкарнация безработного: История о приключениях в другом мире 2", poster = "", year = 2023, contentType = "Фильм")
        assertEquals(franchise, mergeCurrentSeason(film, franchise), "фильм не выдаётся за сезон")
        val special = Anime(id = "ax:s", title = "Реинкарнация безработного 2 сезон", poster = "", year = 2023, contentType = "Спешл")
        assertEquals(franchise, mergeCurrentSeason(special, franchise))
        val otherNumber = Anime(id = "ax:n", title = "Реинкарнация безработного 4 сезон", poster = "", year = 2023)
        assertEquals(franchise, mergeCurrentSeason(otherNumber, franchise))
        val otherYear = Anime(id = "ax:y", title = "Реинкарнация безработного 2 сезон", poster = "", year = 2019)
        assertEquals(franchise, mergeCurrentSeason(otherYear, franchise))
        // Совсем другое аниме с одним словом в названии — не сливаем.
        val stranger = Anime(id = "ax:z", title = "Реинкарнация аристократа 2", poster = "", year = 2023)
        assertEquals(franchise, mergeCurrentSeason(stranger, franchise))
    }

    @Test fun `свой id уже в списке - ничего не трогаем`() {
        val current = franchise[3]
        assertTrue(mergeCurrentSeason(current, franchise) === franchise)
        assertTrue(mergeCurrentSeason(current, emptyList()).isEmpty())
    }
}
