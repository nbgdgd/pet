package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Подбор похожего и персональных рекомендаций.
 *
 * Чистый Kotlin без Compose и без сети: сюда приходят уже загруженные карточки и
 * сохранённое состояние, отсюда уходит порядок. Именно поэтому всё это проверяется
 * тестами на НАСТОЯЩИХ названиях из каталога, а не в запущенном приложении.
 *
 * Два потребителя:
 *  • [similar] — блок «Похожие» на странице тайтла (см. DesktopRepository.similarTitles);
 *  • [buildTaste] + [recommend] — экран «Рекомендации».
 *
 * Общего у них ровно одно, и оно здесь главное: [franchiseKey]. Сезоны, части,
 * спешлы и фильмы по тайтлу — это НЕ «похожее» и НЕ «рекомендация»: их и так видно
 * в селекторе сезонов, а место в подборке они занимают целиком.
 */
object Recommender {

    // ---------------------------------------------------------------------------
    // Нормализация названий
    // ---------------------------------------------------------------------------

    /**
     * Ключ сравнения названий: нижний регистр, «ё» = «е», любая пунктуация —
     * разделитель. Тот же приём, что у DesktopRepository.dedupeTitles: один тайтл из
     * двух источников приходит с разными кавычками, тире и регистром.
     */
    fun normalizeTitle(raw: String): String =
        raw.lowercase().replace('ё', 'е').replace(NON_WORD, " ").trim().replace(SPACES, " ")

    /**
     * «Корень» франшизы — то, что останется от названия без сезона, части и типа.
     *
     * Каталог называет продолжения ровно четырьмя способами, и все четыре снимаются
     * здесь (примеры из каталога Anixart):
     *  • номером в хвосте — «Магическая битва 2», «Overlord IV»;
     *  • подзаголовком после двоеточия/тире/точки — «Атака титанов: Финал»,
     *    «Клинок, рассекающий демонов: Поезд „Бесконечный“», «Ре:Зеро. Жизнь с нуля…»;
     *  • словом «сезон» — «Магическая битва 2-й сезон», «Overlord 2nd Season»;
     *  • типом издания — «Класс убийц OVA», «Тетрадь смерти Special», «Gintama Movie».
     *
     * Резка по подзаголовку не применяется, когда голова короче [MIN_ROOT_LETTERS]
     * букв: у «Ре:Зеро» двоеточие стоит внутри самого названия, и без этого правила
     * ключом стало бы «ре» — под него попал бы весь каталог.
     */
    fun franchiseKey(raw: String): String {
        var text = raw.lowercase().replace('ё', 'е').trim()
        // 1. Отрезать подзаголовок по первому разделителю, у которого голова осмысленна.
        for (match in SUBTITLE_SEPARATOR.findAll(text)) {
            val head = text.substring(0, match.range.first)
            if (head.count { it.isLetter() } >= MIN_ROOT_LETTERS) {
                text = head
                break
            }
        }
        // 2. Снимать хвостовые пометки, пока снимается: «Overlord 2nd Season» — это
        //    и «season», и номер, а «Наруто: Ураганные хроники фильм 3» — тип и номер.
        var changed = true
        while (changed) {
            changed = false
            for (marker in TRAILING_MARKERS) {
                val stripped = marker.replace(text, "")
                if (stripped != text && stripped.count { it.isLetter() } >= MIN_ROOT_LETTERS) {
                    text = stripped.trim()
                    changed = true
                }
            }
        }
        val key = normalizeTitle(text)
        // Осталась пара букв — сравнивать по такому нельзя, возвращаем полное имя.
        return if (key.count { it.isLetter() } >= MIN_ROOT_LETTERS) key else normalizeTitle(raw)
    }

    /**
     * Один ли это тайтл (сезон/часть/спешл/фильм по нему) или разные.
     *
     * Кроме совпадения корней есть второе правило — вхождение целым префиксом:
     * «Бездомный бог ARAGOTO» и «Мастера меча онлайн Алисизация» продолжают название
     * БЕЗ разделителя вовсе, и по корню их не поймать. Префикс засчитывается только
     * от [MIN_PREFIX_LETTERS] букв: иначе «Врата» съели бы «Врата Штейна».
     */
    fun isSameFranchise(a: String, b: String): Boolean {
        val ka = franchiseKey(a)
        val kb = franchiseKey(b)
        return sameFranchiseKeys(ka, kb)
    }

    private fun sameFranchiseKeys(ka: String, kb: String): Boolean {
        if (ka.isBlank() || kb.isBlank()) return false
        if (ka == kb) return true
        val longer = if (ka.length >= kb.length) ka else kb
        val shorter = if (ka.length >= kb.length) kb else ka
        if (shorter.count { it.isLetter() } < MIN_PREFIX_LETTERS) return false
        return longer.startsWith("$shorter ")
    }

    // ---------------------------------------------------------------------------
    // Признаки тайтла
    // ---------------------------------------------------------------------------

    /** Жанры карточки как список нормализованных слов. Источники разделяют по-разному. */
    fun genresOf(anime: Anime): List<String> = splitGenres(anime.genres)

    private fun splitGenres(raw: String): List<String> =
        raw.split(',', ';', '·', '/')
            .map { it.trim().lowercase().replace('ё', 'е') }
            .filter { it.isNotBlank() }
            .distinct()

    /**
     * Тематика — из описания, названия и жанров, по словарю [THEMES].
     *
     * Зачем вообще: жанр «Экшен» стоит у 710 карточек и почти ничего не говорит, а
     * «космос + меха» и «школа + романтика» — это уже два разных зрителя. Словарь
     * намеренно короткий и по корням слов: описание каталога русское, склонения
     * любые, и «магией», «магический», «маг» должны попасть в одну тему.
     */
    fun themesOf(anime: Anime): Set<String> {
        val text = (anime.title + " " + anime.genres + " " + anime.description)
            .lowercase().replace('ё', 'е')
        if (text.isBlank()) return emptySet()
        val out = HashSet<String>()
        for ((theme, roots) in THEMES) {
            if (roots.any { text.contains(it) }) out.add(theme)
        }
        return out
    }

