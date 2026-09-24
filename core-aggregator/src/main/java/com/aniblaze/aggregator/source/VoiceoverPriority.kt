package com.aniblaze.aggregator.source

private fun normalizedVoiceover(name: String): String = buildString(name.length) {
    name.lowercase().forEach { if (it.isLetterOrDigit()) append(it) }
}

internal fun voiceoverPriorityRank(name: String): Int? {
    val normalized = normalizedVoiceover(name)
    return when {
        "дубляж" in normalized || "дублирован" in normalized -> 0
        "studioband" in normalized || ("студийн" in normalized && "банд" in normalized) -> 1
        "anilibria" in normalized || "анилибр" in normalized -> 2
        else -> null
    }
}

/** Ручной выбор важнее приоритета; null означает «оставь прежний fallback источника». */
internal fun preferredVoiceoverIndex(
    names: List<String>,
    manualName: String,
    priorityEnabled: Boolean,
): Int? {
    if (manualName.isNotBlank()) {
        val wanted = normalizedVoiceover(manualName)
        names.indexOfFirst {
            val candidate = normalizedVoiceover(it)
            candidate == wanted || candidate.contains(wanted) || wanted.contains(candidate)
        }.takeIf { it >= 0 }?.let { return it }
    }
    if (!priorityEnabled) return null
    return names.indices
        .mapNotNull { index -> voiceoverPriorityRank(names[index])?.let { rank -> index to rank } }
        .minWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
        ?.first
}
