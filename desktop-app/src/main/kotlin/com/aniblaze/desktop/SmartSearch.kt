package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.CatalogFilter
import com.aniblaze.aggregator.model.CatalogTag
import com.aniblaze.aggregator.model.ContentType
import com.aniblaze.aggregator.model.EpisodeRange
import com.aniblaze.aggregator.model.TitleStatus
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Умный поиск: чистый Kotlin, ни Compose, ни сети. На вход — уже собранный список
 * кандидатов, на выход — отранжированный.
 *
 * Три публичные точки входа, всё остальное — их внутренности:
 *
 *  1. [searchTitles]       — поиск по названию, терпящий опечатки и раскладку;
 *  2. [parseNaturalQuery]  — разбор запроса «человеческим языком» в [SearchIntent];
 *  3. [rankByIntent]       — ранжирование кандидатов по этому разбору.
 *
 * Функции ничего не знают ни про источники, ни про порядок, в котором пришли
 * кандидаты: они СЧИТАЮТ ОЦЕНКУ, а не фильтруют. Отрезать хвост — дело вызывающего.
 */

// =============================================================================
// ЧАСТЬ 1. Поиск по названию
// =============================================================================

/**
 * Чем именно совпало. Порядок объявления — это и порядок «качества» совпадения,
 * от которого пляшут веса в [kindScore].
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
 * Ищет по ВСЕМ названиям, до которых можно дотянуться (см. [titleVariants]):
 * русскому, оригинальному и альтернативным. Терпит опечатки, неправильную раскладку,
 * регистр, ё/е, дефисы и лишние пробелы.
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
 *  • [Anime.title] — русское (у Anixart `title_ru`, у Kodik `title`). Иногда приходит
 *    склейкой «Наруто / Naruto» (так отдаёт AnimeVost) — режем по «/».
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
private val STATUS_TYPE_PREFIX = Regex("""^(тв|ова|ona|ova|onа|фильм|спешл|полнометражный)\b""", RegexOption.IGNORE_CASE)

private fun looksLikeStatus(s: String): Boolean {
    val v = s.trim()
    if (v.isEmpty()) return true
    if (v.lowercase() in STATUS_WORDS) return true
    return STATUS_TYPE_PREFIX.containsMatchIn(v)
}

/** Нормализованный вариант запроса. */
private class QueryVariant(val text: String, val compact: String, val words: List<String>, val layoutFixed: Boolean)

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
 * лишние пробелы схлопнуты. «Человек-бензопила» и «человек бензопила» после этого —
 * одна и та же строка.
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

// =============================================================================
// ЧАСТЬ 2. Разбор запроса естественным языком
// =============================================================================

/** Настроение. Не жанр: «мрачное» — это про ощущение, а не про строку жанров. */
enum class Mood { DARK, LIGHT, FUNNY, VIOLENT, SAD, SCARY }

/** Пожелание по длине. Конкретные границы — в [rankByIntent]. */
enum class LengthWish { SHORT, LONG }

/** Прочие пожелания, которые не жанр и не настроение. */
enum class Trait { STRONG_LEAD }

/**
 * Разобранный запрос. Всё, что удалось вытащить из фразы, — по полям; то, что не
 * удалось, остаётся в [freeText] (обычно это название, и его стоит скормить
 * [searchTitles]).
 */