    /** Оценка в долях своей шкалы (0..1). Шкалы у источников разные — см. Anime.ratingMax. */
    private fun normRating(anime: Anime): Double {
        val max = if (anime.ratingMax > 0.0) anime.ratingMax else 5.0
        if (anime.rating <= 0.0 || anime.rating > max) return 0.0
        return anime.rating / max
    }

    /**
     * Насколько оценке можно верить.
     *
     * Число голосов Anixart в карточке НЕ отдаёт (ratingVotes там остаётся нулём —
     * см. AnixartSource.animeFrom), зато отдаёт favorites_count и watching_count.
     * Поэтому «сколько людей причастны к оценке» берётся как максимум из всего, что
     * вообще известно, и превращается в 0..1 насыщением.
     */
    private fun confidenceOf(anime: Anime): Double {
        val votes = maxOf(anime.ratingVotes, anime.malVotes, anime.favoritesCount, anime.watchingCount)
        if (votes <= 0) return 0.0
        return votes / (votes + VOTES_HALF)
    }

    // ---------------------------------------------------------------------------
    // «Похожие» на странице тайтла
    // ---------------------------------------------------------------------------

    /*
     * Веса признаков. Сумма положительных ≈ 100, чтобы число читалось как проценты
     * совпадения, и выбраны они не на глаз:
     *
     *  40  ЖАНРЫ — единственный признак, который был в старой версии, и он
     *      действительно самый сильный: зритель «Экшен + Фэнтези» почти никогда не
     *      хочет «Повседневность + Драма». Но одного его мало — потому и 40, а не всё.
     *  18  ТЕМА ОПИСАНИЯ — то, чего жанрам не хватает: «Фэнтези» одинаково стоит у
     *      исекая про попаданца и у сказки про ведьму, а по описанию они расходятся.
     *  12  СТУДИЯ — почерк (Kyoto Animation, ufotable, MAPPA) люди выбирают осознанно;
     *      совпадение студии — сильная, но узкая примета: студий у тайтла одна.
     *  10  ОЦЕНКА — половина за «тоже хорошее», половина за «того же уровня»: после
     *      шедевра предлагать проходняк с теми же жанрами — плохая рекомендация.
     *      Умножается на доверие к оценке (см. confidenceOf), иначе наверх лезли бы
     *      карточки с идеальной оценкой от полутора человек.
     *   8  ГОД — соседние годы это ещё и общая мода на приёмы и рисовку; падает
     *      экспонентой, а не обрывом, чтобы 2019 и 2021 остались близкими.
     *   6  ТИП — сериалу подбираем сериал: «Фильм» и «OVA» смотрят в другой момент.
     *   3  СТАТУС — онгоинг к онгоингу (ждать серии вместе — отдельное удовольствие).
     *   3  СТРАНА — японское и китайское аниме зритель обычно не смешивает.
     *
     *  35  БОНУС ИСТОЧНИКА — рекомендации самого каталога построены на том, что люди
     *      смотрели вместе, и такой сигнал сильнее любой нашей эвристики. Но не
     *      абсолютен: их порядок всё равно уточняется признаками выше.
     */
    private const val W_GENRES = 40.0
    private const val W_THEME = 18.0
    private const val W_STUDIO = 12.0
    private const val W_RATING = 10.0
    private const val W_YEAR = 8.0
    private const val W_TYPE = 6.0
    private const val W_STATUS = 3.0
    private const val W_COUNTRY = 3.0

    /** Прибавка карточке, которую «похожей» назвал сам каталог. */
    const val SOURCE_BONUS = 35.0

    /**
     * Ниже этого — уже не «похожее», а просто популярное.
     *
     * Порог низкий намеренно: 12 из 100 набирает карточка с одним общим жанром и
     * близким годом. Выше него блок пустел бы у нишевых тайтлов — а пустой блок и
     * есть та беда, ради которой всё это делается.
     */
    private const val MIN_SIMILARITY = 12.0

    /** Насколько похожи два тайтла, 0..~100. */
    fun similarity(base: Anime, candidate: Anime): Double {
        var score = 0.0

        val baseGenres = genresOf(base)
        val candGenres = genresOf(candidate)
        if (baseGenres.isNotEmpty() && candGenres.isNotEmpty()) {
            val shared = baseGenres.count { it in candGenres }.toDouble()
            // Две доли, а не одна: покрытие («сколько МОИХ жанров нашлось») тянет
            // наверх всё, что попадает в тему, а точность («сколько ЕГО жанров лишние»)
            // не даёт победить карточке с мешком из десяти жанров, куда попадает любой.
            val cover = shared / baseGenres.size
            val precision = shared / candGenres.size
            score += W_GENRES * (0.65 * cover + 0.35 * precision)
        }

        val baseThemes = themesOf(base)
        val candThemes = themesOf(candidate)
        if (baseThemes.isNotEmpty() && candThemes.isNotEmpty()) {
            val shared = baseThemes.count { it in candThemes }.toDouble()
            score += W_THEME * (shared / baseThemes.size)
        }

        if (base.studio.isNotBlank() && base.studio.equals(candidate.studio, ignoreCase = true)) {
            score += W_STUDIO
        }

        val baseQuality = normRating(base)
        val candQuality = normRating(candidate)
        if (candQuality > 0.0) {
            val closeness = if (baseQuality > 0.0) 1.0 - abs(baseQuality - candQuality) else 0.5
            score += W_RATING * (0.6 * candQuality + 0.4 * closeness) * confidenceOf(candidate)
        }

        if (base.year > 0 && candidate.year > 0) {
            score += W_YEAR * exp(-abs(base.year - candidate.year) / YEAR_SCALE)
        }

        if (base.contentType.isNotBlank() && base.contentType.equals(candidate.contentType, ignoreCase = true)) {
            score += W_TYPE
        }
        if (base.airingStatus > 0 && base.airingStatus == candidate.airingStatus) {
            score += W_STATUS
        }
        if (base.country.isNotBlank() && base.country.equals(candidate.country, ignoreCase = true)) {
            score += W_COUNTRY
        }
        return score
    }

