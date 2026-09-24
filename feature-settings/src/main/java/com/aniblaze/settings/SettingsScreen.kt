package com.aniblaze.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aniblaze.ui.components.SectionHeader
import com.aniblaze.ui.components.glass
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.AccentPurple
import com.aniblaze.ui.theme.OledBlack
import com.aniblaze.ui.theme.Surface3
import com.aniblaze.ui.theme.TextPrimary
import com.aniblaze.ui.theme.TextSecondary

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val current = settings ?: return

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        SectionHeader("Воспроизведение")
        ToggleRow(
            title = "Фоновое воспроизведение",
            subtitle = "Продолжать звук, когда приложение свёрнуто",
            checked = current.preferBackgroundPlayback,
            onCheckedChange = viewModel::setBackgroundPlayback,
        )
        ToggleRow(
            title = "Автопереход к следующей серии",
            subtitle = "Автоматически запускать следующую серию",
            checked = current.autoNextSegment,
            onCheckedChange = viewModel::setAutoNext,
        )
        ToggleRow(
            title = "Пропускать опенинг",
            subtitle = "Автоматически, только когда для серии есть точный интервал",
            checked = current.autoSkipOpening,
            onCheckedChange = viewModel::setAutoSkipOpening,
        )
        ToggleRow(
            title = "Пропускать эндинг",
            subtitle = "Автоматически, только когда для серии есть точный интервал",
            checked = current.autoSkipEnding,
            onCheckedChange = viewModel::setAutoSkipEnding,
        )
        ToggleRow(
            title = "Приоритет озвучки",
            subtitle = "Дубляж → Studio Band → AniLibria. Ручной выбор для тайтла важнее.",
            checked = current.voiceoverPriorityEnabled,
            onCheckedChange = viewModel::setVoiceoverPriority,
        )
        ToggleRow(
            title = "Случайное аниме после последней серии",
            subtitle = "Когда серии кончились — прыжок на случайный тайтл из трендов/«сейчас смотрят»/рандома, без уже просмотренного. Переключатель есть и в плеере.",
            checked = current.autoSwitchRandom,
            onCheckedChange = viewModel::setAutoSwitchRandom,
        )
        ToggleRow(
            title = "Картинка в картинке",
            subtitle = "Сворачивать в плавающее окно поверх других приложений",
            checked = current.pictureInPicture,
            onCheckedChange = viewModel::setPictureInPicture,
        )

        ToggleRow(
            title = "Комментарии поверх видео",
            subtitle = "Настоящие реплики зрителей из обсуждения тайтла, изредка проплывают по верху кадра. Чужие серии и спойлеры не показываются. Прячутся, пока открыта панель управления.",
            checked = current.danmakuEnabled,
            onCheckedChange = viewModel::setDanmaku,
        )
        if (current.danmakuEnabled) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                viewModel.danmakuRates.forEach { (key, label) ->
                    val active = current.danmakuRate == key
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) AccentOrange else Surface3)
                            .clickable { viewModel.setDanmakuRate(key) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (active) OledBlack else TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        ToggleRow(
            title = "Чат в плеере",
            subtitle = "Адаптивная панель с настоящими русскими комментариями парсера: сбоку на широком экране и снизу на телефоне.",
            checked = current.chatEnabled,
            onCheckedChange = viewModel::setChatEnabled,
        )
        if (current.chatEnabled) {
            ToggleRow(
                title = "Скрывать спойлеры в чате",
                subtitle = "Подозрительный текст скрыт до нажатия. Комментарии будущих серий не попадают в чат.",
                checked = current.chatHideSpoilers,
                onCheckedChange = viewModel::setChatHideSpoilers,
            )
        }

        SectionHeader("Кино · Lampa")
        Text(
            "Балансер используется для фильмов и сериалов. Если он не найдёт поток, приложение автоматически попробует cdnvideohub, kodik и другие живые источники.",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            viewModel.lampaBalancers.forEach { balancer ->
                val active = current.lampaBalancer == balancer
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) AccentOrange else Surface3)
                        .clickable { viewModel.setLampaBalancer(balancer) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        balancer,
                        color = if (active) OledBlack else TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        OutlinedTextField(
            value = current.lampaPluginUrl,
            onValueChange = viewModel::setLampaPluginUrl,
            label = { Text("URL плагина Lampa") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )

        ToggleRow(
            title = "Торрент-резерв для кино",
            subtitle = "Включается только когда обычные киноисточники не дали поток. P2P может передавать части файла другим участникам.",
            checked = current.cinemaTorrentFallback,
            onCheckedChange = viewModel::setCinemaTorrentFallback,
        )
        if (current.cinemaTorrentFallback) {
            ToggleRow(
                title = "Торрент только по Wi-Fi",
                subtitle = "Не расходовать мобильный трафик и не запускать P2P в тарифицируемой сети",
                checked = current.cinemaTorrentWifiOnly,
                onCheckedChange = viewModel::setCinemaTorrentWifiOnly,
            )
            OutlinedTextField(
                value = current.cinemaTorrentAddonUrl,
                onValueChange = viewModel::setCinemaTorrentAddonUrl,
                label = { Text("URL Stremio-аддона") },
                placeholder = { Text("https://torrentio.strem.fun/manifest.json") },
                supportingText = {
                    Text("Например Torrentio. AniBlaze получает infoHash, а видео передаёт локальный движок")
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            ToggleRow(
                title = "Резервные торрент-индексы",
                subtitle = "Параллельно опрашивать Comet и MediaFusion: у разных индексов разные раздачи, один заблокированный хост — не приговор",
                checked = current.cinemaTorrentExtraAddons,
                onCheckedChange = viewModel::setCinemaTorrentExtraAddons,
            )
        }

        SectionHeader("Кино · Зеркала")
        Text(
            "Домены Kinogo и Rezka регулярно блокируют — укажите рабочее зеркало, если каталог кино перестал открываться",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        OutlinedTextField(
            value = current.kinogoBaseUrl,
            onValueChange = viewModel::setKinogoBaseUrl,
            label = { Text("Зеркало Kinogo") },
            placeholder = { Text("https://kinogo.ec") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
        OutlinedTextField(
            value = current.rezkaBaseUrl,
            onValueChange = viewModel::setRezkaBaseUrl,
            label = { Text("Зеркало HDrezka") },
            placeholder = { Text("https://rezka.ag") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )

        SectionHeader("Сеть · Прокси")
        Text(
            "Если провайдер блокирует адреса парсеров (SNI/IP-фильтр), пустите HTTP-трафик приложения через свой прокси. P2P-трафик торрентов идёт напрямую.",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        ToggleRow(
            title = "Использовать прокси",
            subtitle = "Касается каталогов, поиска, рецензий и торрент-индексов",
            checked = current.proxyEnabled,
            onCheckedChange = viewModel::setProxyEnabled,
        )
        if (current.proxyEnabled) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                viewModel.proxyTypes.forEach { (key, label) ->
                    val active = current.proxyType == key
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) AccentOrange else Surface3)
                            .clickable { viewModel.setProxyType(key) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            label,
                            color = if (active) OledBlack else TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = current.proxyHost,
                onValueChange = viewModel::setProxyHost,
                label = { Text("Хост прокси") },
                placeholder = { Text("127.0.0.1") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            OutlinedTextField(
                value = current.proxyPort.takeIf { it > 0 }?.toString().orEmpty(),
                onValueChange = { viewModel.setProxyPort(it.toIntOrNull() ?: 0) },
                label = { Text("Порт") },
                placeholder = { Text("1080") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            OutlinedTextField(
                value = current.proxyUser,
                onValueChange = viewModel::setProxyUser,
                label = { Text("Логин (необязательно)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            OutlinedTextField(
                value = current.proxyPass,
                onValueChange = viewModel::setProxyPass,
                label = { Text("Пароль (необязательно)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        SectionHeader("Сетка постеров")
        Text(
            "Сколько колонок показывать в каталоге, избранном и истории",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        GridColumnsPicker(
            selected = current.gridColumns,
            onSelect = viewModel::setGridColumns,
        )

        SectionHeader("Масштаб интерфейса")
        Text(
            "Размер текста во всём приложении; размещение элементов продолжает подстраиваться под ширину экрана",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        FontScalePicker(
            selected = current.fontScale,
            onSelect = viewModel::setFontScale,
        )

        SectionHeader("Главный экран")
        Text(
            "Включите нужные разделы и расставьте порядок стрелками",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        val order = current.homeOrder
        order.forEachIndexed { index, key ->
            val title = viewModel.allHomeSections.firstOrNull { it.first == key }?.second ?: key
            HomeSectionRow(
                title = title,
                enabled = true,
                canUp = index > 0,
                canDown = index < order.size - 1,
                onToggle = { viewModel.toggleHomeSection(key, false) },
                onUp = { viewModel.moveHomeSection(key, true) },
                onDown = { viewModel.moveHomeSection(key, false) },
            )
        }
        viewModel.allHomeSections.filter { it.first !in order }.forEach { (key, title) ->
            HomeSectionRow(
                title = title,
                enabled = false,
                canUp = false,
                canDown = false,
                onToggle = { viewModel.toggleHomeSection(key, true) },
                onUp = {},
                onDown = {},
            )
        }
    }
}

@Composable
private fun GridColumnsPicker(selected: Int, onSelect: (Int) -> Unit) {
    // Match desktop choices. A legacy saved value of 2 is treated as Auto by layout.
    val options = listOf(0 to "Авто", 3 to "3", 4 to "4", 5 to "5", 6 to "6")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) AccentOrange else Surface3)
                    .clickable { onSelect(value) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (active) OledBlack else TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun FontScalePicker(selected: Float, onSelect: (Float) -> Unit) {
    val options = listOf(0.9f to "90%", 1f to "100%", 1.1f to "110%", 1.25f to "125%", 1.4f to "140%")
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            val active = kotlin.math.abs(value - selected) < 0.01f
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) AccentOrange else Surface3)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (active) OledBlack else TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun HomeSectionRow(
    title: String,
    enabled: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onToggle: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .glass(16.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = if (enabled) TextPrimary else TextSecondary,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        if (enabled) {
            IconButton(onClick = onUp, enabled = canUp) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Выше",
                    tint = if (canUp) TextPrimary else TextSecondary.copy(alpha = 0.3f),
                )
            }
            IconButton(onClick = onDown, enabled = canDown) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Ниже",
                    tint = if (canDown) TextPrimary else TextSecondary.copy(alpha = 0.3f),
                )
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = AccentOrange,
                checkedTrackColor = AccentPurple,
            ),
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .glass(16.dp)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Text(subtitle, color = TextSecondary, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AccentOrange,
                checkedTrackColor = AccentPurple,
            ),
        )
    }
}