data class SearchIntent(
    val raw: String,
    /** «как X», «похожее на X», «в духе X» — ИМЯ тайтла, ещё не найденный тайтл. */
    val similarTo: String? = null,
    /** Обязательные жанры, ключи [CatalogTag.key]. */
    val includeTags: Set<String> = emptySet(),
    /** «без гарема», «не романтика». */
    val excludeTags: Set<String> = emptySet(),
    val moods: Set<Mood> = emptySet(),
    /** «не мрачное», «менее жестокое». Сравнительная степень тоже сюда: «менее X» —
     *  это «X нежелателен», а точка отсчёта задаётся эталоном в [rankByIntent]. */
    val excludedMoods: Set<Mood> = emptySet(),
    val length: LengthWish? = null,
    val traits: Set<Trait> = emptySet(),
    val yearFrom: Int = 0,
    val yearTo: Int = 0,
    /** Нижняя граница оценки в ДЕСЯТИБАЛЛЬНОЙ шкале (как в [CatalogFilter.minRating]). */
    val minRating: Double = 0.0,
    val contentType: ContentType? = null,
    val status: TitleStatus? = null,
    /** Слова, которые ничем не опознались, — почти всегда название. */
    val freeText: String = "",
) {
    /** Ни одного условия — значит, разбирать было нечего, ищите по названию. */
    val isEmpty: Boolean
        get() = similarTo == null && includeTags.isEmpty() && excludeTags.isEmpty() &&
            moods.isEmpty() && excludedMoods.isEmpty() && length == null && traits.isEmpty() &&
            yearFrom == 0 && yearTo == 0 && minRating <= 0.0 && contentType == null && status == null

    /**
     * То из разбора, что каталог умеет спросить у сервера.
     *
     * Исключения и настроения сюда НЕ переводятся — [CatalogFilter] умеет только «И»
     * по тегам, а «без гарема» и «мрачное» это мягкие условия; их считает
     * [rankByIntent] уже на выданных кандидатах.
     */
    fun toCatalogFilter(): CatalogFilter = CatalogFilter(
        tags = includeTags,
        yearFrom = yearFrom,
        yearTo = yearTo,
        status = status,
        contentType = contentType,
        minRating = minRating,
        episodes = when (length) {
            // Границы каталога не совпадают с нашими один в один (SHORT там 2—12), но
            // это ближайший честный диапазон из тех, что сервер понимает.
            LengthWish.SHORT -> EpisodeRange.SHORT
            LengthWish.LONG -> EpisodeRange.LONG
            null -> null
        },
    )
}

/**
 * Разобрать запрос человеческим языком.
 *
 * Порядок работы: сначала выкусывается «похожее на X» (иначе слова из названия
 * пошли бы в разбор жанров), потом числа (год, рейтинг), потом фраза идёт по словам
 * со словарём понятий. Отрицание («без», «не», «менее») навешивается на ПЕРВОЕ
 * распознанное понятие в пределах трёх слов и на нём же гаснет.
 */
