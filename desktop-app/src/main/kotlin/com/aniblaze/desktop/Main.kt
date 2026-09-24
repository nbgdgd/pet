package com.aniblaze.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.source.AniLibriaSource
import com.aniblaze.aggregator.source.AnixartSource
import com.aniblaze.aggregator.source.CinemaSource
import com.aniblaze.aggregator.source.KinozapasSource
import com.aniblaze.aggregator.source.LampaCatalogSource
import com.aniblaze.aggregator.source.LordfilmSource
import com.aniblaze.aggregator.source.ZetflixSource
import com.aniblaze.aggregator.source.KodikExtractor
import com.aniblaze.aggregator.source.KodikSource
import com.aniblaze.aggregator.source.ShikimoriSource
import com.aniblaze.database.settings.SettingsDataStore
import com.aniblaze.network.AniblazeDns
import com.aniblaze.network.AnixartProxyInterceptor
import com.aniblaze.network.DotDns
import com.aniblaze.network.HttpClient
import com.aniblaze.network.UserAgentInterceptor
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.awt.Rectangle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import androidx.compose.ui.graphics.toArgb

/** Minimal per-host in-memory cookie store — enough to hold a site's session /
 *  Cloudflare clearance cookie so follow-up requests aren't 403'd. */
private class SimpleCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, List<Cookie>>()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val names = cookies.mapTo(HashSet()) { it.name }
        store.compute(url.host) { _, previous ->
            previous.orEmpty().filterNot { it.name in names } + cookies
        }
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host]?.filter { it.expiresAt > System.currentTimeMillis() } ?: emptyList()
}

/** Whether enough of a restored window still intersects any current display to
 * recover it with the mouse. Saved coordinates can point to a monitor that was
 * disconnected between launches; in that case Compose must use PlatformDefault. */
internal fun savedWindowIntersectsDisplay(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    displays: List<Rectangle>,
): Boolean {
    if (x == Int.MIN_VALUE || y == Int.MIN_VALUE || width <= 0 || height <= 0) return false
    val left = x.toLong()
    val top = y.toLong()
    val right = left + width.toLong()
    val bottom = top + height.toLong()
    val requiredWidth = minOf(width, 48).toLong()
    val requiredHeight = minOf(height, 32).toLong()
    return displays.any { display ->
        val displayLeft = display.x.toLong()
        val displayTop = display.y.toLong()
        val displayRight = displayLeft + display.width.toLong()
        val displayBottom = displayTop + display.height.toLong()
        val visibleWidth = (minOf(right, displayRight) - maxOf(left, displayLeft)).coerceAtLeast(0L)
        val visibleHeight = (minOf(bottom, displayBottom) - maxOf(top, displayTop)).coerceAtLeast(0L)
        visibleWidth >= requiredWidth && visibleHeight >= requiredHeight
    }
}

private fun currentDisplayBounds(): List<Rectangle> = runCatching {
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .map { it.defaultConfiguration.bounds }
}.getOrDefault(emptyList())

/**
 * Desktop uses the plain system DNS resolver: the DoT/DoH resilience chain
 * (built for mobile carriers that poison plain DNS for the Anixart API) fails
 * its TLS hostname check under the JVM's stricter cert validation and just
 * adds a multi-second stall to every lookup before falling back anyway. If a
 * user's Windows network turns out to need it, it's easy to re-add.
 */
