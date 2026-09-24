package com.aniblaze.desktop

/**
 * Поиск с опечатками: «Реинкорнация безработнава» → «Реинкарнация безработного».
 *
 * Источники ищут подстрокой и на опечатку отвечают пустотой. Здесь запрос
 * сравнивается с уже известными названиями (история, избранное, загруженные ленты)
 * расстоянием Дамерау–Левенштейна по словам: для каждого слова запроса ищется
 * ближайшее слово названия, допуск — одна правка на пять букв. Найденное название
 * уходит источникам как исправленный запрос — так приходит полная выдача, а не
 * только то, что лежало в кэше.
 */
object TypoSearch {
    /** Ближайшие названия к запросу, лучшие первыми; пусто — ничего похожего. */
    fun closest(query: String, titles: Collection<String>, max: Int = 3): List<String> {
        val words = words(query)
        if (words.isEmpty() || words.all { it.length < 3 }) return emptyList()
        return titles.asSequence()
            .distinct()
            .mapNotNull { title -> score(words, words(title))?.let { title to it } }
            .sortedBy { it.second }
            .take(max)
            .map { it.first }
            .toList()
    }

    /**
     * Суммарная стоимость подгонки слов запроса под слова названия; null — хоть одно
     * слово запроса не нашло себе пары в допуске. Точное совпадение — 0.
     */
    internal fun score(queryWords: List<String>, titleWords: List<String>): Int? {
        if (titleWords.isEmpty()) return null
        var total = 0
        for (q in queryWords) {
            if (q.length < 3) continue
            val allowed = maxOf(1, q.length / 5)
            val best = titleWords.minOf { t ->
                when {
                    t.startsWith(q) && q.length >= 4 -> 0
                    // Длинные слова сравниваем и по основе — окончание «безработнава» /
                    // «безработного» это падеж, а не три опечатки.
                    q.length >= STEM_MIN && t.length >= STEM_MIN -> {
                        val stem = minOf(q.length, t.length) - 2
                        minOf(damerau(q, t), damerau(q.take(stem), t.take(stem)) + 1)
                    }
                    else -> damerau(q, t)
                }
            }
            if (best > allowed) return null
            total += best
        }
        // Одинаково близкие названия — короче лучше: «Наруто» раньше «Наруто: Ураганные хроники».
        return total * 100 + titleWords.size
    }

    /** С этой длины слова сравниваются ещё и по основе (без двух последних букв). */
    private const val STEM_MIN = 6

    fun words(s: String): List<String> = s.lowercase().replace('ё', 'е')
        .split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotBlank() }

    /** Расстояние Дамерау–Левенштейна (с перестановкой соседних букв). */
    fun damerau(a: String, b: String): Int {
        if (a == b) return 0
        val la = a.length
        val lb = b.length
        if (la == 0) return lb
        if (lb == 0) return la
        val d = Array(la + 1) { IntArray(lb + 1) }
        for (i in 0..la) d[i][0] = i
        for (j in 0..lb) d[0][j] = j
        for (i in 1..la) {
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[la][lb]
    }
}