    /**
     * Отбор и порядок блока «Похожие».
     *
     * @param base тайтл, страница которого открыта.
     * @param candidates всё, из чего можно выбирать: рекомендации источника + пул каталога.
     * @param sourceIds идентификаторы, которые «похожими» назвал сам источник.
     * @param exclude franchise-список тайтла (сезоны из селектора) — их сюда нельзя.
     */
    fun similar(
        base: Anime,
        candidates: List<Anime>,
        sourceIds: Set<String> = emptySet(),
        exclude: List<Anime> = emptyList(),
        limit: Int = SIMILAR_LIMIT,
    ): List<Anime> {
        val bannedIds = HashSet<String>()
        bannedIds.add(base.id)
        exclude.forEach { bannedIds.add(it.id) }
        // Корни франшизы: сам тайтл и всё, что источник считает его сезонами.
        val bannedRoots = HashSet<String>()
        bannedRoots.add(franchiseKey(base.title))
        exclude.forEach { bannedRoots.add(franchiseKey(it.title)) }

        // Лучший представитель каждой франшизы — иначе три части одного сериала
        // заняли бы три ячейки из двенадцати.
        val best = LinkedHashMap<String, Pair<Anime, Double>>()
        for (candidate in candidates) {
            if (candidate.id in bannedIds || candidate.title.isBlank()) continue
            val root = franchiseKey(candidate.title)
            if (root in bannedRoots) continue
            // Продолжение без разделителя («Бездомный бог ARAGOTO») — по префиксу.
            if (bannedRoots.any { isSameFranchise(it, root) }) continue
            val fromSource = candidate.id in sourceIds
            val score = similarity(base, candidate) + if (fromSource) SOURCE_BONUS else 0.0
            if (!fromSource && score < MIN_SIMILARITY) continue
            val previous = best[root]
            if (previous == null || score > previous.second) best[root] = candidate to score
        }
        return best.values.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    // ---------------------------------------------------------------------------
    // Персональный вкус
    // ---------------------------------------------------------------------------

    /**
     * Вкус зрителя, выведенный из его же следов в [PersistedState].
     *
     * Все карты УЖЕ нормированы на свой максимум (0..1): так вес жанра у человека с
     * тремя просмотрами и у человека с тремя сотнями означает одно и то же, и
     * [score] не приходится подгонять под размер истории.
     */
    data class Taste(
        /** Жанр → насколько он «свой», 0..1. */
        val genres: Map<String, Double>,
        val studios: Map<String, Double>,
        val themes: Map<String, Double>,
        /** Жанры брошенного — вычитаются из оценки кандидата. */
        val dislikedGenres: Map<String, Double>,
        /** Год, вокруг которого лежат любимые тайтлы (0 = не выведен). */
        val preferredYear: Int,
        /** Идентификаторы всего, что уже смотрели/бросили/добавили в избранное. */
        val seenIds: Set<String>,
        /** Корни франшиз того же самого — чтобы не советовать второй сезон брошенного. */
        val seenRoots: Set<String>,
        /** Названия тайтлов, давших вкусу больше всего, — для подписи «как …». */
        val anchors: List<String>,
        /** Сколько тайтлов реально повлияло на вкус. */
        val samples: Int,
        /** Из них — оценённых лично. Они и весят больше, и доверия дают больше. */
        val ratedSamples: Int = 0,
        val dislikedThemes: Map<String, Double> = emptyMap(),
        val anchorCards: List<Anime> = emptyList(),
        val currentYear: Int = java.time.LocalDate.now().year,
        val sources: List<TasteSource> = emptyList(),
        /** Explicit negative ratings are retained as individual semantic neighbours. */
        val negativeSources: List<TasteSource> = emptyList(),
    ) {
        val isEmpty: Boolean get() = samples == 0

        /**
         * Доверие к выведенному вкусу, 0..1. Три тайтла — это ещё совпадение, три
         * десятка — уже вкус; насыщение на [CONFIDENCE_HALF] даёт ровно такой переход.
         *
         * Оценённый тайтл идёт за [RATED_WORTH] обычных. Без этого «рекомендуй по моим
         * оценкам» не работало бы на практике: доверие входит множителем во ВСЕ личные
         * признаки [score], и при пяти оценках оно равнялось бы 0.38 — то есть выдача
         * на две трети оставалась бы «просто лучшим в каталоге». Три оценки дают 0.53,
         * десять — 0.79.
         */
        val confidence: Double
            get() {
                val effective = (samples - ratedSamples) + ratedSamples * RATED_WORTH
                return effective / (effective + CONFIDENCE_HALF)
            }

        fun topGenres(count: Int): List<String> =
            genres.entries.sortedByDescending { it.value }.take(count).map { it.key }

        fun topStudios(count: Int): List<String> =
            studios.entries.sortedByDescending { it.value }.take(count).map { it.key }
    }

    /** Одна карточка выдачи вместе с причиной, по которой она здесь. */
    data class Recommendation(
        val anime: Anime,
        val score: Double,
        /** Человеческая подпись под постером: «Экшен · Фэнтези», «Студия MAPPA». */
        val reason: String,
        /** true — карточка из «нового направления», а не под текущий вкус. */
        val exploratory: Boolean,
        val explanation: Explanation? = null,
    )

    enum class ReasonFactor { GENRES, THEMES, STUDIO, YEAR, QUALITY, WATCHING, FRESHNESS, SIMILARITY,
        RATING_SIMILARITY, SEMANTIC, FAVORITE_AFFINITY, COMPLETED_HISTORY, DISLIKED }
    enum class HistorySignal { HIGH_RATING, RATING, FAVORITE, WATCHED, STARTED, VISITED }
    data class TasteSource(val anime: Anime, val weight: Double, val signal: HistorySignal, val rating: Int? = null) {
        val genres = genresOf(anime).toSet()
        val themes = themesOf(anime)
    }
    data class Explanation(
        val primaryReason: ReasonFactor,
        val text: String,
        val sourceAnime: List<TasteSource>,
        val matchedGenres: List<String>,
        val matchedTags: List<String>,
        val studioMatch: String?,
        val similarityScore: Double,
        val contributions: Map<ReasonFactor, Double>,
    ) {
        val historySignal: List<HistorySignal> get() = sourceAnime.map { it.signal }
    }

    /**
     * Собрать вкус из сохранённого состояния.
     *
     * ЛИЧНАЯ ОЦЕНКА ПЕРЕВЕШИВАЕТ ВСЁ ОСТАЛЬНОЕ. Это не вопрос вкуса в подборе весов, а
     * вопрос того, чем они являются: оценка — единственный след, где человек СКАЗАЛ,
     * что думает. Всё прочее — догадки по поведению, и догадка не должна спорить со
     * сказанным вслух. Поэтому у оценённого тайтла вес берётся ТОЛЬКО из оценки, а
     * досмотренность, история и «брошено» на него уже не влияют: досмотрел и поставил
     * двойку — это «не понравилось», а не «понравилось, ведь досмотрел».
     *
     *  +6.0 пять звёзд — сильнее, чем избранное и досмотренное вместе взятые;
     *  +3.5 четыре;
     *   0.0 три — нейтрально, без ложного сильного вкуса при единственной тройке;
     *  −3.0 две;
     *  −5.0 одна — жанры такого тайтла уходят в АНТИвкус и вычитаются у кандидатов.
     *
     * Веса следов без оценки — прежние, они остаются для всего, что не оценивали:
     *  +2.5 избранное — явный, но безмолвный голос;
     *  +2.0 досмотрено до конца — «понравилось» без слов;
     *  Частичный просмотр, открытая карточка и догнанный онгоинг — нейтральны.
     *  Давность просмотра НЕ доказывает, что тайтл брошен. Явного dropped-статуса
     *  в текущей модели нет, поэтому такой штраф не выдумывается.
     *
     * Свежесть: вес умножается на 0.5^(возраст в днях / [RECENCY_HALF_LIFE_DAYS]) —
     * то, что смотрели этой весной, весит вдвое больше прошлогоднего, но давнее не
     * обнуляется (нижняя граница [RECENCY_FLOOR]). У оценки возраст свой, у истории и
     * избранного времени нет вовсе — там свежесть берётся из ПОРЯДКА списка.
     */
    fun buildTaste(saved: PersistedState, now: Long = System.currentTimeMillis()): Taste {
        val state = normalizeRecommendationState(saved)
        // 1. Собрать все известные карточки в одном месте: id → что о нём знаем.
        //
        // Оценённые идут ПЕРВЫМИ: карточка у оценки самая свежая из всех, что могли
        // сохраниться, — её положили в момент, когда человек смотрел на страницу тайтла.
        val cards = LinkedHashMap<String, PersistedAnime>()
        state.ratings.forEach { cards.putIfAbsent(it.anime.id, it.anime) }
        state.favorites.forEach { cards.putIfAbsent(it.id, it) }
        state.history.forEach { cards.putIfAbsent(it.id, it) }
        state.progress.forEach { cards.putIfAbsent(it.anime.id, it.anime) }
        state.favoriteRemovals.forEach { cards.putIfAbsent(it.anime.id, it.anime) }
        val ratings = state.ratings.associateBy { it.anime.id }
        val removed = state.favoriteRemovals.associateBy { it.anime.id }

        val favoriteIds = state.favorites.map { it.id }.toSet()
        val historyRank = state.history.mapIndexed { index, a -> a.id to index }.toMap()
        // Последнее касание и доля брошенной серии — из записей прогресса.
        val lastTouch = HashMap<String, Long>()
        for (entry in state.progress) {
            if (entry.positionMs <= 8_000L) continue
            val known = lastTouch[entry.anime.id]
            if (known == null || entry.updatedAt > known) lastTouch[entry.anime.id] = entry.updatedAt
        }
        for ((key, at) in state.watchedAt) {
            val id = key.substringBeforeLast('#')
            lastTouch[id] = maxOf(lastTouch[id] ?: 0L, at)
        }
        val genres = HashMap<String, Double>()
        val studios = HashMap<String, Double>()
        val themes = HashMap<String, Double>()
        val disliked = HashMap<String, Double>()
        val dislikedThemes = HashMap<String, Double>()
        val seenIds = HashSet<String>()
        seenIds.addAll(state.watched.map { it.substringBeforeLast('#') })
        // «Не понравилось» — видено и предлагать нельзя; вкус оно уже опустило
        // одной звездой (см. AppSettings.setDisliked).
        seenIds.addAll(state.disliked)
        // «Не показывать 30 дней» — пока срок не вышел, тайтл в подборку не идёт.
        seenIds.addAll(state.snoozedUntil.filterValues { it > now }.keys)
        val seenRoots = HashSet<String>()
        val anchors = ArrayList<Pair<String, Double>>()
        val sources = ArrayList<TasteSource>()
        val negativeSources = ArrayList<TasteSource>()
        var yearWeight = 0.0
        var yearSum = 0.0
        var samples = 0
        var ratedSamples = 0

        for ((id, card) in cards) {
            seenIds.add(id)
            if (card.title.isNotBlank()) seenRoots.add(franchiseKey(card.title))

            val completed = recommendationCompletion(state, card) == RecommendationCompletion.COMPLETED
            val touched = lastTouch[id] ?: 0L
            val ageDays = if (touched > 0L) ((now - touched).coerceAtLeast(0L)) / DAY_MS.toDouble() else -1.0

            val rated = ratings[id]
            var affinity = 0.0
            if (rated != null) {
                // Сказанное вслух не спорит с догадками — оно их заменяет. Ни
                // досмотренность, ни «брошено», ни избранное сюда не добавляются:
                // человек уже ответил на тот же вопрос прямо.
                affinity = ratingAffinity(rated.score)
            } else {
                if (id in favoriteIds) affinity += 2.5
                if (completed) affinity += 2.0
                // No positive signal for a visit, partial season, or caught-up ongoing.
                // There is no explicit dropped status in this app: inactivity is neutral.
                // Removing a favourite is a weak negative, never stronger than an
                // explicit rating or a completed season.
                if (id in removed && id !in favoriteIds) affinity -= 0.7
            }

            val ratedAgeDays = rated?.at?.takeIf { it > 0L }
                ?.let { ((now - it).coerceAtLeast(0L)) / DAY_MS.toDouble() }
            val recency = when {
                // У оценки свой возраст, и он главнее возраста просмотра: вкус
                // сместился — человек переоценит, а не пересмотрит.
                ratedAgeDays != null ->
                    RECENCY_DECAY.pow(ratedAgeDays / RECENCY_HALF_LIFE_DAYS).coerceAtLeast(RECENCY_FLOOR)
                // Оценка есть, а времени у неё нет (запись из старой сборки) — считаем
                // свежей: снижать вес за то, чего мы не знаем, не за что.
                rated != null -> 1.0
                ageDays >= 0.0 -> RECENCY_DECAY.pow(ageDays / RECENCY_HALF_LIFE_DAYS).coerceAtLeast(RECENCY_FLOOR)
                id in removed -> RECENCY_DECAY.pow(((now - removed.getValue(id).at).coerceAtLeast(0) / DAY_MS.toDouble()) /
                    RECENCY_HALF_LIFE_DAYS).coerceAtLeast(RECENCY_FLOOR)
                // Ни одной записи прогресса — свежесть по месту в истории.
                else -> (1.0 - (historyRank[id] ?: HISTORY_TAIL) / HISTORY_SPAN).coerceIn(RECENCY_FLOOR, 1.0)
            }

            val weight = affinity * recency
            if (weight == 0.0) continue
            samples++
            if (rated != null) ratedSamples++

            val cardGenres = splitGenres(card.genres)
            // Делим на корень числа жанров, а не на само число: иначе тайтл с семью
            // жанрами вносил бы в каждый из них крохи и терялся против тайтла с двумя,
            // хотя смотрели их одинаково.
            val share = if (cardGenres.isEmpty()) 0.0 else weight / sqrt(cardGenres.size.toDouble())
            val target = if (weight > 0) genres else disliked
            cardGenres.forEach { g -> target[g] = (target[g] ?: 0.0) + abs(share) }
            if (weight < 0) themesOf(card.toAnimeLite()).forEach { t ->
                dislikedThemes[t] = (dislikedThemes[t] ?: 0.0) + abs(weight)
            }
            if (weight < 0 && rated != null) {
                negativeSources.add(TasteSource(card.toAnimeLite(), weight, HistorySignal.RATING, rated.score))
            }

            if (weight > 0) {
                if (card.studio.isNotBlank()) {
                    val studio = card.studio.trim()
                    studios[studio] = (studios[studio] ?: 0.0) + weight
                }
                themesOf(card.toAnimeLite()).forEach { t -> themes[t] = (themes[t] ?: 0.0) + weight }
                if (card.year > 0) {
                    yearSum += card.year * weight
                    yearWeight += weight
                }
                anchors.add(card.title to weight)
                sources.add(TasteSource(card.toAnimeLite(), weight, when {
                    rated != null && rated.score >= 4 -> HistorySignal.HIGH_RATING
                    rated != null -> HistorySignal.RATING
                    id in favoriteIds -> HistorySignal.FAVORITE
                    else -> HistorySignal.WATCHED // Only fully completed titles reach here.
                }, rated?.score))
            }
        }

        // Жанры и «антижанры» меряются ОДНОЙ линейкой: один брошенный тайтл среди
        // тридцати досмотренных должен весить как один, а не как «максимум неприязни».
        // Своя нормировка у брошенного делала бы штраф полным всегда, даже когда
        // бросили ровно одно.
        val likeScale = genres.values.maxOrNull()?.takeIf { it > 0.0 }
            ?: disliked.values.maxOrNull()?.takeIf { it > 0.0 }
        return Taste(
            genres = if (likeScale == null) emptyMap() else genres.mapValues { (_, v) -> v / likeScale },
            studios = normalized(studios),
            themes = normalized(themes),
            dislikedGenres = if (likeScale == null) {
                emptyMap()
            } else {
                disliked.mapValues { (_, v) -> (v / likeScale).coerceAtMost(1.0) }
            },
            preferredYear = if (yearWeight > 0) (yearSum / yearWeight).toInt() else 0,
            seenIds = seenIds,
            seenRoots = seenRoots,
            anchors = anchors.sortedByDescending { it.second }.map { it.first }.take(ANCHOR_LIMIT),
            samples = samples,
            ratedSamples = ratedSamples,
            dislikedThemes = dislikedThemes.mapValues { (_, v) ->
                (v / (themes.values.maxOrNull()?.takeIf { it > 0 } ?: dislikedThemes.values.maxOrNull() ?: 1.0)).coerceAtMost(1.0)
            },
            anchorCards = sources.sortedByDescending { it.weight }.take(8).map { it.anime },
            sources = sources.sortedByDescending { it.weight }.take(64),
            negativeSources = negativeSources.sortedByDescending { abs(it.weight) }.take(64),
            currentYear = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).year,
        )
    }