private fun buildOkHttpClient(dns: Dns = Dns.SYSTEM): OkHttpClient {
    val cacheDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "AniBlaze/http_cache")
    // Persist cookies across requests: some sources hand out a session / Cloudflare
    // clearance cookie on the first hit and 403 later requests that don't echo it back.
    return OkHttpClient.Builder()
        .cache(Cache(cacheDir, 30L * 1024 * 1024))
        .cookieJar(SimpleCookieJar())
        .dns(dns)
        .addInterceptor(AnixartProxyInterceptor())
        .addInterceptor(UserAgentInterceptor())
        // Tighter timeouts than mobile: a dead/slow source shouldn't stall a
        // catalog merge for 20s before the fast ones can render.
        // СОЕДИНЕНИЕ отдельно от ОТВЕТА, и сроки у них разные не случайно.
        //
        // Замерено 23.08 на api.anixart.tv, шесть запросов подряд: соединение и TLS
        // укладываются в 50 и 110 мс ВСЕГДА, а первый байт ответа приходит через
        // 3.1, 3.1, 3.7, 5.2, 6.7, 8.2 секунды — и в худшую минуту доходил до 16 и
        // даже перешагивал 25. Источник не лежит, он просто медленно думает: канал до
        // него мгновенный, тормозит сам сервер.
        //
        // Отсюда и разнос: соединение так и остаётся коротким (не отвечает вовсе —
        // выясняется за восемь секунд), а на ОТВЕТ терпения втрое больше. Здесь стояло
        // десять секунд, и на таком сервере это значило отказ на ровном месте: канал
        // открыт, ответ идёт, а мы кладём трубку.
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // Потолок на ВЕСЬ вызов, а не на отдельную попытку.
        //
        // Сроки выше считаются заново на каждое перенаправление и на каждое зеркало из
        // AnixartProxyInterceptor, поэтому один логический запрос складывался из
        // нескольких таймаутов подряд — замерено 21.08: 14.5 с на прокси плюс столько
        // же на origin. Этот потолок кладёт конец сложению.
        //
        // Здесь стояло двенадцать секунд, и это было ошибкой того же рода, что и
        // десять выше: медленный, но живой источник объявлялся мёртвым. От долгого
        // ожидания на по-настоящему мёртвом хосте спасает не короткий срок, а отсечка
        // в AnixartProxyInterceptor — три неудачи подряд, и запросы к нему обрываются
        // мгновенно.
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

/**
 * Retries a failed poster request a few times. Coil never re-fetches a poster that
 * errored (timeout / dropped connection) — the card just stays blank until it
 * happens to recompose. On a slow connection a screenful of same-host posters
 * routinely times out one or two of them; retrying here fills those gaps instead.
 */
private class RetryInterceptor(private val maxAttempts: Int = 3) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var lastError: IOException? = null
        repeat(maxAttempts - 1) {
            try {
                val resp = chain.proceed(chain.request())
                if (resp.isSuccessful || resp.code in 400..499) return resp // 4xx won't fix on retry
                resp.close()
            } catch (e: IOException) {
                lastError = e
            }
        }
        return try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            throw lastError ?: e
        }
    }
}

/**
 * weserv serves most posters as small fast WebPs, but under burst it sometimes fails
 * to fetch the origin (HTTP 404 / upstream timeout) → grey cards. When that happens,
 * fall straight back to the original poster URL (s.anixmirai.com serves it fine
 * directly, just full-size). Net: small+fast normally, always-visible on weserv miss.
 */
private class WeservFallbackInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.host != "images.weserv.nl") return chain.proceed(req)
        val resp = runCatching { chain.proceed(req) }.getOrNull()
        if (resp?.isSuccessful == true) return resp
        resp?.close()
        val raw = req.url.queryParameter("url") ?: return chain.proceed(req)
        val direct = when {
            raw.startsWith("ssl:") -> "https://" + raw.removePrefix("ssl:")
            raw.startsWith("http") -> raw
            else -> "https://$raw"
        }
        return chain.proceed(req.newBuilder().url(direct).build())
    }
}

/**
 * Debug: logs every poster network attempt (final code / exception + timing) to a
 * file so we can see WHY posters go grey (DNS poison, timeout, 403, reset…). Sits
 * innermost so it records each raw attempt, retries included.
 */
private class PosterLogInterceptor(private val logFile: File) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val start = System.nanoTime()
        return try {
            val resp = chain.proceed(req)
            append("OK ${resp.code} ${(System.nanoTime() - start) / 1_000_000}ms ${req.url}")
            resp
        } catch (e: Exception) {
            append("ERR ${e.javaClass.simpleName}: ${e.message} ${(System.nanoTime() - start) / 1_000_000}ms ${req.url}")
            throw e
        }
    }
    private fun append(line: String) { runCatching { synchronized(logFile) { logFile.appendText(line + "\n") } } }
}