fun parseNaturalQuery(query: String): SearchIntent {
    val raw = query.trim()
    if (raw.isEmpty()) return SearchIntent(raw = "")

    var rest = raw
    var similar: String? = null

    // --- «как X» / «похожее на X» / «в духе X» -------------------------------
    val m = SIMILAR_RE.find(rest)
    if (m != null) {
        val captured = m.groupValues[1]
        val name = cutTitleName(captured)
        // «что-то вроде комедии» — после «вроде» стоит ЖАНР, а не название. Такое
        // в similarTo класть нельзя: поиск по названию ничего не найдёт, а жанр
        // потеряется.
        if (name.isNotEmpty() && !looksLikeConcepts(name)) {
            similar = name
            val capturedStart = m.range.last - captured.length + 1
            rest = rest.removeRange(m.range.first, capturedStart + name.length)
        }
    }

    // --- числа ---------------------------------------------------------------
    var yearFrom = 0
    var yearTo = 0
    var minRating = 0.0
    RATING_RE.find(rest)?.let { minRating = it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0 }
    YEAR_FROM_RE.find(rest)?.let { yearFrom = it.groupValues[1].toInt() }
    YEAR_TO_RE.find(rest)?.let { yearTo = it.groupValues[1].toInt() }
    if (yearFrom == 0 && yearTo == 0) {
        YEAR_PLAIN_RE.find(rest)?.let {
            yearFrom = it.groupValues[1].toInt()
            yearTo = yearFrom
        }
    }

    // --- пословный разбор ----------------------------------------------------
    val tokens = tokensOf(rest)
    val stems = tokens.map(::stemRu)

    val include = LinkedHashSet<String>()
    val exclude = LinkedHashSet<String>()
    val moods = LinkedHashSet<Mood>()
    val noMoods = LinkedHashSet<Mood>()
    var length: LengthWish? = null
    var contentType: ContentType? = null
    var status: TitleStatus? = null
    val free = ArrayList<String>()

    var i = 0
    var negated = false
    var negationEndsAt = -1
    while (i < tokens.size) {
        if (negated && i > negationEndsAt) negated = false
        val stem = stems[i]
        if (stem in NEGATIONS) {
            negated = true
            negationEndsAt = i + 3
            i++
            continue
        }
        // Многословные понятия («боевые искусства», «путешествие во времени») сначала:
        // иначе «боевые» ушло бы в мусор, а «искусства» не нашлось бы вовсе.
        var hit: Pair<Concept, Int>? = null
        for (n in 3 downTo 1) {
            if (i + n > tokens.size) continue
            val key = stems.subList(i, i + n).joinToString(" ")
            val c = CONCEPTS[key]
            if (c != null) {
                hit = c to n
                break
            }
        }
        if (hit == null) {
            if (stem !in STOPWORDS) free.add(tokens[i])
            i++
            continue
        }
        val (concept, span) = hit
        concept.tag?.let { if (negated) exclude.add(it.key) else include.add(it.key) }
        concept.mood?.let { if (negated) noMoods.add(it) else moods.add(it) }
        concept.length?.let { if (!negated) length = it }
        concept.type?.let { if (!negated) contentType = it }
        concept.status?.let { if (!negated) status = it }
        if (concept.minRating > 0.0 && !negated && minRating <= 0.0) minRating = concept.minRating
        if (concept.recentYears > 0 && !negated && yearFrom == 0) {
            yearFrom = java.time.LocalDate.now().year - concept.recentYears
        }
        if (concept.oldBefore > 0 && !negated && yearTo == 0) yearTo = concept.oldBefore
        negated = false
        i += span
    }

    // --- «с сильным главным героем» -----------------------------------------
    // Не один словарный ключ, а ПАРА слов на расстоянии: между «сильным» и «героем»
    // может стоять что угодно («главным», «мужским», «оп»), перечислять бессмысленно.
    val traits = LinkedHashSet<Trait>()
    val strongIdx = stems.indices.filter { stems[it] in STRONG_WORDS }
    val heroIdx = stems.indices.filter { stems[it] in HERO_WORDS }
    if (strongIdx.any { s -> heroIdx.any { h -> abs(h - s) <= 3 } }) {
        traits.add(Trait.STRONG_LEAD)
        free.removeAll { normalizeTitle(it).let { n -> stemRu(n) in STRONG_WORDS || stemRu(n) in HERO_WORDS } }
        free.removeAll { stemRu(it) in LEAD_FILLER }
    }

    return SearchIntent(
        raw = raw,
        similarTo = similar,
        includeTags = include,
        excludeTags = exclude,
        moods = moods,
        excludedMoods = noMoods,
        length = length,
        traits = traits,
        yearFrom = yearFrom,
        yearTo = yearTo,
        minRating = minRating,
        contentType = contentType,
        status = status,
        freeText = free.filter { it.toIntOrNull() == null }.joinToString(" "),
    )
}

// ---- вытаскивание имени тайтла ----------------------------------------------

// `\b` в Java по умолчанию не знает кириллицы, поэтому границы слова заданы руками:
// пробел (или начало строки) слева и обязательный пробел справа от ключевого слова.
private val SIMILAR_RE = Regex(
    """(?:^|\s)(?:похож\S*\s+на|в\s+духе|наподоби[ея]|аналог\S*|вроде|типа|как)\s+([^,;.!?]+)""",
    RegexOption.IGNORE_CASE,
)

/** Слова, на которых имя тайтла заканчивается: «как Overlord но без гарема». */
private val NAME_STOP = listOf(
    " но ", " только ", " без ", " кроме ", " менее ", " более ", " поменьше ", " побольше ",
    " чтобы ", " с ", " не ",
)

private fun cutTitleName(captured: String): String {
    var s = " ${captured.trim()} "
    for (stop in NAME_STOP) {
        val at = s.indexOf(stop, startIndex = 1)
        if (at > 0) s = s.substring(0, at + 1)
    }
    return s.trim().trim('«', '»', '"', '\'', '-', '—')
}