    /** Карта, поделённая на свой максимум: веса становятся сравнимыми долями 0..1. */
    /**
     * Вес личной оценки.
     *
     * Шкала намеренно НЕСИММЕТРИЧНА и смещена вниз: тройка это «нормально», а не
     * «середина». Человек не ставит оценку случайному тайтлу — он ставит её тому, что
     * посмотрел, и в такой выборке тройка уже означает «больше не надо». Поэтому
     * положительными остаются только четыре и пять, а двойка с единицей уводят жанры
     * в антивкус.
     *
     * Пятёрка (6.0) тяжелее, чем избранное и досмотренное вместе (2.5 + 2.0): одна
     * осознанная оценка должна перевешивать два косвенных следа, иначе «по большей
     * части по оценкам» не выполняется.
     */
    private fun ratingAffinity(score: Int): Double = when (score) {
        5 -> 6.0
        4 -> 3.5
        3 -> 0.0
        2 -> -3.0
        1 -> -5.0
        else -> 0.0
    }

    private fun normalized(raw: Map<String, Double>): Map<String, Double> {
        val max = raw.values.maxOrNull() ?: return emptyMap()
        if (max <= 0.0) return emptyMap()
        return raw.mapValues { (_, v) -> v / max }
    }

    /*
     * Веса персональной оценки. Отличие от «Похожих» принципиальное: там сравнивают
     * ДВА ТАЙТЛА, здесь — тайтл и НАКОПЛЕННЫЙ ВКУС, у которого есть своя надёжность.
     *
     *  35  ЖАНРЫ вкуса.
     *  20  ТЕМА — то, что отличает «фэнтези про попаданца» от «фэнтези про ведьму».
     *  15  СТУДИЯ — редко совпадает, но когда совпала, это почти всегда попадание.
     *   8  ГОД — зритель, живущий в онгоингах 2024-го, и любитель классики 90-х
     *      хотят разного.
     *  25  КАЧЕСТВО — единственный признак, который работает БЕЗ данных о человеке.
     *      Поэтому его множитель падает по мере роста доверия к вкусу
     *      (1.2 − 0.6·confidence): на пустой истории выдача — это «лучшее в каталоге»,
     *      на полной — «лучшее ИЗ ТВОЕГО». Ровно так «чем больше данных, тем точнее».
     *  20  ШТРАФ за жанры брошенного.
     *
     * Личные признаки умножены на confidence: пока данных мало, вкус не должен
     * притворяться, будто он что-то знает.
     */
    private const val WP_GENRE = 35.0
    private const val WP_THEME = 20.0
    private const val WP_STUDIO = 15.0
    private const val WP_YEAR = 8.0
    private const val WP_QUALITY = 25.0
    private const val WP_DISLIKE = 20.0

