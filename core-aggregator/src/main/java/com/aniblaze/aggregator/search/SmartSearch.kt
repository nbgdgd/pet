package com.aniblaze.aggregator.search

import com.aniblaze.aggregator.model.Anime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Умный поиск по названию: чистый Kotlin, ни Compose, ни сети, ни Android.
 *
 * Перенесено с настольной версии. На телефоне от этого толку больше, чем на ПК, и
 * причина не в коде, а в клавиатуре: на экранной промахиваются пальцем гораздо чаще,
 * автозамена дописывает своё, а раскладка переключается не глядя. Источники же ищут
 * ПОДСТРОКОЙ — одна лишняя буква, и «наруот» не находит ровным счётом ничего.
 *
 * Что умеет:
 *  • опечатки — расстояние Дамерау—Левенштейна, где перестановка соседних букв стоит
 *    одну правку, а не две (самый частый промах пальцев);
 *  • не ту раскладку — «Yfhenj» это «наруто», «,kbx» это «блич»;
 *  • регистр, ё/е, дефисы, лишние пробелы: «Человек-бензопила» = «человек бензопила»;
 *  • несколько названий одного тайтла — русское, оригинальное, альтернативное.
 *
 * Функция ничего не выбрасывает и ни во что не ходит: получает список кандидатов,
 * возвращает его же, отранжированным. Решать, где обрезать, — делу вызывающего.
 */

/**
 * Чем именно совпало. Порядок объявления — это и порядок «качества» совпадения.
 */
enum class TitleMatchKind {
    /** Название целиком равно запросу (с точностью до регистра, ё/е и пунктуации). */
    EXACT,

    /** Название начинается с запроса: «наруто» → «Наруто: Ураганные хроники». */
    PREFIX,

    /** С запроса начинается какое-то слово названия: «титан» → «Атака титанов». */
    WORD_PREFIX,

    /** Запрос встречается внутри названия. */
    CONTAINS,

    /** Все слова запроса нашлись в названии, но не подряд. */
    ALL_WORDS,

    /** Совпало только после исправления опечатки. */
    FUZZY,
}

/** Один найденный тайтл с оценкой. */
data class TitleMatch(
    val anime: Anime,
    /** 0..1, больше — лучше. Не вероятность, а порядковая мера: сравнивать между собой. */
    val score: Double,
    val kind: TitleMatchKind,
    /** Какое из названий тайтла сработало (русское, оригинальное, альтернативное). */
    val matchedTitle: String,
    /** Совпало только после починки раскладки («Yfhenj» → «Наруто»). */
    val layoutFixed: Boolean = false,
)

/**
 * Поиск по названию среди [candidates].
 *
 * Возвращает только то, что хоть как-то совпало, по убыванию оценки. При равных
 * оценках порядок кандидатов сохраняется — источник уже отсортировал их по
 * популярности, и перетасовывать это без причины нельзя.
 */
fun searchTitles(query: String, candidates: List<Anime>, limit: Int = Int.MAX_VALUE): List<TitleMatch> {
    val raw = query.trim()
    if (raw.isEmpty() || candidates.isEmpty() || limit <= 0) return emptyList()

    val queries = queryVariants(raw)
    if (queries.isEmpty()) return emptyList()

    val out = ArrayList<TitleMatch>()
    for (anime in candidates) {
        var best: TitleMatch? = null
        for (variant in queries) {
            for ((title, fieldWeight) in titleVariants(anime)) {
                val hit = scoreOneTitle(variant, title) ?: continue
                val score = hit.second * fieldWeight * (if (variant.layoutFixed) LAYOUT_PENALTY else 1.0)
                if (best == null || score > best.score) {
                    best = TitleMatch(anime, score, hit.first, title, variant.layoutFixed)
                }
            }
        }
        if (best != null) out.add(best)
    }
    // sortedByDescending стабильна — исходный порядок при равных оценках уцелеет.
    return out.sortedByDescending { it.score }.take(limit)
}

/** Насколько «дешевле» совпадение, потребовавшее починки раскладки. */
private const val LAYOUT_PENALTY = 0.95

/**
 * Вес поля. Русское название весит больше оригинального: если запрос совпал точно и
 * с тем, и с другим у РАЗНЫХ тайтлов, человек почти наверняка имел в виду тот, у
 * которого совпало основное название.
 */
private const val WEIGHT_MAIN_TITLE = 1.0
private const val WEIGHT_ALT_TITLE = 0.97

/**
 * Все названия тайтла, по которым имеет смысл искать, вместе с весом поля.
 *
 * У [Anime] нет отдельных полей под английское и альтернативные названия — их и
 * добывать приходится из того, что есть:
 *
 *  • [Anime.title] — русское. Иногда приходит склейкой «Наруто / Naruto» (так отдаёт
 *    AnimeVost) — режем по «/».
 *  • [Anime.status] — ОРИГИНАЛЬНОЕ название у Anixart (`title_original`) и AnimeOn
 *    (`title_orig`). Поле неочевидное: у Shikimori, Kodik и AniLibria в нём лежит
 *    статус выхода, а у AnimeVost — тип («ТВ (25 эп.), 25 мин.»). Поэтому строка
 *    берётся как название только если не похожа на статус (см. [looksLikeStatus]).
 *  • [Anime.description] — Kodik дописывает туда «Оригинал: <название>»; вытаскиваем.
 */
