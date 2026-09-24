package com.aniblaze.aggregator.source

import org.jsoup.Jsoup
import java.util.Locale

internal fun cinemaQualityKey(title: String, year: Int): String = title
    .lowercase(Locale.ROOT).replace('ё', 'е')
    .replace(Regex("\\(\\d{4}\\)"), "")
    .replace(Regex("[^\\p{L}\\p{N}]+"), "") + ":$year"

private val QUALITY_SELECTORS = listOf(
    ".tc-title",
    ".name a",
    ".item__title",
    "img[alt]",
    "img.poster[alt]",
    ".title a",
    "h3",
    "h2",
)

private val QUALITY_TAG_SELECTORS = listOf(
    ".icon-hd",
    ".item__label",
    ".quality",
    ".item__quality",
    ".tc-label",
    ".type",
    ".category",
    ".quality__item",
    ".label",
    "[data-quality]",
)


internal fun parseCinemaQualityIndex(html: String): Map<String, String> = buildMap {
    Jsoup.parse(html).select("article, .movie-box, .item, .tc-item").forEach { card ->
        // A quality span also has title="": never use [title] as the film name.
        val rawTitle = card.resolveText(QUALITY_SELECTORS)
        val yearText = buildString {
            append(rawTitle)
            append(' ')
            append(card.selectFirst(".item__year")?.text())
            append(' ')
            append(card.selectFirst(".year")?.text())
        }
        val year = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(yearText)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach

        val qualitySeed = buildString {
            QUALITY_TAG_SELECTORS.forEach { selector ->
                if (selector.startsWith("[")) {
                    card.select(selector).forEach { element ->
                        if (element.hasAttr("data-quality")) {
                            append(' ')
                            append(element.attr("data-quality"))
                        }
                    }
                } else {
                    append(' ')
                    append(elementText(card, selector))
                }
            }
            append(' ')
            append(card.classNames().joinToString(" "))
            append(' ')
            append(card.attributes().asList().joinToString(" ") { it.value })
            append(' ')
            append(card.text())
        }.toString()
        val label = inferQualityLabel(qualitySeed)
        if (label == null || rawTitle.isBlank()) return@forEach
        put(cinemaQualityKey(rawTitle, year), label)
    }
}

private fun inferQualityLabel(tag: String): String? {
    if (tag.isBlank() || tag.equals("NONE", ignoreCase = true)) return null
    val normalized = tag
        .replace('-', ' ')
        .replace('.', ' ')
        .replace('_', ' ')
        .replace('/', ' ')
        .replace(Regex("[()]"), " ")
        .replace(Regex("[\\[\\]]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase(Locale.ROOT)

    val padded = ' ' + normalized + ' '
    val source = QUALITY_SOURCE_TOKENS.firstNotNullOfOrNull { (signals, display) ->
        signals.any { signal -> (' ' + signal + ' ') in padded }
            .let { hit -> if (hit) display else null }
    }
    val resolution = Regex("\\b(2160p|1080p|720p|480p|360p|240p|4k|uhd)\\b")
        .find(normalized)?.groupValues?.get(1)?.let {
            when (it) {
                "4k", "uhd" -> "4K"
                else -> it
            }
        }
    // A bare resolution without a source type says little about the rip, but on
    // a poster it is still more honest than nothing.
    if (source == null) return resolution
    return if (resolution != null && !source.contains(resolution, ignoreCase = true)) {
        "$source $resolution"
    } else {
        source
    }
}

/** Canonical display tokens, worst tier first (a CAM tag beats a Blu-ray tag). */
private val QUALITY_SOURCE_TOKENS = listOf(
    listOf("camrip", "hdcam", "hd cam", "cam", "hdts", "hd ts", "webcam", "web cam", "screenrip", "screener", "scr") to "CAMRip",
    listOf("telesync", "tele sync", "hdtc", "tsrip", "ts") to "TS",
    listOf("webrip", "web rip", "webr") to "WEBRip",
    listOf("hdrip", "hd rip") to "HDRip",
    listOf("hdtv", "sdtv", "tvrip", "tv rip") to "HDTV",
    listOf("dvdrip", "dvd rip", "dvdscr", "pdvd", "dvd") to "DVD",
    listOf("webdl", "web dl") to "WEB-DL",
    listOf("bluray", "blu ray", "bdrip", "bd remux") to "Blu-ray",
    listOf("remux") to "Remux",
    listOf("fullhd", "1080") to "1080p",
)

private fun elementText(card: org.jsoup.nodes.Element, selector: String): String =
    card.select(selector).joinToString(" ") { it.text().trim() }.trim()

private fun org.jsoup.nodes.Element.resolveText(selectors: List<String>): String =
    selectors.firstNotNullOfOrNull { selector ->
        elementText(this, selector).ifBlank { null }
    } ?: run {
        this.select("a").firstOrNull()?.text()?.trim().orEmpty()
    }