    /** Насколько тайтл подходит вкусу. */
    fun score(taste: Taste, anime: Anime): Double = scoreContributions(taste, anime).values.sum()

    /** The exact terms used for ranking AND explanation; no separate reason heuristic. */
    fun scoreContributions(taste: Taste, anime: Anime): Map<ReasonFactor, Double> {
        val confidence = taste.confidence
        val terms = linkedMapOf<ReasonFactor, Double>()

        val genres = genresOf(anime)
        if (genres.isNotEmpty()) {
            // Средний вес жанра, а не сумма: иначе побеждал бы тайтл с самым длинным
            // списком жанров, а не самый близкий.
            val fit = genres.sumOf { taste.genres[it] ?: 0.0 } / genres.size
            val bad = genres.sumOf { taste.dislikedGenres[it] ?: 0.0 } / genres.size
            terms[ReasonFactor.GENRES] = (WP_GENRE * fit - WP_DISLIKE * bad) * confidence
        }

        val themes = themesOf(anime)
        if (themes.isNotEmpty()) {
            val fit = themes.sumOf { taste.themes[it] ?: 0.0 } / themes.size
            terms[ReasonFactor.THEMES] = (WP_THEME * fit -
                10.0 * themes.sumOf { taste.dislikedThemes[it] ?: 0.0 } / themes.size) * confidence
        }

        if (anime.studio.isNotBlank()) {
            terms[ReasonFactor.STUDIO] = WP_STUDIO * (taste.studios[anime.studio.trim()] ?: 0.0) * confidence
        }

        if (taste.preferredYear > 0 && anime.year > 0) {
            terms[ReasonFactor.YEAR] = WP_YEAR * exp(-abs(anime.year - taste.preferredYear) / TASTE_YEAR_SCALE) * confidence
        }

        val quality = normRating(anime) * confidenceOf(anime)
        terms[ReasonFactor.QUALITY] = WP_QUALITY * quality * (1.2 - 0.6 * confidence)
        terms[ReasonFactor.WATCHING] = 4.0 * anime.watchingCount.coerceAtLeast(0) / (anime.watchingCount.coerceAtLeast(0) + 500.0)
        if (anime.year in 1900..taste.currentYear) terms[ReasonFactor.FRESHNESS] = 3.0 * exp(-(taste.currentYear - anime.year) / 3.0)
        // Small nearest-anchor term complements the aggregate genre/theme profile.
        terms[ReasonFactor.SIMILARITY] = 8.0 * (taste.anchorCards.maxOfOrNull { similarity(it, anime) } ?: 0.0) / 100.0 * confidence
        return terms
    }

