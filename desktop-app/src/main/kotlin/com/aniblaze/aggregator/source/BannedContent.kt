package com.aniblaze.aggregator.source

/**
 * Titles banned from distribution in Russia by court order (St. Petersburg courts,
 * 2020–2021). They must never surface in the catalog, search, recommendations or
 * random — otherwise RuStore moderation rejects the app.
 *
 * Matching is done on a normalized form of both the localized and original title.
 * Multi-word entries match as substrings; the few short/ambiguous ones (e.g. Akira)
 * match only as a whole word to avoid false positives.
 */
object BannedContent {

    private val phrases = listOf(
        // Russian titles
        "тетрадь смерти", "токийский гуль", "эльфийская песнь", "эльфийская песня",
        "инуясики", "инуяшики", "межвидовые рецензенты", "адская девочка",
        "вечеринка мертвецов", "загадка дьявола", "класс убийц", "игра дарвина",
        "клинок маню", "князь тьмы с задней парты", "натюрморт в серых тонах",
        "аки и сора", "аки сора",
        // Original / English titles
        "death note", "tokyo ghoul", "elfen lied", "inuyashiki",
        "ishuzoku reviewers", "interspecies reviewers", "jigoku shoujo", "hell girl",
        "corpse party", "akuma no riddle", "assassination classroom",
        "ansatsu kyoushitsu", "darwins game", "manyuu", "aki sora", "aki-sora",
    )

    /** Short tokens matched only as a standalone word (avoids matching inside names). */
    private val words = setOf("акира", "akira")

    fun isBanned(vararg titles: String?): Boolean {
        val normalized = titles.asSequence()
            .filterNotNull()
            .map(::normalize)
            .filter { it.isNotBlank() }
            .toList()
        if (normalized.isEmpty()) return false
        return normalized.any { title ->
            phrases.any { title.contains(it) } ||
                title.split(' ').any { it in words }
        }
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
