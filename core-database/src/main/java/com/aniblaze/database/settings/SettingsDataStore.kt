package com.aniblaze.database.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aniblaze_settings")

/** Immutable snapshot of user preferences surfaced to the Settings screen. */
data class UserSettings(
    val enabledSources: Set<String>,
    val preferBackgroundPlayback: Boolean,
    val autoNextSegment: Boolean,
    val pictureInPicture: Boolean,
    val preferredQuality: String,
    val preferredVoiceover: String,
    /** Автовыбор: Дубляж → Studio Band → AniLibria, затем прежняя логика. */
    val voiceoverPriorityEnabled: Boolean,
    /** Ручной выбор озвучки хранится отдельно для каждого тайтла. */
    val manualVoiceovers: Map<String, String>,
    val autoSkipOpening: Boolean,
    val autoSkipEnding: Boolean,
    val onboarded: Boolean,
    /** Ordered list of enabled Home section keys (subset of [SettingsDataStore.HOME_SECTIONS]). */
    val homeOrder: List<String>,
    /** Most recent search queries, newest first. */
    val searchHistory: List<String>,
    /** Poster-grid columns: 0 = Auto (adapt to screen width), otherwise a fixed count. */
    val gridColumns: Int,
    /** Multiplier for Compose typography. 1.0 keeps the system font size unchanged. */
    val fontScale: Float,
    /** Lampa plugin URL used by the mobile cinema balancer runtime. */
    val lampaPluginUrl: String,
    /** Preferred Lampa balancer; reliable fallbacks are tried automatically. */
    val lampaBalancer: String,
    /** Try a locally streamed torrent only after every ordinary cinema source failed. */
    val cinemaTorrentFallback: Boolean,
    /** Stremio-compatible addon manifest/base URL used only to discover torrent info hashes. */
    val cinemaTorrentAddonUrl: String,
    /** Also query built-in reserve indexes (Comet, MediaFusion) when the primary finds nothing. */
    val cinemaTorrentExtraAddons: Boolean,
    /** Do not start P2P traffic on a metered/mobile network. */
    val cinemaTorrentWifiOnly: Boolean,
    /** Kinogo mirror base URL (the .ec domain hops when blocked). */
    val kinogoBaseUrl: String,
    /** HDrezka mirror base URL. */
    val rezkaBaseUrl: String,
    /** Route parser/addon HTTP traffic through a user proxy (RF blocks). */
    val proxyEnabled: Boolean,
    val proxyHost: String,
    /** 0 = unset. */
    val proxyPort: Int,
    /** "http" or "socks". */
    val proxyType: String,
    val proxyUser: String,
    val proxyPass: String,
    /** После ПОСЛЕДНЕЙ серии — прыжок на случайное аниме (тренды/сейчас смотрят/
     *  рандом, без уже просмотренного). Обычный автоплей серий не трогает. */
    val autoSwitchRandom: Boolean,
    /** Показывать комментарии зрителей поверх видео. */
    val danmakuEnabled: Boolean,
    /** Как часто они всплывают: rare | normal | often. */
    val danmakuRate: String,
    /** Show the parser-backed chat panel in the mobile player. */
    val chatEnabled: Boolean,
    /** Hide likely plot spoilers in chat until the user taps the message. */
    val chatHideSpoilers: Boolean,
)

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val ENABLED_SOURCES = stringSetPreferencesKey("enabled_sources")
        val BACKGROUND = booleanPreferencesKey("background_playback")
        val AUTO_NEXT = booleanPreferencesKey("auto_next")
        val PIP = booleanPreferencesKey("pip")
        val QUALITY = stringPreferencesKey("preferred_quality")
        val VOICEOVER = stringPreferencesKey("preferred_voiceover")
        val VOICEOVER_PRIORITY = booleanPreferencesKey("voiceover_priority")
        val MANUAL_VOICEOVERS = stringPreferencesKey("manual_voiceovers")
        val AUTO_SKIP_OPENING = booleanPreferencesKey("auto_skip_opening")
        val AUTO_SKIP_ENDING = booleanPreferencesKey("auto_skip_ending")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val HOME_ORDER = stringPreferencesKey("home_order")
        val SEARCH_HISTORY = stringPreferencesKey("search_history")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val LAMPA_PLUGIN_URL = stringPreferencesKey("lampa_plugin_url")
        val LAMPA_BALANCER = stringPreferencesKey("lampa_balancer")
        val CINEMA_TORRENT_FALLBACK = booleanPreferencesKey("cinema_torrent_fallback")
        val CINEMA_TORRENT_ADDON_URL = stringPreferencesKey("cinema_torrent_addon_url")
        val CINEMA_TORRENT_EXTRA_ADDONS = booleanPreferencesKey("cinema_torrent_extra_addons")
        val CINEMA_TORRENT_WIFI_ONLY = booleanPreferencesKey("cinema_torrent_wifi_only")
        val KINOGO_BASE_URL = stringPreferencesKey("kinogo_base_url")
        val REZKA_BASE_URL = stringPreferencesKey("rezka_base_url")
        val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val PROXY_TYPE = stringPreferencesKey("proxy_type")
        val PROXY_USER = stringPreferencesKey("proxy_user")
        val PROXY_PASS = stringPreferencesKey("proxy_pass")
        val AUTO_SWITCH_RANDOM = booleanPreferencesKey("auto_switch_random")
        val DANMAKU = booleanPreferencesKey("danmaku_enabled")
        val DANMAKU_RATE = stringPreferencesKey("danmaku_rate")
        val CHAT_ENABLED = booleanPreferencesKey("chat_enabled")
        val CHAT_HIDE_SPOILERS = booleanPreferencesKey("chat_hide_spoilers")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        val enabled = prefs[Keys.ENABLED_SOURCES] ?: DEFAULT_SOURCES
        Timber.d("[Settings] enabledSources=%s", enabled)
        UserSettings(
            enabledSources = enabled,
            preferBackgroundPlayback = prefs[Keys.BACKGROUND] ?: true,
            autoNextSegment = prefs[Keys.AUTO_NEXT] ?: true,
            pictureInPicture = prefs[Keys.PIP] ?: true,
            preferredQuality = prefs[Keys.QUALITY] ?: "",
            preferredVoiceover = prefs[Keys.VOICEOVER] ?: "",
            voiceoverPriorityEnabled = prefs[Keys.VOICEOVER_PRIORITY] ?: false,
            manualVoiceovers = decodeManualVoiceovers(prefs[Keys.MANUAL_VOICEOVERS].orEmpty()),
            autoSkipOpening = prefs[Keys.AUTO_SKIP_OPENING] ?: false,
            autoSkipEnding = prefs[Keys.AUTO_SKIP_ENDING] ?: false,
            onboarded = prefs[Keys.ONBOARDED] ?: false,
            homeOrder = (prefs[Keys.HOME_ORDER]
                ?.split(",")
                ?.filter { it.isNotBlank() && HOME_SECTION_KEYS.contains(it) }
                ?.takeIf { it.isNotEmpty() }
                // Surface any newly-added sections (e.g. "watching") for existing users.
                ?.let { saved -> saved + DEFAULT_HOME_ORDER.filter { it !in saved } })
                ?: DEFAULT_HOME_ORDER,
            searchHistory = prefs[Keys.SEARCH_HISTORY]
                ?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
            gridColumns = prefs[Keys.GRID_COLUMNS] ?: 0,
            fontScale = (prefs[Keys.FONT_SCALE] ?: 1f).coerceIn(0.9f, 1.4f),
            lampaPluginUrl = prefs[Keys.LAMPA_PLUGIN_URL] ?: DEFAULT_LAMPA_PLUGIN_URL,
            // "rezka" was renamed upstream to "rezka2"; migrate the stored value on
            // read so the settings chips and the resolver agree.
            lampaBalancer = (prefs[Keys.LAMPA_BALANCER] ?: DEFAULT_LAMPA_BALANCER)
                .let { if (it == "rezka") "rezka2" else it },
            cinemaTorrentFallback = prefs[Keys.CINEMA_TORRENT_FALLBACK] ?: false,
            // The default addon host moved from torrentio.strem.fun to
            // torrentio.strem.fun; migrate the stored value on read so
            // existing installs pick up the working host automatically.
            cinemaTorrentAddonUrl = (prefs[Keys.CINEMA_TORRENT_ADDON_URL]
                ?.takeIf { it.isNotBlank() } ?: DEFAULT_TORRENT_ADDON_URL)
                .let { if (it == LEGACY_TORRENT_ADDON_URL) DEFAULT_TORRENT_ADDON_URL else it },
            cinemaTorrentExtraAddons = prefs[Keys.CINEMA_TORRENT_EXTRA_ADDONS] ?: true,
            cinemaTorrentWifiOnly = prefs[Keys.CINEMA_TORRENT_WIFI_ONLY] ?: true,
            kinogoBaseUrl = prefs[Keys.KINOGO_BASE_URL]
                ?.takeIf { it.isNotBlank() } ?: DEFAULT_KINOGO_BASE_URL,
            rezkaBaseUrl = prefs[Keys.REZKA_BASE_URL]
                ?.takeIf { it.isNotBlank() } ?: DEFAULT_REZKA_BASE_URL,
            proxyEnabled = prefs[Keys.PROXY_ENABLED] ?: false,
            proxyHost = prefs[Keys.PROXY_HOST].orEmpty(),
            proxyPort = (prefs[Keys.PROXY_PORT] ?: 0).coerceIn(0, 65535),
            proxyType = (prefs[Keys.PROXY_TYPE] ?: PROXY_TYPE_HTTP)
                .takeIf { it == PROXY_TYPE_HTTP || it == PROXY_TYPE_SOCKS } ?: PROXY_TYPE_HTTP,
            proxyUser = prefs[Keys.PROXY_USER].orEmpty(),
            proxyPass = prefs[Keys.PROXY_PASS].orEmpty(),
            autoSwitchRandom = prefs[Keys.AUTO_SWITCH_RANDOM] ?: false,
            danmakuEnabled = prefs[Keys.DANMAKU] ?: true,
            danmakuRate = prefs[Keys.DANMAKU_RATE] ?: "normal",
            chatEnabled = prefs[Keys.CHAT_ENABLED] ?: true,
            chatHideSpoilers = prefs[Keys.CHAT_HIDE_SPOILERS] ?: true,
        )
    }

    suspend fun setFontScale(scale: Float) {
        context.dataStore.edit { it[Keys.FONT_SCALE] = scale.coerceIn(0.9f, 1.4f) }
    }

    suspend fun setChatEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CHAT_ENABLED] = enabled }
    }

    suspend fun setChatHideSpoilers(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CHAT_HIDE_SPOILERS] = enabled }
    }

    suspend fun setDanmakuEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DANMAKU] = enabled }
    }

    suspend fun setDanmakuRate(rate: String) {
        context.dataStore.edit { it[Keys.DANMAKU_RATE] = rate }
    }

    suspend fun setAutoSwitchRandom(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_SWITCH_RANDOM] = enabled }
    }

    suspend fun setLampaPluginUrl(url: String) {
        context.dataStore.edit { it[Keys.LAMPA_PLUGIN_URL] = url.trim() }
    }

    suspend fun setLampaBalancer(balancer: String) {
        context.dataStore.edit { it[Keys.LAMPA_BALANCER] = balancer }
    }

    suspend fun setCinemaTorrentFallback(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CINEMA_TORRENT_FALLBACK] = enabled }
    }

    suspend fun setCinemaTorrentAddonUrl(url: String) {
        context.dataStore.edit { it[Keys.CINEMA_TORRENT_ADDON_URL] = url.trim() }
    }

    suspend fun setCinemaTorrentExtraAddons(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CINEMA_TORRENT_EXTRA_ADDONS] = enabled }
    }

    suspend fun setKinogoBaseUrl(url: String) {
        context.dataStore.edit { it[Keys.KINOGO_BASE_URL] = url.trim().trimEnd('/') }
    }

    suspend fun setRezkaBaseUrl(url: String) {
        context.dataStore.edit { it[Keys.REZKA_BASE_URL] = url.trim().trimEnd('/') }
    }

    suspend fun setProxyEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PROXY_ENABLED] = enabled }
    }

    suspend fun setProxyHost(host: String) {
        context.dataStore.edit { it[Keys.PROXY_HOST] = host.trim() }
    }

    suspend fun setProxyPort(port: Int) {
        context.dataStore.edit { it[Keys.PROXY_PORT] = port.coerceIn(0, 65535) }
    }

    suspend fun setProxyType(type: String) {
        context.dataStore.edit {
            it[Keys.PROXY_TYPE] = if (type == PROXY_TYPE_SOCKS) PROXY_TYPE_SOCKS else PROXY_TYPE_HTTP
        }
    }

    suspend fun setProxyUser(user: String) {
        context.dataStore.edit { it[Keys.PROXY_USER] = user.trim() }
    }

    suspend fun setProxyPass(pass: String) {
        context.dataStore.edit { it[Keys.PROXY_PASS] = pass }
    }

    suspend fun setCinemaTorrentWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CINEMA_TORRENT_WIFI_ONLY] = enabled }
    }

    suspend fun setGridColumns(columns: Int) {
        context.dataStore.edit { it[Keys.GRID_COLUMNS] = columns }
    }

    suspend fun setHomeOrder(order: List<String>) {
        context.dataStore.edit { it[Keys.HOME_ORDER] = order.joinToString(",") }
    }

    suspend fun addSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SEARCH_HISTORY]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            val next = (listOf(q) + current.filterNot { it.equals(q, ignoreCase = true) }).take(10)
            prefs[Keys.SEARCH_HISTORY] = next.joinToString("\n")
        }
    }

    suspend fun clearSearchHistory() {
        context.dataStore.edit { it.remove(Keys.SEARCH_HISTORY) }
    }

    suspend fun setEnabledSources(sources: Set<String>) {
        context.dataStore.edit { it[Keys.ENABLED_SOURCES] = sources }
    }

    suspend fun setBackgroundPlayback(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BACKGROUND] = enabled }
    }

    suspend fun setAutoNext(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_NEXT] = enabled }
    }

    suspend fun setPictureInPicture(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PIP] = enabled }
    }

    suspend fun setPreferredQuality(quality: String) {
        context.dataStore.edit { it[Keys.QUALITY] = quality }
    }

    suspend fun setPreferredVoiceover(name: String) {
        context.dataStore.edit { it[Keys.VOICEOVER] = name }
    }

    suspend fun setVoiceoverPriority(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VOICEOVER_PRIORITY] = enabled }
    }

    suspend fun setManualVoiceover(contentId: String, name: String) {
        val id = contentId.substringBefore(":t").trim()
        val value = name.trim()
        if (id.isBlank() || value.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = decodeManualVoiceovers(prefs[Keys.MANUAL_VOICEOVERS].orEmpty())
            val ordered = linkedMapOf(id to value)
            current.forEach { (key, voice) -> if (key != id && ordered.size < 200) ordered[key] = voice }
            prefs[Keys.MANUAL_VOICEOVERS] = ordered.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        }
    }

    suspend fun setAutoSkipOpening(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_SKIP_OPENING] = enabled }
    }

    suspend fun setAutoSkipEnding(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_SKIP_ENDING] = enabled }
    }

    suspend fun setOnboarded(value: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDED] = value }
    }

    companion object {
        internal fun decodeManualVoiceovers(raw: String): Map<String, String> = raw.lineSequence()
            .mapNotNull { line ->
                val split = line.indexOf('\t')
                if (split <= 0 || split >= line.lastIndex) null
                else line.substring(0, split).trim().takeIf { it.isNotBlank() }?.let { id ->
                    line.substring(split + 1).trim().takeIf { it.isNotBlank() }?.let { voice -> id to voice }
                }
            }
            .take(200)
            .toMap()

        const val DEFAULT_LAMPA_PLUGIN_URL = "https://nb557.github.io/plugins/online_mod.js"
        // Live sources of the current online_mod plugin (rezka/cdnmovies/vibix are
        // disabled upstream; rezka was renamed to rezka2). Legacy stored values are
        // mapped by LampaExtractor.effectiveBalancer.
        const val DEFAULT_LAMPA_BALANCER = "rezka2"
        const val DEFAULT_TORRENT_ADDON_URL = "https://torrentio.strem.fun/manifest.json"
        /** Previous default host, migrated to [DEFAULT_TORRENT_ADDON_URL] on read. */
        const val LEGACY_TORRENT_ADDON_URL = "https://torrentio.strem.fun/manifest.json"
        /**
         * Reserve torrent indexes, queried concurrently with the primary when
         * [UserSettings.cinemaTorrentExtraAddons] is on. All three are free,
         * need no configuration in the URL and speak the same Stremio
         * `/stream/{type}/{id}.json` contract (verified manifests, 2026).
         */
        const val TORRENT_ADDON_COMET = "https://comet.elfhosted.com/manifest.json"
        const val TORRENT_ADDON_MEDIAFUSION = "https://mediafusion.elfhosted.com/manifest.json"
        val BUILTIN_TORRENT_ADDONS = listOf(
            DEFAULT_TORRENT_ADDON_URL,
            TORRENT_ADDON_COMET,
            TORRENT_ADDON_MEDIAFUSION,
        )
        const val DEFAULT_KINOGO_BASE_URL = "https://kinogo.ec"
        const val DEFAULT_REZKA_BASE_URL = "https://rezka.ag"
        const val PROXY_TYPE_HTTP = "http"
        const val PROXY_TYPE_SOCKS = "socks"
        val LAMPA_BALANCERS = listOf("rezka2", "cdnvideohub", "kodik", "filmix")
        /**
         * AnimeOn стоит рядом с Anixart не ради каталога, а ради ОЗВУЧЕК.
         *
         * У одной и той же серии он отдаёт заметно больше дорожек — на «Блич
         * [ТВ-2, часть 4]» девять против набора Anixart. Когда у Anixart серия не
         * резолвится ни одной озвучкой, шанс, что она доступна здесь, вполне
         * реальный. Потоки при этом те же самые: обе цепочки ведут в Kodik.
         */
        val DEFAULT_SOURCES = setOf("Anixart", "AnimeOn")

        /** All customisable Home sections (key → display title), in default order. */
        val HOME_SECTIONS = listOf(
            "trending" to "В тренде",
            "watching" to "Сейчас смотрят",
            "announce" to "Ожидаемые анонсы",
            "ongoing" to "Сейчас выходит",
            "seasonal" to "Сезонное аниме",
            "recent" to "Недавно обновлённые",
            "popular" to "Популярное за всё время",
            "genres" to "Жанры",
        )
        val DEFAULT_HOME_ORDER = HOME_SECTIONS.map { it.first }
        val HOME_SECTION_KEYS = HOME_SECTIONS.map { it.first }.toSet()
    }
}