/**
 * DNS used ONLY for poster loading. RKN poisons plain DNS for the secondary anime
 * CDNs (AniLibria / Shikimori) on many RF networks, so their posters come back blank.
 * We resolve just those hosts through encrypted DoT (Mullvad), falling back to the
 * system resolver if DoT is unreachable — every other host stays on the fast system
 * path. Scoped to the image loader so a slow DoT lookup never stalls the UI (posters
 * load async) and the catalog sources' own networking is untouched.
 */
private fun posterDns(): Dns {
    val encrypted = AniblazeDns(listOf(DotDns("dns.mullvad.net", "194.242.2.2")))
    val blocked = setOf(
        "images.weserv.nl", "s.anixmirai.com",
        "aniliberty.top", "shikimori.me", "shikimori.one", "shikimori.org",
        "kodik.info", "kodik.biz", "kodikapi.com", "kodikplayer.com",
    )
    return object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> =
            if (blocked.any { hostname == it || hostname.endsWith(".$it") }) encrypted.lookup(hostname)
            else Dns.SYSTEM.lookup(hostname)
    }
}

/**
 * Уводит System.out/System.err в файл.
 *
 * У собранного jpackage-ом GUI-приложения стандартные потоки уходят в пайп,
 * который никто не читает. Как только буфер пайпа заполняется, ОЧЕРЕДНАЯ запись
 * блокируется навсегда — и если печатал UI-поток, приложение встаёт намертво.
 * Ровно это и произошло: дамп потоков показал EDT, висящий в
 * FileOutputStream.writeBytes внутри System.err.printf. Свой вызов я убрал, но
 * печатать может любая библиотека (vlcj, JNA, okhttp), поэтому ловушка
 * обезвреживается разом для всех: файл никогда не «переполнится» и не заблокирует
 * писателя. Файл усечён по размеру, чтобы не расти бесконечно.
 */
private fun redirectStandardStreams() {
    runCatching {
        val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "AniBlaze/logs")
        dir.mkdirs()
        val target = File(dir, "stdio.log")
        if (target.length() > 2L * 1024 * 1024) target.delete()
        val stream = java.io.PrintStream(
            java.io.BufferedOutputStream(java.io.FileOutputStream(target, true), 8192),
            /* autoFlush = */ true,
            "UTF-8",
        )
        System.setOut(stream)
        System.setErr(stream)
    }
}

