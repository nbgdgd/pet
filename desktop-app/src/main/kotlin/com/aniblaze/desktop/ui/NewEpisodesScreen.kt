package com.aniblaze.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.desktop.AppSettings

/**
 * Titles the user follows that gained an episode since they last opened them.
 *
 * The flags are raised by the background notifier, which counts episodes AT THE
 * SOURCE — an episode appears there once its dub is actually up, which is roughly a
 * day after the original broadcast. Opening a title clears its flag.
 */
@Composable
fun NewEpisodesScreen(
    settings: AppSettings,
    /** Обработчик пункта «Открыть в новом окне» в меню карточки; null — пункта нет. */
    onOpenInNewWindow: ((Anime) -> Unit)? = { com.aniblaze.desktop.DetachedTitles.openTitle(it) },
    onOpen: (Anime) -> Unit,
) {
    val state by settings.state.collectAsState()
    val items = remember(state) { settings.newEpisodeTitles() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Вышли новые серии", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (items.isEmpty()) "Пока ничего нового — следим за избранным и историей"
                    else "Новых серий: ${items.size}",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconButton(onClick = { items.forEach { settings.clearNewEpisode(it.id) } }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Отметить все просмотренными", tint = AccentOrange)
            }
        }
        PosterGrid(
            items,
            isLoading = false,
            Modifier.weight(1f),
            onOpenInNewWindow = onOpenInNewWindow,
            onClick = onOpen,
        )
    }
}
