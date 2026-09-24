package com.aniblaze.aggregator.repository

internal data class ProgressEvidence(
    val verifiedPlaybackMs: Long,
    val completed: Boolean,
)

/**
 * Позиция сама по себе не доказывает просмотр: прыжок ползунком к финалу не должен
 * ставить галку. Засчитывается только правдоподобное продвижение медиачасов между
 * соседними сохранениями, сопоставимое с реально прошедшим временем воспроизведения.
 */
internal fun progressEvidence(
    previousPositionMs: Long,
    previousVerifiedMs: Long,
    alreadyCompleted: Boolean,
    positionMs: Long,
    durationMs: Long,
    measuredPlaybackMs: Long,
    ended: Boolean,
): ProgressEvidence {
    if (durationMs <= 0L) return ProgressEvidence(previousVerifiedMs, alreadyCompleted)
    val measured = measuredPlaybackMs.coerceIn(0L, 60_000L)
    val advanced = positionMs - previousPositionMs
    val verifiedDelta = if (measured > 0L && advanced > 0L && advanced <= measured * 4L + 2_000L) {
        advanced
    } else {
        0L
    }
    val verified = (previousVerifiedMs + verifiedDelta).coerceIn(0L, durationMs)
    val fraction = positionMs.toDouble() / durationMs
    // Сам факт MediaItem ended не доказывает просмотр: после пары минут пользователь
    // может перемотать в финал. Требуем реальное воспроизведение большей части выпуска;
    // 70% оставляет место штатному пропуску OP/ED и коротким перемоткам.
    val evidenceNeeded = (durationMs * 7L) / 10L
    return ProgressEvidence(
        verifiedPlaybackMs = verified,
        completed = alreadyCompleted || (fraction >= 0.90 && verified >= evidenceNeeded && (ended || fraction >= 0.95)),
    )
}
