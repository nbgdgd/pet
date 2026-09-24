package com.aniblaze.app.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aniblaze.aggregator.PersistedTorrent
import com.aniblaze.aggregator.TorrentStreamStage
import com.aniblaze.aggregator.TorrentStreamStatus
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.OledBlack
import com.aniblaze.ui.theme.Surface1
import com.aniblaze.ui.theme.TextPrimary
import com.aniblaze.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun TorrentDownloadsScreen(viewModel: TorrentDownloadsViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val persisted by viewModel.persisted.collectAsStateWithLifecycle()
    val live = status.stage != TorrentStreamStage.IDLE
    Column(
        Modifier.fillMaxSize().background(OledBlack).padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text("Торрент-загрузки", color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "Активная загрузка продолжает работать после выхода из плеера",
            color = TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )
        if (!live && persisted == null) {
            Surface(
                color = Surface1,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.Downloading, contentDescription = null, tint = AccentOrange)
                    Text("Активных загрузок нет", color = TextPrimary, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        "Откройте фильм и выберите «Через торрент»",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        } else {
            TorrentDownloadCard(
                status = status,
                persisted = persisted,
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onStop = viewModel::stop,
            )
        }
    }
}

@Composable
private fun TorrentDownloadCard(
    status: TorrentStreamStatus,
    persisted: PersistedTorrent?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val live = status.stage != TorrentStreamStage.IDLE
    // Live telemetry wins; otherwise show the persisted (paused) torrent so
    // the user sees WHICH release waits and WHERE it came from.
    val fileName = status.fileName.ifBlank { persisted?.fileName.orEmpty() }
    val source = status.source.ifBlank { persisted?.source.orEmpty() }
    val quality = status.quality.ifBlank { persisted?.quality.orEmpty() }
    val infoHash = status.infoHash.ifBlank { persisted?.infoHash.orEmpty() }
    val progress = if (status.totalBytes > 0L) {
        (status.downloadedBytes.toFloat() / status.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Downloading, contentDescription = null, tint = AccentOrange)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        fileName.ifBlank { torrentStageLabel(status.stage) },
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sourceLabel = torrentSourceLabel(source)
                    Text(
                        buildString {
                            append(if (live) torrentStageLabel(status.stage) else "Загрузка на паузе")
                            if (sourceLabel.isNotBlank()) {
                                append(" · ")
                                append(sourceLabel)
                            }
                            if (quality.isNotBlank()) {
                                append(" · ")
                                append(quality.substringBefore(" · "))
                            }
                        },
                        color = if (status.stage == TorrentStreamStage.ERROR) Color(0xFFFF6B6B) else TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            // Какая именно раздача используется и откуда она получена.
            if (fileName.isNotBlank() || source.isNotBlank() || infoHash.isNotBlank()) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (fileName.isNotBlank()) {
                        TorrentMetaRow("Раздача", fileName)
                    }
                    if (source.isNotBlank()) {
                        TorrentMetaRow("Источник", torrentSourceLabel(source))
                    }
                    if (infoHash.isNotBlank()) {
                        TorrentMetaRow("Hash", infoHash.take(16) + "…")
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(7.dp),
                color = AccentOrange,
                trackColor = Color.White.copy(alpha = 0.1f),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTorrentBytes(status.downloadedBytes) + " / " + formatTorrentBytes(status.totalBytes), color = TextSecondary, fontSize = 12.sp)
                Text("${(progress * 100).toInt()}%", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(formatTorrentRate(status.downloadBytesPerSecond), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("${status.peers} пиров", color = TextSecondary, fontSize = 14.sp)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (status.stage == TorrentStreamStage.PAUSED || (!live && persisted != null)) {
                    Button(onClick = onResume) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text("Продолжить", modifier = Modifier.padding(start = 6.dp))
                    }
                } else if (live && status.stage != TorrentStreamStage.ERROR) {
                    Button(onClick = onPause) {
                        Icon(Icons.Filled.Pause, contentDescription = null)
                        Text("Пауза", modifier = Modifier.padding(start = 6.dp))
                    }
                }
                OutlinedButton(
                    onClick = onStop,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF7A70)),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text("Остановить и удалить", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun TorrentMetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            "$label: ",
            color = TextSecondary,
            fontSize = 12.sp,
        )
        Text(
            value,
            color = TextPrimary,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

    internal fun torrentStageLabel(stage: TorrentStreamStage): String = when (stage) {    TorrentStreamStage.IDLE -> "Нет загрузки"
    TorrentStreamStage.SEARCHING -> "Поиск раздачи"
    TorrentStreamStage.METADATA -> "Получение метаданных"
    TorrentStreamStage.CONNECTING -> "Подключение к пирам"
    TorrentStreamStage.BUFFERING -> "Начальная буферизация"
    TorrentStreamStage.STREAMING -> "Загрузка и стриминг"
    TorrentStreamStage.PAUSED -> "Загрузка на паузе"
    TorrentStreamStage.ERROR -> "Ошибка: попробуйте другую раздачу"
}

internal fun torrentSourceLabel(source: String): String = source.trim().ifBlank { "Torrent" }

internal fun formatTorrentBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 Б"
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.forLanguageTag("ru"), "%.1f ГБ", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.forLanguageTag("ru"), "%.1f МБ", bytes / (1024.0 * 1024.0))
    else -> "${bytes / 1024L} КБ"
}

internal fun formatTorrentRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024L * 1024L -> String.format(Locale.forLanguageTag("ru"), "%.1f МБ/с", bytesPerSecond / (1024.0 * 1024.0))
    bytesPerSecond >= 1024L -> "${bytesPerSecond / 1024L} КБ/с"
    else -> "$bytesPerSecond Б/с"
}
