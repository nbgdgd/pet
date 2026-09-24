package com.aniblaze.network

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Правила обращения к «вежливым» API: Shikimori и Jikan режут частые запросы кодом
 * 429, а их ответы (карточка, роли, расписание) не меняются часами. Поэтому GET к
 * ним идёт через дисковый кэш с TTL и не чаще одного раза в [minIntervalMs] на хост.
 *
 * Кэш — `%APPDATA%/AniBlaze/cache/http/<sha1(url)>`: первая строка — время записи
 * (epoch ms), дальше тело. 404 тоже запоминается (пустым телом) на короткий срок:
 * повторно спрашивать про несуществующую запись незачем.
 *
 * Хосты вне списка проходят как раньше — без задержек и без кэша.
 */
object HostPolicy {
    data class Rule(val minIntervalMs: Long, val cacheTtlMs: Long, val notFoundTtlMs: Long)

    private val rules = mapOf(
        "shikimori.one" to Rule(minIntervalMs = 350, cacheTtlMs = 24 * 3600_000L, notFoundTtlMs = 6 * 3600_000L),
        "api.jikan.moe" to Rule(minIntervalMs = 400, cacheTtlMs = 24 * 3600_000L, notFoundTtlMs = 6 * 3600_000L),
    )

    /**
     * Кэш и дроссель ВКЛЮЧАЕТ приложение при старте (Main). В тестах политика молчит:
     * иначе тест с подменённым HTTP читал бы из настоящего кэша чужие ответы и сам
     * писал бы в него свои заглушки — так однажды в кэше оказался пустой список
     * студии Madhouse.
     */
    @Volatile var enabled: Boolean = false

    private val lastCallAt = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Any>()

    private val cacheDir: File by lazy {
        File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "AniBlaze/cache/http").apply { mkdirs() }
    }

    fun ruleFor(url: String): Rule? = if (!enabled) null else runCatching { java.net.URI(url).host }.getOrNull()?.let { host ->
        rules.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.value
    }

    /** Кэшированный ответ: `Cached(null)` — запомненный 404; null — в кэше нет или устарело. */
    class Cached(val body: String?)

    fun cached(url: String, rule: Rule): Cached? {
        val file = fileFor(url)
        if (!file.exists()) return null
        return runCatching {
            val text = file.readText()
            val nl = text.indexOf('\n')
            if (nl < 0) return null
            val at = text.substring(0, nl).toLongOrNull() ?: return null
            val body = text.substring(nl + 1)
            val age = System.currentTimeMillis() - at
            val notFound = body.isEmpty()
            if (age > (if (notFound) rule.notFoundTtlMs else rule.cacheTtlMs)) return null
            Cached(if (notFound) null else body)
        }.getOrNull()
    }

    fun store(url: String, body: String?) {
        runCatching {
            fileFor(url).writeText("${System.currentTimeMillis()}\n${body.orEmpty()}")
        }
    }

    /** Подождать, чтобы между двумя запросами к хосту прошло не меньше minIntervalMs. */
    fun throttle(url: String, rule: Rule) {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return
        val lock = locks.getOrPut(host) { Any() }
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val wait = (lastCallAt[host] ?: 0L) + rule.minIntervalMs - now
            if (wait > 0) Thread.sleep(wait)
            lastCallAt[host] = System.currentTimeMillis()
        }
    }

    private fun fileFor(url: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return File(cacheDir, digest.joinToString("") { "%02x".format(it) })
    }

    /** Сколько файлов в кэше и их объём — для настроек. */
    fun stats(): Pair<Int, Long> {
        val files = cacheDir.listFiles()?.filter { it.isFile }.orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    fun clear() {
        cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
    }
}
