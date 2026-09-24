package com.aniblaze.ui.components

import androidx.compose.ui.graphics.Color

/** Human-readable rank shown on anime and cinema posters. */
data class PosterRatingRank(
    val label: String,
    val color: Color,
)

/**
 * Uses one normalized scale because AniBlaze receives 5-point Anixart ratings and
 * 10-point TMDB/Shikimori ratings. Thresholds mirror the established desktop rank.
 */
fun posterRatingRank(rating: Double, ratingMax: Double): PosterRatingRank? {
    if (!rating.isFinite() || rating <= 0.0 || !ratingMax.isFinite() || ratingMax <= 0.0) return null
    val normalized = (rating / ratingMax).coerceIn(0.0, 1.0)
    return when {
        normalized >= 0.854 -> PosterRatingRank("Легенда", Color(0xFFFFC857))
        normalized >= 0.754 -> PosterRatingRank("Прекрасно", Color(0xFF67D7A0))
        normalized >= 0.669 -> PosterRatingRank("Нормально", Color(0xFFB8BBC6))
        else -> PosterRatingRank("Ужас", Color(0xFFE07A55))
    }
}
