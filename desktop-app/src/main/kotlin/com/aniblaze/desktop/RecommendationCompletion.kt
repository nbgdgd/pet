package com.aniblaze.desktop

enum class RecommendationCompletion { UNKNOWN, IN_PROGRESS, CAUGHT_UP, COMPLETED }

/** Recommendation evidence only. Never rewrites playback progress or assumes that
 * finishing episode N means episodes 1..N were watched. Separate OVA/special cards
 * have their own IDs; bonus episodes above the main season's total are not required. */
fun recommendationCompletion(state: PersistedState, card: PersistedAnime): RecommendationCompletion {
    val episodes = state.watched.asSequence().filter { it.substringBeforeLast('#') == card.id }
        .mapNotNull { it.substringAfterLast('#').toIntOrNull() }.filter { it > 0 }.toSet()
    fun all(count: Int) = count in 1..5000 && (1..count).all { it in episodes }
    if (card.airingStatus == 2 || card.airingStatus == 3) {
        val available = maxOf(card.episodesAvailable, state.episodeCounts[card.id] ?: 0)
        return if (all(available)) RecommendationCompletion.CAUGHT_UP else RecommendationCompletion.IN_PROGRESS
    }
    if (card.id in state.completedTitles) return RecommendationCompletion.COMPLETED
    if (card.airingStatus == 1 && all(card.episodesTotal)) return RecommendationCompletion.COMPLETED
    return if (episodes.isNotEmpty() || state.progress.any { it.anime.id == card.id && it.positionMs > 8000 })
        RecommendationCompletion.IN_PROGRESS else RecommendationCompletion.UNKNOWN
}