fun titleVariants(anime: Anime): List<Pair<String, Double>> {
    val out = ArrayList<Pair<String, Double>>(4)
    fun add(s: String?, weight: Double) {
        val v = s?.trim().orEmpty()
        if (v.isEmpty()) return
        if (out.none { it.first.equals(v, ignoreCase = true) }) out.add(v to weight)
    }
    add(anime.title, WEIGHT_MAIN_TITLE)
    // «Наруто / Naruto» — две отдельные цели поиска, а не одна длинная строка.
    if (anime.title.contains('/') || anime.title.contains('|')) {
        anime.title.split('/', '|').forEach { add(it, WEIGHT_MAIN_TITLE) }
    }
    if (!looksLikeStatus(anime.status)) add(anime.status, WEIGHT_ALT_TITLE)
    ORIGINAL_IN_DESCRIPTION.find(anime.description)?.groupValues?.get(1)?.let { add(it, WEIGHT_ALT_TITLE) }
    return out
}

private val ORIGINAL_IN_DESCRIPTION = Regex("""Оригинал:\s*(.+)""")

/** Слова, которые источники кладут в [Anime.status] ВМЕСТО оригинального названия. */
private val STATUS_WORDS = setOf(
    "ongoing", "released", "anons", "announced", "in production", "finished",
    "онгоинг", "вышел", "вышло", "анонс", "выходит", "завершён", "завершен",
)

/** «ТВ (25 эп.), 25 мин.» и подобное — это тип, а не название. */
private val STATUS_TYPE_PREFIX =
    Regex("""^(тв|ова|ona|ova|фильм|спешл|полнометражный)\b""", RegexOption.IGNORE_CASE)

private fun looksLikeStatus(s: String): Boolean {
    val v = s.trim()
    if (v.isEmpty()) return true
    if (v.lowercase() in STATUS_WORDS) return true
    return STATUS_TYPE_PREFIX.containsMatchIn(v)
}

/** Нормализованный вариант запроса. */
private class QueryVariant(
    val text: String,
    val compact: String,
    val words: List<String>,
    val layoutFixed: Boolean,
)

private fun queryVariants(raw: String): List<QueryVariant> {
    val lower = raw.lowercase()
    val forms = LinkedHashMap<String, Boolean>() // текст → «чинили ли раскладку»
    fun offer(s: String, fixed: Boolean) {
        val n = normalizeTitle(s)
        if (n.isNotEmpty() && (n !in forms || !fixed)) forms[n] = fixed
    }
    offer(lower, false)
    // Раскладку чиним ДО нормализации: «,kbx» это «блич», а нормализация сначала
    // выбросила бы запятую — и «б» из названия взяться было бы неоткуда.
    toRussianLayout(lower)?.let { offer(it, true) }
    toLatinLayout(lower)?.let { offer(it, true) }
    return forms.map { (text, fixed) ->
        QueryVariant(text, text.replace(" ", ""), text.split(' ').filter { it.isNotEmpty() }, fixed)
    }
}

/**
 * Приведение к сравнимому виду: регистр, ё→е, любая пунктуация и дефисы → пробел,
 * лишние пробелы схлопнуты.
 */
fun normalizeTitle(s: String): String =
    s.lowercase().replace('ё', 'е').replace(NON_ALNUM, " ").trim().replace(SPACES, " ")

private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
private val SPACES = Regex("""\s{2,}""")

// ---- раскладка --------------------------------------------------------------

