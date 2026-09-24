package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.desktop.Recommender.Taste
import com.aniblaze.desktop.Recommender.TasteSource
import com.aniblaze.desktop.Recommender.Recommendation
import com.aniblaze.desktop.Recommender.ReasonFactor
import com.aniblaze.desktop.Recommender.HistorySignal
import kotlin.math.abs

/** Additive reranker. The old engine still supplies all candidates, filters,
 * base contributions and diversification; no AI/network dependency here. */
object HybridRecommender {
    const val CANDIDATE_LIMIT = 120
    const val RATING_WEIGHT = 60.0
    const val SEMANTIC_WEIGHT = 55.0
    const val DISLIKE_WEIGHT = 90.0
    const val FAVORITE_WEIGHT = 8.0
    const val COMPLETED_WEIGHT = 6.0

    fun candidates(taste: Taste, pool: List<Anime>): List<Recommendation> =
        Recommender.recommend(taste, pool, limit = CANDIDATE_LIMIT)

    fun recommend(taste: Taste, pool: List<Anime>, profiles: Map<String, SemanticProfile> = emptyMap(),
        limit: Int = Recommender.RECOMMEND_LIMIT): List<Recommendation> {
        val base = Recommender.recommend(taste, pool, limit = pool.size + 1)
        if (taste.isEmpty) return base.take(limit)
        val local = HashMap<String, SemanticProfile>()
        fun profile(a: Anime) = profiles[a.id] ?: local.getOrPut(a.id) { AnimeSemantics.local(a) }
        val ranked = base.take(CANDIDATE_LIMIT).map { item ->
            data class Match(val source: TasteSource, val local: Double, val semantic: Double, val ratingFit: Double)
            fun match(source: TasteSource): Match {
                val a = source.anime; val b = item.anime
                fun overlap(x: Set<String>, y: Set<String>): Double =
                    if (x.isEmpty() || y.isEmpty()) 0.0 else 2.0 * x.intersect(y).size / (x.size + y.size)
                val feature = 0.5 * overlap(source.genres, Recommender.genresOf(b).toSet()) +
                    0.35 * overlap(source.themes, Recommender.themesOf(b)) +
                    if (a.studio.isNotBlank() && a.studio.equals(b.studio, true)) 0.15 else 0.0
                // Compare like with like: mixing AI vocabulary with a local dictionary
                // falsely made unanalysed candidates look unrelated to cached anchors.
                val semantic = if (profiles[a.id] != null && profiles[b.id] != null)
                    AnimeSemantics.similarity(profile(a), profile(b)) else
                    AnimeSemantics.similarity(local.getOrPut(a.id) { AnimeSemantics.local(a) },
                        local.getOrPut(b.id) { AnimeSemantics.local(b) })
                return Match(source, feature, semantic, if (profiles[a.id] != null && profiles[b.id] != null)
                    0.35 * feature + 0.65 * semantic else feature)
            }
            val positive = taste.sources.map(::match)
            val negative = taste.negativeSources.map(::match)
            fun strength(m: Match) = (abs(m.source.weight) / if ((m.source.rating ?: 5) <= 2) 5.0 else 6.0).coerceIn(0.0, 1.0)
            fun aggregate(matches: List<Match>, value: (Match) -> Double): Double = matches
                .map { value(it) * strength(it) }.sortedDescending().take(3).average().takeIf { it.isFinite() } ?: 0.0
            // One rating helps, but is not a fully established taste profile.
            val ratedConfidence = taste.ratedSamples / (taste.ratedSamples + 3.0)
            val rated = positive.filter { it.source.rating != null }
            val terms = item.explanation!!.contributions.toMutableMap()
            terms[ReasonFactor.RATING_SIMILARITY] = RATING_WEIGHT * aggregate(rated) { it.ratingFit } * ratedConfidence
            terms[ReasonFactor.SEMANTIC] = SEMANTIC_WEIGHT * aggregate(positive) { it.semantic } * taste.confidence
            terms[ReasonFactor.DISLIKED] = -DISLIKE_WEIGHT * aggregate(negative) {
                0.4 * it.local + 0.6 * it.semantic
            } * ratedConfidence
            terms[ReasonFactor.FAVORITE_AFFINITY] = FAVORITE_WEIGHT * aggregate(positive.filter {
                it.source.signal == HistorySignal.FAVORITE }) { maxOf(it.local, it.semantic) } * taste.confidence
            terms[ReasonFactor.COMPLETED_HISTORY] = COMPLETED_WEIGHT * aggregate(positive.filter {
                it.source.signal == HistorySignal.WATCHED }) { maxOf(it.local, it.semantic) } * taste.confidence
            // Posters are selected by the same signed terms, never by an AI-generated reason.
            fun evidence(m: Match) = strength(m) * (SEMANTIC_WEIGHT * m.semantic * taste.confidence +
                if (m.source.rating != null) RATING_WEIGHT * m.ratingFit * ratedConfidence else
                    (if (m.source.signal == HistorySignal.WATCHED) COMPLETED_WEIGHT else FAVORITE_WEIGHT) * m.local * taste.confidence)
            val supporting = positive.filter { evidence(it) > 1.0 }.sortedByDescending(::evidence).take(3)
            val strongest = terms.maxBy { it.value }.key
            val personal = setOf(ReasonFactor.RATING_SIMILARITY, ReasonFactor.SEMANTIC,
                ReasonFactor.FAVORITE_AFFINITY, ReasonFactor.COMPLETED_HISTORY)
            val explanation = if (strongest in personal && supporting.isNotEmpty()) {
                val sources = supporting.map { it.source }
                val shared = supporting.flatMap { m ->
                    if (profiles[m.source.anime.id] != null && profiles[item.anime.id] != null)
                        AnimeSemantics.shared(profile(m.source.anime), profile(item.anime)) else
                        AnimeSemantics.shared(AnimeSemantics.local(m.source.anime), AnimeSemantics.local(item.anime))
                }.distinct().take(3)
                val prefix = when {
                    sources.all { (it.rating ?: 0) >= 4 } -> "Вы высоко оценили похожие тайтлы"
                    sources.all { it.signal == HistorySignal.WATCHED } -> "Похоже на полностью досмотренное"
                    sources.all { it.signal == HistorySignal.FAVORITE } -> "Похоже на ваше избранное"
                    else -> "По вашим оценкам и предпочтениям"
                }
                val text = prefix +
                    if (shared.isEmpty()) "" else "\n${shared.joinToString(" · ")}"
                item.explanation.copy(primaryReason = strongest, text = text, sourceAnime = sources,
                    matchedTags = shared, similarityScore = supporting.maxOf { it.semantic } * 100, contributions = terms)
            } else item.explanation.copy(contributions = terms)
            item.copy(score = terms.values.sum(), reason = explanation.text, explanation = explanation)
        }
        // The unanalysed tail stays available for pagination; never AI-analyse thousands.
        return Recommender.diversify(ranked.sortedByDescending { it.score }, limit, 0.2) +
            base.drop(CANDIDATE_LIMIT).take((limit - ranked.size).coerceAtLeast(0))
    }
}
