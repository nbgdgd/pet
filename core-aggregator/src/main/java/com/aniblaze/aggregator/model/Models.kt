package com.aniblaze.aggregator.model

/** Streaming-related domain models shared across the app. */

/** A catalog title. */
data class Anime(
    val id: String,
    val title: String,
    val poster: String,
    val description: String = "",
    val rating: Double = 0.0,
    val status: String = "",
    /** Airing weekday (1=Mon .. 7=Sun); 0 = not currently airing. */
    val broadcast: Int = 0,
    /** Comma-separated genre names (for catalog filtering). */
    val genres: String = "",
    /** Release year (0 = unknown). */
    val year: Int = 0,
    /** Release date in ISO format (`yyyy-MM-dd`) when source provides it. */
    val releaseDate: String = "",
    /** How many users favorited it — used to rank announces by popularity. */
    val favoritesCount: Int = 0,
    /** Animation studio (taste signal for recommendations). */
    val studio: String = "",
    /** How many users are watching it right now (for the "Сейчас смотрят" row). */
    val watchingCount: Int = 0,
    /**
     * Верх шкалы, в которой измерена [rating].
     *
     * Anixart считает из пяти, TMDB и Kodik — из десяти, и лежит это в одном поле.
     * Без верхней границы порог «от 7» отсекал бы вообще всё, что пришло по
     * пятибалльной шкале. Пять по умолчанию: Anixart — источник по умолчанию.
     */
    val ratingMax: Double = 5.0,
    /** Страна производства («Япония», «Китай», «Корея»). Пусто = источник не сказал. */
    val country: String = "",
    /** Сколько серий всего (0 = неизвестно). */
    val episodesTotal: Int = 0,
    /** Возрастной рейтинг в шкале каталога (1..5, 0 = не проставлен). См. [AgeRating]. */
    val ageRating: Int = 0,
    /** Тип: «Сериал», «Фильм», «OVA», «Спешл» (пусто = источник не сказал). */
    val contentType: String = "",
    /**
     * Статус выхода: 1 — вышло, 2 — выходит, 3 — анонс, 0 — источник не сказал.
     *
     * Отдельным числом, а не строкой, потому что поле [status] занято: Anixart и
     * AnimeOn кладут туда ОРИГИНАЛЬНОЕ НАЗВАНИЕ, и фильтр «выходит / завершено»,
     * читая его, сравнивал бы условие с японским заголовком.
     */
    val airingStatus: Int = 0,
)

/** A playable unit (episode). */
data class Segment(
    val id: String,
    val contentId: String,
    val number: Int,
    val title: String,
    val releaseDate: String = "",
)

enum class CaptionFormat { WEBVTT, ASS, SRT, UNKNOWN }

data class Caption(
    val language: String,
    val url: String,
    val format: CaptionFormat = CaptionFormat.UNKNOWN,
)

/** Opening/ending interval supplied by a source. No value means no skip UI. */
data class OpeningRange(
    val startMs: Long,
    val endMs: Long,
    /** Borrowed from another episode of the same season (AniSkip has no entry for
     *  this one). The same opening plays, but a cold open can shift it by a few
     *  seconds, so the button is shown over a wider window. */
    val approximate: Boolean = false,
) {
    val isValid: Boolean get() = startMs >= 0L && endMs > startMs
}

/** One selectable quality rendition of a stream. */
data class StreamVariant(
    val quality: String,
    val url: String,
)

/** One selectable voiceover / dub (озвучка) for a title. */
data class Translation(
    val id: Int,
    val name: String,
    val isSub: Boolean = false,
    /** How many views this dub has FOR THIS TITLE (0 = the source doesn't say).
     *  The picker turns these into percentages — which dub people actually watch
     *  this anime in. */
    val views: Long = 0,
)

/** The resolved, playable stream returned by an aggregator. */
data class ContentResult(
    val location: String,
    val quality: String,
    val captions: List<Caption>? = null,
    val metadata: Map<String, String>? = null,
    val source: String = "",
    val referer: String? = null,
    /** All available qualities for the player's quality picker (best first). */
    val variants: List<StreamVariant>? = null,
    /** All available voiceovers for the player's озвучка picker. */
    val translations: List<Translation>? = null,
    /** The voiceover this result was resolved with (matches one in [translations]). */
    val translationId: Int? = null,
) {
    val isHls: Boolean get() = location.contains(".m3u8")
    val isDash: Boolean get() = location.contains(".mpd")
}

/** One user comment under a title (from the source's own community). */
data class TitleComment(
    val id: Long,
    val author: String,
    val avatar: String,
    val message: String,
    /** Epoch seconds. */
    val timestamp: Long,
    /** Community score (can be negative). */
    val votes: Int,
    val isSpoiler: Boolean = false,
    /** How many replies this thread has (0 = none). */
    val replyCount: Int = 0,
    /**
     * Серия, на которой комментарий оставлен. 0 = не указана.
     *
     * Заполнена не всегда: у тайтлов, обсуждавшихся до появления этого поля, она пуста
     * почти везде (замерено: 3 из 294 у «Деревни кузнецов» против 198 из 247 у
     * «Невесты демона»). Поэтому по ней ФИЛЬТРУЮТ, но на неё не полагаются.
     */
    val episode: Int = 0,
)

/** Thrown when no enabled source could resolve a playable stream. */
class NoSourceException(message: String = "No source could resolve the requested stream") :
    Exception(message)