// Физическая клавиатура одна, надписи на ней две. Таблицы стоят символ-в-символ:
// QWERTY-ряды сверху вниз и те же клавиши в ЙЦУКЕН.
private const val LAYOUT_EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,./`"
private const val LAYOUT_RU = "йцукенгшщзхъфывапролджэячсмитьбю.ё"

private val EN_TO_RU: Map<Char, Char> = LAYOUT_EN.indices.associate { LAYOUT_EN[it] to LAYOUT_RU[it] }
private val RU_TO_EN: Map<Char, Char> = LAYOUT_RU.indices.associate { LAYOUT_RU[it] to LAYOUT_EN[it] }

/** «Yfhenj» → «наруто». Null, если менять было нечего. */
fun toRussianLayout(s: String): String? = convertLayout(s, EN_TO_RU)

/** «идуфср» → «bleach». Null, если менять было нечего. */
fun toLatinLayout(s: String): String? = convertLayout(s, RU_TO_EN)

private fun convertLayout(s: String, table: Map<Char, Char>): String? {
    var changed = false
    val sb = StringBuilder(s.length)
    for (ch in s) {
        val mapped = table[ch]
        if (mapped != null && mapped != ch) {
            changed = true
            sb.append(mapped)
        } else {
            sb.append(ch)
        }
    }
    return if (changed) sb.toString() else null
}

// ---- оценка одного названия --------------------------------------------------

private fun scoreOneTitle(q: QueryVariant, rawTitle: String): Pair<TitleMatchKind, Double>? {
    val t = normalizeTitle(rawTitle)
    if (t.isEmpty()) return null

    if (t == q.text) return TitleMatchKind.EXACT to 1.0
    // Равенство «без разделителей» — тот же EXACT, но чуть дешевле: «rezero» и
    // «Re:Zero» это одно, однако человек всё-таки написал не то, что напечатано.
    val tCompact = t.replace(" ", "")
    if (tCompact.isNotEmpty() && tCompact == q.compact) return TitleMatchKind.EXACT to 0.98

    if (q.text.length >= 2) {
        if (t.startsWith(q.text)) return TitleMatchKind.PREFIX to 0.85
        val words = t.split(' ')
        if (words.any { it.startsWith(q.text) }) return TitleMatchKind.WORD_PREFIX to 0.75
        if (t.contains(q.text)) return TitleMatchKind.CONTAINS to 0.65
        if (tCompact.contains(q.compact)) return TitleMatchKind.CONTAINS to 0.62
        if (q.words.size > 1 && q.words.all { qw -> words.any { it.startsWith(qw) } }) {
            return TitleMatchKind.ALL_WORDS to 0.55
        }
    }

    // Опечатки. Порог зависит от длины ЗАПРОСА: это то, что человек напечатал, и
    // ошибок в нём тем больше, чем длиннее строка. Одна правка примерно на пять-шесть
    // символов; на трёх буквах правок нет вовсе, иначе «Блич» и «Боруто» слипаются.
    val maxDist = maxTypos(q.text.length)
    if (maxDist == 0) return null

    var bestScore = 0.0
    val whole = damerauLevenshtein(q.text, t, maxDist)
    if (whole in 1..maxDist) bestScore = FUZZY_BASE - FUZZY_STEP * whole
    // Однословный запрос сверяем ещё и с каждым словом названия: «титанв» должно
    // находить «Атака титанов», где полная строка отличается на пол-названия.
    if (q.words.size == 1) {
        for (word in t.split(' ')) {
            val d = damerauLevenshtein(q.text, word, maxDist)
            if (d in 1..maxDist) bestScore = max(bestScore, FUZZY_WORD_BASE - FUZZY_STEP * d)
        }
    }
    return if (bestScore > 0.0) TitleMatchKind.FUZZY to bestScore else null
}

// Базы подобраны так, чтобы САМАЯ дешёвая опечатка (0.42) была ниже самого дешёвого
// честного вхождения (ALL_WORDS = 0.55): «вхождение выше исправленной опечатки».
private const val FUZZY_BASE = 0.50
private const val FUZZY_WORD_BASE = 0.44
private const val FUZZY_STEP = 0.08

/** Сколько правок прощаем строке такой длины. */
fun maxTypos(length: Int): Int = when {
    length <= 3 -> 0
    length <= 6 -> 1
    length <= 11 -> 2
    else -> 3
}

/**
 * Расстояние Дамерау—Левенштейна (вариант OSA): вставка, удаление, замена и
 * ПЕРЕСТАНОВКА соседних символов — по одной правке каждая.
 *
 * Перестановка считается одной правкой не для красоты: «наруот» вместо «наруто» —
 * самый частый промах пальцев, а по чистому Левенштейну это две правки, и порог для
 * шестибуквенного слова (одна правка) такое бы отбросил.
 *
 * Возвращает [max] + 1, если расстояние заведомо больше порога.
 */
fun damerauLevenshtein(a: String, b: String, max: Int): Int {
    if (a == b) return 0
    if (abs(a.length - b.length) > max) return max + 1
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    var prev2 = IntArray(b.length + 1)
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)

    for (i in 1..a.length) {
        cur[0] = i
        var rowMin = cur[0]
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var v = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                v = min(v, prev2[j - 2] + 1)
            }
            cur[j] = v
            rowMin = min(rowMin, v)
        }
        // Вся строка уже дороже порога — дальше только хуже.
        if (rowMin > max) return max + 1
        val tmp = prev2
        prev2 = prev
        prev = cur
        cur = tmp
    }
    return prev[b.length]
}

// ---- что показать человеку ---------------------------------------------------

/**
 * Подсказка над выдачей: почему показано именно это.
 *
 * Нужна ровно затем, чтобы поиск не выглядел «умничающим»: человек напечатал
 * «Yfhenj», а получил «Наруто» — без строчки «искали „наруто“» это читается как сбой.
 * Пусто, когда объяснять нечего.
 */
fun searchHint(query: String, matches: List<TitleMatch>): String {
    val top = matches.firstOrNull() ?: return ""
    return when {
        top.layoutFixed -> {
            val fixed = toRussianLayout(query.lowercase()) ?: toLatinLayout(query.lowercase())
            if (fixed.isNullOrBlank()) "" else "Искали «${fixed.trim()}»"
        }
        top.kind == TitleMatchKind.FUZZY -> "Точного совпадения нет — показано похожее"
        else -> ""
    }
}