/** Всё ли во фразе — известные понятия (жанр, настроение, длина)? */
private fun looksLikeConcepts(name: String): Boolean {
    val stems = tokensOf(name).map(::stemRu).filter { it !in STOPWORDS }
    if (stems.isEmpty()) return false
    return stems.all { it in CONCEPTS }
}

// ---- числа ------------------------------------------------------------------

private val RATING_RE = Regex("""рейтинг\S*\s+(?:от\s+|выше\s+)?(\d(?:[.,]\d)?)""", RegexOption.IGNORE_CASE)
private val YEAR_FROM_RE = Regex("""(?:с|после|начиная\s+с)\s+((?:19|20)\d{2})""", RegexOption.IGNORE_CASE)
private val YEAR_TO_RE = Regex("""(?:до|раньше)\s+((?:19|20)\d{2})""", RegexOption.IGNORE_CASE)
private val YEAR_PLAIN_RE = Regex("""((?:19|20)\d{2})""")

// ---- словарь ----------------------------------------------------------------

private fun tokensOf(s: String): List<String> =
    s.lowercase().replace('ё', 'е').split(NON_ALNUM).filter { it.isNotBlank() }

/**
 * Грубая нормализация русского слова: снимает ОДНО падежное/родовое окончание, если
 * от слова остаётся хотя бы четыре буквы.
 *
 * Не морфология, а способ свести «романтики» и «романтика», «гарема» и «гарем»,
 * «мрачное» и «мрачный» к одной строке. Порядок окончаний — от длинных к коротким,
 * иначе «ого» никогда бы не сработало: сначала снялось бы «о».
 */
fun stemRu(word: String): String {
    val w = word.lowercase().replace('ё', 'е')
    if (w.length <= 4) return w
    for (end in ENDINGS) {
        if (w.length - end.length >= 4 && w.endsWith(end)) return w.dropLast(end.length)
    }
    return w
}

private val ENDINGS = listOf(
    "ого", "его", "ому", "ему", "ыми", "ими", "ями", "ами",
    "ый", "ий", "ой", "ая", "яя", "ое", "ее", "ые", "ие", "ом", "ем", "ым", "им",
    "ах", "ях", "ов", "ев", "ью", "ья", "ам", "ям",
    "а", "я", "ы", "и", "е", "о", "у", "ю", "й", "ь",
)

/** Одно понятие словаря. Плоская запись вместо иерархии — полей мало, а веток много. */
private class Concept(
    val tag: CatalogTag? = null,
    val mood: Mood? = null,
    val length: LengthWish? = null,
    val type: ContentType? = null,
    val status: TitleStatus? = null,
    val minRating: Double = 0.0,
    val recentYears: Int = 0,
    val oldBefore: Int = 0,
)

private val NEGATIONS = setOf("без", "не", "кроме", "минус", "мене", "поменьш", "исключ", "нет")

private val STOPWORDS = setOf(
    "аниме", "тайтл", "мультик", "что", "то", "нибуд", "как", "какое", "какой", "каки",
    "посовету", "посоветуй", "найд", "хоч", "хочу", "смотрет", "посмотрет", "мне", "мне",
    "про", "об", "о", "с", "со", "и", "но", "а", "в", "во", "на", "для", "где", "ест",
    "тольк", "же", "ну", "бы", "это", "очен", "вроде", "типа", "духе", "наподоби", "аналог",
    "штук", "серия", "сериа", "главн", "мир", "лучш", "мног", "мало", "один", "вечер", "все",
)

private val STRONG_WORDS = setOf("сильн", "могуществен", "имба", "оп", "op", "непобедим", "мощн", "читерск")
private val HERO_WORDS = setOf("гг", "геро", "протагонист", "мс", "персонаж", "герой")
private val LEAD_FILLER = setOf("главн", "мужск", "женск")