    /**
     * Персональная выдача.
     *
     * Три обязательных правила, и все три — против того, как «рекомендации» обычно
     * вырождаются:
     *  1. НИ ОДНОЙ франшизы дважды (та же [franchiseKey], что в «Похожих»): иначе
     *     половину экрана занимают сезоны одного сериала.
     *  2. Уже просмотренное, брошенное и избранное не повторяется — ни по id, ни по
     *     корню названия (у одного тайтла на разных источниках разные id).
     *  3. Доля [exploreShare] отдана НОВЫМ НАПРАВЛЕНИЯМ — карточкам, у которых нет ни
     *     одного из любимых жанров. Без этого выдача схлопывается в один жанр и
     *     перестаёт что-либо открывать; такие карточки отбираются по собственному
     *     качеству и помечены [Recommendation.exploratory].
     */
    fun recommend(
        taste: Taste,
        pool: List<Anime>,
        limit: Int = RECOMMEND_LIMIT,
        exploreShare: Double = EXPLORE_SHARE,
    ): List<Recommendation> {
        if (limit <= 0) return emptyList()
        val favouriteGenres = taste.topGenres(TOP_GENRES_FOR_EXPLORE).toSet()
        // Один представитель франшизы — лучший по оценке.
        val best = LinkedHashMap<String, Recommendation>()
        for (candidate in pool) {
            // Невышедшее в подборке — постер без просмотра: отсеиваем по общему признаку,
            // а не только по флагу «анонс» (у части каталогов его нет).
            if (candidate.title.isBlank() || candidate.id in taste.seenIds ||
                !isAnimeRecommendationId(candidate.id) || com.aniblaze.aggregator.model.isUnreleased(candidate)) continue
            val root = franchiseKey(candidate.title)
            if (root in taste.seenRoots) continue
            if (taste.seenRoots.any { sameFranchiseKeys(it, root) }) continue
            val genres = genresOf(candidate)
            val exploratory = favouriteGenres.isNotEmpty() && genres.none { it in favouriteGenres }
            val terms = scoreContributions(taste, candidate)
            val value = terms.values.sum()
            val previous = best[root]
            if (previous == null || value > previous.score) {
                val explanation = explain(taste, candidate, terms)
                best[root] = Recommendation(
                    anime = candidate,
                    score = value,
                    reason = explanation.text,
                    exploratory = exploratory,
                    explanation = explanation,
                )
            }
        }
        val ranked = best.values.sortedByDescending { it.score }
        return diversify(ranked, limit, exploreShare)
    }

