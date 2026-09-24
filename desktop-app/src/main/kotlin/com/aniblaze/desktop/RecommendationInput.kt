package com.aniblaze.desktop

/** Anime recommendations must not learn preferences from the separate cinema tab. */
internal fun isAnimeRecommendationId(id: String): Boolean =
    !id.startsWith("tmdb:") && !id.startsWith("tmdbtv:") && !id.startsWith("http") &&
        !id.startsWith("lordfilm:") && !id.startsWith("filmix:")

/** Changes only at meaningful events, not at every two-second progress save. */
internal fun recommendationInput(state: PersistedState): PersistedState = PersistedState(
    favorites = state.favorites,
    favoriteRemovals = state.favoriteRemovals,
    history = state.history,
    ratings = state.ratings,
    watched = state.watched,
    completedTitles = state.completedTitles,
    watchedAt = state.watchedAt.mapValues { it.value / 86_400_000L * 86_400_000L },
    episodeCounts = state.episodeCounts,
    progress = state.progress.filter { it.positionMs > 8_000L }.map { entry ->
        // Partial progress is exclusion/recency metadata, NEVER positive taste.
        entry.copy(positionMs = maxOf(9_000L, ((entry.fraction * 4).toInt() * entry.durationMs / 4)),
            updatedAt = entry.updatedAt / 86_400_000L * 86_400_000L)
    },
    enabledSources = state.enabledSources,
    primarySource = state.primarySource,
    // «Не понравилось» и «не показывать» — исключения из подборки, без них
    // спрятанный тайтл всплывал бы снова на первом же обновлении.
    disliked = state.disliked,
    snoozedUntil = state.snoozedUntil,
)

/** Consolidate explicit cross-source duplicates before counting user signals. */
internal fun normalizeRecommendationState(state: PersistedState): PersistedState {
    val all = (state.ratings.map { it.anime } + state.favorites + state.history +
        state.progress.map { it.anime } + state.favoriteRemovals.map { it.anime })
        .filter { isAnimeRecommendationId(it.id) }.distinctBy { it.id }
    val ids = HashMap<String, String>()
    val canonical = HashMap<String, String>()
    for (card in all) {
        val key = "${releaseTitleKey(card.title)}#${card.year}"
        ids[card.id] = canonical.getOrPut(key) { card.id }
    }
    fun card(c: PersistedAnime) = ids[c.id]?.let { c.copy(id = it) }
    fun watchedKey(key: String): String? {
        val id = ids[key.substringBeforeLast('#')] ?: return key.takeIf { isAnimeRecommendationId(it.substringBeforeLast('#')) }
        return "$id#${key.substringAfterLast('#')}"
    }
    return state.copy(
        favorites = state.favorites.mapNotNull(::card).distinctBy { it.id },
        history = state.history.mapNotNull(::card).distinctBy { it.id },
        ratings = state.ratings.sortedByDescending { it.at }.mapNotNull { r -> card(r.anime)?.let { r.copy(anime = it) } }
            .distinctBy { it.anime.id },
        favoriteRemovals = state.favoriteRemovals.mapNotNull { r -> card(r.anime)?.let { r.copy(anime = it) } },
        progress = state.progress.mapNotNull { p -> card(p.anime)?.let { p.copy(anime = it) } },
        watched = state.watched.mapNotNull(::watchedKey).toSet(),
        completedTitles = state.completedTitles.map { ids[it] ?: it }.toSet(),
        watchedAt = state.watchedAt.entries.groupBy { watchedKey(it.key) }.mapNotNull { (key, entries) ->
            key?.let { it to entries.maxOf { e -> e.value } }
        }.toMap(),
        episodeCounts = state.episodeCounts.entries.groupBy { ids[it.key] ?: it.key }
            .mapValues { (_, entries) -> entries.maxOf { it.value } },
    )
}
