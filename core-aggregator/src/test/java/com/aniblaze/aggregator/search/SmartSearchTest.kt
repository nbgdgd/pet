package com.aniblaze.aggregator.search

import com.aniblaze.aggregator.model.Anime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки умного поиска. Каждый случай — это то, что реально печатают на телефоне:
 * промах пальцем, забытая раскладка, дефис не там.
 */
class SmartSearchTest {

    private fun anime(id: String, title: String, original: String = "", description: String = "") =
        Anime(id = id, title = title, poster = "", description = description, status = original)

    private val catalog = listOf(
        anime("1", "Наруто", original = "Naruto"),
        anime("2", "Наруто: Ураганные хроники", original = "Naruto: Shippuuden"),
        anime("3", "Боруто: Новое поколение", original = "Boruto"),
        anime("4", "Блич", original = "Bleach"),
        anime("5", "Атака титанов", original = "Shingeki no Kyojin"),
        anime("6", "Человек-бензопила", original = "Chainsaw Man"),
        anime("7", "Ре:Зеро. Жизнь с нуля в альтернативном мире", original = "Re:Zero"),
    )

    // ---- раскладка ----------------------------------------------------------

    @Test
    fun `latin layout maps to russian`() {
        assertEquals("наруто", toRussianLayout("yfhenj"))
    }

    @Test
    fun `russian layout maps to latin`() {
        assertEquals("bleach", toLatinLayout("идуфср"))
    }

    @Test
    fun `nothing to convert returns null`() {
        assertNull(toRussianLayout("наруто"))
    }

    @Test
    fun `query typed in the wrong layout still finds the title`() {
        val hits = searchTitles("Yfhenj", catalog)
        assertEquals("1", hits.first().anime.id)
        assertTrue(hits.first().layoutFixed)
    }

    @Test
    fun `comma is a russian letter in the other layout`() {
        // «,kbx» — это «блич», набранное латиницей. Запятая тут НЕ пунктуация: чинить
        // раскладку надо до нормализации, иначе «б» взяться неоткуда.
        val hits = searchTitles(",kbx", catalog)
        assertEquals("4", hits.first().anime.id)
    }

    // ---- опечатки -----------------------------------------------------------

    @Test
    fun `transposed letters cost one edit`() {
        // «наруот» вместо «наруто» — самый частый промах; по чистому Левенштейну это
        // две правки, и порог для шести букв (одна) такое бы отбросил.
        assertEquals(1, damerauLevenshtein("наруот", "наруто", 2))
    }

    @Test
    fun `transposition still finds the title`() {
        val hits = searchTitles("наруот", catalog)
        assertEquals("1", hits.first().anime.id)
        assertEquals(TitleMatchKind.FUZZY, hits.first().kind)
    }

    @Test
    fun `short queries forgive nothing`() {
        // На трёх буквах правок нет вовсе, иначе «Блич» и «Боруто» слипаются.
        assertEquals(0, maxTypos(3))
        assertEquals(1, maxTypos(6))
        assertEquals(2, maxTypos(11))
    }

    @Test
    fun `distance bails out above the threshold`() {
        assertEquals(2, damerauLevenshtein("наруто", "боруто", 3))
        assertTrue(damerauLevenshtein("наруто", "атака титанов", 2) > 2)
    }

    // ---- нормализация -------------------------------------------------------

    @Test
    fun `hyphen and case do not matter`() {
        assertEquals(normalizeTitle("Человек-Бензопила"), normalizeTitle("человек бензопила"))
        val hits = searchTitles("человек бензопила", catalog)
        assertEquals("6", hits.first().anime.id)
    }

    @Test
    fun `punctuation inside the title is ignored`() {
        val hits = searchTitles("rezero", catalog)
        assertEquals("7", hits.first().anime.id)
    }

    // ---- порядок выдачи ------------------------------------------------------

    @Test
    fun `exact match outranks a longer title that merely starts with the query`() {
        val hits = searchTitles("наруто", catalog)
        assertEquals("1", hits[0].anime.id)
        assertEquals("2", hits[1].anime.id)
        assertEquals(TitleMatchKind.EXACT, hits[0].kind)
        assertEquals(TitleMatchKind.PREFIX, hits[1].kind)
    }

    @Test
    fun `a real substring outranks a corrected typo`() {
        // «титан» честно входит в «Атака титанов»; ничего исправлять не пришлось.
        val hits = searchTitles("титан", catalog)
        assertEquals("5", hits.first().anime.id)
        assertTrue(hits.first().kind != TitleMatchKind.FUZZY)
    }

    @Test
    fun `original title is searchable too`() {
        val hits = searchTitles("Shingeki", catalog)
        assertEquals("5", hits.first().anime.id)
    }

    @Test
    fun `status word in the status field is not treated as a title`() {
        val ongoing = anime("8", "Ван-Пис", original = "ongoing")
        assertEquals(listOf("Ван-Пис" to 1.0), titleVariants(ongoing))
    }

    @Test
    fun `slash-joined titles become two search targets`() {
        val both = anime("9", "Наруто / Naruto")
        val variants = titleVariants(both).map { it.first }
        assertTrue("Naruto" in variants.map { it.trim() })
    }

    @Test
    fun `no match at all yields an empty list`() {
        assertTrue(searchTitles("квартет фортепиано", catalog).isEmpty())
    }

    // ---- подсказка -----------------------------------------------------------

    @Test
    fun `hint explains a fixed layout`() {
        val hits = searchTitles("Yfhenj", catalog)
        assertEquals("Искали «наруто»", searchHint("Yfhenj", hits))
    }

    @Test
    fun `hint stays silent on an exact match`() {
        val hits = searchTitles("Наруто", catalog)
        assertEquals("", searchHint("Наруто", hits))
    }

    @Test
    fun `hint warns when only a typo match survived`() {
        val hits = searchTitles("наруот", catalog)
        assertEquals("Точного совпадения нет — показано похожее", searchHint("наруот", hits))
    }
}
