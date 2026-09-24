package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.model.TitleComment

/**
 * Сбор обсуждения по страницам: что считать одной и той же записью и когда
 * останавливаться.
 *
 * Отдельным файлом и без единого обращения к сети — чтобы проверялось тестом целиком, а
 * не «на живом Наруто, если источник сегодня отвечает».
 *
 * Разбор поломки, ради которой это написано, — у [AnixartSource.comments]: страницы
 * запрашивались с неоднозначной сортировкой, и четверть выдачи оказывалась повторами,
 * а столько же записей не приходило вовсе. Здесь вторая половина лечения: даже с
 * однозначной сортировкой обход обязан узнавать повтор в лицо — новая запись, поданная
 * посреди обхода, сдвигает окно на единицу, и без отбора по идентичности этот сдвиг
 * доехал бы до экрана.
 */

/**
 * Устойчивый признак «это та же самая запись».
 *
 * По убыванию надёжности:
 *  1. идентификатор источника — он и есть ответ, когда есть;
 *  2. автор + время + нормализованный текст — когда идентификатора нет;
 *  3. нормализованный текст — последнее, что остаётся.
 *
 * Автор и время входят в запасной ключ НАМЕРЕННО: два человека вполне могут написать
 * «топ» под одной серией, и это две разные записи, а не одна. Схлопывать их по тексту —
 * значит терять настоящие мнения ради борьбы с повторами.
 */
fun commentIdentity(comment: TitleComment): String = when {
    comment.id > 0L -> "${comment.source.ifBlank { "unknown" }}:id:${comment.id}"
    else -> {
        val author = comment.authorId.ifBlank { comment.author.trim().lowercase() }
        "${comment.source.ifBlank { "unknown" }}:f:$author|${comment.timestamp}|${normalizedMessage(comment.message)}"
    }
}

/**
 * Текст, приведённый к сравнимому виду: без краевых пробелов, с одинаковыми переводами
 * строк и без разнобоя в пробелах внутри.
 *
 * Регистр НЕ трогаем: «ору» и «ОРУ» — разные реплики по интонации, и на экране это
 * видно. Задача нормализации тут узкая — снять расхождения, которые вносит передача, а
 * не решить за человека, что он имел в виду.
 */
fun normalizedMessage(message: String): String =
    message.replace("\r\n", "\n").replace('\r', '\n').replace(WHITESPACE, " ").trim()

private val WHITESPACE = Regex("[\\p{Z}\\s]+")

/** Почему обход остановился — для журнала и для тестов. */
enum class CommentStop {
    /** Источник сказал, сколько у него страниц, и они кончились. */
    LAST_PAGE,

    /** Страница пришла пустой. */
    EMPTY_PAGE,

    /** Несколько страниц подряд не принесли ни одной новой записи. */
    NO_NEW,

    /** Источник перестал отвечать. */
    FAILED,

    /** Упёрлись в предохранитель. Признак сломанного источника, а не нормальный конец. */
    SAFETY_LIMIT,

    /** Пул достиг безопасного для памяти размера; сеть могла иметь ещё записи. */
    CAPACITY_LIMIT,
}

/**
 * Накопитель уникальных записей.
 *
 * Отбор идёт по множеству известных признаков, то есть за постоянное время на запись:
 * у Наруто их шесть с половиной тысяч, и попарное сравнение стоило бы двадцать
 * миллионов операций на каждую страницу.
 *
 * Порядок поступления сохраняется ([LinkedHashSet]), потому что он осмысленный —
 * страницы приходят от свежих к старым.
 */
class CommentAccumulator {
    private val items = LinkedHashMap<String, TitleComment>()
    @Volatile var revision: Long = 0
        private set

    val size: Int @Synchronized get() = items.size

    /** Добавляет только новое. Возвращает, сколько записей из [page] оказались новыми. */
    @Synchronized
    fun add(page: List<TitleComment>): Int {
        var fresh = 0
        for (comment in page) {
            val id = commentIdentity(comment)
            val previous = items.put(id, comment)
            if (previous == null) fresh++
            if (previous != comment) revision++
        }
        return fresh
    }

    /** Уже собранное. Копия — снаружи список живёт своей жизнью. */
    @Synchronized
    fun snapshot(): List<TitleComment> = ArrayList(items.values)

    /**
     * Only call after an authoritative full traversal, never after network failure.
     * [keepSources] — источники, чьи записи в обходе не участвовали и остаются как есть.
     */
    @Synchronized fun retainIdentities(seen: Set<String>, keepSources: Set<String> = emptySet(), keepReplies: Boolean = false) {
        if (items.entries.removeIf { (key, comment) ->
                key !in seen && comment.source !in keepSources && !(keepReplies && comment.parentId != 0L)
            }
        ) revision++
    }
}

/**
 * Пора ли прекращать обход.
 *
 * Возвращает причину остановки либо null — «есть смысл просить дальше».
 *
 * Ни одно из условий не завязано на маленькое выдуманное число страниц: обход идёт,
 * пока источник приносит новое. Есть только сетевой safety limit и отдельный предел
 * полного пула по памяти.
 */
fun commentStop(
    page: Int,
    /** Сколько записей пришло СЫРЫМИ, до отсева ответов и удалённого. */
    rawReceived: Int,
    /** Сколько сырых записей набрано за весь обход — сравнимо с [totalCount]. */
    rawSeen: Int,
    freshUnique: Int,
    barrenStreak: Int,
    totalCount: Int,
    uniqueSeen: Int = 0,
    safetyLimit: Int = COMMENT_SAFETY_PAGES,
    capacityLimit: Int = COMMENT_SAFETY_ITEMS,
): CommentStop? = when {
    uniqueSeen >= capacityLimit -> CommentStop.CAPACITY_LIMIT
    // ПУСТОТУ СЧИТАЕМ ПО СЫРЫМ ЗАПИСЯМ, а не по тому, что уцелело после отсева. Разбор
    // выбрасывает ответы на комментарии и удалённое, и страница, целиком состоящая из
    // ответов, разбирается в ноль — при том что у источника за ней есть ещё сотни.
    // Считай мы по разобранному, обход обрывался бы на первой такой странице.
    rawReceived == 0 -> CommentStop.EMPTY_PAGE
    // Источник сообщает общее ЧИСЛО ЗАПИСЕЙ — оно точное. Число страниц у него врёт:
    // замерено 23.08 на «Ван-Пис» — 509 записей при 25 на страницу это 21 страница, а
    // заявлено 20. Остановка по заявленным страницам теряла хвост в девять записей.
    totalCount > 0 && rawSeen >= totalCount -> CommentStop.LAST_PAGE
    freshUnique == 0 && barrenStreak >= BARREN_LIMIT -> CommentStop.NO_NEW
    page >= safetyLimit - 1 -> CommentStop.SAFETY_LIMIT
    else -> null
}

/**
 * Сколько страниц подряд без единой новой записи считать концом.
 *
 * Не одна: у неоднозначной сортировки страница целиком из повторов встречается и
 * посреди обсуждения. Три подряд — это уже не совпадение.
 */
private const val BARREN_LIMIT = 3

/**
 * Сетевой предохранитель. Отдельный лимит элементов ниже сработает раньше при обычных
 * страницах; этот остаётся для источника, который бесконечно присылает повторы.
 */
const val COMMENT_SAFETY_PAGES = 1_000

/** Full source pool cap: protects the 768 MB desktop heap without truncating known large titles. */
const val COMMENT_SAFETY_ITEMS = 20_000
