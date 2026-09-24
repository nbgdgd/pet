package com.aniblaze.aggregator.model

/** Streaming-related domain models shared across the app. */

/** A catalog title. */
data class Anime(
    val id: String,
    val title: String,
    val poster: String,
    val description: String = "",
    val rating: Double = 0.0,
    /**
     * Верх шкалы, в которой измерена [rating].
     *
     * Источники считают по-разному и в одном поле: Anixart отдаёт «оценку» от 0 до 5,
     * а TMDB, Shikimori, Kodik, КиноПоиск и AnimeVost — от 0 до 10. Без этого поля
     * 7.4 у фильма и 4.7 у аниме выглядели одинаково «просто числом», и всё, что
     * приходило по десятибалльной шкале, автоматически перепрыгивало любые пороги,
     * посчитанные для пятибалльной, — из-за чего в «Кино» КАЖДЫЙ тайтл получал ранг
     * «Легенда». Пять по умолчанию, потому что Anixart — источник по умолчанию.
     */
    val ratingMax: Double = 5.0,
    /** Сколько человек поставили [rating] (0 = источник не говорит). */
    val ratingVotes: Int = 0,
    val status: String = "",
    /** Airing weekday (1=Mon .. 7=Sun); 0 = not currently airing. */
    val broadcast: Int = 0,
    /** Comma-separated genre names (for catalog filtering). */
    val genres: String = "",
    /** Release year (0 = unknown). */
    val year: Int = 0,
    /** How many users favorited it — used to rank announces by popularity. */
    val favoritesCount: Int = 0,
    /** Animation studio (taste signal for recommendations). */
    val studio: String = "",
    /** Страна производства («Япония», «Китай», «Корея»). */
    val country: String = "",
    /** How many users are watching it right now (for the "Сейчас смотрят" row). */
    val watchingCount: Int = 0,
    /**
     * Оценка MyAnimeList по десятибалльной шкале (через Shikimori, чьи id — это и
     * есть id MAL). 0 = ещё не выяснена.
     */
    val malScore: Double = 0.0,
    /** Сколько человек её поставили. */
    val malVotes: Int = 0,
    /** Из них оценок 4 и ниже — прямая мера «сколько людей считают это провалом». */
    val malLowVotes: Int = 0,
    /**
     * Сколько серий всего (0 = неизвестно). Нужно фильтру «количество серий»: без
     * этого поля проверить его на нашей стороне было бы нечем, а сервер отвечает на
     * него не у всех источников.
     */
    val episodesTotal: Int = 0,
    /** Возрастной рейтинг в шкале каталога (1..5, 0 = не проставлен). См. AgeRating. */
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
    /** Actually released episodes, never the planned season length. 0 = unknown. */
    val episodesAvailable: Int = 0,
    /** Exact release/first-publication time of that episode, epoch ms. NOT title updatedAt. */
    val episodeReleasedAt: Long = 0L,
    /** Schedule-derived evidence is deliberately separate from an exact release. */
    val episodeEstimatedAt: Long = 0L,
    /** First broadcast, epoch ms; never a title metadata modification time. */
    val firstAiredAt: Long = 0L,
    /**
     * MyAnimeList id ИМЕННО ЭТОЙ карточки (сезона), если источник его знает (Yummy
     * отдаёт в `remote_ids`). 0 — неизвестен, и расписание/трейлер ищутся по
     * названию, что для второго-третьего сезона заметно менее точно.
     */
    val malId: Int = 0,
    /** Первоисточник: «Манга», «Ранобэ», «Оригинал», «Игра»… Пусто — источник не сказал. */
    val sourceMaterial: String = "",
)

/** Compact production metadata used by the title, studio and staff screens. */
data class StudioCredit(val id: Int, val name: String)

data class PersonCredit(
    val id: Int,
    val name: String,
    val photo: String = "",
)

/** Персонаж тайтла: главный или второстепенный, с портретом (Shikimori, добор из MAL). */
data class CharacterCredit(
    val id: Int,
    val name: String,
    val image: String = "",
    val main: Boolean = false,
    /** Имя латиницей — ключ сопоставления с MyAnimeList; пусто, если не известно. */
    val latinName: String = "",
    /** Японский сэйю (из MAL); пусто — не известен. */
    val seiyuu: String = "",
)

data class TitleCredits(
    val studios: List<StudioCredit> = emptyList(),
    val directors: List<PersonCredit> = emptyList(),
    /** Главные первыми; пусто — источник персонажей не отдал. */
    val characters: List<CharacterCredit> = emptyList(),
)

data class StaffWork(
    val anime: Anime,
    /** The exact role returned for this work, e.g. «Режиссёр». */
    val role: String,
)

data class PersonDetails(
    val person: PersonCredit,
    val works: List<StaffWork> = emptyList(),
)

/** A playable unit (episode). */
data class Segment(
    val id: String,
    val contentId: String,
    val number: Int,
    val title: String,
    val releaseDate: String = "",
    /** False when catalog metadata knows about the episode but no stream exists yet. */
    val playable: Boolean = true,
)

