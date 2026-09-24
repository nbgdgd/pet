package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime

/**
 * Запись франшизы, которая и есть текущая карточка, — заменяется самой карточкой.
 *
 * Список пришёл от другого источника, и у «того же» сезона там другой id. Без
 * подмены меню показало бы сезон дважды (карточка + запись) и не выделило бы
 * текущий. Совпадение СТРОГОЕ: нормализованное название целиком (с номером
 * сезона и части) и год, когда он известен у обоих. Фильм и спешл никогда не
 * считаются «тем же» сезоном сериала.
 */
internal fun mergeCurrentSeason(current: Anime, franchise: List<Anime>): List<Anime> {
    if (franchise.isEmpty()) return franchise
    if (franchise.any { it.id == current.id }) return franchise
    // Сначала строгое совпадение, потом ослабленное — только при известном у обоих
    // годе и одинаковых номерах сезона/части (см. sameSeasonRelaxed).
    val index = franchise.indexOfFirst { sameSeasonCard(current, it) }
        .takeIf { it >= 0 } ?: franchise.indexOfFirst { sameSeasonRelaxed(current, it) }
    if (index < 0) return franchise
    val entry = franchise[index]
    // Берём id и постер карточки, а недостающие поля — из записи франшизы.
    val replaced = current.copy(
        year = current.year.takeIf { it > 0 } ?: entry.year,
        contentType = current.contentType.ifBlank { entry.contentType },
        airingStatus = current.airingStatus.takeIf { it > 0 } ?: entry.airingStatus,
        episodesTotal = current.episodesTotal.takeIf { it > 0 } ?: entry.episodesTotal,
        firstAiredAt = current.firstAiredAt.takeIf { it > 0 } ?: entry.firstAiredAt,
    )
    return franchise.toMutableList().apply { set(index, replaced) }
}

/** Та же карточка сезона: название целиком (с номерами) и год, тип не спорит. */
internal fun sameSeasonCard(a: Anime, b: Anime): Boolean {
    fun norm(s: String) = s.lowercase()
        .replace('ё', 'е')
        .replace(NON_WORD, " ")
        .trim()
    if (norm(a.title).isBlank() || norm(a.title) != norm(b.title)) return false
    if (a.year > 0 && b.year > 0 && a.year != b.year) return false
    val ta = a.contentType.ifBlank { "Сериал" }
    val tb = b.contentType.ifBlank { "Сериал" }
    return ta == tb
}

/**
 * Ослабленное совпадение для разных источников, которые называют один сезон
 * по-разному («… в другом мире 2» против «Реинкарнация безработного 2 сезон»):
 * год обязан совпасть у обоих, номера сезона/части — совпасть, тип — тот же, и
 * не меньше половины значимых слов общие. Фильм и спешл сюда не попадают.
 */
internal fun sameSeasonRelaxed(a: Anime, b: Anime): Boolean {
    if (a.year <= 0 || b.year <= 0 || a.year != b.year) return false
    val ta = a.contentType.ifBlank { "Сериал" }
    val tb = b.contentType.ifBlank { "Сериал" }
    if (ta != tb || ta !in setOf("Сериал", "TV")) return false
    if (seasonMarkers(a.title) != seasonMarkers(b.title)) return false
    val wa = words(a.title); val wb = words(b.title)
    if (wa.isEmpty() || wb.isEmpty()) return false
    // Не меньше двух общих слов: одно слово («Реинкарнация») роднит и чужие тайтлы.
    val common = wa.intersect(wb).size
    return common >= 2 && common * 2 >= minOf(wa.size, wb.size)
}

private val STOP = setOf("сезон", "часть", "season", "part", "the", "тв", "tv", "и", "в", "о", "на")

/** Всё, что не буква и не цифра (любого алфавита). */
private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

/** Число или римское число, стоящее отдельным словом. */
private val NUMBER_WORD = Regex("""(?<![\p{L}\p{N}])(\d{1,2}|[ivx]{1,4})(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)

private fun words(title: String): Set<String> = title.lowercase().replace('ё', 'е')
    .replace(NON_WORD, " ")
    .split(' ')
    .filter { it.length > 2 && it !in STOP && it.toIntOrNull() == null }
    .toSet()

/** Числа в названии (номер сезона, части, римские) — порядок сохранён. */
private fun seasonMarkers(title: String): List<Int> =
    NUMBER_WORD.findAll(title.lowercase()).mapNotNull { m ->
            val t = m.groupValues[1]
            t.toIntOrNull() ?: roman(t)
        }.filter { it in 1..20 }.toList()

private fun roman(s: String): Int? {
    val map = mapOf('i' to 1, 'v' to 5, 'x' to 10)
    var total = 0
    var prev = 0
    for (c in s.reversed()) {
        val v = map[c] ?: return null
        if (v < prev) total -= v else { total += v; prev = v }
    }
    return total.takeIf { it > 0 }
}
