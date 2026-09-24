package com.aniblaze.desktop.ui

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.desktop.PersistedState
import com.aniblaze.desktop.RecommendationCompletion
import com.aniblaze.desktop.recommendationCompletion
import com.aniblaze.desktop.toPersisted

internal data class SeasonMenuEntry(val anime: Anime, val label: String, val status: String)

/** Release order is the available recommendation; unknown dates keep source order.
 * Different parts, specials and remakes must not be merged just because names resemble. */
internal fun seasonMenu(current: Anime, seasons: List<Anime>, state: PersistedState): List<SeasonMenuEntry> {
    val all = (seasons + current).distinctBy { it.id }
    fun canonical(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
    fun identity(a: Anime): String = if (a.year > 0 && a.poster.isNotBlank())
        listOf(canonical(a.title), a.year, a.contentType, canonical(a.status), a.episodesTotal, a.poster.substringBefore('?')).joinToString("|")
        else a.id
    fun lastActivity(id: String): Long = maxOf(
        state.progress.filter { it.anime.id == id && it.positionMs > 8000 }.maxOfOrNull { it.updatedAt } ?: 0L,
        state.watchedAt.filterKeys { it.substringBeforeLast('#') == id }.values.maxOrNull() ?: 0L,
        // watchedAt was added after the original watched set. Old state files can
        // therefore know that an episode was watched without knowing when. Treat
        // that as real (low-priority) activity instead of labelling the season as
        // untouched after restart.
        if (state.watched.any { it.substringBeforeLast('#') == id }) 1L else 0L,
    )
    val groups = all.groupBy(::identity).values.sortedWith(compareBy<List<Anime>>(
        { it.first().year.takeIf { y -> y > 0 } ?: Int.MAX_VALUE },
        { it.first().firstAiredAt.takeIf { at -> at > 0 } ?: Long.MAX_VALUE },
        { Regex("(?i)(?:сезон\\s*|season\\s*|тв[- ]*)(\\d+)").find(it.first().title)?.groupValues?.get(1)?.toIntOrNull() ?: 1 },
    ))
    fun completed(a: Anime): Boolean {
        val saved = (state.history + state.favorites + state.progress.map { it.anime }).firstOrNull { it.id == a.id }
        val card = a.toPersisted().let { fresh -> if (saved == null) fresh else fresh.copy(
            airingStatus = fresh.airingStatus.takeIf { it > 0 } ?: saved.airingStatus,
            episodesTotal = fresh.episodesTotal.takeIf { it > 0 } ?: saved.episodesTotal,
        ) }
        return recommendationCompletion(state, card) == RecommendationCompletion.COMPLETED
    }
    val done = groups.map { it.any(::completed) }
    val activity = groups.map { group -> group.maxOf { lastActivity(it.id) } }
    // A partial replay is current even when the season was completed earlier.
    val partial = groups.map { group -> group.any { a ->
        state.progress.any { it.anime.id == a.id && it.positionMs > 8000 && it.fraction < .9f && it.updatedAt >= lastActivity(a.id) }
    } }
    val currentIndex = groups.indices.filter { partial[it] || (!done[it] && activity[it] > 0) }
        // With legacy entries several seasons can have the same synthetic timestamp.
        // Prefer the later release in that tie: it is the only ordering evidence the
        // old state still contains.
        .maxWithOrNull(compareBy<Int>({ activity[it] }, { it }))
    val nextIndex = if (currentIndex != null) ((currentIndex + 1) until groups.size).firstOrNull { !done[it] }
        else groups.indices.firstOrNull { !done[it] }
    var seasonNumber = 0
    return groups.mapIndexed { index, group ->
        val a = group.maxByOrNull { lastActivity(it.id) }!!
        val main = a.contentType.isBlank() || a.contentType == "Сериал" || a.contentType.equals("TV", true)
        val kind = if (main) "Сезон ${++seasonNumber}" else a.contentType
        SeasonMenuEntry(a, "${index + 1}. $kind", when {
            index == currentIndex -> "Сейчас смотрите"
            done[index] -> "Просмотрено"
            index == nextIndex -> "Следующий"
            else -> ""
        })
    }
}