/**
 * Словарь: стеммированная фраза (1—3 слова) → понятие.
 *
 * Жанры НЕ выдуманы — они берутся из [CatalogTag] (и подпись, и точное имя жанра в
 * каталоге Anixart), а руками дописаны только синонимы, которыми люди пишут запросы.
 * Оттуда же и подстановки для жанров, которых в каталоге просто нет: «магия» → Фэнтези,
 * «постапокалипсис» → Выживание, «демоны» → Сверхъестественное (см. комментарий к
 * [CatalogTag]).
 */
private val CONCEPTS: Map<String, Concept> = buildMap {
    fun put(phrase: String, concept: Concept) {
        val key = tokensOf(phrase).joinToString(" ") { stemRu(it) }
        if (key.isNotEmpty() && key !in this) this[key] = concept
    }

    // 1) всё, что каталог называет сам
    for (tag in CatalogTag.entries) {
        put(tag.label, Concept(tag = tag))
        if (tag.anixart.isNotBlank()) put(tag.anixart, Concept(tag = tag))
    }

    // 2) как люди это называют на самом деле
    fun syn(tag: CatalogTag, vararg words: String) = words.forEach { put(it, Concept(tag = tag)) }
    syn(CatalogTag.FANTASY, "фентези", "фантези", "магия", "магический", "волшебство", "волшебный", "чародейство")
    syn(CatalogTag.ISEKAI, "исекай", "изекай", "попаданцы", "попаданец", "другой мир", "иной мир")
    syn(CatalogTag.ROMANCE, "любовь", "любовный", "лавстори", "мелодрама")
    syn(CatalogTag.HAREM, "гаремник", "гаремное")
    syn(CatalogTag.COMEDY, "юмор", "юмористическое")
    syn(CatalogTag.ACTION, "экшн", "боевик", "action")
    syn(CatalogTag.SCIFI, "фантастика", "сайфай", "sci fi")
    syn(CatalogTag.HORROR, "хоррор", "ужастик")
    syn(CatalogTag.PSYCHOLOGY, "психология", "психологический")
    syn(CatalogTag.SUPERNATURAL, "мистика", "демоны", "демонический")
    syn(CatalogTag.MECHA, "роботы", "меху")
    syn(CatalogTag.SPORT, "спортивное")
    syn(CatalogTag.SCHOOL, "школьное", "школьный")
    syn(CatalogTag.SLICE_OF_LIFE, "повседневное", "бытовое")
    syn(CatalogTag.MYSTERY, "загадка", "загадочное")
    syn(CatalogTag.WAR, "война", "военный")
    syn(CatalogTag.HISTORY, "история", "исторический")
    syn(CatalogTag.MARTIAL_ARTS, "единоборства", "рукопашка")
    syn(CatalogTag.SURVIVAL, "постапокалипсис", "постапокалиптическое")
    syn(CatalogTag.VIDEOGAMES, "игровой мир", "игры", "гейм")
    syn(CatalogTag.REINCARNATION, "перерождение", "переродился")
    syn(CatalogTag.SUPERPOWER, "суперсила", "суперспособности", "способности", "сверхспособности")
    syn(CatalogTag.SPACE, "космическое")
    syn(CatalogTag.MUSIC, "музыкальное")
    syn(CatalogTag.CRIME, "криминал", "мафия", "якудза")
    syn(CatalogTag.VAMPIRES, "вампирское")
    syn(CatalogTag.ECCHI, "эччи")
    syn(CatalogTag.SHOUNEN, "шонен")
    syn(CatalogTag.SEINEN, "сейнен")
    syn(CatalogTag.SHOUJO, "седзе")
    syn(CatalogTag.MYTHOLOGY, "мифы", "мифический")
    syn(CatalogTag.TIME_TRAVEL, "петля времени", "временные петли")
    syn(CatalogTag.GOURMET, "кулинария", "еда")

    // 3) настроения
    fun mood(m: Mood, vararg words: String) = words.forEach { put(it, Concept(mood = m)) }
    mood(Mood.DARK, "мрачное", "мрачный", "темное", "депрессивное", "тяжелое", "безысходное", "чернуха", "гримдарк")
    mood(Mood.LIGHT, "доброе", "добрый", "милое", "светлое", "уютное", "ламповое", "теплое", "легкое", "позитивное")
    mood(Mood.FUNNY, "веселое", "смешное", "забавное", "угарное", "ржачное")
    mood(Mood.VIOLENT, "жестокое", "жестокость", "кровавое", "кровища", "брутальное", "мясо")
    mood(Mood.SAD, "грустное", "печальное", "слезное", "трогательное", "драматичное")
    mood(Mood.SCARY, "страшное", "жуткое", "пугающее")

    // 4) длина
    fun len(l: LengthWish, vararg words: String) = words.forEach { put(it, Concept(length = l)) }
    len(LengthWish.SHORT, "короткое", "короткий", "короче", "покороче", "недлинное", "мало серий", "немного серий", "один вечер", "односезонное")
    len(LengthWish.LONG, "длинное", "длинный", "подлиннее", "долгое", "затяжное", "много серий", "многосерийное")

    // 5) тип и статус — эти два прямо ложатся в CatalogFilter
    put("полнометражка", Concept(type = ContentType.MOVIE))
    put("полнометражное", Concept(type = ContentType.MOVIE))
    put("ова", Concept(type = ContentType.OVA))
    put("спешл", Concept(type = ContentType.SPECIAL))
    put("онгоинг", Concept(status = TitleStatus.ONGOING))
    put("выходит", Concept(status = TitleStatus.ONGOING))
    put("завершенное", Concept(status = TitleStatus.FINISHED))
    put("завершено", Concept(status = TitleStatus.FINISHED))
    put("анонс", Concept(status = TitleStatus.ANNOUNCED))

    // 6) прочее
    put("топовое", Concept(minRating = 7.5))
    put("лучшее", Concept(minRating = 7.5))
    put("шедевр", Concept(minRating = 8.0))
    put("новое", Concept(recentYears = 2))
    put("свежее", Concept(recentYears = 2))
    put("классика", Concept(oldBefore = 2005))
    put("старое", Concept(oldBefore = 2005))
}