/** Next scheduled episode, used by Favorites for a real countdown. */
data class EpisodeSchedule(
    val airDate: String,
    val season: Int = 0,
    val episode: Int = 0,
)

enum class CaptionFormat { WEBVTT, ASS, SRT, UNKNOWN }

data class Caption(
    val language: String,
    val url: String,
    val format: CaptionFormat = CaptionFormat.UNKNOWN,
)

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
     *  The picker turns the list into shares, so you can see which dub people
     *  actually watch this anime in instead of guessing from the names. */
    val views: Long = 0,
)

/** Opening interval supplied by a source. No value means no skip UI. */
data class OpeningRange(
    val startMs: Long,
    val endMs: Long,
    /** Borrowed from another episode of the same season (AniSkip has no entry for
     *  this one). The same opening plays, but a cold open can shift it by a few
     *  seconds, so the button is shown over a wider window. */
    val approximate: Boolean = false,
    /** Locally matched audio AND video, never a timing copied from another release. */
    val autoDetected: Boolean = false,
    val confidence: Double = 1.0,
) {
    val isValid: Boolean get() = startMs >= 0L && endMs > startMs
}

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
    /** Source-provided opening timing; never guessed by the player. */
    val opening: OpeningRange? = null,
) {
    val isHls: Boolean get() = location.contains(".m3u8")
    val isDash: Boolean get() = location.contains(".mpd")
}

/** One user comment under a title (from the source's own community). */
data class TitleComment(
    val id: Long,
    /** Stable key of the community that owns [id] (for example `anixart`). */
    val source: String = "",
    /** Stable author/profile id when the source exposes one. */
    val authorId: String = "",
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
    /** Ответ на запись [parentId] (0 = самостоятельная). */
    val parentId: Long = 0L,
    /** Ник автора записи, на которую это ответ («@ник» в чате); пусто — не ответ. */
    val replyTo: String = "",
    /**
     * Серия, на которой комментарий оставлен. 0 = не указана.
     *
     * Заполнена не всегда: у тайтлов, обсуждавшихся до появления этого поля, она
     * пуста почти везде (замерено: 147 из 150 у «Владыки демонов» против 128 из 150
     * у «Невесты демона»). Поэтому по ней ФИЛЬТРУЮТ, но на неё не полагаются.
     */
    val episode: Int = 0,
)

/**
 * Одна страница обсуждения вместе с тем, что источник знает о его размере.
 *
 * Раньше страница возвращалась голым списком, и обход не имел ни малейшего понятия,
 * где конец: он останавливался на выдуманном потолке в двенадцать страниц. Источник же
 * сообщает и общее число записей, и число страниц — этого достаточно, чтобы дойти до
 * настоящего конца и не сделать ни одного лишнего запроса.
 */
data class CommentPage(
    val items: List<TitleComment>,
    /**
     * Сколько записей было в ответе ДО отсева ответов и удалённого.
     *
     * Нужно, чтобы отличить «источник отдал пустоту» от «на этой странице одни ответы».
     * Второе разбирается в ноль, но концом обсуждения не является.
     */
    val rawCount: Int = items.size,
    /** Сколько страниц у обсуждения по мнению источника. 0 = не сообщил. */
    val totalPages: Int = 0,
    /** Сколько всего записей. 0 = не сообщил. */
    val totalCount: Int = 0,
) {
    companion object {
        val EMPTY = CommentPage(emptyList())
    }
}

/**
 * Порция обсуждения, отданная экрану: всё уникальное, что собрано К ЭТОМУ МОМЕНТУ.
 *
 * Именно накопленный список, а не «новинки этой страницы»: экрану не приходится
 * склеивать куски и, стало быть, негде ошибиться и приписать одно и то же дважды.
 */
data class CommentBatch(
    val comments: List<TitleComment>,
    /** Обход дошёл до конца — просить больше нечего. */
    val complete: Boolean,
    /** Почему остановились; null — просто исчерпан бюджет вызывающего. */
    val stop: com.aniblaze.aggregator.source.CommentStop?,
    /** Снимок пришёл с диска до фонового обновления. */
    val fromCache: Boolean = false,
    /** После этого снимка обход ещё продолжается. */
    val refreshing: Boolean = !complete,
)

/** Thrown when no enabled source could resolve a playable stream. */
class NoSourceException(message: String = "No source could resolve the requested stream") :
    Exception(message)

/**
 * Ещё не вышло: каталог говорит «анонс» либо год выхода впереди. Отсутствие серий
 * или оценки признаком НЕ считается: у вышедшего тайтла их может не быть у
 * источника, и по такому признаку пустели бы целые ряды.
 */
fun isUnreleased(anime: Anime, thisYear: Int = java.time.LocalDate.now().year): Boolean = when {
    anime.airingStatus == 3 -> true
    anime.airingStatus in 1..2 -> false
    else -> anime.year > thisYear
}
