package com.aniblaze.aggregator.model

/**
 * Фильтр «главный герой»: умный, сильнейший, антигерой, героиня.
 *
 * Ни один каталог не размечает тайтлы по характеру главного героя, поэтому признак
 * ВЫЧИСЛЯЕТСЯ из того, что уже есть на карточке — названия, описания и жанров:
 * взвешенные ключи (по основам слов, чтобы «гений/гениальный/гениальность» были одним
 * ключом), минус-ключи, и порог по сумме и по числу РАЗНЫХ ключей — одно совпадение
 * не делает героя умным. У двух профилей есть и внешняя разметка — теги AniList
 * (Anti-Hero, Villainess, Female Protagonist); список по ним добавляет репозиторий.
 *
 * Описание у карточек лент обрезано (~200 знаков у Anixart), поэтому отсев работает
 * на первых страницах не идеально: зато он одинаково честен для всех источников и не
 * требует ни одного запроса.
 */
enum class Protagonist(
    val key: String,
    val label: String,
    /** Основы слов → вес. Ищутся как подстроки в нормализованном тексте. */
    val keys: Map<String, Int>,
    /** Основы, снижающие оценку. */
    val negative: Map<String, Int> = emptyMap(),
    /** Теги AniList, если есть; пусто — только по тексту. */
    val anilistTags: List<String> = emptyList(),
    /** Порог по сумме весов и по числу разных сработавших ключей. */
    val minScore: Int = 4,
    val minKeys: Int = 2,
) {
    GENIUS(
        key = "genius",
        label = "Умный герой",
        keys = mapOf(
            "гени" to 3, "умн" to 2, "умен" to 2, "интеллект" to 3, "стратег" to 3, "тактик" to 2,
            "хитр" to 2, "расчёт" to 2, "расчет" to 2, "замыс" to 2, "интриг" to 2, "манипул" to 2,
            "дедукц" to 3, "детектив" to 2, "разгад" to 2, "загадк" to 1, "шахмат" to 2,
            "iq" to 3, "вундеркинд" to 3, "просчит" to 2, "обыгр" to 2, "переигр" to 2, "мудр" to 1, "гений" to 3,
        ),
        // Минус-ключи слабые: «глупую шутку» в описании — не про героя.
        negative = mapOf("туп" to 1, "глуп" to 1, "идиот" to 1, "бестолков" to 1),
        anilistTags = listOf("Detective"),
        // Одного прямого «гений» достаточно: описание и так называет героя гением.
        minScore = 3,
        minKeys = 1,
    ),
    OVERPOWERED(
        key = "op",
        label = "Сильнейший герой",
        keys = mapOf(
            "сильнейш" to 3, "непобедим" to 3, "всемогущ" to 3, "имба" to 3, "читер" to 3, "невероятно сил" to 3,
            "уровня" to 1, "999" to 2, "божеств" to 2, "легендарн" to 1, "самый сильн" to 3, "силён" to 2, "силен" to 2,
            "одного удара" to 3, "одним ударом" to 3, "могуществ" to 2, "непревзойд" to 2, "побежда" to 1,
            "величайш" to 2, "безгранич" to 2, "сверхсил" to 2, "скрывает силу" to 3, "скрывая силу" to 3, "любого" to 1,
        ),
        negative = mapOf("слабейш" to 3, "неудачник" to 2, "бездарн" to 2),
    ),
    ANTIHERO(
        key = "antihero",
        label = "Антигерой",
        keys = mapOf(
            "антигеро" to 3, "злоде" to 3, "месть" to 2, "мстит" to 2, "отомст" to 2, "жесток" to 2,
            "убийц" to 1, "наёмн" to 2, "наемн" to 2, "преступ" to 2, "мафи" to 2, "якудз" to 2,
            "циничн" to 2, "безжалост" to 2, "властелин" to 2, "повелител" to 2, "тиран" to 2,
            "злой" to 2, "зло " to 1, "тёмный" to 1, "темный" to 1, "падш" to 2, "предател" to 1,
        ),
        negative = mapOf("спасти мир" to 2, "защища" to 1),
        anilistTags = listOf("Anti-Hero", "Villainess"),
    ),
    HEROINE(
        key = "heroine",
        label = "Героиня",
        keys = mapOf(
            "героин" to 3, "девушк" to 2, "девочк" to 2, "школьниц" to 2, "она " to 1, "её " to 1, "ее " to 1,
            "принцесс" to 2, "ведьм" to 2, "волшебниц" to 2, "воительниц" to 3, "студентк" to 2, "дочь" to 1,
            "сестр" to 1, "императриц" to 2, "королев" to 2, "госпож" to 1, "леди" to 2, "аристократк" to 2,
        ),
        negative = mapOf("парен" to 1, "юнош" to 1, "он " to 1, "его " to 1, "школьник " to 1),
        anilistTags = listOf("Female Protagonist"),
        minScore = 4,
        minKeys = 2,
    );

    /** Оценка по тексту карточки; порог — [matches]. Публична ради тестов и подписи. */
    fun score(anime: Anime): Int {
        val text = normalize(anime.title + " . " + anime.description + " . " + anime.genres)
        if (text.isBlank()) return 0
        var total = 0
        var hits = 0
        for ((stem, weight) in keys) {
            val count = occurrences(text, stem)
            if (count > 0) {
                hits++
                total += weight * count.coerceAtMost(3)
            }
        }
        for ((stem, weight) in negative) total -= weight * occurrences(text, stem).coerceAtMost(2)
        return if (hits >= minKeys) total else minOf(total, minScore - 1)
    }

    fun matches(anime: Anime): Boolean = score(anime) >= minScore

    companion object {
        fun byKey(key: String): Protagonist? = entries.firstOrNull { it.key == key }

        private fun normalize(s: String): String =
            s.lowercase().replace('ё', 'е').replace(Regex("""[^\p{L}\p{N} ]+"""), " ").replace(Regex(" +"), " ") + " "

        private fun occurrences(text: String, stem: String): Int {
            var index = 0
            var count = 0
            while (true) {
                index = text.indexOf(stem, index)
                if (index < 0) return count
                count++
                index += stem.length
            }
        }
    }
}