// =============================================================================
// ЧАСТЬ 3. Ранжирование по разбору
// =============================================================================

/** Кандидат с оценкой соответствия разбору и человекочитаемым «почему». */
data class IntentMatch(
    val anime: Anime,
    /** Не нормирована: сравнивать между собой, а не с порогом. Может быть меньше нуля. */
    val score: Double,
    val reasons: List<String>,
)

/**
 * Отранжировать кандидатов по разобранному запросу.
 *
 * [similarTo] — уже НАЙДЕННЫЙ эталон для «как X» (его имя лежит в
 * [SearchIntent.similarTo], а искать его — дело вызывающего через [searchTitles]).
 * Сам эталон из выдачи выбрасывается: «похожее на X» — это не X.
 *
 * Условия мягкие: несовпадение штрафует, но не выкидывает. Жёстко выкидывать нельзя,
 * потому что строка жанров у половины источников неполная, а у части просто пустая —
 * ровно по той же причине, по которой [CatalogFilter.matches] пропускает неизвестное
 * поле. Поэтому НЕИЗВЕСТНОЕ (пустые жанры, нулевое число серий) не штрафуется вовсе.
 */
fun rankByIntent(intent: SearchIntent, candidates: List<Anime>, similarTo: Anime? = null): List<IntentMatch> {
    if (candidates.isEmpty()) return emptyList()
    val refTags = similarTo?.let { CatalogTag.of(it) }.orEmpty()

    val out = ArrayList<IntentMatch>(candidates.size)
    for (anime in candidates) {
        if (similarTo != null && anime.id == similarTo.id) continue
        val tags = CatalogTag.of(anime)
        val why = ArrayList<String>(4)
        var score = 0.0

        // --- обязательные жанры ---------------------------------------------
        if (tags.isNotEmpty()) {
            for (key in intent.includeTags) {
                if (key in tags) {
                    score += W_TAG_HIT
                    why += "жанр ${CatalogTag.byKey(key)?.label ?: key}"
                } else {
                    score -= W_TAG_MISS
                }
            }
            for (key in intent.excludeTags) {
                if (key in tags) {
                    score -= W_TAG_EXCLUDED
                    why += "нежелательный жанр ${CatalogTag.byKey(key)?.label ?: key}"
                }
            }
        }

        // --- настроение -------------------------------------------------------
        for (mood in intent.moods) {
            val signal = MOOD_SIGNALS[mood].orEmpty().count { it in tags }
            val anti = MOOD_ANTI[mood].orEmpty().count { it in tags }
            if (signal > 0) {
                score += min(signal * W_MOOD_SIGNAL, W_MOOD_CAP)
                why += "настроение ${mood.name.lowercase()}"
            }
            if (anti > 0) score -= min(anti * W_MOOD_ANTI, W_MOOD_ANTI_CAP)
            score += ageBonusFor(mood, anime.ageRating)
        }
        for (mood in intent.excludedMoods) {
            val signal = MOOD_SIGNALS[mood].orEmpty().count { it in tags }
            val anti = MOOD_ANTI[mood].orEmpty().count { it in tags }
            if (signal > 0) {
                score -= min(signal * W_NOMOOD_SIGNAL, W_NOMOOD_CAP)
                why += "нежелательное настроение ${mood.name.lowercase()}"
            }
            if (anti > 0) score += min(anti * 0.2, 0.6)
            score -= ageBonusFor(mood, anime.ageRating) * 2.0
        }

        // --- длина -------------------------------------------------------------
        if (intent.length != null && anime.episodesTotal > 0) {
            val eps = anime.episodesTotal
            val fit = when (intent.length) {
                // Границы взяты из того, как аниме реально издаётся: один сезон это
                // 12—13 серий, два — 24—26, дальше уже «долгоиграющее».
                LengthWish.SHORT -> when {
                    eps <= 13 -> 1.0
                    eps <= 26 -> 0.2
                    else -> -1.0
                }
                LengthWish.LONG -> when {
                    eps >= 50 -> 1.0
                    eps >= 26 -> 0.2
                    else -> -1.0
                }
            }
            score += fit
            if (fit > 0) why += if (intent.length == LengthWish.SHORT) "короткое ($eps)" else "длинное ($eps)"
        }

        // --- сильный главный герой ---------------------------------------------
        if (Trait.STRONG_LEAD in intent.traits) {
            var lead = 0.0
            for ((key, w) in STRONG_LEAD_SIGNALS) if (key in tags) lead += w
            if (lead > 0) {
                score += min(lead, W_TRAIT_CAP)
                why += "сильный герой"
            }
        }

        // --- похожесть на эталон ------------------------------------------------
        if (refTags.isNotEmpty() && tags.isNotEmpty()) {
            val union = refTags.size + tags.size - refTags.count { it in tags }
            val jaccard = if (union == 0) 0.0 else refTags.count { it in tags }.toDouble() / union
            if (jaccard > 0) {
                score += jaccard * W_SIMILARITY
                why += "жанрово похоже (%.0f%%)".format(jaccard * 100)
            }
            if (similarTo!!.studio.isNotBlank() && similarTo.studio.equals(anime.studio, ignoreCase = true)) {
                score += W_SAME_STUDIO
                why += "та же студия"
            }
        }

        // --- прочие условия ------------------------------------------------------
        if (intent.minRating > 0.0 && anime.rating > 0.0) {
            val share = anime.rating / anime.ratingMax
            if (share >= intent.minRating / 10.0) score += 0.3 else score -= 0.5
        }
        if (anime.year > 0) {
            if (intent.yearFrom > 0 && anime.year < intent.yearFrom) score -= 0.5
            if (intent.yearTo > 0 && anime.year > intent.yearTo) score -= 0.5
        }
        intent.contentType?.let { if (!it.matches(anime)) score -= 1.5 }
        intent.status?.let { if (!it.matches(anime)) score -= 1.5 }

        // Небольшая надбавка за качество: при прочих равных сверху то, что лучше
        // оценили. Считается в ДОЛЯХ своей шкалы — Anixart меряет из пяти, остальные
        // из десяти, и сырые числа тут перепутали бы всё (см. Anime.ratingMax).
        if (anime.rating > 0.0 && anime.ratingMax > 0.0) score += (anime.rating / anime.ratingMax) * W_QUALITY

        out.add(IntentMatch(anime, score, why))
    }
    return out.sortedByDescending { it.score }
}

