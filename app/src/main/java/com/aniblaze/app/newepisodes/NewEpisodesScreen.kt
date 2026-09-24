package com.aniblaze.app.newepisodes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.aniblaze.ui.components.glass
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.TextPrimary
import com.aniblaze.ui.theme.TextSecondary

/**
 * «Вышли новые серии» — отслеживаемые тайтлы, у которых прибавилась серия с тех
 * пор, как их последний раз открывали.
 *
 * Пометку поднимает обход, считающий серии У ИСТОЧНИКА: серия появляется там, когда
 * её озвучка уже выложена, а это примерно сутки после оригинального эфира. Открытие
 * тайтла пометку снимает.
 */
@Composable
fun NewEpisodesScreen(
    onAnimeClick: (String) -> Unit,
    viewModel: NewEpisodesViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Вышли новые серии",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (items.isEmpty()) {
                        "Пока ничего нового — следим за избранным и историей"
                    } else {
                        "Новых серий: ${items.size}"
                    },
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (refreshing) {
                // Индикатор занимает место кнопки обновления, а не добавляет ряд:
                // иначе шапка дёргается по высоте на каждый обход.
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = AccentOrange,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                IconButton(onClick = viewModel::refresh) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Проверить новые серии",
                        tint = AccentOrange,
                    )
                }
            }
            if (items.isNotEmpty()) {
                IconButton(onClick = viewModel::markAllRead) {
                    Icon(
                        Icons.Filled.DoneAll,
                        contentDescription = "Отметить все просмотренными",
                        tint = AccentOrange,
                    )
                }
            }
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Новые серии появятся здесь, как только они выйдут у отслеживаемых тайтлов.",
                    color = TextSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(items, key = { it.id }) { item ->
                NewEpisodeCard(
                    item = item,
                    onOpen = {
                        // Порядок важен: сначала снимаем пометку, потом уходим на
                        // экран тайтла — иначе возврат назад показывает её снова.
                        viewModel.markRead(item.id)
                        onAnimeClick(item.id)
                    },
                    onDismiss = { viewModel.markRead(item.id) },
                )
            }
        }
    }
}

/** Та же миниатюра 2:3, что и в расписании: строка списка остаётся строкой. */
private val ThumbWidth = 56.dp

@Composable
private fun NewEpisodeCard(
    item: NewEpisodeItem,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .glass(14.dp)
            .clickable(onClick = onOpen)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.poster,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(ThumbWidth)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.episodeLabel,
                color = AccentOrange,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Filled.DoneAll,
                contentDescription = "Отметить просмотренным",
                tint = TextSecondary,
            )
        }
    }
}
