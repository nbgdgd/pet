package com.aniblaze.network

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException

/**
 * Keeps the Anixart catalog working from Russia.
 *
 * RKN blocked `api.anixart.tv` (and every other anixart.* domain — they share one
 * IP) on 2025-06-05, so a direct connection from most RF networks hangs/resets.
 * Posters (s.anixmirai.com) and video (kodikplayer.com) are on separate, un-blocked
 * hosts, so only the API host needs help.
 *
 * Different RF providers block different things — some drop the Anixart IP, some
 * also drop Cloudflare/workers.dev. So requests to the API host are tried against
 * an ordered list of reverse-proxies ([PROXY_HOSTS]) and finally the origin itself.
 * The first host that answers wins; whatever is reachable on that particular
 * network gets used. Users outside RF normally succeed on the first proxy (or could
 * use the origin directly — either works).
 *
 * Put the most reliable proxy first (a VPS on a clean, non-Cloudflare IP). Leave the
 * list empty to disable proxying entirely (talk to the origin directly, as before).
 */
/**
 * Отсечка «источник лежит»: держит паузу, пока хост не подаёт признаков жизни.
 *
 * Отдельным классом, а не парой полей в перехватчике, ровно затем, чтобы проверяться
 * тестом без сети: часы подставляются снаружи.
 */
class OutageGate(
    private val streak: Int,
    private val pauseMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var failures = 0
    private var openUntil = 0L

    /** Пора ли отказывать сразу, не трогая сеть. */
    @Synchronized fun isOpen(): Boolean = clock() < openUntil

    @Synchronized fun recordSuccess() {
        failures = 0
        openUntil = 0L
    }

    @Synchronized fun recordFailure() {
        failures++
        if (failures >= streak) openUntil = clock() + pauseMs
    }
}

class AnixartProxyInterceptor : Interceptor {

    private val outage = OutageGate(OUTAGE_STREAK, OUTAGE_PAUSE_MS)

    /** Путь, ответивший последним: он и пробуется первым (см. разбор в [intercept]). */
    @Volatile private var preferred: String = PROXY_HOSTS.firstOrNull { it.isNotBlank() } ?: ORIGIN

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != API_HOST) {
            return chain.proceed(request)
        }

        // ЛЕЖАЩИЙ ИСТОЧНИК НЕ ДОЛЖЕН ВЕШАТЬ ПРИЛОЖЕНИЕ.
        //
        // Перебор зеркал стоит одного таймаута КАЖДОЕ, и когда лежит сам Anixart, ни
        // одно из них ответить не может по определению: прокси — это он же. Замерено
        // 21.08, когда api.anixart.tv отдавал 502: один запрос сгорал 14.5 секунды на
        // прокси и столько же на origin, и так на каждую строчку каталога, на каждый
        // список серий, на каждую проверку новых серий. Наружу это ровно «ничего не
        // работает»: приложение не падает, оно ЖДЁТ, и конца этому нет.
        //
        // Поэтому после [OUTAGE_STREAK] подряд неудач host объявляется лежащим и на
        // [OUTAGE_PAUSE_MS] запросы к нему обрываются мгновенно. Экран получает честный
        // отказ за миллисекунды и успевает нарисовать хоть что-то, а не крутит спиннер.
        // Первый же успех снимает паузу.
        if (outage.isOpen()) {
            throw IOException("$API_HOST не отвечает — попытки приостановлены на ${OUTAGE_PAUSE_MS / 1000} с")
        }
        var lastError: IOException? = null
        // ПЕРВЫМ ПРОБУЕМ ТОТ ПУТЬ, ЧТО ОТВЕТИЛ В ПРОШЛЫЙ РАЗ.
        //
        // Порядок был жёстким — сперва зеркала, потом origin, — и это стоило полного
        // таймаута НА КАЖДЫЙ запрос там, где зеркало недоступно, а origin отвечает.
        // Замерено 23.08: «proxy … failed (timeout) — trying next» на каждой странице
        // обсуждения, и только за этим шёл успешный прямой ответ.
        //
        // Обратная сторона тоже реальна: у кого origin заблокирован провайдером,
        // единственный рабочий путь — зеркало, и жёсткий порядок «сначала origin» ломал
        // бы жизнь ровно им. Поэтому порядок не зашит, а ВЫУЧИВАЕТСЯ: сработавший путь
        // запоминается и в следующий раз идёт первым. На блокирующей сети наверх
        // всплывает зеркало, на свободной — origin, и обоим не приходится ждать чужой
        // неудачи.
        val routes = (PROXY_HOSTS.filter { it.isNotBlank() } + ORIGIN)
            .sortedByDescending { it == preferred }
        for (host in routes) {
            val rerouted = if (host == ORIGIN) request else request.withHost(host)
            try {
                val resp = chain.proceed(rerouted)
                if (resp.code < 500) {
                    preferred = host
                    outage.recordSuccess()
                    return resp
                }
                resp.close()
                Timber.w("route %s returned HTTP %d — trying next", host, resp.code)
            } catch (e: IOException) {
                lastError = e
                Timber.w("route %s failed (%s) — trying next", host, e.message)
            }
        }
        // Ни один путь не сработал — вот это и есть неудача, которую считает отсечка.
        outage.recordFailure()
        throw lastError ?: IOException("$API_HOST не отвечает ни напрямую, ни через зеркала")
    }

    /** Same path/query/body/method, swapped onto [host]. */
    private fun Request.withHost(host: String): Request {
        val rerouted = url.newBuilder().host(host).build()
        return newBuilder().url(rerouted).build()
    }

    companion object {
        private const val API_HOST = "api.anixart.tv"

        /** Прямой путь к источнику — такой же вариант перебора, как и зеркала. */
        private const val ORIGIN = API_HOST

        /** Столько неудач подряд — и хост считается лежащим. */
        private const val OUTAGE_STREAK = 3

        /**
         * Сколько держать паузу. Полминуты: перебои у Anixart живут минутами, а не
         * секундами, и пробовать чаще — значит снова упереться в тот же таймаут. Первый
         * успешный ответ снимает паузу немедленно, так что ждать полминуты после
         * восстановления никому не придётся.
         */
        private const val OUTAGE_PAUSE_MS = 30_000L

        /**
         * Reverse-proxy hosts for api.anixart.tv, tried in order. Put a VPS on a
         * clean non-Cloudflare IP first; the Cloudflare Worker is a secondary that
         * works on networks which don't block workers.dev.
         */
        val PROXY_HOSTS = listOf(
            "aniblaze-api.no9875806.workers.dev", // Cloudflare Worker
        )
    }
}