// Веса. Выбраны так, чтобы ОДНО прямое требование («фэнтези») весило столько же,
// сколько одно попадание в настроение, а ЗАПРЕТ («без гарема») был вдвое тяжелее
// требования: пожелание — это «хочу», а запрет — «не показывай», и цена ошибки разная.
private const val W_TAG_HIT = 1.0
private const val W_TAG_MISS = 0.8
private const val W_TAG_EXCLUDED = 2.0
private const val W_MOOD_SIGNAL = 0.6
private const val W_MOOD_CAP = 1.8
private const val W_MOOD_ANTI = 0.3
private const val W_MOOD_ANTI_CAP = 0.9
private const val W_NOMOOD_SIGNAL = 0.8
private const val W_NOMOOD_CAP = 2.4
private const val W_TRAIT_CAP = 1.0
private const val W_SIMILARITY = 2.0
private const val W_SAME_STUDIO = 0.4
private const val W_QUALITY = 0.4

/**
 * Чем настроение выдаёт себя в строке жанров. Настроения в каталоге нет — есть
 * жанры, и «мрачное» опознаётся по психологии, ужасам, драме и войне, а не по слову.
 */
private val MOOD_SIGNALS: Map<Mood, Set<String>> = mapOf(
    Mood.DARK to setOf("psychology", "thriller", "horror", "seinen", "drama", "war"),
    Mood.LIGHT to setOf("slice_of_life", "comedy", "kids", "family", "gourmet", "school", "romance"),
    Mood.FUNNY to setOf("comedy", "ecchi", "harem", "school", "slice_of_life"),
    Mood.VIOLENT to setOf("horror", "thriller", "war", "action", "crime", "seinen", "martial_arts", "survival"),
    Mood.SAD to setOf("drama", "romance", "psychology"),
    Mood.SCARY to setOf("horror", "thriller", "supernatural", "mystery"),
)

