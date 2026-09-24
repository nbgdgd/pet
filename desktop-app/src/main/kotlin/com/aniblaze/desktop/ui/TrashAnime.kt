package com.aniblaze.desktop.ui

/**
 * «Шлак» — по оценкам MyAnimeList, десятибалльным. Это то же, чем для кино служит
 * IMDb: общая для всех шкала, на которой уже проголосовали десятки тысяч человек.
 *
 * Путь сюда был через две выброшенные версии, и обе стоит помнить:
 *
 * 1. Словарь по названию («перерождение», «уровень», «гильдия») — врал в обе
 *    стороны. «Реинкарнация безработного» (MAL 8.20) и «О моём перерождении в
 *    слизь» (8.13) — лучшие тайтлы в списке владельца, а словарь клеймил их за одно
 *    слово. Жанр не равен качеству.
 * 2. Пятизвёздочная оценка Anixart — своя шкала небольшой аудитории, где всё
 *    жмётся к 4.5–4.8 и различать почти нечего.
 *
 * Оценка берётся из Shikimori: его id — это id MAL, а `/animes/{id}` отдаёт и
 * среднюю, и `rates_scores_stats` — сколько человек поставили каждый балл.
 *
 * ПОРОГИ ЗАМЕРЕНЫ по случайной выборке каталога (391 сериал, вышедшие; выборка
 * именно случайная — по популярности медиана уезжает к 7.9 и порог получился бы
 * бессмысленно строгим):
 *
 *     медиана 6.90 | 25% ниже 6.38 | 10% ниже 5.81
 *
 * «Шлак» = нижняя четверть каталога либо каждая седьмая оценка «4 и ниже».
 */
internal object TrashAnime {
    enum class Category { BEST, NORMAL, POOR }

    fun category(personal: Int, score: Double, lowVotes: Int, votes: Int): Category? = when {
        personal in 4..5 -> Category.BEST
        personal == 3 -> Category.NORMAL
        personal in 1..2 -> Category.POOR
        !hasVerdict(votes) || score <= 0 -> null
        isTrash(score, lowVotes, votes) -> Category.POOR
        score >= 8.0 -> Category.BEST
        else -> Category.NORMAL
    }

    /** Нижняя четверть каталога по оценке MAL. */
    private const val SCORE_THRESHOLD = 6.38

    /** Доля оценок «4 и ниже», после которой тайтл считается спорным. */
    private const val LOW_SHARE_THRESHOLD = 15.0

    /**
     * Меньше этого числа голосов — судить не по чему.
     *
     * У свежего онгоинга бывает полторы сотни оценок, и там доля «низких» скачет
     * на несколько процентов от десятка человек. Такие тайтлы не считаются НИ
     * шлаком, ни хорошими — они просто не участвуют в подсчёте.
     */
    const val MIN_VOTES = 200

    fun hasVerdict(votes: Int): Boolean = votes >= MIN_VOTES

    /** Доля оценок «4 и ниже», в процентах. */
    fun lowShare(lowVotes: Int, votes: Int): Double =
        if (votes <= 0) 0.0 else lowVotes * 100.0 / votes

    fun isTrash(score: Double, lowVotes: Int, votes: Int): Boolean {
        if (!hasVerdict(votes)) return false
        return (score in 0.01..SCORE_THRESHOLD) || lowShare(lowVotes, votes) >= LOW_SHARE_THRESHOLD
    }

    /** Чем меньше оценка, тем выше в списке обвиняемых. */
    fun severity(score: Double, lowVotes: Int, votes: Int): Double =
        if (!hasVerdict(votes)) 0.0 else lowShare(lowVotes, votes) + (10.0 - score) * 2

    /** Медиана каталога — с ней сравнивается средняя оценка зрителя. */
    const val CATALOG_MEDIAN = 6.90

    /**
     * Подпись под долей. Это шутка про статистику, а не приговор вкусу: цифра
     * говорит о голосах других людей, а не о том, что стоит смотреть.
     */
    fun verdict(percent: Int): String = when {
        percent >= 70 -> "Коллекционер провалов"
        percent >= 50 -> "Половина — то, что ругают"
        percent >= 30 -> "Смотришь и спорное"
        percent >= 15 -> "Иногда заносит"
        percent > 0 -> "Почти всё хвалят"
        else -> "Ни одного провального"
    }

    /** Подпись к средней оценке относительно каталога. */
    fun tasteVerdict(average: Double): String = when {
        average <= 0.0 -> ""
        average >= CATALOG_MEDIAN + 1.0 -> "заметно выше медианы каталога"
        average >= CATALOG_MEDIAN + 0.3 -> "выше медианы каталога"
        average >= CATALOG_MEDIAN - 0.3 -> "вровень с медианой каталога"
        else -> "ниже медианы каталога"
    }
}