    /** Penalize repetition in a six-card window; keep quality/relevance as the base. */
    internal fun diversify(ranked: List<Recommendation>, limit: Int, exploreShare: Double): List<Recommendation> {
        data class Features(val recommendation: Recommendation, val genres: List<String>, val root: String)
        val remaining = ranked.map { Features(it, genresOf(it.anime), franchiseKey(it.anime.title)) }.toMutableList()
        val selected = ArrayList<Recommendation>()
        val roots = HashSet<String>()
        val recentGenres = ArrayList<List<String>>()
        val step = (1.0 / exploreShare.coerceIn(0.05, 0.5)).toInt().coerceAtLeast(2)
        while (remaining.isNotEmpty() && selected.size < limit) {
            val genreCounts = recentGenres.takeLast(5).flatten().groupingBy { it }.eachCount()
            // Bounded shortlist avoids an O(catalog²) rerank for huge histories.
            val shortlist = remaining.take(80).filter { candidate ->
                roots.none { sameFranchiseKeys(it, candidate.root) }
            }
            if (shortlist.isEmpty()) {
                remaining.subList(0, minOf(80, remaining.size)).clear()
                continue
            }
            val alternatives = shortlist.filter { c -> c.genres.none { (genreCounts[it] ?: 0) >= 3 } }
            val eligible = alternatives.ifEmpty { shortlist }
            val wantExplore = (selected.size + 1) % step == 0
            val pool = if (wantExplore) eligible.filter { it.recommendation.exploratory }.ifEmpty { eligible } else eligible
            val pick = pool.maxBy { c ->
                c.recommendation.score - c.genres.sumOf { (genreCounts[it] ?: 0) * 4.0 }
            }
            selected.add(pick.recommendation)
            recentGenres.add(pick.genres)
            roots.add(pick.root)
            remaining.remove(pick)
        }
        return selected
    }

    fun explain(taste: Taste, anime: Anime,
        terms: Map<ReasonFactor, Double> = scoreContributions(taste, anime)): Explanation {
        val primary = terms.maxBy { it.value }.key
        val genres = genresOf(anime).filter { (taste.genres[it] ?: 0.0) > 0 }.sortedByDescending { taste.genres[it] }
        val themes = themesOf(anime).filter { (taste.themes[it] ?: 0.0) > 0 }.sortedByDescending { taste.themes[it] }
        val studio = anime.studio.trim().takeIf { (taste.studios[it] ?: 0.0) > 0 }
        val nearest = taste.anchorCards.map { it to similarity(it, anime) }.maxByOrNull { it.second }
        val contributing = taste.sources.filter { source -> when (primary) {
            ReasonFactor.GENRES -> source.genres.any { it in genres }
            ReasonFactor.THEMES -> source.themes.any { it in themes }
            ReasonFactor.STUDIO -> source.anime.studio.trim() == studio
            ReasonFactor.SIMILARITY -> source.anime.id == nearest?.first?.id
            else -> false
        } }.sortedByDescending { source -> source.weight * when (primary) {
            ReasonFactor.GENRES -> source.genres.count { it in genres } / sqrt(source.genres.size.coerceAtLeast(1).toDouble())
            ReasonFactor.THEMES -> source.themes.count { it in themes }.toDouble()
            else -> 1.0
        } }.distinctBy { it.anime.id }.take(3)
        val text = if ((terms[primary] ?: 0.0) <= 0) "Вариант из каталога" else when (primary) {
            ReasonFactor.GENRES -> "Вам нравятся: ${genres.take(2).joinToString(" · ") { it.replaceFirstChar(Char::uppercase) }}"
            ReasonFactor.THEMES -> "Близкие темы: ${themes.take(2).joinToString(" · ")}"
            ReasonFactor.STUDIO -> if (contributing.isNotEmpty() && contributing.all { it.signal == HistorySignal.HIGH_RATING })
                "Вы высоко оценили тайтлы студии $studio" else "Знакомая вам студия: $studio"
            ReasonFactor.YEAR -> "Близко к любимым годам: ${taste.preferredYear}"
            ReasonFactor.QUALITY -> "Оценка зрителей с учётом её надёжности"
            ReasonFactor.WATCHING -> "Популярно среди зрителей сейчас"
            ReasonFactor.FRESHNESS -> "Свежий тайтл ${anime.year} года"
            ReasonFactor.SIMILARITY -> {
                val source = contributing.firstOrNull()
                val prefix = when (source?.signal) {
                    HistorySignal.WATCHED -> "Потому что вы смотрели"
                    HistorySignal.STARTED -> "Похоже на то, что вы начали"
                    HistorySignal.HIGH_RATING -> "Похоже на высоко оценённый вами тайтл"
                    HistorySignal.FAVORITE -> "Похоже на ваш любимый тайтл"
                    else -> "Похоже на"
                }
                "$prefix «${nearest?.first?.title.orEmpty()}»"
            }
            else -> "Близко к вашим предпочтениям"
        }
        return Explanation(primary, text, contributing, genres, themes, studio, nearest?.second ?: 0.0, terms)
    }

    /** Scoring metadata plus the original image for an explanation thumbnail. */
    private fun PersistedAnime.toAnimeLite(): Anime =
        toAnime()

    // ---------------------------------------------------------------------------
    // Константы и словари
    // ---------------------------------------------------------------------------

    /** Сколько карточек отдаёт «Похожие» — на компактную ленту и «Показать ещё». */
    const val SIMILAR_LIMIT = 40

    /** Сколько показать до кнопки «Показать ещё». */
    const val SIMILAR_PREVIEW = 12

    const val RECOMMEND_LIMIT = 60

    /** Доля «новых направлений» в персональной выдаче. */
    private const val EXPLORE_SHARE = 0.25