/** Жанры, прямо противоречащие настроению. */
private val MOOD_ANTI: Map<Mood, Set<String>> = mapOf(
    Mood.DARK to setOf("comedy", "kids", "slice_of_life", "school", "magical_girl"),
    Mood.LIGHT to setOf("horror", "thriller", "psychology", "war"),
    Mood.FUNNY to setOf("drama", "horror", "psychology", "war"),
    Mood.VIOLENT to setOf("kids", "family", "slice_of_life"),
    Mood.SAD to setOf("comedy", "kids"),
    Mood.SCARY to setOf("kids", "comedy"),
)

/**
 * Возрастной рейтинг — второй, независимый от жанров сигнал настроения. «18+» у
 * Anixart стоит у «Берсерка» и «Атаки титанов», «0+» — у «Тоторо»; для «мрачного» и
 * «жестокого» это ровно то, что нужно (шкала — см. AgeRating).
 */
private fun ageBonusFor(mood: Mood, ageRating: Int): Double {
    if (ageRating <= 0) return 0.0
    return when (mood) {
        Mood.DARK, Mood.VIOLENT, Mood.SCARY -> if (ageRating >= 5) 0.3 else 0.0
        Mood.LIGHT -> if (ageRating <= 2) 0.3 else 0.0
        else -> 0.0
    }
}

/**
 * Чем выдаёт себя «сильный главный герой». «Супер сила» — прямое попадание, исекай и
 * реинкарнация — почти всегда про это же (герой приходит в новый мир уже сильным),
 * экшен и боевые искусства только намекают.
 */
private val STRONG_LEAD_SIGNALS: List<Pair<String, Double>> = listOf(
    "superpower" to 0.6,
    "isekai" to 0.3,
    "reincarnation" to 0.3,
    "action" to 0.2,
    "martial_arts" to 0.2,
)