fun main(args: Array<String>) {
    System.setProperty("sun.awt.noerasebackground", "true")
    // Second launch (double-clicked the shortcut while we sit in the tray) —
    // the running instance was signalled to show its window; nothing to do here.
    if (!SingleInstance.acquireOrSignal()) return
    // `--tray` (autostart with Windows): start hidden, only the tray icon shows.
    val startHidden = args.contains("--tray")
    redirectStandardStreams()
    com.aniblaze.desktop.player.PlayerDiagnostics.startSession()
    // Дисковый кэш и дроссель Shikimori/Jikan — только в приложении, не в тестах.
    com.aniblaze.network.HostPolicy.enabled = true
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        com.aniblaze.desktop.player.PlayerDiagnostics.failure("app.uncaught.${thread.name}", error)
    }

    // Warm up the WHOLE libVLC runtime in the background: discovery + the shared
    // MediaPlayerFactory (module-bank init — the expensive part). By the time the
    // user opens the player, the factory already exists and composition touches
    // nothing native. Initializing it lazily on the EDT was the "виснет намертво"
    // freeze (17s with a cold plugin cache).
    Thread { com.aniblaze.desktop.player.VlcRuntime.acquire() }.apply { isDaemon = true }.start()

    val okHttpClient = buildOkHttpClient()
    // Separate client for posters. Three poster-specific tweaks over the API client:
    //  • its OWN cache dir — two OkHttp clients must never share one Cache directory;
    //  • DoT DNS for the RKN-poisoned secondary CDNs (posterDns);
    //  • much higher per-host concurrency + a longer read timeout. Every Anixart
    //    poster lives on ONE host (s.anixmirai.com); OkHttp's default cap of 5
    //    requests/host meant a screenful of ~25 posters queued 5-at-a-time and the
    //    tail timed out on a slow connection → the "some posters blank" gaps.
    val appDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "AniBlaze")
    val imgCacheDir = File(appDir, "img_cache")
    val posterLog = File(appDir, "poster_debug.log").apply { runCatching { parentFile.mkdirs(); writeText("=== poster debug ${System.currentTimeMillis()} ===\n") } }
    val imageClient = okHttpClient.newBuilder()
        .cache(Cache(imgCacheDir, 200L * 1024 * 1024))
        .dns(posterDns())
        // Posters now come as small WebPs from weserv/Cloudflare, so bandwidth is no
        // longer the bottleneck. Keep timeouts modest and concurrency reasonable.
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .addInterceptor(RetryInterceptor(2))
        .addInterceptor(PosterLogInterceptor(posterLog))
        .addInterceptor(WeservFallbackInterceptor())
        // Low per-host cap: fewer simultaneous weserv fetches means weserv rate-limits
        // us far less (the HTTP-404-after-5s bursts), so posters stay fast + complete.
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 48; maxRequestsPerHost = 4 })
        .build()

    fun plog(s: String) { runCatching { synchronized(posterLog) { posterLog.appendText(s + "\n") } } }

    com.aniblaze.desktop.ui.CinemaImages.configure(imageClient, appDir)

    // Register a global Coil image loader that fetches posters through our OkHttp
    // client (browser UA). Without an explicit network fetcher Coil renders blank
    // posters on desktop — which is why the catalog art was missing.
    SingletonImageLoader.setSafe {
        ImageLoader.Builder(it)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageClient })) }
            // У Coil по умолчанию размер зависит от heap и мог удерживать слишком
            // много декодированных постеров рядом с VLC/Skia. Жёсткий потолок делает
            // расход предсказуемым; weak-ссылки выключены, чтобы старые bitmap не
            // висели до следующего большого GC.
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(96L * 1024L * 1024L)
                    .weakReferencesEnabled(false)
                    .build()
            }
            .crossfade(true)
            .eventListener(object : coil3.EventListener() {
                override fun onSuccess(request: coil3.request.ImageRequest, result: coil3.request.SuccessResult) {
                    plog("COIL-OK ${result.dataSource} ${request.data}")
                }
                override fun onError(request: coil3.request.ImageRequest, result: coil3.request.ErrorResult) {
                    plog("COIL-ERR ${result.throwable::class.simpleName}: ${result.throwable.message} ${request.data}")
                }
            })
            .build()
    }

    // Прогрев HTTP-соединений к горячим хостам. Измерено: первый запрос к Anixart —
    // 2.6 с, последующие — ~260 мс; вся разница уходит на DNS + TLS-рукопожатие.
    // К моменту, когда главная запросит свои секции, соединения уже тёплые.
    Thread {
        listOf(
            "https://api.anixart.tv/release/random",
            "https://aniliberty.top/api/v1/app/status",
            "https://images.weserv.nl/",
        ).forEach { url ->
            runCatching {
                okHttpClient.newCall(Request.Builder().url(url).head().build()).execute().close()
            }
        }
    }.apply { isDaemon = true; name = "aniblaze-connection-warmup" }.start()

    val http = HttpClient(okHttpClient)
    val appSettings = AppSettings()
    // Тема — до первого кадра, иначе окно мигнёт классикой перед выбранной темой.
    appSettings.state.value.let { com.aniblaze.desktop.ui.AppThemeState.apply(it.theme, it.accent) }
    // Штатные выходы (крестик, «Выход» в трее) сбрасывают настройки на диск сами,
    // но процесс может закончиться и мимо них — обновлением сборки, перезагрузкой,
    // «завершить задачу». Тогда терялось всё, что накопилось с последней записи:
    // на какой серии и на каком тайминге остановились.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { appSettings.flush() }
            runCatching { com.aniblaze.desktop.player.PlayerDiagnostics.flush() }
        },
    )
    val settingsDataStore = SettingsDataStore()

    val aggregators: List<ContentAggregator> = listOf(
        AnixartSource(http, KodikExtractor(okHttpClient), settingsDataStore),
        AniLibriaSource(http),
        // Открытый API animeon.cc: тот же Kodik на потоках, но у эпизода бывает
        // заметно больше озвучек. Нужен как запасной набор файлов, когда у Anixart
        // серия не резолвится ни одной озвучкой.
        com.aniblaze.aggregator.source.AnimeOnSource(http, KodikExtractor(okHttpClient)),
        // Progressive MP4 (range-seekable) — the only source here that isn't HLS.
        com.aniblaze.aggregator.source.AnimeVostSource(http),
        ShikimoriSource(http, okHttpClient),
        KodikSource(http),
        // Запасные (выключены по умолчанию, включаются в настройках). Yummy — второй
        // маршрут к Kodik + Sibnet MP4; Animedia и SameBand — СВОИ CDN, не Kodik.
        com.aniblaze.aggregator.source.YummyAnimeSource(http, KodikExtractor(okHttpClient)),
        com.aniblaze.aggregator.source.AnimediaSource(http),
        com.aniblaze.aggregator.source.SameBandSource(http),
    )
    val lampa = com.aniblaze.aggregator.lampa.LampaExtractor(okHttpClient)
    // Switchable "Кино" sources (first = default). lordfilm/zetflix/kinozapas scrape
    // a site catalog; "Lampa (TMDB)" browses TMDB and plays via plugin balancers.
    val cinemaSources: List<CinemaSource> = listOf(
        LordfilmSource(http),
        ZetflixSource(http),
        KinozapasSource(http),
        LampaCatalogSource(
            http, lampa,
            pluginUrl = { appSettings.state.value.lampaPluginUrl },
            balancer = { appSettings.state.value.lampaBalancer },
        ),
    )
    val aniskip = com.aniblaze.aggregator.source.AniskipTimings(http)
    val balancer = com.aniblaze.aggregator.source.BalancerSource(http, KodikExtractor(okHttpClient))
    // Air dates for the episode list; shares aniskip's Shikimori→MAL id cache.
    val airDates = com.aniblaze.aggregator.source.EpisodeAirDates(http, aniskip)
    val repository = DesktopRepository(aggregators, cinemaSources, appSettings, lampa, aniskip, balancer, airDates, http)
    // Background watcher: raises a Windows notification when a followed title gains a
    // playable episode (i.e. when the dub is actually out).
    val notifier = NewEpisodeNotifier(repository, appSettings).also {
        it.start()
        // Create the tray icon NOW: close-to-tray relies on it existing before the
        // first (lazy, delayed) notification sweep would have made it.
        it.ensureTray()
    }
    // Зависания интерфейса ловим со снимком стеков: причина обрыва потока в журнал
    // не попадала как раз потому, что в момент зависания его никто не пишет.
    FreezeWatchdog.start()

    application {
        // Окно открывается там же и таким же, каким его закрыли. Раньше здесь стояли
        // жёсткие 1280x800 без позиции: каждый запуск давал маленькое окно в углу,
        // а развёрнутое состояние не переживало перезапуск вовсе.
        val saved = appSettings.state.value
        val restoredWidth = saved.windowWidth.takeIf { it > 320 } ?: 1280
        val restoredHeight = saved.windowHeight.takeIf { it > 240 } ?: 800
        val restoredPositionIsVisible = androidx.compose.runtime.remember {
            savedWindowIntersectsDisplay(
                saved.windowX,
                saved.windowY,
                restoredWidth,
                restoredHeight,
                currentDisplayBounds(),
            )
        }
        val windowState = rememberWindowState(
            size = DpSize(restoredWidth.dp, restoredHeight.dp),
            position = if (restoredPositionIsVisible) {
                androidx.compose.ui.window.WindowPosition(saved.windowX.dp, saved.windowY.dp)
            } else {
                androidx.compose.ui.window.WindowPosition.PlatformDefault
            },
            placement = if (saved.windowMaximized) {
                androidx.compose.ui.window.WindowPlacement.Maximized
            } else {
                androidx.compose.ui.window.WindowPlacement.Floating
            },
        )
        androidx.compose.runtime.LaunchedEffect(Unit) {
            // Опрос, а не подписка: snapshotFlow на трёх полях сразу всё равно
            // сводился бы к этому, а раз в секунду достаточно — окно не двигают
            // непрерывно, и лишних записей в файл настроек не будет.
            while (true) {
                kotlinx.coroutines.delay(1_000)
                val maximized = windowState.placement == androidx.compose.ui.window.WindowPlacement.Maximized
                val position = windowState.position
                appSettings.saveWindowBounds(
                    width = windowState.size.width.value.toInt(),
                    height = windowState.size.height.value.toInt(),
                    x = if (position.isSpecified) position.x.value.toInt() else Int.MIN_VALUE,
                    y = if (position.isSpecified) position.y.value.toInt() else Int.MIN_VALUE,
                    maximized = maximized,
                )
            }
        }
        var windowVisible by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(!startHidden) }
        // Incremented even when the window is already visible, so requests from a
        // detached title can raise an obscured main window as well as restore it.
        var mainWindowRaiseRequest by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(0L)
        }
        // Тайтл, который просят открыть извне (клик по всплывашке «новая серия»).
        var openTitleRequest by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<com.aniblaze.aggregator.model.Anime?>(null)
        }
        // Tray menu / second-launch signal → show the window; tray "Выход" → real exit.
        androidx.compose.runtime.DisposableEffect(Unit) {
            // Свёрнутое (не спрятанное) окно: windowVisible уже true, и одного его
            // недостаточно — эффект подъёма не перезапустится. Поэтому ещё снимаем
            // свёрнутость и дёргаем счётчик подъёма: окно выходит наверх в любом
            // состоянии — из трея, из «свернуть», из-под других окон.
            val show: () -> Unit = {
                com.aniblaze.desktop.player.PlayerDiagnostics.log("app.showFromTray", "minimized=${windowState.isMinimized}; visible=$windowVisible")
                windowVisible = true
                windowState.isMinimized = false
                mainWindowRaiseRequest++
            }
            notifier.onOpenWindow = show
            SingleInstance.onShow = show
            notifier.onOpenTitle = { anime ->
                openTitleRequest = anime
                show()
            }
            notifier.onQuit = {
                com.aniblaze.desktop.player.PlayerDiagnostics.log("app.exit", "reason=trayQuit")
                val saved = appSettings.flush()
                com.aniblaze.desktop.player.PlayerDiagnostics.flush()
                if (saved) exitApplication()
            }
            onDispose {
                notifier.onOpenWindow = null
                notifier.onOpenTitle = null
                notifier.onQuit = null
                SingleInstance.onShow = null
            }
        }
        // «Картинка в картинке»: отдельное окно поверх остальных, пока главное свёрнуто.
        //
        // Условие смотрит и на сворачивание, и на уход в трей: и то и другое значит
        // «окна на экране нет», а смотреть человек не перестал. Сток кадров при этом
        // тот же самый — окошко не создаёт ни второго проигрывателя, ни копии кадров.
        val pipSettings by appSettings.state.collectAsState()
        val pipSink = com.aniblaze.desktop.player.PipBridge.sink
        val hidden = windowState.isMinimized || !windowVisible
        // Вернулись в приложение — «закрыл окошко» перестаёт действовать: это было
        // решение про ТОТ раз, а не про функцию целиком.
        androidx.compose.runtime.LaunchedEffect(hidden) {
            if (!hidden) com.aniblaze.desktop.player.PipBridge.dismissed = false
            // Плеер по этому признаку решает, готовить ли кадры вообще: когда окна
            // нет на экране и окошко не всплыло, превращать их в картинки незачем.
            com.aniblaze.desktop.player.PipBridge.mainHidden = hidden
        }
        if (pipSettings.pictureInPicture && pipSink != null && hidden &&
            !com.aniblaze.desktop.player.PipBridge.dismissed
        ) {
            com.aniblaze.desktop.player.PictureInPictureWindow(
                sink = pipSink,
                enhance = com.aniblaze.desktop.player.VideoEnhance.Level.of(pipSettings.videoEnhance),
                initialBounds = pipSettings.pipBounds,
                onBoundsChange = appSettings::setPipBounds,
                onRestore = {
                    windowVisible = true
                    windowState.isMinimized = false
                },
                // Крестик закрывает только окошко: воспроизведение продолжается, и
                // всплывать снова оно будет уже со следующей серии.
                onClose = { com.aniblaze.desktop.player.PipBridge.dismissed = true },
            )
        }
        // Тайтлы, открытые «в новом окне». Окна создаются ЗДЕСЬ, на самом верху, а
        // команда приходит из карточки на десять уровней ниже — через DetachedTitles
        // (см. комментарий там, почему держатель, а не сквозной параметр).
        //
        // Ключ по id обязателен: без него закрытие среднего окна из трёх заставило бы
        // Compose переиспользовать состояние соседа, и в окне оказался бы чужой тайтл.
        DetachedTitles.open.forEach { detached ->
            androidx.compose.runtime.key(detached.id) {
                DetachedTitleWindow(
                    anime = detached,
                    repository = repository,
                    settings = appSettings,
                    onClose = { DetachedTitles.close(detached) },
                )
            }
        }
        Window(
            visible = windowVisible,
            onCloseRequest = {
                // Крестик прячет в трей (уведомления продолжают жить в фоне);
                // реальный выход — из меню трея. Fallback на полный выход, когда
                // трея нет (урезанные оболочки) или так решено в настройках.
                if (appSettings.state.value.closeToTray && notifier.isAvailable()) {
                    com.aniblaze.desktop.player.PlayerDiagnostics.log("app.hideToTray")
                    appSettings.flush()
                    windowVisible = false
                } else {
                    com.aniblaze.desktop.player.PlayerDiagnostics.log("app.exit", "reason=windowClose")
                    val saved = appSettings.flush()
                    com.aniblaze.desktop.player.PlayerDiagnostics.flush()
                    if (saved) exitApplication()
                }
            },
            title = "AniBlaze",
            state = windowState,
            icon = AniBlazeWindowIcon,
        ) {
            // Re-shown from the tray → bring the window forward, not just unhide.
            androidx.compose.runtime.LaunchedEffect(windowVisible, mainWindowRaiseRequest) {
                if (windowVisible) {
                    windowState.isMinimized = false
                    runCatching { window.toFront(); window.requestFocus() }
                }
            }
            // Prepare the native AWT/Swing host before any player/menu transition.
            // Otherwise Windows can expose Swing's default white buffer for a frame
            // while the heavyweight VLC surface or a choice panel is being laid out.
            // This must run once per Window, not once per player recomposition.
            // Reassigning AWT colours while frames are being drawn causes needless
            // repaints and, in the callback renderer, overlaps native video work.
            // Фон AWT — цвет темы: у светлой темы чёрная подложка мигала бы на resize.
            val themeBackground = com.aniblaze.desktop.ui.AppThemeState.theme.background
            androidx.compose.runtime.LaunchedEffect(window, themeBackground) {
                val awt = java.awt.Color(themeBackground.toArgb(), true)
                window.background = awt
                window.contentPane.background = awt
                (window.contentPane as? javax.swing.JComponent)?.isOpaque = true
            }
            // Repaint the native title bar / border dark to match the app (kills the
            // white Windows 11 caption + top border line). Re-applied on maximize
            // toggle since some transitions reset the caption color.
            androidx.compose.runtime.LaunchedEffect(windowState.placement) {
                repeat(2) { DwmDark.apply(window); kotlinx.coroutines.delay(120) }
            }
            App(
                repository, appSettings, windowState, notifier,
                windowShown = windowVisible,
                openTitleRequest = openTitleRequest,
                onOpenTitleConsumed = { openTitleRequest = null },
                onRequestMainWindow = {
                    windowVisible = true
                    windowState.isMinimized = false
                    mainWindowRaiseRequest++
                },
            )
        }
    }
}
