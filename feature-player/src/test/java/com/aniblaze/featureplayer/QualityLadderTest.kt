package com.aniblaze.featureplayer

import com.aniblaze.aggregator.model.StreamVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки «улучшения качества»: когда за 1080p вообще идут, что кладут в настройки и
 * в каком порядке потом лежит список.
 *
 * Каждый случай здесь — это подпись, которую реально пишет какой-то источник: «720p» у
 * Kodik, «auto» у прямой ссылки Anixart, «1080p · AniLibria» у дописанного варианта.
 */
class QualityLadderTest {

    private fun v(quality: String, url: String = "https://x/$quality.m3u8") = StreamVariant(quality, url)

    // ---- разбор подписи --------------------------------------------------------

    @Test
    fun `height comes out of the label`() {
        assertEquals(720, qualityHeightOf("720p"))
        assertEquals(1080, qualityHeightOf("1080p · AniLibria"))
        assertEquals(480, qualityHeightOf("480"))
    }

    @Test
    fun `a label without digits means unknown, not bad`() {
        assertEquals(0, qualityHeightOf("auto"))
        assertEquals(0, qualityHeightOf(""))
    }

    // ---- когда идти за 1080p -----------------------------------------------------

    @Test
    fun `a source capped at 720 is worth upgrading`() {
        assertTrue(shouldTryHiRes(listOf(v("720p"), v("480p"))))
    }

    @Test
    fun `a source that already has 1080 is left alone`() {
        assertFalse(shouldTryHiRes(listOf(v("1080p"), v("720p"))))
    }

    @Test
    fun `an unlabelled stream is asked about, not skipped`() {
        // Прямая ссылка Anixart подписана «auto». Прежнее условие брало максимум высот
        // и выходило по `?: return`, когда высот не нашлось вовсе, — на этом пути
        // улучшение не срабатывало НИКОГДА.
        assertTrue(shouldTryHiRes(listOf(v("auto"))))
        assertTrue(shouldTryHiRes(emptyList()))
    }

    // ---- сохранённое предпочтение --------------------------------------------------

    @Test
    fun `the preference stores the height, not the source label`() {
        assertEquals("1080p", preferredQualityKey("1080p · AniLibria"))
        assertEquals("720p", preferredQualityKey("720p"))
    }

    @Test
    fun `a label with no height is stored as-is`() {
        assertEquals("auto", preferredQualityKey("auto"))
    }

    @Test
    fun `a preference set from the AniLibria variant still matches the source next time`() {
        // Ровно та поломка, ради которой это писалось: человек один раз выбрал
        // дописанный «1080p · AniLibria», и на следующей серии авто-выбор качества
        // переставал работать совсем — сравнение подписей целиком не совпадало ни с
        // чем, включая честные 1080p самого источника.
        val stored = preferredQualityKey("1080p · AniLibria")
        val nextEpisode = listOf(v("1080p"), v("720p"), v("480p"))
        assertEquals("1080p", pickPreferred(nextEpisode, stored)?.quality)
    }

    @Test
    fun `an exact label wins over a same-height one`() {
        val variants = listOf(v("1080p"), v("1080p · AniLibria"))
        assertEquals("1080p · AniLibria", pickPreferred(variants, "1080p · AniLibria")?.quality)
    }

    @Test
    fun `a missing height is not substituted with something else`() {
        assertNull(pickPreferred(listOf(v("720p"), v("480p")), "1080p"))
    }

    @Test
    fun `an unlabelled preference matches only itself`() {
        assertEquals("auto", pickPreferred(listOf(v("auto"), v("720p")), "auto")?.quality)
        assertNull(pickPreferred(listOf(v("720p")), "auto"))
    }

    @Test
    fun `no preference picks nothing`() {
        assertNull(pickPreferred(listOf(v("720p")), ""))
    }

    // ---- склейка списка --------------------------------------------------------------

    @Test
    fun `the appended 1080p lands first, not last`() {
        // Простое дописывание в конец ставило 1080p ПОСЛЕ 480p: в списке выбора он
        // оказывался внизу, а «первый = лучший» переставало быть правдой.
        val merged = mergeVariants(listOf(v("720p"), v("480p")), listOf(v("1080p · AniLibria")))
        assertEquals(listOf("1080p · AniLibria", "720p", "480p"), merged.map { it.quality })
    }

    @Test
    fun `an unlabelled variant sinks below a declared one`() {
        val merged = mergeVariants(listOf(v("auto")), listOf(v("1080p · AniLibria")))
        assertEquals(listOf("1080p · AniLibria", "auto"), merged.map { it.quality })
    }

    @Test
    fun `the same stream is not listed twice`() {
        val same = v("720p", "https://x/same.m3u8")
        val dup = v("720p · AniLibria", "https://x/same.m3u8")
        assertEquals(1, mergeVariants(listOf(same), listOf(dup)).size)
    }

    @Test
    fun `merging nothing changes nothing`() {
        val base = listOf(v("720p"), v("480p"))
        assertEquals(base.map { it.quality }, mergeVariants(base, emptyList()).map { it.quality })
    }
}