    /** По скольким любимым жанрам решается, «новое» это направление или нет. */
    private const val TOP_GENRES_FOR_EXPLORE = 4

    private const val MIN_ROOT_LETTERS = 4
    private const val MIN_PREFIX_LETTERS = 8
    private const val YEAR_SCALE = 6.0
    private const val TASTE_YEAR_SCALE = 9.0
    private const val VOTES_HALF = 500.0
    private const val CONFIDENCE_HALF = 8.0

    /** Во сколько обычных следов оценивается один оценённый тайтл. См. Taste.confidence. */
    private const val RATED_WORTH = 3.0
    private const val RECENCY_HALF_LIFE_DAYS = 120.0
    private const val RECENCY_DECAY = 0.5
    private const val RECENCY_FLOOR = 0.3
    private const val HISTORY_SPAN = 200.0
    private const val HISTORY_TAIL = 100
    private const val DAY_MS = 86_400_000L
    private const val ANCHOR_LIMIT = 5

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex("\\s+")

    /** Двоеточие, тире, точка с пробелом, японские тильды — все способы начать подзаголовок. */
    private val SUBTITLE_SEPARATOR = Regex("\\s*[:：]\\s*|\\s+[—–]\\s+|\\s+-\\s+|\\.\\s+|\\s*[~〜～]\\s*")

    /**
     * Хвостовые пометки продолжений. Все — с обязательным пробелом перед номером:
     * без него «\\s*\\d+$» съедал бы «Gundam 00» по одной цифре, а «\\s*[ivx]+$» —
     * последние буквы слов вроде «climax».
     */
    private val TRAILING_MARKERS = listOf(
        Regex("\\s+\\(?(тв|tv)[-\\s]?\\d*\\)?$"),
        Regex("\\s+\\d+\\s*[-\\s]?\\s*[йея]?\\s*сезон$"),
        Regex("\\s+сезон\\s*\\d*$"),
        Regex("\\s+(перв|втор|трет|четв[её]рт|пят|шест|финальн)\\w*\\s+сезон$"),
        // Порядок важен: «2nd Season» обязан сниматься ЦЕЛИКОМ. Если бы сначала
        // сработало правило «season», от названия остался бы висящий хвост «2nd»,
        // который не снимает уже ничто, — и «Overlord 2nd Season» разошёлся бы с
        // «Overlord» по разным франшизам.
        Regex("\\s+\\d+(st|nd|rd|th)\\s+season$"),
        Regex("\\s+(final\\s+)?season\\s*\\d*$"),
        Regex("\\s+\\d+(st|nd|rd|th)$"),
        Regex("\\s+(часть|part)\\s*\\d+$"),
        Regex("\\s+(the\\s+)?(фильм|movie|ova|ona|oad|special|specials|спешл|спешлы|ова|рекап|recap)$"),
        Regex("\\s+(финал|final)$"),
        Regex("\\s+[ivx]+$"),
        Regex("\\s+\\d{1,3}$"),
        Regex("[\\s'°+★☆.]+$"),
    )

    /**
     * Тема → корни слов, по которым она видна в описании.
     *
     * Корни, а не слова: описания русские, склонения любые, и «магия», «магией»,
     * «магический» обязаны попасть в одну тему. Список короткий намеренно — это
     * дополнение к жанрам, а не вторая система жанров.
     */
    private val THEMES: Map<String, List<String>> = mapOf(
        "школа" to listOf("школ", "академи", "старшеклас", "ученик", "учител", "студент", "одноклассник"),
        "магия" to listOf("магич", "магия", "магии", "магию", "магией", "волшеб", "заклин", "чароде", "колдун", "ведьм"),
        "иной мир" to listOf("иной мир", "ином мире", "другой мир", "другом мире", "исэкай", "исекай", "перерожд", "реинкарн", "призван в"),
        "битвы" to listOf("сражени", "битв", "поединок", "турнир", "боевые искусств", "сильнейш"),
        "война" to listOf("войн", "арми", "солдат", "фронт", "импери", "восстани", "революци"),
        "меч" to listOf("мечом", "мечами", "клинок", "клинком", "катан", "рыцар", "самура", "фехтов"),
        "демоны" to listOf("демон", "дьявол", "нечист", "проклят", "ёкай", "екай", "призрак", "нежит"),
        "меха" to listOf("робот", "меха", "пилот", "андроид", "киборг", "экзоскелет"),
        "космос" to listOf("космос", "космич", "планет", "галакт", "инопланет", "звездол", "орбит"),
        "романтика" to listOf("любов", "влюб", "роман", "свидан", "признани", "чувств к"),
        "спорт" to listOf("спорт", "футбол", "волейбол", "баскетбол", "теннис", "бейсбол", "матч", "чемпионат", "тренировк"),
        "детектив" to listOf("детектив", "расследов", "убийств", "преступлени", "полиц", "улик", "загадочн"),
        "еда" to listOf("кулинар", "готовит", "ресторан", "повар", "кафе", "пекарн"),
        "музыка" to listOf("музык", "песн", "идол", "концерт", "гитар", "оркестр", "вокал"),
        "игры" to listOf("виртуальн", "mmo", "игров", "геймер", "подземель", "данж", "гильди", "квест"),
        "выживание" to listOf("выжив", "апокалипс", "зомби", "катастроф", "эпидеми", "руины", "пустош"),
        "вампиры" to listOf("вампир", "оборотен", "монстр", "чудовищ", "мутант"),
        "история" to listOf("эпох", "средневеков", "династи", "сёгун", "сегун", "феодал", "древн", "исторически"),
        "приключения" to listOf("путешестви", "приключен", "сокровищ", "пират", "экспедици", "странстви"),
        "криминал" to listOf("мафи", "якудза", "банд", "гангстер", "наёмник", "наемник", "киллер", "контрабанд"),
        "повседневность" to listOf("повседнев", "будни", "уютн", "друзь", "подработ"),
        "психология" to listOf("психолог", "сознани", "травм", "одиночеств", "депресс", "разум"),
        "сверхспособности" to listOf("способност", "сверхъестествен", "суперсил", "телепат", "телекинез"),
    )
}
