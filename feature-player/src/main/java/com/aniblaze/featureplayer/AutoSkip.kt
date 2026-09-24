package com.aniblaze.featureplayer

import com.aniblaze.aggregator.model.OpeningRange

internal enum class AutoSkipKind { OPENING, ENDING }

internal data class AutoSkipDecision(
    val kind: AutoSkipKind,
    val targetMs: Long,
)

/**
 * Автопропуск работает только внутри точного интервала текущей серии. Диапазон,
 * позаимствованный у соседней серии, остаётся доступен через ручную кнопку: его
 * автоматическое применение может съесть cold open или сцену после титров.
 */
internal fun autoSkipDecision(
    positionMs: Long,
    opening: OpeningRange?,
    ending: OpeningRange?,
    skipOpening: Boolean,
    skipEnding: Boolean,
    openingHandled: Boolean,
    endingHandled: Boolean,
): AutoSkipDecision? {
    fun exactActive(range: OpeningRange?): Boolean =
        range != null && range.isValid && !range.approximate && positionMs in range.startMs until range.endMs

    return when {
        skipOpening && !openingHandled && exactActive(opening) ->
            AutoSkipDecision(AutoSkipKind.OPENING, opening!!.endMs)
        skipEnding && !endingHandled && exactActive(ending) ->
            AutoSkipDecision(AutoSkipKind.ENDING, ending!!.endMs)
        else -> null
    }
}
