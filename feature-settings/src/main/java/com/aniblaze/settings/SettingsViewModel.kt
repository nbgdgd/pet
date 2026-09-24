package com.aniblaze.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniblaze.database.settings.SettingsDataStore
import com.aniblaze.database.settings.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsDataStore,
) : ViewModel() {

    val settings: StateFlow<UserSettings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val allSources: List<String> = SettingsDataStore.DEFAULT_SOURCES.toList().sorted()

    fun toggleSource(source: String, enabled: Boolean) {
        val current = settings.value?.enabledSources ?: return
        val next = if (enabled) current + source else current - source
        viewModelScope.launch { store.setEnabledSources(next) }
    }

    fun setBackgroundPlayback(enabled: Boolean) =
        viewModelScope.launch { store.setBackgroundPlayback(enabled) }.let {}

    fun setAutoNext(enabled: Boolean) =
        viewModelScope.launch { store.setAutoNext(enabled) }.let {}

    fun setAutoSkipOpening(enabled: Boolean) =
        viewModelScope.launch { store.setAutoSkipOpening(enabled) }.let {}

    fun setAutoSkipEnding(enabled: Boolean) =
        viewModelScope.launch { store.setAutoSkipEnding(enabled) }.let {}

    fun setVoiceoverPriority(enabled: Boolean) =
        viewModelScope.launch { store.setVoiceoverPriority(enabled) }.let {}

    fun setAutoSwitchRandom(enabled: Boolean) =
        viewModelScope.launch { store.setAutoSwitchRandom(enabled) }.let {}

    fun setPictureInPicture(enabled: Boolean) =
        viewModelScope.launch { store.setPictureInPicture(enabled) }.let {}

    fun setGridColumns(columns: Int) =
        viewModelScope.launch { store.setGridColumns(columns) }.let {}

    fun setFontScale(scale: Float) =
        viewModelScope.launch { store.setFontScale(scale) }.let {}

    fun setDanmaku(enabled: Boolean) =
        viewModelScope.launch { store.setDanmakuEnabled(enabled) }.let {}

    fun setDanmakuRate(rate: String) =
        viewModelScope.launch { store.setDanmakuRate(rate) }.let {}

    fun setChatEnabled(enabled: Boolean) =
        viewModelScope.launch { store.setChatEnabled(enabled) }.let {}

    fun setChatHideSpoilers(enabled: Boolean) =
        viewModelScope.launch { store.setChatHideSpoilers(enabled) }.let {}

    /** Частота показа комментариев: ключ → подпись. Ключи те же, что у DanmakuRate. */
    val danmakuRates: List<Pair<String, String>> = listOf(
        "rare" to "Редко",
        "normal" to "Обычно",
        "often" to "Часто",
    )

    val lampaBalancers: List<String> = SettingsDataStore.LAMPA_BALANCERS

    fun setLampaBalancer(balancer: String) =
        viewModelScope.launch { store.setLampaBalancer(balancer) }.let {}

    fun setLampaPluginUrl(url: String) =
        viewModelScope.launch { store.setLampaPluginUrl(url) }.let {}

    fun setCinemaTorrentFallback(enabled: Boolean) =
        viewModelScope.launch { store.setCinemaTorrentFallback(enabled) }.let {}

    fun setCinemaTorrentAddonUrl(url: String) =
        viewModelScope.launch { store.setCinemaTorrentAddonUrl(url) }.let {}

    fun setCinemaTorrentExtraAddons(enabled: Boolean) =
        viewModelScope.launch { store.setCinemaTorrentExtraAddons(enabled) }.let {}

    fun setKinogoBaseUrl(url: String) =
        viewModelScope.launch { store.setKinogoBaseUrl(url) }.let {}

    fun setRezkaBaseUrl(url: String) =
        viewModelScope.launch { store.setRezkaBaseUrl(url) }.let {}

    fun setProxyEnabled(enabled: Boolean) =
        viewModelScope.launch { store.setProxyEnabled(enabled) }.let {}

    fun setProxyHost(host: String) =
        viewModelScope.launch { store.setProxyHost(host) }.let {}

    fun setProxyPort(port: Int) =
        viewModelScope.launch { store.setProxyPort(port) }.let {}

    fun setProxyType(type: String) =
        viewModelScope.launch { store.setProxyType(type) }.let {}

    fun setProxyUser(user: String) =
        viewModelScope.launch { store.setProxyUser(user) }.let {}

    fun setProxyPass(pass: String) =
        viewModelScope.launch { store.setProxyPass(pass) }.let {}

    val proxyTypes: List<Pair<String, String>> = listOf(
        SettingsDataStore.PROXY_TYPE_HTTP to "HTTP",
        SettingsDataStore.PROXY_TYPE_SOCKS to "SOCKS5",
    )

    fun setCinemaTorrentWifiOnly(enabled: Boolean) =
        viewModelScope.launch { store.setCinemaTorrentWifiOnly(enabled) }.let {}

    /** All Home sections (key → title); the user enables/orders a subset of these. */
    val allHomeSections: List<Pair<String, String>> = SettingsDataStore.HOME_SECTIONS

    fun toggleHomeSection(key: String, enabled: Boolean) {
        val current = settings.value?.homeOrder ?: return
        val next = if (enabled) {
            if (key in current) current else current + key
        } else {
            current - key
        }
        if (next.isNotEmpty()) viewModelScope.launch { store.setHomeOrder(next) }
    }

    fun moveHomeSection(key: String, up: Boolean) {
        val current = (settings.value?.homeOrder ?: return).toMutableList()
        val i = current.indexOf(key)
        if (i < 0) return
        val j = if (up) i - 1 else i + 1
        if (j !in current.indices) return
        current[i] = current[j].also { current[j] = current[i] }
        viewModelScope.launch { store.setHomeOrder(current) }
    }
}
