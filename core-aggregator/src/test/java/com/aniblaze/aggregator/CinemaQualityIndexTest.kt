package com.aniblaze.aggregator

import com.aniblaze.aggregator.source.cinemaQualityKey
import com.aniblaze.aggregator.source.parseCinemaQualityIndex
import org.junit.Assert.*
import org.junit.Test

class CinemaQualityIndexTest {
    @Test fun `lordfilm keeps year separate from title`() {
        val html = """<div class="item"><div class="item__label">TS</div>
            <a class="item__title">Обезьяна</a><div class="item__year">(2025)</div></div>"""
        assertEquals("TS", parseCinemaQualityIndex(html)[cinemaQualityKey("Обезьяна", 2025)])
    }
    @Test fun `quality title attribute must not replace film title`() {
        val html = """
            <article class="tc-item"><a><img alt="Надежда (2026)">
            <span class="icon-hd" title="">TS</span>
            <div class="tc-title">Надежда (2026)</div></a></article>
            <article class="tc-item"><span class="icon-hd" title="">WEBDL</span>
            <div class="tc-title">Моана (2026)</div></article>
        """.trimIndent()
        val index = parseCinemaQualityIndex(html)
        assertEquals("TS", index[cinemaQualityKey("Надежда", 2026)])
        assertEquals("WEB-DL", index[cinemaQualityKey("Моана", 2026)])
        assertNull(index[cinemaQualityKey("Моана", 2016)])
        assertEquals(2, index.size)
    }

    @Test fun `resolution alone yields bare resolution`() {
        val html = """<article><div class="tc-title">Фильм (2026)</div>
            <span class="icon-hd">4K</span></article>"""
        val index = parseCinemaQualityIndex(html)
        assertEquals("4K", index[cinemaQualityKey("Фильм", 2026)])
    }

    @Test fun `source plus resolution combine on poster`() {
        val html = """<article><div class="tc-title">Фильм (2026)</div>
            <span class="icon-hd">WEB-DL 1080p</span></article>"""
        val index = parseCinemaQualityIndex(html)
        assertEquals("WEB-DL 1080p", index[cinemaQualityKey("Фильм", 2026)])
    }

    @Test fun `live catalogue uses the same parser as mobile`() {
        if (System.getenv("ANIBLAZE_QUALITY_LIVE") != "1") return
        val combined = mutableMapOf<String, String>()
        for (url in listOf("https://kinozapas.net/", "https://kinozapas.net/page/2/",
            "https://kinozapas.net/page/3/", "https://lordfilm.org/films/",
            "https://lordfilm.org/films/page/2/", "https://lordfilm.org/films/page/3/")) {
        val connection = java.net.URL(url).openConnection().apply {
            connectTimeout = 15000
            readTimeout = 15000
        }
        val bytes = connection.getInputStream().use { it.readBytes() }
        val charset = Regex("charset=([^; ]+)", RegexOption.IGNORE_CASE)
            .find(connection.contentType.orEmpty())?.groupValues?.get(1) ?: "windows-1251"
        val index = parseCinemaQualityIndex(String(bytes, charset(charset)))
        assertTrue("No real quality records parsed", index.size > 5)
        println("Parsed real title/year quality records: ${index.size}")
        index.entries.take(8).forEach { println(it) }
        combined.putAll(index)
        }
        println("Combined title/year records: ${combined.size}")
    }
}
